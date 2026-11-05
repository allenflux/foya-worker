"""Render caller-supplied copy with real fonts before any image sampling.

The generated visual never receives the copy. This module returns an empty
visual canvas and a separate full-size canvas whose lower panel contains the
exact supplied copy. No LLM, OCR, translation, trimming, or normalization occurs.
"""
from __future__ import annotations

import json
import math
import re
import unicodedata
from pathlib import Path

import regex
from PIL import Image, ImageColor, ImageDraw, ImageFont
from fontTools.ttLib import TTFont


class LayoutError(ValueError):
    def __init__(self, code, message, details=None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.details = details or {}

    def as_dict(self):
        return {"code": self.code, "message": self.message, "details": self.details}

    def __str__(self):
        return json.dumps(self.as_dict(), ensure_ascii=False)


def _error(code, message, **details):
    raise LayoutError(code, message, details)


def _integer(value, name, low, high):
    if type(value) is not int or not low <= value <= high:
        _error("INVALID_" + name.upper(), f"{name} 必须是 {low}–{high} 范围内的整数。")
    return value


def _opaque_color(value, field):
    try:
        if not isinstance(value, str):
            raise ValueError("not a string")
        rgba = ImageColor.getcolor(value, "RGBA")
        if rgba[3] != 255:
            raise ValueError("transparent text/panel is not supported")
        return rgba[:3]
    except (ValueError, TypeError) as exc:
        raise LayoutError("INVALID_COLOR", f"{field} 必须是有效的不透明颜色。") from exc


def _luminance(rgb):
    def linear(value):
        value /= 255.0
        return value / 12.92 if value <= 0.04045 else ((value + 0.055) / 1.055) ** 2.4
    return sum(weight * linear(value) for weight, value in zip((0.2126, 0.7152, 0.0722), rgb))


def _font(path, size, text):
    path = Path(path)
    if not path.is_file():
        _error("FONT_UNREADABLE", "字体文件不存在，请先安装随包中文字体。", font=path.name)
    try:
        with TTFont(str(path), fontNumber=0, lazy=True) as font_file:
            cmap = font_file.getBestCmap() or {}
            missing = sorted({character for character in text if character not in "\r\n"
                              and (ord(character) not in cmap or cmap[ord(character)] == ".notdef")})
        if missing:
            _error("FONT_MISSING_GLYPHS", "所选字体缺少文案字形，不能替换、删字或使用方框。",
                   missing_characters="".join(missing),
                   needs_adjustment="请选择完整覆盖这些字形的字体。")
        return ImageFont.truetype(str(path), size=size)
    except LayoutError:
        raise
    except Exception as exc:
        raise LayoutError("FONT_UNREADABLE", "字体无法加载。", {"font": path.name}) from exc


def _width(text, font):
    box = font.getbbox(text, anchor="lt")
    return max(float(font.getlength(text)), float(box[2] - box[0]))


def _tokens(paragraph):
    """Keep Unicode grapheme clusters and non-CJK words together."""
    tokens = []
    current_word = ""
    for cluster in regex.findall(r"\X", paragraph):
        is_cjk = bool(regex.search(r"\p{Han}|\p{Hiragana}|\p{Katakana}|\p{Hangul}", cluster))
        is_word = not is_cjk and bool(regex.fullmatch(r"[\p{L}\p{M}\p{N}]+", cluster))
        if is_word:
            current_word += cluster
        elif current_word and cluster in ("'", "’", "-", "‑"):
            current_word += cluster
        else:
            if current_word:
                tokens.append(current_word)
                current_word = ""
            tokens.append(cluster)
    if current_word:
        tokens.append(current_word)
    assert "".join(tokens) == paragraph
    return tokens


_OPENING = set("（([【《〈「『“‘")
_CLOSING = set("，。！？；：、）)]】》〉」』”’!?;:,%％")


def _wrap_paragraph(paragraph, font, available_width):
    lines = []
    current = []
    for token in _tokens(paragraph):
        if _width(token, font) > available_width:
            _error("TEXT_TOO_LONG", "完整单词或字形超过文字区宽度。",
                   needs_adjustment="请增加目标宽度，或由调用方调整字号、字体或完整文案。")
        candidate = "".join(current) + token
        if current and _width(candidate, font) > available_width:
            carry = []
            while current and current[-1] in _OPENING:
                carry.insert(0, current.pop())
            if token in _CLOSING and current:
                carry.insert(0, current.pop())
            if carry:
                candidate_carry = "".join(carry) + token
                if not current or _width(candidate_carry, font) > available_width:
                    _error("TEXT_TOO_LONG", "标点与完整单词在当前字号下无法安全换行。",
                           needs_adjustment="请增加目标宽度，或由调用方调整字号、字体或完整文案。")
            lines.append("".join(current))
            current = carry
        current.append(token)
    lines.append("".join(current))
    assert "".join(lines) == paragraph
    return lines


def _wrap(text, font, available_width):
    lines = []
    for paragraph in re.split(r"\r\n|\r|\n", text):
        lines.extend(_wrap_paragraph(paragraph, font, available_width))
    return lines


def _line_advance(font, lines):
    ascent, descent = font.getmetrics()
    ink_height = max((font.getbbox(line, anchor="lt")[3] - font.getbbox(line, anchor="lt")[1]
                      for line in lines), default=0)
    return max(ascent + descent, ink_height) + max(3, math.ceil(font.size * 0.15))


def prepare_layout(copy_text, design_prompt, font_path, width=1088, height=1440,
                   count=1, first_line_title=True, title_size=64, body_size=34,
                   text_color="#3b2415", panel_color="#fff8ed"):
    """Validate exact text and produce separate generation and typeset canvases."""
    width = _integer(width, "width", 512, 4096)
    height = _integer(height, "height", 512, 4096)
    count = _integer(count, "count", 1, 4)
    title_size = _integer(title_size, "title_size", 8, 256)
    body_size = _integer(body_size, "body_size", 8, 128)
    if type(first_line_title) is not bool:
        _error("INVALID_TITLE_MODE", "first_line_title 必须为布尔值。")
    if not isinstance(copy_text, str) or not any(not ch.isspace() for ch in copy_text):
        _error("COPY_REQUIRED", "请在独立文案输入框填写完整上图原文；不会自动补写文案。")
    if len(copy_text) > 12000:
        _error("TEXT_TOO_LONG", "文案超过当前布局的可处理范围。",
               needs_adjustment="请由调用方调整完整文案或目标尺寸。")
    unsupported = {ch for ch in copy_text if
                   (unicodedata.category(ch) in {"Cc", "Cf", "Cs", "Zl", "Zp"}) and ch not in "\r\n"}
    if unsupported:
        _error("UNSUPPORTED_TEXT_CONTROL", "文案含不能可靠排版的控制字符，不会自动删除。",
               codepoints=[f"U+{ord(ch):04X}" for ch in sorted(unsupported)])
    if not isinstance(design_prompt, str) or len(design_prompt) > 8000:
        _error("INVALID_DESIGN_PROMPT", "设计要求必须是不超过 8000 字符的视觉描述。")
    if re.search(r"(?:写上|寫上|添加文字|加入文字|标题(?:为|是)|標題(?:為|是))\s*[\"“'「『]"
                 r"|(?:add\s+(?:the\s+)?text|write)\s*[:：]?\s*[\"“']", design_prompt, re.I):
        _error("COPY_IN_DESIGN_PROMPT", "请将上图文案放入独立文案框；设计要求只填写视觉效果。")
    foreground = _opaque_color(text_color, "text_color")
    background = _opaque_color(panel_color, "panel_color")
    low, high = sorted((_luminance(foreground), _luminance(background)))
    contrast = (high + 0.05) / (low + 0.05)
    if contrast < 3:
        _error("LOW_TEXT_CONTRAST", "文字与底板颜色过于接近，无法保证清晰。",
               needs_adjustment="请调整文字颜色或底板颜色。")
    title_font = _font(font_path, title_size, copy_text)
    body_font = _font(font_path, body_size, copy_text)
    if first_line_title:
        parts = re.split(r"(\r\n|\r|\n)", copy_text, maxsplit=1)
        title_text = parts[0]
        body_text = parts[2] if len(parts) == 3 else None
    else:
        title_text, body_text = None, copy_text
    margin = max(24, round(min(width, height) * 0.04))
    available_width = width - margin * 2
    title_lines = _wrap(title_text, title_font, available_width) if title_text is not None else []
    body_lines = _wrap(body_text, body_font, available_width) if body_text is not None else []
    title_advance = _line_advance(title_font, title_lines)
    body_advance = _line_advance(body_font, body_lines)
    gap = max(10, round(margin * 0.4)) if title_lines and body_lines else 0
    text_height = len(title_lines) * title_advance + gap + len(body_lines) * body_advance
    required_panel = max(math.ceil(height * 0.22), text_height + margin * 2)
    visual_height = math.floor((height - required_panel) / 32) * 32
    if (height - visual_height > math.floor(height * 0.55) or visual_height < 256):
        _error("TEXT_TOO_LONG", "完整文案在当前尺寸和字号下无法放入文字区。",
               needs_adjustment="请增加目标尺寸，或由调用方调整字号、字体或完整文案。系统不会删减、改写或缩小字号。",
               measured_text_height=text_height, maximum_panel_height=math.floor(height * 0.55))
    sampling_width = math.floor(width / 32) * 32
    visual_x = (width - sampling_width) // 2
    visual = Image.new("RGB", (sampling_width, visual_height), background)
    typeset = Image.new("RGB", (width, height), background)
    draw = ImageDraw.Draw(typeset)
    y = visual_height + margin
    measured_lines = []
    for role, lines, font, advance in (
            ("title", title_lines, title_font, title_advance),
            ("body", body_lines, body_font, body_advance)):
        for line in lines:
            box = font.getbbox(line, anchor="lt")
            line_width = _width(line, font)
            ink_height = box[3] - box[1]
            if line_width > available_width or y + ink_height > height - margin:
                _error("TEXT_TOO_LONG", "文字测量超出安全区域，请由调用方调整字号或尺寸。")
            draw.text((margin - box[0], y - box[1]), line, font=font,
                      fill=foreground, anchor="lt")
            measured_lines.append({"text": line, "role": role, "x": margin, "y": y,
                                   "width": line_width, "height": ink_height, "advance": advance})
            y += advance
        if role == "title" and title_lines:
            y += gap
    # copy_text is deliberately absent from this prompt and visual canvas.
    prompt = ("<image1> 仅决定无字主视觉的尺寸。使用 <image2> 的主要宣传素材设计画面，"
              "保持人物、产品及品牌特征，构图完整、主体清楚。视觉要求：" + design_prompt
              + "。本次只生成无字底图，画面内不绘制任何新增文字、字母、数字、标题、标语或水印；"
              "所有上图文字将在图像生成完成后由字体程序排入独立区域。")
    manifest = {"copy_text": copy_text, "width": width, "height": height, "count": count,
                "font": Path(font_path).name, "font_rendering": "real_font_glyphs",
                "checks": {"copy": "passed", "glyph_coverage": "passed", "layout": "passed",
                           "background_incidental_text": "requires_review"},
                "visual_acceptance": "requires_review"}
    return {"copy_text": copy_text, "prompt": prompt, "visual": visual,
            "typeset_canvas": typeset, "count": count, "visual_x": visual_x,
            "visual_height": visual_height, "margin": margin,
            "title_lines": title_lines, "body_lines": body_lines,
            "measured_lines": measured_lines, "manifest": manifest}

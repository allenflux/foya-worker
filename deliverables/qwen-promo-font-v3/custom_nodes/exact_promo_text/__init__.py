"""One focused ComfyUI node for exact real-font copy layout.

Qwen sampling, batch repetition, final image compositing and saving stay native.
"""
from __future__ import annotations

import json
from pathlib import Path

from .layout import LayoutError, prepare_layout

FONT_DIRECTORY = Path(__file__).resolve().parent / "fonts"


def _available_fonts():
    return sorted(path.name for path in FONT_DIRECTORY.iterdir()
                  if path.is_file() and path.suffix.lower() in {".otf", ".ttf", ".ttc"}) if FONT_DIRECTORY.is_dir() else []


class ExactPromoTextPrepare:
    CATEGORY = "promo/exact_text"
    RETURN_TYPES = ("STRING", "IMAGE", "IMAGE", "INT", "INT", "STRING")
    RETURN_NAMES = ("background_prompt", "sampling_canvas", "typeset_canvas_batch",
                    "count", "visual_x", "copy_manifest")
    FUNCTION = "prepare"

    @classmethod
    def INPUT_TYPES(cls):
        return {"required": {
            "material": ("IMAGE",),
            "copy_text": ("STRING", {"forceInput": True, "multiline": True}),
            "design_prompt": ("STRING", {"forceInput": True, "multiline": True}),
            "font": (_available_fonts() or ["请先安装随包中文字体"],),
            "width": ("INT", {"default": 1088, "min": 512, "max": 4096, "step": 1}),
            "height": ("INT", {"default": 1440, "min": 512, "max": 4096, "step": 1}),
            "count": ("INT", {"default": 1, "min": 1, "max": 4}),
            "first_line_title": ("BOOLEAN", {"default": True}),
            "title_size": ("INT", {"default": 64, "min": 8, "max": 256}),
            "body_size": ("INT", {"default": 34, "min": 8, "max": 128}),
            "text_color": ("STRING", {"default": "#3b2415"}),
            "panel_color": ("STRING", {"default": "#fff8ed"}),
        }}

    @classmethod
    def IS_CHANGED(cls, **kwargs):
        return float("nan")

    def prepare(self, material, copy_text, design_prompt, font, width=1088, height=1440,
                count=1, first_line_title=True, title_size=64, body_size=34,
                text_color="#3b2415", panel_color="#fff8ed"):
        import numpy as np
        import torch
        shape = getattr(material, "shape", ())
        if (len(shape) != 4 or shape[0] < 1 or shape[1] < 1 or shape[2] < 1
                or shape[3] not in (3, 4)):
            raise LayoutError("MATERIAL_REQUIRED", "请提供可读取的宣传素材，不能转为纯文生图。")
        try:
            array = material.detach().cpu().numpy()
            if not np.isfinite(array).all():
                raise ValueError("non-finite material")
        except Exception as exc:
            raise LayoutError("MATERIAL_UNREADABLE", "宣传素材数据无效，请重新上传。") from exc
        if not isinstance(font, str) or Path(font).name != font or font not in _available_fonts():
            raise LayoutError("FONT_UNREADABLE", "未找到所选字体，请安装随包字体后重启 ComfyUI。")
        context = prepare_layout(copy_text, design_prompt, FONT_DIRECTORY / font,
                                 width, height, count, first_line_title, title_size,
                                 body_size, text_color, panel_color)
        def tensor(image):
            return torch.from_numpy(np.asarray(image, dtype=np.float32).copy() / 255.0).unsqueeze(0)
        visual_tensor = tensor(context["visual"])
        typeset_batch = tensor(context["typeset_canvas"]).repeat(count, 1, 1, 1)
        manifest = json.dumps(context["manifest"], ensure_ascii=False, allow_nan=False)
        return {"ui": {"text": [manifest]},
                "result": (context["prompt"], visual_tensor, typeset_batch, count,
                           context["visual_x"], manifest)}


NODE_CLASS_MAPPINGS = {"ExactPromoTextPrepare": ExactPromoTextPrepare}
NODE_DISPLAY_NAME_MAPPINGS = {"ExactPromoTextPrepare": "宣传图 · 真实字体排版与采样前校验"}

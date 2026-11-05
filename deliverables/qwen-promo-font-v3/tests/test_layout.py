import importlib.util
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / "custom_nodes" / "exact_promo_text"
spec = importlib.util.spec_from_file_location("exact_v3_layout", PACKAGE / "layout.py")
layout = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = layout
spec.loader.exec_module(layout)
FONT = PACKAGE / "fonts" / "NotoSansCJKsc-Regular.otf"


class ExactLayoutTests(unittest.TestCase):
    def make(self, text="咖啡还是我的好", **kwargs):
        return layout.prepare_layout(text, kwargs.pop("design_prompt", "自然光，咖啡店氛围。"),
                                     kwargs.pop("font_path", FONT), **kwargs)

    def assert_error(self, code, fn):
        with self.assertRaises(layout.LayoutError) as raised:
            fn()
        self.assertEqual(raised.exception.code, code)
        self.assertEqual(raised.exception.as_dict()["code"], code)

    def test_actual_supplied_phrase_renders_only_that_phrase(self):
        context = self.make()
        self.assertEqual(context["copy_text"], "咖啡还是我的好")
        self.assertEqual(context["manifest"]["copy_text"], "咖啡还是我的好")
        self.assertEqual(context["title_lines"], ["咖啡还是我的好"])
        self.assertEqual(context["body_lines"], [])
        self.assertEqual([line["text"] for line in context["measured_lines"]], ["咖啡还是我的好"])
        self.assertNotIn("咖啡还是我的好", context["prompt"])

    def test_empty_copy_stops_before_generation(self):
        for text in ("", " \r\n ", None):
            self.assert_error("COPY_REQUIRED", lambda text=text: self.make(text))

    def test_chinese_traditional_english_and_mixed_are_not_converted(self):
        for text in ("简体中文，保持原样。", "繁體中文，保持原樣。", "Coffee & Croissant 2026!",
                     "简體 Mixed EN 2026，原樣保留。"):
            with self.subTest(text=text):
                context = self.make(text)
                self.assertEqual(context["manifest"]["copy_text"], text)
                self.assertEqual("".join(context["title_lines"]), text)

    def test_spaces_crlf_and_fullwidth_forms_preserved_exactly(self):
        text = "  原樣標題  \r\n Body  EN：１２３ \r\n\r\n末尾  "
        context = self.make(text, first_line_title=True, title_size=44, body_size=24)
        self.assertEqual(context["manifest"]["copy_text"], text)
        self.assertEqual("".join(context["title_lines"]), "  原樣標題  ")
        self.assertEqual(context["body_lines"], [" Body  EN：１２３ ", "", "末尾  "])

    def test_unicode_combining_cluster_remains_whole(self):
        text = "Cafe\u0301 crème"
        tokens = layout._tokens(text)
        self.assertEqual("".join(tokens), text)
        self.assertIn("Cafe\u0301", tokens)
        self.assertNotIn("\u0301", tokens)
        context = self.make(text, first_line_title=False)
        self.assertEqual(context["manifest"]["copy_text"], text)

    def test_long_copy_is_rejected_without_shortening_or_font_shrink(self):
        original = "完整文案原样保留。" * 400
        self.assert_error("TEXT_TOO_LONG", lambda: self.make(original))
        self.assertEqual(original, "完整文案原样保留。" * 400)

    def test_overwide_word_requires_caller_adjustment(self):
        self.assert_error("TEXT_TOO_LONG", lambda: self.make("W" * 400))

    def test_missing_font_and_glyph_are_explicit(self):
        self.assert_error("FONT_UNREADABLE", lambda: self.make(font_path=PACKAGE / "absent.otf"))
        self.assert_error("FONT_MISSING_GLYPHS", lambda: self.make("缺字\U0010ffff"))

    def test_control_characters_are_never_silently_removed(self):
        for text in ("文\t案", "文\u200d案", "文\x00案"):
            self.assert_error("UNSUPPORTED_TEXT_CONTROL", lambda text=text: self.make(text))

    def test_count_one_two_three_four_and_invalid_counts(self):
        for count in (1, 2, 3, 4):
            self.assertEqual(self.make(count=count)["manifest"]["count"], count)
        for count in (0, 5, True, 2.0, "2"):
            self.assert_error("INVALID_COUNT", lambda count=count: self.make(count=count))

    def test_visual_and_text_regions_do_not_overlap(self):
        context = self.make("标题\n正文第一行\nBody second line", width=1080, height=1440)
        self.assertEqual(context["typeset_canvas"].size, (1080, 1440))
        self.assertEqual(context["visual"].width % 32, 0)
        self.assertEqual(context["visual"].height % 32, 0)
        self.assertEqual(context["visual_x"], 12)
        self.assertEqual(context["visual"].width, 1056)
        for line in context["measured_lines"]:
            self.assertGreaterEqual(line["y"], context["visual_height"] + context["margin"])
            self.assertLessEqual(line["x"] + line["width"], 1080 - context["margin"])
            self.assertLessEqual(line["y"] + line["height"], 1440 - context["margin"])
        previous = None
        for line in context["measured_lines"]:
            if previous is not None:
                self.assertGreaterEqual(line["y"], previous["y"] + previous["height"])
            previous = line

    def test_visual_canvas_contains_no_typeset_pixels(self):
        context = self.make()
        visual = context["visual"]
        self.assertEqual(visual.getextrema(), tuple((value, value) for value in visual.getpixel((0, 0))))

    def test_visual_prompt_cannot_request_quoted_copy(self):
        self.assert_error("COPY_IN_DESIGN_PROMPT", lambda: self.make(design_prompt='写上“另一个标题”'))

    def test_invisible_or_low_contrast_text_is_rejected(self):
        self.assert_error("INVALID_COLOR", lambda: self.make(text_color="#00000000"))
        self.assert_error("LOW_TEXT_CONTRAST", lambda: self.make(text_color="#fff8ed"))


if __name__ == "__main__":
    unittest.main()

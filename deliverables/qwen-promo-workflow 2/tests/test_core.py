"""CPU-only acceptance checks for validation and exact-copy composition."""
import copy
import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("promo_core_under_test", ROOT / "custom_nodes/promo_workflow/core.py")
core = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(core)


class CoreAcceptanceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        candidates = [Path("/Library/Fonts/Arial Unicode.ttf"),
                      Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")]
        cls.font_path = next((path for path in candidates if path.is_file()), None)
        if cls.font_path is None:
            raise unittest.SkipTest("No local test font installed; provide a CJK font to run all tests.")

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        shutil.copyfile(self.font_path, self.root / "test.ttf")
        image = Image.new("RGBA", (400, 200), "red")
        image.putpixel((0, 0), (0, 255, 0, 255))
        image.save(self.root / "product.png")
        self.request = {"copy": {"title": "Summer SALE", "body": "Enjoy the season."},
                        "language": "en", "width": 1024, "height": 1536, "count": 1,
                        "assets": [{"path": "product.png", "role": "product"}],
                        "font_title": "test.ttf", "font_body": "test.ttf"}

    def error(self, request, code):
        with self.assertRaises(core.PromoError) as raised:
            core.prepare_request(request, self.root)
        self.assertEqual(raised.exception.code, code)
        self.assertEqual(json.loads(str(raised.exception))["code"], code)

    def test_missing_and_blank_copy_require_caller(self):
        for value in (None, {}, {"title": " \n ", "body": ""}):
            with self.subTest(value=value):
                request = {**self.request, "copy": value}
                self.error(request, "COPY_REQUIRED")

    def test_copy_is_byte_for_byte_unchanged_in_context(self):
        exact = {"title": "  Summer SALE  ", "body": " First line\r\nSecond line  "}
        request = {**self.request, "copy": exact}
        before = copy.deepcopy(request)
        context = core.prepare_request(request, self.root)
        self.assertEqual(context["copy"], exact)
        self.assertEqual(context["request"], before)
        self.assertEqual(request, before)
        self.assertNotIn(exact["title"], context["prompt"])

    def test_counts_one_to_four_are_preserved_for_single_batch(self):
        for count in (1, 2, 3, 4):
            with self.subTest(count=count):
                context = core.prepare_request({**self.request, "count": count}, self.root)
                self.assertEqual(context["count"], count)
                self.assertEqual(context["render_width"] % 32, 0)
                self.assertEqual(context["render_height"] % 32, 0)

    def test_count_rejects_non_integer_and_out_of_range(self):
        for count in (0, 5, True, 1.0, "2"):
            with self.subTest(count=count):
                self.error({**self.request, "count": count}, "INVALID_COUNT")

    def test_missing_material_never_becomes_text_to_image(self):
        self.error({**self.request, "assets": []}, "MATERIAL_REQUIRED")
        self.error({**self.request, "assets": [{"path": "absent.png", "role": "product"}]}, "MATERIAL_UNREADABLE")

    def test_unreadable_material(self):
        (self.root / "broken.png").write_text("not an image")
        self.error({**self.request, "assets": [{"path": "broken.png", "role": "product"}]}, "MATERIAL_UNREADABLE")

    def test_background_alone_is_not_promotional_material(self):
        self.error({**self.request, "assets": [{"path": "product.png", "role": "background"}]}, "MATERIAL_REQUIRED")

    def test_paths_cannot_escape_input_folder(self):
        for path in ("../outside.png", str(self.root / "product.png")):
            with self.subTest(path=path):
                self.error({**self.request, "assets": [{"path": path, "role": "product"}]}, "MATERIAL_UNREADABLE")

    def test_symlink_cannot_escape_input_folder(self):
        outside = Path(self.tmp.name).parent / (Path(self.tmp.name).name + "_outside.png")
        Image.new("RGB", (8, 8), "blue").save(outside)
        self.addCleanup(lambda: outside.unlink(missing_ok=True))
        (self.root / "linked.png").symlink_to(outside)
        self.error({**self.request, "assets": [{"path": "linked.png", "role": "product"}]}, "MATERIAL_UNREADABLE")

    def test_long_copy_is_rejected_without_shortening(self):
        request = {**self.request, "copy": {"title": "SALE " * 500, "body": "complete body"}}
        before = copy.deepcopy(request)
        self.error(request, "TEXT_TOO_LONG")
        self.assertEqual(request, before)

    def test_overwide_english_word_is_not_split_or_rewritten(self):
        self.error({**self.request, "copy": {"title": "W" * 100, "body": ""}}, "TEXT_TOO_LONG")

    def test_missing_font_glyph_is_explicit(self):
        self.error({**self.request, "copy": {"title": "Missing \U0010ffff", "body": ""}}, "FONT_MISSING_GLYPHS")

    def test_three_languages_and_mixed_copy_preserve_original(self):
        if "Arial Unicode" not in str(self.font_path):
            self.skipTest("CJK cases require Arial Unicode or an equivalent CJK test font.")
        cases = [("zh-Hans", False, "夏日优惠", "新品上市"),
                 ("zh-Hant", False, "夏日優惠", "新品上市"),
                 ("en", False, "Summer Sale", "New arrivals"),
                 ("zh-Hant", True, "夏日優惠 Summer SALE", "新品上市 · 优惠 20%")]
        for language, mixed, title, body in cases:
            with self.subTest(language=language, mixed=mixed):
                exact = {"title": title, "body": body}
                context = core.prepare_request({**self.request, "copy": exact,
                                                "language": language, "allow_mixed_scripts": mixed}, self.root)
                self.assertEqual(context["copy"], exact)
                result = core.compose_image(Image.new("RGB", (100, 100), "blue"), context)
                self.assertEqual(result.size, (1024, 1536))

    def test_language_conflict_requires_adjustment_no_conversion(self):
        for language, title in (("zh-Hans", "優惠"), ("zh-Hant", "优惠"), ("en", "夏日")):
            with self.subTest(language=language):
                request = {**self.request, "language": language, "copy": {"title": title, "body": ""}}
                before = copy.deepcopy(request)
                self.error(request, "LANGUAGE_CONFLICT")
                self.assertEqual(request, before)

    def test_style_selected_unselected_and_conflicting(self):
        self.assertEqual(core.prepare_request(self.request, self.root)["effective_style"], "auto")
        self.assertEqual(core.prepare_request({**self.request, "style": "tech"}, self.root)["effective_style"], "tech")
        self.assertEqual(core.prepare_request({**self.request, "design_prompt": "复古色调"}, self.root)["effective_style"], "retro")
        self.error({**self.request, "style": "tech", "prompt_style": "retro"}, "STYLE_CONFLICT")
        self.error({**self.request, "style": "tech", "design_prompt": "复古色调"}, "STYLE_CONFLICT")
        self.assertEqual(core.prepare_request({**self.request, "style": "tech", "design_prompt": "不要复古"}, self.root)["effective_style"], "tech")

    def test_composition_preserves_asset_aspect_and_dedicated_text_panel(self):
        context = core.prepare_request(self.request, self.root)
        result = core.compose_image(Image.new("RGB", (500, 500), "blue"), context)
        self.assertEqual(result.size, (1024, 1536))
        self.assertEqual(result.mode, "RGB")
        box = context["layout"]["protected_boxes"][0]
        source = context["assets"][0]["image"]
        fitted = ImageOps.contain(source, (box[2] - box[0], box[3] - box[1]), Image.Resampling.LANCZOS)
        x = box[0] + (box[2] - box[0] - fitted.width) // 2
        y = box[1] + (box[3] - box[1] - fitted.height) // 2
        self.assertEqual(result.crop((x, y, x + fitted.width, y + fitted.height)).tobytes(), fitted.convert("RGB").tobytes())
        self.assertGreaterEqual(context["layout"]["text_top"], context["layout"]["visual_height"])

    def test_control_characters_require_caller_adjustment(self):
        self.error({**self.request, "copy": {"title": "A\tB", "body": ""}}, "UNSUPPORTED_TEXT_CONTROL")

    def test_explicit_copy_in_visual_prompt_must_be_moved_by_caller(self):
        for prompt in ('标题为“自动补充文案”', 'add text "new slogan"'):
            with self.subTest(prompt=prompt):
                self.error({**self.request, "design_prompt": prompt}, "COPY_IN_DESIGN_PROMPT")

    def test_invalid_fields_return_structured_errors(self):
        for field, value, code in (("language", [], "INVALID_LANGUAGE"),
                                   ("style", [], "INVALID_STYLE"),
                                   ("allow_mixed_scripts", "true", "INVALID_LANGUAGE")):
            with self.subTest(field=field):
                self.error({**self.request, field: value}, code)


if __name__ == "__main__":
    unittest.main()

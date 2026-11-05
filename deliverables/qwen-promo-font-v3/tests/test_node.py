import importlib.util
import json
import sys
import types
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / "custom_nodes" / "exact_promo_text"
spec = importlib.util.spec_from_file_location("exact_v3_node", PACKAGE / "__init__.py",
                                            submodule_search_locations=[str(PACKAGE)])
node = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = node
spec.loader.exec_module(node)


class FakeTensor:
    def __init__(self, array):
        self.array = np.asarray(array)
        self.shape = self.array.shape

    def detach(self):
        return self

    def cpu(self):
        return self

    def numpy(self):
        return self.array

    def unsqueeze(self, index):
        return FakeTensor(np.expand_dims(self.array, index))

    def repeat(self, *repeats):
        return FakeTensor(np.tile(self.array, repeats))


class NodeBridgeTests(unittest.TestCase):
    def prepare(self, count=1, material=None, **kwargs):
        if material is None:
            material = FakeTensor(np.zeros((1, 8, 8, 3), dtype=np.float32))
        fake_torch = types.SimpleNamespace(from_numpy=FakeTensor)
        with patch.dict(sys.modules, {"torch": fake_torch}):
            return node.ExactPromoTextPrepare().prepare(
                material, "咖啡还是我的好", "自然光、咖啡店氛围。",
                "NotoSansCJKsc-Regular.otf", count=count, **kwargs)

    def test_schema_and_return_slots_are_stable(self):
        cls = node.ExactPromoTextPrepare
        self.assertEqual(list(cls.INPUT_TYPES()["required"]),
                         ["material", "copy_text", "design_prompt", "font", "width", "height", "count",
                          "first_line_title", "title_size", "body_size", "text_color", "panel_color"])
        self.assertEqual(cls.RETURN_TYPES, ("STRING", "IMAGE", "IMAGE", "INT", "INT", "STRING"))

    def test_one_to_four_destinations_keep_every_batch_member(self):
        for count in (1, 2, 3, 4):
            with self.subTest(count=count):
                prepared = self.prepare(count=count)
                prompt, visual, destination, actual_count, x, manifest = prepared["result"]
                self.assertEqual(prepared["ui"]["text"], [manifest])
                metadata = json.loads(manifest)
                self.assertEqual(actual_count, count)
                self.assertEqual(destination.shape, (count, 1440, 1088, 3))
                self.assertEqual(visual.shape[0], 1)
                self.assertEqual(metadata["count"], count)
                self.assertEqual(metadata["copy_text"], "咖啡还是我的好")
                self.assertNotIn("咖啡还是我的好", prompt)
                # Equivalent to the native rectangle composite for a full mask.
                before = destination.array.copy()
                visual_h, visual_w = visual.shape[1:3]
                fake_decoded = np.full((count, visual_h, visual_w, 3), 0.123, dtype=np.float32)
                composite = destination.array.copy()
                composite[:, :visual_h, x:x + visual_w, :] = fake_decoded
                np.testing.assert_array_equal(composite[:, visual_h:, :, :], before[:, visual_h:, :, :])
                self.assertEqual(composite.shape[0], count)

    def test_missing_or_nonfinite_material_does_not_generate(self):
        for material in (FakeTensor(np.zeros((0, 8, 8, 3))), FakeTensor(np.zeros((1, 0, 8, 3))),
                         FakeTensor(np.zeros((1, 8, 8, 2)))):
            with self.assertRaises(node.LayoutError) as raised:
                self.prepare(material=material)
            self.assertEqual(raised.exception.code, "MATERIAL_REQUIRED")
        with self.assertRaises(node.LayoutError) as raised:
            self.prepare(material=FakeTensor(np.full((1, 8, 8, 3), np.nan)))
        self.assertEqual(raised.exception.code, "MATERIAL_UNREADABLE")

    def test_target_size_is_exact_and_not_stretched(self):
        _, visual, destination, _, x, _ = self.prepare(width=1080, height=1437)["result"]
        self.assertEqual(destination.shape, (1, 1437, 1080, 3))
        self.assertEqual(visual.shape[2], 1056)
        self.assertEqual(x, 12)


if __name__ == "__main__":
    unittest.main()

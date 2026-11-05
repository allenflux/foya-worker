"""CPU-only bridge tests. Native encoder is stubbed; these are not GPU QA."""
from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

PACKAGE_DIR = Path(__file__).resolve().parents[1] / "custom_nodes" / "promo_workflow"


class BridgeError(Exception):
    def as_dict(self):
        return {"code": "TEST_COMPOSITION_FAILURE", "message": str(self),
                "needs_adjustment": ["test adjustment"]}


def load_bridge():
    name = "_promo_bridge_test"
    fake_core = types.ModuleType(name + ".core")
    fake_core.PromoError = BridgeError
    fake_core.prepare_request = lambda request, root: request
    fake_core.compose_image = lambda background, context: background
    spec = importlib.util.spec_from_file_location(name, PACKAGE_DIR / "__init__.py",
                                                submodule_search_locations=[str(PACKAGE_DIR)])
    bridge = importlib.util.module_from_spec(spec)
    with patch.dict(sys.modules, {name: bridge, name + ".core": fake_core}):
        spec.loader.exec_module(bridge)
    return bridge, fake_core


class EncodeBridgeTests(unittest.TestCase):
    def test_native_encode_once_for_each_requested_batch_size(self):
        bridge, _ = load_bridge()
        for count in range(1, 5):
            with self.subTest(count=count):
                zeros_calls = []
                native_calls = []
                fake_torch = types.ModuleType("torch")
                fake_torch.zeros = lambda shape, device: zeros_calls.append((shape, device)) or {"shape": shape}
                comfy = types.ModuleType("comfy")
                management = types.ModuleType("comfy.model_management")
                management.intermediate_device = lambda: "cpu"
                comfy.model_management = management
                extras = types.ModuleType("comfy_extras")
                qwen = types.ModuleType("comfy_extras.nodes_qwen")
                class Native:
                    @classmethod
                    def execute(cls, **kwargs):
                        native_calls.append(kwargs)
                        return types.SimpleNamespace(result=("positive", "negative", "unused_native_latent"))
                qwen.TextEncodeQwenImage21 = Native
                modules = {"torch": fake_torch, "comfy": comfy,
                           "comfy.model_management": management,
                           "comfy_extras": extras, "comfy_extras.nodes_qwen": qwen}
                context = {"count": count, "render_width": 640, "render_height": 384,
                           "prompt": "Use only visual instructions.",
                           "assets": [{"image": "first", "role": "product"},
                                      {"image": "second", "role": "logo"}]}
                with patch.dict(sys.modules, modules), \
                     patch.object(bridge, "_reference_canvas", return_value="canvas"), \
                     patch.object(bridge, "_bounded_reference", side_effect=lambda image: image), \
                     patch.object(bridge, "_pil_tensor", side_effect=lambda image: image):
                    positive, negative, latent = bridge.PromoEncodeQwen21().encode(context, "clip", "vae")
                self.assertEqual(len(native_calls), 1)
                self.assertEqual(zeros_calls, [([count, 64, 24, 40], "cpu")])
                self.assertEqual(list(native_calls[0]["images"]), ["image_1", "image_2", "image_3"])
                self.assertEqual(native_calls[0]["resolution"], 0)
                self.assertEqual((positive, negative), ("positive", "negative"))
                self.assertEqual(latent["samples"]["shape"][0], count)

    def test_prepare_rejects_duplicate_keys_and_missing_assets_error_survives(self):
        bridge, fake_core = load_bridge()
        folder_paths = types.ModuleType("folder_paths")
        folder_paths.get_input_directory = lambda: "/tmp"
        with patch.dict(sys.modules, {"folder_paths": folder_paths}):
            with self.assertRaises(RuntimeError) as caught:
                bridge.PromoPrepareRequest().prepare('{"count":1,"count":4}')
            self.assertEqual(json.loads(str(caught.exception))["code"], "INVALID_JSON")
            fake_core.prepare_request = lambda *args: (_ for _ in ()).throw(BridgeError("素材无法读取"))
            with self.assertRaises(RuntimeError) as caught:
                bridge.PromoPrepareRequest().prepare("{}")
            self.assertEqual(json.loads(str(caught.exception))["code"], "TEST_COMPOSITION_FAILURE")


class SaveBridgeTests(unittest.TestCase):
    def setUp(self):
        try:
            import numpy as np
            from PIL import Image
        except ImportError:
            self.skipTest("Pillow and numpy are required for saver integration tests")
        self.np, self.Image = np, Image
        self.bridge, self.core = load_bridge()
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.folder_paths = types.ModuleType("folder_paths")
        self.folder_paths.get_output_directory = lambda: self.temporary.name

    def batch(self, count):
        np = self.np
        class Frame:
            def __init__(self, index): self.index = index
            def detach(self): return self
            def cpu(self): return self
            def numpy(self): return np.ones((16, 16, 3), dtype=np.float32) * (self.index / 4)
        class Batch:
            shape = (count, 16, 16, 3)
            def __getitem__(self, index): return Frame(index)
        return Batch()

    def context(self, count):
        return {"count": count, "width": 16, "height": 16,
                "copy": {"title": "原樣 Brand", "body": "逐字保留\nExact text"},
                "language": "zh-Hant"}

    def test_save_batch_1_2_3_4_metadata_and_png_copy(self):
        for count in range(1, 5):
            with self.subTest(count=count), patch.dict(sys.modules, {"folder_paths": self.folder_paths}):
                context = self.context(count)
                output = self.bridge.PromoComposeAndSave().save(self.batch(count), context)
                result = json.loads(output["result"][0])
                self.assertEqual(result["status"], "success")
                self.assertEqual((result["requested_count"], result["success_count"]), (count, count))
                self.assertEqual(len(output["ui"]["images"]), count)
                for item in result["images"]:
                    self.assertEqual(item["copy"], context["copy"])
                    path = Path(self.temporary.name) / item["subfolder"] / item["filename"]
                    with self.Image.open(path) as image:
                        self.assertEqual(image.size, (16, 16))
                        self.assertEqual(json.loads(image.info["promo_copy"]), context["copy"])
                    self.assertEqual(item["acceptance_status"], "requires_visual_review")

    def test_partial_success_retains_other_batch_members(self):
        call = 0
        def compose(background, context):
            nonlocal call
            call += 1
            if call == 2:
                raise BridgeError("second frame failed")
            return background
        self.core.compose_image = compose
        with patch.dict(sys.modules, {"folder_paths": self.folder_paths}):
            result = json.loads(self.bridge.PromoComposeAndSave().save(self.batch(4), self.context(4))["result"][0])
        self.assertEqual(result["status"], "partial_success")
        self.assertEqual(result["success_count"], 3)
        self.assertEqual([item["status"] for item in result["results"]],
                         ["success", "failed", "success", "success"])
        self.assertEqual(result["results"][1]["error"]["code"], "TEST_COMPOSITION_FAILURE")

    def test_batch_mismatch_and_unsafe_prefix_return_indexed_failures(self):
        for frames, prefix, code in [(1, "promo", "BATCH_COUNT_MISMATCH"),
                                     (2, "../escape", "INVALID_FILENAME_PREFIX")]:
            with patch.dict(sys.modules, {"folder_paths": self.folder_paths}):
                result = json.loads(self.bridge.PromoComposeAndSave().save(self.batch(frames), self.context(2), prefix)["result"][0])
            self.assertEqual(result["status"], "failed")
            self.assertEqual(len(result["results"]), 2)
            self.assertTrue(all(item["error"]["code"] == code for item in result["results"]))


if __name__ == "__main__":
    unittest.main()

import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class WorkflowIsolationTests(unittest.TestCase):
    def test_copy_model_and_final_image_paths(self):
        api = json.loads((ROOT / "Qwen2.1_宣传图_真实字体_API.json").read_text())
        self.assertEqual(api["30"]["inputs"]["copy_text"], ["20", 0])
        self.assertEqual(api["5"]["inputs"]["prompt"], ["30", 0])
        self.assertEqual(api["5"]["inputs"]["images.image_1"], ["30", 1])
        self.assertEqual(api["31"]["inputs"]["destination"], ["30", 2])
        self.assertEqual(api["31"]["inputs"]["source"], ["8", 0])
        self.assertEqual(api["31"]["inputs"]["x"], ["30", 4])
        self.assertFalse(api["31"]["inputs"]["resize_source"])
        self.assertEqual(api["9"]["inputs"]["images"], ["31", 0])
        self.assertEqual(api["6"]["inputs"]["amount"], ["30", 3])
        self.assertEqual(api["30"]["inputs"]["material"], ["10", 0])
        self.assertEqual(sum(n["class_type"] == "KSampler" for n in api.values()), 1)
        # Nothing takes the composited result back into a model or encoder.
        consumers = [key for key, node in api.items()
                     if any(value == ["31", 0] for value in node["inputs"].values())]
        self.assertEqual(consumers, ["9"])

    def test_ui_graph_links_and_live_copy(self):
        graph = json.loads((ROOT / "Qwen2.1_宣传图_真实字体排版.json").read_text())
        nodes = {n["id"]: n for n in graph["nodes"]}
        self.assertEqual(nodes[20]["widgets_values"], ["咖啡还是我的好"])
        self.assertEqual(nodes[5]["widgets_values"][0], "")
        self.assertEqual(sum(n["type"] == "ExactPromoTextPrepare" for n in nodes.values()), 1)
        for lid, sid, source_slot, tid, target_slot, typ in graph["links"]:
            self.assertIn(lid, nodes[sid]["outputs"][source_slot]["links"])
            self.assertEqual(nodes[tid]["inputs"][target_slot]["link"], lid)
            self.assertEqual(nodes[sid]["outputs"][source_slot]["type"], typ)
            self.assertEqual(nodes[tid]["inputs"][target_slot]["type"], typ)


if __name__ == "__main__":
    unittest.main()

import copy
import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("promo_api", ROOT / "promo_api.py")
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)


class FakeGateway(api.ComfyGateway):
    def __init__(self, result=None, error=None):
        super().__init__(poll_interval=0)
        self.result, self.error = result, error
        self.submissions = []

    def _json(self, path, payload=None):
        if path == "/prompt":
            self.submissions.append(payload)
            return {"prompt_id": "single-batch"}
        if path == "/history/single-batch":
            history = {"status": {"completed": True}, "outputs": {}}
            if self.error:
                history["status"]["messages"] = [["execution_error", {
                    "exception_message": json.dumps(self.error, ensure_ascii=False), "node_id": "4"}]]
            else:
                history["outputs"]["8"] = {"text": [json.dumps(self.result, ensure_ascii=False)]}
            return {"single-batch": history}
        raise AssertionError(path)


def successful_result(request, failing=()):
    rows = []
    for index in range(1, request["count"] + 1):
        if index in failing:
            rows.append({"index": index, "status": "failed", "error": {
                "code": "OUTPUT_WRITE_FAILED", "message": "磁盘故障", "needs_adjustment": ["磁盘"]}})
        else:
            rows.append({"index": index, "status": "success", "url": "/view?filename=image%d.png" % index,
                         "width": request["width"], "height": request["height"], "format": "png",
                         "copy": copy.deepcopy(request["copy"]), "language": request["language"]})
    images = [copy.deepcopy(row) for row in rows if row["status"] == "success"]
    return {"status": "success" if len(images) == request["count"] else "partial_success" if images else "failed",
            "acceptance_status": "requires_visual_review", "requested_count": request["count"],
            "success_count": len(images), "results": rows, "images": images}


class GatewayTests(unittest.TestCase):
    def request(self, count):
        return {"copy": {"title": "简体 繁體 EN 2026", "body": " 原样\n保留 "},
                "language": "zh-Hans", "allow_mixed_scripts": True,
                "width": 1080, "height": 1440, "count": count,
                "assets": [{"path": "product.png", "role": "product"}]}

    def test_count_1_2_3_4_uses_one_submission(self):
        for count in (1, 2, 3, 4):
            with self.subTest(count=count):
                request = self.request(count)
                gateway = FakeGateway(successful_result(request))
                result = gateway.generate(request)
                self.assertEqual(result["status"], "success")
                self.assertEqual(result["success_count"], count)
                self.assertEqual(len(gateway.submissions), 1)
                graph = gateway.submissions[0]["prompt"]
                self.assertEqual(len([n for n in graph.values() if n["class_type"] == "KSampler"]), 1)
                self.assertEqual(json.loads(graph["4"]["inputs"]["request_json"]), request)
                self.assertEqual(len(result["results"]), count)
                self.assertTrue(result["images"][0]["url"].startswith("http://127.0.0.1:8188/view?"))
                self.assertEqual(result["images"][0]["copy"], request["copy"])

    def test_partial_success_keeps_exact_per_image_status(self):
        request = self.request(4)
        gateway = FakeGateway(successful_result(request, failing=(2,)))
        result = gateway.generate(request)
        self.assertEqual(result["status"], "partial_success")
        self.assertEqual(result["success_count"], 3)
        self.assertEqual([r["index"] for r in result["images"]], [1, 3, 4])
        self.assertEqual(result["results"][1]["error"]["code"], "OUTPUT_WRITE_FAILED")
        self.assertEqual(len(gateway.submissions), 1)

    def test_validation_failure_does_not_retry(self):
        error = {"code": "COPY_REQUIRED", "message": "请补充完整上图文案。", "needs_adjustment": ["copy"]}
        gateway = FakeGateway(error=error)
        result = gateway.generate(self.request(3))
        self.assertEqual(result["status"], "failed")
        self.assertEqual(result["error"], error)
        self.assertEqual(len(result["results"]), 3)
        self.assertEqual(len(gateway.submissions), 1)

    def test_invalid_count_never_submits(self):
        for count in (0, 5, True, 2.0, "2"):
            gateway = FakeGateway()
            result = gateway.generate(self.request(count))
            self.assertEqual(result["status"], "failed")
            self.assertEqual(result["error"]["code"], "INVALID_COUNT")
            self.assertEqual(gateway.submissions, [])

    def test_result_cannot_claim_wrong_count(self):
        request = self.request(4)
        bad = successful_result(request)
        bad["success_count"] = 1
        result = FakeGateway(bad).generate(request)
        self.assertEqual(result["error"]["code"], "INVALID_RESULT")


class WorkflowTests(unittest.TestCase):
    def test_ui_link_integrity(self):
        graph = json.loads((ROOT / "宣传图编辑_工作流.json").read_text())
        nodes = {n["id"]: n for n in graph["nodes"]}
        for link, sid, slot, tid, target, typ in graph["links"]:
            self.assertIn(link, nodes[sid]["outputs"][slot]["links"])
            self.assertEqual(nodes[tid]["inputs"][target]["link"], link)
            self.assertEqual(nodes[sid]["outputs"][slot]["type"], typ)
            self.assertEqual(nodes[tid]["inputs"][target]["type"], typ)
        self.assertEqual(nodes[6]["inputs"][3]["link"], 7)
        self.assertEqual(nodes[5]["outputs"][2]["type"], "LATENT")

    def test_no_original_enhancer_lora_or_stale_widget_copies(self):
        graph = json.loads((ROOT / "宣传图编辑_工作流.json").read_text())
        for node in graph["nodes"]:
            self.assertNotIn(node["type"], ("SulphurAPIVisionPromptEnhancer", "Power Lora Loader (rgthree)",
                                           "SetNode", "GetNode", "EmptyLatentImage"))
            self.assertNotIn("widgets_values_named", node)
        self.assertNotIn("extensions", graph)
        request = json.loads((ROOT / "request.example.json").read_text())
        self.assertEqual(request["copy"], {"title": "", "body": ""})


if __name__ == "__main__":
    unittest.main()

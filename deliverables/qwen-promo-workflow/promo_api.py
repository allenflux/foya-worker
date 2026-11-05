#!/usr/bin/env python3
"""Single-request, single-batch ComfyUI promotional image gateway (stdlib only)."""
from __future__ import annotations

import argparse
import copy
import json
import secrets
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urljoin
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent
REQUIRED_NODES = (
    "PromoPrepareRequest", "PromoEncodeQwen21", "PromoComposeAndSave",
    "TextEncodeQwenImage21", "UNETLoader", "CLIPLoader", "VAELoader",
    "KSampler", "VAEDecode",
)


class GatewayError(Exception):
    def __init__(self, code, message, needs_adjustment=None, details=None):
        super().__init__(message)
        self.error = {"code": code, "message": message,
                      "needs_adjustment": needs_adjustment or []}
        if details is not None:
            self.error["details"] = details


def validate_envelope(request):
    if not isinstance(request, dict):
        raise GatewayError("INVALID_REQUEST", "请求必须是 JSON 对象。")
    count = request.get("count", 1)
    if type(count) is not int or not 1 <= count <= 4:
        raise GatewayError("INVALID_COUNT", "单次请求的 count 必须是 1–4 的整数。", ["count"])
    if "seed" in request and (type(request["seed"]) is not int or
                              not 0 <= request["seed"] < 2**64):
        raise GatewayError("INVALID_SEED", "seed 必须是 uint64 整数。", ["seed"])
    return count


def failure(request, error, prompt_id=None):
    count = request.get("count", 1) if isinstance(request, dict) else 0
    count = count if type(count) is int and 1 <= count <= 4 else 0
    result = {
        "status": "failed", "acceptance_status": "not_evaluated",
        "requested_count": count, "success_count": 0, "images": [],
        "results": [{"index": i + 1, "status": "failed", "error": error}
                    for i in range(count)], "error": error,
    }
    if prompt_id:
        result["prompt_id"] = prompt_id
    return result


class ComfyGateway:
    def __init__(self, base_url="http://127.0.0.1:8188", timeout=600, poll_interval=0.5):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.poll_interval = poll_interval

    def _json(self, path, payload=None):
        data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = Request(self.base_url + path, data=data,
                      headers={"Content-Type": "application/json"})
        try:
            with urlopen(req, timeout=min(self.timeout, 30)) as response:
                return json.load(response)
        except HTTPError as exc:
            detail = exc.read(65536).decode("utf-8", "replace")
            raise GatewayError("COMFY_HTTP_ERROR", f"ComfyUI HTTP {exc.code}",
                               ["检查节点、模型及请求参数"], detail) from exc
        except (URLError, TimeoutError, OSError) as exc:
            raise GatewayError("COMFY_UNREACHABLE", "无法连接 ComfyUI。",
                               ["检查 ComfyUI 地址及服务状态"], str(exc)) from exc

    def check(self):
        missing = []
        for name in REQUIRED_NODES:
            info = self._json("/object_info/" + quote(name))
            if name not in info:
                missing.append(name)
        if missing:
            raise GatewayError("MISSING_NODES", "ComfyUI 缺少必需节点。",
                               ["安装随包自定义节点并更新 ComfyUI"], missing)
        return {"status": "ready", "nodes": list(REQUIRED_NODES)}

    def graph(self, request):
        validate_envelope(request)
        graph = json.loads((ROOT / "workflow.api.json").read_text(encoding="utf-8"))
        graph["4"]["inputs"]["request_json"] = json.dumps(request, ensure_ascii=False)
        graph["6"]["inputs"]["seed"] = request.get("seed", secrets.randbits(63))
        return graph

    @staticmethod
    def _execution_error(history):
        for kind, details in history.get("status", {}).get("messages", []):
            if kind in ("execution_error", "execution_interrupted"):
                message = details.get("exception_message", "执行被中断。")
                try:
                    parsed = json.loads(message)
                    if isinstance(parsed, dict) and "code" in parsed:
                        return parsed
                except (TypeError, ValueError):
                    pass
                return {"code": "GENERATION_FAILED", "message": message,
                        "needs_adjustment": ["检查显存、素材、字体、节点与模型配置"],
                        "node_id": details.get("node_id")}
        return None

    def generate(self, request):
        prompt_id = None
        try:
            count = validate_envelope(request)
            graph = self.graph(request)
            # Exactly ONE submission. Never split N images into N prompt calls.
            queued = self._json("/prompt", {"prompt": graph, "client_id": str(uuid.uuid4())})
            if queued.get("error") or queued.get("node_errors"):
                raise GatewayError("PROMPT_REJECTED", "ComfyUI 拒绝工作流。",
                                   ["检查节点版本和模型文件"], queued)
            prompt_id = queued.get("prompt_id")
            if not prompt_id:
                raise GatewayError("INVALID_COMFY_RESPONSE", "ComfyUI 未返回 prompt_id。")
            deadline = time.monotonic() + self.timeout
            while time.monotonic() < deadline:
                histories = self._json("/history/" + quote(prompt_id))
                history = histories.get(prompt_id)
                if history:
                    error = self._execution_error(history)
                    if error:
                        return failure(request, error, prompt_id)
                    output = history.get("outputs", {}).get("8", {})
                    texts = output.get("text", [])
                    if texts:
                        result = json.loads(texts[0])
                        self._validate_result(result, count)
                        result["prompt_id"] = prompt_id
                        # Use the ComfyUI origin, never the gateway origin, for /view.
                        for collection in (result["images"], result["results"]):
                            for item in collection:
                                if item.get("url"):
                                    item["url"] = urljoin(self.base_url + "/", item["url"])
                        return result
                    if history.get("status", {}).get("completed"):
                        raise GatewayError("MISSING_RESULT", "任务结束但缺少逐图结果。")
                time.sleep(self.poll_interval)
            # Timed-out queued work may finish later. Do not retry or create duplicate batches.
            return {"status": "pending", "acceptance_status": "not_evaluated",
                    "prompt_id": prompt_id, "requested_count": count,
                    "success_count": 0, "images": [],
                    "results": [{"index": i + 1, "status": "pending"} for i in range(count)],
                    "error": {"code": "WAIT_TIMEOUT", "message": "等待超时，原批次可能仍在运行。",
                              "needs_adjustment": ["按 prompt_id 查询 /history，避免重复提交"]}}
        except GatewayError as exc:
            return failure(request, exc.error, prompt_id)
        except (ValueError, KeyError, TypeError) as exc:
            return failure(request, {"code": "INVALID_RESULT", "message": "结果格式不符合约定。",
                                     "needs_adjustment": ["检查自定义节点版本"],
                                     "details": str(exc)}, prompt_id)

    @staticmethod
    def _validate_result(result, count):
        rows = result["results"]
        images = result["images"]
        if result["requested_count"] != count or len(rows) != count:
            raise ValueError("requested_count/results mismatch")
        if sorted(row["index"] for row in rows) != list(range(1, count + 1)):
            raise ValueError("result indexes mismatch")
        successes = [r for r in rows if r["status"] == "success"]
        if result["success_count"] != len(images) or len(images) != len(successes):
            raise ValueError("success_count mismatch")
        expected = "success" if len(images) == count else "partial_success" if images else "failed"
        if result["status"] != expected:
            raise ValueError("overall status mismatch")


def handler_for(gateway):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            if self.path != "/generate":
                self.send_error(404)
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 1024 * 1024:
                    raise GatewayError("INVALID_REQUEST", "请求体必须为 1 字节至 1 MiB。")
                request = json.loads(self.rfile.read(length))
                result = gateway.generate(request)
            except (ValueError, GatewayError) as exc:
                error = exc.error if isinstance(exc, GatewayError) else {
                    "code": "INVALID_JSON", "message": "请求不是有效 JSON。", "needs_adjustment": ["请求体"]}
                result = failure({}, error)
            status = 202 if result["status"] == "pending" else 422 if result["status"] == "failed" else 200
            body = json.dumps(result, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, fmt, *args):
            # HTTP status only; never log caller copy or image contents.
            return
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("check", "generate", "serve"))
    parser.add_argument("request", nargs="?", type=Path)
    parser.add_argument("--comfy-url", default="http://127.0.0.1:8188")
    parser.add_argument("--timeout", type=float, default=600)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8199)
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout 必须大于 0")
    gateway = ComfyGateway(args.comfy_url, args.timeout)
    if args.mode == "serve":
        # A local adapter; deploy behind your existing authentication gateway if needed.
        ThreadingHTTPServer((args.host, args.port), handler_for(gateway)).serve_forever()
    elif args.mode == "check":
        try:
            print(json.dumps(gateway.check(), ensure_ascii=False, indent=2))
        except GatewayError as exc:
            print(json.dumps(failure({}, exc.error), ensure_ascii=False, indent=2))
            raise SystemExit(1)
    else:
        if not args.request:
            parser.error("generate 需要请求 JSON 文件路径")
        result = gateway.generate(json.loads(args.request.read_text(encoding="utf-8")))
        print(json.dumps(result, ensure_ascii=False, indent=2))
        raise SystemExit(0 if result["status"] == "success" else 2)


if __name__ == "__main__":
    main()

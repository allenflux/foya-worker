"""ComfyUI bridge for strict, source-backed promotional image composition.

Native Qwen encoder compatibility is based on ComfyUI TextEncodeQwenImage21.
The supplied batch is one sampler execution; this module never queues prompts.
"""
from __future__ import annotations

import copy
import json
import math
import re
import uuid
from pathlib import Path
from urllib.parse import urlencode

from . import core


def _error(code, message, adjustments=None):
    return {"code": code, "message": message,
            "needs_adjustment": adjustments or []}


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("JSON 存在重复字段：" + key)
        result[key] = value
    return result


def _raise_structured(error):
    raise RuntimeError(json.dumps(error, ensure_ascii=False))


class PromoPrepareRequest:
    CATEGORY = "promo/strict"
    RETURN_TYPES = ("PROMO_CONTEXT",)
    RETURN_NAMES = ("context",)
    FUNCTION = "prepare"

    @classmethod
    def INPUT_TYPES(cls):
        return {"required": {"request_json": ("STRING", {"multiline": True,
                                                               "default": "{}"})}}

    @classmethod
    def IS_CHANGED(cls, **kwargs):
        # Assets and font files may have changed without changing JSON text.
        return float("nan")

    def prepare(self, request_json):
        import folder_paths
        try:
            request = json.loads(request_json, object_pairs_hook=_unique_object,
                                 parse_constant=lambda value: (_ for _ in ()).throw(
                                     ValueError("非法 JSON 常量：" + value)))
        except (TypeError, ValueError) as exc:
            _raise_structured(_error("INVALID_JSON", str(exc), ["请提供合法的请求 JSON。 "]))
        try:
            context = core.prepare_request(request, Path(folder_paths.get_input_directory()))
        except core.PromoError as exc:
            _raise_structured(exc.as_dict())
        return (context,)


def _pil_tensor(image):
    import numpy as np
    import torch
    return torch.from_numpy(np.asarray(image.convert("RGB"), dtype=np.float32)
                            .copy() / 255.0).unsqueeze(0)


def _reference_canvas(context):
    from PIL import Image, ImageOps
    canvas = Image.new("RGBA", (context["render_width"], context["render_height"]),
                       (240, 240, 240, 255))
    assets = context["assets"]
    primary = next((asset for asset in assets
                    if asset["role"] in ("subject", "product")), assets[0])
    fitted = ImageOps.contain(primary["image"], canvas.size, Image.Resampling.LANCZOS)
    canvas.alpha_composite(fitted, ((canvas.width - fitted.width) // 2,
                                   (canvas.height - fitted.height) // 2))
    return canvas.convert("RGB")


def _bounded_reference(image):
    # Native resolution=0 expects multiples of 32. Pad instead of stretching.
    from PIL import Image, ImageOps
    rgba = image.convert("RGBA")
    fitted = ImageOps.contain(rgba, (1024, 1024), Image.Resampling.LANCZOS)
    width = max(32, math.ceil(fitted.width / 32) * 32)
    height = max(32, math.ceil(fitted.height / 32) * 32)
    canvas = Image.new("RGBA", (width, height), (255, 255, 255, 255))
    canvas.alpha_composite(fitted, ((width - fitted.width) // 2,
                                   (height - fitted.height) // 2))
    return canvas.convert("RGB")


class PromoEncodeQwen21:
    CATEGORY = "promo/strict"
    RETURN_TYPES = ("CONDITIONING", "CONDITIONING", "LATENT")
    RETURN_NAMES = ("positive", "negative", "latent")
    FUNCTION = "encode"

    @classmethod
    def INPUT_TYPES(cls):
        return {"required": {"context": ("PROMO_CONTEXT",), "clip": ("CLIP",),
                             "vae": ("VAE",),
                             "resolution": ("INT", {"default": 0, "min": 0,
                                                     "max": 4096, "step": 32})}}

    def encode(self, context, clip, vae, resolution=0):
        import torch
        import comfy.model_management
        try:
            from comfy_extras.nodes_qwen import TextEncodeQwenImage21
        except (ImportError, AttributeError) as exc:
            raise RuntimeError("缺少 ComfyUI 原生 TextEncodeQwenImage21 节点；"
                               "请升级到支持 Qwen Image 2.1 的版本。") from exc
        count = context["count"]
        if type(count) is not int or not 1 <= count <= 4:
            _raise_structured(_error("INVALID_COUNT", "出图数量必须是 1–4 的整数。"))
        if not context.get("assets"):
            _raise_structured(_error("MATERIAL_REQUIRED", "请提供可读取的宣传素材。"))
        references = {"image_1": _pil_tensor(_reference_canvas(context))}
        roles = []
        for index, asset in enumerate(context["assets"], start=2):
            references[f"image_{index}"] = _pil_tensor(_bounded_reference(asset["image"]))
            roles.append(f"<image{index}> 的素材角色为 {asset['role']}。")
        prompt = ("<image1> 是目标画面比例的布局参考画布。 " + " ".join(roles)
                  + " " + context["prompt"])
        negative = ("new slogans, invented lettering, random typography, additional captions, "
                    "new watermark, illegible generated glyphs, distorted product, "
                    "distorted faces, malformed anatomy, low quality, blurred background")
        encoded = TextEncodeQwenImage21.execute(
            clip=clip, prompt=prompt, negative_prompt=negative, vae=vae,
            resolution=resolution, images=references)
        result = encoded.result
        if not result or len(result) != 3:
            raise RuntimeError("TextEncodeQwenImage21 返回格式不兼容；请核对 ComfyUI 版本。")
        width, height = context["render_width"], context["render_height"]
        if width % 32 or height % 32:
            raise RuntimeError("内部画布尺寸必须为 32 的倍数。")
        # Qwen Image 2.1 uses 64 channels and a spatial factor of 16.
        # One latent tensor gives one KSampler invocation for all requested images.
        latent = {"samples": torch.zeros(
            [count, 64, height // 16, width // 16],
            device=comfy.model_management.intermediate_device())}
        return (result[0], result[1], latent)


def _item_failure(index, error, context):
    return {"index": index, "status": "failed", "error": error,
            "copy": copy.deepcopy(context["copy"]), "language": context["language"]}


def _summary(context, results):
    images = [item for item in results if item["status"] == "success"]
    succeeded = len(images)
    count = context["count"]
    status = "success" if succeeded == count else "partial_success" if succeeded else "failed"
    return {"status": status, "requested_count": count, "success_count": succeeded,
            "acceptance_status": "requires_visual_review" if succeeded else "failed",
            "images": images, "results": results}


class PromoComposeAndSave:
    CATEGORY = "promo/strict"
    RETURN_TYPES = ("STRING",)
    RETURN_NAMES = ("result_json",)
    FUNCTION = "save"
    OUTPUT_NODE = True

    @classmethod
    def INPUT_TYPES(cls):
        return {"required": {"images": ("IMAGE",), "context": ("PROMO_CONTEXT",),
                             "filename_prefix": ("STRING", {"default": "promo"})}}

    def save(self, images, context, filename_prefix="promo"):
        import folder_paths
        import numpy as np
        from PIL import Image, PngImagePlugin
        count = context["count"]
        results = []
        previews = []
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", filename_prefix):
            error = _error("INVALID_FILENAME_PREFIX", "文件名前缀仅支持 1–80 位字母、数字、下划线或连字符。",
                           ["请调整 filename_prefix。"])
            results = [_item_failure(i, error, context) for i in range(1, count + 1)]
        elif len(getattr(images, "shape", ())) != 4 or int(images.shape[0]) != count:
            error = _error("BATCH_COUNT_MISMATCH", "解码图片批次与请求数量不一致。",
                           ["请保留单次批量采样连接，并核对请求数量。"])
            results = [_item_failure(i, error, context) for i in range(1, count + 1)]
        else:
            subfolder = "promo/" + uuid.uuid4().hex
            directory = Path(folder_paths.get_output_directory()) / subfolder
            directory_error = None
            try:
                directory.mkdir(parents=True, exist_ok=False)
            except OSError as exc:
                directory_error = _error("OUTPUT_WRITE_FAILED", str(exc), ["请检查输出目录权限和磁盘空间。"])
            for index in range(1, count + 1):
                temporary = None
                try:
                    if directory_error:
                        results.append(_item_failure(index, directory_error, context))
                        continue
                    frame = images[index - 1].detach().cpu().numpy()
                    if frame.ndim != 3 or frame.shape[-1] not in (3, 4) or not np.isfinite(frame).all():
                        raise ValueError("解码图像必须为有限值 RGB/RGBA 数据。")
                    background = Image.fromarray((np.clip(frame, 0, 1) * 255).round().astype(np.uint8))
                    composed = core.compose_image(background, context)
                    if composed.size != (context["width"], context["height"]):
                        raise ValueError("最终尺寸与请求尺寸不一致。")
                    filename = f"{filename_prefix}_{index:02d}.png"
                    temporary = directory / (filename + ".tmp")
                    destination = directory / filename
                    metadata = PngImagePlugin.PngInfo()
                    metadata.add_text("promo_copy", json.dumps(context["copy"], ensure_ascii=False))
                    metadata.add_text("promo_language", context["language"])
                    composed.convert("RGB").save(temporary, format="PNG", pnginfo=metadata)
                    temporary.replace(destination)
                    query = urlencode({"filename": filename, "subfolder": subfolder, "type": "output"})
                    item = {"index": index, "status": "success", "url": "/view?" + query,
                            "filename": filename, "subfolder": subfolder, "type": "output",
                            "width": composed.width, "height": composed.height, "format": "png",
                            "copy": copy.deepcopy(context["copy"]), "language": context["language"],
                            "acceptance_status": "requires_visual_review",
                            "checks": {"copy": "passed", "glyphs": "passed", "layout": "passed",
                                       "size": "passed", "protected_assets": "original_pixels_composited",
                                       "background_text": "requires_review", "visual_design": "requires_review"}}
                    results.append(item)
                    previews.append({key: item[key] for key in ("filename", "subfolder", "type")})
                except core.PromoError as exc:
                    results.append(_item_failure(index, exc.as_dict(), context))
                except Exception as exc:
                    results.append(_item_failure(index,
                        _error("COMPOSITION_OR_SAVE_FAILED", str(exc),
                               ["请检查生成图像、排版输入和输出目录。"]), context))
                finally:
                    if temporary and temporary.exists():
                        try:
                            temporary.unlink(missing_ok=True)
                        except OSError:
                            pass
        summary = _summary(context, results)
        result_json = json.dumps(summary, ensure_ascii=False, allow_nan=False)
        return {"ui": {"images": previews, "text": [result_json]}, "result": (result_json,)}


NODE_CLASS_MAPPINGS = {
    "PromoPrepareRequest": PromoPrepareRequest,
    "PromoEncodeQwen21": PromoEncodeQwen21,
    "PromoComposeAndSave": PromoComposeAndSave,
}
NODE_DISPLAY_NAME_MAPPINGS = {
    "PromoPrepareRequest": "宣传图 · 请求与素材校验",
    "PromoEncodeQwen21": "宣传图 · Qwen 2.1 多图批量编码",
    "PromoComposeAndSave": "宣传图 · 原文排版与逐图结果",
}

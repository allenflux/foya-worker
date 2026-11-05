# Qwen Image 2.1 宣传图编辑工作流

导入 `宣传图编辑_工作流.json`。此版本删除原来的提示词改写接口、不合适的 LoRA、旧预设及重复配置。**需要安装随包自定义节点，不能只导入 JSON。**

## 安装

1. 把本包 `custom_nodes/promo_workflow` 文件夹复制到 `ComfyUI/custom_nodes/`。
2. 使用 **运行 ComfyUI 的同一个 Python** 安装依赖：

   ```sh
   python -m pip install -r ComfyUI/custom_nodes/promo_workflow/requirements.txt
   ```

3. 重启 ComfyUI，并确认版本具有原生 `TextEncodeQwenImage21`。模型名沿用原工作流：

   | 目录 | 模型 |
   | --- | --- |
   | `models/diffusion_models/` | `qwen_image_2.1_bf16.safetensors` |
   | `models/text_encoders/` | `qwen3vl_8b_int8_convrot.safetensors` |
   | `models/vae/` | `qwen_image_2.1_vae_bf16.safetensors` |

4. 将素材放进 `ComfyUI/input/materials/`，将有授权且覆盖文案字形的 TTF/OTF/TTC 字体放进 `ComfyUI/input/fonts/`。本包不附字体或模型。
5. 导入工作流，在节点 **① 完整文案、素材、语言、尺寸、数量** 编辑请求 JSON，点击运行。

## 请求填写

`request.example.json` 是同一份模板。文案故意留空，必须由调用方完整填写后运行。

- `copy.title` / `copy.body`：完整原文。可仅有其中一项有内容；不自动生成、改写、翻译、简繁转换、删减或缩小字号。原换行保留，按宽度增加软换行。放不下会报错，需由调用方调整文案、尺寸或字体。
- `language`：`zh-Hans` 简体中文、`zh-Hant` 繁体中文、`en` 英文。文案保留原字形。中英混排可直接使用；有意混合简繁字形或在英文版加入中文时，设 `allow_mixed_scripts: true`。
- `width` / `height`：最终尺寸，512–4096 像素的整数。按比例布局，不裁切、拉伸原素材。
- `count`：必填，1、2、3、4。**一次运行、一个 KSampler 批次**完成全部图片；不会循环提交单张任务。显存不足时整个采样批次会失败，不会偷偷改成逐张生成。
- `style`：选填，可留空；支持 `minimal` 极简、`luxury` 高级、`retro` 复古、`playful` 活泼、`tech` 科技、`natural` 自然。
- `design_prompt`：视觉补充要求。上图文字全部放在 `copy`，不要在此要求添加标语。选定风格时以该风格为基础；没有选时由模型结合视觉要求、参考图和素材匹配。
- `prompt_style`：可选，明确声明提示词中的风格；与已选 `style` 不同会返回 `STYLE_CONFLICT`。自由文本冲突只覆盖明确的常见风格词，仍需调用方核对复杂语义。
- `assets`：1–9 个素材；每项 `path` 是相对于 `ComfyUI/input/` 的路径，`role` 为 `subject` 人物、`product` 产品、`logo` 标识、`background` 背景或 `reference` 风格参考。至少提供一张人物、产品或标识素材；无素材或任何素材读取失败都会停止。
- `font_title` / `font_body`：相对于 `input/` 的字体文件路径。显式指定可控制字体；缺字会报错。未指定时，高级/复古优先 Noto Serif，其余使用 Noto Sans；中文按 sc/tc 选文件。字体需自行准备，也可通过服务器环境变量 `PROMO_FONT_SANS` / `PROMO_FONT_SERIF` 指定默认字体。

模型负责背景和视觉氛围；人物、产品使用原素材在独立区域等比合成，Logo 使用独立区域，字体程序最后按原文排字。**透明 PNG 更适合合成**；普通照片会保留照片本身的背景，不自动抠图或改造人物姿势。此布局优先保留素材特征，素材自带的包装文字与标识也会保留，不把它们提取为新文案。

## 输出及状态

PNG 保存在 `ComfyUI/output/promo/<批次编号>/`。节点⑤返回完整 JSON，可从 ComfyUI 历史输出中读取：

- `images`：成功图片列表，每项包含 `url`、`width`、`height`、`format`、实际使用的 `copy`、`language` 及检查状态。原文也写入 PNG 元数据。
- `requested_count` / `success_count`：请求数量、实际保存成功数量。
- `results`：按 `index` 列出每一张的 `success` / `failed` 及失败 `error.code`、原因和 `needs_adjustment`。
- 请求状态为 `success`、`partial_success` 或 `failed`。逐图合成/保存失败可保留同一批次中其他成功图；编码、采样或整批解码失败时整批失败。直接运行工作流时，前置校验和上游失败显示在 ComfyUI 错误中；需要统一错误 JSON 时使用下面的可选适配器。
- `acceptance_status: requires_visual_review` 明确表示还需检查视觉效果。确定性排版和保留原素材可以验证，但模型背景是否出现伪文字、构图和风格是否满意不能仅靠提示词保证。本包未连接你的 GPU 环境跑实图，不把这些项目标为已通过验收。

## 可选接口适配器

如果需要调用接口，`workflow.api.json` 为 API 格式；`promo_api.py` 封装一次 `/prompt` 提交并收集逐图结果，所有路径仍指向 ComfyUI 所在机器的 `input` 文件夹：

```sh
python promo_api.py check --comfy-url http://127.0.0.1:8188
python promo_api.py generate request.example.json --comfy-url http://127.0.0.1:8188
python promo_api.py serve --comfy-url http://127.0.0.1:8188
```

服务默认监听本机 8199，向 `/generate` POST 同样的 JSON。一次接口调用提交一次批量任务，结果 URL 指向 ComfyUI 的 `/view`；不是公共对象存储地址。等待超时返回 `pending` 和 `prompt_id`，原批次可能仍在运行，应查询历史，不会自动重新提交。需要再次生成时由调用方重新调用。

CPU 检查：安装依赖及 numpy 后执行 `python -m unittest discover -s tests -v`。测试包含排版、字形覆盖、三种语言与混排、数量 1/2/3/4、单次提交及部分失败；原生模型调用在测试中使用替身，不能替代 GPU 出图验收。

原生接口参考：[ComfyUI Qwen 编码器](https://github.com/Comfy-Org/ComfyUI/blob/master/comfy_extras/nodes_qwen.py)、[ComfyUI API 示例](https://github.com/Comfy-Org/ComfyUI/blob/master/script_examples/websockets_api_example.py)。

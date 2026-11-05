# Qwen 宣传图 · 真实字体排版版

**Qwen 只生成上方无字主视觉；指定文案由真实字体绘制在下方独立文字区。两部分合成后直接保存，不再经过模型。**

本包保留你当前输入的完整原文「咖啡还是我的好」。只会排这 7 个字，不补正文。另附空白模板供其他请求使用。

## 安装后导入

1. 将本包 `custom_nodes/exact_promo_text` 整个目录复制到 `ComfyUI/custom_nodes/`，包括里面的 `fonts` 文件夹。
2. 在 ComfyUI 根目录，使用**运行 ComfyUI 的同一个 Python**安装依赖：

   ```sh
   python -m pip install -r custom_nodes/exact_promo_text/requirements.txt
   ```

3. 重启 ComfyUI，再导入 **`Qwen2.1_宣传图_真实字体排版.json`**。
4. 若原素材文件在当前服务器不存在，在节点①重新上传素材。

这次只增加一个 `ExactPromoTextPrepare` 小节点，负责采样前的字体、字形和容量校验，以及文字层绘制。模型加载、Qwen 编码、批量采样、VAE 解码、最终合成和保存全部使用原生节点。单独导入 JSON 而未安装这个小节点，会提示缺少节点。

## 怎么填写

- **② 指定上图文案**：填完整原文，字形、大小写、标点、空格及原有换行均保留，不补写、改写、简繁转换或翻译。自动增加的软换行只影响排版。缺文案直接报 `COPY_REQUIRED`。
- **③ 视觉设计要求**：只写素材用途、风格、背景、配色、光影和构图，不填上图文字。该部分进入 Qwen；②的文案不进入 Qwen。
- **④ 真实字体排版**：控制最终宽高、`count` 数量 1–4、字号、文字和底板颜色。`first_line_title=true` 时，第一行按标题字号排，其余行按正文字号排；关闭则全部按正文排。
- 字体选 `NotoSansCJKsc-Regular.otf` 对应简体中文风格，`NotoSansCJKtc-Regular.otf` 对应繁体中文风格。两者均附在包内；不转换输入字符。英文、中文及混排可以直接输入。
- 当前字体缺少某个字时会报 `FONT_MISSING_GLYPHS`；放不下会报 `TEXT_TOO_LONG`。不会自动删字、替换为方框、缩小字号或裁掉文案。需要由调用方调整完整文案、尺寸、字号或字体。
- 数量只在④的 `count` 中填写；工具栏 `Batch Count` 保持 1。一个 KSampler 批次完成 1–4 张，不循环提交单张。

文字区根据实际排版高度分配，最多占最终高度的 55%。上方主视觉独立生成并按原尺寸合成，不拉伸、不裁切；宽度不能整除 32 时保留居中边距，最终输出仍为指定宽高。最终版式有独立文字底板，避免文字遮挡人物或产品。

## 输出与验证

PNG 保存到 `ComfyUI/output/`，前缀为 `Qwen_Promo_Exact`。原生历史输出中的节点 `9` 返回保存图片列表，`30.text[0]` 返回包含**实际原文、字体、宽高、数量和排字检查结果**的 JSON。原生 SaveImage 的 PNG 元数据中也包含工作流及原文输入。

`Qwen2.1_宣传图_真实字体_API.json` 为接口格式：文案写入 `20.inputs.value`，视觉要求写入 `21.inputs.value`，素材写入 `10.inputs.image`，尺寸和数量写入 `30.inputs`；整个图只提交一次 `/prompt`。

`文字排版验证.png` 是使用你这 7 个字生成的真实字体文字区预览，**不是重新运行的 Qwen 海报底图**。18 项 CPU 检查和 2 项工作流连线检查已通过，覆盖空文案、缺字、溢出、混排、空白与换行、组合字符、准确尺寸和 1–4 张文字画布。节点测试使用 CPU 数组替身，不代表已经在你的 GPU 环境完成运行验收。

指定文案不再由扩散模型画字。主视觉仍需检查主体、品牌和构图，以及模型是否偶发生成额外伪文字；本包没有把这些视觉检查标记为自动通过。

## 字体与来源

包内字体来自 [Noto CJK 官方仓库](https://github.com/notofonts/noto-cjk)，附原始 `OFL.txt` 许可证及 `FONT_SOURCES.json` 来源和校验值。你也可将有使用授权的 TTF/OTF/TTC 字体放入同一 `fonts` 目录，重启后选择。

原生接口参考：[Qwen 编码器](https://github.com/Comfy-Org/ComfyUI/blob/master/comfy_extras/nodes_qwen.py)、[最终图像合成](https://github.com/Comfy-Org/ComfyUI/blob/master/comfy_extras/nodes_mask.py)。

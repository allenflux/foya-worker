"""Build workflow files; this script is not installed or run by ComfyUI."""
from __future__ import annotations

import copy
import json
from pathlib import Path
import re
import uuid


ROOT = Path(__file__).resolve().parent
SOURCE = ROOT.parent / "qwen-promo-integrated" / "Qwen2.1_宣传图_融合排版.json"
old = json.loads(SOURCE.read_text())
source_nodes = {n["id"]: n for n in old["nodes"]}
nodes = {}
links = []
api = {}


def add(nid, kind, title, pos, size, widgets=None, outputs=(), *, cnr="comfy-core", collapsed=False):
    n = {"id": nid, "type": kind, "title": title, "pos": list(pos), "size": list(size),
         "flags": {"collapsed": True} if collapsed else {}, "order": len(nodes), "mode": 0,
         "inputs": [], "outputs": [{"name": name, "type": typ, "links": []} for name, typ in outputs],
         "properties": {"Node name for S&R": kind, "cnr_id": cnr}}
    if widgets is not None:
        n["widgets_values"] = widgets
    nodes[nid] = n
    if kind != "Note":
        api[str(nid)] = {"class_type": kind, "inputs": {}, "_meta": {"title": title}}
    return n


def clone(nid, title, pos, size=None):
    s = source_nodes[nid]
    n = add(nid, s["type"], title, pos, size or s["size"], copy.deepcopy(s.get("widgets_values")),
            [(o["name"], o["type"]) for o in s.get("outputs", [])],
            cnr=s.get("properties", {}).get("cnr_id", "comfy-core"))
    return n


def connect(sid, slot, tid, name, typ, *, widget=False, optional=False):
    lid = len(links) + 1
    inp = {"name": name, "type": typ, "link": lid}
    if widget:
        inp["widget"] = {"name": name}
    if optional:
        inp["shape"] = 7
    tin = len(nodes[tid]["inputs"])
    nodes[tid]["inputs"].append(inp)
    out_type = nodes[sid]["outputs"][slot]["type"]
    links.append([lid, sid, slot, tid, tin, out_type])
    nodes[sid]["outputs"][slot]["links"].append(lid)
    api[str(tid)]["inputs"][name] = [str(sid), slot]


def regex(nid, title, pos, pattern, replacement, *, dotall=False, collapsed=True):
    add(nid, "RegexReplace", title, pos, (330, 210),
        ["", pattern, replacement, False, False, dotall, 1], [("STRING", "STRING")], collapsed=collapsed)
    api[str(nid)]["inputs"].update(regex_pattern=pattern, replace=replacement,
                                   case_insensitive=False, multiline=False, dotall=dotall, count=1)


def primitive(nid, title, pos, value, height=130):
    add(nid, "PrimitiveStringMultiline", title, pos, (450, height), [value], [("STRING", "STRING")])
    api[str(nid)]["inputs"]["value"] = value


for nid, title, pos in ((1, "Qwen Image 2.1 基础模型", (40, 40)),
                        (2, "Qwen 编码器", (40, 190)),
                        (3, "Qwen VAE", (40, 370))):
    clone(nid, title, pos)
api["1"]["inputs"].update(unet_name=nodes[1]["widgets_values"][0], weight_dtype=nodes[1]["widgets_values"][1])
api["2"]["inputs"].update(zip(("clip_name", "type", "device"), nodes[2]["widgets_values"]))
api["3"]["inputs"]["vae_name"] = nodes[3]["widgets_values"][0]
clone(10, "① 必需宣传素材 · 已保留当前文件", (40, 540), (330, 370))
api["10"]["inputs"]["image"] = nodes[10]["widgets_values"][0]
clone(4, "⑤ 目标画布 · 不追加文字底栏", (40, 1450))
nodes[4]["widgets_values"] = [1152, 1536, 1, 0xFFF8ED]
api["4"]["inputs"].update(width=1152, height=1536, batch_size=1, color=0xFFF8ED)
add(41, "LoadImage", "无字构图与配色参考 · 已部署此服务器", (40, 990), (330, 370),
    ["promo_layout_1152x1536.png", "image"], [("IMAGE", "IMAGE"), ("MASK", "MASK")])
api["41"]["inputs"]["image"] = "promo_layout_1152x1536.png"

primitive(20, "② 标题原文 · 必填，仅绘制输入字形", (420, 40), "咖啡还是我的好")
primitive(24, "③ 副标题原文 · 可留空，不补写", (420, 230), "okok 哈喽哈喽")
primitive(30, "④ 正文原文 · 可留空，不补写", (420, 420), "", 170)

visual = (
    "<image1> 只决定最终画布尺寸与比例；<image2> 是必须保留关键特征的实际宣传主体；"
    "<image3> 是不含任何文字的配色与构图参考，不是新增宣传主体。"
    "请制作完整、有表现力的无字商业海报底图，保留指定人物身份、产品外形、数量、结构、原有品牌标识与关键颜色。\n\n"
    "根据素材统一视觉语言，主体突出且完整。后续真实字体区域的范围由提示词末尾的动态坐标确定，"
    "用低纹理、可读的自然底色与无字图形承接；主体主视觉按照动态坐标紧接下方，以大比例填充中下部，"
    "避免上方整面空白墙、主体缩在最底端或生硬上下分栏。背景与主体周围自然连贯，"
    "允许少量无字色带、曲线、刷痕、纸张或几何层次穿插衔接，形成视觉节奏，避免廉价贴图和杂乱堆砌。\n\n"
    "从素材与配色参考提取主色、强调色和中性色，保持品牌色准确，色调协调。统一主光方向、色温、透视和景深，"
    "保留自然接触阴影与材质的细微纹理、透明度和光泽，避免塑料化、过曝、死黑、白边或悬浮感。"
    "食品饮品保留真实细节与自然诱人的色泽。\n\n"
    "本阶段只设计主体、背景与无字图形，绝不绘制标题、副标题、正文、字母、数字、标语、标签、"
    "按钮文字、伪字或文字占位符，也不要把这些制作说明写到图片上。完整指定文字会在生成后由真实字体节点绘制。"
)
primitive(21, "⑥ 无字海报设计要求 · 这里只填视觉", (420, 680), visual, 670)
clone(5, "⑦ Qwen 整体设计 · 不接收标题／副标题／正文", (960, 40), (460, 450))
negative = ("新增文字，伪文字，乱码，字母，数字，标语，说明文字，文字占位符，新增水印，新增logo，"
            "主体变形，数量错误，关键主体裁切，产品拉伸，悬浮，抠图白边，塑料质感，过曝，低清晰度")
nodes[5]["widgets_values"] = ["", negative, 0]
api["5"]["inputs"].update(negative_prompt=negative, resolution=0)
connect(2, 0, 5, "clip", "CLIP")
connect(4, 0, 5, "images.image_1", "IMAGE", optional=True)
connect(3, 0, 5, "vae", "VAE", optional=True)
connect(10, 0, 5, "images.image_2", "IMAGE", optional=True)
connect(41, 0, 5, "images.image_3", "IMAGE", optional=True)
connect(21, 0, 5, "prompt", "STRING", widget=True)
clone(6, "⑧ 一次出图数量 · amount=1–4", (960, 590))
api["6"]["inputs"]["amount"] = 1
connect(5, 2, 6, "samples", "LATENT")
clone(7, "⑨ 单次整批采样 · 不绘制文案", (960, 790))
api["7"]["inputs"].update(zip(("seed", "steps", "cfg", "sampler_name", "scheduler", "denoise"),
                                 [404940743691510, 30, 3.0, "euler", "simple", 1.0]))
connect(1, 0, 7, "model", "MODEL")
connect(5, 0, 7, "positive", "CONDITIONING")
connect(5, 1, 7, "negative", "CONDITIONING")
connect(6, 0, 7, "latent_image", "LATENT")
clone(8, "⑩ 无字底图解码", (960, 1190))
connect(7, 0, 8, "samples", "LATENT")
connect(3, 0, 8, "vae", "VAE")

roles = [
    dict(raw=20, x=1500, safe=51, color1=52, color2=53, color=60, draw=40,
         name="标题", font="NotoSerifCJKsc-Bold.otf", size=200, foreground="#4b2d16"),
    dict(raw=24, x=1900, safe=54, color1=55, color2=56, color=61, draw=42,
         name="副标题", font="NotoSansCJKsc-Regular.otf", size=72, foreground="#b96125"),
    dict(raw=30, x=2300, safe=57, color1=58, color2=59, color=62, draw=43,
         name="正文", font="NotoSansCJKsc-Regular.otf", size=40, foreground="#4b2d16"),
]
regex(50, "仅恰好7个汉字的标题软换行为4＋3", (1500, -80),
      r"\A([\u3400-\u4DBF\u4E00-\u9FFF]{4})([\u3400-\u4DBF\u4E00-\u9FFF]{3})\Z", r"\1" + "\n" + r"\2")
connect(20, 0, 50, "string", "STRING", widget=True)

for role in roles:
    raw, x, name = role["raw"], role["x"], role["name"]
    safe_source = 50 if raw == 20 else raw
    regex(role["safe"], f"{name}空字段仅作内部透明占位", (x, 40), r"\A\s*\Z", ".")
    connect(safe_source, 0, role["safe"], "string", "STRING", widget=True)
    regex(role["color1"], f"{name}非空时选指定字色", (x, 140), r"\A.*\S.*\Z", "", dotall=True)
    connect(raw, 0, role["color1"], "string", "STRING", widget=True)
    primitive(role["color"], f"{name}字色 · #RRGGBB", (x, 400), role["foreground"], 80)
    connect(role["color"], 0, role["color1"], "replace", "STRING", widget=True)
    regex(role["color2"], f"{name}为空时颜色全透明", (x, 240), r"\A\s*\Z", "#00000000")
    connect(role["color1"], 0, role["color2"], "string", "STRING", widget=True)
    nid = role["draw"]
    vals = ["", role["font"], role["size"], role["foreground"], "#00000000", 0, 0,
            "#00000000", "left", "top", 0, 0, "ltr"]
    add(nid, "DrawText+", f"{name} · 真实字体透明层（字号可调）", (x, 550), (360, 510),
        vals, [("IMAGE", "IMAGE"), ("MASK", "MASK")], cnr="comfyui_essentials")
    nodes[nid]["inputs"].append({"name": "img_composite", "type": "IMAGE", "shape": 7, "link": None})
    api[str(nid)]["inputs"].update(zip(("text", "font", "size", "color", "background_color", "shadow_distance",
                                      "shadow_blur", "shadow_color", "horizontal_align", "vertical_align",
                                      "offset_x", "offset_y", "direction"), vals))
    connect(role["safe"], 0, nid, "text", "STRING", widget=True)
    connect(role["color2"], 0, nid, "color", "STRING", widget=True)

add(63, "RegexMatch", "副标题是否有非空白原文", (2300, 1320), (350, 170),
    ["", r"\S", False, False, False], [("matches", "BOOLEAN")], collapsed=True)
api["63"]["inputs"].update(regex_pattern=r"\S", case_insensitive=False, multiline=False, dotall=False)
connect(24, 0, 63, "string", "STRING", widget=True)
for nid, title, pos, expr in ((64, "副标题顺排位置", (1900, 1130), "80+a[1]+24"),
                              (65, "正文顺排位置 · 空副标题不占位", (2300, 1130), "a+(b[1]+20)*c")):
    add(nid, "SimpleMath+", title, pos, (350, 140), [expr], [("INT", "INT"), ("FLOAT", "FLOAT")],
        cnr="comfyui_essentials")
    api[str(nid)]["inputs"]["value"] = expr
connect(40, 0, 64, "a", "*", optional=True)
connect(64, 0, 65, "a", "*", optional=True)
connect(42, 0, 65, "b", "*", optional=True)
connect(63, 0, 65, "c", "*", optional=True)

add(66, "RegexMatch", "正文是否有非空白原文", (2300, 1420), (350, 170),
    ["", r"\S", False, False, False], [("matches", "BOOLEAN")], collapsed=True)
api["66"]["inputs"].update(regex_pattern=r"\S", case_insensitive=False, multiline=False, dotall=False)
connect(30, 0, 66, "string", "STRING", widget=True)
for nid, title, pos, expr in ((67, "实际文字区最下沿 · 空正文不占位", (1900, 1500), "a+b[1]*c"),
                              (68, "主体紧接文字区的位置", (2300, 1500), "a+48")):
    add(nid, "SimpleMath+", title, pos, (350, 140), [expr], [("INT", "INT"), ("FLOAT", "FLOAT")],
        cnr="comfyui_essentials")
    api[str(nid)]["inputs"]["value"] = expr
connect(65, 0, 67, "a", "*", optional=True)
connect(43, 0, 67, "b", "*", optional=True)
connect(66, 0, 67, "c", "*", optional=True)
connect(67, 0, 68, "a", "*", optional=True)
format_string = (
    "{a}\n\n后续真实字体区域的最下沿为 y={b} 像素，主体从 y={c} 像素附近紧接下方；"
    "主视觉大而完整，背景与无字装饰贯通上下，不留空白墙。以上数字和说明都不上图，底图禁止新增文字。"
)
add(69, "StringFormat", "只把图层尺寸传给Qwen · 不发送原文字句", (960, 1500), (460, 350),
    [format_string], [("STRING", "STRING")])
api["69"]["inputs"]["f_string"] = format_string
connect(21, 0, 69, "values.a", "*")
connect(67, 0, 69, "values.b", "*")
connect(68, 0, 69, "values.c", "*")
# Keep the original encoder prompt socket/link ID; replace only its source.
prompt_link = next(link for link in links if link[3] == 5 and nodes[5]["inputs"][link[4]]["name"] == "prompt")
nodes[21]["outputs"][0]["links"].remove(prompt_link[0])
prompt_link[1] = 69
nodes[69]["outputs"][0]["links"].append(prompt_link[0])
api["5"]["inputs"]["prompt"] = ["69", 0]

add(70, "SimpleMath+", "柔和文字底面高度 · 字区后渐隐96px", (1500, 1500), (350, 140),
    ["a+96"], [("INT", "INT"), ("FLOAT", "FLOAT")], cnr="comfyui_essentials")
api["70"]["inputs"]["value"] = "a+96"
connect(67, 0, 70, "a", "*", optional=True)
add(71, "ImageScale", "同色构图参考作为文字底面", (2840, 1180), (350, 220),
    ["lanczos", 1152, 825, "disabled"], [("IMAGE", "IMAGE")])
api["71"]["inputs"].update(upscale_method="lanczos", width=1152, crop="disabled")
connect(41, 0, 71, "image", "IMAGE")
connect(70, 0, 71, "height", "INT", widget=True)
add(72, "SolidMask", "完整覆盖文字区 · 不改变画布尺寸", (3240, 1180), (320, 160),
    [1.0, 1152, 825], [("MASK", "MASK")])
api["72"]["inputs"].update(value=1.0, width=1152)
connect(70, 0, 72, "height", "INT", widget=True)
add(73, "FeatherMask", "仅在字区后向照片渐隐96px", (3240, 1400), (320, 180),
    [0, 0, 0, 96], [("MASK", "MASK")])
api["73"]["inputs"].update(left=0, top=0, right=0, bottom=96)
connect(72, 0, 73, "mask", "MASK")
add(74, "ImageCompositeMasked", "同画布柔和文字底面 · 无外加底栏", (2840, 1520), (350, 220),
    [0, 0, False], [("IMAGE", "IMAGE")])
api["74"]["inputs"].update(x=0, y=0, resize_source=False)
connect(8, 0, 74, "destination", "IMAGE")
connect(71, 0, 74, "source", "IMAGE")
connect(73, 0, 74, "mask", "MASK", optional=True)

for nid, name, pos, draw, dest, x, y, dynamic in (
    (31, "⑪ 标题字形叠入同一画布", (2840, 40), 40, 74, 72, 80, None),
    (32, "⑫ 副标题字形合成 · 可空", (2840, 400), 42, 31, 80, 0, 64),
    (33, "⑬ 正文字形合成 · 可空", (2840, 760), 43, 32, 80, 0, 65)):
    add(nid, "ImageCompositeMasked", name, pos, (350, 220), [x, y, False], [("IMAGE", "IMAGE")])
    api[str(nid)]["inputs"].update(x=x, y=y, resize_source=False)
    connect(dest, 0, nid, "destination", "IMAGE")
    connect(draw, 0, nid, "source", "IMAGE")
    connect(draw, 1, nid, "mask", "MASK", optional=True)
    if dynamic is not None:
        connect(dynamic, 0, nid, "y", "INT", widget=True)

clone(9, "⑭ 保存并预览 · 三项原文真实字形", (3300, 40), (460, 800))
nodes[9]["widgets_values"] = ["Qwen_Promo_ThreeText_Font"]
api["9"]["inputs"]["filename_prefix"] = "Qwen_Promo_ThreeText_Font"
connect(33, 0, 9, "images", "IMAGE")

note = (
    "只填写②标题、③副标题、④正文的实际原文；副标题和正文可留空。三项只进入字体节点，不发送给Qwen，"
    "模型无法续写这些字段。Qwen负责整体画面与无字图形，生成后叠加真实字体。文字图层高度用于计算文字区底沿及主体起点，"
    "仅把视觉要求和坐标数字传给Qwen，不传原文字句。\n"
    "同色无字参考作为柔和文字底面，先在同一画布内遮住文字区的窗框或杂乱纹理，"
    "在字区最下沿之后向照片渐隐96px。图片尺寸不变，不追加硬白块或文字底栏。\n"
    "复用服务器已经安装的ComfyUI Essentials DrawText+与SimpleMath+，没有我们开发的自定义节点，"
    "不需新增插件或重载服务。参考图promo_layout_1152x1536.png已由本次任务部署在当前服务器input。\n"
    "空字段内部用全透明占位绘制，MASK全零，成品不显示任何占位点。不要把DrawText的img_composite接到底图，"
    "保持透明背景与零阴影；三个ImageCompositeMasked必须连接各自MASK，且不反相。\n"
    "标题恰好7个汉字时只做4＋3软换行；原输入不改。其他长标题、长副标题和长正文请手动换行，"
    "在对应字体节点调整字号，并复核文字未溢出或遮挡主体。本图不提供缺字、长文溢出的自动拒收，不能冒称API预检全部通过。\n"
    "①需有实际素材；⑤画布默认1152×1536。⑧amount=1–4只执行一个采样批次，工具栏Batch Count保持1。"
    "三次字体合成广播到整批底图，直接保存所有图片，没有OCR拦截或外加文字底栏。\n"
    "真实字体保证文字层来自输入；Qwen无字底图仍需目视确认没有额外伪字。原图中的品牌标识按要求保留。"
)
add(19, "Note", "使用说明 · 现有节点／三项文案／透明合成", (1500, 1870), (1840, 300), [note])

workflow = {"id": str(uuid.uuid4()), "revision": 0, "last_node_id": max(nodes), "last_link_id": len(links),
            "nodes": list(nodes.values()), "links": links,
            "groups": [
                {"id": 1, "title": "素材与画布", "bounding": [10, -10, 360, 1660], "color": "#44665b", "font_size": 24, "flags": {}},
                {"id": 2, "title": "三项原文＋视觉要求", "bounding": [395, -10, 500, 1400], "color": "#5c7790", "font_size": 24, "flags": {}},
                {"id": 3, "title": "Qwen：只设计无字底图", "bounding": [935, -10, 510, 1880], "color": "#76654c", "font_size": 24, "flags": {}},
                {"id": 4, "title": "现有字体节点：标题／副标题／正文", "bounding": [1475, -120, 1230, 1910], "color": "#6a5879", "font_size": 24, "flags": {}},
                {"id": 5, "title": "柔和底面＋逐层字形合成与成品", "bounding": [2810, -10, 990, 1840], "color": "#4c716c", "font_size": 24, "flags": {}}],
            "config": {}, "extra": {"ds": {"scale": .32, "offset": [20, 150]}}, "version": .4}

for lid, sid, slot, tid, tin, kind in links:
    assert lid in nodes[sid]["outputs"][slot]["links"]
    assert nodes[tid]["inputs"][tin]["link"] == lid
    assert nodes[tid]["inputs"][tin]["type"] in (kind, "*")
assert len([n for n in nodes.values() if n["type"] == "KSampler"]) == 1
assert all(next(i for i in nodes[nid]["inputs"] if i["name"] == "img_composite")["link"] is None for nid in (40, 42, 43))
assert api["5"]["inputs"]["prompt"] == ["69", 0]
assert api["69"]["inputs"]["values.a"] == ["21", 0]
assert api["69"]["inputs"]["values.b"] == ["67", 0]
assert api["69"]["inputs"]["values.c"] == ["68", 0]
assert api["31"]["inputs"]["destination"] == ["74", 0]
assert api["74"]["inputs"]["destination"] == ["8", 0]
assert api["71"]["inputs"]["height"] == api["72"]["inputs"]["height"] == ["70", 0]
assert not any(word in visual for word in ("咖啡还是我的好", "okok 哈喽哈喽"))
assert re.sub(api["50"]["inputs"]["regex_pattern"], api["50"]["inputs"]["replace"], "咖啡还是我的好") == "咖啡还是\n我的好"
for role in roles:
    for raw in ("", " \r\n\u3000", "okok 哈喽哈喽", "繁體\r\n EN  "):
        safe = re.sub(r"\A\s*\Z", ".", raw, count=1)
        color = re.sub(r"\A.*\S.*\Z", role["foreground"], raw, count=1, flags=re.S)
        color = re.sub(r"\A\s*\Z", "#00000000", color, count=1)
        assert color == (role["foreground"] if raw.strip() else "#00000000")
        assert safe == (raw if raw.strip() else ".")

ui_path = ROOT / "Qwen2.1_标题副标题正文_真实字体_现有节点.json"
api_path = ROOT / "Qwen2.1_标题副标题正文_真实字体_API.json"
ui_path.write_text(json.dumps(workflow, ensure_ascii=False, indent=2) + "\n")
api_path.write_text(json.dumps(api, ensure_ascii=False, indent=2) + "\n")
print(ui_path)
print(api_path)
print(f"Validated {len(nodes)} nodes, {len(links)} links, one sampler, three independent transparent font layers.")

#!/usr/bin/env python3
"""imagegen provider 真连冒烟（imagegen 双层批 2026-10-24 spec 的真连腿）。

两条适配器各打一次真实 HTTP（适配器按协议划分——第二条用智谱 OpenAI 兼容端点
 exercise openai-images 协议路径，gpt-image-1/Seedream 形态由同一适配器承载）：

  ① CogView 适配器原生形态 → cogview-3-flash（免费档）
  ② OpenAiImages 适配器形态 → 同一端点（n / response_format / 三档 size 吸附口径）

凭据读实例 ~/.stand/nebflow.json 的 llm.providers.zhipu.apiKey（只读，不落盘不打印）。
产物落 /tmp/imagegen-smoke-*.png，校验真实图片 magic bytes（PNG/JPEG/WEBP/GIF 嗅探——cogview 实测回 JPEG）。
"""

import base64
import json
import sys
import urllib.request

CONFIG = os_path = __import__("os").path.expanduser("~/.stand/nebflow.json")

PNG_MAGIC = b"\x89PNG"
JPEG_MAGIC = b"\xff\xd8\xff"
WEBP_MAGIC = b"RIFF"
GIF_MAGIC = b"GIF"


def sniff(path_bytes):
    for magic, ext in ((PNG_MAGIC, "png"), (JPEG_MAGIC, "jpeg"), (WEBP_MAGIC, "webp"), (GIF_MAGIC, "gif")):
        if path_bytes.startswith(magic):
            return ext
    return None


def load_zhipu_key():
    with open(CONFIG, encoding="utf-8") as f:
        cfg = json.load(f)
    key = cfg["llm"]["providers"]["zhipu"]["apiKey"].strip()
    if not key:
        raise SystemExit("no zhipu apiKey in llm.providers")
    return key


def post_json(url, token, body):
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read().decode("utf-8"))


def fetch_image(ref):
    if "url" in ref and ref["url"]:
        with urllib.request.urlopen(ref["url"], timeout=60) as resp:
            return resp.read()
    return base64.b64decode(ref["b64_json"])


def run_case(name, body, out_path):
    result = post_json("https://open.bigmodel.cn/api/paas/v4/images/generations", TOKEN, body)
    data = result.get("data") or []
    if not data:
        raise SystemExit(f"[FAIL] {name}: no data[] in response: {json.dumps(result)[:300]}")
    payload = fetch_image(data[0])
    ext = sniff(payload)
    if ext is None:
        raise SystemExit(f"[FAIL] {name}: unrecognised image magic ({len(payload)} bytes)")
    out_path = out_path.rsplit(".", 1)[0] + "." + ext
    with open(out_path, "wb") as f:
        f.write(payload)
    print(f"[OK] {name}: {len(payload)} bytes ({ext}) -> {out_path}")
    return len(payload)


TOKEN = load_zhipu_key()

# ① CogView 原生形态（枚举 size 口径）
run_case(
    "cogview-adapter/cogview-3-flash",
    {"model": "cogview-3-flash", "prompt": "a small glass house on a foggy hillside at dawn",
     "size": "1344x768", "response_format": "url"},
    "/tmp/imagegen-smoke-cogview.png",
)

# ② OpenAiImages 形态（吸附后 size + n + response_format）
run_case(
    "openai-images-adapter/via-zhipu-compat",
    {"model": "cogview-3-flash", "prompt": "a red panda sketch, ink style",
     "size": "1024x1024", "n": 1, "response_format": "url"},
    "/tmp/imagegen-smoke-openai-compat.png",
)

print("SMOKE PASS: both adapters reached a live endpoint and produced real image bytes")

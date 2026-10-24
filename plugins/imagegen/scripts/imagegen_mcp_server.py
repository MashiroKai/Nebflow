#!/usr/bin/env python3
"""imagegen MCP server（imagegen 双层批 2026-10-24，腿②插件模式）——调试中 / experimental。

stdio JSON-RPC 2.0（newline-delimited，MCP 2025-06-18 子集：initialize /
notifications/initialized / tools/list / tools/call）。单工具 generate_image：
把调用翻译成本机引擎环回路由 POST http://127.0.0.1:<port>/api/imagegen 的**阻塞**
HTTP 调用——路由侧走与腿①内置工具完全相同的 provider 层（配置同读 nebflow.json
imagegen 节），返回落盘 PNG 绝对路径。无第三方依赖（纯 stdlib）。

端口解析序：--port 参数 > $STAND_GATEWAY_PORT > $NEBFLOW_GATEWAY_PORT > 8080。
"""

import json
import os
import sys
import urllib.error
import urllib.request

PROTOCOL_VERSION = "2025-06-18"
SERVER_NAME = "stand-imagegen"
SERVER_VERSION = "0.1.0"

TOOL_NAME = "generate_image"
TOOL_DESCRIPTION = (
    "Generate an image from a text prompt (DEBUG/experimental). BLOCKING: the call "
    "waits for generation to finish and returns the saved PNG absolute path. "
    "Providers/config come from the instance nebflow.json `imagegen` section "
    "(cogview / openai-images / imagen adapters). Root sessions should use the "
    "built-in non-blocking generate_image tool instead."
)
TOOL_SCHEMA = {
    "type": "object",
    "properties": {
        "prompt": {
            "type": "string",
            "description": "Text description of the image to generate (subject, style, composition).",
        },
        "size": {
            "type": "string",
            "description": 'Optional, "WxH" form (default 1024x1024); snapped to the nearest size the model supports.',
        },
        "provider": {
            "type": "string",
            "description": "Optional provider name from imagegen.providers; defaults to imagegen.defaultProvider.",
        },
        "quality": {
            "type": "string",
            "description": "Optional quality hint for OpenAI-images providers (low|medium|high).",
        },
    },
    "required": ["prompt"],
}


def resolve_port(argv):
    if len(argv) > 2 and argv[1] == "--port":
        return int(argv[2])
    for var in ("STAND_GATEWAY_PORT", "NEBFLOW_GATEWAY_PORT"):
        val = os.environ.get(var)
        if val and val.isdigit():
            return int(val)
    return 8080


PORT = resolve_port(sys.argv)
ENDPOINT = f"http://127.0.0.1:{PORT}/api/imagegen"


def send_json(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def handle_request(req):
    method = req.get("method")
    req_id = req.get("id")

    if method == "initialize":
        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {"tools": {}},
                "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
            },
        }

    if method == "notifications/initialized":
        return None  # notification: no response frame

    if method == "tools/list":
        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {"tools": [{
                "name": TOOL_NAME,
                "description": TOOL_DESCRIPTION,
                "inputSchema": TOOL_SCHEMA,
            }]},
        }

    if method == "tools/call":
        params = req.get("params") or {}
        if params.get("name") != TOOL_NAME:
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "error": {"code": -32602, "message": f"unknown tool: {params.get('name')}"},
            }
        args = params.get("arguments") or {}
        body = json.dumps({
            "prompt": args.get("prompt", ""),
            "size": args.get("size"),
            "provider": args.get("provider"),
            "quality": args.get("quality"),
        }).encode("utf-8")
        request = urllib.request.Request(
            ENDPOINT, data=body, headers={"Content-Type": "application/json"}, method="POST"
        )
        try:
            with urllib.request.urlopen(request, timeout=170) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
            text = (
                f"Image generated ({payload.get('provider', 'imagegen')}): {payload['path']}\n"
                f"bytes={payload.get('bytes')}\n"
                f"debug=调试中"
            )
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {"content": [{"type": "text", "text": text}], "isError": False},
            }
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", errors="replace")[:400]
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {"content": [{"type": "text",
                                        "text": f"imagegen failed: HTTP {e.code}: {detail}"}],
                           "isError": True},
            }
        except Exception as e:  # noqa: BLE001 — transport error surface as tool result
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {"content": [{"type": "text",
                                        "text": f"imagegen transport error: {e}"}],
                           "isError": True},
            }

    # ping / unknown notifications: per JSON-RPC, method-not-found for calls only
    if req_id is None:
        return None
    return {
        "jsonrpc": "2.0",
        "id": req_id,
        "error": {"code": -32601, "message": f"method not found: {method}"},
    }


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except json.JSONDecodeError:
            send_json({"jsonrpc": "2.0", "id": None,
                       "error": {"code": -32700, "message": "parse error"}})
            continue
        response = handle_request(req)
        if response is not None:
            send_json(response)


if __name__ == "__main__":
    main()

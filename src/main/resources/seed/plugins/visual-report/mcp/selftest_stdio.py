#!/usr/bin/env python3
"""selftest_stdio — 不经引擎、直接跑一遍 MCP 握手与五个工具的 stdio 冒烟。

用法（Windows 绝对路径 Python）：
  python mcp/selftest_stdio.py --vault D:/workspace@zcode/nebflow/nebflow-stand/docs

它按引擎 `StdioTransport` 的口径（逐行 JSON、数字 id 逐字回显、UTF-8）驱动本插件
的 mcp.json 同款命令，因此「本脚本全绿」= 装载后引擎侧同样能跑通；区别只在于
引擎的最终证据是日志行 `MCP server 'plugin_visual-report_glossary' connected, N tools
registered`（由 McpManager.scala:106 打出）。
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent


class Client:
    def __init__(self, cmd: list[str], cwd: str, env: dict):
        self.proc = subprocess.Popen(cmd, cwd=cwd, env=env, stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.pid = 1

    def send(self, method: str, params=None, notify=False):
        frame = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            frame["params"] = params
        if not notify:
            frame["id"] = self.pid
            self.pid += 1
        self.proc.stdin.write((json.dumps(frame, ensure_ascii=False) + "\n").encode("utf-8"))
        self.proc.stdin.flush()
        if notify:
            return None
        line = self.proc.stdout.readline()
        if not line:
            err = self.proc.stderr.read().decode("utf-8", "replace")
            raise RuntimeError(f"server closed the pipe; stderr:\n{err}")
        return json.loads(line.decode("utf-8"))

    def call(self, name: str, args: dict) -> dict:
        resp = self.send("tools/call", {"name": name, "arguments": args})
        if "error" in resp:
            raise RuntimeError(f"tool {name} error: {resp['error']}")
        text = "\n".join(c.get("text", "") for c in resp["result"].get("content", []))
        return json.loads(text) if text.startswith(("{", "[")) else {"text": text}

    def close(self):
        try:
            self.proc.stdin.close()
        except OSError:
            pass
        try:
            self.proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        return self.proc.stderr.read().decode("utf-8", "replace")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--vault", required=True, help="项目 docs/ 目录（验收用真实 vault）")
    ap.add_argument("--python", default=sys.executable)
    ap.add_argument("--data", default=str(Path(os.environ.get("TEMP") or "/tmp") / "visual-report-glossary-selftest"),
                    help="索引数据目录（默认系统临时目录，绝不写进插件包）")
    ap.add_argument("--query", default="段落锚")
    args = ap.parse_args()

    plugin_root = HERE.parent
    env = dict(os.environ)
    tmp_data = args.data
    env.update({
        "PLUGIN_ROOT": str(plugin_root),
        # 索引默认落临时目录：冒烟测试绝不把 .db 写进插件包（那会漂 digest、被打包）
        "PLUGIN_DATA": tmp_data,
        "GLOSSARY_CHECKER": str(plugin_root / "skills" / "wiki-docs" / "scripts" / "check_wiki_docs.py"),
        "PYTHONIOENCODING": "utf-8",
    })
    cmd = [args.python, "-X", "utf8", str(HERE / "glossary_server.py")]
    c = Client(cmd, str(plugin_root), env)
    ok = True
    try:
        init = c.send("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                     "clientInfo": {"name": "selftest", "version": "1"}})
        assert init["result"]["serverInfo"]["name"], init
        print("initialize          OK", init["result"]["serverInfo"])
        c.send("notifications/initialized", notify=True)
        tools = c.send("tools/list", {})["result"]["tools"]
        print(f"tools/list          OK {len(tools)} tools: {[t['name'] for t in tools]}")
        assert len(tools) == 6

        s = c.call("glossary_search", {"query": args.query, "vault": args.vault, "limit": 5})
        print(f"glossary_search     OK count={s['count']} rebuilt={s['indexRebuilt']}")
        for h in s["results"][:5]:
            print(f"   - {h['slug']:<20} {h['name']}  ({h['source']}, backlinks={h['backlinks']})")

        slug = s["results"][0]["slug"] if s["results"] else "vault"
        look = c.call("glossary_lookup", {"slug": slug, "vault": args.vault})
        print(f"glossary_lookup     OK slug={slug} backlinkDocs={look.get('backlinkDocsCount')}")
        for d in (look.get("backlinkDocs") or [])[:3]:
            print(f"   - {d}")

        links = c.call("glossary_links", {"slug": slug, "vault": args.vault})
        print(f"glossary_links      OK slug-mode backlinks={links['backlinkCount']}")
        links2 = c.call("glossary_links", {"doc": "index.md", "vault": args.vault})
        print(f"glossary_links      OK doc-mode entries={len(links2.get('entries') or [])}")

        re_i = c.call("glossary_reindex", {"vault": args.vault})
        print(f"glossary_reindex    OK {re_i['elapsed_ms']}ms docs={re_i['docs']} stats={re_i['stats']['entries']}")

        val = c.call("glossary_validate", {"vault": args.vault})
        print(f"glossary_validate   OK total={val['total']} checker={val['checkerViolations']} "
              f"added={val['addedByGlossaryLayer']} buckets={val['countsByBucket']}")
        for v in val["violations"][:5]:
            print(f"   - {v['file']}:{v['line']} [{v['rule']}] {v['detail'][:70]}")
    except Exception as exc:  # noqa: BLE001
        ok = False
        print(f"FAILED: {type(exc).__name__}: {exc}")
    finally:
        err = c.close()
    if err.strip():
        print("--- server stderr ---")
        print(err.strip()[-2000:])
    print("RESULT", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())

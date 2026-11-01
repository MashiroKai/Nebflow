#!/usr/bin/env python3
"""build-fixtures.py — 续聊会话「面板正文空白」标本生成器（2026-11-01 报障批）。

WHAT
  从实盘会话（默认 `delegate-nebflow-8bbf8cf7` = 2026-11-01 的一条 Mail 续聊
  delegate）生成两份**历史回执载荷**（GET/WS `getHistory` → `historyPage.messages`
  的真实形状）：

    injected-only.json  改前实盘 .ui.json **逐字节**（22 条注入行，零 ai/tool 行）
                        = 报障现场：面板只剩归因徽章行。
    with-body.json      同一会话在**修后**（wsSend 桥通、录制层能落 ai/tool/thinking
                        行）应有的 ui.json：注入行原文不动 + 由该会话**真实 transcript**
                        （<sid>.json，LLM 消息流水）按录制层规则重投影出的 ai/tool 行。

WHY 重投影而不是造数
  改动在**取数腿**（MailTool.continueDelegate 的 wsSend），历史行是引擎按帧落盘的
  产物，实盘里这批行**从未存在**（帧在发射处被吞），所以没有任何现存文件可供摘取；
  本脚本因此用该会话自己的 transcript（真实内容）按 `WebSocketRoutes#makeRecordingWsSend`
  同规则重放：assistant 文本/思考 → UiMessage.Ai（toolStart 处冲刷、轮末 terminal=true），
  tool_use + tool_result → UiMessage.Tool。正文/思考/工具输入与结果**全部为真实内容**；
  只有 label/summary 的**排版串**是近似（引擎侧按工具各自格式化，本脚本用通用式），
  这一项对「正文是否渲染」的判据不承载语义。

RUN
  python3 tests/fixtures/continuation-panel/build-fixtures.py [--sid delegate-…] \
      [--sessions ~/.stand/sessions]

工具：python3 标准库，零依赖。
"""

import argparse
import json
import os
import sys

TOOL_CONTENT_CAP = 1200  # 单条工具结果落盘正文上限（截断，保持标本体积可控）


def load(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def blocks(msg):
    c = msg.get("content")
    if isinstance(c, str):
        return [{"type": "text", "text": c}]
    return c or []


def short_summary(text):
    t = (text or "").strip()
    if not t:
        return "(no output)"
    first = t.splitlines()[0][:80]
    n = len(t.splitlines())
    return first if n <= 1 else f"{n} lines of output"


def approx_label(name, inp):
    """工具卡标签近似面（真实排版由引擎按工具格式化 —— 见文件头 WHY）。"""
    keys = ("command", "file_path", "path", "pattern", "url", "prompt", "task", "description")
    hint = ""
    for k in keys:
        v = inp.get(k) if isinstance(inp, dict) else None
        if isinstance(v, str) and v.strip():
            hint = v.strip().replace("\n", " ")[:110]
            break
    return f"{name}({hint})" if hint else f"{name}()"


def project(transcript, t0, model=None):
    """transcript → [UiMessage.Ai | UiMessage.Tool …]（录制层同规则重放）。"""
    rows = []
    ts = t0
    # tool_use id → (name, input, order)
    pending = {}
    # 每个 assistant 消息 = 一轮：text/thinking 攒着，遇到 tool_use 即冲刷一行 Ai。
    for msg in transcript:
        role = msg.get("role")
        bl = blocks(msg)
        if role == "assistant":
            text_parts, think_parts = [], []
            for b in bl:
                bt = b.get("type")
                if bt == "text":
                    text_parts.append(b.get("text") or "")
                elif bt in ("thinking", "redacted_thinking"):
                    think_parts.append(b.get("thinking") or "")
                elif bt == "tool_use":
                    ts += 1
                    text = "".join(text_parts).strip()
                    thinking = "".join(think_parts).strip()
                    if text or thinking:
                        row = {"type": "ai", "timestamp": ts, "text": text}
                        if thinking:
                            row["thinking"] = thinking
                        if model:
                            row["model"] = model
                        rows.append(row)
                        text_parts, think_parts = [], []
                    pending[b.get("id")] = (b.get("name") or "tool", b.get("input") or {})
            text = "".join(text_parts).strip()
            thinking = "".join(think_parts).strip()
            if text or thinking:
                ts += 1
                row = {"type": "ai", "timestamp": ts, "text": text}
                if thinking:
                    row["thinking"] = thinking
                if model:
                    row["model"] = model
                rows.append(row)
        elif role == "user":
            for b in bl:
                if b.get("type") != "tool_result":
                    continue
                name, inp = pending.pop(b.get("toolUseId"), ("tool", {}))
                content = b.get("content")
                if isinstance(content, list):
                    content = "\n".join(
                        (x.get("text") or "") if isinstance(x, dict) else str(x) for x in content
                    )
                content = content or ""
                ts += 1
                rows.append(
                    {
                        "type": "tool",
                        "label": approx_label(name, inp),
                        "summary": short_summary(content),
                        "content": content[:TOOL_CONTENT_CAP],
                        "isError": bool(b.get("isError")),
                        "input": json.dumps(inp, ensure_ascii=False)[:TOOL_CONTENT_CAP],
                    }
                )
    # 轮末终态（replay-terminal 批口径）：每轮最后一行 Ai 盖 terminal=true + durationMs，
    # 让 turn-header（归因行）在历史回放腿照常出——面板「有徽章行也有正文」的判据面。
    for i, r in enumerate(rows):
        if r["type"] != "ai":
            continue
        nxt = rows[i + 1] if i + 1 < len(rows) else None
        if nxt is None or nxt["type"] != "ai":
            r["terminal"] = True
            r["durationMs"] = 1000 + (i % 7) * 250
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sid", default="delegate-nebflow-8bbf8cf7")
    ap.add_argument("--sessions", default=os.path.expanduser("~/.stand/sessions"))
    ap.add_argument("--out", default=os.path.dirname(os.path.abspath(__file__)))
    a = ap.parse_args()

    ui_path = os.path.join(a.sessions, a.sid + ".ui.json")
    tp_path = os.path.join(a.sessions, a.sid + ".json")
    for p in (ui_path, tp_path):
        if not os.path.exists(p):
            print(f"missing: {p}", file=sys.stderr)
            return 1

    ui = load(ui_path)              # 改前实盘载荷（注入行）
    transcript = load(tp_path)
    if isinstance(transcript, dict):
        transcript = transcript.get("messages") or []
    if not isinstance(ui, list) or not ui:
        print("ui.json is not a message list", file=sys.stderr)
        return 1

    t0 = int(ui[0].get("timestamp") or 0) or 1_760_000_000_000
    body = project(transcript, t0)

    injected_only = ui
    with_body = [ui[0]] + body + ui[1:]

    def dump(name, rows):
        p = os.path.join(a.out, name)
        with open(p, "w", encoding="utf-8") as f:
            json.dump(rows, f, ensure_ascii=False, indent=1)
        print(f"{p}  rows={len(rows)}")

    dump("injected-only.json", injected_only)
    dump("with-body.json", with_body)
    print(f"source: {a.sid}  (ui rows={len(ui)}, projected={len(body)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())

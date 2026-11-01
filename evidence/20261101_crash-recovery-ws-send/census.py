#!/usr/bin/env python3
"""census.py — 「重启后某些会话面板空白」取证普查（wsSend 桥接批 2026-11-01 · 同类可疑点②）。

判据（全部可从实盘复算，非手抄）：
  A. **node-* 全局面**：`~/.stand/sessions/node-<8hex>.ui.json` 里 ai/tool 行的分布。
     ai/tool/thinking 三类行的唯一写者是录制层（`WebSocketRoutes#makeRecordingWsSend`，
     按帧内 nodeSessionId 落盘）；注入行不经 wsSend（`AgentActor#emitInjectedUserEvent`
     直写 SessionStore）⇒ 「只有 user 行、零 ai/tool」正是面板只剩归因徽章行的形态。
     再对零 ai+tool 的会话读 transcript，统计「真有助手产出（干过活）却零正文行」的数量
     ——这才是可判读的「实测空白」。
  B. **重启恢复窗口面**：`**/.nebflow/flow-map-events.jsonl` 里 type=boot-recovery 且
     summary 带 `sessionRef=node-…` 的条目（= boot 认领（resume）或被认领失败的节点会话）。
     对每条认领，统计 claim 时刻之后该会话的 transcript 消息数与 ui.json 行数：
     「claim 后有工作但 claim 后零 ai/tool 行」= 恢复窗口实测空白。
  C. **设备腿样本面**：`~/.stand/delegates/*.json` 中 `delegatedBy` 命中的条数
     （= 本机收到过的设备委托会话；0 则同类可疑点①无实盘样本）。

RUN: python3 evidence/20261101_crash-recovery-ws-send/census.py [--json]
"""

import argparse
import collections
import glob
import json
import os
import re
import sys

SESS = os.path.expanduser("~/.stand/sessions")


def rows_of(path):
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, list) else None
    except Exception:
        return None


def type_counts(rows):
    return collections.Counter(r.get("type") for r in rows if isinstance(r, dict))


def has_assistant_text(msgs):
    if not msgs:
        return False
    for m in msgs:
        if not isinstance(m, dict):
            continue
        if m.get("role") != "assistant":
            continue
        c = m.get("content")
        if isinstance(c, str) and c.strip():
            return True
        if isinstance(c, list):
            for part in c:
                if isinstance(part, dict) and isinstance(part.get("text"), str) and part["text"].strip():
                    return True
    return False


def surface_a():
    out = {"sessions_with_ui": 0, "zero_ai_tool": 0, "zero_with_work": 0, "with_ai_tool": 0}
    samples = []
    for p in glob.glob(os.path.join(SESS, "node-*.ui.json")):
        rows = rows_of(p)
        if rows is None:
            continue
        out["sessions_with_ui"] += 1
        c = type_counts(rows)
        sid = os.path.basename(p)[: -len(".ui.json")]
        if c.get("ai", 0) + c.get("tool", 0) == 0:
            out["zero_ai_tool"] += 1
            msgs = rows_of(os.path.join(SESS, sid + ".json"))
            if has_assistant_text(msgs):
                out["zero_with_work"] += 1
                if len(samples) < 5:
                    samples.append(
                        {
                            "session": sid,
                            "ui_rows": len(rows),
                            "user_rows": c.get("user", 0),
                            "transcript_msgs": len(msgs or []),
                        }
                    )
        else:
            out["with_ai_tool"] += 1
    out["samples_blank_with_work"] = samples
    return out


def surface_b():
    claims = []
    for p in glob.glob(os.path.expanduser("~/.stand/**/.nebflow/flow-map-events.jsonl"), recursive=True):
        try:
            fh = open(p, "r", encoding="utf-8", errors="replace")
        except Exception:
            continue
        with fh:
            for line in fh:
                if "boot-recovery" not in line:
                    continue
                try:
                    e = json.loads(line)
                except Exception:
                    continue
                if e.get("type") != "boot-recovery":
                    continue
                m = re.search(r"sessionRef=(node-[0-9a-f]+)", e.get("summary") or "")
                if not m:
                    continue
                claims.append(
                    {
                        "ts": e.get("ts") or 0,
                        "session": m.group(1),
                        "summary": (e.get("summary") or "")[:80],
                        "log": p,
                    }
                )
    out = {
        "claims": len(claims),
        "resume": 0,
        "failed": 0,
        "blank_after_claim": 0,
        "inject_only_after_claim": 0,
        "measurable": 0,
        "rows": [],
    }
    for c in claims:
        if c["summary"].startswith("claimed (resume)"):
            out["resume"] += 1
        elif c["summary"].startswith("failed"):
            out["failed"] += 1
        msgs = rows_of(os.path.join(SESS, c["session"] + ".json")) or []
        rows = rows_of(os.path.join(SESS, c["session"] + ".ui.json")) or []
        post_msgs = [m for m in msgs if isinstance(m, dict) and (m.get("timestamp") or 0) > c["ts"]]
        post_rows = [r for r in rows if isinstance(r, dict) and (r.get("timestamp") or 0) > c["ts"]]
        pc = type_counts(post_rows)
        rec = {
            "session": c["session"],
            "claim": c["summary"],
            "transcript_msgs": len(msgs),
            "post_claim_msgs": len(post_msgs),
            "post_claim_rows": len(post_rows),
            "post_claim_ai_tool": pc.get("ai", 0) + pc.get("tool", 0),
            "post_claim_user": pc.get("user", 0),
        }
        out["rows"].append(rec)
        if rec["post_claim_msgs"] > 0:
            out["measurable"] += 1
            if rec["post_claim_rows"] == 0:
                out["blank_after_claim"] += 1
            elif rec["post_claim_ai_tool"] == 0:
                out["inject_only_after_claim"] += 1
    return out


def surface_c():
    total = 0
    delegated = 0
    for p in glob.glob(os.path.expanduser("~/.stand/delegates/*.json")):
        total += 1
        try:
            d = json.load(open(p, encoding="utf-8"))
        except Exception:
            continue
        if isinstance(d, dict) and d.get("delegatedBy"):
            delegated += 1
    return {"delegate_records": total, "delegated_by_hits": delegated}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()
    res = {"A_node_ui_scan": surface_a(), "B_boot_recovery_window": surface_b(), "C_device_leg_samples": surface_c()}
    if a.json:
        json.dump(res, sys.stdout, ensure_ascii=False, indent=2)
        print()
        return
    A, B, C = res["A_node_ui_scan"], res["B_boot_recovery_window"], res["C_device_leg_samples"]
    print("A. node-* 会话面板取数面")
    print(f"   有 ui.json: {A['sessions_with_ui']}  零 ai+tool: {A['zero_ai_tool']}  有 ai/tool: {A['with_ai_tool']}")
    print(f"   其中『干过活却零正文行』: {A['zero_with_work']}")
    for s in A["samples_blank_with_work"]:
        print(f"     标本 {s['session']}: ui 行 {s['ui_rows']}（user {s['user_rows']}）/ transcript {s['transcript_msgs']} 条")
    print("B. 重启恢复窗口（boot-recovery sessionRef 面）")
    print(f"   认领事件: {B['claims']}（resume {B['resume']} / failed {B['failed']}）  可测: {B['measurable']}")
    print(f"   claim 后零行: {B['blank_after_claim']}   claim 后只有注入行: {B['inject_only_after_claim']}")
    for r in B["rows"]:
        print(
            f"     {r['session']}: post_msgs={r['post_claim_msgs']} post_rows={r['post_claim_rows']}"
            f" (ai+tool={r['post_claim_ai_tool']}, user={r['post_claim_user']})  [{r['claim']}]"
        )
    print("C. 设备腿样本面")
    print(f"   delegate 记录: {C['delegate_records']}  delegatedBy 命中: {C['delegated_by_hits']}")


if __name__ == "__main__":
    main()

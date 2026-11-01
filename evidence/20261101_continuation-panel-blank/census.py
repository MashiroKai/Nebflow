#!/usr/bin/env python3
"""census.py — 续聊会话「面板正文空白」判别变量普查（2026-11-01 报障批）。

判据：对实盘 `~/.stand/subagent-tasks/<root>.json` 里每一条 `delegate-*` 任务行，
读它的 `.ui.json`（面板历史回执的唯一来源），数 `ai`/`tool`/`user` 三类行数，并与
任务行的 `prompt`（续聊腿 = `MailTool.continueDelegate` 生成的 brief，首行恒
`[continuation of delegate …`）交叉。

结论（本脚本重新生成，非手抄）：
  · continuation 会话：ai+tool 行数恒 0（100%）⇒ 面板正文空白（只剩注入行 = 归因徽章行）；
  · 非 continuation 会话：ai+tool 行数 > 0（除 failed/cancelled 零工作会话）。

RUN: python3 evidence/20261101_continuation-panel-blank/census.py [--json]
"""

import argparse
import collections
import json
import os
import glob
import sys

SESS = os.path.expanduser("~/.stand/sessions")
TASKS = os.path.expanduser("~/.stand/subagent-tasks")


def ui_counts(sid):
    p = os.path.join(SESS, sid + ".ui.json")
    if not os.path.exists(p):
        return None
    try:
        with open(p, "r", encoding="utf-8") as f:
            rows = json.load(f)
    except Exception:
        return None
    if not isinstance(rows, list):
        return None
    c = collections.Counter(m.get("type") for m in rows if isinstance(m, dict))
    return {"ai": c.get("ai", 0), "tool": c.get("tool", 0), "user": c.get("user", 0), "n": len(rows)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()

    agg = collections.Counter()
    rows = []
    for f in sorted(glob.glob(os.path.join(TASKS, "*.json"))):
        try:
            with open(f, "r", encoding="utf-8") as fh:
                tasks = json.load(fh)
        except Exception:
            continue
        if not isinstance(tasks, list):
            continue
        for t in tasks:
            if not isinstance(t, dict):
                continue
            tid = str(t.get("taskId") or "")
            if not tid.startswith("delegate-"):
                continue
            cnt = ui_counts(tid)
            if cnt is None:
                continue
            cont = str(t.get("prompt") or "").startswith("[continuation")
            has_body = (cnt["ai"] + cnt["tool"]) > 0
            agg[(cont, has_body)] += 1
            if not has_body:
                rows.append(
                    {
                        "sessionId": tid,
                        "continuation": cont,
                        "status": t.get("status"),
                        "mode": t.get("mode"),
                        "ui": cnt,
                        "promptHead": str(t.get("prompt") or "")[:70].replace("\n", " "),
                    }
                )

    out = {
        "census": [{"continuation": k[0], "hasBodyRows": k[1], "count": v} for k, v in sorted(agg.items())],
        "zeroBodyRows": rows,
    }
    if a.json:
        print(json.dumps(out, ensure_ascii=False, indent=1))
        return 0

    print("continuation?  has ai+tool rows  count")
    for (cont, has), n in sorted(agg.items()):
        print(f"  {str(cont):5s}        {str(has):5s}          {n}")
    print(f"\n零正文行会话 {len(rows)} 条：")
    for r in rows:
        print(f"  {r['sessionId']:34s} cont={str(r['continuation']):5s} {r['status']:10s} ui={r['ui']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

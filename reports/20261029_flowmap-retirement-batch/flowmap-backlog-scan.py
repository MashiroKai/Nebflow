#!/usr/bin/env python3
"""
flowmap-backlog-scan.py — delegate 作用域目录存量清算扫描器（R3 批 2026-10-29）。

只读（--apply 才删）：枚举各工作区 `.nebflow/flow-maps/<delegateId>/`，对每个目录做
§5.6 语义的清算分类（有终态且结果已交付→删；在飞/无终态/blocked→保留并列表报），
产出机器可核对的 JSON 清单 + 人读 Markdown 报告。

分类判据（与 DelegateFlowMapLifecycle / §5.6 逐条对齐）：
  DELETE  = registry 记录在 ∧ archivedAt 在（终态清理序列跑完 = 结果已交付）
            ∧ 活动区无 open 节点（pending/wiring/running/interrupted）
            ∧ 活动区无 blocked 节点（blocked 永不自动处置——人工裁定域）
            ∧ 非本批自身作用域
  KEEP    = 其余，逐目录给 reason（no-record / in-flight / blocked / not-archived / self）

用法：
  python3 flowmap-backlog-scan.py --dry-run            # 只出清单（默认）
  python3 flowmap-backlog-scan.py --apply --manifest <dry-run.json>
                                                       # 按 dry-run 清单执行 DELETE 类删除
"""

import argparse
import json
import os
import sys
import time

DATA_ROOT = os.path.expanduser("~/.stand")
DELEGATES_DIR = os.path.join(DATA_ROOT, "delegates")
SELF_ID = "delegate-nebflow-88974c4d"  # 本批自身的会话作用域——硬排除

OPEN_STATUSES = {"pending", "wiring", "running", "interrupted"}
BLOCKED = "blocked"

WORKSPACES = [
    ("/Users/kaiyu/Claude code/Stand", "stand"),
    ("/Users/kaiyu/Claude code/DOSTEL", "DOSTEL"),
    (os.path.join(DATA_ROOT, "general"), "general(dataRoot)"),
    (os.path.join(DATA_ROOT, "projects/stand-website"), "stand-website"),
    (os.path.join(DATA_ROOT, "projects/link-server"), "link-server"),
    (os.path.join(DATA_ROOT, "projects/general"), "projects/general"),
    (os.path.join(DATA_ROOT, "projects/stand-kai/brain"), "stand-kai/brain"),
    (os.path.join(DATA_ROOT, "projects/gamma-telescope"), "gamma-telescope"),
    (os.path.join(DATA_ROOT, "projects/SlideBlocks Studio"), "SlideBlocks Studio"),
    (os.path.join(DATA_ROOT, "projects/视觉工作室"), "视觉工作室"),
    (os.path.join(DATA_ROOT, "projects/Slideblocks Skill Dev"), "Slideblocks Skill Dev"),
]


def dir_size(path):
    total = 0
    files = 0
    for root, _dirs, names in os.walk(path):
        for n in names:
            try:
                total += os.lstat(os.path.join(root, n)).st_size
                files += 1
            except OSError:
                pass
    return total, files


def load_json(path):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return None


def ledger_state(scope_dir):
    """checkpoint + journal 折叠（ChainLedgerStore.loadState 同判据：最后一条完整记录的 state）。"""
    cp = load_json(os.path.join(scope_dir, "chain-ledger.json")) or {}
    journal = os.path.join(scope_dir, "chain-ledger.json.journal")
    state = cp if isinstance(cp, dict) else {}
    journal_rounds = []
    if os.path.exists(journal):
        last = None
        try:
            with open(journal, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        rec = json.loads(line)
                        last = rec
                    except Exception:
                        break  # 残尾丢弃（同 store 判据：解析失败即停）
            if isinstance(last, dict) and isinstance(last.get("state"), dict):
                state = last["state"]
        except Exception:
            pass
    entries = state.get("entries", {}) if isinstance(state, dict) else {}
    by_status = {}
    for _cid, e in (entries or {}).items():
        by_status[e.get("status", "?")] = by_status.get(e.get("status", "?"), 0) + 1
    rounds = state.get("rounds", []) if isinstance(state, dict) else []
    return {
        "entriesTotal": len(entries or {}),
        "entriesByStatus": by_status,
        "cancelledChains": len((state or {}).get("cancelledChains", {}) or {}),
        "rounds": len(rounds) if isinstance(rounds, list) else 0,
    }


def flowmap_active(scope_dir):
    j = load_json(os.path.join(scope_dir, "flow-map.json"))
    nodes = (j or {}).get("nodes", {}) or {}
    by_status = {}
    for _nid, n in nodes.items():
        s = n.get("status", "?")
        by_status[s] = by_status.get(s, 0) + 1
    return {
        "nodeCount": len(nodes),
        "byStatus": by_status,
        "hasOpen": any(s in OPEN_STATUSES for s in by_status),
        "hasBlocked": BLOCKED in by_status,
    }


def archive_zone(scope_dir):
    """归档区：flow-map-archive.json（单文件存量）或 flow-map-archive/ 分文件目录。"""
    single = load_json(os.path.join(scope_dir, "flow-map-archive.json"))
    if isinstance(single, dict) and single.get("nodes"):
        return len(single["nodes"])
    arc_dir = os.path.join(scope_dir, "flow-map-archive")
    if os.path.isdir(arc_dir):
        count = 0
        for f in os.listdir(arc_dir):
            if f.endswith(".json"):
                j = load_json(os.path.join(arc_dir, f))
                if isinstance(j, dict):
                    count += len(j.get("nodeIds", []) or [])
        return count
    return 0


def classify(entry):
    reasons = []
    if entry["id"] == SELF_ID:
        reasons.append("self")
    rec = entry.get("record")
    if rec is None:
        reasons.append("no-record")
    else:
        if not rec.get("archivedAt"):
            reasons.append("not-archived")
    fm = entry.get("flowMap", {})
    if fm.get("hasOpen"):
        reasons.append("in-flight")
    if fm.get("hasBlocked"):
        reasons.append("blocked")
    entry["disposition"] = "KEEP" if reasons else "DELETE"
    entry["keepReasons"] = reasons
    return entry


def scan():
    rows = []
    for ws, ws_name in WORKSPACES:
        maps = os.path.join(ws, ".nebflow", "flow-maps")
        if not os.path.isdir(maps):
            continue
        for sid in sorted(os.listdir(maps)):
            scope = os.path.join(maps, sid)
            if not os.path.isdir(scope):
                continue
            size, files = dir_size(scope)
            rec = load_json(os.path.join(DELEGATES_DIR, f"{sid}.json"))
            rec_view = (
                {
                    "mode": rec.get("mode"),
                    "archivedAt": rec.get("archivedAt"),
                    "cancelRequestedAt": rec.get("cancelRequestedAt"),
                    "taskId": rec.get("taskId"),
                    "createdAt": rec.get("createdAt"),
                    "title": (rec.get("title") or "")[:80],
                }
                if isinstance(rec, dict)
                else None
            )
            fm = flowmap_active(scope)
            led = ledger_state(scope)
            rows.append(
                classify(
                    {
                        "id": sid,
                        "workspace": ws,
                        "workspaceName": ws_name,
                        "scopeDir": scope,
                        "bytes": size,
                        "files": files,
                        "record": rec_view,
                        "flowMap": fm,
                        "archiveNodes": archive_zone(scope),
                        "ledger": led,
                        "ageDays": round(
                            (time.time() * 1000 - (rec_view or {}).get("createdAt", 0)) / 86_400_000, 1
                        )
                        if rec_view and rec_view.get("createdAt")
                        else None,
                    }
                )
            )
    return rows


def totals(rows):
    t = {
        "dirs": len(rows),
        "bytes": sum(r["bytes"] for r in rows),
        "delete": sum(1 for r in rows if r["disposition"] == "DELETE"),
        "deleteBytes": sum(r["bytes"] for r in rows if r["disposition"] == "DELETE"),
        "keep": sum(1 for r in rows if r["disposition"] == "KEEP"),
        "keepBytes": sum(r["bytes"] for r in rows if r["disposition"] == "KEEP"),
    }
    return t


def write_reports(rows, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    ts = time.strftime("%Y%m%d_%H%M%S")
    manifest_path = os.path.join(out_dir, f"{ts}_flowmap-backlog-manifest.json")
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump({"generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S"), "totals": totals(rows), "rows": rows}, f, ensure_ascii=False, indent=1)
    md_path = os.path.join(out_dir, f"{ts}_flowmap-backlog-dry-run.md")
    t = totals(rows)
    with open(md_path, "w", encoding="utf-8") as f:
        f.write("# flow-map 作用域目录存量清算 · dry-run 清单\n\n")
        f.write(f"生成时刻：{time.strftime('%Y-%m-%d %H:%M:%S')}\n\n")
        f.write("| 工作区 | 目录数 | 字节 | DELETE | KEEP |\n|---|---|---|---|---|\n")
        per_ws = {}
        for r in rows:
            w = per_ws.setdefault(r["workspaceName"], [0, 0, 0, 0])
            w[0] += 1
            w[1] += r["bytes"]
            w[2] += 1 if r["disposition"] == "DELETE" else 0
            w[3] += 1 if r["disposition"] == "KEEP" else 0
        for ws_name, (n, b, d, k) in sorted(per_ws.items()):
            f.write(f"| {ws_name} | {n} | {b:,} | {d} | {k} |\n")
        f.write(f"| **合计** | **{t['dirs']}** | **{t['bytes']:,}** | **{t['delete']}** | **{t['keep']}** |\n\n")
        f.write("## KEEP 类（保留并列表报——人工裁定，不擅杀）\n\n")
        f.write("| id | 工作区 | 原因 | 节点(活动区) | bytes | ageDays |\n|---|---|---|---|---|---|\n")
        for r in rows:
            if r["disposition"] == "KEEP":
                st = ",".join(f"{k}:{v}" for k, v in sorted(r["flowMap"]["byStatus"].items())) or "-"
                f.write(
                    f"| {r['id']} | {r['workspaceName']} | {','.join(r['keepReasons'])} | {st} | {r['bytes']:,} | {r['ageDays']} |\n"
                )
        f.write("\n## DELETE 类（拟删清单）\n\n")
        f.write("| id | 工作区 | bytes | archivedAt | mode | ledger(active/rounds) |\n|---|---|---|---|---|---|\n")
        for r in rows:
            if r["disposition"] == "DELETE":
                f.write(
                    f"| {r['id']} | {r['workspaceName']} | {r['bytes']:,} | {r['record']['archivedAt']} "
                    f"| {r['record']['mode']} | {r['ledger']['entriesByStatus'].get('active', 0)}/{r['ledger']['rounds']} |\n"
                )
    print(md_path)
    print(manifest_path)
    return manifest_path, md_path


def register_retirement(row):
    """摘要先行降级登记（§5.6 第 2 步的运维镜像）：有活动区残留的目录，删除前向基工作区
    flow-map-events.jsonl 落一行 `scope-dir-retired`（与 FlowMapEventLog.append 逐字同
    行形）；残留节点清单随行，图谱可考古。零残留目录零登记（无事可记）。"""
    fm = row.get("flowMap") or {}
    if not fm.get("nodeCount"):
        return False
    scope = row["scopeDir"]
    fj = load_json(os.path.join(scope, "flow-map.json")) or {}
    nodes = fj.get("nodes", {}) or {}
    ordered = sorted(
        (n for n in nodes.values() if isinstance(n, dict)), key=lambda n: n.get("createdAt", 0)
    )
    first_id = (ordered[0].get("id") if ordered else "") or ""
    residue = ",".join(f"{n.get('id','?')}:{n.get('status','?')}" for n in ordered) or "-"
    led = row.get("ledger") or {}
    summary = (
        f"backlog cleanup (R3 batch): retired delegate scope dir id={row['id']} bytes={row['bytes']} "
        f"residueNodes={residue} ledgerActive={led.get('entriesByStatus', {}).get('active', 0)} "
        f"ledgerArchived={led.get('entriesByStatus', {}).get('archived', 0)} "
        f"recordArchivedAt={(row.get('record') or {}).get('archivedAt')} — archaeology: chain rows and batch "
        f"files retired with the dir; this event is the degraded registration"
    )
    import json as _json

    ws_nebflow = os.path.join(row["workspace"], ".nebflow")
    os.makedirs(ws_nebflow, exist_ok=True)
    line = _json.dumps(
        {
            "ts": int(time.time() * 1000),
            "type": "scope-dir-retired",
            "project": os.path.basename(row["workspace"].rstrip("/")),
            "nodeId": first_id,
            "summary": summary,
        },
        ensure_ascii=False,
    )
    with open(os.path.join(ws_nebflow, "flow-map-events.jsonl"), "a", encoding="utf-8") as f:
        f.write(line + "\n")
    return True


def apply_removal(manifest_path, out_dir):
    with open(manifest_path, "r", encoding="utf-8") as f:
        manifest = json.load(f)
    before = manifest["totals"]
    removed, failed, skipped, registered = [], [], [], []
    removed_bytes = 0
    for r in manifest["rows"]:
        if r["disposition"] != "DELETE":
            continue
        d = r["scopeDir"]
        # 执行前逐条复核（防清单生成后状态变化——终态证据与盘面静默双复核）
        rec = load_json(os.path.join(DELEGATES_DIR, f"{r['id']}.json"))
        fm = flowmap_active(d) if os.path.isdir(d) else None
        if not os.path.isdir(d):
            skipped.append((r["id"], "dir already gone"))
            continue
        if not (isinstance(rec, dict) and rec.get("archivedAt")):
            skipped.append((r["id"], "record no longer archived"))
            continue
        if fm and (fm["hasOpen"] or fm["hasBlocked"]):
            skipped.append((r["id"], "flow-map no longer quiet"))
            continue
        try:
            if register_retirement(r):
                registered.append(r["id"])
            import shutil

            shutil.rmtree(d)
            removed.append(r["id"])
            removed_bytes += r["bytes"]
        except Exception as e:
            failed.append((r["id"], str(e)))
    after = scan_totals_after()
    ts = time.strftime("%Y%m%d_%H%M%S")
    out = {
        "appliedAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "manifest": manifest_path,
        "before": before,
        "removedCount": len(removed),
        "removedBytes": removed_bytes,
        "registeredRetirementEvents": registered,
        "failed": failed,
        "skipped": skipped,
        "after": after,
    }
    path = os.path.join(out_dir, f"{ts}_flowmap-backlog-apply-result.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(path)
    print(f"removed={len(removed)} dirs / {removed_bytes:,} bytes; failed={len(failed)}; skipped={len(skipped)}")


def scan_totals_after():
    rows = scan()
    return totals(rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--manifest", help="dry-run manifest to apply")
    ap.add_argument("--out", default=os.path.dirname(os.path.abspath(__file__)))
    args = ap.parse_args()
    if args.apply:
        if not args.manifest:
            sys.exit("--apply requires --manifest <dry-run.json>")
        apply_removal(args.manifest, args.out)
    else:
        rows = scan()
        write_reports(rows, args.out)


if __name__ == "__main__":
    main()

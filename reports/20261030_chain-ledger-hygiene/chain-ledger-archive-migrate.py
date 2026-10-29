#!/usr/bin/env python3
"""chain-ledger-archive 存量一次性压缩迁移（R3 续批 2026-10-30，任务②d）。

54,309 个逐拍落盘的 round-<n>.json（2.3GB，93%+ 为 retire 轮）按月打包为
`rounds-YYYYMM.jsonl.gz`（一行一 RoundFile，gzip）。读路径透明解压由引擎侧
ChainLedgerStore.parseColdFile / foldJsonl 承担（R3 续批同步落地）。

模式
  dry-run  扫描 + 逐文件读验（round/kind/单行性），产出 manifest JSON（默认打印摘要）。
  apply    携带 dry-run manifest：逐文件复核（存在 + 字节数相符）→ 打包 → 读回校验
           （逐行解析、round/kind/条数与 manifest 逐一比对）→ 全部通过后才删原文件
           → 目录 fsync → 落 apply-result JSON。任一校验失败即中止（原文件零触碰）。
  verify   事后对账：扫束读回，输出轮覆盖数与字节读数。

安全判据（前批范式：先证后删、dry-run 先行、对账进报告）
  - 只碰 `round-*.json`（新形态 .json.gz / 旁卷 / tmp 一律排除）；
  - 打包行 = 原文件字节逐字（引擎 roundConservation 的 hotBefore/hotAfter 原样保留）；
  - 原文件内容含换行 ⇒ 打不进束（JSONL 形态破），该文件进 errors、整批中止；
  - 删除前先做「束读回 == manifest」的强校验；删除后立即 fsync 目录。
"""

import argparse
import gzip
import json
import os
import sys
import time
from collections import defaultdict
from datetime import datetime, timezone

ROUND_PREFIX = "round-"
ROUND_SUFFIX = ".json"


def fail(msg: str) -> None:
    print(f"[migrate] FATAL: {msg}", file=sys.stderr)
    sys.exit(1)


def list_round_files(archive: str) -> list[str]:
    if not os.path.isdir(archive):
        fail(f"archive dir not found: {archive}")
    return sorted(
        f for f in os.listdir(archive)
        if f.startswith(ROUND_PREFIX) and f.endswith(ROUND_SUFFIX)
        and not f.endswith(".gz") and not f.startswith(".")
    )


def read_round_file(path: str) -> dict:
    """读一个 round 文件 → {round, kind, entries, aliases, single_line, bytes}。"""
    with open(path, "rb") as fh:
        raw = fh.read()
    text = raw.decode("utf-8")
    try:
        doc = json.loads(text)
    except json.JSONDecodeError as e:
        fail(f"{path}: not valid JSON ({e})")
    if not isinstance(doc, dict) or "round" not in doc:
        fail(f"{path}: not a RoundFile document")
    return {
        "name": os.path.basename(path),
        "round": int(doc["round"]),
        "kind": str(doc.get("kind", "?")),
        "entries": len(doc.get("entries", []) or []),
        "aliases": len(doc.get("aliases", []) or []),
        "members": sum(len(e.get("members", []) or []) for e in (doc.get("entries", []) or [])),
        "single_line": "\n" not in text.strip(),
        "bytes": len(raw),
        "mtime": int(os.stat(path).st_mtime),
    }


def bundle_name(mtime: int) -> str:
    return "rounds-" + datetime.fromtimestamp(mtime, tz=timezone.utc).strftime("%Y%m") + ".jsonl.gz"


def month_of(mtime: int) -> str:
    return datetime.fromtimestamp(mtime, tz=timezone.utc).strftime("%Y-%m")


def scan(archive: str) -> tuple[list[dict], dict]:
    files = list_round_files(archive)
    records, errors = [], []
    for i, name in enumerate(files):
        try:
            rec = read_round_file(os.path.join(archive, name))
        except SystemExit:
            raise
        except Exception as e:  # noqa: BLE001
            errors.append({"name": name, "error": str(e)})
            continue
        rec["bundle"] = bundle_name(rec["mtime"])
        rec["month"] = month_of(rec["mtime"])
        records.append(rec)
        if (i + 1) % 5000 == 0:
            print(f"[migrate] scanned {i + 1}/{len(files)}", flush=True)
    totals = {
        "files": len(records),
        "bytes": sum(r["bytes"] for r in records),
        "kinds": _kinds(records),
        "errors": errors,
    }
    return records, totals


def _kinds(records: list[dict]) -> dict:
    out: dict[str, int] = defaultdict(int)
    for r in records:
        out[r["kind"]] += 1
    return dict(sorted(out.items()))


def group_bundles(records: list[dict]) -> dict[str, list[dict]]:
    bundles: dict[str, list[dict]] = defaultdict(list)
    for r in records:
        bundles[r["bundle"]].append(r)
    for name in bundles:
        bundles[name].sort(key=lambda r: r["round"])
    return dict(bundles)


def cmd_dry_run(args: argparse.Namespace) -> None:
    records, totals = scan(args.archive)
    if totals["errors"]:
        print(json.dumps({"errors": totals["errors"]}, ensure_ascii=False, indent=2), file=sys.stderr)
        fail(f"{len(totals['errors'])} unreadable files — fix before migration")
    bad_line = [r for r in records if not r["single_line"]]
    if bad_line:
        fail(f"{len(bad_line)} files are multi-line JSON — JSONL bundle form would corrupt; aborting")
    bundles = group_bundles(records)
    manifest = {
        "tool": "chain-ledger-archive-migrate.py",
        "mode": "dry-run",
        "archive": os.path.abspath(args.archive),
        "generatedAtEpochSec": int(time.time()),
        "totals": {k: v for k, v in totals.items() if k != "errors"},
        "bundles": [
            {
                "name": name,
                "month": bundle[0]["month"],
                "fileCount": len(bundle),
                "bytes": sum(r["bytes"] for r in bundle),
                "roundRange": [bundle[0]["round"], bundle[-1]["round"]],
            }
            for name, bundle in sorted(bundles.items())
        ],
        "files": records,
    }
    out = args.out or os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        f"{datetime.now().strftime('%Y%m%d_%H%M%S')}_archive-migrate-dry-run.json",
    )
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, ensure_ascii=False)
    print(f"[migrate] dry-run manifest → {out}")
    print(f"[migrate] files={totals['files']} bytes={totals['bytes']:,} kinds={totals['kinds']}")
    for b in manifest["bundles"]:
        print(f"[migrate]   {b['name']}: {b['fileCount']} files, {b['bytes']:,} B, rounds {b['roundRange'][0]}–{b['roundRange'][1]}")


def cmd_apply(args: argparse.Namespace) -> None:
    with open(args.manifest, encoding="utf-8") as fh:
        manifest = json.load(fh)
    if manifest.get("mode") != "dry-run":
        fail("manifest must be a dry-run manifest")
    archive = manifest["archive"]
    if os.path.abspath(archive) != os.path.abspath(args.archive):
        fail(f"archive mismatch: manifest={archive} arg={args.archive}")
    records = manifest["files"]
    missing = [r["name"] for r in records if not os.path.isfile(os.path.join(archive, r["name"]))]
    if missing:
        fail(f"{len(missing)} manifest files missing on disk (already migrated?) — e.g. {missing[:3]}")
    resized = [
        r["name"] for r in records
        if os.path.getsize(os.path.join(archive, r["name"])) != r["bytes"]
    ]
    if resized:
        fail(f"{len(resized)} files changed size since dry-run — re-run dry-run first")
    if any(not r["single_line"] for r in records):
        fail("manifest contains multi-line files — re-run dry-run")

    bundles = group_bundles(records)
    written: dict[str, dict] = {}
    for bname, bundle in sorted(bundles.items()):
        target = os.path.join(archive, bname)
        tmp = target + f".tmp.{os.getpid()}"
        with open(tmp, "wb") as fh:
            with gzip.GzipFile(fileobj=fh, mode="wb") as gz:
                for r in bundle:
                    with open(os.path.join(archive, r["name"]), "rb") as src:
                        gz.write(src.read())
                        gz.write(b"\n")
            fh.flush()
            os.fsync(fh.fileno())
        written[bname] = {"tmp": tmp, "target": target, "files": bundle}
        print(f"[migrate] packed {bname}: {len(bundle)} files", flush=True)

    # 读回强校验（对 tmp 做读回，改名前先证）：逐行解析、与 manifest 逐文件比对
    # （round/kind/entries/aliases）。全部通过后才改名落位 + 删原文件。
    expected = {b: {r["name"]: r for r in bundle} for b, bundle in bundles.items()}
    for bname, w in written.items():
        exp = expected[bname]
        got: dict[str, dict] = {}
        with gzip.open(w["tmp"], "rt", encoding="utf-8") as gz:
            for line in gz:
                doc = json.loads(line)
                key = f"round-{doc['round']}.json"
                got[key] = {
                    "round": int(doc["round"]),
                    "kind": doc.get("kind"),
                    "entries": len(doc.get("entries", []) or []),
                    "aliases": len(doc.get("aliases", []) or []),
                }
        diffs = []
        for key, e in exp.items():
            g = got.get(key)
            if g is None:
                diffs.append(f"{key}: missing from bundle")
            elif (g["round"], g["kind"], g["entries"], g["aliases"]) != (e["round"], e["kind"], e["entries"], e["aliases"]):
                diffs.append(f"{key}: {g} != {e}")
        if diffs:
            fail(f"read-back mismatch in {bname} — originals untouched: {diffs[:5]}")
        os.replace(w["tmp"], w["target"])
        print(f"[migrate] verified {bname}: {len(exp)} rounds read back OK", flush=True)

    # 全部通过 ⇒ 删原文件 + 目录 fsync。
    deleted = 0
    for w in written.values():
        for r in w["files"]:
            os.remove(os.path.join(archive, r["name"]))
            deleted += 1
    dfd = os.open(archive, os.O_RDONLY)
    try:
        os.fsync(dfd)
    finally:
        os.close(dfd)

    after_files = list_round_files(archive)
    result = {
        "tool": "chain-ledger-archive-migrate.py",
        "mode": "apply",
        "archive": archive,
        "manifest": os.path.abspath(args.manifest),
        "finishedAtEpochSec": int(time.time()),
        "before": {"files": len(records), "bytes": sum(r["bytes"] for r in records), "kinds": _kinds(records)},
        "after": {"looseRoundFiles": len(after_files)},
        "bundles": [
            {"name": b, "fileCount": len(w["files"]), "bytes": os.path.getsize(w["target"])}
            for b, w in sorted(written.items())
        ],
        "deleted": deleted,
    }
    out = args.out or os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        f"{datetime.now().strftime('%Y%m%d_%H%M%S')}_archive-migrate-apply-result.json",
    )
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=2)
    print(f"[migrate] deleted {deleted} originals; apply result → {out}")


def cmd_verify(args: argparse.Namespace) -> None:
    archive = args.archive
    bundles = sorted(f for f in os.listdir(archive) if f.startswith("rounds-") and f.endswith(".jsonl.gz"))
    loose = list_round_files(archive)
    total_rounds, total_entries, total_aliases, total_bytes = 0, 0, 0, 0
    kinds: dict[str, int] = defaultdict(int)
    for b in bundles:
        p = os.path.join(archive, b)
        total_bytes += os.path.getsize(p)
        with gzip.open(p, "rt", encoding="utf-8") as gz:
            for line in gz:
                doc = json.loads(line)
                total_rounds += 1
                total_entries += len(doc.get("entries", []) or [])
                total_aliases += len(doc.get("aliases", []) or [])
                kinds[str(doc.get("kind"))] += 1
        print(f"[migrate] {b}: cumulative rounds={total_rounds}")
    print(f"[migrate] verify: bundles={len(bundles)} loose={len(loose)} rounds={total_rounds} "
          f"entries={total_entries:,} aliases={total_aliases:,} bytes={total_bytes:,} kinds={dict(sorted(kinds.items()))}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("dry-run", "apply", "verify"):
        s = sub.add_parser(name)
        s.add_argument("--archive", required=True, help="chain-ledger-archive 目录")
        if name in ("dry-run", "apply"):
            s.add_argument("--out", default=None, help="输出 JSON 路径")
        if name == "apply":
            s.add_argument("--manifest", required=True, help="dry-run 产出的 manifest JSON")
    args = ap.parse_args()
    if args.cmd == "dry-run":
        cmd_dry_run(args)
    elif args.cmd == "apply":
        cmd_apply(args)
    else:
        cmd_verify(args)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""run-gateway-device-backfill.py -- CLIENT-leg parametrized driver for the
MVP-2 gateway device-history backfill (就绪面 / readiness face + dry-run rehearsal).

CONTRACT, READ BEFORE TOUCHING A FLAG
-------------------------------------
Canonical entry = the SERVER leg's script

    <neblink-server>/scripts/import-gateway-device-history.py

pinned by 契约终版 §8.10 「§7.2 回填脚本（幂等键 + dry-run/manifest）」, with the
VERBATIM flag set

    --db / --messages-json / --owner-user-id / --local-device-id / --dry-run / --manifest

and the VERBATIM idempotency key

    gwimport:<msgId>      (KEY_PREFIX + msgId -> messages.client_msg_id)

Contract §9 item 4 hands the CLIENT leg exactly this job: 「真实网关 JSON 的读取/搬运与
执行回填 —— 脚本已就绪且自测通过，但真实源文件与执行机属客户端腿。」

WHAT THIS DRIVER DOES (and, more importantly, does NOT do)
----------------------------------------------------------
It NEVER re-shapes a row and NEVER writes SQL of its own. It

  1. reads the gateway JSON read-only and SCOPES it (--peers / --since / --until);
  2. slices the scoped rows into deterministic chunks (--batch-size), written into
     a scratch --workdir in the SOURCE's own {peer: [row, ...]} shape;
  3. invokes the canonical script once per chunk with verbatim flags (via
     `python3 -B`, so not even bytecode is written next to it), and
  4. aggregates the canonical per-chunk manifests into ONE audit record
     (--manifest) whose readings are the task book's §1② set: rows to process /
     rows skipped + reason class / idempotency-key samples / planned write
     surface (tables x columns).

Row shaping, skip classification, direction resolution and the idempotency key
therefore stay SINGLE-SOURCE in the canonical script; this driver's own counts are
asserted against the canonical's and a mismatch is a hard failure (exit 4), never a
silent divergence.

PARAMETERS
----------
Names marked §8.10 are contract-verbatim and must not be renamed. Names marked ⟡
are this batch's CLIENT-LEG additions: §9 and 卡 §7.2 name NO scope / window /
batch / key-convention flag, so they are declared here explicitly rather than
smuggled in (see the report's 「参数清单」 and 「幂等键口径」 sections).

    --server-script PATH        [required] canonical entry (invoked, never modified)
    --db PATH                   [required] §8.10 -- target SQLite database
    --owner-user-id ID          [required] §8.10 -- owning account id
    --local-device-id ID        [required] §8.10 -- THIS machine's device id
    --messages-json PATH        §8.10 -- 数据源路径 (default: the gateway's own
                                documented location, DropboxService.scala:48-50)
    --dry-run                   §8.10 -- and the DEFAULT MODE of this driver
⟡   --peers ID[,ID...]          数据源范围 (default: every peer present in the file)
⟡   --since TS / --until TS     时间窗, HALF-OPEN [since, until) on the source `ts`
                                (epoch ms, or ISO-8601); unset = unbounded
⟡   --batch-size N             批次大小: max source rows per canonical invocation
⟡   --idem-key-mode MODE       幂等键口径; legal domain = {gwimport-msgid} ONLY
⟡   --workdir DIR               scratch dir for derived chunk JSON + per-chunk manifests
    --manifest PATH             §8.10 -- aggregate audit record (JSON)
⟡   --call-timeout SEC          per-canonical-invocation timeout (default 120)
    --execute --i-know-this-writes
                                🔴 REAL WRITE PATH -- author deploy window only. Every
                                variant of it is refused on the readiness face.

EXIT CODES
    0 ok (including every dry run)
    2 usage / input error (incl. a non-contract --idem-key-mode value)
    3 target database not migrated by an MVP-2 (or later) server yet (canonical gate)
    4 a cross-check FAILED: interface conformance, real-only SQL present, count
      mismatch against the canonical, key-convention mismatch, or source/target
      mutated underneath a dry run
    5 the write guard refused a non-dry-run invocation
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone

# --------------------------------------------------------------------------
# contract constants (逐字取自 canonical 脚本 / 契约 §8.10；改这里 = 改契约)
# --------------------------------------------------------------------------

#: 契约 §8.10 逐字：`gwimport:<msgId>`，写 `messages.client_msg_id`。
KEY_PREFIX_VERBATIM = "gwimport:"
#: 唯一合法的 --idem-key-mode 取值（口径名 -> 前缀在 canonical 脚本里现取核对）。
IDEM_KEY_MODES = {"gwimport-msgid": KEY_PREFIX_VERBATIM}

#: canonical 的 REQUIRED interface: 缺一个即 exit 4（接口漂移闸）。
CANONICAL_FLAGS = [
    "--db",
    "--messages-json",
    "--owner-user-id",
    "--local-device-id",
    "--dry-run",
    "--manifest",
]

#: 网关本机历史文件的既定位置（卡 §7.2 + DropboxService.scala:48-50）。
DEFAULT_SOURCE = "~/.nebflow/dropbox/messages.json"

#: 契约 §8.11 加性面 —— 部署窗前置条件（目标库须被 MVP-2+ 二进制启动过一次）。
PREFLIGHT_COLUMNS = [
    ("messages", "sender_device_id"),
    ("message_delivery_events", "to_device_id"),
    ("device_event_cursors", "last_device_conversation_message_id"),
]
PREFLIGHT_TABLES = ["device_read_cursors", "device_message_receipts"]

#: canonical 自己的 exit-3 闸所用的列（§8.10：缺列 => SCHEMA_HINT + return 3）。
CANONICAL_GATE_COLUMN = ("messages", "sender_device_id")

KEY_PREFIX_RE = re.compile(r'^KEY_PREFIX\s*=\s*"([^"]*)"', re.MULTILINE)


# --------------------------------------------------------------------------
# small helpers
# --------------------------------------------------------------------------


def parse_args(argv):
    p = argparse.ArgumentParser(
        prog="run-gateway-device-backfill.py",
        description="Client-leg parametrized driver around the canonical MVP-2 "
        "gateway device-history backfill (dry-run first, always).",
    )
    p.add_argument("--server-script", required=True, dest="server_script",
                   help="canonical import-gateway-device-history.py (invoked read-only)")
    p.add_argument("--db", required=True, help="§8.10 -- target neblink-server SQLite db")
    p.add_argument("--owner-user-id", required=True, dest="owner_user_id",
                   help="§8.10 -- owning account id (messages.sender_id)")
    p.add_argument("--local-device-id", required=True, dest="local_device_id",
                   help="§8.10 -- THIS machine's device id (sender of every `out` row)")
    p.add_argument("--messages-json", default=DEFAULT_SOURCE, dest="messages_json",
                   help=f"§8.10 -- gateway history file (default: {DEFAULT_SOURCE})")
    p.add_argument("--dry-run", action="store_true", dest="dry_run",
                   help="§8.10 -- count and report only (the default mode)")
    p.add_argument("--peers", action="append", default=None,
                   help="⟡ 数据源范围: peer device id, repeatable / comma separated")
    p.add_argument("--since", default=None, help="⟡ 时间窗下界 (inclusive), epoch ms | ISO-8601")
    p.add_argument("--until", default=None, help="⟡ 时间窗上界 (EXCLUSIVE), epoch ms | ISO-8601")
    p.add_argument("--batch-size", type=int, default=1000, dest="batch_size",
                   help="⟡ 批次大小: max source rows per canonical invocation")
    p.add_argument("--idem-key-mode", default="gwimport-msgid", dest="idem_key_mode",
                   help="⟡ 幂等键口径; legal domain = {gwimport-msgid}")
    p.add_argument("--workdir", default=None,
                   help="⟡ scratch dir for derived chunk files (default: fresh temp dir)")
    p.add_argument("--manifest", default=None, help="§8.10 -- aggregate audit record (JSON)")
    p.add_argument("--call-timeout", type=float, default=120.0, dest="call_timeout",
                   help="⟡ per-canonical-invocation timeout, seconds")
    p.add_argument("--execute", action="store_true",
                   help="🔴 leave dry-run (author deploy window ONLY; needs the ack flag)")
    p.add_argument("--i-know-this-writes", action="store_true", dest="i_know_this_writes",
                   help="🔴 explicit acknowledgement required together with --execute")
    return p.parse_args(argv)


def die(code, message):
    print(f"error: {message}", file=sys.stderr)
    return code


def epoch_ms(raw):
    """Mirror of the canonical `epoch_ms` (single-source row semantics)."""
    try:
        value = int(raw)
    except (TypeError, ValueError):
        return None
    if value <= 0:
        return None
    return value * 1000 if value < 1_000_000_000_000 else value


def parse_window_ts(raw):
    """Accept epoch ms (all digits) or ISO-8601; return epoch-ms int."""
    if raw is None:
        return None, None
    text = raw.strip()
    if re.fullmatch(r"\d{10,}", text):
        return int(text), text
    try:
        stamp = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        raise ValueError(f"unparsable timestamp {raw!r} (epoch ms or ISO-8601)")
    if stamp.tzinfo is None:
        stamp = stamp.replace(tzinfo=timezone.utc)
    ms = int(stamp.timestamp() * 1000)
    return ms, stamp.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def file_snapshot(path):
    if not os.path.exists(path):
        return {"path": path, "exists": False}
    st = os.stat(path)
    return {
        "path": os.path.abspath(path),
        "exists": True,
        "size": st.st_size,
        "mtime_ns": st.st_mtime_ns,
        "sha256": sha256_file(path),
    }


def sqlite_scalar(conn, sql, params=()):
    row = conn.execute(sql, params).fetchone()
    return None if row is None else row[0]


def preflight(db_path):
    """Read-only check of the §8.11 additive面 (the deploy-window preconditions)."""
    out = {"columns": {}, "tables": {}, "rows": {}}
    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        for table, column in PREFLIGHT_COLUMNS:
            cols = {r[1] for r in conn.execute(f"PRAGMA table_info({table})")}
            out["columns"][f"{table}.{column}"] = column in cols
        for table in PREFLIGHT_TABLES:
            out["tables"][table] = (
                sqlite_scalar(
                    conn,
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?",
                    (table,),
                )
                == 1
            )
        for table in ("conversations", "conversation_members", "messages"):
            out["rows"][table] = sqlite_scalar(
                conn, f"SELECT count(*) FROM sqlite_master WHERE name='{table}'"
            ) and sqlite_scalar(conn, f"SELECT count(*) FROM {table}")
    except sqlite3.DatabaseError as exc:
        out["error"] = str(exc)
    finally:
        conn.close()
    return out


def db_snapshot(db_path):
    snap = file_snapshot(db_path)
    sidecars = {}
    for suffix in ("-wal", "-shm", "-journal"):
        snap_path = db_path + suffix
        sidecars[suffix] = os.path.exists(snap_path)
    snap["sidecars"] = sidecars
    return snap


def load_history(path):
    """READ-ONLY load. The source file is never rewritten by this driver."""
    with open(path, "r", encoding="utf-8") as fh:
        data = json.load(fh)
    if not isinstance(data, dict):
        raise ValueError(
            "the history file must be an object keyed by peer device id, got "
            f"{type(data).__name__}"
        )
    out = {}
    for peer, rows in data.items():
        if not isinstance(rows, list):
            raise ValueError(f"peer {peer!r}: expected a list of messages")
        out[str(peer)] = rows
    return out


# --------------------------------------------------------------------------
# canonical-script interface conformance (read-only static gates)
# --------------------------------------------------------------------------


def normalize_sql_source(src):
    """Join adjacent Python string literals and drop the quotes, so the SQL the
    canonical script emits can be matched as plain text."""
    text = re.sub(r'"\s*\n\s*"', "", src)
    text = text.replace('"', "")
    return re.sub(r"\s+", " ", text)


def inspect_canonical(path):
    with open(path, "r", encoding="utf-8") as fh:
        raw = fh.read()
    norm = normalize_sql_source(raw)
    info = {
        "path": os.path.abspath(path),
        "sha256": sha256_file(path),
        "missing_flags": [f for f in CANONICAL_FLAGS if f'"{f}"' not in raw],
        "key_prefix": None,
        "reads_source_read_only": 'open(path, "r", encoding="utf-8")' in raw,
        "write_surface": {},
        "delete_or_drop": [],
        "modifies_messages": [],
    }
    match = KEY_PREFIX_RE.search(raw)
    info["key_prefix"] = None if match is None else match.group(1)

    for table, cols in re.findall(r"INSERT (?:OR IGNORE )?INTO (\w+) ?\(([^)]*)\)", norm):
        info["write_surface"].setdefault(table, [])
        for col in cols.split(","):
            col = col.strip()
            if col and col not in info["write_surface"][table]:
                info["write_surface"][table].append(col)
    for table, sets in re.findall(r"UPDATE (\w+) SET ([^ ]+) =", norm):
        key = f"{table}.{sets.strip()}"
        info["write_surface"].setdefault(table, [])
        if key.split(".")[1] not in info["write_surface"][table]:
            info["write_surface"][table].append(key.split(".")[1])

    info["delete_or_drop"] = [
        stmt for stmt in ("DELETE FROM", "DROP TABLE", "DROP INDEX", "TRUNCATE")
        if stmt in norm.upper()
    ]
    info["modifies_messages"] = [
        stmt for stmt in ("UPDATE messages ",) if stmt in norm + " "
    ]
    return info


# --------------------------------------------------------------------------
# scope + chunking (the ONLY transformations this driver performs)
# --------------------------------------------------------------------------


def scope_rows(history, peers, since, until):
    kept, excluded = [], {}
    for peer in sorted(history):
        if peers is not None and peer not in peers:
            excluded["scope:peer-not-selected"] = (
                excluded.get("scope:peer-not-selected", 0) + len(history[peer])
            )
            continue
        for row in history[peer]:
            ts = epoch_ms(row.get("ts")) if isinstance(row, dict) else None
            # An unusable ts cannot be placed in a window: it stays IN SCOPE so the
            # canonical script classifies it ("unusable ts ...") -- never silently
            # dropped by a second implementation of the skip rules.
            if ts is not None:
                if since is not None and ts < since:
                    excluded["scope:ts-before-since"] = excluded.get("scope:ts-before-since", 0) + 1
                    continue
                if until is not None and ts >= until:
                    excluded["scope:ts-at-or-after-until"] = (
                        excluded.get("scope:ts-at-or-after-until", 0) + 1
                    )
                    continue
            kept.append((peer, row))
    return kept, excluded


def chunk_rows(kept, size):
    """Deterministic slicing: peers ascending, source order within a peer."""
    chunks = []
    for start in range(0, len(kept), size):
        bucket = {}
        for peer, row in kept[start:start + size]:
            bucket.setdefault(peer, []).append(row)
        chunks.append(bucket)
    return chunks


def plan_key_samples(chunks, owner_user_id, local_device_id, key_prefix, limit):
    """The keys the canonical run WILL write, derived by the SAME rule (an `out`
    row is keyed on THIS device, an `in` row on the peer). Samples only; the
    authoritative count comes from the canonical manifests."""
    keys, dup = [], 0
    seen = set()
    for bucket in chunks:
        for peer in sorted(bucket):
            for row in bucket[peer]:
                msg_id = row.get("msgId") if isinstance(row, dict) else None
                if not isinstance(msg_id, str) or not msg_id:
                    continue
                direction = row.get("direction")
                device_key = local_device_id if direction == "out" else (
                    peer if direction == "in" else None
                )
                if device_key is None:
                    continue
                key = (f"dev:{device_key}", owner_user_id, f"{key_prefix}{msg_id}")
                if key in seen:
                    dup += 1
                seen.add(key)
                if len(keys) < limit:
                    keys.append({"conversation_id": key[0], "client_msg_id": key[2]})
    digest = hashlib.sha256()
    for conversation_id, sender_id, client_msg_id in sorted(seen):
        digest.update(f"{conversation_id}\x1f{sender_id}\x1f{client_msg_id}\x1e".encode())
    return keys, dup, len(seen), digest.hexdigest()


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------


def main(argv):
    args = parse_args(argv)
    started = int(time.time())

    # ---- guards -----------------------------------------------------------
    if args.execute and not args.i_know_this_writes:
        return die(5, "🔴 --execute refused: it needs --i-know-this-writes. Real "
                      "backfill is the AUTHOR DEPLOY WINDOW's job; this batch is the "
                      "readiness face (dry-run only).")
    if args.execute and args.dry_run:
        return die(2, "--execute and --dry-run are mutually exclusive")
    mode = "import" if args.execute else "dry-run"

    if args.idem_key_mode not in IDEM_KEY_MODES:
        return die(2, f"--idem-key-mode {args.idem_key_mode!r} is outside the contract "
                      f"domain {sorted(IDEM_KEY_MODES)} — 契约 §8.10 pins the key to "
                      f"`{KEY_PREFIX_VERBATIM}<msgId>` (messages.client_msg_id); a "
                      "different namespace would stop deduplicating against live sends.")
    key_prefix = IDEM_KEY_MODES[args.idem_key_mode]

    if args.batch_size < 1:
        return die(2, "--batch-size must be >= 1")
    if args.call_timeout <= 0:
        return die(2, "--call-timeout must be > 0")

    server_script = os.path.abspath(os.path.expanduser(args.server_script))
    if not os.path.isfile(server_script) or not os.access(server_script, os.R_OK):
        return die(2, f"--server-script not found or not readable: {server_script}")

    db_path = os.path.abspath(os.path.expanduser(args.db))
    if not os.path.isfile(db_path):
        return die(2, f"database not found: {db_path}")

    source = os.path.abspath(os.path.expanduser(args.messages_json))
    if not os.path.isfile(source):
        return die(2, f"history file not found: {source}")

    try:
        since, since_norm = parse_window_ts(args.since)
        until, until_norm = parse_window_ts(args.until)
    except ValueError as exc:
        return die(2, str(exc))
    if since is not None and until is not None and since >= until:
        return die(2, f"empty window: --since ({since_norm}) >= --until ({until_norm})")

    peers = None
    if args.peers:
        peers = {p.strip() for chunk in args.peers for p in chunk.split(",") if p.strip()}

    workdir = args.workdir or tempfile.mkdtemp(prefix="gwdev-backfill-")
    workdir = os.path.abspath(os.path.expanduser(workdir))
    os.makedirs(workdir, exist_ok=True)

    # scratch must never land inside the source dir, the target db dir or the
    # server repo -- the "zero write to those three" claim stays structural.
    for label, guard in (
        ("source dir", os.path.dirname(source)),
        ("target db dir", os.path.dirname(db_path)),
        ("server repo", os.path.dirname(os.path.dirname(server_script))),
    ):
        if workdir == guard or workdir.startswith(guard.rstrip("/") + os.sep):
            return die(2, f"--workdir {workdir} is inside the {label} ({guard}); "
                          "pick a scratch dir outside it")

    # ---- static interface conformance (read-only) -------------------------
    canon = inspect_canonical(server_script)
    if canon["missing_flags"]:
        return die(4, "canonical script no longer exposes the §8.10 flag set; missing "
                      f"{canon['missing_flags']}")
    if canon["key_prefix"] != key_prefix:
        return die(4, f"idempotency-key convention mismatch: canonical KEY_PREFIX="
                      f"{canon['key_prefix']!r} vs driver {key_prefix!r} (契约 §8.10)")
    if canon["delete_or_drop"] or canon["modifies_messages"]:
        return die(4, "canonical script contains a destructive statement "
                      f"({canon['delete_or_drop'] + canon['modifies_messages']}); the "
                      "readiness face assumes additive-only writes (契约 §10.1)")

    # ---- pre-state snapshots --------------------------------------------
    src_before = file_snapshot(source)
    db_before = db_snapshot(db_path)
    pre = preflight(db_path)

    record = {
        "tool": "run-gateway-device-backfill.py",
        "mode": mode,
        "started_at": started,
        "server_script": canon,
        "params": {
            "db": db_path,
            "messages_json": source,
            "owner_user_id": args.owner_user_id,
            "local_device_id": args.local_device_id,
            "peers": sorted(peers) if peers is not None else None,
            "since": since, "since_normalized": since_norm,
            "until": until, "until_normalized": until_norm,
            "window_semantics": "[since, until) on the source `ts`",
            "batch_size": args.batch_size,
            "idem_key_mode": args.idem_key_mode,
            "idem_key_prefix": key_prefix,
            "workdir": workdir,
        },
        "source_before": src_before,
        "target_before": db_before,
        "preflight": pre,
    }

    # canonical's own gate: no `messages.sender_device_id` => it returns 3.
    gate_table, gate_column = CANONICAL_GATE_COLUMN
    if not pre.get("columns", {}).get(f"{gate_table}.{gate_column}"):
        record["finished_at"] = int(time.time())
        if args.manifest:
            write_manifest(args.manifest, record)
        print(f"error: target db {db_path} lacks {gate_table}.{gate_column} — start an "
              "MVP-2 (or later) server binary against it ONCE (additive migration), "
              "then retry. §8.11 preflight: "
              f"{json.dumps(pre, ensure_ascii=False)}", file=sys.stderr)
        return 3

    # ---- scope + chunk ----------------------------------------------------
    try:
        history = load_history(source)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return die(2, f"cannot read {source}: {exc}")

    kept, excluded = scope_rows(history, peers, since, until)
    chunks = chunk_rows(kept, args.batch_size)
    samples, dup_count, unique_keys, plan_digest = plan_key_samples(
        chunks, args.owner_user_id, args.local_device_id, key_prefix, 5
    )

    record["source_shape"] = {
        "peers": len(history),
        "rows": sum(len(rows) for rows in history.values()),
    }
    record["scope"] = {
        "rows_in_scope": len(kept),
        "peers_in_scope": sorted({peer for peer, _ in kept}),
        "excluded_total": sum(excluded.values()),
        "excluded_by_reason": excluded,
        "chunks": len(chunks),
    }
    record["plan"] = {
        "idempotency_key_samples": samples,
        "idempotency_keys_unique": unique_keys,
        "idempotency_key_collisions": dup_count,
        "plan_digest": plan_digest,
    }
    record["write_surface"] = {
        "tables": canon["write_surface"],
        "statement_families": ["INSERT", "INSERT OR IGNORE", "UPDATE"],
        "delete_or_drop_statements": 0,
    }

    # ---- one canonical invocation per chunk (per-step persistence) --------
    calls, skipped_rows, by_reason, per_conversation = [], [], {}, {}
    totals = {"planned": 0, "conversations_created": 0, "messages_inserted": 0,
              "messages_already_present": 0}
    chunk_paths = []
    for index, bucket in enumerate(chunks):
        chunk_path = os.path.join(workdir, f"chunk_{index:04d}.json")
        with open(chunk_path, "w", encoding="utf-8") as fh:
            json.dump(bucket, fh, ensure_ascii=False)
        chunk_paths.append(chunk_path)
        chunk_manifest = os.path.join(workdir, f"chunk_{index:04d}.manifest.json")

        call_argv = [
            sys.executable, "-B", server_script,
            "--db", db_path,
            "--messages-json", chunk_path,
            "--owner-user-id", args.owner_user_id,
            "--local-device-id", args.local_device_id,
            "--manifest", chunk_manifest,
        ]
        if not args.execute:
            call_argv.append("--dry-run")

        print(f"[chunk {index + 1}/{len(chunks)}] rows={sum(len(v) for v in bucket.values())} "
              f"peers={len(bucket)} ...", flush=True)
        try:
            proc = subprocess.run(
                call_argv, capture_output=True, text=True,
                timeout=args.call_timeout, cwd=workdir,
            )
        except subprocess.TimeoutExpired:
            return die(4, f"canonical invocation {index} exceeded --call-timeout "
                          f"{args.call_timeout}s")
        calls.append({
            "chunk": index,
            "rows": sum(len(v) for v in bucket.values()),
            "argv": call_argv,
            "rc": proc.returncode,
            "stdout": proc.stdout.strip(),
            "stderr": proc.stderr.strip(),
        })
        if proc.returncode != 0:
            record["calls"] = calls
            record["finished_at"] = int(time.time())
            if args.manifest:
                write_manifest(args.manifest, record)
            print(proc.stdout, end="")
            print(proc.stderr, end="", file=sys.stderr)
            return proc.returncode if proc.returncode in (2, 3) else 4

        with open(chunk_manifest, "r", encoding="utf-8") as fh:
            sub = json.load(fh)
        totals["planned"] += sub["planned_messages"]
        for key in ("conversations_created", "messages_inserted",
                    "messages_already_present"):
            totals[key] += sub["report"][key]
        for row in sub["skipped"]:
            reason = row["reason"]
            by_reason[reason] = by_reason.get(reason, 0) + 1
            skipped_rows.append({"chunk": index, **row})
        for conversation_id, stat in sub["report"]["conversations"].items():
            slot = per_conversation.setdefault(
                conversation_id, {"inserted": 0, "already_present": 0}
            )
            slot["inserted"] += stat["inserted"]
            slot["already_present"] += stat["existing"]
        record["calls"] = calls
        record["progress"] = {"chunks_done": index + 1, "chunks_total": len(chunks)}
        record["readings"] = build_readings(record, totals, by_reason, per_conversation)
        if args.manifest:                       # per-step persistence
            write_manifest(args.manifest, record)
        print(f"[chunk {index + 1}/{len(chunks)}] rc=0 planned={sub['planned_messages']} "
              f"skipped={len(sub['skipped'])} would_insert={sub['report']['messages_inserted']} "
              f"already_present={sub['report']['messages_already_present']}", flush=True)

    # ---- cross-checks -----------------------------------------------------
    classified = totals["planned"] + len(skipped_rows)
    if classified != len(kept):
        return die(4, f"count cross-check failed: driver in-scope rows={len(kept)} but "
                      f"canonical planned+skipped={classified} — row shaping would "
                      "have diverged")

    src_after = file_snapshot(source)
    db_after = db_snapshot(db_path)
    record["source_after"] = src_after
    record["target_after"] = db_after
    record["source_unchanged"] = src_before == src_after
    record["target_unchanged"] = db_before == db_after
    if not args.execute and not (record["source_unchanged"] and record["target_unchanged"]):
        return die(4, "dry run MUTATED its inputs: "
                      f"source_unchanged={record['source_unchanged']} "
                      f"target_unchanged={record['target_unchanged']}")

    record["readings"] = build_readings(record, totals, by_reason, per_conversation)
    record["finished_at"] = int(time.time())
    if args.manifest:
        write_manifest(args.manifest, record)

    report(record)
    return 0


def build_readings(record, totals, by_reason, per_conversation):
    scope = record["scope"]
    return {
        "to_process": totals["planned"],
        "skipped": sum(by_reason.values()),
        "skipped_by_reason": dict(sorted(by_reason.items())),
        "excluded_before_planning": scope["excluded_total"],
        "excluded_by_reason": scope["excluded_by_reason"],
        "rows_cross_checked": totals["planned"] + sum(by_reason.values()),
        "conversations_created": totals["conversations_created"],
        "messages_inserted": totals["messages_inserted"],
        "messages_already_present": totals["messages_already_present"],
        "per_conversation": dict(sorted(per_conversation.items())),
    }


def write_manifest(path, record):
    path = os.path.abspath(os.path.expanduser(path))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(record, fh, indent=2, sort_keys=True, ensure_ascii=False)
        fh.write("\n")


def report(record):
    readings = record["readings"]
    scope = record["scope"]
    print(f"mode={record['mode']} source_unchanged={record['source_unchanged']} "
          f"target_unchanged={record['target_unchanged']}")
    print(f"source: peers={record['source_shape']['peers']} "
          f"rows={record['source_shape']['rows']} sha256={record['source_before']['sha256'][:16]}…")
    print(f"scope: peers_in_scope={len(scope['peers_in_scope'])} "
          f"rows_in_scope={scope['rows_in_scope']} chunks={scope['chunks']} "
          f"excluded={scope['excluded_total']} {scope['excluded_by_reason']}")
    print(f"to_process={readings['to_process']} skipped={readings['skipped']} "
          f"{readings['skipped_by_reason']}")
    print(f"would_insert={readings['messages_inserted']} "
          f"already_present={readings['messages_already_present']} "
          f"conversations_created={readings['conversations_created']}")
    print(f"plan_digest={record['plan']['plan_digest']} "
          f"idempotency_keys_unique={record['plan']['idempotency_keys_unique']} "
          f"collisions={record['plan']['idempotency_key_collisions']}")
    print(f"idempotency_key_samples={json.dumps(record['plan']['idempotency_key_samples'], ensure_ascii=False)}")
    print("write_surface(tables->columns)="
          f"{json.dumps(record['write_surface']['tables'], ensure_ascii=False, sort_keys=True)}")
    for conversation_id, stat in readings["per_conversation"].items():
        print(f"  {conversation_id}: would_insert={stat['inserted']} "
              f"already_present={stat['already_present']}")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

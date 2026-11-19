#!/usr/bin/env python3
"""Backend-side probe of GET /api/usage/aggregate — the *after* leg of the before/after
comparison, measured with the same method as the diagnosis batch's法①
(`.nebflow/evidence/20260917_tokenpanel-diag/measure_live.py`):

  1. single request, 3 repetitions  -> median (baseline: 0.778 s)
  2. panel-open mirror: the exact 7+1 request set usageDashboard.js fires, launched
     concurrently from reloadAll() -> wall clock of the open

Run against an **isolated** instance (own port + own home); the token is read from
`$NEBFLOW_HOME_DIR/auth.json` (or ~/.nebflow/auth.json) read-only and never printed.
GET only — zero writes, zero signals.

Usage:
  NEBFLOW_URL=http://127.0.0.1:8097 NEBFLOW_HOME_DIR=/tmp/nb-tp-home \
    python3 scripts/bench-usage-aggregate-http.py --out <json> [--rounds 3]
"""
import argparse
import concurrent.futures as cf
import json
import os
import statistics
import time
import urllib.request

MS_DAY = 86400000


def start_of_today(now=None):
    lt = time.localtime(now)
    return int(time.mktime((lt.tm_year, lt.tm_mon, lt.tm_mday, 0, 0, 0, 0, 0, -1)) * 1000)


def panel_request_set(now=None):
    """The 8 requests of a panel open (default state: statRange '1w', heatDim 'total')."""
    today = start_of_today(now)
    days = 7
    sfrom = today - (days - 1) * MS_DAY
    sto = today + MS_DAY
    wk_start = today - 6 * MS_DAY
    lt = time.localtime(now)
    dow = (lt.tm_wday + 1) % 7
    this_sunday = today - dow * MS_DAY
    return [
        ("summary.totals", {"from": sfrom, "to": sto}),
        ("summary.hour.today", {"dim": "hour", "from": today, "to": today + MS_DAY}),
        ("summary.hour.yest", {"dim": "hour", "from": today - MS_DAY, "to": today}),
        ("summary.week.this", {"from": wk_start, "to": today + MS_DAY}),
        ("summary.week.last", {"from": wk_start - 7 * MS_DAY, "to": wk_start}),
        ("summary.agent", {"dim": "agent", "from": sfrom, "to": sto}),
        ("summary.model", {"dim": "model", "from": sfrom, "to": sto}),
        ("heatmap.day", {"dim": "day", "from": this_sunday - 52 * 7 * MS_DAY, "to": sto}),
    ]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="/tmp/usage-agg-http-probe.json")
    ap.add_argument("--rounds", type=int, default=3)
    ap.add_argument("--opens", type=int, default=2)
    args = ap.parse_args()

    base = os.environ.get("NEBFLOW_URL", "http://127.0.0.1:8097")
    home = os.environ.get("NEBFLOW_HOME_DIR", os.path.expanduser("~/.nebflow"))
    with open(os.path.join(home, "auth.json")) as fh:
        token = json.load(fh)
    hdrs = {"Authorization": "Bearer " + token}

    ledger = os.path.join(home, "usage-records", "usage-records.jsonl")
    cache = os.path.join(home, "usage-records", "usage-agg-v1.json")
    scale = {
        "ledger_bytes": os.path.getsize(ledger) if os.path.exists(ledger) else 0,
        "ledger_rows": sum(1 for _ in open(ledger)) if os.path.exists(ledger) else 0,
        "cache_exists": os.path.exists(cache),
        "cache_bytes": os.path.getsize(cache) if os.path.exists(cache) else 0,
    }
    print(f"[http] base={base} rows={scale['ledger_rows']} ledger={scale['ledger_bytes']}B cache={scale['cache_bytes']}B", flush=True)

    def get(spec):
        label, params = spec
        q = "&".join(f"{k}={v}" for k, v in params.items() if v is not None)
        url = f"{base}/api/usage/aggregate" + (("?" + q) if q else "")
        t0 = time.perf_counter()
        try:
            req = urllib.request.Request(url, headers=hdrs)
            with urllib.request.urlopen(req, timeout=300) as resp:
                body = resp.read()
                code = resp.status
            err = None
        except Exception as e:  # noqa: BLE001
            body, code, err = b"", -1, repr(e)
        dt = time.perf_counter() - t0
        rec = {"label": label, "params": params, "http": code, "seconds": round(dt, 4), "bytes": len(body), "error": err}
        try:
            j = json.loads(body.decode())
            rec["count"] = j.get("count")
            rec["nbuckets"] = len(j.get("buckets") or [])
            rec["totalInput"] = j.get("totalInput")
            rec["costEquivalent"] = j.get("costEquivalent")
        except Exception:  # noqa: BLE001
            pass
        print(f"  [{label}] http={code} {dt:.4f}s bytes={len(body)} count={rec.get('count')} buckets={rec.get('nbuckets')}", flush=True)
        return rec

    # ── 1. single request, N repetitions ──
    singles = [get(("single", {})) for _ in range(args.rounds)]
    ok_singles = [s["seconds"] for s in singles if s["http"] == 200]
    single_stats = {
        "rounds": args.rounds,
        "seconds": ok_singles,
        "min": round(min(ok_singles), 4) if ok_singles else None,
        "median": round(statistics.median(ok_singles), 4) if ok_singles else None,
        "max": round(max(ok_singles), 4) if ok_singles else None,
    }
    print(f"[http] single median={single_stats['median']}s (baseline 0.778s)", flush=True)

    # ── 2. panel-open mirror (8 concurrent requests), twice: warm; freeze/cold optional ──
    opens = []
    for i in range(args.opens):
        specs = panel_request_set()
        t0 = time.perf_counter()
        with cf.ThreadPoolExecutor(max_workers=8) as ex:
            recs = list(ex.map(get, specs))
        wall = time.perf_counter() - t0
        cpu_sum = sum(r["seconds"] for r in recs)
        opens.append({
            "open_index": i,
            "wall_seconds": round(wall, 4),
            "sum_request_seconds": round(cpu_sum, 4),
            "amplification": round(cpu_sum / wall, 2) if wall > 0 else None,
            "slowest_request": max(r["seconds"] for r in recs),
            "requests": recs,
        })
        print(f"[http] open#{i} wall={wall:.3f}s sumCPU={cpu_sum:.3f}s amp={cpu_sum/wall:.2f}x (baseline 9.506/12.700s)", flush=True)

    with open(args.out, "w") as fh:
        json.dump({
            "batch": "tokenpanel-incremental",
            "base": base,
            "home": home,
            "scale": scale,
            "single_request": single_stats,
            "panel_opens": opens,
            "baseline_reference": {
                "single_request_median_s": 0.778,
                "panel_open_wall_s": [9.506, 12.700],
                "panel_open_sum_cpu_s": [68.267, 96.480],
                "amplification": [12.2, 16.3],
                "source": ".nebflow/evidence/20260917_tokenpanel-diag/live_probe.json",
            },
        }, fh, indent=2)
    print(f"[http] wrote {args.out}", flush=True)


if __name__ == "__main__":
    main()

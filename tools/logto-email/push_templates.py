#!/usr/bin/env python3
"""Push the four STAND email templates to Logto via Management API (M2M).

- Snapshot: GETs the full Email connector config first and saves it locally
  (0600) for rollback; prints sha256 fingerprints only — secrets never echoed.
- Drift: replaces only config.templates with the generated set. If the live
  templates already equal the generated set, no PATCH is issued (zero write).
- Apply: `python3 push_templates.py --apply --env-file <path>` performs the
  PATCH then re-GETs and verifies byte equality.
- Rollback: `python3 push_templates.py --apply --env-file <path> \
      --restore snapshots/email-connector-<ts>.json` PATCHes the snapshot back.

Default (no --apply) is read-only: prints would-change report and exits 0/1.
"""
import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
TPL_DIR = ROOT / "src/main/resources/web/brand/stand/email/templates"
USAGES = ["SignIn", "Register", "ForgotPassword", "Generic"]
SNAP_DIR = HERE / "snapshots"


def parse_env(path: Path) -> dict:
    env = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        v = v.strip()
        if len(v) >= 2 and v[0] == v[-1] and v[0] in ("'", '"'):
            v = v[1:-1]
        env[k.strip()] = v
    return env


def sha8(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()[:8]


class Logto:
    def __init__(self, endpoint: str, token: str):
        self.base = endpoint.rstrip("/")
        self.tok = token

    def req(self, route: str, body=None, method="GET"):
        headers = {"Authorization": "Bearer " + self.tok}
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode()
        r = urllib.request.Request(self.base + route, data=data, headers=headers, method=method)
        with urllib.request.urlopen(r, timeout=30) as resp:
            payload = resp.read()
            return json.loads(payload) if payload else None


def mint_token(env: dict) -> str:
    form = urllib.parse.urlencode({
        "grant_type": "client_credentials",
        "client_id": env["LOGTO_M2M_CLIENT_ID"],
        "client_secret": env["LOGTO_M2M_CLIENT_SECRET"],
        "resource": env["LOGTO_MANAGEMENT_RESOURCE"],
        "scope": "all",
    }).encode()
    r = urllib.request.Request(env["LOGTO_ENDPOINT"].rstrip("/") + "/oidc/token", data=form)
    with urllib.request.urlopen(r, timeout=20) as resp:
        return json.load(resp)["access_token"]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--env-file", type=Path, required=True)
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--restore", type=Path)
    a = ap.parse_args()

    env = parse_env(a.env_file)
    api = Logto(env["LOGTO_ENDPOINT"], mint_token(env))

    conn = None
    for c in api.req("/api/connectors"):
        if c.get("type") == "Email":
            conn = c
            break
    if conn is None:
        print("ERROR: no Email connector")
        return 2
    print(f"connector {conn['id']} (type=Email)")

    if a.restore:
        snap = json.loads(a.restore.read_text())
        api.req("/api/connectors/" + conn["id"], {"config": snap["config"]}, "PATCH")
        after = api.req("/api/connectors/" + conn["id"])
        ok = after["config"] == snap["config"]
        print(f"ROLLBACK from {a.restore.name}: verified={ok}")
        return 0 if ok else 3

    # snapshot for rollback (contains SMTP auth — local only, 0600, never printed)
    SNAP_DIR.mkdir(exist_ok=True)
    snap_path = SNAP_DIR / f"email-connector-{__import__('datetime').datetime.now().strftime('%Y%m%d-%H%M%S')}.json"
    snap_path.write_text(json.dumps({"connectorId": conn["id"], "config": conn["config"]},
                                    ensure_ascii=False, indent=1))
    os.chmod(snap_path, 0o600)
    print(f"snapshot: {snap_path.name} (0600, auth redacted from logs)")

    live = {t["usageType"]: t for t in conn["config"]["templates"]}
    for u in USAGES:
        if u not in live:
            print(f"ERROR: live template set missing {u}")
            return 2

    generated = {
        "SignIn": {"usageType": "SignIn", "subject": "STAND sign-in code: {{code}}",
                   "contentType": "text/html",
                   "content": (TPL_DIR / "SignIn.html").read_text()},
        "Register": {"usageType": "Register", "subject": "STAND sign-up code: {{code}}",
                     "contentType": "text/html",
                     "content": (TPL_DIR / "Register.html").read_text()},
        "ForgotPassword": {"usageType": "ForgotPassword", "subject": "STAND password reset code: {{code}}",
                           "contentType": "text/html",
                           "content": (TPL_DIR / "ForgotPassword.html").read_text()},
        "Generic": {"usageType": "Generic", "subject": "STAND sign-in code: {{code}}",
                    "contentType": "text/html",
                    "content": (TPL_DIR / "Generic.html").read_text()},
    }

    new_cfg = {k: v for k, v in conn["config"].items() if k != "templates"}
    new_templates = []
    drift = []
    for u in USAGES:
        gen = generated[u]
        lv = live.get(u, {})
        new_templates.append(gen)
        for field in ("content", "subject", "contentType"):
            if lv.get(field) != gen[field]:
                drift.append(f"{u}.{field} live_sha8={sha8(str(lv.get(field,'')).encode())} new_sha8={sha8(str(gen[field]).encode())}")
    new_cfg["templates"] = new_templates

    if drift:
        print("drift detected:")
        for d in drift:
            print("  " + d)
    else:
        print("drift: zero — live templates already equal generated set; no write needed")
        return 0

    if not a.apply:
        print("read-only check only; re-run with --apply to write")
        return 1

    api.req("/api/connectors/" + conn["id"], {"config": new_cfg}, "PATCH")
    after = api.req("/api/connectors/" + conn["id"])
    after_tpl = {t["usageType"]: t for t in after["config"]["templates"]}
    ok = all(after_tpl[u]["content"] == generated[u]["content"] and
             after_tpl[u]["subject"] == generated[u]["subject"] for u in USAGES)
    print(f"PATCH applied; re-GET verify byte-equality across 4 templates: {'PASS' if ok else 'FAIL'}")
    print(f"rollback: python3 push_templates.py --env-file <env> --restore {snap_path}")
    return 0 if ok else 3


if __name__ == "__main__":
    sys.exit(main())

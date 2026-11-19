#!/usr/bin/env python3
"""Trigger a REAL sign-in verification code email through the production Logto
sign-in UI (auth.mystand.space), driving the browser the same way a user does:
OIDC authorize -> sign-in page -> email step -> code step (sends the email).

No M2M needed; exercises the LIVE SignIn template end-to-end. Prints status
narration only; never prints the code. Evidence screenshots to --shots dir.
"""
import argparse
import base64
import hashlib
import os
import secrets
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import quote, urlencode

HERE = Path(__file__).resolve().parent
CLIENT_ID = "csxh16cas0x03bgk6w7ej"  # nebflow-desktop-pkce (Native, loopback redirect)
ENDPOINT = "https://auth.mystand.space"
REDIRECT = "http://127.0.0.1/auth/callback"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--to", required=True)
    ap.add_argument("--usage", default="SignIn", choices=["SignIn", "Register", "ForgotPassword"])
    ap.add_argument("--shots", type=Path, default=HERE / "shots")
    a = ap.parse_args()
    a.shots.mkdir(parents=True, exist_ok=True)

    verifier = base64.urlsafe_b64encode(secrets.token_bytes(48)).rstrip(b"=").decode()
    challenge = base64.urlsafe_b64encode(
        hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    q = urlencode({
        "client_id": CLIENT_ID,
        "redirect_uri": REDIRECT,
        "response_type": "code",
        "scope": "openid offline_access profile",
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        "prompt": "consent",
        "state": "mailfix-qa",
    })
    url = f"{ENDPOINT}/oidc/auth?{q}"
    print(f"authorize url built (state=mailfix-qa)")

    script = HERE / "_qa_flow.mjs"
    script.write_text(f"""
import {{ chromium }} from "playwright";
const url = {url!r};
const email = {a.to!r};
const usage = {a.usage!r};
const shots = {str(a.shots)!r};
const browser = await chromium.launch();
const page = await browser.newPage({{ viewport: {{ width: 480, height: 900 }} }});
try {{
  await page.goto(url, {{ waitUntil: "networkidle", timeout: 45000 }});
  await page.screenshot({{ path: shots + "/qa-1-signin.png", fullPage: true }});
  console.log("step1: sign-in page loaded:", page.url());
  if (usage === "Register") {{
    const create = page.locator("a:has-text('Create'), button:has-text('Create'), a:has-text('注册'), a:has-text('创建')");
    if (await create.count()) {{ await create.first().click(); await page.waitForTimeout(800); }}
  }} else if (usage === "ForgotPassword") {{
    const fp = page.locator("a:has-text('Forgot'), button:has-text('Forgot'), a:has-text('忘记')");
    if (await fp.count()) {{ await fp.first().click(); await page.waitForTimeout(800); }}
  }}
  const input = page.locator("input[type='email'], input[name='email'], input[placeholder*='mail' i]").first();
  await input.waitFor({{ timeout: 15000 }});
  await input.fill(email);
  await page.screenshot({{ path: shots + "/qa-2-filled.png", fullPage: true }});
  console.log("step2: email filled");
  const btn = page.locator("button[type='submit'], button:has-text('Continue'), button:has-text('Send'), button:has-text('继续')").first();
  await btn.click();
  await page.waitForTimeout(3500);
  await page.screenshot({{ path: shots + "/qa-3-code-step.png", fullPage: true }});
  const bodyText = await page.evaluate(() => document.body.innerText.slice(0, 400));
  console.log("step3: url now:", page.url());
  console.log("step3: page head:", JSON.stringify(bodyText.slice(0, 160)));
  await browser.close();
  console.log("FLOW COMPLETE — code email requested for the QA inbox");
}} catch (e) {{
  await page.screenshot({{ path: shots + "/qa-9-error.png", fullPage: true }}).catch(() => {{}});
  console.error("FLOW ERROR:", e.message.slice(0, 300), "at", page.url());
  await browser.close();
  process.exit(1);
}}
""")
    env = dict(os.environ, NODE_PATH="/Users/kaiyu/Claude code/Stand/node_modules")
    r = subprocess.run(["node", str(script)], env=env, capture_output=True, text=True, timeout=120)
    sys.stdout.write(r.stdout)
    sys.stderr.write(r.stderr[-600:])
    return r.returncode


if __name__ == "__main__":
    sys.exit(main())

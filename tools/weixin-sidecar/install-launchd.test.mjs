#!/usr/bin/env node
/**
 * install-launchd.sh config-face v2 (weixin plist auto-gen batch r2, 2026-10-08).
 *
 * Pins the v2 provisioner contract (design r2 §1.2 C1.1-C1.8; wx4 review):
 *   - the plist carries the msgloop-leg token key WEIXIN_MSGLOOP_TOKEN_FILE
 *     (never the send-leg family name),
 *   - NEBFLOW_HOME flows input → plist → --print-config "nebflowHome",
 *   - WEIXIN_SEND_TOKEN[_FILE] are a forbidden zone: any set-and-non-empty
 *     presence fail-closed rejects with a non-zero exit and an explicit
 *     error, and nothing leaks into --print-config output (hostile-probe
 *     pair PX1-PX4; a set-but-blank value counts as not-provided and a
 *     fully-unset one must not trip `set -u` — both pinned by PX4),
 *   - the straight-path run lands WEIXIN_MSGLOOP_TOKEN_FILE and NEBFLOW_HOME
 *     with zero forbidden keys (probe B / C1.8 hand-plist semantic shape),
 *   - the install face takes the no-change fast path when the existing
 *     plist's ProgramArguments + EnvironmentVariables match key-for-key,
 *   - every v1 behavior stays: bare-run byte-for-byte plist (C1.3),
 *     --print-config determinism (C1.2), rehearsal-dir override (C1.5),
 *     backup discipline (C1.6), narrowed port occupancy (C1.4), input
 *     validation.
 * All spawns are hermetic: scratch HOME, scratch rehearsal PLIST_DIR, and
 * stubbed launchctl/lsof first on PATH — zero real launchd touch, zero
 * contact with any live instance; the only processes ever signalled are
 * helpers this file spawned itself.
 *
 * Run: node --test tools/weixin-sidecar/install-launchd.test.mjs
 */
import test from "node:test";
import assert from "node:assert/strict";
import { spawnSync, spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SCRIPT = path.join(HERE, "install-launchd.sh");
const LABEL = "com.nebflow.weixin-sidecar";
const NODE_DIR = path.dirname(process.execPath);
const UID = process.getuid();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// the C1.1 v2 env matrix; sample values = the hand-fixed live plist shape (C1.8)
const V2 = {
  WEIXIN_SIDECAR_PORT: "8787",
  WEIXIN_MSGLOOP_INGRESS_URL: "http://127.0.0.1:8097/api/social/channels/weixin-ilink/ingress",
  WEIXIN_MSGLOOP_TOKEN_FILE: "/tmp/nebflow-coldstart/secrets/weixin-ilink-ingress-token",
  NEBFLOW_HOME: "/tmp/nebflow-coldstart",
};
const FORBIDDEN = "WEIXIN_SEND";

function sandbox(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "wx1v2-iltest-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true, maxRetries: 3 }));
  const binDir = path.join(root, "bin");
  fs.mkdirSync(binDir);
  // launchctl stub: records every call, never touches the real daemon
  fs.writeFileSync(
    path.join(binDir, "launchctl"),
    "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"${INSTALL_TEST_LAUNCHCTL_LOG:?}\"\nexit 0\n",
    { mode: 0o755 },
  );
  // lsof stub: reports exactly the pids named in INSTALL_TEST_LSOF_PIDS
  fs.writeFileSync(
    path.join(binDir, "lsof"),
    "#!/bin/sh\n[ -n \"${INSTALL_TEST_LSOF_PIDS:-}\" ] || exit 0\nfor p in $INSTALL_TEST_LSOF_PIDS; do printf '%s\\n' \"$p\"; done\nexit 0\n",
    { mode: 0o755 },
  );
  const home = path.join(root, "home");
  fs.mkdirSync(home);
  const plistDir = path.join(root, "rehearsal-LaunchAgents");
  const lcLog = path.join(root, "launchctl-calls.log");
  fs.writeFileSync(lcLog, "");
  return { root, binDir, home, plistDir, lcLog };
}

function envFor(s, extra = {}) {
  return {
    HOME: s.home,
    WEIXIN_SIDECAR_PLIST_DIR: s.plistDir,
    INSTALL_TEST_LAUNCHCTL_LOG: s.lcLog,
    INSTALL_TEST_LSOF_PIDS: "",
    PATH: `${s.binDir}:${NODE_DIR}:/usr/bin:/bin:/usr/sbin`,
    ...extra,
  };
}

const runPrint = (s, extra = {}) =>
  spawnSync(SCRIPT, ["--print-config"], { env: envFor(s, extra), encoding: "utf8" });
const runInstall = (s, extra = {}) =>
  spawnSync(SCRIPT, [], { env: envFor(s, extra), encoding: "utf8" });

const plistPathOf = (s) => path.join(s.plistDir, `${LABEL}.plist`);
const backupCount = (s) =>
  fs.existsSync(s.plistDir)
    ? fs.readdirSync(s.plistDir).filter((f) => f.startsWith(`${LABEL}.plist.bak-`)).length
    : 0;
const lcCalls = (s) => fs.readFileSync(s.lcLog, "utf8").split("\n").filter(Boolean);

function envKeysInOrder(plistText) {
  const tail = plistText.split("<key>EnvironmentVariables</key>")[1] ?? "";
  return [...tail.matchAll(/<key>([^<]+)<\/key><string>/g)].map((m) => m[1]);
}

// the historical (pre-refactor) template; bare-run output must equal it byte-for-byte
function expectedPlistText(s, envVars, nodePath = process.execPath) {
  const logDir = path.join(s.home, "Library/Logs/nebflow-weixin-sidecar");
  const envLines = [`    <key>WEIXIN_SIDECAR_PORT</key><string>${envVars.WEIXIN_SIDECAR_PORT ?? "8787"}</string>`];
  for (const k of ["WEIXIN_MSGLOOP_INGRESS_URL", "WEIXIN_MSGLOOP_TOKEN_FILE", "NEBFLOW_HOME"]) {
    if (envVars[k]) envLines.push(`    <key>${k}</key><string>${envVars[k]}</string>`);
  }
  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>${LABEL}</string>
  <key>ProgramArguments</key>
  <array>
    <string>${nodePath}</string>
    <string>${path.join(HERE, "shim.mjs")}</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>10</integer>
  <key>StandardOutPath</key><string>${logDir}/out.log</string>
  <key>StandardErrorPath</key><string>${logDir}/err.log</string>
  <key>EnvironmentVariables</key>
  <dict>
${envLines.join("\n")}
  </dict>
</dict>
</plist>
`;
}

// reorder the EnvironmentVariables entries inside a plist text (stays valid;
// used to pin the key-by-key, order-insensitive fast-path comparison)
function permuteEnvLines(plistText) {
  const marker = "<key>EnvironmentVariables</key>\n  <dict>\n";
  const head = plistText.slice(0, plistText.indexOf(marker) + marker.length);
  const rest = plistText.slice(head.length);
  const end = rest.indexOf("  </dict>");
  const lines = rest.slice(0, end).split("\n").filter(Boolean);
  return head + [...lines].reverse().join("\n") + "\n" + rest.slice(end);
}

// long-lived helper whose command line carries `marker`; exercises the port
// occupancy classification (ps command-line grep). Only ever signalled by us.
function markerProcess(t, marker) {
  const child = spawn(process.execPath, ["-e", "setInterval(() => {}, 1000)", "ignored", marker], {
    stdio: "ignore",
  });
  t.after(() => {
    try {
      child.kill("SIGKILL");
    } catch {}
  });
  return child;
}
const alive = (pid) => {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
};
async function expectDeath(pid) {
  for (let i = 0; i < 100; i++) {
    if (!alive(pid)) return;
    await sleep(50);
  }
  throw new Error(`helper pid ${pid} still alive`);
}

// ── hostile probe A: the forbidden zone must fail closed ──

test("PX1 probe A: WEIXIN_SEND_TOKEN_FILE in the env ⇒ fail-closed reject, --print-config output is empty", (t) => {
  const s = sandbox(t);
  const r = runPrint(s, { ...V2, WEIXIN_SEND_TOKEN_FILE: "/EVIL/evil-token" });
  assert.equal(r.status, 2, `expected exit 2, got ${r.status}; stderr=${r.stderr}`);
  assert.equal(r.stdout, "", "no byte of config JSON may be emitted on rejection");
  assert.equal(r.stdout.includes(FORBIDDEN), false);
  assert.equal(r.stdout.includes("/EVIL"), false);
  assert.match(r.stderr, /WEIXIN_SEND_TOKEN_FILE/);
  assert.match(r.stderr, /forbidden/);
});

test("PX2 probe A: the install face refuses too — no plist, no backup, zero launchd calls", (t) => {
  const s = sandbox(t);
  const r = runInstall(s, { ...V2, WEIXIN_SEND_TOKEN_FILE: "/EVIL/evil-token" });
  assert.equal(r.status, 2, `stderr=${r.stderr}`);
  assert.equal(fs.existsSync(plistPathOf(s)), false, "no plist may be written");
  assert.equal(fs.existsSync(s.plistDir), false, "not even the rehearsal dir may be created");
  assert.equal(backupCount(s), 0);
  assert.deepEqual(lcCalls(s), [], "no bootout/bootstrap may run");
  assert.match(r.stderr, /forbidden/);
});

test("PX3 probe A: the value form WEIXIN_SEND_TOKEN rejects the same way", (t) => {
  const s = sandbox(t);
  const r = runPrint(s, { ...V2, WEIXIN_SEND_TOKEN: "/EVIL/evil-token-value" });
  assert.equal(r.status, 2, `stderr=${r.stderr}`);
  assert.equal(r.stdout, "");
  assert.equal(r.stdout.includes(FORBIDDEN), false);
  assert.equal(r.stdout.includes("/EVIL"), false);
  assert.match(r.stderr, /WEIXIN_SEND_TOKEN\b/);
});

test("PX4 probe A: non-empty forbidden vars reject; set-but-blank counts as not-provided; unset stays set -u-safe", (t) => {
  const s = sandbox(t);
  // value semantics: a non-empty value — even whitespace-only — still rejects
  for (const [k, v] of [
    ["WEIXIN_SEND_TOKEN_FILE", "/EVIL/evil-token"],
    ["WEIXIN_SEND_TOKEN_FILE", "   "],
    ["WEIXIN_SEND_TOKEN", "x"],
  ]) {
    const r = runPrint(s, { ...V2, [k]: v });
    assert.equal(r.status, 2, `${k}=${JSON.stringify(v)} must reject; stderr=${r.stderr}`);
    assert.equal(r.stdout, "");
  }
  // set-but-blank: treated as not-provided (same trim semantics as the real
  // inputs) — falls through to a clean config face, zero forbidden-name leak
  for (const [k, v] of [
    ["WEIXIN_SEND_TOKEN_FILE", ""],
    ["WEIXIN_SEND_TOKEN", ""],
  ]) {
    const r = runPrint(s, { ...V2, [k]: v });
    assert.equal(r.status, 0, `${k}=${JSON.stringify(v)} must not reject; stderr=${r.stderr}`);
    const cfg = JSON.parse(r.stdout);
    assert.equal(cfg.plistText.includes(k), false);
  }
  // fully-unset target: ${!VAR-} must expand empty under `set -u` — bash 3.2
  // (macOS /bin/bash) aborts on a bare ${!VAR} with "unbound variable";
  // PX5-PX13 re-run this face many more times, this pins the reason.
  const unset = runPrint(s, V2);
  assert.equal(unset.status, 0, unset.stderr);
  assert.equal(unset.stderr.includes("unbound variable"), false);
});

// ── probe B: the straight path ──

test("PX5 probe B / C1.8: four v2 keys ⇒ plist carries MSGLOOP token key + NEBFLOW_HOME, zero forbidden keys", (t) => {
  const s = sandbox(t);
  const r = runPrint(s, V2);
  assert.equal(r.status, 0, r.stderr);
  assert.equal(r.stdout.includes(FORBIDDEN), false, "no forbidden substring anywhere in the output");
  const cfg = JSON.parse(r.stdout);
  assert.equal(cfg.port, 8787);
  assert.equal(cfg.ingressUrl, V2.WEIXIN_MSGLOOP_INGRESS_URL);
  assert.equal(cfg.tokenFile, V2.WEIXIN_MSGLOOP_TOKEN_FILE);
  assert.equal(cfg.nebflowHome, V2.NEBFLOW_HOME);
  assert.equal(cfg.plistPath, plistPathOf(s));
  // exact shape: the four v2 keys in the hand-fixed live-plist order, exact values
  assert.equal(cfg.plistText, expectedPlistText(s, V2));
  assert.deepEqual(envKeysInOrder(cfg.plistText), [
    "WEIXIN_SIDECAR_PORT",
    "WEIXIN_MSGLOOP_INGRESS_URL",
    "WEIXIN_MSGLOOP_TOKEN_FILE",
    "NEBFLOW_HOME",
  ]);
});

// ── v1 regressions ──

test("PX6 C1.3: bare run (no inputs) is byte-for-byte the historical template, null optional fields", (t) => {
  const s = sandbox(t);
  const r = runPrint(s);
  assert.equal(r.status, 0, r.stderr);
  const cfg = JSON.parse(r.stdout);
  assert.equal(cfg.port, 8787);
  assert.equal(cfg.ingressUrl, null);
  assert.equal(cfg.tokenFile, null);
  assert.equal(cfg.nebflowHome, null);
  assert.equal(cfg.plistText, expectedPlistText(s, {}));
  assert.deepEqual(envKeysInOrder(cfg.plistText), ["WEIXIN_SIDECAR_PORT"]);
});

test("PX7 C1.2: --print-config is deterministic (bare and full env, two runs each, byte-identical)", (t) => {
  const s = sandbox(t);
  const bare1 = runPrint(s);
  const bare2 = runPrint(s);
  const full1 = runPrint(s, V2);
  const full2 = runPrint(s, V2);
  for (const r of [bare1, bare2, full1, full2]) assert.equal(r.status, 0, r.stderr);
  assert.equal(bare1.stdout, bare2.stdout);
  assert.equal(full1.stdout, full2.stdout);
  assert.notEqual(bare1.stdout, full1.stdout);
});

test("PX8 C1.5: rehearsal-dir install writes only there; real LaunchAgents untouched; stubbed launchd leg runs", (t) => {
  const s = sandbox(t);
  const r = runInstall(s, V2);
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.stdout, /installed:/);
  const written = fs.readFileSync(plistPathOf(s), "utf8");
  assert.equal(written, expectedPlistText(s, V2), "install write must be byte-identical to the config face");
  assert.equal(
    fs.existsSync(path.join(s.home, "Library", "LaunchAgents", `${LABEL}.plist`)),
    false,
    "the real LaunchAgents dir must stay untouched",
  );
  fs.existsSync(path.join(s.home, "Library", "Logs", "nebflow-weixin-sidecar")); // log dir face lives under scratch HOME
  assert.deepEqual(lcCalls(s), [
    `bootout gui/${UID}/${LABEL}`,
    `bootstrap gui/${UID} ${plistPathOf(s)}`,
    `print gui/${UID}/${LABEL}`, // the state: report line (v1 behavior)
  ]);
  assert.equal(backupCount(s), 0, "first install has no prior plist to back up");
});

test("PX9 fast path: identical rerun prints unchanged and skips backup/write/bootout/bootstrap; key-order-only diff is still unchanged", (t) => {
  const s = sandbox(t);
  assert.equal(runInstall(s, V2).status, 0);
  const callsAfterFirst = lcCalls(s).length;
  const before = fs.readFileSync(plistPathOf(s), "utf8");

  const again = runInstall(s, V2);
  assert.equal(again.status, 0, again.stderr);
  assert.equal(again.stdout, "unchanged\n");
  assert.equal(lcCalls(s).length, callsAfterFirst, "no new launchd calls on the fast path");
  assert.equal(backupCount(s), 0, "no backup on the fast path");
  assert.equal(fs.readFileSync(plistPathOf(s), "utf8"), before, "no rewrite on the fast path");

  // same keys, same values, different dict order ⇒ still key-by-key equal
  fs.writeFileSync(plistPathOf(s), permuteEnvLines(before));
  const perm = runInstall(s, V2);
  assert.equal(perm.status, 0, perm.stderr);
  assert.equal(perm.stdout, "unchanged\n");
  assert.equal(lcCalls(s).length, callsAfterFirst);
  assert.equal(fs.readFileSync(plistPathOf(s), "utf8"), permuteEnvLines(before), "still no rewrite");
});

test("PX10 fast path: a config change (port) falls back to the full path — backup + write + restart", (t) => {
  const s = sandbox(t);
  assert.equal(runInstall(s, V2).status, 0);
  const callsAfterFirst = lcCalls(s).length;
  const before = fs.readFileSync(plistPathOf(s), "utf8");

  const changed = runInstall(s, { ...V2, WEIXIN_SIDECAR_PORT: "8788" });
  assert.equal(changed.status, 0, changed.stderr);
  assert.match(changed.stdout, /installed:/);
  assert.equal(backupCount(s), 1);
  const bak = fs.readdirSync(s.plistDir).find((f) => f.startsWith(`${LABEL}.plist.bak-`));
  assert.equal(fs.readFileSync(path.join(s.plistDir, bak), "utf8"), before, "backup preserves the prior bytes");
  assert.equal(
    fs.readFileSync(plistPathOf(s), "utf8"),
    expectedPlistText(s, { ...V2, WEIXIN_SIDECAR_PORT: "8788" }),
  );
  const newCalls = lcCalls(s).slice(callsAfterFirst);
  assert.deepEqual(newCalls, [
    `bootout gui/${UID}/${LABEL}`,
    `bootstrap gui/${UID} ${plistPathOf(s)}`,
    `print gui/${UID}/${LABEL}`,
  ]);
});

test("PX11 C1.4: port occupancy stays narrowed — foreign listener refused (and survives), shim listener stopped", async (t) => {
  // 11a: a listener whose command line lacks shim.mjs is a hard refusal
  const sA = sandbox(t);
  const foreign = markerProcess(t, "unrelated-server.js");
  await sleep(150); // let ps see the helper
  const rA2 = spawnSync(SCRIPT, [], {
    env: envFor(sA, { ...V2, INSTALL_TEST_LSOF_PIDS: String(foreign.pid) }),
    encoding: "utf8",
  });
  assert.equal(rA2.status, 1, `stderr=${rA2.stderr}`);
  assert.match(rA2.stderr, /refusing to kill unrelated/);
  assert.equal(alive(foreign.pid), true, "the foreign listener must survive the refusal");
  assert.deepEqual(lcCalls(sA), [], "refusal must happen before any bootout/bootstrap");

  // 11b: a shim.mjs listener is stopped, then the install completes
  const sB = sandbox(t);
  const shim = markerProcess(t, "shim.mjs");
  await sleep(150);
  const rB = spawnSync(SCRIPT, [], {
    env: envFor(sB, { ...V2, INSTALL_TEST_LSOF_PIDS: String(shim.pid) }),
    encoding: "utf8",
  });
  assert.equal(rB.status, 0, rB.stderr);
  await expectDeath(shim.pid);
  assert.deepEqual(lcCalls(sB), [
    `bootout gui/${UID}/${LABEL}`,
    `bootstrap gui/${UID} ${plistPathOf(sB)}`,
    `print gui/${UID}/${LABEL}`,
  ]);
});

test("PX12 input validation regressions: bad port (non-numeric / out of range) and unknown arg all exit 2", (t) => {
  const s = sandbox(t);
  for (const [args, extra] of [
    [["--print-config"], { WEIXIN_SIDECAR_PORT: "not-a-port" }],
    [["--print-config"], { WEIXIN_SIDECAR_PORT: "70000" }],
    [["--print-config"], { WEIXIN_SIDECAR_PORT: "0" }],
    [["--bogus"], {}],
  ]) {
    const r = spawnSync(SCRIPT, args, { env: envFor(s, extra), encoding: "utf8" });
    assert.equal(r.status, 2, `args=${JSON.stringify(args)} extra=${JSON.stringify(extra)} stderr=${r.stderr}`);
  }
});

test("PX13 C1.6 backup discipline: a malformed pre-existing plist still falls through to a full install and is preserved in the backup", (t) => {
  const s = sandbox(t);
  fs.mkdirSync(s.plistDir, { recursive: true });
  fs.writeFileSync(plistPathOf(s), "PRE-EXISTING-BYTES (not a plist)");
  const r = runInstall(s, V2);
  assert.equal(r.status, 0, r.stderr, "a malformed old plist must fall through, not crash");
  assert.match(r.stdout, /installed:/);
  assert.equal(backupCount(s), 1);
  const bak = fs.readdirSync(s.plistDir).find((f) => f.startsWith(`${LABEL}.plist.bak-`));
  assert.equal(fs.readFileSync(path.join(s.plistDir, bak), "utf8"), "PRE-EXISTING-BYTES (not a plist)");
  assert.equal(fs.readFileSync(plistPathOf(s), "utf8"), expectedPlistText(s, V2));
  assert.deepEqual(lcCalls(s), [
    `bootout gui/${UID}/${LABEL}`,
    `bootstrap gui/${UID} ${plistPathOf(s)}`,
    `print gui/${UID}/${LABEL}`,
  ]);
});

#!/usr/bin/env node
// measure-boot.mjs — repeatable startup-segment measurement for stand assembly jars.
//
// WHAT IT DOES (one process, fully self-contained):
//   1. generates a synthetic fixture home under /tmp (profiles below);
//   2. boots an ISOLATED gateway instance on that home (idle 809x port only,
//      --no-browser --foreground, NEBFLOW_GATEWAY_PORT fork-proof belt);
//   3. measures external spawn->listen wall clock + internal log segments
//      (same milestone vocabulary as the host boot autopsy evidence batch);
//   4. asserts isolation invariants (listener == self-spawned PID, port-6138
//      listener set unchanged before/after) and cleans up every round
//      (PID identity-verified kill, fixture home removed);
//   5. writes one JSON file per round plus a summary with medians.
//
// SAFETY CONTRACT (hard):
//   - never touches the real home (~/.stand / ~/.nebflow) — fixtures live in
//     /tmp only; the only reads outside /tmp are lsof/ps for PID verification;
//   - never signals anything except the PID it spawned, after verifying the
//     command line carries the jar / main class;
//   - refuses ports 6138/8080/8097 and any occupied port (never adopts);
//   - snapshots the :6138 listener set before/after and FAILS the run on drift.
//
// FIXTURE PROFILES (synthetic data-scale reproduction, shapes sampled from the
// real production data root read-only; all content synthetic):
//   empty     — bare home (floor measurement)
//   projects  — 20 projects approximating the host mix:
//                 1 heavy  (54k-line rounds side volume ~20MB, checkpoint 500
//                           rounds, junk cold files over the 32MB read budget)
//                 4 mid    (over-budget junk cold files, 3k-line side volume)
//                 12 under (valid small round files ~4MB total -> open-time
//                           cold audit/verify path runs in full)
//                 3 tiny   (minimal maps, no archive)
//   scopes    — 886 delegate scope dirs (86 with running residues) spread over
//               two project workspaces + the general workspace
//   combined  — projects + scopes on one home (closest to the host shape)
//
// USAGE:
//   node scripts/measure-boot.mjs --jar <path> [--profile combined]
//        [--port 8093] [--rounds 3] [--out <dir>] [--settle-ms 8000] [--keep]
//
// Output: per-round round-<n>.json in --out (default <repo>/.nebflow/tmp/measure-boot
// is NOT used — default is ./measure-out-<ts> under the CWD) and a median summary
// on stdout. Exit code 0 = all rounds ok + invariants held.

import { execFileSync, spawn } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";

// ── args ────────────────────────────────────────────────────────────────────
const args = process.argv.slice(2);
function argOf(flag, dflt) {
  const i = args.indexOf(flag);
  if (i === -1) return dflt;
  const v = args[i + 1];
  if (v === undefined || String(v).startsWith("--")) {
    throw new Error(`${flag} requires a value`);
  }
  return v;
}
const JAR = argOf("--jar", null);
const PROFILE = argOf("--profile", "empty");
const PORT = Number(argOf("--port", "8093"));
const ROUNDS = Number(argOf("--rounds", "3"));
const SETTLE_MS = Number(argOf("--settle-ms", "8000"));
const KEEP = args.includes("--keep");
const OUT = argOf("--out", path.resolve(`measure-out-${Date.now()}`));

if (!JAR) {
  console.error("usage: node scripts/measure-boot.mjs --jar <assembly.jar> [--profile p] [--port n] [--rounds n] [--out dir]");
  process.exit(2);
}
if (!fs.existsSync(JAR)) {
  console.error(`jar not found: ${JAR}`);
  process.exit(2);
}
if (!["empty", "projects", "scopes", "combined"].includes(PROFILE)) {
  console.error(`unknown profile: ${PROFILE}`);
  process.exit(2);
}
// Port guard: refuse the host/prod/protected ports and anything occupied.
const REFUSED_PORTS = new Set([6138, 8080, 8097]);
if (REFUSED_PORTS.has(PORT) || PORT < 1024 || PORT > 65535) {
  console.error(`port ${PORT} refused (protected or out of range)`);
  process.exit(2);
}

// ── small helpers ───────────────────────────────────────────────────────────
function sh(cmd) {
  return execFileSync(cmd, { shell: "/bin/bash", encoding: "utf8", timeout: 15000 }).trim();
}
function listenersOf(port) {
  try {
    return sh(`lsof -nP -iTCP:${port} -sTCP:LISTEN -t 2>/dev/null | sort | tr '\\n' ' '`).trim();
  } catch {
    return "";
  }
}
const nowMs = () => Number(process.hrtime.bigint() / 1000000n);
function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}
// Deterministic PRNG so fixture content is identical across rounds/builds.
function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// ── fixture generator ───────────────────────────────────────────────────────
// JSON shapes mirror the real on-disk formats (sampled read-only from the
// production data root): flow-map.json / chain-ledger.json (checkpoint) /
// chain-ledger-rounds.jsonl (manifest side volume) / round-<n>.json (cold
// rounds). Numbers and ids are synthetic.
const FIX_ROOT = "/tmp/qa-startup-measure";

function roundManifest(round, kind, moved) {
  return {
    round,
    at: 1789800000000 + round * 1000,
    kind,
    file: `round-${round}.json`,
    movedEntries: moved,
    movedAliases: 0,
    movedMembers: moved * 3,
    hotBefore: { entries: 4000, aliases: 10, members: 12000, refCount: 9000 },
    hotAfter: { entries: 4000 - moved, aliases: 10, members: 12000 - moved * 3, refCount: 9000 },
    digest: "0".repeat(40),
    tick: round,
  };
}
function coldRound(round, entryCount) {
  const entries = [];
  for (let i = 0; i < entryCount; i++) {
    entries.push({
      chainId: `chain-fx-${round}-${i}`,
      anchor: `node-fx-${round}-${i}`,
      bornAt: 1789800000000 + round,
      members: [`node-fx-${round}-${i}-a`, `node-fx-${round}-${i}-b`],
      memberCount: 2,
      status: "active",
      archivedAt: null,
      refCount: 2,
      compacted: false,
    });
  }
  return {
    round,
    at: 1789800000000 + round * 1000,
    kind: "retire",
    thresholds: { maxHotRows: 4096, maxHotBytes: 1048576, batchRows: 512 },
    hotBefore: { entries: 4096, aliases: 4, members: 8192, refCount: 6144 },
    hotAfter: { entries: 4096, aliases: 4, members: 8192, refCount: 6144 },
    entries,
    aliases: [],
  };
}
function checkpointState(project, rounds) {
  return {
    v: 1,
    project,
    updatedAt: 1789900000000,
    entries: {},
    aliases: {},
    externalRefs: {},
    rounds: rounds.map((m) => roundManifest(m.round, m.kind, m.movedEntries)),
    roundsTotal: rounds.length,
  };
}
function emptyFlowMap(project) {
  return { updatedAt: 1789900000000, project, v: 1, nodes: {}, manualRecoveryNodes: [] };
}
// A scope flow-map node cloned from the real production node shape (complete
// key set, long strings sanitized; types preserved so the codec decodes it).
const NODE_TEMPLATE = {
  agent: "nebflow",
  bgWait: null,
  blockCount: 0,
  blockedFeedback: null,
  chainId: "chain-fx",
  completedAt: null,
  createdAt: 1789800000000,
  deliveredTo: [],
  deps: [],
  description: "fixture node",
  descriptionLong: null,
  destroyAt: null,
  gen: 0,
  id: "n-fx",
  in: [],
  lastVerdict: null,
  loop: null,
  loopLastVerdict: null,
  loopPhase: null,
  loopRound: 0,
  loopStartedAt: null,
  mcp: null,
  merge: false,
  name: "n-fx",
  nebulaDeliveredAt: null,
  notify: null,
  notifyDispatcher: false,
  notifySentAt: null,
  out: [],
  pendingOut: null,
  pendingSuccession: [],
  plugins: [],
  preset: null,
  reportPendingSince: null,
  reportReminderCount: 0,
  result: null,
  retry: null,
  role: "task",
  sessionRef: null,
  sessionRefVerify: null,
  skill: null,
  startedAt: null,
  status: "pending",
  task: "fixture task",
  taskFile: null,
  taskId: null,
  ttlExpireAt: null,
  worktree: null,
};
function scopeNode(id, status, chainId) {
  return {
    ...NODE_TEMPLATE,
    id,
    name: id,
    status,
    chainId,
    task: `fixture task for ${id}`,
    createdAt: 1789800000000,
    startedAt: status === "running" ? 1789900000000 : null,
    completedAt: status === "completed" ? 1789900000000 : null,
    sessionRef: status === "running" ? `node-fx-${id}` : null,
  };
}

function writeJson(file, obj) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(obj));
}
function appendJsonl(file, objs) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const out = fs.openSync(file, "a");
  for (const o of objs) fs.writeSync(out, JSON.stringify(o) + "\n");
  fs.closeSync(out);
}

function makeProject(home, name, spec, rnd) {
  const ws = path.join(home, "ws", name);
  const projDir = path.join(home, "projects", name);
  writeJson(path.join(projDir, "project.json"), {
    device: "self",
    name,
    description: `fixture project ${name}`,
    workspace: ws,
    agentFile: path.join(ws, "AGENTS.md"),
    createdAt: 1789800000000,
  });
  const nf = path.join(ws, ".nebflow");
  writeJson(path.join(nf, "flow-map.json"), emptyFlowMap(name));
  const arch = path.join(nf, "chain-ledger-archive");
  if (spec.sideVolumeLines > 0) {
    const lines = [];
    for (let r = 1; r <= spec.sideVolumeLines; r++) {
      lines.push(roundManifest(r, r % 5 === 0 ? "dissolve" : "retire", 2 + Math.floor(rnd() * 8)));
    }
    appendJsonl(path.join(arch, "chain-ledger-rounds.jsonl"), lines);
  }
  if (spec.checkpointRounds > 0) {
    const rounds = [];
    for (let r = 1; r <= spec.checkpointRounds; r++) {
      rounds.push(roundManifest(r, "retire", 2));
    }
    writeJson(path.join(nf, "chain-ledger.json"), checkpointState(name, rounds));
  }
  if (spec.validColdRounds > 0) {
    // Valid small round files: the under-budget open-time audit parses these.
    for (let r = 1; r <= spec.validColdRounds; r++) {
      writeJson(path.join(arch, `round-${r}.json`), coldRound(r, spec.coldEntriesPerRound));
    }
  }
  if (spec.junkColdMB > 0) {
    // Over-budget junk cold file(s): never parsed on the over-budget path
    // (the byte-stat walk crosses the 32MB budget and skips the audit) —
    // only the file SIZE matters, so raw zeros are fine.
    const buf = Buffer.alloc(1024 * 1024);
    const f = path.join(arch, "rounds-fx-junk.jsonl");
    fs.writeFileSync(f, "");
    const fd = fs.openSync(f, "a");
    for (let i = 0; i < spec.junkColdMB; i++) fs.writeSync(fd, buf);
    fs.closeSync(fd);
  }
  return ws;
}

function makeScope(ws, scopeId, withRunning, seq) {
  const dir = path.join(ws, ".nebflow", "flow-maps", scopeId);
  const nodes = {};
  nodes[`n-${seq}-a`] = scopeNode(`n-${seq}-a`, "completed", `chain-fx-${seq}`);
  if (withRunning) {
    nodes[`n-${seq}-b`] = scopeNode(`n-${seq}-b`, "running", `chain-fx-${seq}`);
  }
  writeJson(path.join(dir, "flow-map.json"), {
    updatedAt: 1789900000000,
    project: "fx",
    v: 1,
    nodes,
    manualRecoveryNodes: [],
  });
  writeJson(path.join(dir, "chain-ledger.json"), checkpointState(scopeId, []));
  const arch = path.join(dir, "chain-ledger-archive");
  writeJson(path.join(arch, "round-1.json"), coldRound(1, 4));
  writeJson(path.join(arch, "round-2.json"), coldRound(2, 4));
  fs.mkdirSync(path.join(dir, "results"), { recursive: true });
  fs.mkdirSync(path.join(dir, "tasks"), { recursive: true });
}

function generateFixture(profile, root) {
  const rnd = mulberry32(20261113);
  const home = fs.mkdtempSync(root);
  const t0 = nowMs();
  let projects = [];
  if (profile === "projects" || profile === "combined") {
    const specs = [];
    specs.push({ name: "fx-heavy", sideVolumeLines: 54000, checkpointRounds: 500, junkColdMB: 40, validColdRounds: 0 });
    // Host-faithful multiplicity: the real home has FIVE projects with >32MB
    // cold archives (each a multi-second ledger open); give this profile four
    // more heavy ledgers (~15k manifest lines ≈ 5.5MB side volume each) so a
    // parallel mount actually has long poles to overlap.
    for (let i = 1; i <= 4; i++) {
      specs.push({ name: `fx-mid-${i}`, sideVolumeLines: 15000, checkpointRounds: 200, junkColdMB: 40, validColdRounds: 0 });
    }
    for (let i = 1; i <= 12; i++) {
      // ~4MB of valid cold rounds per project + a small side volume whose
      // manifest lines match the checkpoint rounds (host-faithful: the real
      // boot also re-parses the side volume as cold lines under budget and
      // logs per-line corrupt WARNs — 42 such lines in the host boot sample).
      specs.push({ name: `fx-under-${i}`, sideVolumeLines: 30, checkpointRounds: 30, validColdRounds: 250, coldEntriesPerRound: 60 });
    }
    for (let i = 1; i <= 3; i++) {
      specs.push({ name: `fx-tiny-${i}`, sideVolumeLines: 0, checkpointRounds: 0 });
    }
    projects = specs.map((s) => makeProject(home, s.name, s, rnd));
  }
  if (profile === "scopes" || profile === "combined") {
    // Two host workspaces + the general workspace carry 886 scopes total
    // (86 with a running residue, mirroring the host claim counts).
    const hostA = makeProject(home, "fx-scopehost-a", { sideVolumeLines: 0, checkpointRounds: 0 }, rnd);
    const hostB = makeProject(home, "fx-scopehost-b", { sideVolumeLines: 0, checkpointRounds: 0 }, rnd);
    projects = [hostA, hostB];
    const general = path.join(home, "general");
    fs.mkdirSync(general, { recursive: true });
    let seq = 0;
    const put = (ws, count, runningEvery) => {
      for (let i = 0; i < count; i++) {
        seq++;
        makeScope(ws, `delegate-fx-${String(seq).padStart(4, "0")}`, i % runningEvery === 0, seq);
      }
    };
    put(hostA, 800, 10); // ~80 running
    put(hostB, 86, 2); // ~43 running
    put(general, 50, 25); // ~2 running
  }
  const genMs = nowMs() - t0;
  return { home, genMs, projects };
}

// ── log segment parser ──────────────────────────────────────────────────────
const MILESTONES = [
  ["ownerGate", /owner check skipped|owner check/],
  ["dbOpen", /db handle opened for data root/],
  ["searchIndexOpen", /search-index: face 'chat' opened at/],
  ["createLlm", /sandbox provider=/],
  ["versionLine", /stand \d{4}\.\d+\.\d+/],
  ["watchReplay", /watch replay:/],
  ["pendingAskSweep", /Pending-ask boot reconcile:/],
  ["ttlSweeps", /\[delegate-ttl\]|Delegate TTL sweep:/],
  ["crashMailboxSweeps", /\[mailbox-boot-sweep\]|Mailbox boot reconcile:/],
  ["seed", /^.*\bSeed:/],
  ["startupMount", /Startup mount:/],
  ["projectCrashSweep", /Boot crash-recovery sweep:/],
  ["delegateScopeSweep", /Boot delegate-scope sweep:/],
  ["bootWake", /Boot dispatcher wake:/],
  ["msgTokens", /Msg-token boot reconcile:/],
  ["listening", /gateway listening on/],
];
function parseLogTs(line) {
  const m = line.match(/^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{3})/);
  if (!m) return null;
  return new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6], +m[7]).getTime();
}
function parseSegments(logFile, spawnEpoch) {
  const lines = fs.readFileSync(logFile, "utf8").split("\n");
  let firstTs = null;
  const marks = {};
  const selfChecks = [];
  const warns = [];
  for (const line of lines) {
    const ts = parseLogTs(line);
    if (ts === null) continue;
    if (firstTs === null) firstTs = ts;
    for (const [name, re] of MILESTONES) {
      if (marks[name] === undefined && re.test(line)) marks[name] = ts;
    }
    const sc = line.match(/chain-ledger\[(.+?)\] self-check/);
    if (sc) selfChecks.push({ project: sc[1], ts });
    if (/WARN/.test(line)) warns.push(line.slice(0, 200));
  }
  const segs = {};
  let prev = firstTs;
  let prevName = "firstLine";
  if (firstTs !== null) segs.firstLine = { sinceSpawn: firstTs - spawnEpoch, delta: 0, ts: firstTs, after: "" };
  for (const [name] of MILESTONES) {
    if (marks[name] === undefined) continue;
    segs[name] = { sinceSpawn: marks[name] - spawnEpoch, delta: marks[name] - prev, ts: marks[name], after: "" };
    prev = marks[name];
    prevName = name;
  }
  if (marks.listening !== undefined) {
    for (const [name] of MILESTONES) {
      if (marks[name] !== undefined && marks[name] > marks.listening) segs[name].after = "post-listen";
    }
  }
  return { segs, selfChecks, warns, firstTs, listeningTs: marks.listening ?? null };
}

// ── one measurement round ───────────────────────────────────────────────────
function pickJava() {
  const pinned = path.join(os.homedir(), ".stand/jdk-21/bin/java");
  if (fs.existsSync(pinned)) return pinned;
  return "java";
}
async function runRound(n, javaBin) {
  const h6138Before = listenersOf(6138);
  const pre = listenersOf(PORT);
  if (pre !== "") throw new Error(`port ${PORT} occupied by [${pre}] — refusing to adopt`);

  const { home, genMs } = generateFixture(PROFILE, `${FIX_ROOT}-r${n}-`);
  const stdoutLog = path.join(home, "boot-stdout.log");
  const out = fs.openSync(stdoutLog, "a");

  const spawnEpoch = Date.now();
  const child = spawn(javaBin, [
    "--add-opens", "java.base/java.lang=ALL-UNNAMED",
    "-cp", path.resolve(JAR), "nebflow.Main",
    "--home", home, "--port", String(PORT), "--no-browser", "--foreground", "start",
  ], {
    // STAND_HOME/NEBFLOW_HOME are belt-and-braces with --home: logback's FILE
    // appender resolves its path from the ENV (not the CLI flag), and the
    // isolation guard keys on a redirected data root.
    env: { ...process.env, NEBFLOW_GATEWAY_PORT: String(PORT), STAND_HOME: home, NEBFLOW_HOME: home },
    stdio: ["ignore", out, out],
    detached: false,
  });
  const pid = child.pid;
  let cleaned = false;
  const cleanup = async () => {
    if (cleaned) return;
    cleaned = true;
    try {
      const cmd = sh(`ps -p ${pid} -o command= 2>/dev/null`);
      if (cmd.includes("nebflow") || cmd.includes(path.basename(JAR))) {
        process.kill(pid, "SIGTERM");
        await sleep(1500);
        try {
          process.kill(pid, 0);
          process.kill(pid, "SIGKILL");
        } catch { /* already gone */ }
      } else if (cmd !== "") {
        console.error(`ROUND ${n}: refusing to kill pid ${pid} (cmd mismatch: ${cmd})`);
      }
    } catch { /* process gone */ }
    try { child.kill("SIGKILL"); } catch { /* gone */ }
    fs.closeSync(out);
    await sleep(300);
    const left = listenersOf(PORT);
    if (left !== "") console.error(`ROUND ${n}: WARNING port ${PORT} still held by [${left}]`);
    if (!KEEP) fs.rmSync(home, { recursive: true, force: true });
  };

  try {
    // Poll for listen (200ms cadence, 120s deadline).
    let listenEpoch = null;
    const deadline = Date.now() + 120000;
    while (Date.now() < deadline) {
      if (listenersOf(PORT) !== "") { listenEpoch = Date.now(); break; }
      const [code] = [child.exitCode];
      if (child.exitCode !== null) throw new Error(`process exited early with code ${child.exitCode}`);
      await sleep(200);
    }
    if (listenEpoch === null) throw new Error("no listener within 120s");

    // Health probe (definition of serviceable).
    let health = null;
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/api/health`, { signal: AbortSignal.timeout(5000) });
      health = r.status;
    } catch (e) {
      health = `ERR:${String(e).slice(0, 60)}`;
    }

    // Let post-listen background legs flush into the log.
    await sleep(SETTLE_MS);

    // Post-flight isolation assertions.
    const listeners = listenersOf(PORT).trim().split(/\s+/).filter(Boolean);
    const h6138After = listenersOf(6138);
    const listenerOk = listeners.length === 1 && String(listeners[0]) === String(pid);

    const logFile = path.join(home, "logs", "stand.log");
    const parsed = fs.existsSync(logFile)
      ? parseSegments(logFile, spawnEpoch)
      : { segs: {}, selfChecks: [], warns: ["(no log file)"], firstTs: null, listeningTs: null };

    return {
      round: n,
      profile: PROFILE,
      port: PORT,
      pid,
      fixtureGenMs: genMs,
      externalSpawnToListenMs: listenEpoch - spawnEpoch,
      health,
      engineInternalMs: parsed.listeningTs !== null && parsed.firstTs !== null ? parsed.listeningTs - parsed.firstTs : null,
      segments: parsed.segs,
      selfCheckCount: parsed.selfChecks.length,
      selfCheckProjects: [...new Set(parsed.selfChecks.map((s) => s.project))].length,
      warnCount: parsed.warns.length,
      warnSample: parsed.warns.slice(0, 6),
      invariants: {
        listenerIsSelf: listenerOk,
        h6138Unchanged: h6138Before === h6138After,
        h6138Before,
        h6138After,
      },
      fixtureHome: KEEP ? home : "(removed)",
    };
  } finally {
    await cleanup();
  }
}

// ── main ────────────────────────────────────────────────────────────────────
function median(xs) {
  const s = xs.slice().sort((a, b) => a - b);
  if (s.length === 0) return null;
  const m = Math.floor(s.length / 2);
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

const h6138GlobalBefore = listenersOf(6138);
console.log(`[measure-boot] jar=${path.basename(JAR)} profile=${PROFILE} port=${PORT} rounds=${ROUNDS}`);
console.log(`[measure-boot] 6138 listener set before: [${h6138GlobalBefore || "(none)"}]`);

fs.mkdirSync(OUT, { recursive: true });
const javaBin = pickJava();
console.log(`[measure-boot] java=${javaBin}`);

const results = [];
for (let n = 1; n <= ROUNDS; n++) {
  const t = nowMs();
  const r = await runRound(n, javaBin);
  results.push(r);
  fs.writeFileSync(path.join(OUT, `round-${n}.json`), JSON.stringify(r, null, 2));
  console.log(
    `[measure-boot] round ${n}/${ROUNDS}: external=${r.externalSpawnToListenMs}ms internal=${r.engineInternalMs}ms ` +
    `health=${r.health} selfChecks=${r.selfCheckCount} warns=${r.warnCount} invariants=${r.invariants.listenerIsSelf && r.invariants.h6138Unchanged ? "OK" : "VIOLATION"} (${nowMs() - t}ms wall)`
  );
  if (r.segments) {
    const order = MILESTONES.map(([name]) => name);
    for (const name of ["firstLine", ...order]) {
      const s = r.segments[name];
      if (s) console.log(`    ${name.padEnd(20)} +${(s.sinceSpawn / 1000).toFixed(3)}s  Δ${String(s.delta).padStart(7)}ms ${s.after}`);
    }
  }
}

const h6138GlobalAfter = listenersOf(6138);
const summary = {
  jar: path.basename(JAR),
  profile: PROFILE,
  rounds: ROUNDS,
  externalSpawnToListenMs: { values: results.map((r) => r.externalSpawnToListenMs), median: median(results.map((r) => r.externalSpawnToListenMs)) },
  engineInternalMs: { values: results.map((r) => r.engineInternalMs), median: median(results.map((r) => r.engineInternalMs).filter((v) => v !== null)) },
  invariantsAllOk: results.every((r) => r.invariants.listenerIsSelf && r.invariants.h6138Unchanged),
  h6138Drift: h6138GlobalBefore !== h6138GlobalAfter ? { before: h6138GlobalBefore, after: h6138GlobalAfter } : null,
  outDir: OUT,
};
fs.writeFileSync(path.join(OUT, "summary.json"), JSON.stringify(summary, null, 2));
console.log(`[measure-boot] MEDIAN external spawn->listen: ${summary.externalSpawnToListenMs.median}ms  engine-internal: ${summary.engineInternalMs.median}ms`);
console.log(`[measure-boot] invariants: ${summary.invariantsAllOk ? "OK (listener=self each round, 6138 set unchanged)" : "VIOLATION — see round jsons"}`);
if (summary.h6138Drift) console.error(`[measure-boot] 6138 DRIFT: ${JSON.stringify(summary.h6138Drift)}`);
console.log(`[measure-boot] results: ${OUT}`);
process.exit(summary.invariantsAllOk ? 0 : 1);

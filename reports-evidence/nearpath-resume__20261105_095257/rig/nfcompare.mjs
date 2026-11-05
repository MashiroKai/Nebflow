// nfcompare.mjs — 「同一份文件、同一台机器」的取件腿 A/B：环回腿（本机直连）vs 隧道腿（门户）。
//
// 问题（作者原令）：Mac 上开 Mac 文件的耗时/路径，改造前后到底差多少？
// 本脚本把差异钉在两件可复算的事情上：
//   腿 L（loopback）  GET http://127.0.0.1:6410/api/nf-file?path=…&ticket=…   ← 近路命中后浏览器走的路
//   腿 T（tunnel）    GET https://mystand.space/api/nf-file?…（经 rig 门户 ingress）
//                     ⇒ portal wire 帧面：http_request / body_frame(base64, 256KiB 切片)
//   基线 D（disk）    同一文件 fs.readFileSync 的纯磁盘读（页缓存命中态），给上面两腿做地板
//
// 纪律：
//   · 只在隔离引擎（:6410）上跑，生产 6138 零接触；
//   · 腿 T 走的是**本机假中继**（无公网 RTT）⇒ 本脚本给出的是「隧道编码/分帧税」的下界，
//     生产还要叠加公网 RTT（审计件 §6.3：ping 43.1ms）。这一点在输出里显式标注，不混淆。
//
// 用量：node nfcompare.mjs [--engine-src DIR] [--classpath FILE] [--n-small 30]

import https from 'node:https';
import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { startRig } from './rig.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));

const ROOT = '/tmp/nearpath-fire';
const FILES_DIR = path.join(ROOT, 'files');
const FILE_TOKEN = 'rig-file-token';   // rig home 的 auth.json 内容（Bearer = 文件 token 轨）
const ENGINE_PORT = 6410;
const INGRESS_PORT = 7443;

const arg = (k, d = null) => {
  const i = process.argv.indexOf(k);
  return i > 0 ? process.argv[i + 1] : d;
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 样本文件（内容随机，大小固定；可复算：sha256 会打出来）────────────────────
const SPECS = [
  { label: '4 KiB', bytes: 4 * 1024, n: Number(arg('--n-small', 30)) },
  { label: '1 MiB', bytes: 1024 * 1024, n: Number(arg('--n-mid', 20)) },
  { label: '10 MiB', bytes: 10 * 1024 * 1024, n: Number(arg('--n-big', 10)) },
];

function ensureFiles() {
  fs.mkdirSync(FILES_DIR, { recursive: true });
  return SPECS.map((s) => {
    const p = path.join(FILES_DIR, `sample-${s.bytes}.bin`);
    if (!fs.existsSync(p) || fs.statSync(p).size !== s.bytes) {
      fs.writeFileSync(p, crypto.randomBytes(s.bytes));
    }
    const sha = crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
    return { ...s, path: p, sha };
  });
}

// ── HTTP 取件（两腿共用同一计时口径：首字节→末字节，读满 body）─────────────────
function fetchOnce({ leg, filePath, ticket }) {
  return new Promise((resolve, reject) => {
    const q = `?path=${encodeURIComponent(filePath)}&ticket=${encodeURIComponent(ticket)}`;
    const t0 = process.hrtime.bigint();
    let tFirst = null;
    let bytes = 0;
    const onRes = (res) => {
      res.on('data', (c) => {
        if (tFirst === null) tFirst = process.hrtime.bigint();
        bytes += c.length;
      });
      res.on('end', () => {
        const t1 = process.hrtime.bigint();
        resolve({
          status: res.statusCode,
          bytes,
          totalMs: Number(t1 - t0) / 1e6,
          ttfbMs: tFirst === null ? null : Number(tFirst - t0) / 1e6,
        });
      });
    };
    if (leg === 'loopback') {
      // 每请求一个新 socket（cold 语义）；warm 轮由 keep-alive agent 复用，见 runLeg
      const req = http.request({
        host: '127.0.0.1', port: ENGINE_PORT, path: `/api/nf-file${q}`, method: 'GET', agent: false,
      }, onRes);
      req.on('error', reject);
      req.end();
    } else {
      const req = https.request({
        host: '127.0.0.1', port: INGRESS_PORT, path: `/api/nf-file${q}`, method: 'GET',
        servername: 'mystand.space',
        headers: { host: 'mystand.space' },
        rejectUnauthorized: false,
      }, onRes);
      req.on('error', reject);
      req.end();
    }
  });
}

function fetchOnceKeepAlive(agent, { leg, filePath, ticket }) {
  return new Promise((resolve, reject) => {
    const q = `?path=${encodeURIComponent(filePath)}&ticket=${encodeURIComponent(ticket)}`;
    const t0 = process.hrtime.bigint();
    let tFirst = null;
    let bytes = 0;
    const opts = leg === 'loopback'
      ? { host: '127.0.0.1', port: ENGINE_PORT, path: `/api/nf-file${q}`, method: 'GET', agent }
      : { host: '127.0.0.1', port: INGRESS_PORT, path: `/api/nf-file${q}`, method: 'GET', servername: 'mystand.space', headers: { host: 'mystand.space' }, rejectUnauthorized: false, agent };
    const req = (leg === 'loopback' ? http : https).request(opts, (res) => {
      res.on('data', (c) => {
        if (tFirst === null) tFirst = process.hrtime.bigint();
        bytes += c.length;
      });
      res.on('end', () => {
        const t1 = process.hrtime.bigint();
        resolve({ status: res.statusCode, bytes, totalMs: Number(t1 - t0) / 1e6, ttfbMs: tFirst === null ? null : Number(tFirst - t0) / 1e6 });
      });
    });
    req.on('error', reject);
    req.end();
  });
}

// ── 铸票（Bearer = 文件 token 轨；票 uses=-1 ⇒ 同一张票可重复取件，A/B 两腿共用一张）──
function mintTickets(paths) {
  return new Promise((resolve, reject) => {
    const body = Buffer.from(JSON.stringify({ sessionId: 'rig-nf-ab', paths }));
    const req = http.request({
      host: '127.0.0.1', port: ENGINE_PORT, path: '/api/nf-ticket', method: 'POST', agent: false,
      headers: { 'content-type': 'application/json', 'content-length': body.length, authorization: `Bearer ${FILE_TOKEN}` },
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const txt = Buffer.concat(chunks).toString('utf8');
        if (res.statusCode >= 400) return reject(new Error(`nf-ticket → ${res.statusCode}: ${txt.slice(0, 300)}`));
        try { resolve(JSON.parse(txt)); } catch (e) { reject(new Error('nf-ticket bad json: ' + txt.slice(0, 200))); }
      });
    });
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

const stats = (xs) => {
  const s = [...xs].sort((a, b) => a - b);
  const q = (p) => s[Math.min(s.length - 1, Math.max(0, Math.ceil((p / 100) * s.length) - 1))];
  const sum = s.reduce((a, b) => a + b, 0);
  return { n: s.length, min: s[0], med: q(50), p95: q(95), max: s[s.length - 1], mean: sum / s.length };
};

// ── 主流程 ─────────────────────────────────────────────────────────────────
const files = ensureFiles();
const rig = await startRig({
  engineSrc: arg('--engine-src') || `${ROOT}/engine-src`,
  classpathFile: arg('--classpath') || `${ROOT}/classpath.txt`,
});
const out = { engineSrc: rig.cfg ? undefined : undefined, files: [], legs: {} };

try {
  const minted = await mintTickets(files.map((f) => f.path));
  const tickets = {};
  for (const f of files) {
    const t = minted.tickets?.[f.path];
    if (!t) throw new Error(`no ticket for ${f.path}; rejected=${JSON.stringify(minted.rejected)}`);
    tickets[f.path] = t.t;
  }

  // 纯磁盘地板（页缓存命中态；读 5 次取中位）
  for (const f of files) {
    const ms = [];
    for (let i = 0; i < 5; i++) {
      const t0 = process.hrtime.bigint();
      const b = fs.readFileSync(f.path);
      ms.push(Number(process.hrtime.bigint() - t0) / 1e6);
      if (b.length !== f.bytes) throw new Error('short read');
    }
    f.diskMs = stats(ms).med;
  }

  const loopAgent = new http.Agent({ keepAlive: true, maxSockets: 1 });
  const tunAgent = new https.Agent({ keepAlive: true, maxSockets: 1, rejectUnauthorized: false });
  const agentOf = (leg) => (leg === 'loopback' ? loopAgent : tunAgent);

  // 预热两腿各 3 次（不计入）——JIT/内核缓存不能只让先跑的那条腿吃到
  for (const f of files) {
    for (const leg of ['loopback', 'tunnel']) {
      const args = { leg, filePath: f.path, ticket: tickets[f.path] };
      for (let i = 0; i < 3; i++) await fetchOnceKeepAlive(agentOf(leg), args);
    }
  }

  // 交错配对（paired A/B）：同一轮里先环回后隧道，n 轮 ⇒ 两腿吃同一段时间的
  // 机器状态（JIT 成熟度、页缓存、CPU 频率），避免「先跑的那条腿替另一条背冷启动税」。
  out.legs.loopback = [];
  out.legs.tunnel = [];
  for (const f of files) {
    const perLeg = { loopback: [], tunnel: [] };
    const coldByLeg = {};
    const frames0 = { ...rig.state.frameStats };
    for (let i = 0; i < f.n; i++) {
      for (const leg of ['loopback', 'tunnel']) {
        const args = { leg, filePath: f.path, ticket: tickets[f.path] };
        if (i === 0) coldByLeg[leg] = await fetchOnce(args);   // 冷连接（新 socket）只取一次
        perLeg[leg].push(await fetchOnceKeepAlive(agentOf(leg), args));
      }
    }
    const frames1 = rig.state.frameStats;
    // 帧读数只在隧道腿上才有意义：按隧道腿响应数（n + 1 冷）归一
    const tunResponses = f.n + 1;
    for (const leg of ['loopback', 'tunnel']) {
      const rs = perLeg[leg];
      const isTun = leg === 'tunnel';
      const wireBytes = isTun ? frames1.bodyBytesB64 - frames0.bodyBytesB64 : 0;
      const wireFrames = isTun ? frames1.bodyFramesDown - frames0.bodyFramesDown : 0;
      const row = {
        leg, size: f.label, bytes: f.bytes, responses: rs.length + 1,
        coldMs: coldByLeg[leg].totalMs, coldStatus: coldByLeg[leg].status,
        warm: stats(rs.map((r) => r.totalMs)),
        ttfb: stats(rs.map((r) => r.ttfbMs)),
        statuses: [...new Set([coldByLeg[leg].status, ...rs.map((r) => r.status)])],
        wireBytesB64: wireBytes,
        wireFrames,
        wireB64PerResp: isTun ? wireBytes / tunResponses : 0,
        framesPerResp: isTun ? wireFrames / tunResponses : 0,
        // base64 膨胀（每响应）：线上 base64 字符数 / 原始字节数；理论值 = 4/3 = 1.333
        inflationPerResp: isTun ? (wireBytes / tunResponses) / f.bytes : 0,
        httpRequestsOnWire: isTun ? frames1.httpRequests - frames0.httpRequests : 0,
      };
      row.throughputMiBs = (f.bytes / (1024 * 1024)) / (row.warm.med / 1000);
      out.legs[leg].push(row);
      console.log(`${leg.padEnd(9)} ${f.label.padEnd(6)} cold=${row.coldMs.toFixed(2)}ms warm.med=${row.warm.med.toFixed(2)}ms p95=${row.warm.p95.toFixed(2)}ms ` +
        `ttfb.med=${row.ttfb.med.toFixed(2)}ms frames/resp=${row.framesPerResp.toFixed(0)} wireB64/resp=${(row.wireB64PerResp / 1024).toFixed(1)}KiB ` +
        `inflate=${row.inflationPerResp.toFixed(2)}x`);
    }
  }
  loopAgent.destroy();
  tunAgent.destroy();

  // ── 对照表 ──
  const rows = files.map((f, i) => {
    const L = out.legs.loopback[i];
    const T = out.legs.tunnel[i];
    return {
      size: f.label, bytes: f.bytes,
      diskMs: Number(f.diskMs.toFixed(3)),
      loopMed: Number(L.warm.med.toFixed(2)), loopP95: Number(L.warm.p95.toFixed(2)), loopCold: Number(L.coldMs.toFixed(2)),
      tunMed: Number(T.warm.med.toFixed(2)), tunP95: Number(T.warm.p95.toFixed(2)), tunCold: Number(T.coldMs.toFixed(2)),
      speedupMed: Number((T.warm.med / L.warm.med).toFixed(2)),
      tunnelFramesPerResp: Math.round(T.framesPerResp),
      tunnelWireKiBPerResp: Number((T.wireB64PerResp / 1024).toFixed(1)),
      wireInflation: Number(T.inflationPerResp.toFixed(2)),
      sha256: f.sha,
      n: f.n,
    };
  });
  out.table = rows;

  console.log('\n| 文件 | 纯磁盘 | 环回腿 med | 环回 p95 | 环回 cold | 隧道腿 med | 隧道 p95 | 隧道 cold | 隧道/环回 | 隧道帧数/响应 | 线上 base64/响应 | 膨胀 |');
  console.log('|---|---|---|---|---|---|---|---|---|---|---|---|');
  for (const r of rows) {
    console.log(`| ${r.size} | ${r.diskMs} ms | **${r.loopMed} ms** | ${r.loopP95} ms | ${r.loopCold} ms | ${r.tunMed} ms | ${r.tunP95} ms | ${r.tunCold} ms | ${r.speedupMed}× | ${r.tunnelFramesPerResp} | ${r.tunnelWireKiBPerResp} KiB | ${r.wireInflation}× |`);
  }
  console.log('\n注：隧道腿在本机假中继上跑（无公网 RTT），因此这里的倍率是**隧道编码/分帧税的下界**；');
  console.log('    生产门户 egress 还要叠加每程 RTT（审计件 §6.3 实测 ping 43.1ms）。');
} finally {
  out.finishedAt = new Date().toISOString();
  out.engine = { engineSrc: arg('--engine-src') || `${ROOT}/engine-src`, classpath: arg('--classpath') || `${ROOT}/classpath.txt` };
  const tag = arg('--tag', '');
  const outFile = path.resolve(HERE, '..', 'red', tag ? `nfcompare-${tag}.json` : 'nfcompare.json');
  fs.writeFileSync(outFile, JSON.stringify(out, null, 2));
  console.log(`\njson → ${outFile}`);
  await rig.stop();
  // 跑完即退：JSON 已落盘 = 本次测量成功。残留的 keep-alive/pipe 不该改变退出码。
  process.exit(0);
}

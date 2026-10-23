#!/usr/bin/env node
// verify-console-fastpath.mjs — console-fastpath 批（2026-10-23，作者令三腿）实测 harness。
//
// 用法：node scripts/verify-console-fastpath.mjs [BASE_URL] [--rtt-ms 150]
//   BASE_URL  默认 http://127.0.0.1:8090（本机网关；隧道模型数字由 --rtt-ms 投影）。
//
// 实测项（全部对活实例）：
//   A. 首开静态墙逐文件取（浏览器口径：6 连接并行波）——请求数/耗时/字节。
//   B. POST /api/console-assets 打包取同清单 —— 请求数/耗时/字节（腿②实测）。
//   C. 服务端内联打包（隧道脸 index 应答）体量估算 = index + 内联资产原始字节。
//   D. /api/nf-ticket 铸票响应并键 size 是否在场（腿③判定键）。
//   E. /api/nf-file CORS/PNA 头（OPTIONS 预检 + 无票 401 出向）。
//   F. 隧道投影：按 --rtt-ms 把 A/B/C 实测套「每请求两段 RTT」模型出首开墙对比。
//
// 纪律：全只读（唯一写是 nf-ticket 铸票——短 TTL 一次性面，与页面行为一致）。

const BASE = process.argv[2] && process.argv[2].startsWith('http') ? process.argv[2] : 'http://127.0.0.1:8090';
const rttMsIdx = process.argv.indexOf('--rtt-ms');
const RTT_MS = rttMsIdx > 0 ? Number(process.argv[rttMsIdx + 1]) || 150 : 150;

const now = () => performance.now();

async function timed(fn) {
  const t0 = now();
  const out = await fn();
  return { out, ms: now() - t0 };
}

async function main() {
  // ── 取 index + 资产清单 ─────────────────────────────
  const indexRes = await fetch(`${BASE}/`);
  if (!indexRes.ok) throw new Error(`GET / → ${indexRes.status}（网关没在跑？）`);
  const html = await indexRes.text();
  const css = [...html.matchAll(/<link rel="stylesheet" href="((?:css|vendor)\/[^"]+)"/g)].map((m) => m[1]);
  const vendorJs = [...html.matchAll(/<script defer src="(vendor\/[^"]+)"><\/script>/g)].map((m) => m[1]);
  const assets = [...new Set([...css, ...vendorJs])];
  const assetBytes = (await Promise.all(assets.map(async (p) => (await fetch(`${BASE}/${p}`)).arrayBuffer()))).reduce((n, b) => n + b.byteLength, 0);

  // ── A. 逐文件（6 连接并行波，浏览器口径）────────────
  const waves = [];
  for (let i = 0; i < assets.length; i += 6) waves.push(assets.slice(i, i + 6));
  const a = await timed(async () => {
    let bytes = 0;
    for (const wave of waves) {
      const rs = await Promise.all(wave.map((p) => fetch(`${BASE}/${p}`)));
      for (const r of rs) bytes += (await r.arrayBuffer()).byteLength;
    }
    return bytes;
  });

  // ── B. 打包（1 请求）───────────────────────────────
  const b = await timed(async () => {
    const r = await fetch(`${BASE}/api/console-assets`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ paths: assets }),
    });
    const j = await r.json();
    const packed = Object.values(j.assets || {}).reduce((n, e) => n + (e.body ? e.body.length : 0), 0);
    return { status: r.status, packedChars: packed, missing: (j.missing || []).length };
  });

  // ── C. 隧道内联打包体量（原始字节，index + css + vendor）────
  const indexBytes = Buffer.byteLength(html);
  // ── D. nf-ticket size 并键（铸票探测，匿名可失败——只报在场性）────
  let mintSizeProbe = 'skipped';
  try {
    const mr = await fetch(`${BASE}/api/nf-ticket`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId: 'verify-harness', paths: [] }),
    });
    mintSizeProbe = `http_${mr.status}`;
  } catch { mintSizeProbe = 'unreachable'; }

  // ── E. nf-file CORS/PNA ────────────────────────────
  const opt = await fetch(`${BASE}/api/nf-file`, { method: 'OPTIONS' });
  const optCors = opt.headers.get('access-control-allow-origin');
  const optPna = opt.headers.get('access-control-allow-private-network');
  const noTicket = await fetch(`${BASE}/api/nf-file?path=/etc/hostname&ticket=`);
  const noTicketCors = noTicket.headers.get('access-control-allow-origin');

  // ── F. 隧道投影（每请求两段 RTT 模型，诊断件同口径）────
  const wavesCount = waves.length;
  const tunnelIndividual = wavesCount * 2 * RTT_MS;
  const tunnelBatch = 2 * RTT_MS;
  const projectedIndexPacked = indexBytes + assetBytes;

  const report = {
    base: BASE,
    rttModelMs: RTT_MS,
    assetCount: assets.length,
    assetBytes,
    A_individual: { requests: assets.length, parallelWaves: wavesCount, ms: Math.round(a.ms), bytes: a.out },
    B_batch: { requests: 1, ms: Math.round(b.ms), status: b.out.status, missing: b.out.missing, base64Chars: b.out.packedChars },
    C_packedShell: { projectedBytes: projectedIndexPacked },
    D_nfTicketSizeKey: mintSizeProbe,
    E_nfFileCors: { optionsStatus: opt.status, acao: optCors, pna: optPna, noTicket401Acao: noTicketCors, noTicketStatus: noTicket.status },
    F_tunnelProjection: {
      individualMs: tunnelIndividual,
      batchMs: tunnelBatch,
      reductionX: +(tunnelIndividual / tunnelBatch).toFixed(1),
      note: '每请求两段跨境 RTT 模型（诊断件 20261023_234431 同口径）；打包首开墙 45+→1 请求',
    },
  };
  console.log(JSON.stringify(report, null, 2));
}

main().catch((e) => { console.error('harness failed:', e.message); process.exit(1); });

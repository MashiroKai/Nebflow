// gates.mjs — 用 rig.mjs 的实测台把 ws.js 四道闸逐闸钉死（Chromium + WebKit）。
//
// 一条命令：
//   node gates.mjs                    # 全矩阵（chromium 全变体 + webkit 子集）
//   node gates.mjs --engine chromium  # 只跑一个引擎
//   node gates.mjs --only V0,V7       # 只跑指定变体
//
// 产物（red/）：
//   results-<engine>-<axis>.json   每次运行的原始读数（miss 原文/console/网络/screenshot）
//   screenshots/*.png
//   summary.json                   汇总
//   engine-<ts>.log                引擎日志片段（近路相关行）
//
// 判据：
//   hit  = 页面整页跳到 http://127.0.0.1:6410/?ticket=…（闸①②③④ 全过）
//   miss = sessionStorage['stand_ws_nearpath_loopback_miss'] 有值（ws.js:757-765），
//          `reason` 原文即该闸的失败分支标记

import { chromium, webkit } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import https from 'node:https';
import { fileURLToPath } from 'node:url';
import { startRig, readEngineLog } from './rig.mjs';

// rig 控制面永远走 127.0.0.1（不能走页面 origin：mystand.space 在 Node 侧是真 DNS，
// 只有浏览器侧才有 --host-resolver-rules 的映射；且自签证书要放行）。
const CTL_BASE = 'https://127.0.0.1:7443';
function ctl(pathname, { method = 'GET', body = null } = {}) {
  return new Promise((resolve, reject) => {
    const req = https.request(`${CTL_BASE}${pathname}`, {
      method, rejectUnauthorized: false,
      headers: body ? { 'Content-Type': 'application/json' } : {},
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        if (res.statusCode >= 400) return reject(new Error(`${pathname} → ${res.statusCode}: ${text.slice(0, 200)}`));
        try { resolve(JSON.parse(text)); } catch { resolve(text); }
      });
    });
    req.on('error', reject);
    if (body) req.write(JSON.stringify(body));
    req.end();
  });
}

const HERE = path.dirname(fileURLToPath(import.meta.url));
const RED = path.resolve(HERE, '..', 'red');
fs.mkdirSync(path.join(RED, 'screenshots'), { recursive: true });

const MISS_KEY = 'stand_ws_nearpath_loopback_miss';
const ENGINE_PORT = 6410;
const INGRESS_PORT = 7443;

// presence/P2P 平面（NeblinkPresenceService）的日志信号。闸链全程在浏览器 HTTP 面，
// 不该出现任何 presence 活动 —— 这是「近路判定链零 presence 参与」的判据串。
const PRESENCE_RE = /presence sendData|Presence dial failed|connectionsHasKey=/;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const arg = (k, d = null) => {
  const i = process.argv.indexOf(k);
  return i > 0 ? process.argv[i + 1] : d;
};

// ── 变体矩阵：每个变体只拧一道闸，其余全开 ─────────────────────────────────
// expectWebkit：WebKit 的闸③ 会被**混合内容策略**杀掉（实测：[blocked] ... insecure
// content ... http://127.0.0.1:6411/api/health），所以凡是要走到闸③ 的变体，
// WebKit 侧的预期结论就是 probe_unreachable —— 这不是台子跑歪，正是结论本身。
const VARIANTS = [
  { id: 'V0-full', note: '四闸全开（真实链路；闸③ 走 :6411 观测代理 → :6410）', modes: { portalStatus: 'ok', loopbackInfo: 'probe_proxy', mint: 'ok', probe: 'pass' }, expect: 'hit', expectWebkit: 'probe_unreachable' },
  { id: 'V0b-direct', note: '四闸全开且闸③ 直连 :6410（loopback-info 由引擎原样答）', modes: { portalStatus: 'ok', loopbackInfo: 'engine', mint: 'ok', probe: 'pass' }, expect: 'hit', expectWebkit: 'probe_unreachable' },
  { id: 'V1-g1-404', note: '闸①：/api/portal/status 404', modes: { portalStatus: 'http404' }, expect: 'portal_status_http_404' },
  { id: 'V2-g1-nobound', note: '闸①：portal/status 200 但无 boundHost', modes: { portalStatus: 'no_bound_host' }, expect: 'no_bound_host' },
  { id: 'V3-g2-403', note: '闸②：loopback-info 403', modes: { loopbackInfo: 'http403' }, expect: 'loopback_info_http_403' },
  { id: 'V4-g2-port0', note: '闸②：loopback-info 给 port:0', modes: { loopbackInfo: 'port0' }, expect: 'no_loopback_port' },
  { id: 'V5-g3-unreachable', note: '闸③：loopback-info 给无人监听端口', modes: { loopbackInfo: 'dead_port' }, expect: 'probe_unreachable' },
  { id: 'V6-g3-http500', note: '闸③：探测面回 500', modes: { loopbackInfo: 'probe_proxy', probe: 'http500' }, expect: 'probe_http_500', expectWebkit: 'probe_unreachable' },
  { id: 'V7-g3-deviceid', note: '闸③：探测面 deviceId 不等', modes: { loopbackInfo: 'probe_proxy', probe: 'wrong_id' }, expect: 'device_mismatch', expectWebkit: 'probe_unreachable' },
  { id: 'V8-g3-pna-sim', note: '闸③【模拟】：对端不回 Access-Control-Allow-Private-Network', modes: { loopbackInfo: 'probe_proxy', probe: 'pna_refuse' }, expect: 'probe_', expectWebkit: 'probe_' },
  { id: 'V9-g4-mint403', note: '闸④：铸票 403', modes: { loopbackInfo: 'probe_proxy', mint: 'http403' }, expect: 'mint_http_403', expectWebkit: 'probe_unreachable' },
  { id: 'V10-g4-nourl', note: '闸④：铸票 200 无 consoleUrl', modes: { loopbackInfo: 'probe_proxy', mint: 'no_url' }, expect: 'mint_no_url', expectWebkit: 'probe_unreachable' },
  { id: 'V11-g4-noticket', note: '闸④：铸票 200 但 URL 无 ticket', modes: { loopbackInfo: 'probe_proxy', mint: 'no_ticket' }, expect: 'mint_no_ticket', expectWebkit: 'probe_unreachable' },
];

const CHROMIUM_IDS = ['V0-full', 'V0b-direct', 'V1-g1-404', 'V2-g1-nobound', 'V3-g2-403', 'V4-g2-port0',
  'V5-g3-unreachable', 'V6-g3-http500', 'V7-g3-deviceid', 'V8-g3-pna-sim', 'V9-g4-mint403', 'V10-g4-nourl', 'V11-g4-noticket'];
const WEBKIT_IDS = ['V0-full', 'V0b-direct', 'V1-g1-404', 'V3-g2-403', 'V5-g3-unreachable', 'V7-g3-deviceid', 'V9-g4-mint403'];

const only = arg('--only') ? arg('--only').split(',') : null;
const onlyEngine = arg('--engine');

function pick(ids, extra = []) {
  const all = [...ids, ...extra];
  return VARIANTS.filter((v) => (only ? only.includes(v.id) : all.includes(v.id)));
}

// ── 单次运行 ────────────────────────────────────────────────────────────────
async function runOne(page, plan, variant, rig) {
  const { engineName, axis, origin } = plan;
  // 摆好本次的闸位 + 清读数
  await ctl('/rig/modes', {
    method: 'POST',
    body: { portalStatus: 'ok', loopbackInfo: 'engine', mint: 'ok', probe: 'pass', ...variant.modes },
  });
  await ctl('/rig/reset-probe-log', { method: 'POST' });

  // 每轮换新监听器（同一 page 复用会话；只在 plan 开头兑换一次票——
  // ConsoleTicketStore 的铸票预算 = 10 次/60s 固定窗，逐轮铸票必撞
  // `rate_limited`，那是产品常量不是台子毛病）
  page.removeAllListeners('console');
  page.removeAllListeners('pageerror');
  page.removeAllListeners('requestfailed');
  page.removeAllListeners('response');
  const consoleLines = [];
  const reqFail = [];
  const responses = [];
  page.on('console', (m) => {
    const t = m.text();
    if (/nearpath|loopback|Mixed|CORS|blocked|Access-Control/i.test(t)) consoleLines.push(`[${m.type()}] ${t}`);
  });
  page.on('pageerror', (e) => consoleLines.push(`[pageerror] ${String(e).slice(0, 300)}`));
  page.on('requestfailed', (r) => reqFail.push({ url: r.url().slice(0, 200), err: r.failure()?.errorText || '' }));
  page.on('response', (r) => {
    const u = r.url();
    if (u.includes('/api/health') || u.includes('/api/portal') || u.includes('/api/nf-authcheck')) {
      responses.push({ url: u.slice(0, 200), status: r.status() });
    }
  });

  const logBefore = readEngineLog(rig.cfg, rig.state).length;
  const t0 = Date.now();
  await page.goto(`${origin}/`, { waitUntil: 'domcontentloaded', timeout: 45000 }).catch((e) => consoleLines.push(`[goto] ${String(e).slice(0, 200)}`));

  // 等「跳走」或「miss 键写下」
  let outcome = { kind: 'timeout' };
  const deadline = Date.now() + 35000;
  while (Date.now() < deadline) {
    const url = page.url();
    // 近路命中 = 整页跳到 http://<host>:<port>（host/port 都来自闸③ 探测赢家；
    // 闸③ 走观测代理时 port=6411，走引擎原答时 port=6410）
    if (/^http:\/\/(127\.0\.0\.1|localhost):\d+/.test(url)) {
      outcome = { kind: 'hit', url };
      break;
    }
    try {
      const miss = await page.evaluate((k) => { try { return sessionStorage.getItem(k); } catch { return null; } }, MISS_KEY);
      if (miss) { outcome = { kind: 'miss', missRaw: miss, reason: (JSON.parse(miss) || {}).reason, url }; break; }
    } catch { /* mid-navigation */ }
    await sleep(250);
  }
  const elapsedMs = Date.now() - t0;
  if (outcome.kind === 'timeout') outcome.url = page.url();
  if (outcome.kind === 'hit') {
    await page.waitForLoadState('domcontentloaded').catch(() => {});
    await sleep(1500);
    outcome.url = page.url();
    outcome.hasInput = await page.locator('#input').count().then((c) => c > 0).catch(() => false);
  }

  const safe = (s) => String(s).replace(/[^a-zA-Z0-9._-]+/g, '-').replace(/-+/g, '-');
  const shot = path.join(RED, 'screenshots', `${safe(`${engineName}-${axis}-${variant.id}`)}.png`);
  await page.screenshot({ path: shot, fullPage: false }).catch(() => {});

  let status = null;
  try { status = await ctl('/rig/status'); } catch { /* */ }

  const engineLogNew = readEngineLog(rig.cfg, rig.state).slice(logBefore);
  const nearpathLines = engineLogNew.split('\n').filter((l) => /nearpath|console ticket redeemed|portal http|ws_open/i.test(l)).slice(-25);
  const presenceLines = engineLogNew.split('\n').filter((l) => PRESENCE_RE.test(l));
  rig.state.presenceTotal = (rig.state.presenceTotal || 0) + presenceLines.length;

  const expect = (plan.engineName === 'webkit' && variant.expectWebkit) ? variant.expectWebkit : variant.expect;
  return {
    engine: engineName, axis, origin, variant: variant.id, note: variant.note, expect,
    kind: outcome.kind, reason: outcome.reason || null, missRaw: outcome.missRaw || null,
    finalUrl: outcome.url || null, hasInput: outcome.hasInput ?? null, elapsedMs,
    consoleLines, requestFailed: reqFail.slice(0, 20),
    apiResponses: responses.slice(-25),
    probeLog: status ? status.probeLog : [],
    ingressLog: status ? status.ingressLog.filter((x) => !x.path.startsWith('/rig')).slice(-25) : [],
    engineNearpathLines: nearpathLines,
    presenceLinesDuringRun: presenceLines.length,
    presenceSamples: presenceLines.slice(0, 2),
    screenshot: path.basename(shot),
    expectOk: expect === 'hit'
      ? outcome.kind === 'hit'
      : (outcome.kind === 'miss' && String(outcome.reason || '').startsWith(expect)),
  };
}

// ── 主流程 ─────────────────────────────────────────────────────────────────
const BATCH = `${new Date().toISOString().replace(/[:.]/g, '-')}`;
// 「改前」半边：引擎与静态资源取自**修复提交的父提交** 567d83f16 的干净树。
// （resume 批订正：原台的 /tmp/nearpath-fire/engine-src 事后被覆盖成了修复后代码，
//  直接复用会得到「改前也命中」的假读数；见 red/setup-before.log 的 sha256 对账。）
const rig = await startRig({
  lanIp: process.env.RIG_LAN_IP || '',
  engineSrc: process.env.RIG_ENGINE_SRC || '/tmp/nearpath-fire/engine-src-before',
  classpathFile: process.env.RIG_CLASSPATH || '/tmp/nearpath-fire/classpath-before.txt',
});
const summary = { batch: BATCH, deviceId: rig.deviceId, runs: [], enginePid: rig.state.enginePid };

try {
  const plans = [];
  if (!onlyEngine || onlyEngine === 'chromium') {
    // 主轴 = 生产门户 origin。做法：Chromium 走 rig 转发代理（:7444），mystand.space
    // 的 CONNECT 落到本机 ingress（自签证书 SAN 含 DNS:mystand.space）⇒ 页面 origin
    // 逐字等于 https://mystand.space，且引擎的 CORS 白名单默认就含这个 origin。
    plans.push({
      engineName: 'chromium',
      axis: 'portal-origin(https://mystand.space via rig proxy)',
      origin: rig.origins.portal,
      variants: pick(CHROMIUM_IDS),
      launch: { proxy: { server: rig.origins.proxy, bypass: '' } },
    });
    // 对照轴 = 回环 origin（地址空间 local；生产是 public ⇒ 这一轴专门用来
    // 对照「PNA 是否只在非本机 origin 下才出现」）
    plans.push({
      engineName: 'chromium',
      axis: 'loopback-origin(https://127.0.0.1:7443)',
      origin: rig.origins.local,
      variants: pick(['V0-full', 'V0b-direct', 'V7-g3-deviceid']),
      launch: {},
    });
  }
  if (!onlyEngine || onlyEngine === 'webkit') {
    plans.push({
      engineName: 'webkit',
      axis: 'portal-origin(https://mystand.space via rig proxy)',
      origin: rig.origins.portal,
      variants: pick(WEBKIT_IDS),
      launch: { proxy: { server: rig.origins.proxy } },
    });
    plans.push({
      engineName: 'webkit',
      axis: 'loopback-origin(https://127.0.0.1:7443)',
      origin: rig.origins.local,
      variants: pick(['V0-full']),
      launch: {},
    });
  }

  for (const plan of plans) {
    const launcher = plan.engineName === 'chromium' ? chromium : webkit;
    const browser = await launcher.launch(plan.launch || {});
    // 一票到底：ConsoleTicketStore 铸票预算 = 10 次/60s 固定窗（产品常量），
    // 逐轮铸票必撞 rate_limited ⇒ 每个 plan 只兑换一次票，同 plan 全变体复用
    // 同一会话（sessionStorage 不跨 page 生命周期，每次 goto 都是新页面判定）。
    const { ticket } = await ctl('/rig/ticket');
    if (!ticket) throw new Error('rig minted no ticket (plan ' + plan.engineName + ')');
    const ctx = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1440, height: 900 } });
    // 每个 document 起手清空 sessionStorage：
    //   · 清 `stand_ws_nearpath_at` 的 30s 防摆闸（否则同 origin 连跑第二轮会被
    //     闸门直接短路 = 既无 miss 键也无跳转 ⇒ 台子读不到任何东西）；
    //   · 清上一轮留下的 `stand_ws_nearpath_loopback_miss`（否则读到的是旧结论）。
    // 会话 cookie 不在 sessionStorage 里，清它不影响登录态。
    await ctx.addInitScript(() => { try { sessionStorage.clear(); } catch (_) { /* 内存态即可 */ } });
    const page = await ctx.newPage();
    await page.goto(`${plan.origin}/blank-prepare.html`, { waitUntil: 'commit', timeout: 20000 }).catch(() => { /* 首跳兑换 */ });
    await page.goto(`${plan.origin}/?ticket=${encodeURIComponent(ticket)}`, { waitUntil: 'domcontentloaded', timeout: 45000 });
    await page.waitForSelector('#input', { timeout: 30000 }).catch(() => {});
    console.log(`\n=== ${plan.engineName} @ ${plan.axis} (${plan.variants.length} variants) ===`);
    for (const variant of plan.variants) {
      let r;
      try { r = await runOne(page, plan, variant, rig); }
      catch (e) { r = { engine: plan.engineName, axis: plan.axis, variant: variant.id, kind: 'error', error: String(e).slice(0, 400), expectOk: false }; }
      summary.runs.push(r);
      console.log(`${r.kind === 'hit' ? 'HIT ' : r.kind === 'miss' ? 'MISS' : r.kind.toUpperCase()}  ${variant.id.padEnd(20)} reason=${(r.reason || '-').padEnd(28)} ${r.expectOk ? 'OK' : 'MISMATCH'}  ${r.elapsedMs || '-'}ms`);
    }
    await ctx.close();
    await browser.close();
    fs.writeFileSync(path.join(RED, `results-${plan.engineName}-${plan.axis.replace(/[^a-z0-9]+/gi, '_')}.json`), JSON.stringify(summary.runs.filter((x) => x.engine === plan.engineName && x.axis === plan.axis), null, 2));
  }

  // 引擎侧全量日志（近路相关）
  const full = readEngineLog(rig.cfg, rig.state, 4_000_000);
  fs.writeFileSync(path.join(RED, `engine-${BATCH}.log`), full);
  summary.engineNearpathAll = full.split('\n').filter((l) => /nearpath|console ticket redeemed|origin=relay-mint/i.test(l));
  summary.loopbackInfoServed = (full.match(/nearpath: loopback-info served/g) || []).length;
  summary.ticketsRedeemed = (full.match(/console ticket redeemed/g) || []).length;
  // 「近路判定链零 presence 参与」断言：整批闸链评估窗口内，引擎日志不得出现
  // 任何 presence/P2P 信号（presence sendData / Presence dial failed / connectionsHasKey）。
  // PASS = 环回闸链是纯浏览器 HTTP 面，与 presence/P2P 平面零因果（旁证闸 miss 与
  // 「P2P 连不上」无关；KAI 不可达只影响跨设备取件腿）。
  const presenceAll = full.split('\n').filter((l) => PRESENCE_RE.test(l));
  summary.presenceInGateWindow = presenceAll.length;
  summary.presenceSamples = presenceAll.slice(0, 3);
  summary.zeroPresenceAssertion = presenceAll.length === 0 ? 'PASS' : 'FAIL';
  console.log(`\nzero-presence assertion: ${summary.zeroPresenceAssertion} (presence lines in all gate windows = ${presenceAll.length})`);
  if (presenceAll.length) console.log(presenceAll.slice(0, 5).join('\n'));
} finally {
  summary.finishedAt = new Date().toISOString();
  fs.writeFileSync(path.join(RED, 'summary.json'), JSON.stringify(summary, null, 2));
  await rig.stop();
}

const bad = summary.runs.filter((r) => !r.expectOk).length;
console.log(`\n${summary.runs.length} runs, ${bad} mismatched expectations`);
console.log('engine: loopback-info served =', summary.loopbackInfoServed, '; console tickets redeemed =', summary.ticketsRedeemed);
process.exit(bad === 0 ? 0 : 1);

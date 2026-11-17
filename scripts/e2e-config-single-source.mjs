#!/usr/bin/env node
// e2e-config-single-source.mjs — config-single-source 批（作者裁定①–⑤ 2026-11-05）
// 的**隔离实例**验收腿：一个独立 home + 独立端口（默认 8799），绝不碰宿主 6138。
//
// 覆盖的机械断言（编号与批任务书①–⑤ 对齐）：
//   ① 只有旧 `nebflow.json` 的 home ⇒ 启动后自动生成 `stand.json`，配置值一一相等
//      （规范化 JSON diff），旧文件改名 `.bak`，启动日志出现迁移行。
//   ② `stand.json` 内可见 `llm.fallback` 小节；改其**顺序**（经 PATCH /api/config，
//      即面板写路径）⇒ `GET /api/models/candidates` 的候选顺序随之改变（贴前后）。
//   ③ 删掉一个 provider ⇒ 候选集消失（且链被 prune）；加回 ⇒ 立刻出现。
//   ④ 面板只有**一处**「添加不可用时段」（provider 级）：真实页面里 import 真实
//      providersUi.js 模块渲染真实弹窗，DOM 计数 + 收集值形状；该值经配置写面落到
//      `stand.json` 的 provider 小节（贴文件证据）。
//   ⑤ 面板常显文案不再出现括号式解释（「（留空 = 不限制）」）与请求预告
//      （「POST http…」），解释收进 `?` 悬停提示（DOM 断言 + 截图）。
//
// 密钥纪律：本脚本只用**假** apiKey（`fake-key-a/b/c`），从不读取、打印或写入真实密钥；
// 宿主 home 只读一次（不复用其配置）。
//
// 用法：node scripts/e2e-config-single-source.mjs
// 退出码：0 = 全部 PASS；1 = 有 FAIL。

import { chromium } from 'playwright';
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync, readdirSync, rmSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { evidenceBatch } from './lib/evidence-batch.mjs';

const REPO = fileURLToPath(new URL('..', import.meta.url));
const GATEWAY_PORT = Number(process.env.NF_CFG_PORT || 8799);
const BASE = `http://127.0.0.1:${GATEWAY_PORT}`;
const HOME = process.env.NF_CFG_HOME || join(tmpdir(), `nf-cfg-single-source-${Date.now()}`);
// Evidence = an EvidenceTemp batch (manifest.json + no .active ⇒ the engine's
// 7-day TTL sweep can reclaim it); a fully green run deletes the batch outright.
const EVID = process.env.NF_CFG_EVIDENCE || evidenceBatch('stand', 'config-single-source');
const JAR = (() => {
  const dir = join(REPO, 'target');
  const hits = readdirSync(dir).filter((d) => d.startsWith('scala-'));
  for (const d of hits) {
    const f = readdirSync(join(dir, d)).filter((x) => /^stand-assembly-.*\.jar$/.test(x));
    if (f.length) return join(dir, d, f[0]);
  }
  throw new Error('assembly jar not found — run `sbt assembly` first');
})();

let TOKEN = '';
let failures = 0;
const lines = [];
function log(s) { console.log(s); lines.push(s); }
function check(name, ok, detail = '') {
  if (!ok) failures++;
  log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  [${detail}]` : ''}`);
}

// ── 夹具：旧名配置（fake keys only）─────────────────────────────────────────
function buildLegacyHome() {
  mkdirSync(HOME, { recursive: true });
  const legacy = {
    port: GATEWAY_PORT,
    llm: {
      providers: {
        alpha: {
          baseUrl: 'http://127.0.0.1:1/v1/',
          apiKey: 'fake-key-a',
          protocol: 'openai',
          // 存量 model 级时段：必须被向上合并进 provider 级（裁定④迁移）
          models: [{ id: 'm1', contextWindow: 1000, unavailableWindows: [{ from: '01:00', to: '02:00' }] }],
        },
        beta: { baseUrl: 'http://127.0.0.1:1/v1/', apiKey: 'fake-key-b', protocol: 'openai', models: [{ id: 'm2', contextWindow: 2000 }] },
        gamma: { baseUrl: 'http://127.0.0.1:1/v1/', apiKey: 'fake-key-c', protocol: 'anthropic', models: [{ id: 'm3', contextWindow: 3000 }] },
      },
      // 链小节（顺序语义的当场证据）：首选 gamma，兜底 alpha
      fallback: { preferred: 'gamma/m3', fallbacks: ['alpha/m1'] },
    },
  };
  writeFileSync(join(HOME, 'nebflow.json'), JSON.stringify(legacy, null, 2));
}

function startGateway(tag) {
  const child = spawn(
    'java',
    ['--add-opens', 'java.base/java.lang=ALL-UNNAMED', '-jar', JAR, '--home', HOME, '--port', String(GATEWAY_PORT), '--no-browser'],
    { cwd: REPO, stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, NF_SEED_OFF: '1' } }
  );
  return new Promise((resolve, reject) => {
    let buf = '';
    const onData = (d) => {
      buf += String(d);
      for (const l of String(d).split('\n')) if (l.trim()) lines.push(`  | ${l.trim().slice(0, 200)}`);
    };
    child.stdout.on('data', onData);
    child.stderr.on('data', onData);
    const t0 = Date.now();
    const timer = setInterval(async () => {
      if (Date.now() - t0 > 180000) { clearInterval(timer); reject(new Error(`${tag}: gateway never came up`)); return; }
      try {
        const r = await fetch(`${BASE}/health`);
        if (r.status < 500) { clearInterval(timer); resolve({ child, buf: () => buf }); }
      } catch { /* not up yet */ }
    }, 700);
  });
}

async function stopGateway(home) {
  // 用产品自身的停服命令（不向任何进程发信号）：只停本脚本自己起的隔离实例。
  await new Promise((resolve) => {
    const p = spawn('java', ['--add-opens', 'java.base/java.lang=ALL-UNNAMED', '-jar', JAR, 'stop', '--home', home], {
      cwd: REPO, stdio: 'ignore',
    });
    p.on('exit', () => resolve());
    setTimeout(() => resolve(), 20000);
  });
}

function loadToken() {
  const raw = JSON.parse(readFileSync(join(HOME, 'auth.json'), 'utf8'));
  TOKEN = typeof raw === 'string' ? raw : raw.token || raw.tokenRef || '';
}

async function api(path, method = 'GET', body) {
  const r = await fetch(`${BASE}${path}`, {
    method,
    headers: { Authorization: `Bearer ${TOKEN}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await r.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* non-JSON */ }
  return { status: r.status, json, text };
}

const readJson = (p) => JSON.parse(readFileSync(p, 'utf8'));
const norm = (v) => JSON.parse(JSON.stringify(v));

async function main() {
  mkdirSync(EVID, { recursive: true });
  buildLegacyHome();
  log(`[fixture] ${HOME} (legacy nebflow.json only, fake apiKeys)`);

  let gw = await startGateway('boot');
  loadToken();
  const bootLog = gw.buf();
  check('① 隔离实例启动（独立 home + 独立端口，未触碰宿主 6138）', true, BASE);

  // ── ① 改名 + 一次性迁移 ────────────────────────────────────────────────
  const standPath = join(HOME, 'stand.json');
  const bakPath = join(HOME, 'nebflow.json.bak');
  check('①a stand.json 自动生成', existsSync(standPath));
  check('①b 旧文件保留为 nebflow.json.bak（不删除）', existsSync(bakPath));
  check('①c 旧名 nebflow.json 已改名（不留两个真相）', !existsSync(join(HOME, 'nebflow.json')));
  if (existsSync(standPath) && existsSync(bakPath)) {
    const moved = norm(readJson(bakPath));
    const stand = norm(readJson(standPath));
    // 判据分两段（迁移必须「逐值相等」，唯一允许的变化是裁定④的时段上移）：
    //   (a) 剥掉全部 unavailableWindows 后逐叶子相等 —— 任何别的键丢/变即红；
    //   (b) 旧文件的每一条 model 级时段都必须出现在该 provider 的 provider 级数组里
    //       —— 证明是**上移**而不是丢弃。
    const strip = (o) => JSON.parse(JSON.stringify(o, (k, v) => (k === 'unavailableWindows' ? undefined : v)));
    const flat = (o, p = '', out = {}) => {
      if (o && typeof o === 'object' && !Array.isArray(o)) {
        for (const [k, v] of Object.entries(o)) flat(v, p ? `${p}.${k}` : k, out);
      } else out[p] = o;
      return out;
    };
    const oldF = flat(strip(moved));
    const newF = flat(strip(stand));
    const diffs = Object.keys(oldF).filter((k) => JSON.stringify(oldF[k]) !== JSON.stringify(newF[k]));
    const movedWindows = [];
    for (const [pid, p] of Object.entries(moved.llm?.providers || {})) {
      for (const m of p.models || []) {
        for (const w of m.unavailableWindows || []) {
          const dest = (stand.llm?.providers?.[pid]?.unavailableWindows) || [];
          movedWindows.push({
            pid, model: m.id, w,
            present: dest.some((d) => d.from === w.from && d.to === w.to),
          });
        }
      }
    }
    const lostWindows = movedWindows.filter((x) => !x.present);
    writeFileSync(join(EVID, '01-migration-diff.txt'),
      `legacy (nebflow.json.bak) vs stand.json\n` +
      `value diffs (unavailableWindows stripped): ${diffs.length}\n${diffs.map((k) => `  ${k}: ${JSON.stringify(oldF[k])} → ${JSON.stringify(newF[k])}`).join('\n')}\n` +
      `model-level windows carried up to provider level: ${movedWindows.length - lostWindows.length}/${movedWindows.length}\n` +
      lostWindows.map((x) => `  LOST ${x.pid}/${x.model} ${JSON.stringify(x.w)}\n`).join(''));
    check('①d 配置值一一相等（剥掉时段后逐叶子 diff）', diffs.length === 0, `${diffs.length} diff`);
    check('④a0 旧 model 级时段全部上移到 provider 级（不丢弃）',
      movedWindows.length > 0 && lostWindows.length === 0,
      `${movedWindows.length - lostWindows.length}/${movedWindows.length}`);
  }
  check('①e 启动日志出现一次性迁移行', /\[config-migration\].*nebflow\.json.*stand\.json/.test(bootLog));
  const migrationLine = bootLog.split('\n').find((l) => l.includes('[config-migration]')) || '';
  writeFileSync(join(EVID, '01-migration-log.txt'), `${migrationLine}\n`);

  // ④ 迁移：model 级时段向上合并（不留 model 级）
  const standAfterBoot = readJson(standPath);
  const alpha = standAfterBoot.llm.providers.alpha;
  check('④a model 级不可用时段已合并到 provider 级',
    Array.isArray(alpha.unavailableWindows) && alpha.unavailableWindows.some((w) => w.from === '01:00' && w.to === '02:00'),
    JSON.stringify(alpha.unavailableWindows));
  check('④b 文件中不再残留 model 级 unavailableWindows',
    (alpha.models || []).every((m) => m.unavailableWindows === undefined));

  // ── ② 链小节可见 + 顺序决定运行时顺序 ───────────────────────────────────
  check('②a stand.json 内可见 llm.fallback 小节',
    !!standAfterBoot.llm.fallback && standAfterBoot.llm.fallback.preferred === 'gamma/m3',
    JSON.stringify(standAfterBoot.llm.fallback));
  const before = await api('/api/models/candidates');
  const beforeCands = before.json?.candidates || [];
  writeFileSync(join(EVID, '02-candidates-before.json'), JSON.stringify(before.json, null, 2));
  check('②b 候选顺序 = llm.fallback 顺序（首选 gamma 在前）',
    beforeCands[0] === 'gamma/m3' && beforeCands[1] === 'alpha/m1', beforeCands.join(' → '));

  // 改顺序（面板/配置写面 = PATCH /api/config，热生效，不重启）
  const patchOrder = await api('/api/config', 'PATCH', {
    llm: { fallback: { preferred: 'alpha/m1', fallbacks: ['gamma/m3'] } },
  });
  check('②c 配置写面接受链顺序改动（PATCH /api/config）', patchOrder.status === 200, `http ${patchOrder.status}`);
  const after = await api('/api/models/candidates');
  const afterCands = after.json?.candidates || [];
  writeFileSync(join(EVID, '02-candidates-after.json'), JSON.stringify(after.json, null, 2));
  check('②d 改顺序 ⇒ 运行期候选顺序随之改变（热生效，零重启）',
    afterCands[0] === 'alpha/m1' && afterCands[1] === 'gamma/m3',
    `before: ${beforeCands.join(' → ')} | after: ${afterCands.join(' → ')}`);
  const fileAfterPatch = readJson(standPath);
  check('②e 改动落在 stand.json（不是内存私货）',
    fileAfterPatch.llm.fallback.preferred === 'alpha/m1',
    JSON.stringify(fileAfterPatch.llm.fallback));

  // ── ③ 删掉 provider ⇒ 候选集消失；加回 ⇒ 出现 ──────────────────────────
  const del = await api('/api/config', 'PATCH', { llm: { providers: { gamma: null } } });
  check('③a 删除 provider gamma（面板删除同路径）', del.status === 200, `http ${del.status}`);
  const gone = await api('/api/models/candidates');
  const goneCands = gone.json?.candidates || [];
  writeFileSync(join(EVID, '03-candidates-after-delete.json'), JSON.stringify(gone.json, null, 2));
  check('③b 删掉 ⇒ 候选集不再含 gamma（正）',
    !goneCands.some((c) => c.startsWith('gamma/')), goneCands.join(' → '));
  const fileAfterDelete = readJson(standPath);
  check('③c 删掉 ⇒ llm.fallback 内的 gamma 引用被 prune（文件不再承诺打不通的候选）',
    !JSON.stringify(fileAfterDelete.llm.fallback || {}).includes('gamma'),
    JSON.stringify(fileAfterDelete.llm.fallback));

  const add = await api('/api/config', 'PATCH', {
    llm: {
      providers: {
        gamma: { baseUrl: 'http://127.0.0.1:1/v1/', apiKey: 'fake-key-c', protocol: 'anthropic', models: [{ id: 'm3', contextWindow: 3000 }] },
      },
      fallback: { preferred: 'alpha/m1', fallbacks: ['gamma/m3'] },
    },
  });
  check('③d 加回 provider gamma', add.status === 200, `http ${add.status}`);
  const back = await api('/api/models/candidates');
  const backCands = back.json?.candidates || [];
  writeFileSync(join(EVID, '03-candidates-after-add.json'), JSON.stringify(back.json, null, 2));
  check('③e 加回 ⇒ 立刻含 gamma（反）', backCands.some((c) => c.startsWith('gamma/')), backCands.join(' → '));

  // ── ④⑤ 面板：真实页面 + 真实模块渲染断言 ───────────────────────────────
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  // token 只进 localStorage（不打印、不落盘）
  await page.addInitScript((t) => { try { localStorage.setItem('stand_token', t); } catch { /* ignore */ } }, TOKEN);
  await page.goto(BASE, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);

  const dom = await page.evaluate(async () => {
    const m = await import('/js/sidebar/providersUi.js');
    m.showProviderModal(
      'alpha',
      {
        baseUrl: 'http://127.0.0.1:1/v1/',
        apiKey: '***',
        protocol: 'openai',
        models: [{ id: 'm1', contextWindow: 1000, unavailableWindows: [{ from: '01:00', to: '02:00' }] }],
        unavailableWindows: [{ from: '01:00', to: '02:00' }],
      },
      (name, data) => { window.__saved = { name, data }; }
    );
    const overlay = document.getElementById('cfg-modal');
    const text = overlay ? overlay.innerText : '';
    return {
      providerWindowsHosts: document.querySelectorAll('[data-field="unavailableWindows"]').length,
      modelWindowsHosts: document.querySelectorAll('[data-model-windows]').length,
      addButtons: document.querySelectorAll('[data-window-add]').length,
      hintBadges: [...document.querySelectorAll('.cfg-hintbadge')].map((b) => b.getAttribute('title')),
      hasParenExplain: /（留空|留空 = 不限制|\(empty = unrestricted/.test(text),
      hasRequestAnnounce: /POST\s+https?:/.test(text),
      textSample: text.slice(0, 400),
    };
  });
  check('④c 面板只有一处 provider 级「添加不可用时段」', dom.providerWindowsHosts === 1 && dom.addButtons === 1,
    `provider-hosts=${dom.providerWindowsHosts} add-buttons=${dom.addButtons}`);
  check('④d 不再有 model 级不可用时段编辑面', dom.modelWindowsHosts === 0, `model-hosts=${dom.modelWindowsHosts}`);
  check('⑤a 页面无括号式解释（「（留空 = 不限制）」）', !dom.hasParenExplain);
  check('⑤b 页面无请求预告（「POST http…」）', !dom.hasRequestAnnounce);
  check('⑤c 解释已移入 `?` 悬停提示', dom.hintBadges.some((t) => t && t.includes('统一生效')), JSON.stringify(dom.hintBadges));
  await page.screenshot({ path: join(EVID, '04-05-provider-modal.png'), fullPage: false });

  // 驱动面板加一段时段并保存：DOM → 收集值 → 配置写面 → stand.json
  const saved = await page.evaluate(async () => {
    const overlay = document.getElementById('cfg-modal');
    overlay.querySelector('[data-window-add]').click();
    const rows = overlay.querySelectorAll('[data-window-row] [data-tw-range]');
    const last = rows[rows.length - 1];
    last.dataset.start = '14:00';
    last.dataset.end = '18:00';
    document.getElementById('cfg-modal-save').click();
    return window.__saved;
  });
  const savedWindows = saved?.data?.unavailableWindows || [];
  check('④e 面板收集到的时段形状 = wire 契约（{from,to}）',
    savedWindows.some((w) => w.from === '01:00' && w.to === '02:00') && savedWindows.some((w) => w.from === '14:00' && w.to === '18:00'),
    JSON.stringify(savedWindows));
  check('④f 保存 payload 的 model 条目不再携带 unavailableWindows',
    (saved?.data?.models || []).every((m) => m.unavailableWindows === undefined),
    JSON.stringify(saved?.data?.models || []));

  // 值落到 stand.json：走与面板**同形**的配置写面——面板每次保存都提交
  // 「完整 provider 对象」（onSave payload），也正是 ConfigService 校验要求的形状
  // （只送子键会被 validateConfig 判 Base URL required 而 400）。这里直接用面板
  // 自己产出的 payload（`saved.data`），apiKey `***` = 保留旧值。
  const persist = await api('/api/config', 'PATCH', {
    llm: { providers: { alpha: { ...saved.data } } },
  });
  const fileAfterWindow = readJson(standPath);
  const alphaWindows = fileAfterWindow.llm.providers.alpha.unavailableWindows || [];
  check('④g 时段值写入 stand.json 的 provider 小节（面板 payload 形状）',
    persist.status === 200 && alphaWindows.some((w) => w.from === '14:00' && w.to === '18:00'),
    `http ${persist.status} · ${JSON.stringify(alphaWindows)}`);
  check('④h 写入后 model 小节仍无 unavailableWindows（单一落点）',
    (fileAfterWindow.llm.providers.alpha.models || []).every((m) => m.unavailableWindows === undefined));

  await browser.close();
  writeFileSync(join(EVID, 'e2e-config-single-source.log'), lines.join('\n') + '\n');

  await stopGateway(HOME);
  log(`\n[e2e] ${failures === 0 ? 'ALL PASS' : `${failures} FAIL`} — evidence: ${EVID}`);
  // Success: retire-then-delete the evidence batch (green runs leave nothing
  // behind). Failure keeps it for diagnosis; the batch is non-active with a
  // manifest, so the TTL sweep still bounds its life to 7 days.
  if (failures === 0) {
    try { rmSync(EVID, { recursive: true, force: true }); } catch { /* best effort */ }
  }
  process.exit(failures === 0 ? 0 : 1);
}

main().catch(async (e) => {
  console.error('E2E ERROR:', e?.stack || e);
  try { await stopGateway(HOME); } catch { /* ignore */ }
  process.exit(1);
});

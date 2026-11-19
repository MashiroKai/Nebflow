#!/usr/bin/env node
// shot-self-explaining-cleanup.mjs — 自解释清理批（2026-11-04）改前/改后对照实拍。
//
// 用途（本批验收第四项）：两个改动面 × 双端（1440 桌面 / 375 手机）× 改前/改后。
//   面 A = 全设备统一更新卡「进度中」态 —— 一张图同时覆盖本批三条改动：
//          ① `fleet.blocking`（行为教学 → 状态事实）
//          ② `fleet.manualHow` 徽标（路径指引 → 能力事实徽标）
//          ③ `fleet.subtitle` / `fleet.stages`（保留项，改前后应逐字不变）
//   面 B = 设置页「运行」段的冻结时段说明（`settings.workScheduleOnHint`：
//          删尾句「点击「跳过本次」即可立即解冻」）
//
// 路线 = 本仓统一的零端口打桩（`scripts/lib/visual-harness.mjs`）：page.route 从
// 磁盘服务真实前端（不起端口、结构上碰不到宿主实例 6138）+ MockWS 走真实 ws.js
// 分发（帧经产品代码入卡，不直写 DOM）。
//
// 改前/改后怎么拿到（不改工作树）：`NEBFLOW_WEB_ROOT=<改前 web 副本>` 跑一遍 =
// before，`NEBFLOW_WEB_ROOT` 缺省（= 当前工作树）= after。改前副本用
// `git archive`/`git show` 从基线提交还原，见本批知识文档「证据生成」段。
//
// Run（worktree 根）：
//   node scripts/shot-self-explaining-cleanup.mjs --phase before --web-root /tmp/self-expl-web-before
//   node scripts/shot-self-explaining-cleanup.mjs --phase after
// 产物：/Users/kaiyu/.stand/evidence/self-explaining-cleanup/<phase>/<face>-<width>.png

import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { homedir } from 'node:os';
import { loadPlaywright, launchBrowser, createSession, settle, shoot, ROOT, setWebRoot, webRoot } from './lib/visual-harness.mjs';

const argv = process.argv.slice(2);
const valOf = (f, d = null) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const PHASE = valOf('--phase', 'after');
if (!['before', 'after'].includes(PHASE)) {
  console.error('--phase must be before|after');
  process.exit(2);
}
const WEB_ROOT_ARG = valOf('--web-root');
if (WEB_ROOT_ARG) setWebRoot(WEB_ROOT_ARG);

const EVIDENCE = join(homedir(), '.stand', 'evidence', 'self-explaining-cleanup', PHASE);
mkdirSync(EVIDENCE, { recursive: true });

// ── 帧/桩数据（字段名 = 引擎 FleetUpdateCoordinator#snapshot 逐字，取自
//    scripts/shot-fleet-update-card.mjs 的实跑抓帧）────────────────────────
const TARGET = '2026.12.01';
const RUN = (o) => ({
  deviceId: o.id, deviceName: o.name, isLocal: !!o.isLocal, stage: o.stage, state: o.state,
  progress: o.progress ?? null, detail: o.detail ?? '', attempts: o.attempts ?? 0,
  failure: o.failure ?? null, failureKey: o.failureKey ?? null,
});
const PLAN = {
  targetVersion: TARGET,
  summary: {
    allConsistent: false, manualCount: 1, pendingOnlineCount: 1, pushCount: 1,
    totalDevices: 4, status: 'manual-required', messageKey: 'fleet.summary.manual-required',
  },
  devices: [
    { deviceId: 'dev-a-1', deviceName: 'Mashiro 的 MacBook', platform: 'macos', isLocal: true, online: true,
      version: '2026.12.01', capability: 'version-threshold', capabilityKey: 'fleet.capability.version-threshold',
      remoteUpdatable: true, branch: 'push', reason: null, versionState: 'in-sync' },
    { deviceId: 'dev-b-2', deviceName: 'DESKTOP-IJBBEOT', platform: 'windows', isLocal: false, online: true,
      version: '2026.10.26-beta.2', capability: 'self-reported', capabilityKey: 'fleet.capability.self-reported',
      remoteUpdatable: true, branch: 'push', reason: null, versionState: 'outdated' },
    { deviceId: 'dev-c-3', deviceName: 'NAS（旧版）', platform: 'linux', isLocal: false, online: true,
      version: '2025.08.01', capability: 'version-too-old', capabilityKey: 'fleet.capability.version-too-old',
      remoteUpdatable: false, branch: 'manual', reason: 'version-too-old', versionState: 'outdated' },
    { deviceId: 'dev-d-4', deviceName: 'KAI 的工作站', platform: 'windows', isLocal: false, online: false,
      version: null, capability: 'unknown', capabilityKey: 'fleet.capability.unknown',
      remoteUpdatable: false, branch: 'queued', reason: null, versionState: 'unknown' },
  ],
};
const RUNNING_FRAME = {
  job: {
    // 帧形状 = 引擎 `FleetUpdateCoordinator#snapshot` 逐字：`job.job` 是任务收口元数据，
    // `job.runs/manual/pending` 是行。`jobRunning()` 只认 `job.job.status === 'running'`
    // （fleetUpdate.js:152）—— 少了这一层嵌套，「进度中」的 blocking 行不会渲染。
    job: {
      jobId: 'se-shot-1', status: 'running', statusKey: 'fleet.job.running',
      targetVersion: TARGET, startedAt: 1, finishedAt: 0,
      updatedCount: 0, failedCount: 0, failed: [],
      manualCount: 1, pendingOnlineCount: 1, cancelledCount: 0,
    },
    runs: [
      RUN({ id: 'dev-a-1', name: 'Mashiro 的 MacBook', isLocal: true, stage: 'download', state: 'in-progress',
        progress: 0.42, detail: 'downloading stand-assembly-2026.12.01.jar (51 MB / 121 MB)' }),
      RUN({ id: 'dev-b-2', name: 'DESKTOP-IJBBEOT', stage: 'switch', state: 'in-progress',
        detail: 'executing on the device side' }),
    ],
    manual: [PLAN.devices[2]],
    pending: [PLAN.devices[3]],
  },
  inflight: ['se-shot-1'],
};
const NEBLINK_STATUS = {
  loggedIn: true,
  device: { id: 'dev-a-1', name: 'Mashiro 的 MacBook', platform: 'macos' },
  peers: [
    { deviceId: 'dev-b-2', deviceName: 'DESKTOP-IJBBEOT', platform: 'windows', online: true },
    { deviceId: 'dev-c-3', deviceName: 'NAS（旧版）', platform: 'linux', online: true },
    { deviceId: 'dev-d-4', deviceName: 'KAI 的工作站', platform: 'windows', online: false },
  ],
};

const VIEWPORTS = [
  { tag: '1440', width: 1440, height: 900 },
  { tag: '375', width: 375, height: 812 },
];

const shot = async (page, file) => {
  const buf = await shoot(page, { selector: null, fullPage: false });
  writeFileSync(file, buf);
  console.log(`saved ${file}`);
};

const pw = loadPlaywright();
const { browser } = await launchBrowser(pw.mod || pw, {});
const results = [];

for (const vp of VIEWPORTS) {
  const s = await createSession(browser, {
    width: vp.width, height: vp.height, colorScheme: 'dark',
    apiStubs: {
      '/api/update/fleet': { body: { ok: true, plan: PLAN } },
      '/api/neblink/status': { body: NEBLINK_STATUS },
      '/api/config': { body: {} },
    },
  });
  await s.boot({
    config: {
      llm: { providers: {} },
      features: { friends: false },
      workSchedule: { enabled: true, segments: [{ start: '09:00', end: '18:00' }] },
    },
  });

  // ── 面 B：设置页「运行」段冻结时段说明（先拍：更新卡是阻塞浮层，会盖住设置页）──
  await s.page.evaluate(async () => {
    const { openSettingsPanel } = await import('/js/sidebar.js');
    openSettingsPanel();
  });
  await s.page.waitForSelector('#settings-overlay:not(.hidden)', { timeout: 12000 }).catch(() => {});
  await settle(s.page, 400);
  const hint = await s.page.evaluate(() => {
    const t = document.getElementById('toggle-schedule');
    if (t && !t.classList.contains('on')) t.click();
    const nodes = [...document.querySelectorAll('.cfg-hint')];
    const el = nodes.find((n) => n.textContent.includes('冻结时段内') || /freeze window/i.test(n.textContent));
    if (!el) return { found: false, text: '', visible: false, hits: nodes.map((n) => n.textContent.trim().slice(0, 40)) };
    el.scrollIntoView({ block: 'center' });
    const r = el.getBoundingClientRect();
    return { found: true, text: el.textContent.trim(), visible: r.height > 0 && r.top >= 0 && r.bottom <= innerHeight + 1 };
  });
  results.push([`${vp.tag} freeze hint visible`, hint.found && hint.visible]);
  console.log(`[${vp.tag}] freeze hint (visible=${hint.visible}): ${hint.found ? hint.text : `NOT FOUND (${JSON.stringify(hint.hits)})`}`);
  await settle(s.page, 250);
  await shot(s.page, join(EVIDENCE, `settings-freeze-${vp.tag}.png`));

  // ── 面 A：全设备统一更新卡「进度中」 ──────────────────────────────
  await s.page.evaluate(async () => {
    const m = await import('/js/fleetUpdate.js');
    await m.openFleetUpdatePanel();
  });
  await s.page.evaluate((f) => window.__wsMock.onmessage({
    data: JSON.stringify({ type: 'fleetUpdate', ...f, minRemoteVersion: '2026.10.26', protocol: 1, currentVersion: '2026.10.26-beta.2' }),
  }), RUNNING_FRAME);
  await s.page.waitForSelector('#fleet-update-card', { timeout: 12000 });
  await settle(s.page, 350);
  const probe = await s.page.evaluate(() => {
    const card = document.getElementById('fleet-update-card');
    const text = card ? card.innerText : '';
    return {
      visible: !!card && card.getBoundingClientRect().height > 40,
      text,
      badges: card ? [...card.querySelectorAll('.fleet-badge')].map((n) => n.textContent.trim()) : [],
      blocking: card ? [...card.querySelectorAll('.fleet-blocking')].map((n) => n.textContent.trim()) : [],
      rawKeys: /fleet\.[a-z-]+\./.test(text),
    };
  });
  if (!probe.visible) results.push(['fleet card visible', false]);
  if (probe.rawKeys) results.push(['fleet card has no raw i18n keys', false]);
  results.push([`${vp.tag} fleet card`, probe.visible && !probe.rawKeys]);
  console.log(`[${vp.tag}] fleet blocking: ${probe.blocking.join(' | ')}`);
  console.log(`[${vp.tag}] fleet card badges: ${probe.badges.join(' / ')}`);
  console.log(`[${vp.tag}] fleet card text:\n${probe.text.split('\n').filter(Boolean).join('\n')}`);
  await shot(s.page, join(EVIDENCE, `fleet-running-${vp.tag}.png`));

  if (s.errors.length) results.push([`${vp.tag} zero pageerror: ${s.errors.slice(0, 2).join(' | ')}`, false]);
  await s.close();
  void ROOT; void webRoot;
}

await browser.close();
const failed = results.filter(([, ok]) => !ok);
console.log(`\n[summary] phase=${PHASE} web=${webRoot()} → ${EVIDENCE}`);
console.log(failed.length === 0 ? `ALL PASS (${results.length})` : `${failed.length} FAILED: ${JSON.stringify(failed)}`);
process.exit(failed.length ? 1 : 0);

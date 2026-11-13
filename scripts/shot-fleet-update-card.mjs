#!/usr/bin/env node
// shot-fleet-update-card.mjs — 全设备统一更新（fleet 批 2026-11-01）界面实拍。
//
// 作者令验收面 ③：**控制台设置·设备页** × 深/浅 × 四态
// （更新前 / 进度中 / 部分失败 / 完成）。同一张阻塞式状态卡在四态下的样子，
// 外加设备页的两个入口（「全部更新」+ 每行「更新此设备」）在场证明。
//
// 路线 = 本仓统一的零端口打桩（`scripts/lib/visual-harness.mjs`，样板取自
// shot-bgtask-panel.cjs / shot-header-name.cjs）：
//   · page.route 从磁盘服务真实前端（不起端口、不碰宿主 6138）；
//   · MockWS 走真实 ws.js 分发路径 ⇒ `fleetUpdate` 帧经产品代码入卡（不直写 DOM）；
//   · 计划面走 HTTP 桩 `GET /api/update/fleet`（与真实路由同形：`{plan:{…}}`）。
//
// 帧形状 = 引擎 `FleetUpdateCoordinator#snapshot` 的**逐字产物**（e2e 实跑抓的真帧
// 结构），不是杜撰的漂亮数据：字段名错一个，卡就是空的——所以这里同时做 DOM 断言。
//
// 断言（任一失败 ⇒ exit 1）：
//   A1 四态各渲染出卡（#fleet-update-card 可见）且行数 = 帧里的设备数
//   A2 文案零缺键：卡内文本不含 `fleet.` 字面（i18n 未解析 = 键名裸奔）
//   A3 进度态显式给出**状态事实**（「正在更新」+ 阶段名 + 预计时间）——自解释批
//      2026-11-04 铁律 8 后，原「请稍候 / 本控制台不可用」行为教学已删
//   A4 失败态给出五类之一的**可操作建议**（advice 行在场），且收口四元组齐全
//   A5 完成态收口句 = 「全部完成」+ 计数，不是「已发起」
//   A6 设备页两个入口在场：「全部更新」按钮 + 每行「更新此设备」
//   A7 零 pageerror（卡与设备页都不许抛 JS 异常）
//
// Run（worktree 根）：
//   node scripts/shot-fleet-update-card.mjs
//   NB_EVIDENCE_BATCH=<batch> node scripts/shot-fleet-update-card.mjs   # 旧工作区 evidence 位
// 产物：缺省 = EvidenceTemp 批（manifest + 非活 ⇒ TTL 可回收）；NB_EVIDENCE_BATCH 时 =
//       <主区>/.nebflow/evidence/<batch>/fleet-shots/*.png

import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { loadPlaywright, launchBrowser, createSession, settle, shoot, ROOT } from './lib/visual-harness.mjs';
import { guardWrites } from './lib/writeguard.mjs';
import { evidenceBatch } from './lib/evidence-batch.mjs';

// 本脚本在 worktree 里跑（`.nebflow/worktrees/<name>/scripts/…`），产物必须落在**主区**
// 的 evidence 目录（writeguard 的允许根就是主区 evidence）。ROOT = worktree 根
// （visual-harness 的 `../..`），去掉 worktree 段即主区；不在 worktree 里跑时 ROOT 就是主区。
const MAIN = ROOT.replace(/\/\.nebflow\/worktrees\/[^/]+$/, '');
// Default output = an EvidenceTemp batch (manifest + non-active ⇒ TTL-sweepable).
// Setting NB_EVIDENCE_BATCH keeps the historical workspace-evidence destination
// (<main>/.nebflow/evidence/<batch>/fleet-shots) for runs that share evidence
// with the main checkout.
const OUT = process.env.NB_EVIDENCE_BATCH
  ? join(MAIN, '.nebflow', 'evidence', process.env.NB_EVIDENCE_BATCH, 'fleet-shots')
  : evidenceBatch('fleet', 'update-card');
guardWrites([OUT], { repoRoot: ROOT, label: 'shot-fleet' });
mkdirSync(OUT, { recursive: true });

const TARGET = '2026.12.01';
const LOCAL_V = '2026.12.01';

// ── 计划（GET /api/update/fleet 的 plan 面；字段名 = FleetPlan.toJson 逐字）──
const PLAN = {
  targetVersion: TARGET,
  summary: {
    allConsistent: false, manualCount: 1, pendingOnlineCount: 1, pushCount: 1,
    totalDevices: 4, status: 'manual-required', messageKey: 'fleet.summary.manual-required',
  },
  devices: [
    { deviceId: 'dev-a-1', deviceName: 'Mashiro 的 MacBook', platform: 'macos', isLocal: true, online: true,
      version: LOCAL_V, capability: 'version-threshold', capabilityKey: 'fleet.capability.version-threshold',
      remoteUpdatable: true, branch: 'push', reason: null, versionState: 'in-sync' },
    { deviceId: 'dev-b-2', deviceName: 'DESKTOP-IJBBEOT', platform: 'windows', isLocal: false, online: true,
      version: '2026.10.26-beta.2', capability: 'self-reported', capabilityKey: 'fleet.capability.self-reported',
      remoteUpdatable: true, branch: 'push', reason: null, versionState: 'outdated' },
    { deviceId: 'dev-c-3', deviceName: 'NAS（旧版）', platform: 'linux', isLocal: false, online: true,
      version: '2025.01.01', capability: 'version-too-old', capabilityKey: 'fleet.capability.version-too-old',
      remoteUpdatable: false, branch: 'manual',
      reason: 'version 2025.01.01 is below the cloud hot-update floor 2026.10.26', versionState: 'outdated' },
    { deviceId: 'dev-d-4', deviceName: 'KAI 的工作站', platform: 'windows', isLocal: false, online: false,
      version: '2026.10.26-beta.2', capability: 'self-reported', capabilityKey: 'fleet.capability.self-reported',
      remoteUpdatable: true, branch: 'queued', reason: null, versionState: 'outdated' },
  ],
};

const RUN = (o) => ({
  deviceId: o.id, deviceName: o.name, isLocal: !!o.isLocal,
  stage: o.stage, stageKey: `fleet.stage.${o.stage}`,
  state: o.state, progress: o.progress ?? null, detail: o.detail || '',
  failure: o.failure || null, failureKey: o.failure ? `fleet.failure.${o.failure}` : null,
  retryable: o.retryable ?? false, attempts: o.attempts ?? 1, startedAt: 1, updatedAt: 2,
});

const JOB = (o) => ({
  job: {
    jobId: o.jobId, status: o.status, statusKey: `fleet.job.${o.status}`,
    targetVersion: TARGET, startedAt: 1, finishedAt: o.status === 'running' ? 0 : 3,
    updatedCount: o.updatedCount, failedCount: o.failedCount,
    failed: o.failed || [], manualCount: o.manualCount, pendingOnlineCount: o.pendingOnlineCount,
    cancelledCount: 0,
  },
  runs: o.runs, manual: o.manual || [], pending: o.pending || [],
});

// 四态（帧 = 引擎真产物形状；`plan` 由 HTTP 腿补，卡内 `fleetFrame.plan` 合并）
const STATES = {
  // ① 更新前：没有任务 ⇒ 版本核对表 + 收口句 + 「开始全部更新」
  before: { job: null, inflight: [] },
  // ② 进度中：本机在下载（真百分比只在本机腿有），远端在切换（进度不可见 ⇒ null）
  running: {
    job: JOB({
      jobId: 'fleet-shot-2', status: 'running', updatedCount: 0, failedCount: 0,
      manualCount: 1, pendingOnlineCount: 1,
      runs: [
        RUN({ id: 'dev-a-1', name: 'Mashiro 的 MacBook', isLocal: true, stage: 'download', state: 'in-progress',
          progress: 0.42, detail: 'downloading stand-assembly-2026.12.01.jar (51 MB / 121 MB)' }),
        RUN({ id: 'dev-b-2', name: 'DESKTOP-IJBBEOT', stage: 'switch', state: 'in-progress',
          detail: 'executing on the device side — install progress is not visible from this console' }),
        RUN({ id: 'dev-c-3', name: 'NAS（旧版）', stage: 'manual', state: 'manual',
          detail: 'version 2025.01.01 is below the cloud hot-update floor 2026.10.26' }),
        RUN({ id: 'dev-d-4', name: 'KAI 的工作站', stage: 'waiting-online', state: 'pending',
          detail: 'device is offline — queued; it will be pushed when it comes online' }),
      ],
    }),
    inflight: ['fleet-shot-2'],
  },
  // ③ 部分失败：一台自检没过（可重试）+ 一台已完成 + 一台需手动 + 一台待上线
  partial: {
    job: JOB({
      jobId: 'fleet-shot-3', status: 'partial', updatedCount: 1, failedCount: 1,
      manualCount: 1, pendingOnlineCount: 1,
      failed: [{ deviceId: 'dev-b-2', deviceName: 'DESKTOP-IJBBEOT', failure: 'healthcheck-failed', failureKey: 'fleet.failure.healthcheck-failed' }],
      runs: [
        RUN({ id: 'dev-a-1', name: 'Mashiro 的 MacBook', isLocal: true, stage: 'done', state: 'completed',
          progress: 1.0, detail: 'local instance came back on 2026.12.01 — health check passed' }),
        RUN({ id: 'dev-b-2', name: 'DESKTOP-IJBBEOT', stage: 'failed', state: 'failed', failure: 'healthcheck-failed',
          retryable: true, attempts: 1,
          detail: 'the device came back but still reports a different version (install did not take effect / rolled back)' }),
        RUN({ id: 'dev-c-3', name: 'NAS（旧版）', stage: 'manual', state: 'manual',
          detail: 'version 2025.01.01 is below the cloud hot-update floor 2026.10.26' }),
        RUN({ id: 'dev-d-4', name: 'KAI 的工作站', stage: 'waiting-online', state: 'pending',
          detail: 'device is offline — queued; it will be pushed when it comes online' }),
      ],
      manual: [PLAN.devices[2]],
      pending: [PLAN.devices[3]],
    }),
    inflight: [],
  },
  // ④ 完成：全部一致
  done: {
    job: JOB({
      jobId: 'fleet-shot-4', status: 'completed', updatedCount: 3, failedCount: 0,
      manualCount: 0, pendingOnlineCount: 0,
      runs: [
        RUN({ id: 'dev-a-1', name: 'Mashiro 的 MacBook', isLocal: true, stage: 'done', state: 'completed',
          progress: 1.0, detail: 'local instance came back on 2026.12.01 — health check passed' }),
        RUN({ id: 'dev-b-2', name: 'DESKTOP-IJBBEOT', stage: 'done', state: 'completed',
          progress: 1.0, detail: 'device is back on 2026.12.01' }),
        RUN({ id: 'dev-d-4', name: 'KAI 的工作站', stage: 'done', state: 'completed',
          progress: 1.0, detail: 'device is back on 2026.12.01' }),
      ],
    }),
    inflight: [],
  },
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

const results = [];
const ok = (name, cond, extra = '') => {
  results.push([name, !!cond]);
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
};

const pw = loadPlaywright();
const { browser } = await launchBrowser(pw.mod || pw, {});

for (const scheme of ['dark', 'light']) {
  const s = await createSession(browser, {
    width: 1440, height: 900, colorScheme: scheme,
    apiStubs: {
      '/api/update/fleet': { body: { ok: true, plan: PLAN } },
      '/api/neblink/status': { body: NEBLINK_STATUS },
      '/api/config': { body: {} },
    },
  });
  await s.boot({ config: { llm: { providers: {} }, features: { friends: false } } });

  // ① 设备页：两个入口在场（「全部更新」+ 每行「更新此设备」）
  await s.page.evaluate(async () => {
    const { openSettingsPanel } = await import('/js/sidebar.js');
    openSettingsPanel();
  });
  await s.page.waitForSelector('#settings-devices-slot [data-fleet-open]', { timeout: 15000 });
  await settle(s.page);
  const entry = await s.page.evaluate(() => {
    const slot = document.getElementById('settings-devices-slot');
    return {
      open: !![...slot.querySelectorAll('[data-fleet-open]')].length,
      perRow: [...slot.querySelectorAll('[data-settings-device-update]')].length,
      rows: [...slot.querySelectorAll('[data-settings-device-row]')].length,
      label: (slot.querySelector('[data-fleet-open]') || {}).textContent || '',
    };
  });
  ok(`[${scheme}] A6 设备页入口：「全部更新」+ 每行「更新此设备」`,
    entry.open && entry.perRow === 3 && entry.rows === 4,
    `open=${entry.open} perRow=${entry.perRow} rows=${entry.rows} label=${entry.label.trim()}`);
  const devShot = join(OUT, `20261102_fleet-devices-${scheme}.png`);
  await s.page.screenshot({ path: devShot, animations: 'disabled' });
  console.log(`saved ${devShot}`);

  // ② 四态实拍（帧走真实 ws.js 分发；计划走 HTTP 桩）
  for (const [name, frame] of Object.entries(STATES)) {
    await s.page.evaluate(async (f) => {
      const m = await import('/js/fleetUpdate.js');
      await m.openFleetUpdatePanel();
    }, frame);
    await s.page.evaluate(([f, v]) => window.__wsMock.onmessage({ data: JSON.stringify({ type: 'fleetUpdate', ...f, minRemoteVersion: '2026.10.26', protocol: 1, currentVersion: v }) }), [frame, LOCAL_V]);
    await s.page.waitForSelector('#fleet-update-card', { timeout: 10000 });
    await settle(s.page, 300);

    // 设备行 = `[data-fleet-row]`（任务面）+ `[data-fleet-plan-row]`（计划面，无 job 时）。
    // 刻意不用 `.fleet-row`：那个类名也用在「需手动 / 待上线」两个子清单上，是同一台
    // 设备的第二视图，数进来会把 4 台数成 6 行。
    const probeNow = () => s.page.evaluate(() => {
      const card = document.getElementById('fleet-update-card');
      const text = card ? card.innerText : '';
      return {
        visible: !!card && card.getBoundingClientRect().height > 40,
        text,
        rows: card ? card.querySelectorAll('[data-fleet-row],[data-fleet-plan-row]').length : 0,
        subs: card ? card.querySelectorAll('.fleet-sub').length : 0,
        blocking: card ? card.querySelectorAll('.fleet-blocking').length : 0,
        detail: card ? [...card.querySelectorAll('.fleet-row-detail')].map((n) => n.textContent).join(' | ') : '',
        badges: card ? [...card.querySelectorAll('.fleet-badge')].map((n) => n.textContent).join(',') : '',
        buttons: card ? [...card.querySelectorAll('.fleet-btn')].map((n) => n.textContent.trim()).join(',') : '',
      };
    });
    let probe = await probeNow();
    const hasRawKey = () => /fleet\.[a-z-]+\./.test(probe.text);
    // 期望行数 = 帧里的设备行数（有 job ⇒ runs；无 job ⇒ 计划表）；不是恒等于 4：
    // 「完成」态的 runs 只有三台（老版本那台不在本轮里）。
    const expectRows = frame.job ? frame.job.runs.length : PLAN.devices.length;
    ok(`[${scheme}/${name}] A1 卡在场且行数 = 帧内设备数`,
      probe.visible && probe.rows === expectRows, `rows=${probe.rows} expect=${expectRows}`);
    ok(`[${scheme}/${name}] A2 文案零缺键（无 fleet.* 裸字面）`, !hasRawKey(), hasRawKey() ? probe.text.slice(0, 120) : '');

    if (name === 'partial') {
      // 失败行的 detail/advice 只在**展开**后出现（作者令「每台一行可展开」）：
      // 先真点一次失败行（走产品代码的点击委托），再取文案 —— 证明展开是真的能用。
      await s.page.click('[data-fleet-row="dev-b-2"]');
      await settle(s.page, 200);
      probe = await probeNow();
    }

    if (name === 'running') {
      // A3 判据随自解释批（2026-11-04 铁律 8）改口：阻塞面的保留项是**状态事实**
      // （阶段名 + 预计时间），原「正在更新，请稍候……更新期间本控制台不可用」是行为教学，
      // 已删（见 locales 同键注释与 `docs/<本批>` 清单）。断言锁事实，不锁教学句。
      ok(`[${scheme}/running] A3 阻塞提示（阶段事实）+ 预计时间 + 中断键`,
        probe.text.includes('正在更新') && probe.text.includes('预计 3–5 分钟') && probe.buttons.includes('中断'),
        probe.buttons);
    }
    if (name === 'partial') {
      const adviceOk = probe.detail.includes('建议') && /1 台已更新、1 台失败、1 台需手动、1 台待上线/.test(probe.text);
      ok(`[${scheme}/partial] A4 展开失败行后给可操作建议 + 收口四元组 + 可重试`,
        adviceOk && probe.buttons.includes('重试失败项'),
        `detail=${probe.detail.slice(0, 60)} buttons=${probe.buttons}`);
    }
    if (name === 'done') {
      ok(`[${scheme}/done] A5 收口句是结果不是「已发起」`,
        probe.text.includes('全部完成') && /3 台已更新、0 台失败、0 台需手动、0 台待上线/.test(probe.text),
        probe.text.split('\n').slice(-2).join(' / '));
    }
    if (name === 'before') {
      ok(`[${scheme}/before] 版本核对表带在线/能力/分支徽章 + 收口句`,
        probe.badges.includes('在线') && probe.badges.includes('需手动') && probe.text.includes('有待手动更新的设备'),
        probe.badges);
    }

    const shot = join(OUT, `20261102_fleet-card-${name}-${scheme}.png`);
    await shoot(s.page, { selector: '#fleet-update-card' }).then((buf) => import('node:fs').then((fs) => fs.writeFileSync(shot, buf)));
    console.log(`saved ${shot}`);
  }

  ok(`[${scheme}] A7 零 pageerror`, s.errors.length === 0, s.errors.slice(0, 2).join(' | '));
  await s.close();
}

await browser.close();
const failed = results.filter(([, v]) => !v).length;
console.log(`\n[summary] ${failed === 0 ? `ALL PASS (${results.length})` : `${failed} FAILED / ${results.length}`}  → ${OUT}`);
process.exit(failed === 0 ? 0 : 1);

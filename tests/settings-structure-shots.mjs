#!/usr/bin/env node
// settings-structure-shots.mjs — 设置页结构治理批（2026-11-18）证据实拍 + 读数。
//
// 同一支脚本跑两棵树（判红纪律「改前 / 改后」对照）：
//   · SHOTS_WEB_ROOT 未设 ⇒ 跑本仓 src/main/resources/web（改后树）；
//   · SHOTS_WEB_ROOT=<改前树 web/> ⇒ 拍改前帧（缺件不抛错，逐场景 try/catch，
//     缺的正是本批新增/改动的面）。
//
// 场景（桌面 1280×900 弹窗形态 / 手机 390×844 **全屏页**形态）：
//   S1 高级区（分区头 vs 卡内标题 + 按钮排布）
//   S2 关于区（分区头 vs 卡内标题）
//   S3 设备行退出按钮（桌面资料面 #settings-profile-overlay / 手机 me 页）
//   S4 模型配置（默认「全部跟随主会话」/「分开配置」两模式）
//   S5 后台智能体恢复按钮（手机 ⋯ 详情动作区 / 桌面页脚管理条）
//
// 用法：
//   node tests/settings-structure-shots.mjs                                   # 改后
//   SHOTS_WEB_ROOT=/tmp/settings-before/src/main/resources/web \
//   SHOTS_OUT=<abs>/evidence/20261118_settings-structure/before \
//   node tests/settings-structure-shots.mjs                                   # 改前

import { mkdirSync, writeFileSync } from 'node:fs';
import { join, isAbsolute } from 'node:path';
import { ROOT, loadPlaywright, launchBrowser, createSession, settle, setWebRoot } from '../scripts/lib/visual-harness.mjs';

const WEB = process.env.SHOTS_WEB_ROOT || join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = process.env.SHOTS_OUT || join(ROOT, 'evidence', '20261118_settings-structure', 'after');
const TAG = process.env.SHOTS_TAG || (process.env.SHOTS_WEB_ROOT ? 'before' : 'after');
setWebRoot(isAbsolute(WEB) ? WEB : join(ROOT, WEB));
mkdirSync(OUT, { recursive: true });

// 钉时钟（时长/新建时间读数恒定）。
const CLOCK = 1794976000000;
const UPTIME_MS = 7 * 60 * 1000;

const ROOT_SID = 'settings-root';
const BG_SID = 'delegate-settings-1';
const BG_NAME = '设置治理巡检';
const BG_TASK = '【设置页结构治理 · 恢复入口复测】';

const SELF = {
  id: 'dev-self', name: 'Mashiro-Mac', platform: 'macos',
  displayName: '测试用户', email: 'me@example.com',
};
const PEERS = [
  { deviceId: 'dev-kai', deviceName: 'KAI', platform: 'windows', online: true, directOnline: true },
];

const CONFIG = {
  features: { friends: true },
  llm: {
    providers: {
      cmdcode: { models: [{ id: 'deepseek-v4-flash' }, { id: 'gpt-6.1-terra' }] },
      spider: { models: [{ id: 'zeus-1' }] },
    },
  },
  imagegen: { providers: {} },
};

/** 角色模型读数：Nebula 主链 = cmdcode/deepseek-v4-flash；四个 follower 默认跟随。 */
const roleModel = (role) => {
  const explicit = ['kernel', 'project-dispatcher', 'node', 'subagent'].includes(role) && role !== 'kernel';
  const chain = explicit ? { preferred: 'spider/zeus-1', fallbacks: [] } : null;
  return {
    mode: explicit ? 'explicit' : 'follow',
    chain,
    effectiveChain: explicit ? chain : { preferred: 'cmdcode/deepseek-v4-flash', fallbacks: [] },
    resolvedFrom: explicit ? 'own-chain' : 'nebula-chain',
    current: 'cmdcode/deepseek-v4-flash',
    settable: true,
  };
};

const DELEGATE_DETAIL = {
  record: {
    id: BG_SID, executor: 'claude', cwd: '/Users/kaiyu/worktrees/settings',
    project: 'stand', taskId: '7', parentSessionId: ROOT_SID,
    title: BG_TASK, createdAt: CLOCK, name: BG_NAME,
    branch: 'fix/settings-structure', worktree: '/Users/kaiyu/worktrees/settings',
    plugins: ['wiki-docs'],
  },
  live: { status: 'frozen' },
  verdict: null,
  task: null,
  childrenCount: 0,
};

const API_STUBS = {
  '/api/neblink/status': { body: { loggedIn: true, device: SELF, peers: PEERS } },
  '/api/users/search': {
    body: {
      found: true,
      user: { userId: 'u-self', username: 'Mashiro', display_name: '测试用户', avatar: null },
      relation_status: 'self',
    },
  },
  '/api/delegates': { body: DELEGATE_DETAIL },
};

/** 逐角色模型桩（最长前缀命中不适用 ⇒ 走 route 前的自定义补丁，见 patchRoleModels）。 */
async function patchRoleModels(session) {
  await session.page.route('**/api/agents/*/model', (route) => {
    const m = decodeURIComponent(new URL(route.request().url()).pathname)
      .match(/^\/api\/agents\/([^/]+)\/model$/);
    if (!m) return route.fallback();
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify(roleModel(decodeURIComponent(m[1]))),
    });
  });
}

const MEASURE = () => {
  const q = (s, root = document) => root.querySelector(s);
  const rect = (el) => {
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { x: +r.x.toFixed(1), y: +r.y.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) };
  };
  const face = (el) => {
    if (!el) return null;
    const cs = getComputedStyle(el);
    return {
      rect: rect(el), cls: el.className,
      h: cs.height, radius: cs.borderRadius, pad: cs.padding,
      font: cs.fontSize, bg: cs.backgroundColor, border: cs.border,
      color: cs.color, display: cs.display, minH: cs.minHeight,
    };
  };
  const content = q('#settings-content');
  const groups = content
    ? [...content.children].filter((el) => el.classList.contains('sp-panel-group')).map((g) => {
      const h2 = g.querySelector(':scope > h2');
      const cards = [...g.querySelectorAll(':scope > .set-card')].map((c) => ({
        cardTitle: q(':scope > .set-card-title', c)?.textContent?.trim() ?? null,
        id: c.id || null,
      }));
      return { group: g.dataset.panelGroup, sectionTitle: h2?.textContent?.trim() ?? null, cards };
    })
    : [];
  // 栏目名重复判据：分区头文本 === 卡内标题文本。
  const dupes = groups.filter((g) => g.cards.some((c) => c.cardTitle && c.cardTitle === g.sectionTitle))
    .map((g) => ({ group: g.group, text: g.sectionTitle }));
  const adv = q('[data-panel-group="advanced"]');
  const advBtns = adv ? [...adv.querySelectorAll('button')].map((b) => ({
    text: (b.textContent || '').trim(), cls: b.className, id: b.id || null, face: face(b),
  })) : [];
  const signout = q('.pd-device-signout');
  const retry = q('.fa-mgmt-btn.fa-mgmt-retry') || q('.bgagent-sheet-retry');
  const chainBody = q('#model-chain-section-body');
  const modes = q('#model-chain-modes') || q('.model-panel .mp-modes');
  const modelPanel = q('.model-panel');
  return {
    mobileLayer: document.body.classList.contains('mobile-layer'),
    fullscreenPage: !!q('.mobile-page[data-page="settings"] #settings-content'),
    groups, dupes,
    advanced: { buttons: advBtns },
    about: (() => {
      const g = q('[data-panel-group="about"]');
      return g ? { text: g.textContent.replace(/\s+/g, ' ').trim().slice(0, 90) } : null;
    })(),
    signout: face(signout),
    retry: face(retry),
    retryParent: retry ? (retry.parentElement?.className || null) : null,
    retryVisible: retry ? retry.getClientRects().length > 0 : false,
    modelModes: modes ? {
      text: modes.textContent.replace(/\s+/g, ' ').trim(),
      buttons: [...modes.querySelectorAll('button')].map((b) => ({
        text: b.textContent.trim(), mode: b.dataset.mode || null,
        on: b.getAttribute('aria-pressed'), face: face(b),
      })),
    } : null,
    modelRoleRows: chainBody ? chainBody.querySelectorAll('.mp-settings-role-panel').length : 0,
    modelRoleSelects: chainBody ? chainBody.querySelectorAll('select').length : 0,
    followNote: q('#model-chain-follow-note')?.textContent?.trim() ?? null,
    settingsHint: q('#model-chain-hint')?.textContent?.trim() ?? null,
    // /model 面板头是否被更长的模式标签挤破（两模式控件改名后的回归读数）。
    modelHeader: modelPanel ? (() => {
      const h = modelPanel.querySelector('.mp-header');
      const modes = modelPanel.querySelector('.mp-modes');
      return h ? {
        header: rect(h), modes: rect(modes),
        overflow: h.scrollWidth > h.clientWidth + 1,
        modesScroll: modes ? modes.scrollWidth : 0,
        modesClient: modes ? modes.clientWidth : 0,
      } : null;
    })() : null,
  };
};

/** 采集：整屏 + 指定场景裁剪 + 读数。 */
async function shot(session, scene, file, opts = {}) {
  const path = join(OUT, file);
  try {
    if (opts.selector) {
      const el = session.page.locator(opts.selector).first();
      await el.scrollIntoViewIfNeeded().catch(() => {});
      await settle(session.page, 120);
      await el.screenshot({ path, animations: 'disabled', caret: 'hide' });
    } else {
      await session.page.screenshot({ path, animations: 'disabled', caret: 'hide', fullPage: !!opts.fullPage });
    }
    shots.push({ scene, file: path, tag: TAG });
    return true;
  } catch (e) {
    shots.push({ scene, file: path, tag: TAG, absent: String((e && e.message) || e).split('\n')[0] });
    return false;
  }
}

const shots = [];
const runs = [];
const pageErrors = [];
/** 模式切换帧序列（GIF/MP4 素材；每帧一文件，ffmpeg 装配）。 */
const modeFrames = [];

async function build(config = CONFIG) {
  const pwRes = loadPlaywright();
  const pw = pwRes.mod.playwright || pwRes.mod;
  const { browser, label: browserLabel } = await launchBrowser(pw);
  return { pw, browser, browserLabel };
}

async function openDesktopSettings(browser, theme) {
  const s = await createSession(browser, {
    width: 1280, height: 900, colorScheme: theme, clock: CLOCK, apiStubs: API_STUBS,
  });
  await patchRoleModels(s);
  await s.boot({ config: CONFIG, sessionId: ROOT_SID });
  await s.page.locator('#settings-btn').click();
  await s.page.waitForSelector('#settings-content .sp-panel-group', { timeout: 15000 });
  await settle(s.page, 300);
  return s;
}

async function openMobileSettings(browser, theme) {
  const s = await createSession(browser, {
    width: 390, height: 844, colorScheme: theme, clock: CLOCK, apiStubs: API_STUBS,
  });
  await patchRoleModels(s);
  await s.boot({ config: CONFIG, sessionId: ROOT_SID });
  await s.page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await s.page.waitForTimeout(300);
  const row = s.page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="settings"]');
  await row.click();
  await s.page.waitForSelector('.mobile-page[data-page="settings"] #settings-content .sp-panel-group', { timeout: 15000 });
  await settle(s.page, 350);
  return s;
}

async function expandModels(s) {
  const disc = s.page.locator('#model-chain-section [data-settings-disclosure="models"]');
  if (await disc.count()) {
    await disc.click().catch(() => {});
    await s.page.waitForTimeout(700);
  }
}

/** 设备行退出按钮所在的**唯一**两个消费面：桌面个人资料面 / 手机 me 页。 */
async function openDeviceSurfaceMobile(s) {
  await s.page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await s.page.waitForTimeout(250);
  await s.page.locator('.mobile-page[data-page="workbench"] .mf-me-entry').click();
  await s.page.waitForSelector('.mobile-page[data-page="me"] .pd-devices', { timeout: 8000 });
  await settle(s.page, 250);
}

async function openDeviceSurfaceDesktop(s) {
  await s.page.evaluate(async () => {
    const m = await import('/js/settingsIdentity.js');
    m.openProfileFace();
  });
  await s.page.waitForSelector('#settings-profile-overlay:not([hidden]) .pd-devices', { timeout: 8000 });
  await settle(s.page, 250);
}

/** 后台智能体弹窗（恢复入口唯一消费面）：真模块入口 + 冻结快照。 */
async function openBgAgent(s, mobile) {
  await s.page.evaluate(([sid, msgs]) => {
    window.__histReplies = 0;
    setInterval(() => {
      const sent = window.__wsSent || [];
      const pend = sent.filter((f) => f.type === 'getHistory' && f.sessionId === sid);
      if (pend.length > window.__histReplies) {
        window.__histReplies = pend.length;
        window.__wsMock.onmessage({ data: JSON.stringify({
          type: 'historyPage', sessionId: sid, messages: msgs, total: msgs.length, offset: 0, hasMore: false,
        }) });
      }
    }, 40);
  }, [BG_SID, [{ type: 'user', injected: true, source: 'delegate', eventType: 'dispatch', sender: 'Nebula',
    header: 'DELEGATE · TASK', timestamp: CLOCK, text: BG_TASK, attachments: [] }]]);
  await s.frame({
    type: 'activeAgents',
    agents: [{
      rootSessionId: ROOT_SID, sessionId: BG_SID, agentId: BG_SID,
      name: BG_NAME, task: BG_TASK, kind: 'Delegate',
      // 快照行 status 是后端枚举字面量 'Frozen'（小写 'frozen' 不命中映射）；
      // frozenKind = FreezeDisplay 的 'needs-human' ⇒ showsRecoveryAction(manual)
      // ⇒ 恢复按钮可达（冻结态误报批的呈现分类表）。
      status: 'Frozen', startedAt: CLOCK - UPTIME_MS, retryCount: 2,
      frozenKind: 'needs-human', needsHuman: true, freezeReasonWire: 'loop-detected',
    }],
  });
  await settle(s.page, 150);
  await s.page.evaluate(async ([sid, name, task]) => {
    const m = await import('/js/bgAgentPopup.js');
    m.openStepPopup(sid, name, task);
  }, [BG_SID, BG_NAME, BG_TASK]);
  await s.page.waitForTimeout(700);
  await settle(s.page, 200);
  await s.page.addStyleTag({ content: '.flow-agent-overlay .fa-status-dot{animation:none !important}' });
  if (mobile) {
    const more = s.page.locator('.bgagent-overlay .bgagent-more');
    if (await more.count()) { await more.click().catch(() => {}); await s.page.waitForTimeout(300); }
  }
}

/** /model 面板（两模式控件的另一消费面）。 */
async function openModelPanel(s) {
  await s.page.evaluate(async () => {
    const m = await import('/js/modelPanel.js');
    await m.openModelPanel();
  });
  await s.page.waitForSelector('.model-panel .mp-modes', { timeout: 8000 });
  await settle(s.page, 250);
}

async function main() {
  const { browser, browserLabel } = await build();
  console.log(`[harness] browser=${browserLabel} web=${WEB} out=${OUT} tag=${TAG}`);

  for (const theme of ['light', 'dark']) {
    // ── 桌面 ──────────────────────────────────────────────────────────
    {
      const s = await openDesktopSettings(browser, theme);
      pageErrors.push(...s.errors.map((e) => `desktop/${theme}: ${e}`));
      await expandModels(s);
      const m = await s.page.evaluate(MEASURE);
      await shot(s, 'advanced', `d-${theme}-s1-advanced.png`, { selector: '[data-panel-group="advanced"]' });
      await shot(s, 'about', `d-${theme}-s2-about.png`, { selector: '[data-panel-group="about"]' });
      await shot(s, 'model', `d-${theme}-s4-model-default.png`, { selector: '#model-chain-section' });
      await shot(s, 'full', `d-${theme}-full.png`);
      // 分开配置模式（模式控件在场时）
      const sep = s.page.locator('#model-chain-modes button[data-mode="separate"]');
      if (await sep.count()) {
        await sep.click();
        await s.page.waitForTimeout(500);
        await shot(s, 'model-separate', `d-${theme}-s4-model-separate.png`, { selector: '#model-chain-section' });
        await s.page.locator('#model-chain-modes button[data-mode="unified"]').click();
        await s.page.waitForTimeout(400);
      }
      // 设备行退出按钮：桌面个人资料面
      await openDeviceSurfaceDesktop(s);
      const signoutMeasure = await s.page.evaluate(MEASURE);
      m.signoutInProfile = signoutMeasure.signout;
      await shot(s, 'device-exit', `d-${theme}-s3-device-exit.png`, { selector: '#settings-profile-overlay' });
      // 后台智能体恢复按钮：桌面页脚管理条
      await s.page.evaluate(async () => {
        const m = await import('/js/settingsIdentity.js');
        m.closeProfileFace();
      });
      await openBgAgent(s, false);
      const bgMeasure = await s.page.evaluate(MEASURE);
      m.retryInBg = bgMeasure.retry;
      m.retryVisibleInBg = bgMeasure.retryVisible;
      await shot(s, 'bgagent', `d-${theme}-s5-bgagent-retry.png`, { selector: '.flow-agent-overlay' });
      // /model 面板头（两模式控件另一面 + 更长标签的溢出读数）
      await s.page.keyboard.press('Escape');
      await s.page.waitForTimeout(200);
      await openModelPanel(s);
      const panelMeasure = await s.page.evaluate(MEASURE);
      m.modelHeader = panelMeasure.modelHeader;
      m.modelModesInPanel = panelMeasure.modelModes;
      await shot(s, 'model-panel', `d-${theme}-s6-model-panel.png`, { selector: '.model-panel' });
      runs.push({ viewport: 'desktop', theme, measure: m });
      await s.close();
    }

    // ── 手机（全屏页）──────────────────────────────────────────────────
    {
      const s = await openMobileSettings(browser, theme);
      pageErrors.push(...s.errors.map((e) => `mobile/${theme}: ${e}`));
      const m0 = await s.page.evaluate(MEASURE);
      await shot(s, 'advanced', `m-${theme}-s1-advanced.png`, { selector: '.mobile-page[data-page="settings"] [data-panel-group="advanced"]' });
      await shot(s, 'about', `m-${theme}-s2-about.png`, { selector: '.mobile-page[data-page="settings"] [data-panel-group="about"]' });
      await expandModels(s);
      const m = await s.page.evaluate(MEASURE);
      m.fullscreenPage = m0.fullscreenPage;
      await shot(s, 'model', `m-${theme}-s4-model-default.png`, { selector: '.mobile-page[data-page="settings"] #model-chain-section' });
      // 模式切换帧序列（≤10MB GIF/MP4 素材：真实点击 + 每拍一帧）
      const sep = s.page.locator('#model-chain-modes button[data-mode="separate"]');
      if (await sep.count()) {
        modeFrames.length = 0;
        const clip = await s.page.evaluate(() => {
          const el = document.getElementById('model-chain-section');
          const r = el.getBoundingClientRect();
          return { x: Math.max(0, r.x - 4), y: Math.max(0, r.y - 4), width: Math.min(r.width + 8, window.innerWidth), height: Math.min(r.height + 8, window.innerHeight) };
        });
        for (let i = 0; i < 6; i++) {
          modeFrames.push({ phase: 'unified', i, clip });
          await s.page.screenshot({ path: join(OUT, `mode-${theme}-f${String(modeFrames.length - 1).padStart(2, '0')}.png`), clip, animations: 'disabled', caret: 'hide' }).catch(() => {});
        }
        await sep.click();
        for (let i = 0; i < 8; i++) {
          await s.page.waitForTimeout(120);
          modeFrames.push({ phase: 'separate', i, clip });
          await s.page.screenshot({ path: join(OUT, `mode-${theme}-f${String(modeFrames.length - 1).padStart(2, '0')}.png`), clip, animations: 'disabled', caret: 'hide' }).catch(() => {});
        }
        await s.page.waitForTimeout(400);
        const mSep = await s.page.evaluate(MEASURE);
        m.modesAfterSeparate = mSep.modelModes;
        m.roleRowsAfterSeparate = mSep.modelRoleRows;
        await shot(s, 'model-separate', `m-${theme}-s4-model-separate.png`, { selector: '.mobile-page[data-page="settings"] #model-chain-section' });
        await s.page.locator('#model-chain-modes button[data-mode="unified"]').click();
        await s.page.waitForTimeout(400);
        const mUni = await s.page.evaluate(MEASURE);
        m.roleRowsAfterUnified = mUni.modelRoleRows;
        await shot(s, 'model-back-unified', `m-${theme}-s4-model-back.png`, { selector: '.mobile-page[data-page="settings"] #model-chain-section' });
      }
      await shot(s, 'full', `m-${theme}-full-settings.png`);
      // 设备行退出按钮：手机 me 页（作者实拍的那一屏）
      await openDeviceSurfaceMobile(s);
      const devMeasure = await s.page.evaluate(MEASURE);
      m.signoutInMe = devMeasure.signout;
      await shot(s, 'device-exit', `m-${theme}-s3-device-exit.png`, { selector: '.mobile-page[data-page="me"]' });
      // 后台智能体恢复按钮：手机 ⋯ 详情动作区
      await openBgAgent(s, true);
      const bgMeasure = await s.page.evaluate(MEASURE);
      m.retryInBg = bgMeasure.retry;
      m.retryVisibleInBg = bgMeasure.retryVisible;
      await shot(s, 'bgagent', `m-${theme}-s5-bgagent-retry.png`, { selector: '.bgagent-overlay' });
      runs.push({ viewport: 'mobile', theme, measure: m });
      await s.close();
    }
  }

  writeFileSync(join(OUT, `readings-${TAG}.json`),
    JSON.stringify({ tag: TAG, web: WEB, browser: browserLabel, shots, runs, pageErrors, modeFrames }, null, 1));
  console.log(`[harness] shots=${shots.length} modeFrames=${modeFrames.length} errors=${pageErrors.length}`);
  console.log(JSON.stringify(shots.map((x) => `${x.scene}:${x.absent ? 'ABSENT(' + x.absent + ')' : 'ok'}`), null, 0));
  if (pageErrors.length) console.log('PAGE ERRORS:', JSON.stringify(pageErrors, null, 1));
  await browser.close();
}

await main();

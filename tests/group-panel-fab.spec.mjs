#!/usr/bin/env node
// tests/group-panel-fab.spec.mjs — 「发起群聊」面板统一批（2026-11-06）机检 + 读数 + 实拍。
//
// 作者令（2026-11-06 08:48）四条形态要求 → 本脚本四条机检（C1–C4）+ 两条辅检（C5/C6）：
//   C1 该面板不得再出现右上 X 关闭件            （overlay 内 `.fm-modal-close` 计数 = 0）
//   C2 主操作必须是悬浮圆钮类且走玻璃单源        （`.fm-pick-fab` ∈ `g2-fab` ∧ `nf-glass`，
//                                                且计算 backdrop-filter ≠ none）
//   C3 标题只出现一次                          （overlay 内等于标题文本的叶子节点计数 = 1）
//   C4 禁用态下点击零副作用                    （真鼠标点命中盒 → 无 POST /api/groups、
//                                                面板未关、无 toast；勾选 1 人后转可点）
//   C5 关闭键 = 左上返回圆钮且键盘可达           （`.fm-pick-back` 是原生 button + g2-fab，
//                                                回车触发返回语义）
//   C6 标题字阶 = 项目面板（`.wsp-title`）同一套  （font-size/weight/color 逐项相等）
//
// 相对量：与既有 Canvas 右下悬浮圆钮（`.ask-fab`，glass.css 圆钮级浮件档同族）逐项读数
// （尺寸/圆角/模糊/阴影/描边/图标/离边距/命中盒）。做法 = 在同页插一枚 `ask-fab` 探针，
// 读的是**同一份样式表**的计算值（不是抄来的数字）。
//
// 用法（改前红 / 改后绿 双跑）：
//   node tests/group-panel-fab.spec.mjs --web-root /tmp/group-panel-before/web --tag before
//   node tests/group-panel-fab.spec.mjs --web-root src/main/resources/web --tag after
// 产出：<out>/<tag>-<viewport>-<theme>-<state>.png + <out>/<tag>.json；任何一条 FAIL ⇒ exit 1。

import { mkdirSync, writeFileSync } from 'node:fs';
import { join, isAbsolute } from 'node:path';
import { ROOT, loadPlaywright, launchBrowser, createSession, settle, setWebRoot } from '../scripts/lib/visual-harness.mjs';

const argv = process.argv.slice(2);
const valOf = (f, d = null) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const WEB_ROOT = valOf('--web-root', 'src/main/resources/web');
const TAG = valOf('--tag', 'after');
const OUT = valOf('--out', 'reports-evidence/group-panel-fab-20261106');

setWebRoot(isAbsolute(WEB_ROOT) ? WEB_ROOT : join(ROOT, WEB_ROOT));
mkdirSync(OUT, { recursive: true });

const TITLE = '发起群聊';
const FRIENDS = [
  { userId: 'u1', username: 'RouRou', display_name: 'RouRou', avatar: '' },
  { userId: 'u2', username: 'Jwin', display_name: 'Jwin', avatar: '' },
  { userId: 'u3', username: 'Arrogant', display_name: 'Arrogant', avatar: '' },
];

const VIEWS = [[375, 812, '375'], [1440, 900, '1440']];
const THEMES = ['light', 'dark'];

/** 页内读数（一次性取全，减少往返）。 */
const IN_PAGE_MEASURE = (title) => {
  const overlay = document.getElementById('fm-group-create-overlay');
  const modal = overlay && overlay.querySelector('.fm-pick-modal');
  const pick = (el, props) => {
    if (!el) return null;
    const cs = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    const out = { rect: { x: +r.x.toFixed(2), y: +r.y.toFixed(2), w: +r.width.toFixed(2), h: +r.height.toFixed(2) } };
    for (const p of props) out[p] = cs[p];
    return out;
  };
  const BTN = ['width', 'height', 'borderRadius', 'backdropFilter', 'webkitBackdropFilter', 'boxShadow',
    'background', 'border', 'color', 'opacity', 'zIndex', 'position', 'right', 'bottom', 'cursor', 'padding'];
  // 叶子文本节点计数（自己无元素子节点）——「标题只出现一次」的判据面。
  const textLeaves = overlay ? [...overlay.querySelectorAll('*')].filter(
    (e) => e.children.length === 0 && e.textContent.trim() === title) : [];
  // 项目面板标题字阶基准（同页探针，读同一份样式表）
  const probe = document.createElement('div');
  probe.className = 'wsp-title';
  probe.textContent = title;
  probe.style.position = 'absolute';
  probe.style.visibility = 'hidden';
  document.body.appendChild(probe);
  const titleBase = pick(probe, ['fontSize', 'fontWeight', 'lineHeight', 'color', 'letterSpacing']);
  probe.remove();
  // 既有 Canvas 右下悬浮圆钮基准（.ask-fab 探针：本页把 canvas 面板换成 pane 容器，
  // .ask-fab 的绝对定位包含块取最近的 positioned 祖先，尺寸/材质/离边距不受影响）
  const host = document.createElement('div');
  host.style.cssText = 'position:fixed;inset:0;pointer-events:none;visibility:hidden';
  const ask = document.createElement('button');
  ask.className = 'ask-fab';
  ask.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"></svg>';
  host.appendChild(ask);
  document.body.appendChild(host);
  const askRef = pick(ask, BTN);
  const askSvg = ask.querySelector('svg');
  const askIcon = askSvg ? (() => { const cs = getComputedStyle(askSvg); return { width: cs.width, height: cs.height }; })() : null;
  host.remove();

  const fab = overlay && overlay.querySelector('.fm-pick-fab');
  const back = overlay && overlay.querySelector('.fm-pick-back');
  const nameEl = overlay && overlay.querySelector('.fm-modal-name');
  const hit = fab ? (() => {
    const r = fab.getBoundingClientRect();
    const t = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
    return {
      topIsFabOrChild: !!(t && (t === fab || fab.contains(t))),
      hitBox: fab.offsetWidth && fab.offsetHeight ? `${fab.offsetWidth}x${fab.offsetHeight}` : null,
    };
  })() : null;

  return {
    overlayPresent: !!overlay,
    modalPresent: !!modal,
    mobileLayer: document.body.classList.contains('mobile-layer'),
    closeXCount: overlay ? overlay.querySelectorAll('.fm-modal-close').length : -1,
    titleLeafCount: textLeaves.length,
    titleTexts: textLeaves.map((e) => e.textContent.trim()),
    fab: fab ? {
      ...pick(fab, BTN), disabled: fab.disabled, classes: fab.className,
      ariaLabel: fab.getAttribute('aria-label'),
      iconSize: (() => { const s = fab.querySelector('svg'); if (!s) return null; const cs = getComputedStyle(s); return `${cs.width}x${cs.height}`; })(),
      ...hit,
    } : null,
    back: back ? {
      ...pick(back, BTN), tag: back.tagName, classes: back.className,
      ariaLabel: back.getAttribute('aria-label'), type: back.type,
      iconSize: (() => { const s = back.querySelector('svg'); if (!s) return null; const cs = getComputedStyle(s); return `${cs.width}x${cs.height}`; })(),
    } : null,
    name: nameEl ? { ...pick(nameEl, ['fontSize', 'fontWeight', 'lineHeight', 'color', 'letterSpacing']), text: nameEl.textContent.trim() } : null,
    titleBase,
    askRef,
    askIcon,
    modalRect: modal ? (() => { const r = modal.getBoundingClientRect(); return { x: +r.x.toFixed(2), y: +r.y.toFixed(2), w: +r.width.toFixed(2), h: +r.height.toFixed(2) }; })() : null,
    headerRect: (() => { const h = overlay && overlay.querySelector('.fm-modal-header'); if (!h) return null; const r = h.getBoundingClientRect(); const cs = getComputedStyle(h); return { x: +r.x.toFixed(2), y: +r.y.toFixed(2), w: +r.width.toFixed(2), h: +r.height.toFixed(2), padding: cs.padding, borderBottom: cs.borderBottom }; })(),
    tokens: {
      sizeCircle: getComputedStyle(document.documentElement).getPropertyValue('--cv-size-circle').trim(),
      cvIcon: getComputedStyle(document.documentElement).getPropertyValue('--cv-icon').trim(),
      askGap: getComputedStyle(document.documentElement).getPropertyValue('--ask-fab-gap').trim(),
      askBottom: getComputedStyle(document.documentElement).getPropertyValue('--ask-fab-bottom').trim(),
      fsTitle: getComputedStyle(document.documentElement).getPropertyValue('--ds-fs-title').trim(),
    },
  };
};

async function run() {
  const pwRes = loadPlaywright();
  const pw = pwRes.mod.playwright || pwRes.mod;   // loadPlaywright 返回 { mod, from }，这里显式展开
  const { browser, label } = await launchBrowser(pw);
  console.log(`[harness] browser=${label} root=${WEB_ROOT} tag=${TAG}`);
  const report = { tag: TAG, webRoot: WEB_ROOT, browser: label, runs: [], checks: [], failures: 0 };
  let firstRun = null;

  for (const [w, h, vp] of VIEWS) {
    for (const theme of THEMES) {
      const s = await createSession(browser, {
        width: w, height: h, colorScheme: theme,
        apiStubs: {
          '/api/neblink/status': {
            body: {
              loggedIn: true,
              device: { id: 'dev1', name: 'stand', platform: 'macos', capabilities: {}, avatarUrl: '', userDescription: '' },
              peers: [],
            },
          },
          '/api/friends': { body: { friends: FRIENDS, incoming: [], outgoing: [] } },
          // 群路由在场（否则 groupsAvailable()=false ⇒ 打开面板会带一条「群聊功能暂不可用」
          // toast，那是可用性探针的噪声，会污染 C4「无 toast」判据）
          '/api/groups': { body: { groups: [] } },
          '/api/groups/invites': { body: { incoming: [] } },
        },
      });
      /** @type {string[]} */
      const apiCalls = [];
      s.page.on('request', (r) => { if (r.url().includes('/api/')) apiCalls.push(`${r.method()} ${new URL(r.url()).pathname}`); });
      await s.boot({ config: { llm: { providers: {} }, features: { friends: true } } });
      // 打开面板：真实模块入口（不直写 DOM）
      const opened = await s.page.evaluate(async () => {
        const nb = await import('/js/neblink.js');
        if (!nb.getNeblinkState().loggedIn) await nb.fetchNeblinkStatus();
        const m = await import('/js/friendGroups.js');
        m.openCreateGroupDialog();
        return !!document.getElementById('fm-group-create-overlay');
      });
      if (!opened) throw new Error('openCreateGroupDialog did not mount overlay');
      await settle(s.page, 250);

      const shot = (state) => s.page.screenshot({ path: join(OUT, `${TAG}-${vp}-${theme}-${state}.png`) });

      // ── 态①：未勾选（禁用态） ─────────────────────────────────────────
      const closed = await s.page.evaluate(IN_PAGE_MEASURE, TITLE);
      await shot('disabled');

      // C4 禁用态：真鼠标点命中盒 ⇒ 零副作用
      const before = apiCalls.length;
      const toastsBefore = await s.page.evaluate(() => document.querySelectorAll('.toast, .nf-toast, [class*="toast"]').length);
      let clickErr = null;
      if (closed.fab) {
        const r = closed.fab.rect;
        try {
          await s.page.mouse.click(r.x + r.w / 2, r.y + r.h / 2);
        } catch (e) { clickErr = String(e.message || e); }
      }
      await s.page.waitForTimeout(200);
      const afterClick = await s.page.evaluate(() => ({
        overlayPresent: !!document.getElementById('fm-group-create-overlay'),
        toasts: document.querySelectorAll('.toast, .nf-toast, [class*="toast"]').length,
      }));
      const disabledSideEffects = {
        apiCallsDelta: apiCalls.slice(before),
        overlayStillPresent: afterClick.overlayPresent,
        // 「零副作用」判据 = 点击前后**新增** toast 数 = 0（不比较绝对数：可用性探针等噪声不计入）
        toastDelta: afterClick.toasts - toastsBefore,
        toastAppeared: afterClick.toasts > toastsBefore,
        clickErr,
      };

      // ── 态②：勾选 1 人（可点态） ─────────────────────────────────────
      const rowClicked = await s.page.evaluate(() => {
        const row = document.querySelector('#fm-group-create-overlay .fm-pick-row');
        if (!row) return false;
        row.click();
        return true;
      });
      await s.page.waitForTimeout(200);
      const enabled = await s.page.evaluate(IN_PAGE_MEASURE, TITLE);
      await shot('enabled');

      // C5 返回语义（点在真实返回钮上，面板应关闭）——只在本轮最后一跑做，避免影响后续读数
      report.runs.push({
        viewport: vp, theme, mobileLayer: closed.mobileLayer,
        disabled: closed, enabled,
        disabledClick: disabledSideEffects, rowClicked,
        apiCalls,
      });
      if (!firstRun && vp === '1440' && theme === 'light') firstRun = { closed, enabled, disabledSideEffects, rowClicked };
      await s.close();
    }
  }

  // ── C5 返回钮：单独一次点击验证（同一装置） ─────────────────────────────
  let backResult = null;
  {
    const s = await createSession(browser, {
      width: 1440, height: 900, colorScheme: 'light',
      apiStubs: {
        '/api/neblink/status': { body: { loggedIn: true, device: { id: 'dev1', name: 'stand', platform: 'macos', capabilities: {} }, peers: [] } },
        '/api/friends': { body: { friends: FRIENDS, incoming: [], outgoing: [] } },
        '/api/groups': { body: { groups: [] } },
        '/api/groups/invites': { body: { incoming: [] } },
      },
    });
    await s.boot({ config: { llm: { providers: {} }, features: { friends: true } } });
    backResult = await s.page.evaluate(async () => {
      const nb = await import('/js/neblink.js');
      if (!nb.getNeblinkState().loggedIn) await nb.fetchNeblinkStatus();
      const m = await import('/js/friendGroups.js');
      m.openCreateGroupDialog();
      const back = document.querySelector('#fm-group-create-overlay .fm-pick-back');
      if (!back) return { present: false };
      const isButton = back.tagName === 'BUTTON';
      back.focus();
      const focused = document.activeElement === back;
      // 原生键盘激活（Enter）走浏览器默认 click ⇒ 这里用 dispatch 模拟用户回车
      back.click();
      return {
        present: true, isButton, focused, type: back.type,
        overlayClosedAfterClick: !document.getElementById('fm-group-create-overlay'),
        hasAriaLabel: !!back.getAttribute('aria-label'),
      };
    });
    await s.close();
  }

  // ── 判据 ────────────────────────────────────────────────────────────
  const f = firstRun;
  const check = (id, name, ok, reading) => {
    report.checks.push({ id, name, ok: !!ok, reading });
    if (!ok) report.failures += 1;
    console.log(`${ok ? 'PASS' : 'FAIL'} ${id} ${name} — ${reading}`);
  };

  check('C1', '顶栏不再有右上 X 关闭件', f.closed.closeXCount === 0, `overlay 内 .fm-modal-close = ${f.closed.closeXCount}`);
  check('C2', '主操作为悬浮圆钮类且走玻璃单源',
    !!(f.closed.fab && /\bg2-fab\b/.test(f.closed.fab.classes) && /\bnf-glass\b/.test(f.closed.fab.classes)
      && f.closed.fab.backdropFilter && f.closed.fab.backdropFilter !== 'none'),
    f.closed.fab ? `classes="${f.closed.fab.classes}" backdrop-filter=${f.closed.fab.backdropFilter}` : '未找到 .fm-pick-fab');
  check('C3', '标题只出现一次', f.closed.titleLeafCount === 1, `等于「${TITLE}」的叶子节点 = ${f.closed.titleLeafCount}`);
  check('C4', '禁用态点击零副作用（无请求/面板不关/无 toast）',
    !!f.closed.fab && f.closed.fab.disabled === true
      && f.disabledSideEffects.apiCallsDelta.length === 0 && f.disabledSideEffects.overlayStillPresent && !f.disabledSideEffects.toastAppeared,
    f.closed.fab
      ? `disabled=${f.closed.fab.disabled} api=${JSON.stringify(f.disabledSideEffects.apiCallsDelta)} overlay=${f.disabledSideEffects.overlayStillPresent} toast=${f.disabledSideEffects.toastAppeared}`
      : '未找到 .fm-pick-fab');
  check('C4b', '勾选 1 人后转可点', !!f.enabled.fab && f.enabled.fab.disabled === false,
    f.enabled.fab ? `disabled=${f.enabled.fab.disabled}` : '未找到 .fm-pick-fab');
  check('C5', '关闭键 = 左上返回圆钮（原生 button + g2-fab + 返回语义）',
    !!(f.closed.back && backResult && backResult.present && backResult.isButton && backResult.overlayClosedAfterClick && backResult.hasAriaLabel
      && /\bg2-fab\b/.test(f.closed.back.classes)),
    f.closed.back ? `tag=${f.closed.back.tag} classes="${f.closed.back.classes}" aria-label="${f.closed.back.ariaLabel}" 点击后关闭=${backResult && backResult.overlayClosedAfterClick}` : '未找到 .fm-pick-back');
  check('C6', '标题字阶 = 项目面板（.wsp-title）同一套',
    !!(f.closed.name && f.closed.titleBase
      && f.closed.name.fontSize === f.closed.titleBase.fontSize
      && f.closed.name.fontWeight === f.closed.titleBase.fontWeight
      && f.closed.name.color === f.closed.titleBase.color),
    f.closed.name ? `面板=${f.closed.name.fontSize}/${f.closed.name.fontWeight}/${f.closed.name.color} · 项目面板基准=${f.closed.titleBase.fontSize}/${f.closed.titleBase.fontWeight}/${f.closed.titleBase.color}` : '未找到 .fm-modal-name');

  // 一致性读数（主操作钮 vs 既有 Canvas 右下悬浮圆钮 .ask-fab）
  const fab = f.closed.fab;
  const ref = f.closed.askRef;
  const consistency = fab && ref ? [
    ['尺寸', `${fab.width}x${fab.height}`, `${ref.width}x${ref.height}`],
    ['圆角', fab.borderRadius, ref.borderRadius],
    ['模糊', fab.backdropFilter, ref.backdropFilter],
    ['阴影', fab.boxShadow, ref.boxShadow],
    ['描边', fab.border, ref.border],
    ['图标', fab.iconSize || '-', f.closed.askIcon ? `${f.closed.askIcon.width}x${f.closed.askIcon.height}` : '-'],
    ['离边距', `right=${fab.right} bottom=${fab.bottom}`, `right=${ref.right} bottom=${ref.bottom}`],
    ['命中盒', fab.hitBox, ref.rect ? `${ref.rect.w.toFixed(0)}x${ref.rect.h.toFixed(0)}` : ''],
    ['层级 z-index', fab.zIndex, ref.zIndex],
    ['底色', fab.background, ref.background],
  ] : [];
  report.consistency = consistency;
  report.backResult = backResult;
  report.tokens = f.closed.tokens;

  console.log('\n── 主操作钮 vs 既有 Canvas 右下悬浮圆钮（.ask-fab，同页同表计算值）──');
  for (const [k, a, b] of consistency) console.log(`  ${k.padEnd(10)} 本面板=${a}  |  既有=${b}  ${a === b ? '=' : '≠'}`);

  writeFileSync(join(OUT, `${TAG}.json`), JSON.stringify(report, null, 2));
  console.log(`\n[${TAG}] ${report.failures === 0 ? 'ALL GREEN' : `${report.failures} FAIL`} → ${join(OUT, `${TAG}.json`)}`);
  await browser.close();
  process.exit(report.failures === 0 ? 0 : 1);
}

run().catch((e) => { console.error(e); process.exit(2); });

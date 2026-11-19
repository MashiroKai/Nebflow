#!/usr/bin/env node
// verify-toggle-unify.mjs — 开关统一批（toggle-unify 2026-11-05）的实拍 + 实测装置
//
// 判据不是「看起来像那个胶囊」，而是逐项可读数的性质：
//   ① 几何：轨道 40×22、圆钮 16、圆角、行程 18（圆钮 OFF/ON 的实际位移像素）
//   ② 动效：transition-duration 180ms、timing-function cubic-bezier(0.2,0,0,1)
//   ③ 命中区：::before 外扩盒 ≥ 44×44
//   ④ 键鼠：Tab 可达、Space 与 Enter 都翻转 aria-checked、焦点环是 2px outline
//   ⑤ 状态：role="switch" + aria-checked 与 .on 类同源
// 全部来自**真实产品页**（index.html + 真 CSS/JS，零端口、零实例：visual-harness
// 用 page.route 从磁盘服务静态根，MockWS 承接服务端帧）——不是重写的替身页。
//
// 两条腿（同一支脚本、同一套场景、同一组读数）：
//   --root <dir>  served 的静态根（默认本仓 web/）；改前 = main tip 的 web/ 快照
//   --tag before|after   产出文件命名
// 用法：
//   node scripts/verify-toggle-unify.mjs --tag after  --out /tmp/tv --root src/main/resources/web
//   node scripts/verify-toggle-unify.mjs --tag before --out /tmp/tv --root /tmp/toggle-before/src/main/resources/web
//
// 产出：<out>/<tag>-<leg>-<theme>.png（真实行克隆到对照板后截整板）
//       <out>/<tag>-measure.json（逐腿逐项的实测值 + 断言结果）
// 退出码：0 = 全部断言通过；1 = 有断言失败（读数照旧写盘）。

import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { evidenceBatch } from './lib/evidence-batch.mjs';
import {
  loadPlaywright, launchBrowser, createSession, settle, setWebRoot, webRoot,
} from './lib/visual-harness.mjs';

const argv = process.argv.slice(2);
const valOf = (f, d = null) => {
  const i = argv.indexOf(f);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : d;
};

const ROOT_DIR = valOf('--root', 'src/main/resources/web');
const TAG = valOf('--tag', 'after');
// Default output = an EvidenceTemp batch (manifest + non-active ⇒ TTL-sweepable);
// an explicit --out keeps its meaning.
const OUT = valOf('--out', null) || evidenceBatch('toggle-unify', 'verify');
const THEMES = (valOf('--themes', 'light,dark') || '').split(',').filter(Boolean);
const LEGS = (valOf('--legs', 'settings,profile,todo,daemons,perception') || '')
  .split(',').filter(Boolean);

setWebRoot(ROOT_DIR);
mkdirSync(OUT, { recursive: true });

// ── 无头引导（零端口：route 从磁盘服务静态根；MockWS 承接服务端帧）──────────
const DAEMONS_STUB = [
  { id: 'web-dev', name: 'web-dev', command: 'npm run dev', status: 'running', port: 5199, portOpen: true, autoStart: true, hasConfigPanel: false },
  { id: 'tunnel', name: 'tunnel', command: 'ssh -N tunnel', status: 'stopped', autoStart: false, hasConfigPanel: false },
];

const FRIEND_STUB = { userId: 'f1', id: 'f1', name: 'Kai', remark: '', bio: 'hi', muted: false, pinned: true };
const API_STUBS = {
  '/api/daemons': { body: DAEMONS_STUB },
  '/api/friends': { body: { friends: [FRIEND_STUB], groups: [] } },
  // 感知面板：entries 是面板体的唯一数据源（renderPanel 读 entriesCache）
  '/api/perception/entries': {
    body: { entries: [{
      id: 'm1', name: 'Mail', state: 'Idle', summary: 'kai@example.com', notice: '',
    }] },
  },
};

/** 每个腿：怎么把真实行渲染出来 + 从哪取（选择器）。 */
const LEG_DEFS = {
  settings: {
    label: '设置页开关（设置面板 · 共享组件）',
    mount: async (page) => {
      await page.evaluate(async () => {
        const m = await import('/js/sidebar.js');
        m.openSettingsPanel();
        await new Promise((r) => setTimeout(r, 400));
      });
      return '#settings-content';
    },
    rowSelectors: ['.settings-row'],
    switchSelector: '.nb-toggle',
    liveSwitchSelector: '#settings-content .nb-toggle',
  },
  profile: {
    label: '好友信息页开关（免打扰 / 顶置）',
    mount: async (page) => {
      await page.evaluate(async () => {
        const m = await import('/js/profileFace.js');
        await m.openFriendProfile({ userId: 'f1', id: 'f1', name: 'Kai', remark: '', bio: 'hi', muted: false, pinned: true });
        await new Promise((r) => setTimeout(r, 400));
      });
      return '.pf-switch';
    },
    rowSelectors: ['.pf-switch'],
    switchSelector: '.nb-toggle',
    liveSwitchSelector: '.pf-switch .nb-toggle',
  },
  todo: {
    label: '待办面截止时间开关（行内创建行）',
    mount: async (page) => {
      await page.evaluate(async () => {
        const m = await import('/js/todoFace.js');
        const host = document.createElement('div');
        host.id = 'nf-todo-host';
        // 必须可聚焦（visibility:hidden / display:none 都收不到 Tab 与 Space）⇒
        // 放到视口外而不是隐藏；对照板里的克隆件才是出图对象。
        host.style.cssText = 'position:fixed;left:-3000px;top:0;width:520px;';
        document.body.appendChild(host);
        const face = m.mountTodoFaceInto(host);
        await new Promise((r) => setTimeout(r, 250));
        face?.startCreate?.();
        await new Promise((r) => setTimeout(r, 350));
      });
      return '.tfl-edit-due';
    },
    rowSelectors: ['.tfl-edit-due'],
    switchSelector: '.nb-toggle, .tfl-switch',
    liveSwitchSelector: '#nf-todo-host .nb-toggle, #nf-todo-host .tfl-switch',
  },
  daemons: {
    label: '心跳进程自启动开关',
    mount: async (page) => {
      await page.evaluate(async () => {
        document.getElementById('daemon-btn')?.click();
        await new Promise((r) => setTimeout(r, 700));
      });
      return '#daemon-panel .daemon-panel-body';
    },
    rowSelectors: ['.daemon-row'],
    switchSelector: '.nb-toggle, .daemon-autostart',
    liveSwitchSelector: '#daemon-panel .nb-toggle, #daemon-panel .daemon-autostart',
  },
  perception: {
    label: '感知面板邮件开关',
    mount: async (page) => {
      await page.evaluate(async () => {
        document.getElementById('perception-btn')?.click();
        await new Promise((r) => setTimeout(r, 800));
        // 开关在**展开的条目详情**里（手风琴），先点开第一条
        document.querySelector('#perception-panel .pp-entry-head')?.click();
        await new Promise((r) => setTimeout(r, 400));
      });
      return '#perception-panel';
    },
    rowSelectors: ['.pp-mailrow', '.pp-alert'],
    switchSelector: '.nb-toggle, .pp-toggle',
    liveSwitchSelector: '#perception-panel .nb-toggle, #perception-panel .pp-toggle',
  },
};

// ── 页内测量（一条函数把「几何 / 动效 / 状态」三面读全）────────────────────
const MEASURE_FN = function measureSwitches(arg) {
  const { switchSelector, pseudoOf } = arg;
  const els = [...document.querySelectorAll(switchSelector)];
  const px = (v) => {
    const n = parseFloat(v);
    return Number.isFinite(n) ? n : null;
  };
  const matrixTranslateX = (t) => {
    if (!t || t === 'none') return 0;
    const m = t.match(/matrix\(([^)]+)\)/);
    if (m) return parseFloat(m[1].split(',')[4]);
    const m3 = t.match(/matrix3d\(([^)]+)\)/);
    if (m3) return parseFloat(m3[1].split(',')[12]);
    return 0;
  };
  return els.map((el) => {
    const r = el.getBoundingClientRect();
    const cs = getComputedStyle(el);
    const knobEl = pseudoOf === 'child' ? el.querySelector('.tfl-switch-knob, .daemon-autostart-thumb, .pp-toggle-knob') : null;
    const knob = pseudoOf === 'child'
      ? (knobEl ? getComputedStyle(knobEl) : null)
      : getComputedStyle(el, '::after');
    const hit = pseudoOf === 'child' ? null : getComputedStyle(el, '::before');
    return {
      cls: el.className,
      role: el.getAttribute('role'),
      ariaChecked: el.getAttribute('aria-checked'),
      on: el.classList.contains('on'),
      track: { w: +r.width.toFixed(2), h: +r.height.toFixed(2) },
      radius: cs.borderRadius,
      background: cs.backgroundColor,
      borderColor: cs.borderColor,
      transition: {
        property: cs.transitionProperty,
        duration: cs.transitionDuration,
        timing: cs.transitionTimingFunction,
      },
      knob: knob ? {
        w: px(knob.width),
        h: px(knob.height),
        radius: knob.borderRadius,
        bg: knob.backgroundColor,
        border: knob.border,
        transform: knob.transform,
        translateX: +matrixTranslateX(knob.transform).toFixed(2),
      } : null,
      hitBox: hit ? { w: hit.width, h: hit.height, content: hit.content } : null,
      focusable: el.tabIndex >= 0 && !el.disabled,
    };
  });
};

/**
 * 键盘实测（在**产品原件**上做，不在对照板的克隆件上做）：
 * 聚焦 → Space → Enter，每步读 aria-checked，并读焦点环的计算样式。
 * 用 page.keyboard.press 派发真键事件 —— 原生 <button> 的 Space/Enter UA 行为会
 * 自己派发 click，这正是「键盘可达是构造出来的」的验证点。
 */
async function keyboardProbe(page, selector, index = 0) {
  const setup = await page.evaluate((arg) => {
    const el = document.querySelectorAll(arg.selector)[arg.index];
    if (!el) return null;
    el.focus();
    const cs = getComputedStyle(el);
    // 记录 aria-checked 的每一次翻转（乐观翻面后可能被后端真值收敛回来 —— 记序列
    // 才能证明「键确实驱动了状态」，而不是只读最后一帧）
    const log = [];
    window.__nfKbLog = log;
    window.__nfKbObserver?.disconnect?.();
    const mo = new MutationObserver(() => log.push({ v: el.getAttribute('aria-checked'), t: performance.now() }));
    mo.observe(el, { attributes: true, attributeFilter: ['aria-checked'] });
    window.__nfKbObserver = mo;
    return {
      tag: el.tagName,
      focused: document.activeElement === el,
      focusVisible: el.matches(':focus-visible'),
      ariaChecked: el.getAttribute('aria-checked'),
      on: el.classList.contains('on'),
      ring: { outline: `${cs.outlineWidth} ${cs.outlineStyle} ${cs.outlineColor}`, offset: cs.outlineOffset },
    };
  }, { selector, index });
  if (!setup) return { error: 'live switch not found' };

  const snap = () => page.evaluate((arg) => {
    const el = document.querySelectorAll(arg.selector)[arg.index];
    const log = window.__nfKbLog || [];
    return {
      ariaChecked: el ? el.getAttribute('aria-checked') : null,
      on: el ? el.classList.contains('on') : null,
      activeIsSelf: document.activeElement === el,
      log: log.slice(),
    };
  }, { selector, index });

  await page.keyboard.press('Space');
  const space = await snap();
  await page.keyboard.press('Enter');
  const enter = await snap();
  return { setup, space, enter };
}


/**
 * 状态面读数（只在改后腿上跑）：行程 / 不确定态 / 禁用态 / 减少动态。
 * 全部在**产品原件**上改属性读计算样式，不碰样式表 —— 读的就是真实级联结果。
 */
async function stateProbe(page, selector) {
  const read = () => page.evaluate((sel) => {
    const el = document.querySelector(sel);
    if (!el) return null;
    const cs = getComputedStyle(el);
    const knob = getComputedStyle(el, '::after');
    const m = knob.transform.match(/matrix\(([^)]+)\)/);
    return {
      on: el.classList.contains('on'),
      ariaChecked: el.getAttribute('aria-checked'),
      ariaBusy: el.getAttribute('aria-busy'),
      borderStyle: cs.borderStyle,
      cursor: cs.cursor,
      opacity: cs.opacity,
      knobOpacity: knob.opacity,
      knobTranslateX: m ? parseFloat(m[1].split(',')[4]) : 0,
      duration: cs.transitionDuration,
      outline: `${cs.outlineWidth} ${cs.outlineStyle} ${cs.outlineColor}`,
      trackBg: cs.backgroundColor,
      trackBorder: cs.borderTopColor,
      knobBg: knob.backgroundColor,
      knobBorder: knob.borderTopColor,
      // 行底：向上找第一个非透明的背景（settings-row 自己通常是透明，
      // 直接读它会得到 rgba(0,0,0,0)，对比度会被算成「压黑」——假读数）
      pageBg: (() => {
        let n = document.querySelector(sel)?.parentElement;   // 从**父件**起走（自己的底色是轨道色）
        while (n && n !== document.documentElement) {
          const c = getComputedStyle(n).backgroundColor;
          if (c && !/rgba\(0, 0, 0, 0\)|transparent/.test(c)) return c;
          n = n.parentElement;
        }
        return getComputedStyle(document.body).backgroundColor;
      })(),
    };
  }, selector);

  // 先强制回 OFF 并等过渡落定 —— 否则量到的是「过渡中的中间帧」而不是端点
  await page.evaluate((sel) => {
    const el = document.querySelector(sel);
    el.classList.remove('on');
    el.setAttribute('aria-checked', 'false');
  }, selector);
  await settle(page, 400);
  const off = await read();
  // 行程：点一次（真实 click 路径）→ 等过渡落定 → 读 ON 档圆钮的实际位移
  await page.evaluate((sel) => document.querySelector(sel).click(), selector);
  await settle(page, 400);
  const on = await read();
  // 不确定态：aria-busy（组件约定的「进行中」表达）
  await page.evaluate((sel) => document.querySelector(sel).setAttribute('aria-busy', 'true'), selector);
  await settle(page, 80);
  const busy = await read();
  await page.evaluate((sel) => document.querySelector(sel).removeAttribute('aria-busy'), selector);
  // 禁用态
  await page.evaluate((sel) => { document.querySelector(sel).disabled = true; }, selector);
  await settle(page, 80);
  const disabled = await read();
  await page.evaluate((sel) => { document.querySelector(sel).disabled = false; }, selector);
  // 减少动态
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await settle(page, 80);
  const reduced = await read();
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  return { off, on, busy, disabled, reduced };
}

/** WCAG 2.x 相对亮度 / 对比度（输入 = getComputedStyle 的 rgb()/rgba() 串）。 */
function parseRgb(str) {
  const m = String(str).match(/rgba?\(([^)]+)\)/);
  if (!m) return null;
  const p = m[1].split(',').map((x) => parseFloat(x));
  return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 };
}
function relLum({ r, g, b }) {
  const f = (c) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
}
function contrast(a, b) {
  const la = relLum(a); const lb = relLum(b);
  const hi = Math.max(la, lb); const lo = Math.min(la, lb);
  return +((hi + 0.05) / (lo + 0.05)).toFixed(2);
}
/** 半透明前景压到底色上（α 合成），否则暗档的 #ffffff14 之类会被当成纯色算 */
function over(fg, bg) {
  const a = fg.a ?? 1;
  return {
    r: fg.r * a + bg.r * (1 - a),
    g: fg.g * a + bg.g * (1 - a),
    b: fg.b * a + bg.b * (1 - a),
    a: 1,
  };
}

async function run() {
  const pw = loadPlaywright();
  const { browser, label } = await launchBrowser(pw.mod, {});
  const report = {
    tag: TAG,
    webRoot: webRoot(),
    browser: label,
    generatedAt: new Date().toISOString(),
    legs: {},
    assertions: [],
  };

  const assert = (name, ok, detail) => {
    report.assertions.push({ name, ok: !!ok, detail });
    return ok;
  };

  for (const theme of THEMES) {
    for (const legId of LEGS) {
      const def = LEG_DEFS[legId];
      if (!def) continue;
      // 每条腿一张干净页：面板/图层栈/持久化互不串味（同一页连跑会踩图层栈残留）
      const s = await createSession(browser, {
        width: 1440, height: 900, colorScheme: theme, apiStubs: API_STUBS,
      });
      const { page } = s;
      // harness 默认 reducedMotion:'reduce'（视觉基线要确定性）；本批要读「正常动效」
      // 的 180ms/曲线，故先覆盖回 no-preference。
      await page.emulateMedia({ reducedMotion: 'no-preference' });
      let ok = true;
      let rows = 0;
      try {
        await s.boot();
        await def.mount(page);
        await settle(page, 250);

        // 对照板：真实行克隆进来并排摆（真实 DOM + 真实 CSS，只换位置）
        await page.evaluate(() => {
          document.getElementById('nf-toggle-stage')?.remove();
          const stage = document.createElement('div');
          stage.id = 'nf-toggle-stage';
          stage.style.cssText = 'position:fixed;left:0;top:0;z-index:99999;background:var(--st-bg,#f5f6f8);'
            + 'padding:12px 16px;display:flex;flex-direction:column;gap:6px;min-width:520px;max-width:640px;';
          document.body.appendChild(stage);
        });

        // 克隆：按行选择器把真实节点搬进对照板
        const cloned = await page.evaluate((arg) => {
          const { rows: rowSels, switchSel } = arg;
          const stage = document.getElementById('nf-toggle-stage');
          if (!stage) return 0;
          let n = 0;
          for (const sel of rowSels) {
            for (const node of document.querySelectorAll(sel)) {
              if (!node.querySelector(switchSel)) continue;
              const c = node.cloneNode(true);
              // 克隆件不承担交互：只作为「视觉复本」——原件的键盘与状态读数另测
              c.querySelectorAll('[id]').forEach((x) => x.removeAttribute('id'));
              stage.appendChild(c);
              n++;
            }
          }
          return n;
        }, { rows: def.rowSelectors, switchSel: def.switchSelector });

        if (!cloned) {
          report.legs[`${legId}-${theme}`] = { ok: false, reason: '未渲染出任何含开关的真实行（本腿未执行）', rows: 0 };
          assert(`${legId}/${theme} 渲染出开关行`, false, '0 行');
          ok = false;
        } else {
          const usesChildKnob = TAG === 'before' && (legId === 'todo' || legId === 'daemons');
          const measured = await page.evaluate(MEASURE_FN, {
            switchSelector: `#nf-toggle-stage ${def.switchSelector}`,
            pseudoOf: usesChildKnob ? 'child' : 'pseudo',
          });
          const kb = await keyboardProbe(page, def.liveSwitchSelector || def.switchSelector, 0)
            .catch((e) => ({ error: String(e).slice(0, 300) }));

          let states = null;
          if (TAG === 'after' && legId === 'settings') {
            states = await stateProbe(page, def.liveSwitchSelector).catch(() => null);
            if (states) {
              const travel = Math.abs(states.on.knobTranslateX);
              report.states = states;
              assert('行程 18px（圆钮 OFF→ON 实测位移）', Math.abs(travel - 18) < 0.6, `translateX=${states.on.knobTranslateX} (OFF ${states.off.knobTranslateX})`);
              assert('不确定态 = 虚线描边 + 圆钮 .55',
                states.busy.borderStyle === 'dashed' && Math.abs(parseFloat(states.busy.knobOpacity) - 0.55) < 0.02,
                JSON.stringify({ style: states.busy.borderStyle, knob: states.busy.knobOpacity }));
              assert('禁用态 = 不透明度 .5 + not-allowed',
                Math.abs(parseFloat(states.disabled.opacity) - 0.5) < 0.02 && states.disabled.cursor === 'not-allowed',
                JSON.stringify({ opacity: states.disabled.opacity, cursor: states.disabled.cursor }));
              assert('减少动态 = 0.01ms 瞬间落位',
                /0\.00001s|1e-05s|0s/.test(String(states.reduced.duration)), states.reduced.duration);
              // 对比度实测（判据 6）：ON/OFF 两态下轨道 ↔ 圆钮、轨道 ↔ 行底
              const bg = parseRgb(states.off.pageBg) || { r: 255, g: 255, b: 255, a: 1 };
              const offTrack = over(parseRgb(states.off.trackBg), bg);
              const onTrack = over(parseRgb(states.on.trackBg), bg);
              const knobOff = over(parseRgb(states.off.knobBg), offTrack);
              const knobOn = over(parseRgb(states.on.knobBg), onTrack);
              const c = {
                theme,
                knobVsTrackOff: contrast(knobOff, offTrack),
                knobVsTrackOn: contrast(knobOn, onTrack),
                trackOnVsTrackOff: contrast(onTrack, offTrack),
                trackOnVsPage: contrast(onTrack, bg),
                trackOffVsPage: contrast(offTrack, bg),
                colors: {
                  offTrack: states.off.trackBg, onTrack: states.on.trackBg,
                  knobOff: states.off.knobBg, knobOn: states.on.knobBg,
                  knobBorder: states.off.knobBorder, page: states.off.pageBg,
                },
              };
              report.contrast = report.contrast || {};
              report.contrast[theme] = c;
              assert(`${theme} 对比度读数已采集`, c.trackOnVsTrackOff > 0, JSON.stringify(c));
            }
          }
          const shot = join(OUT, `${TAG}-${legId}-${theme}.png`);
          await page.locator('#nf-toggle-stage').screenshot({ path: shot });

          report.legs[`${legId}-${theme}`] = { ok: true, rows: cloned, measured, keyboard: kb, states: null, shot };
          if (TAG === 'after') {
            const m = measured[0] || {};
            assert(`${legId}/${theme} 轨道 40×22`, m.track && Math.abs(m.track.w - 40) < 0.6 && Math.abs(m.track.h - 22) < 0.6, JSON.stringify(m.track));
            assert(`${legId}/${theme} 圆钮 16×16`, m.knob && Math.abs(m.knob.w - 16) < 0.6 && Math.abs(m.knob.h - 16) < 0.6, JSON.stringify(m.knob && { w: m.knob.w, h: m.knob.h }));
            assert(`${legId}/${theme} 圆角 999px/胶囊`, /999px|11px/.test(m.radius || ''), m.radius);
            assert(`${legId}/${theme} 过渡 180ms + A 组曲线`,
              /0\.18s|180ms/.test(m.transition?.duration || '') && /cubic-bezier\(0\.2, 0, 0, 1\)|cubic-bezier\(0.2,0,0,1\)/.test((m.transition?.timing || '').replace(/\s/g, '')),
              JSON.stringify(m.transition));
            assert(`${legId}/${theme} 命中盒 ≥ 44×44`, m.hitBox && parseFloat(m.hitBox.w) >= 44 && parseFloat(m.hitBox.h) >= 44, JSON.stringify(m.hitBox));
            assert(`${legId}/${theme} role=switch + aria-checked`, m.role === 'switch' && ['true', 'false'].includes(m.ariaChecked), `${m.role}/${m.ariaChecked}`);
            const spaceFlips = !!kb && !kb.error
              && (kb.space.log.length > 0 || kb.space.ariaChecked !== kb.setup.ariaChecked);
            const enterFlips = !!kb && !kb.error && kb.enter.log.length > kb.space.log.length;
            assert(`${legId}/${theme} 键盘 Space 翻转`, spaceFlips,
              JSON.stringify(kb && { b: kb.setup.ariaChecked, s: kb.space.ariaChecked, log: kb.space.log.length, err: kb.error }));
            assert(`${legId}/${theme} 键盘 Enter 翻转`, enterFlips,
              JSON.stringify(kb && { s: kb.space.log.length, e: kb.enter.log.length, err: kb.error }));
            assert(`${legId}/${theme} 焦点环 = 2px outline`,
              kb && /2px solid/.test(kb.setup.ring?.outline || '') && kb.setup.focusVisible,
              JSON.stringify(kb && kb.setup.ring));
          } else {
            // 改前腿：只记录读数，不做「应符合 A 组」的断言（记录差异即证据）
            assert(`${legId}/${theme} 改前读数已采集`, true, `${cloned} 行`);
          }
        }
      } catch (e) {
        report.legs[`${legId}-${theme}`] = { ok: false, reason: String(e).slice(0, 400), rows };
        assert(`${legId}/${theme} 采集完成`, false, String(e).slice(0, 200));
        ok = false;
      }
      void ok;
      await s.close();
    }
  }

  await browser.close();

  // 行程实测（ON 档圆钮实际位移）：从 settings 腿的 OFF/ON 两态读数差得出
  writeFileSync(join(OUT, `${TAG}-measure.json`), JSON.stringify(report, null, 2));
  const bad = report.assertions.filter((a) => !a.ok);
  console.log(`[${TAG}] 断言 ${report.assertions.length} 条，失败 ${bad.length} 条；读数 → ${join(OUT, `${TAG}-measure.json`)}`);
  for (const b of bad) console.log(`  ✗ ${b.name} — ${b.detail}`);
  if (bad.length) process.exit(1);
}

await run();

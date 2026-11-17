#!/usr/bin/env node
// tests/mobile-bgagent-chrome.spec.mjs — 手机端后台 agent 对话框简化批（2026-11-06）
// 机检 + 读数 + 实拍（真渲染；零端口静态装置，visual-harness 同族）。
//
// 作者令（2026-11-06 10:00/12:03 附实拍 1079617902519958_IMG_1766.jpg）六条形态 →
// 本脚本六条机检（M1–M6，手机 375）+ 一条桌面零变化对照（D1，1440）：
//   M1 顶栏无重复关闭件          （overlay 内 .flow-agent-close = 0，左上返回钮在场，
//                                  点返回 = 弹窗关闭——关闭路径只剩一条）
//   M2 状态胶囊 = 状态词面+时长  （.bgagent-status-pill 可见，含「运行中」与时长
//                                  「7m」，不含任务标题全文；位于顶带 y < 64）
//   M3 终止钮 = 右下悬浮圆钮     （.bgagent-stop-fab ∈ g2-fab，命中盒 ≥40，
//                                  右下离边档，backdrop-filter ≠ none，
//                                  色 = 危险档 --color-error）
//   M4 ⋯ 详情浮层可开合且含三字段（插件 / 工作树 / 任务内容全文 + 来源 / 创建；
//                                   点 ⋯ 开、点 scrim 收）
//   M5 底部简化                  （.flow-agent-footer 不可见；.fa-task 不在可见面；
//                                  灰行 .bgws 只存在于详情浮层内）
//   M6 终止钮发真实终止帧        （真鼠标点命中盒 → __wsSent 增 {type:'cancelAgent'}）
//   D1 桌面零变化（1440）        （✕ 在场=1；胶囊/⋯/悬浮钮/浮层全缺；footer 在场；
//                                  摘要条与灰行仍在弹窗头下原位）
//
// 用法（改前红 / 改后绿 双跑）：
//   node tests/mobile-bgagent-chrome.spec.mjs --web-root /tmp/bgagent-before/web --tag before
//   node tests/mobile-bgagent-chrome.spec.mjs --web-root src/main/resources/web --tag after
// 产出：<out>/<tag>-<viewport>-<theme>-<state>.png + <out>/<tag>.json；任何一条 FAIL ⇒ exit 1。

import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, isAbsolute } from 'node:path';
import { ROOT, loadPlaywright, launchBrowser, createSession, settle, setWebRoot } from '../scripts/lib/visual-harness.mjs';

const argv = process.argv.slice(2);
const valOf = (f, d = null) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const WEB_ROOT = valOf('--web-root', 'src/main/resources/web');
const TAG = valOf('--tag', 'after');
const OUT = valOf('--out', 'reports-evidence/bgagent-mobile-chrome-20261106');

setWebRoot(isAbsolute(WEB_ROOT) ? WEB_ROOT : join(ROOT, WEB_ROOT));
mkdirSync(OUT, { recursive: true });

// 钉时钟：uptime/创建时间读数恒定（visual-harness 的 clock 装置——B0-1 同款）。
const CLOCK = 1793940000000;
const UPTIME_MS = 7 * 60 * 1000; // 快照 startedAt = CLOCK - 7min ⇒ 胶囊时长恒「7m 0s」

const SID = 'delegate-fee-final-1';
const ROOT_SID = 'visual-root';
const AGENT_NAME = 'FEE巡检三次';
const TASK_TITLE = '【FEE 巡检第三次复测 · 作者已重新烧写尾槽探测脚本】';
const TASK_BRIEF = TASK_TITLE + ' 对 chisel-fpga 工作树做第三次 FEE 巡检复测，逐槽验证尾槽探测修复；完成后按合并轮纪律申报，禁止就地直改主工作区。';

const DELEGATE_DETAIL = {
  record: {
    id: SID,
    executor: 'claude',
    cwd: '/Users/kaiyu/worktrees/fee-final',
    project: 'stand',
    taskId: '12',
    parentSessionId: ROOT_SID,
    title: TASK_TITLE,
    createdAt: CLOCK,
    name: AGENT_NAME,
    branch: 'delegate/fee-final',
    worktree: '/Users/kaiyu/worktrees/fee-final',
    plugins: ['wiki-docs', 'visual-report'],
  },
  live: { status: 'running' },
  verdict: null,
  task: null,
  childrenCount: 0,
};

const HISTORY_ROWS = [
  { type: 'user', injected: true, source: 'delegate', eventType: 'dispatch', sender: 'Nebula',
    header: 'DELEGATE · NEBFLOW · TASK', timestamp: CLOCK, text: TASK_BRIEF, attachments: [] },
];

const VIEWS = [
  [375, 812, '375'],
  [1440, 900, '1440'],
];

/** 页内一次性取全读数（自 harness 页上下文；零选择器假设 ⇒ 改前树安全返回 null 面）。 */
const IN_PAGE_MEASURE = (sid) => {
  // 改前树无 .bgagent-overlay 作用域钩 ⇒ 回退 .flow-agent-overlay.fullscreen（同族
  // 旧形态读数完整，改前红有内容可读）。同一时刻只有一个弹窗，无双命中歧义。
  const overlay = document.querySelector('.bgagent-overlay, .flow-agent-overlay.fullscreen');
  const vis = (el) => !!el && el.getClientRects().length > 0;
  const pick = (el, props) => {
    if (!el) return null;
    const cs = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    const out = { rect: { x: +r.x.toFixed(2), y: +r.y.toFixed(2), w: +r.width.toFixed(2), h: +r.height.toFixed(2) } };
    for (const p of props) out[p] = cs[p];
    return out;
  };
  const PILL = ['position', 'fontSize', 'color', 'background', 'border', 'pointerEvents', 'zIndex', 'maxWidth', 'whiteSpace'];
  const FAB = ['position', 'right', 'bottom', 'width', 'height', 'borderRadius', 'color',
    'backdropFilter', 'webkitBackdropFilter', 'cursor', 'zIndex', 'display', 'opacity'];
  const sheet = overlay && overlay.querySelector('.bgagent-more-sheet');
  const pill = overlay && overlay.querySelector('.bgagent-status-pill');
  const more = overlay && overlay.querySelector('.bgagent-more');
  const stopFab = overlay && overlay.querySelector('.bgagent-stop-fab');
  const footer = overlay && overlay.querySelector('.flow-agent-footer');
  const closeX = overlay && overlay.querySelector('.flow-agent-close');
  const back = overlay && overlay.querySelector('.mfs-back');
  const taskEl = footer && footer.querySelector('.fa-task');
  const stripInSheet = sheet && sheet.querySelector('[data-delegate-strip]');
  const wsInSheet = sheet && sheet.querySelector('.bgws');
  const stripInModal = overlay && [...overlay.querySelectorAll(':scope .flow-agent-modal [data-delegate-strip]')].length;
  const wsInModal = overlay && [...overlay.querySelectorAll(':scope .flow-agent-modal .bgws')].length;
  // 危险档基准探针（同页同表计算值，不抄数字）：--color-error 既有 token。
  const errProbe = document.createElement('span');
  errProbe.style.cssText = 'position:absolute;visibility:hidden;color:var(--color-error)';
  document.body.appendChild(errProbe);
  const errColor = getComputedStyle(errProbe).color;
  errProbe.remove();
  return {
    mobileLayer: document.body.classList.contains('mobile-layer'),
    overlayPresent: !!overlay,
    closeX: closeX ? { count: 1, visible: vis(closeX) } : { count: 0, visible: false },
    back: back ? { present: true, visible: vis(back), rect: back.getBoundingClientRect().toJSON() } : { present: false },
    heading: (() => {
      const heading = overlay && overlay.querySelector('.bgagent-mobile-heading');
      const title = heading && heading.querySelector('.bgagent-mobile-title');
      return heading && title && pill ? { text: title.textContent.trim(), title: title.getBoundingClientRect().toJSON(), pill: pill.getBoundingClientRect().toJSON(), nested: pill.parentElement === heading } : null;
    })(),
    pill: pill ? {
      ...pick(pill, PILL), visible: vis(pill),
      text: pill.textContent.trim(),
      ariaLabel: pill.getAttribute('aria-label'),
      iconClass: (pill.querySelector('.bgagent-pill-status') || {}).className || '',
    } : null,
    more: more ? {
      visible: vis(more), classes: more.className,
      ariaLabel: more.getAttribute('aria-label'), ariaExpanded: more.getAttribute('aria-expanded'),
      rect: more.getBoundingClientRect().toJSON(),
    } : null,
    stopFab: stopFab ? {
      ...pick(stopFab, FAB), visible: vis(stopFab), classes: stopFab.className,
      ariaLabel: stopFab.getAttribute('aria-label'),
      hitBox: `${stopFab.offsetWidth}x${stopFab.offsetHeight}`,
      inOverlayNotFooter: !(stopFab.closest('.flow-agent-footer')),
      dangerColor: getComputedStyle(stopFab).color === errColor,
      hasConvertedIcon: !!stopFab.querySelector('svg'),
    } : null,
    footer: footer ? { visible: vis(footer), display: getComputedStyle(footer).display } : null,
    faTaskVisible: taskEl ? vis(taskEl) : false,
    sheet: sheet ? {
      hidden: sheet.hidden, visible: vis(sheet),
      metaRows: [...(sheet.querySelectorAll('.bgagent-meta-row') || [])].map((r) => r.textContent.trim()),
      title: (sheet.querySelector('.bgagent-sheet-title') || {}).textContent || '',
      taskText: (sheet.querySelector('.bgagent-strip-tasktext') || {}).textContent || '',
      taskTextHidden: (sheet.querySelector('.bgagent-strip-tasktext') || { hidden: true }).hidden,
      stripBodyHidden: (sheet.querySelector('.bgagent-strip-body') || { hidden: true }).hidden,
      ariaExpanded: (sheet.querySelector('.bgagent-strip-toggle') || {}).getAttribute
        ? sheet.querySelector('.bgagent-strip-toggle').getAttribute('aria-expanded') : null,
      stripInSheet: !!stripInSheet, wsInSheet: !!wsInSheet,
      nfGlass: !!(sheet.querySelector('.bgagent-sheet-card') && sheet.querySelector('.bgagent-sheet-card').classList.contains('nf-glass')),
    } : null,
    stripInModal: stripInModal || 0,
    wsInModal: wsInModal || 0,
    sessionPresent: !!document.querySelector('.flow-agent-overlay .flow-agent-chat'),
  };
};

async function run() {
  const pwRes = loadPlaywright();
  const pw = pwRes.mod.playwright || pwRes.mod;
  const { browser, label } = await launchBrowser(pw);
  console.log(`[harness] browser=${label} root=${WEB_ROOT} tag=${TAG}`);
  const report = { tag: TAG, webRoot: WEB_ROOT, browser: label, runs: [], shots: [], checks: [], failures: 0 };
  let firstMobile = null;
  let firstDesktop = null;

  for (const [w, h, vp] of VIEWS) {
    for (const theme of ['light', 'dark']) {
      const s = await createSession(browser, {
        width: w, height: h, colorScheme: theme, clock: CLOCK,
        apiStubs: {
          // G4 聚合单查桩：最长前缀命中（/api/delegates 覆盖 /api/delegates/:id）。
          '/api/delegates': { body: DELEGATE_DETAIL },
        },
      });
      await s.boot({ config: { llm: { providers: {} }, features: {} }, sessionId: ROOT_SID });
      // getHistory 应答器：监视真实发送队列，弹窗一发 getHistory 就回持久注入行
      // （走真实 ws.js 分发路径，不直写 DOM）。
      await s.page.evaluate(([sid, msgs]) => {
        window.__histReplies = 0;
        setInterval(() => {
          const sent = window.__wsSent || [];
          const pend = sent.filter((f) => f.type === 'getHistory' && f.sessionId === sid);
          if (pend.length > window.__histReplies) {
            window.__histReplies = pend.length;
            window.__wsMock.onmessage({ data: JSON.stringify({
              type: 'historyPage', sessionId: sid, messages: msgs,
              total: msgs.length, offset: 0, hasMore: false,
            }) });
          }
        }, 40);
      }, [SID, HISTORY_ROWS]);

      // 快照帧（open 之前到齐 ⇒ openStepPopup 的快照种子行吃到 status/startedAt/kind）
      await s.frame({
        type: 'activeAgents',
        agents: [{
          rootSessionId: ROOT_SID, sessionId: SID, agentId: SID,
          name: AGENT_NAME, task: TASK_TITLE, kind: 'Delegate',
          status: 'Processing', startedAt: CLOCK - UPTIME_MS, retryCount: 0,
        }],
      });
      await settle(s.page, 120);

      // 真实模块入口开弹窗（不直写 DOM）
      await s.page.evaluate(async ([sid, name, task]) => {
        const m = await import('/js/bgAgentPopup.js');
        m.openStepPopup(sid, name, task);
      }, [SID, AGENT_NAME, TASK_TITLE]);
      // 详情 fetch + 摘要条回填（历史回执）到位
      await s.page.waitForTimeout(600);
      await settle(s.page, 200);

      const shot = (state) => {
        const p = join(OUT, `${TAG}-${vp}-${theme}-${state}.png`);
        return s.page.screenshot({ path: p, animations: 'disabled', caret: 'hide', scale: 'css' })
          .then(() => report.shots.push(p));
      };

      // 指纹钉：页脚运行点的 fa-pulse 是无限动画，`animations:'disabled'` 取消时机
      // 依赖节拍 ⇒ 改前/改后两跑各采到不同 alpha 帧（实测 31 字节差）。两跑注入
      // 同一条样式钉死为静止帧 —— 只动采集装置，不动产品。
      await s.page.addStyleTag({ content: '.flow-agent-overlay .fa-status-dot{animation:none !important}' });

      const m = await s.page.evaluate(IN_PAGE_MEASURE, SID);
      await shot('main');

      // M4 开合腿（真点击 ⋯ 键 → scrim 收）——截图 sheet 态在开着的瞬间拍。
      let sheetToggle = null;
      if (m.more && m.more.visible) {
        const r = m.more.rect;
        await s.page.mouse.click(r.x + r.width / 2, r.y + r.height / 2);
        await s.page.waitForTimeout(250);
        const opened = await s.page.evaluate(IN_PAGE_MEASURE, SID);
        await shot('sheet');
        const scrim = await s.page.evaluate(() => {
          const sc = document.querySelector('.bgagent-overlay .bgagent-sheet-scrim');
          if (!sc) return { clicked: false };
          const r = sc.getBoundingClientRect();
          return { clicked: true, rect: r.toJSON() };
        });
        if (scrim.clicked) {
          await s.page.mouse.click(scrim.rect.x + Math.min(scrim.rect.width / 2, 8), scrim.rect.y + Math.min(scrim.rect.height / 2, 8));
          await s.page.waitForTimeout(200);
        }
        const closedAgain = await s.page.evaluate(() => {
          const sh = document.querySelector('.bgagent-overlay .bgagent-more-sheet');
          return sh ? sh.hidden : null;
        });
        sheetToggle = { opened: opened.sheet, scrimClosed: closedAgain === true, scrim };
        await shot('sheet-closed');
      }

      // M6 终止帧腿（真鼠标点悬浮钮命中盒中心；放本跑最后，免污染截图）
      let stopClick = null;
      if (m.stopFab && m.stopFab.visible) {
        const sentBefore = await s.page.evaluate(() => (window.__wsSent || []).filter((f) => f.type === 'cancelAgent').length);
        const r = m.stopFab.rect; // pick() 形 rect：{x,y,w,h}
        await s.page.mouse.click(r.x + r.w / 2, r.y + r.h / 2);
        await s.page.waitForTimeout(250);
        const sentAfter = await s.page.evaluate(() => (window.__wsSent || []).filter((f) => f.type === 'cancelAgent').length);
        stopClick = { delta: sentAfter - sentBefore };
      }

      // M1 返回钮腿（真点击左上返回 = 弹窗关闭）——本跑收尾。
      let backClick = null;
      if (m.back && m.back.present) {
        const r = m.back.rect;
        await s.page.mouse.click(r.x + r.width / 2, r.y + r.height / 2);
        await s.page.waitForTimeout(250);
        backClick = { overlayClosedAfterClick: !(await s.page.evaluate(() => !!document.querySelector('.bgagent-overlay'))) };
      }

      report.runs.push({ viewport: vp, theme, measure: m, sheetToggle, stopClick, backClick });
      if (!firstMobile && vp === '375') firstMobile = report.runs[report.runs.length - 1];
      if (!firstDesktop && vp === '1440') firstDesktop = report.runs[report.runs.length - 1];
      await s.close();
    }
  }

  // ── 判据 ────────────────────────────────────────────────────────────
  const check = (id, name, ok, reading) => {
    report.checks.push({ id, name, ok: !!ok, reading });
    if (!ok) report.failures += 1;
    console.log(`${ok ? 'PASS' : 'FAIL'} ${id} ${name} — ${reading}`);
  };

  const fm = firstMobile.measure;
  const st = firstMobile.sheetToggle;

  check('M1', '顶栏无重复关闭件（✕=0，返回钮在场且可关）',
    fm.closeX.count === 0 && !fm.closeX.visible && !!fm.back.present && fm.back.visible
      && firstMobile.backClick && firstMobile.backClick.overlayClosedAfterClick === true,
    `closeX=${fm.closeX.count}(vis=${fm.closeX.visible}) back=${fm.back.present}(vis=${fm.back.visible}) 点击关闭=${firstMobile.backClick && firstMobile.backClick.overlayClosedAfterClick}`);

  check('M2', '状态胶囊 = 状态词面 + 时长，不含任务标题（顶带内）',
    !!fm.pill && fm.pill.visible
      && fm.pill.text.includes('运行中') && /\b7m\b/.test(fm.pill.text)
      && !fm.pill.text.includes('FEE 巡检第三次复测')
      && !fm.pill.text.includes(TASK_TITLE)
      && fm.pill.rect.y < 64 && fm.pill.rect.h <= 32,
    `text="${fm.pill ? fm.pill.text : '-'}" y=${fm.pill ? fm.pill.rect.y : '-'} h=${fm.pill ? fm.pill.rect.h : '-'}`);

  check('M2b', 'name above status in same heading',
    !!fm.heading && !!fm.heading.text && fm.heading.nested && fm.heading.title.bottom <= fm.heading.pill.top,
    `heading=${JSON.stringify(fm.heading)}`);

  check('M3', '终止钮走悬浮圆钮类且命中盒 ≥40（右下离边、玻璃、危险档、图标已转换）',
    !!fm.stopFab && fm.stopFab.visible && fm.stopFab.inOverlayNotFooter
      && /\bg2-fab\b/.test(fm.stopFab.classes)
      && fm.stopFab.hitBox && parseInt(fm.stopFab.hitBox) >= 40
      && fm.stopFab.backdropFilter && fm.stopFab.backdropFilter !== 'none'
      && fm.stopFab.dangerColor === true
      && fm.stopFab.hasConvertedIcon === true
      && (fm.stopFab.rect.x + fm.stopFab.rect.w) > 375 - 20
      && (fm.stopFab.rect.y + fm.stopFab.rect.h) > 812 - 30,
    `classes="${fm.stopFab ? fm.stopFab.classes : '-'}" hit=${fm.stopFab ? fm.stopFab.hitBox : '-'} color=${fm.stopFab ? fm.stopFab.color : '-'} danger=${fm.stopFab ? fm.stopFab.dangerColor : '-'} icon=${fm.stopFab ? fm.stopFab.hasConvertedIcon : '-'} right=${fm.stopFab ? (fm.stopFab.rect.x + fm.stopFab.rect.w).toFixed(1) : '-'} bottom=${fm.stopFab ? (fm.stopFab.rect.y + fm.stopFab.rect.h).toFixed(1) : '-'} bf=${fm.stopFab ? fm.stopFab.backdropFilter : '-'}`);

  check('M4', '⋯ 详情浮层可开合且含来源、创建与任务内容（毛玻璃卡）',
    !!st && !!st.opened && st.opened.visible === true && st.opened.hidden === false
      && st.opened.nfGlass === true
      && st.opened.metaRows.some((r) => r.includes('来源'))
      && st.opened.metaRows.some((r) => r.includes('创建'))
      && st.opened.taskText.includes('第三次 FEE 巡检复测')
      && st.opened.taskTextHidden === false && st.opened.stripBodyHidden === false
      && st.scrimClosed === true,
    `meta=[${st && st.opened ? st.opened.metaRows.join(' | ') : '-'}] taskFilled=${st && st.opened ? st.opened.taskText.includes('第三次 FEE 巡检复测') : '-'} scrimClosed=${st ? st.scrimClosed : '-'} glass=${st && st.opened ? st.opened.nfGlass : '-'}`);

  check('M5', '底部简化：footer 不可见、任务标题不在可见面、灰行只在详情内',
    !!fm.footer && fm.footer.visible === false
      && fm.faTaskVisible === false
      && (fm.wsInModal === 0) && !!(st && st.opened && st.opened.wsInSheet),
    `footerVis=${fm.footer ? fm.footer.visible : '-'} faTaskVis=${fm.faTaskVisible} wsInModal=${fm.wsInModal} wsInSheet=${st && st.opened ? st.opened.wsInSheet : '-'} stripInModal=${fm.stripInModal}`);

  check('M6', '真点击悬浮终止钮 → 发 cancelAgent 终止帧',
    !!firstMobile.stopClick && firstMobile.stopClick.delta === 1,
    `cancelAgent 增量=${firstMobile.stopClick ? firstMobile.stopClick.delta : '-'}（改前形态：行内小方块，无悬浮钮）`);

  // 桌面零变化（取 1440-light 跑）
  const fd = firstDesktop.measure;
  check('D1', '桌面零变化：✕ 在场、胶囊/⋯/悬浮钮/浮层全缺、footer 在场、条在原位',
    !!fd.overlayPresent && fd.closeX.count === 1 && fd.closeX.visible
      && !fd.pill && !fd.more && !fd.stopFab && !fd.sheet
      && fd.footer && fd.footer.visible === true && fd.faTaskVisible === true
      && fd.stripInModal === 1 && fd.wsInModal === 1,
    `closeX=${fd.closeX.count} pill=${!!fd.pill} more=${!!fd.more} stopFab=${!!fd.stopFab} sheet=${!!fd.sheet} footerVis=${fd.footer ? fd.footer.visible : '-'} stripInModal=${fd.stripInModal} wsInModal=${fd.wsInModal}`);

  // 桌面零像素变化对照：1440-light 改前/改后 PNG 指纹（同机同浏览器可比）
  report.desktopSha = {};
  for (const f of report.shots) {
    if (/-1440-light-main\.png$/.test(f)) {
      report.desktopSha[TAG] = createHash('sha256').update(readFileSync(f)).digest('hex').slice(0, 16);
    }
  }

  writeFileSync(join(OUT, `${TAG}.json`), JSON.stringify(report, null, 2));
  console.log(`\n[${TAG}] ${report.failures === 0 ? 'ALL GREEN' : `${report.failures} FAIL`} → ${join(OUT, `${TAG}.json`)}（shots ${report.shots.length} 张）`);
  await browser.close();
  process.exit(report.failures === 0 ? 0 : 1);
}

run().catch((e) => { console.error(e); process.exit(2); });

#!/usr/bin/env node
// tests/bgagent-header-window.spec.mjs — delegate 会话窗口头部整改批（2026-11-07）
// 机检 + 读数 + 实拍（真渲染；零端口静态装置，visual-harness 同族）。
//
// 作者令（2026-11-07，附实拍 1201662819758583_image.jpg）四条，逐条对应本脚本的判据：
//   ① 「顶部的任务可以不显示，因为蓝色气泡里已经包含了」
//      → H1 桌面弹窗头下无任务条（无「任务」chip / 无 briefline / 无 tasktext；
//        在飞期整条缺席 = 顶部不留空行）
//      → H2 蓝色注入气泡仍是任务全权承载面（折叠=一句话摘要；点开=逐字全文，
//        含 [workspace] 行与尾行 —— 「不删除任务数据」的可达性证据）
//   ② 「选择模式的下拉有bug」
//      → H3 关一次再开，下拉仍是满表且选中当前生效值（改前实测：options=[] ⇒
//        下拉点开是空的，会话切不了模型）
//      → H4 真选一项 → 发 setSessionModel 帧（modelRef 逐字）
//   ③ 「不美观」
//      → H5 头部只出现一次模型串（改前实测：徽标 + 下拉各印一遍同一串）
//      → H6 下拉已收编输入面唯一标准（裸件 + 下拉档：无自画底/边/圆角/影，
//        appearance:none + 标准箭头 --nf-field-caret）
//   ④ 「窗口关闭按钮应该靠右」+ 窄宽/亮暗/键盘无障碍
//      → H7 关闭键贴头部右缘（右隙 = 头部内距）；窄宽 800 不溢出；亮暗同几何
//      → H8 关闭键是 <button>（Tab 可达 + 键盘焦点环在场 + Enter 可关 + 可访问名）
//   H9 有判词时桌面条 = 结果条（任务面仍缺席）
//   D1 手机层零回归（2026-11-06 六条口径不改）：标题行无 ✕、⋯ 详情里任务全文仍在
//
// 隔离：静态根走 page.route 从磁盘服务 + MockWS 打桩（visual-harness），
// 不起服务、不开端口、不碰宿主实例 6138，也不触发任何发布腿。
//
// 用法（改前红 / 改后绿 双跑）：
//   node tests/bgagent-header-window.spec.mjs --web-root src/main/resources/web --tag after
//   node tests/bgagent-header-window.spec.mjs --web-root /tmp/hdr-before/web --tag before
// 产出：<out>/<tag>-<viewport>-<theme>-<state>.png + <out>/<tag>.json；任一 FAIL ⇒ exit 1。

import { mkdirSync, writeFileSync } from 'node:fs';
import { join, isAbsolute } from 'node:path';
import { ROOT, loadPlaywright, launchBrowser, createSession, settle, setWebRoot } from '../scripts/lib/visual-harness.mjs';

const argv = process.argv.slice(2);
const valOf = (f, d = null) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const WEB_ROOT = valOf('--web-root', 'src/main/resources/web');
const TAG = valOf('--tag', 'after');
const OUT = valOf('--out', 'reports-evidence/bgagent-header-20261107');

setWebRoot(isAbsolute(WEB_ROOT) ? WEB_ROOT : join(ROOT, WEB_ROOT));
mkdirSync(OUT, { recursive: true });

const SID = 'delegate-hdr-1';
const ROOT_SID = 'visual-root';
const AGENT_NAME = 'Beta发布';
const MODEL_REF = 'Irical/DeepSeek-V4.1-Flash-1017';
const FALLBACK_MODEL = 'Irical/claude-sonnet-4.5';
const CLOCK = 1793940000000;
// 派发 brief 逐字（含作者实拍顶部条里那行 [workspace]）
const TASK_BRIEF = '发布新的 beta 版：按 runbook 执行 freeze → VERSION bump → build → boot-smoke → tests/gates → merge → tag/publish。\n'
  + '[workspace] /Users/kaiyu/Claude code/Stand/.nebflow/worktrees/beta-release-nov (file tools need absolute paths; Bash cwd is not guaranteed)\n'
  + '尾行哨兵：这一段是刻意加长的任务正文，用来验证顶部任务条退役后蓝色气泡仍能给出完整任务。';
const TASK_TITLE = '发布新的 beta 版';
const WS_LINE = '[workspace] /Users/kaiyu/Claude code/Stand/.nebflow/worktrees/beta-release-nov';

const HISTORY_ROWS = [
  { type: 'user', injected: true, source: 'delegate', eventType: 'dispatch', sender: 'Nebula',
    header: 'DELEGATE · NEBFLOW · TASK', timestamp: CLOCK, text: TASK_BRIEF, attachments: [] },
];

const detailStub = (verdict, reportDetail) => ({
  record: {
    id: SID, executor: 'claude', project: 'stand', taskId: '12', parentSessionId: ROOT_SID,
    title: TASK_TITLE, createdAt: CLOCK, name: AGENT_NAME,
    branch: 'delegate/beta-release-nov',
    worktree: '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/beta-release-nov',
    plugins: ['wiki-docs', 'visual-report'], reportDetail,
  },
  live: { status: 'running' }, verdict, task: null, childrenCount: 0,
});

/** 页内一次性取全读数（改前树安全返回可读的「缺件」而非抛错）。 */
const IN_PAGE_MEASURE = (modelRef) => {
  const q = (s) => document.querySelector(s);
  const vis = (el) => !!el && el.getClientRects().length > 0;
  const rect = (el) => { if (!el) return null; const r = el.getBoundingClientRect(); return { x: +r.x.toFixed(2), y: +r.y.toFixed(2), w: +r.width.toFixed(2), h: +r.height.toFixed(2), right: +r.right.toFixed(2), bottom: +r.bottom.toFixed(2) }; };
  const cs = (el, props) => { if (!el) return null; const c = getComputedStyle(el); const o = {}; for (const p of props) o[p] = c.getPropertyValue(p); return o; };
  const overlay = q('.bgagent-overlay, .flow-agent-overlay.fullscreen');
  const header = q('.flow-agent-header');
  const nameEl = q('.flow-agent-name');
  const badge = q('#bgagent-model');
  const sel = q('#bgagent-model-switch');
  const close = q('#bgagent-close');
  const strip = q('.bgagent-delegate-strip');
  const bubble = q('.bubble.injected');
  const bubbleContent = bubble && bubble.querySelector('.injected-content');
  const taskEl = strip && strip.querySelector('.bgagent-strip-tasktext');
  const chip = strip && strip.querySelector('.bgagent-strip-chip');
  return {
    mobileLayer: document.body.classList.contains('mobile-layer'),
    overlayPresent: !!overlay,
    header: rect(header),
    headerScroll: header ? { sw: header.scrollWidth, cw: header.clientWidth } : null,
    headerText: header ? header.textContent.replace(/\s+/g, ' ').trim() : '',
    name: nameEl ? { ...rect(nameEl), text: nameEl.textContent } : null,
    badge: badge ? { ...rect(badge), hidden: badge.hidden, text: badge.textContent } : null,
    sel: sel ? {
      ...rect(sel), value: sel.value,
      options: [...sel.options].map((o) => ({ v: o.value, t: o.textContent, s: o.selected })),
      style: (() => {
        const c = getComputedStyle(sel);
        return {
          appearance: c.getPropertyValue('appearance') || c.getPropertyValue('-webkit-appearance'),
          backgroundImage: c.getPropertyValue('background-image'),
          borderTopStyle: c.getPropertyValue('border-top-style'),
          borderTopWidth: c.getPropertyValue('border-top-width'),
          borderRadius: c.getPropertyValue('border-radius'),
          boxShadow: c.getPropertyValue('box-shadow'),
          paddingRight: c.getPropertyValue('padding-right'),
          fontSize: c.getPropertyValue('font-size'),
          color: c.getPropertyValue('color'),
          height: c.getPropertyValue('height'),
        };
      })(),
    } : null,
    close: close ? {
      ...rect(close), tag: close.tagName, ariaLabel: close.getAttribute('aria-label'),
      tabIndex: close.tabIndex, style: cs(close, ['outline-style', 'outline-width', 'outline-color', 'cursor']),
    } : null,
    closeCount: overlay ? overlay.querySelectorAll('.flow-agent-close').length : 0,
    strip: strip ? {
      rect: rect(strip), hidden: strip.hidden, text: strip.innerText.replace(/\s+/g, ' ').trim(),
      chipText: chip ? chip.textContent : null,
      hasTaskChip: !!(chip && chip.textContent === '任务'),
      hasBriefline: !!strip.querySelector('.bgagent-strip-briefline'),
      hasTaskText: !!taskEl,
      taskText: taskEl ? taskEl.textContent : '',
      resultWord: (strip.querySelector('.bgagent-strip-result') || {}).textContent || '',
      bodyHidden: (strip.querySelector('.bgagent-strip-body') || { hidden: true }).hidden,
      reportText: (strip.querySelector('.bgagent-strip-report') || {}).textContent || '',
      verdictText: (strip.querySelector('.bgagent-strip-verdict') || {}).textContent || '',
      ariaExpanded: (strip.querySelector('.bgagent-strip-toggle') || {}).getAttribute
        ? strip.querySelector('.bgagent-strip-toggle').getAttribute('aria-expanded') : null,
    } : null,
    bubble: bubble ? {
      visible: vis(bubble),
      summary: (bubble.querySelector('.nf-inject-summary, .nf-rcpt-summary-text') || {}).textContent || '',
      contentDisplay: bubbleContent ? getComputedStyle(bubbleContent).display : null,
      contentText: bubbleContent ? bubbleContent.textContent : '',
      hasWorkspaceLine: bubbleContent ? bubbleContent.textContent.includes('[workspace] /Users/kaiyu/Claude code/Stand/.nebflow/worktrees/beta-release-nov') : false,
      hasTailLine: bubbleContent ? bubbleContent.textContent.includes('尾行哨兵') : false,
    } : null,
    // F 批（ui-unify-panels 2026-11-17）后头部模型串有两处 DOM 印记：可见 trigger 脸
    // （.nf-select-val）+ 隐藏原生承载 select 的 options 文本。可见印刷仍只一次 ——
    // 计数折去 select 子树，断言意图（徽标隐藏、不重复印串）不动。
    modelOccurrencesInHeader: header
      ? (header.textContent.split(modelRef).length - 1) - (sel ? sel.textContent.split(modelRef).length - 1 : 0)
      : 0,
  };
};

const measure = (s) => s.page.evaluate(IN_PAGE_MEASURE, MODEL_REF);

/** 打开弹窗（真实模块入口）。 */
async function openPopup(s, taskTitle = TASK_TITLE) {
  await s.frame({
    type: 'activeAgents',
    agents: [{
      rootSessionId: ROOT_SID, sessionId: SID, agentId: SID, name: AGENT_NAME,
      task: taskTitle, kind: 'Delegate', status: 'Processing',
      startedAt: CLOCK - 54 * 60 * 1000, retryCount: 0,
    }],
  });
  await settle(s.page, 120);
  await s.page.evaluate(async ([sid, name, task]) => {
    const m = await import('/js/bgAgentPopup.js');
    m.openStepPopup(sid, name, task);
  }, [SID, AGENT_NAME, taskTitle]);
  // G4 详情单查 + 历史回执都要到齐（采集与截图必须看同一帧：改前实测 800ms 时
  // 结果条尚未回来，读数与 PNG 会打架）
  await s.page.waitForTimeout(1500);
  await settle(s.page, 250);
}

async function run() {
  const pwRes = loadPlaywright();
  const pw = pwRes.mod.playwright || pwRes.mod;
  const { browser, label } = await launchBrowser(pw);
  console.log(`[harness] browser=${label} root=${WEB_ROOT} tag=${TAG}`);
  const report = { tag: TAG, webRoot: WEB_ROOT, browser: label, checks: [], runs: [], shots: [], failures: 0 };

  const VIEWS = [
    { vp: '1440', width: 1440, height: 900, theme: 'light', state: 'inflight' },
    { vp: '1440', width: 1440, height: 900, theme: 'dark', state: 'inflight' },
    { vp: '800', width: 800, height: 800, theme: 'light', state: 'inflight' },
    { vp: '1440', width: 1440, height: 900, theme: 'light', state: 'done' },
    { vp: '375', width: 375, height: 812, theme: 'light', state: 'inflight' },
  ];

  for (const v of VIEWS) {
    const done = v.state === 'done';
    const s = await createSession(browser, {
      width: v.width, height: v.height, colorScheme: v.theme, clock: CLOCK,
      apiStubs: {
        '/api/agents': { body: { current: MODEL_REF, preferred: MODEL_REF } },
        '/api/delegates': {
          body: done
            ? detailStub({ verdict: 'completed', reason: '合并轮完成' }, 'release: VERSION 已 bump，闸全绿。')
            : detailStub(null, ''),
        },
      },
    });
    await s.boot({ sessionId: ROOT_SID, messages: [] });
    // 模型选项（H3/H4 需要满表；走真实 modelOptions 分发路径）
    await s.frame({
      type: 'modelOptions',
      models: [MODEL_REF, FALLBACK_MODEL, 'OpenRouter/deepseek-v3.2'].map((ref) => ({ ref })),
    });
    // getHistory 应答器：弹窗一发 getHistory 就回注入行（走真实 ws.js 分发，不直写 DOM）
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
    await settle(s.page, 120);

    await openPopup(s);
    const shot = async (phase) => {
      const p = join(OUT, `${TAG}-${v.vp}-${v.theme}-${v.state}-${phase}.png`);
      await s.page.screenshot({ path: p, animations: 'disabled', caret: 'hide', scale: 'css' });
      report.shots.push(p);
    };
    // 采集装置钉：运行点脉冲是无限动画 ⇒ 固定帧（不动产品）
    await s.page.addStyleTag({ content: '.flow-agent-overlay .fa-status-dot{animation:none !important}' });

    const inflight = await measure(s);
    await shot('main');

    // 蓝色气泡展开腿（真点击卡头 → 正文 display 由 none 变 block）
    let bubbleExpanded = null;
    if (inflight.bubble && inflight.bubble.visible && inflight.bubble.contentDisplay === 'none') {
      const target = await s.page.evaluate(() => {
        const b = document.querySelector('.bubble.injected');
        const t = b && (b.querySelector('.nf-rcpt-top') || b.querySelector('.injected-source-label'));
        if (!t) return null;
        const r = t.getBoundingClientRect();
        return { x: r.x + 12, y: r.y + r.height / 2 };
      });
      if (target) {
        await s.page.mouse.click(target.x, target.y);
        await s.page.waitForTimeout(250);
        bubbleExpanded = await measure(s);
        await shot('bubble-open');
      }
    }

    // 结果条展开腿（有判词的会话）：点开 → 报告全文可达（「不删除数据」在结果面的证据）
    let stripOpen = null;
    if (inflight.strip && inflight.strip.hidden === false) {
      const t = await s.page.evaluate(() => {
        const b = document.querySelector('.bgagent-delegate-strip .bgagent-strip-toggle');
        if (!b) return null;
        const r = b.getBoundingClientRect();
        return { x: r.x + 24, y: r.y + r.height / 2 };
      });
      if (t) {
        await s.page.mouse.click(t.x, t.y);
        await s.page.waitForTimeout(200);
        stripOpen = (await measure(s)).strip;
        await shot('strip-open');
      }
    }

    let reopen = null;
    let setModelFrame = null;
    let keyboard = null;
    let closeByEnter = null;
    if (!inflight.mobileLayer && inflight.close) {
      // H8 键盘腿：先把焦点放到下拉（关闭键的 DOM 前驱），再真按 Tab →
      // 键盘模态下的焦点环才出现（脚本 focus() 不产生 :focus-visible）
      keyboard = await s.page.evaluate(() => {
        const btn = document.getElementById('bgagent-close');
        if (!btn) return { found: false };
        const sel = document.getElementById('bgagent-model-switch');
        if (sel) sel.focus();
        return { found: true, tag: btn.tagName, ariaLabel: btn.getAttribute('aria-label'), tabIndex: btn.tabIndex };
      });
      await s.page.keyboard.press('Tab');
      keyboard.focusedByTab = await s.page.evaluate(() => document.activeElement && document.activeElement.id);
      if (keyboard.focusedByTab !== 'bgagent-close') {
        for (let i = 0; i < 24 && keyboard.focusedByTab !== 'bgagent-close'; i++) {
          await s.page.keyboard.press('Tab');
          keyboard.focusedByTab = await s.page.evaluate(() => document.activeElement && document.activeElement.id);
        }
      }
      keyboard.focusStyle = await s.page.evaluate(() => {
        const el = document.activeElement;
        if (!el) return null;
        const c = getComputedStyle(el);
        return { outlineStyle: c.outlineStyle, outlineWidth: c.outlineWidth, outlineColor: c.outlineColor };
      });
      await s.page.keyboard.press('Enter');
      await s.page.waitForTimeout(250);
      closeByEnter = await s.page.evaluate(() => !document.querySelector('.bgagent-overlay'));

      // 再开 → 下拉是否仍是满表（改前 bug 的验红点）
      await s.page.evaluate(async ([sid, name, task]) => {
        const m = await import('/js/bgAgentPopup.js');
        m.openStepPopup(sid, name, task);
      }, [SID, AGENT_NAME, TASK_TITLE]);
      await s.page.waitForTimeout(1500);
      await settle(s.page, 250);
      reopen = await measure(s);
      await shot('reopen');

      // H4 改值发帧
      setModelFrame = await s.page.evaluate(async ([ref]) => {
        const sel = document.getElementById('bgagent-model-switch');
        if (!sel) return null;
        const before = (window.__wsSent || []).filter((f) => f.type === 'setSessionModel').length;
        sel.value = ref;
        sel.dispatchEvent(new Event('change'));
        await new Promise((r) => setTimeout(r, 150));
        const sent = (window.__wsSent || []).filter((f) => f.type === 'setSessionModel');
        return { before, after: sent.length, last: sent[sent.length - 1] || null };
      }, [FALLBACK_MODEL]);
    }

    report.runs.push({
      viewport: v.vp, theme: v.theme, state: v.state,
      inflight, bubbleExpanded, stripOpen, reopen, setModelFrame, keyboard, closeByEnter,
      pageErrors: s.errors.slice(),
    });
    await s.close();
  }

  const find = (vp, theme, state) => report.runs.find((r) => r.viewport === vp && r.theme === theme && r.state === state) || null;
  const light = find('1440', 'light', 'inflight');
  const dark = find('1440', 'dark', 'inflight');
  const narrow = find('800', 'light', 'inflight');
  const doneRun = find('1440', 'light', 'done');
  const mobile = find('375', 'light', 'inflight');

  const check = (id, name, ok, reading) => {
    report.checks.push({ id, name, ok: !!ok, reading });
    if (!ok) report.failures += 1;
    console.log(`${ok ? 'PASS' : 'FAIL'} ${id} ${name} — ${reading}`);
  };

  const L = light && light.inflight;
  const exp = light && light.bubbleExpanded && light.bubbleExpanded.bubble;
  const ro = light && light.reopen && light.reopen.sel;

  check('H1', '桌面弹窗头下不再有任务条（无任务 chip / 无 briefline / 无 tasktext；在飞整条缺席）',
    !!L && !!L.strip && L.strip.hidden === true && L.strip.hasTaskChip === false
      && L.strip.hasBriefline === false && L.strip.hasTaskText === false,
    `strip=${L && L.strip ? `hidden=${L.strip.hidden} chip=${L.strip.chipText} briefline=${L.strip.hasBriefline} tasktext=${L.strip.hasTaskText}` : 'absent'}`);

  check('H2', '蓝色注入气泡仍是任务全权承载面（折叠摘要在场 → 点开 = 逐字全文含 [workspace] 行与尾行）',
    !!L && !!L.bubble && L.bubble.visible && L.bubble.summary.length > 0
      && L.bubble.contentDisplay === 'none'
      && !!exp && exp.contentDisplay === 'block' && exp.hasWorkspaceLine === true && exp.hasTailLine === true,
    `summary="${L && L.bubble ? L.bubble.summary.slice(0, 26) : '-'}…" 折叠=${L && L.bubble ? L.bubble.contentDisplay : '-'} 展开=${exp ? exp.contentDisplay : '-'} workspace行=${exp ? exp.hasWorkspaceLine : '-'} 尾行=${exp ? exp.hasTailLine : '-'}`);

  check('H3', '关一次再开，模型下拉仍满表且选中当前生效值（改前：options=[]）',
    !!ro && ro.options.length >= 2 && ro.value === MODEL_REF && ro.options.some((o) => o.s),
    `options=${ro ? ro.options.length : 0} value="${ro ? ro.value : '-'}" selected=${ro ? ro.options.filter((o) => o.s).map((o) => o.t).join('|') : '-'}`);

  check('H4', '真选一项 → 发 setSessionModel 帧（modelRef 逐字 = 选中项）',
    !!light && !!light.setModelFrame && light.setModelFrame.after === light.setModelFrame.before + 1
      && light.setModelFrame.last && light.setModelFrame.last.type === 'setSessionModel'
      && light.setModelFrame.last.modelRef === FALLBACK_MODEL && light.setModelFrame.last.sessionId === SID,
    `delta=${light && light.setModelFrame ? light.setModelFrame.after - light.setModelFrame.before : '-'} frame=${light && light.setModelFrame && light.setModelFrame.last ? JSON.stringify(light.setModelFrame.last) : '-'}`);

  check('H5', '头部只出现一次模型串（徽标与下拉不再重复印同一串）',
    !!L && L.modelOccurrencesInHeader === 1 && !!L.badge && L.badge.hidden === true,
    `headOccurrences=${L ? L.modelOccurrencesInHeader : '-'} badgeHidden=${L && L.badge ? L.badge.hidden : '-'} badgeText="${L && L.badge ? L.badge.text : '-'}"`);

  const selStyle = L && L.sel && L.sel.style;
  check('H6', '模型下拉已收编输入面唯一标准（无自画底/边/圆角/影 + 标准箭头 --nf-field-caret）',
    !!L && !!L.sel && selStyle.appearance === 'none' && selStyle.borderTopStyle === 'none'
      && selStyle.boxShadow === 'none' && selStyle.borderRadius === '0px'
      && selStyle.backgroundImage !== 'none' && /gradient/.test(String(selStyle.backgroundImage))
      && +String(selStyle.paddingRight).replace('px', '') >= 20,
    `appearance=${selStyle && selStyle.appearance} borderStyle=${selStyle && selStyle.borderTopStyle} shadow=${selStyle && selStyle.boxShadow} radius=${selStyle && selStyle.borderRadius} caret=${selStyle ? String(selStyle.backgroundImage).slice(0, 30) : '-'}… padRight=${selStyle && selStyle.paddingRight}`);

  const rightGap = (m) => (m && m.header && m.close ? +(m.header.right - m.close.right).toFixed(2) : null);
  const nIn = narrow && narrow.inflight;
  const narrowOk = !!nIn && !!nIn.header && !!nIn.close
    && nIn.headerScroll.sw <= nIn.headerScroll.cw + 1
    && rightGap(nIn) !== null && rightGap(nIn) >= 15 && rightGap(nIn) <= 17
    && nIn.close.x > nIn.header.x;
  check('H7', '关闭键贴头部右缘（右隙 = 头部内距 16px ±1）；窄宽 800 同几何且头部零横向溢出；亮暗同几何',
    rightGap(L) !== null && rightGap(L) >= 15 && rightGap(L) <= 17
      && rightGap(dark && dark.inflight) === rightGap(L) && narrowOk,
    `light 右隙=${rightGap(L)} dark 右隙=${rightGap(dark && dark.inflight)} 窄宽 右隙=${rightGap(nIn)} 窄宽 scrollW=${nIn && nIn.headerScroll ? nIn.headerScroll.sw : '-'}/${nIn && nIn.headerScroll ? nIn.headerScroll.cw : '-'}`);

  const kb = light && light.keyboard;
  check('H8', '关闭键键盘可达（<button> + Tab 可达 + 键盘焦点环 + Enter 可关 + 可访问名）',
    !!kb && kb.found === true && kb.tag === 'BUTTON' && kb.tabIndex === 0
      && !!kb.ariaLabel && kb.ariaLabel.length > 0
      && kb.focusedByTab === 'bgagent-close'
      && !!kb.focusStyle && kb.focusStyle.outlineStyle === 'solid'
      && light.closeByEnter === true,
    `tag=${kb && kb.tag} tabIndex=${kb && kb.tabIndex} ariaLabel="${kb && kb.ariaLabel}" Tab命中=${kb && kb.focusedByTab} 环=${kb && kb.focusStyle ? `${kb.focusStyle.outlineStyle}/${kb.focusStyle.outlineWidth}` : '-'} Enter关闭=${light && light.closeByEnter}`);

  const D = doneRun && doneRun.inflight;
  const so = doneRun && doneRun.stripOpen;
  check('H9', '有判词时桌面条 = 结果条（chip=结果、结果词在位、任务面仍缺席；点开 = 判词+报告全文可达）',
    !!D && !!D.strip && D.strip.hidden === false && D.strip.chipText === '结果'
      && D.strip.resultWord.length > 0 && D.strip.hasTaskText === false && D.strip.hasBriefline === false
      && !!so && so.bodyHidden === false && so.reportText.includes('闸全绿') && so.hasTaskText === false,
    `hidden=${D && D.strip ? D.strip.hidden : '-'} chip=${D && D.strip ? D.strip.chipText : '-'} word=${D && D.strip ? D.strip.resultWord : '-'} tasktext=${D && D.strip ? D.strip.hasTaskText : '-'} 展开body=${so ? (so.bodyHidden ? 'hidden' : 'open') : '-'} 报告="${so ? so.reportText.slice(0, 20) : '-'}"`);


  const M = mobile && mobile.inflight;
  check('D1', '手机层零回归：标题行无 ✕ + ⋯ 详情里任务全文仍在（2026-11-06 口径③不改）',
    !!M && M.mobileLayer === true && M.closeCount === 0 && !!M.strip && M.strip.hasTaskText === true,
    `mobileLayer=${M && M.mobileLayer} ✕数=${M && M.closeCount} 详情任务全文=${M && M.strip ? M.strip.hasTaskText : '-'}（"${M && M.strip ? M.strip.taskText.slice(0, 16) : '-'}…"）`);

  const errs = report.runs.flatMap((r) => r.pageErrors || []);
  check('E0', '零页面 JS 异常', errs.length === 0, `pageErrors=${errs.length}${errs.length ? ` → ${errs[0]}` : ''}`);

  writeFileSync(join(OUT, `${TAG}.json`), JSON.stringify(report, null, 2));
  console.log(`\n[${TAG}] ${report.failures === 0 ? 'ALL GREEN' : `${report.failures} FAIL`} → ${join(OUT, `${TAG}.json`)}（shots ${report.shots.length} 张）`);
  await browser.close();
  process.exit(report.failures === 0 ? 0 : 1);
}

run().catch((e) => { console.error(e); process.exit(2); });

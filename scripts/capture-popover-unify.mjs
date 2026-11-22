#!/usr/bin/env node
// capture-popover-unify.mjs — canvas 文件说明弹窗 × 词条弹窗「同源」证据采集器
// （canvas-popup-unify 批 2026-11-22。作者令逐字：
//   「另外 canvas点文件的说明和词条的弹窗 这两个设计应该是同源的 但是点文件那个
//    现在设计的很丑 而且不是毛玻璃的 是全透明的」）
//
// 为什么要独立一支：本批要证明的是「同一份产品、同一场景、只换了静态根（CSS/JS）
// 之后，文件说明弹窗从【无模糊的全透明面 + 原生按钮】变成【与词条弹窗同族】」。
// 采集器必须**参数化静态根**（--root），否则改前改后是两次手感两套场景，对照不成立。
//
// 装置 = scripts/lib/visual-harness.mjs（仓内既有视觉基线装置：page.route 从磁盘
// 服务 web 根 + MockWS 走真实 ws.js 分发 + 真 Chromium 渲染 + settle 后拍摄）。
// 因此截图是**真渲染真 DOM**：弹窗节点由**产品自己的模块**建
// （js/documentTermCard.js showDocumentTermCard / js/termTip.js attachTermFace +
//  termTapExpand），样式来自磁盘上的真 CSS 文件，背景是 canvas 面板里真渲染的
// markdown 正文。
//
// 🔴 诚实申报（哪里是真、哪里是夹具）：
//   · 真：CSS/JS 是磁盘真文件；弹窗 DOM 是产品模块的真产出；取数走产品模块的真
//     入口（showDocumentTermCard / attachTermFace + termTapExpand）；背后正文是真
//     canvas 渲染；焦点环读数走真键盘模态的聚焦差分（与 check-focus-ring-runtime
//     P-1/P-3 同法：同一裁剪区有/无指示器逐像素差分）。
//   · 夹具：① boot 外壳走视觉基线装置的 MockWS（既有批同法）；② 触发元素
//     （.att-ref-card / .nf-wikilink-term）是手搭的同款结构 —— 真实 ref 卡由
//     js/reference.js 在消息面生成，本批的面是 canvas 引用卡，拖整套消息流进来
//     只会让背景变噪，故手搭同结构触发件；③ 词条载荷用手写 data-wiki-resolved
//     （结构 = 消解 pass 的产出形态）。
//
// 用法：
//   node scripts/capture-popover-unify.mjs --root src/main/resources/web --tag after \
//        --out <dir>
//   node scripts/capture-popover-unify.mjs --root <before-web-root> --tag before \
//        --out <dir>
// 产出：<out>/<tag>-<scene>-<theme>-<size>.png + <out>/<tag>-measure.json
// 退出码：0 = 全部采集成功；2 = 环境错误。

import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  loadPlaywright, launchBrowser, createSession, settle, setWebRoot,
} from './lib/visual-harness.mjs';
import { comparePng } from './lib/png-diff.mjs';

const argv = process.argv.slice(2);
const valOf = (f, d = null) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const ROOT_DIR = valOf('--root', 'src/main/resources/web');
const TAG = valOf('--tag', 'after');
const OUT = valOf('--out', null);
if (!OUT) { console.error('usage: --out <dir>'); process.exit(2); }
const THEMES = (valOf('--themes', 'dark,light') || '').split(',').filter(Boolean);
const SIZES = (valOf('--sizes', 'desktop,mobile') || '').split(',').filter(Boolean);

mkdirSync(OUT, { recursive: true });
const served = setWebRoot(ROOT_DIR);
console.log(`# static root = ${served}`);

const PANE_ID = 'file:/tmp/popover-unify-note.md';
const DOC_PATH = '/stand/.stand/docs/术语系统设计__20261105_141229.md';

// 背后正文必须**真的在弹窗底下**：作者要判的是「底下的字有没有被洗成颜色」，
// 只有正文伸到弹窗下面，这条判据才有意义。故整页长文，不是三行示例。
const CANVAS_MD = [
  '# 术语系统与 canvas 说明面',
  '',
  '这一段正文用来验证弹窗背后的文字是否还认得出笔画：**粗体**、`行内代码`、列表。',
  '',
  '- 词条弹窗 = 悬浮提示（tip）形态，读的是消解载荷里的定义正文',
  '- 文件说明弹窗 = 文档提要 + 状态 + 更新 + 路径 + 打开原文档',
  '',
  '> 判定：弹窗底下的大字应当只留下颜色，不留笔画。',
  '',
  ...Array.from({ length: 24 }, (_, i) =>
    `第 ${i + 1} 段：词条网络把「同一实体」收敛成一个可点击的面，点击即读定义；` +
    '文档侧则收敛成提要卡，提要卡与词条卡在同一个表面语言下并排出现时不应有第二种质感。'),
].join('\n');

const TERM_PAYLOAD = {
  term: {
    name: '术语系统',
    definition: '术语系统 = 由注册表、消解 pass 与索引块组成的词条网络。注册表登记每个词条的归属文档与定义段；消解 pass 把正文里的 [[词条]] 与裸写命中解析成可点击的面；索引块让读者能从引用处回到定义段。',
    rel: '术语系统设计__20261105_141229.md',
    doc: '术语系统设计',
  },
};

const DOC_META_PATH = '/stand/.stand/docs/收口面甲prime修复__20261121_160300.md';

/**
 * ④ 点击说明弹窗（文档元数据）三档数据形态 —— 逐档改 stub 的 body 再拍：
 *   meta   = frontmatter `summary:` 命中（正典档）
 *   empty  = 两档皆无（可读空态；批前这是「点开是空的」的那一格）
 *   legacy = 旧文档 `<!-- reader-summary -->` 完整段落（回退档）
 * 批前 JS 走词条库（/api/wiki/terms）⇒ 没有提要的文档查不到，卡只剩「暂无提要」；
 * 批后 JS 走 /api/wiki/doc，读序由服务端 WikiIndex.readerSummary 定。两支 stub 都
 * 挂上，同一份采集器才能同时服务改前/改后两个静态根。
 */
const DOC_SCENES = {
  meta: {
    doc: {
      available: true, project: 'stand', rel: '收口面甲prime修复__20261121_160300.md',
      path: DOC_META_PATH, title: '收口面甲 prime 修复',
      type: 'audit', status: 'accepted', tags: ['takeover', 'glass', 'popup'],
      updated: '2026-11-21 16:03',
      summary: '本批修掉接管毛玻璃的背景来源：幕布 blur 令牌失活导致整页压暗只剩一层薄膜，四修后暗亮两档都成立。',
      summarySource: 'frontmatter', headings: 6, links: 12,
    },
    terms: {
      count: 1, available: true,
      terms: [{
        name: '接管毛玻璃', source: 'document_summary',
        definition: '本批修掉接管毛玻璃的背景来源：幕布 blur 令牌失活导致整页压暗只剩一层薄膜，四修后暗亮两档都成立。',
        doc: {
          project: 'stand', rel: '收口面甲prime修复__20261121_160300.md', abs_path: DOC_META_PATH,
          title: '收口面甲 prime 修复', status: 'accepted', updated_at: '2026-11-21 16:03',
        },
      }],
    },
  },
  empty: {
    doc: {
      available: true, project: 'stand', rel: '未写提要的产出文档__20261122_091500.md',
      path: '/stand/.stand/docs/未写提要的产出文档__20261122_091500.md',
      title: '未写提要的产出文档', type: 'research-log', status: 'open', tags: [],
      updated: '2026-11-22 09:15', summary: null, summarySource: 'none', headings: 4, links: 3,
    },
    terms: { count: 0, available: true, terms: [] },
  },
  legacy: {
    doc: {
      available: true, project: 'stand', rel: '旧的产出文档__20261119_232517.md',
      path: '/stand/.stand/docs/旧的产出文档__20261119_232517.md',
      title: '旧的产出文档', type: 'research-log', status: 'solved', tags: ['legacy'],
      updated: '2026-11-19 23:25',
      summary: '旧文档把读者提要写在正文 `<!-- reader-summary -->` 标记之后；回退档读的是标记后的完整段落，不截半句。',
      summarySource: 'marker', headings: 5, links: 7,
    },
    terms: { count: 0, available: true, terms: [] },
  },
};

const API_STUBS = {
  '/api/wiki/doc': { body: DOC_SCENES.meta.doc },
  '/api/wiki/terms': {
    body: {
      count: 1,
      available: true,
      terms: [{
        name: '术语系统',
        source: 'document_summary',
        definition: '本文档描述术语系统的注册表、消解 pass 与索引块三重结构，以及词条视觉面的单源令牌。',
        doc: {
          project: 'stand',
          rel: '术语系统设计__20261105_141229.md',
          abs_path: DOC_PATH,
          title: '术语系统设计',
          status: 'accepted',
          updated_at: '2026-11-05 14:12',
        },
      }],
    },
  },
  '/api/wiki/term': {
    body: {
      resolved: true,
      term: { name: TERM_PAYLOAD.term.name, definition: TERM_PAYLOAD.term.definition, rel: TERM_PAYLOAD.term.rel },
    },
  },
};

const SIZES_PX = { desktop: { width: 1440, height: 900 }, mobile: { width: 390, height: 844 } };

async function openCanvas(page) {
  await page.evaluate((content) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: {
        id: 'file:/tmp/popover-unify-note.md', itemType: 'markdown', title: 'popover-unify-note.md',
        content, absPath: '/tmp/popover-unify-note.md', size: content.length, mtimeMs: 1757000000000,
      },
    }));
  }, CANVAS_MD);
  // 桌面 = pane 在 #canvas-panel 里；手机 = pane 被收编进 mobile 页（#canvas-panel
  // 自身 display:none、页面未落页前 visibility:hidden ⇒ 先等挂载，落页后再等可见）。
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="${PANE_ID}"]`, { state: 'attached', timeout: 20000 });
  await page.waitForTimeout(900);
  // 采集噪声清除：MockWS 不答「席位/已保存」帧 ⇒ 产品按真失败路径弹一次
  // 「Canvas 保存失败」toast。它不在本批判定的面上（且真实例里存盘成功不会出现），
  // 拍前移除，避免四格里都飘着一块与本批无关的通知。
  await page.evaluate(() => {
    document.querySelectorAll('.nebflow-toast, #notification-banner').forEach((n) => n.remove());
  });
}

/** 触发件：结构与产品真产出同款（file ref card / term link）。
 *  摆位 = 真 canvas 正文面上（弹窗按触发件包围盒落位，底下必须有正文才算真对照）。 */
async function mountTriggers(page, size) {
  await page.evaluate(({ size, paneId }) => {
    const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="${paneId}"]`);
    const r = pane ? pane.getBoundingClientRect() : { left: 0, top: 0, width: innerWidth, height: innerHeight };
    const host = document.createElement('div');
    host.setAttribute('data-evidence-triggers', '1');
    host.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:50;';
    const place = (el, x, y) => {
      el.style.cssText = `position:fixed;left:${Math.round(x)}px;top:${Math.round(y)}px;pointer-events:auto;`;
    };
    const file = document.createElement('div');
    file.className = 'att-ref-card file-lang';
    file.style.width = '260px';
    file.innerHTML = '<div class="fc-icon">MD</div><div class="fc-meta"><div class="fc-name">术语系统设计.md</div><div class="fc-sub">docs · stand</div></div>';
    const term = document.createElement('a');
    term.className = 'nf-wikilink-term';
    term.textContent = '术语系统';
    term.href = '#';
    // 文件卡在上、词条链接在下：两张弹窗各自的落位算法（下开 / 上开）都不会互相压住。
    place(file, r.left + (size === 'mobile' ? 16 : 48), r.top + (size === 'mobile' ? 96 : 150));
    place(term, r.left + (size === 'mobile' ? 24 : Math.min(320, r.width * 0.5)), r.top + (size === 'mobile' ? 560 : 620));
    host.append(file, term);
    document.body.appendChild(host);
    window.__evTriggers = { file, term };
  }, { size, paneId: PANE_ID });
  await settle(page, 150);
}

/** 文件说明弹窗：调产品模块真入口。path/title 可由 ④ 的场景覆盖。 */
async function openFileCard(page, { path = DOC_PATH, title = '术语系统设计.md' } = {}) {
  await page.evaluate(async ({ path, title }) => {
    const mod = await import('/js/documentTermCard.js');
    const { file } = window.__evTriggers;
    mod.showDocumentTermCard(file, { path, title, openDocument: () => {} });
  }, { path, title });
  await page.waitForSelector('.document-term-card', { state: 'visible', timeout: 10000 });
  // 提要卡是异步取数（fetch 打桩走 route），等文案落定再拍：等到「不是加载态」即可
  // —— 空态也是合法落定态（④ 的空态帧要的就是它）。
  await page.waitForFunction(() => {
    const s = document.querySelector('.document-term-summary');
    return !!s && !!s.textContent;
  }, null, { timeout: 10000 }).catch(() => {});
  await page.waitForTimeout(350);
  await settle(page, 200);
}

/** ④ 场景取数：打桩的 body 在请求时才读 ⇒ 逐个场景改 body 即可拍不同数据形态。
 *  改前根的 JS 走 `/api/wiki/terms`，改后根的 JS 走 `/api/wiki/doc` —— 两支一起改，
 *  同一份采集器才能服务两个静态根。 */
function useDocScene(scene) {
  API_STUBS['/api/wiki/doc'].body = DOC_SCENES[scene].doc;
  API_STUBS['/api/wiki/terms'].body = DOC_SCENES[scene].terms;
}

/** 词条弹窗：调产品模块真入口（attachTermFace + termTapExpand）。 */
async function openTermTip(page) {
  await page.evaluate(async () => {
    const mod = await import('/js/termTip.js');
    const { term } = window.__evTriggers;
    term.dataset.wikiResolved = JSON.stringify({
      term: {
        name: '术语系统',
        definition: '术语系统 = 由注册表、消解 pass 与索引块组成的词条网络。注册表登记每个词条的归属文档与定义段；消解 pass 把正文里的 [[词条]] 与裸写命中解析成可点击的面；索引块让读者能从引用处回到定义段。',
        rel: '术语系统设计__20261105_141229.md',
        doc: '术语系统设计',
      },
    });
    mod.attachTermFace(term, document.body, { term: true });
    mod.termTapExpand(term, document.body);
  });
  await page.waitForFunction(() => {
    const el = document.querySelector('.nf-term-tip');
    return el && el.style.display !== 'none' && el.querySelector('.ts').textContent;
  }, null, { timeout: 10000 });
  await settle(page, 250);
}

async function hideBoth(page) {
  await page.evaluate(async () => {
    const doc = await import('/js/documentTermCard.js');
    doc && document.dispatchEvent(new Event('keydown'));
    const tip = await import('/js/termTip.js');
    tip.hideTermTip();
    // 文件卡只有 Escape / 外部点击才收：发一次 Escape 即可（产品自己的监听）。
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
  });
  await page.waitForTimeout(150);
}

const measurements = {};

/** 逐格读数：表面材质 + 排版节奏 + 按钮（全部取 computedStyle 真值）。 */
async function readFace(page, label) {
  return page.evaluate((lbl) => {
    const cs = (el) => (el ? getComputedStyle(el) : null);
    const g = (el, p) => (el && cs(el) ? cs(el).getPropertyValue(p) : null);
    const rect = (el) => {
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { x: +r.x.toFixed(1), y: +r.y.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) };
    };
    const face = (sel, kids) => {
      const root = document.querySelector(sel);
      if (!root) return null;   // 单面格里另一面本就不在场
      const out = {
        sel,
        rect: rect(root),
        background: g(root, 'background-color'),
        backdropFilter: g(root, 'backdrop-filter') || g(root, '-webkit-backdrop-filter'),
        border: `${g(root, 'border-top-width')} ${g(root, 'border-top-style')} ${g(root, 'border-top-color')}`,
        radius: g(root, 'border-top-left-radius'),
        boxShadow: g(root, 'box-shadow'),
        padding: g(root, 'padding'),
        color: g(root, 'color'),
        fontSize: g(root, 'font-size'),
        lineHeight: g(root, 'line-height'),
        overflowY: g(root, 'overflow-y'),
        zIndex: g(root, 'z-index'),
      };
      out.kids = {};
      for (const [k, ksel] of Object.entries(kids)) {
        const el = root.querySelector(ksel);
        out.kids[k] = el ? {
          fontSize: g(el, 'font-size'),
          fontWeight: g(el, 'font-weight'),
          lineHeight: g(el, 'line-height'),
          color: g(el, 'color'),
          opacity: g(el, 'opacity'),
          marginTop: g(el, 'margin-top'),
          background: g(el, 'background-color'),
          border: `${g(el, 'border-top-width')} ${g(el, 'border-top-style')} ${g(el, 'border-top-color')}`,
          radius: g(el, 'border-top-left-radius'),
          boxShadow: g(el, 'box-shadow'),
          rect: rect(el),
          textLength: (el.textContent || '').length,
        } : null;
      }
      return out;
    };
    return {
      label: lbl,
      file: face('.document-term-card', {
        heading: 'strong', summary: '.document-term-summary',
        meta: '.document-term-meta', path: '.document-term-path', button: '.document-term-open',
      }),
      term: face('.nf-term-tip', { title: '.tt', body: '.ts', foot: '.td' }),
      theme: document.documentElement.getAttribute('data-theme') || '(unset)',
      prefersDark: matchMedia('(prefers-color-scheme: dark)').matches,
      mobileLayer: document.body.classList.contains('mobile-layer'),
    };
  }, label);
}

/** 弹窗内控件的焦点环读数（P-1 可见 / P-3 不裁切 / P-4 玻璃层上可读）：
 *  做法 = 与 check-focus-ring-runtime 同一条判据（同一裁剪区有/无指示器逐像素差分），
 *  只是把测量对象换成文件说明卡里的「打开原文档」钮 —— 运行时闸的默认场景不含这
 *  张卡，本批需要它自己的读数。 */
async function focusProbe(page, tag) {
  const box = await page.evaluate(() => {
    const btn = document.querySelector('.document-term-card .document-term-open');
    if (!btn) return null;
    const r = btn.getBoundingClientRect();
    return { x: r.x, y: r.y, w: r.width, h: r.height };
  });
  if (!box) return null;
  const clip = {
    x: Math.max(0, Math.floor(box.x) - 6),
    y: Math.max(0, Math.floor(box.y) - 6),
    width: Math.ceil(box.w) + 12,
    height: Math.ceil(box.h) + 12,
  };
  await page.evaluate(() => { document.activeElement && document.activeElement.blur(); });
  await settle(page, 120);
  const before = await page.screenshot({ clip, animations: 'disabled' });
  // 真键盘模态：先一次 Tab（Chromium 以键盘为最近输入源），再把焦点落到钮上。
  await page.keyboard.press('Tab');
  await page.evaluate(() => {
    const btn = document.querySelector('.document-term-card .document-term-open');
    if (btn) btn.focus();
  });
  await settle(page, 150);
  const after = await page.screenshot({ clip, animations: 'disabled' });
  const style = await page.evaluate(() => {
    const btn = document.querySelector('.document-term-card .document-term-open');
    if (!btn) return null;
    const cs = getComputedStyle(btn);
    return {
      focusVisible: btn.matches(':focus-visible'),
      outlineStyle: cs.outlineStyle,
      outlineWidth: cs.outlineWidth,
      outlineColor: cs.outlineColor,
      outlineOffset: cs.outlineOffset,
      isActiveElement: document.activeElement === btn,
      tabIndex: btn.tabIndex,
    };
  });
  const diff = comparePng(before, after, { tolerance: 0 });
  writeFileSync(join(OUT, `${TAG}-focus-${tag}-before.png`), before);
  writeFileSync(join(OUT, `${TAG}-focus-${tag}-after.png`), after);
  return {
    clip,
    buttonBox: { x: +box.x.toFixed(1), y: +box.y.toFixed(1), w: +box.w.toFixed(1), h: +box.h.toFixed(1) },
    style,
    diffPixels: diff.diffPixels,
    // 差分包围盒换算回视口坐标，判「环完全落在组件 border-box 内」（P-3 内缩）
    ringBBox: diff.bbox ? {
      x0: diff.bbox.x0 + clip.x, y0: diff.bbox.y0 + clip.y,
      x1: diff.bbox.x1 + clip.x, y1: diff.bbox.y1 + clip.y,
    } : null,
    insideButtonBox: diff.bbox
      ? (diff.bbox.x0 + clip.x >= box.x - 1 && diff.bbox.y0 + clip.y >= box.y - 1 &&
         diff.bbox.x1 + clip.x <= box.x + box.w + 1 && diff.bbox.y1 + clip.y <= box.y + box.h + 1)
      : null,
  };
}

async function main() {
  const pwRes = loadPlaywright();
  const pw = pwRes.mod.playwright || pwRes.mod;
  const { browser } = await launchBrowser(pw);
  const allErrors = [];
  let exit = 0;

  for (const size of SIZES) {
    const vp = SIZES_PX[size];
    for (const theme of THEMES) {
      const tag = `${size}-${theme}`;
      const s = await createSession(browser, { colorScheme: theme, width: vp.width, height: vp.height, apiStubs: API_STUBS });
      try {
        await s.boot();
        await openCanvas(s.page);
        if (size === 'mobile') {
          // 手机层：canvas 是页面栈里的一页（.mobile-page.live 才可见），
          // 用产品自己的导航入口落页，不手改类名。
          await s.page.evaluate(async () => {
            const nav = await import('/js/mobile/navController.js');
            nav.dockTo('canvas');
          });
          await s.page.waitForSelector(`.canvas-tab-pane[data-tab-id="${PANE_ID}"]`, { state: 'visible', timeout: 20000 });
          await s.page.waitForTimeout(700);
        }
        await mountTriggers(s.page, size);
        // 1) 两弹窗都不在场的基线（像素对照用：证明模糊真的洗掉了背后笔画）
        await hideBoth(s.page);
        await settle(s.page, 200);
        await s.page.screenshot({ path: join(OUT, `${TAG}-closed-${tag}.png`), animations: 'disabled' });

        // 2) 文件说明弹窗单开
        await openFileCard(s.page);
        await s.page.screenshot({ path: join(OUT, `${TAG}-file-${tag}.png`), animations: 'disabled' });
        const fileM = (await readFace(s.page, `${TAG}:file`)).file;

        // 2b) 纯面帧：遮掉面内文字，量**材质本身**透不透底（背面笔画还在不在）。
        //     不遮的话，面内的字（提要/元数据/按钮）会把方差吃掉，读数就不再是
        //     「模糊有没有把背景洗掉」的证据了 —— 本批第一版读数就是这么被骗的。
        await s.page.evaluate(() => {
          for (const el of document.querySelectorAll('.document-term-card > *')) el.style.visibility = 'hidden';
        });
        await settle(s.page, 150);
        await s.page.screenshot({ path: join(OUT, `${TAG}-glass-${tag}.png`), animations: 'disabled' });
        await s.page.evaluate(() => {
          for (const el of document.querySelectorAll('.document-term-card > *')) el.style.visibility = '';
        });

        // 3) 词条弹窗单开（先收文件卡）
        await hideBoth(s.page);
        await openTermTip(s.page);
        await s.page.screenshot({ path: join(OUT, `${TAG}-term-${tag}.png`), animations: 'disabled' });
        const termM = (await readFace(s.page, `${TAG}:term`)).term;

        // 4) 并排同源对照（两弹窗同屏）
        await openFileCard(s.page);
        await settle(s.page, 250);
        await s.page.screenshot({ path: join(OUT, `${TAG}-pair-${tag}.png`), animations: 'disabled' });
        const pairRaw = await readFace(s.page, `${TAG}:pair`);
        const pairM = { file: pairRaw.file, term: pairRaw.term, theme: pairRaw.theme, mobileLayer: pairRaw.mobileLayer };

        // 5) ④ 点击说明弹窗的三档数据形态（读序 / 回退路径 / 空态）各一帧
        const docScenes = {};
        for (const scene of ['meta', 'empty', 'legacy']) {
          useDocScene(scene);
          await hideBoth(s.page);
          await openFileCard(s.page, {
            path: DOC_SCENES[scene].doc.path,
            title: `${DOC_SCENES[scene].doc.title || 'doc'}.md`,
          });
          await s.page.screenshot({ path: join(OUT, `${TAG}-doc-${scene}-${tag}.png`), animations: 'disabled' });
          docScenes[scene] = await s.page.evaluate(() => {
            const card = document.querySelector('.document-term-card');
            const q = (sel) => card?.querySelector(sel)?.textContent ?? null;
            const s = card?.querySelector('.document-term-summary');
            return {
              heading: q('strong'),
              summary: q('.document-term-summary'),
              summaryClasses: s ? s.className : null,
              summarySource: s?.dataset?.summarySource || null,
              meta: q('.document-term-meta'),
              path: q('.document-term-path'),
              button: q('.document-term-open'),
              backdropFilter: card ? (getComputedStyle(card).backdropFilter || getComputedStyle(card).webkitBackdropFilter) : null,
              rect: card ? (() => { const r = card.getBoundingClientRect(); return { x: +r.x.toFixed(1), y: +r.y.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) }; })() : null,
            };
          });
        }
        // 复原场景（后续尺寸/主题复用同一份打桩）
        useDocScene('meta');

        // 6) 弹窗内控件焦点环（真键盘模态 + 像素差分）
        useDocScene('meta');
        await hideBoth(s.page);
        await openFileCard(s.page, { path: DOC_SCENES.meta.doc.path, title: '收口面甲 prime 修复.md' });
        const focus = await focusProbe(s.page, tag);

        measurements[tag] = { file: fileM, term: termM, pair: pairM, docScenes, focus, pageErrors: [...s.errors] };
        allErrors.push(...s.errors.map((e) => `${tag}: ${e}`));
      } catch (e) {
        console.error(`!! ${tag} failed: ${e.message}`);
        exit = 1;
      } finally {
        await s.close();
      }
    }
  }

  writeFileSync(join(OUT, `${TAG}-measure.json`), JSON.stringify({ tag: TAG, root: served, cells: measurements, pageErrors: allErrors }, null, 2));
  await browser.close();
  console.log(`# shots + measure → ${OUT}`);
  console.log(`# pageErrors = ${allErrors.length}`);
  for (const e of allErrors) console.log('  ! ' + e);
  process.exit(exit);
}

main().catch((e) => { console.error(e); process.exit(2); });

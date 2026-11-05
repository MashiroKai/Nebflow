#!/usr/bin/env node
// capture-glass-unify.mjs — glass-unify 批的证据采集器（改前 / 改后 同一支脚本）
//
// 为什么要独立一支：本批要证明的是「同一份产品、同一套场景、只换了静态根（CSS/JS）
// 之后，毛玻璃从图1 形态变成图2 形态」。所以采集器必须**参数化静态根**，否则改前
// 改后是两个人、两次手感、两套场景，对照不成立。
//
// 装置 = scripts/lib/visual-harness.mjs（仓内既有视觉基线装置：page.route 从磁盘
// 服务 web 根 + MockWS 走真实 ws.js 分发路径 + 真 Chromium 渲染 + settle 后拍摄）。
// 因此截图是**真渲染真 DOM**，不是手绘、不是 CSS 片段拼贴。
//
// 用法：
//   node scripts/capture-glass-unify.mjs --root src/main/resources/web --tag after  --out /tmp/shots
//   node scripts/capture-glass-unify.mjs --root /tmp/glass-before/src/main/resources/web \
//                                        --tag before --out /tmp/shots
//
// 产出：<out>/<tag>-<scene>-<theme>.png + <out>/<tag>-measure.json

import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  loadPlaywright, launchBrowser, createSession, settle, setWebRoot,
} from './lib/visual-harness.mjs';

const argv = process.argv.slice(2);
const valOf = (f, d = null) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const ROOT_DIR = valOf('--root', 'src/main/resources/web');
const TAG = valOf('--tag', 'after');
const OUT = valOf('--out', '/tmp/glass-shots');
const THEMES = (valOf('--themes', 'light,dark') || '').split(',').filter(Boolean);

mkdirSync(OUT, { recursive: true });
const served = setWebRoot(ROOT_DIR);
console.log(`# static root = ${served}`);

const PANE_ID = 'file:/tmp/glass-unify-note.md';
// 正文必须**填满整个 pane 并伸到浮层底下**：作者要证的是「底下的字透不上来」，
// 只有正文真的在玻璃背后，这条才可判。所以这里是长文，不是三行示例。
const CANVAS_MD = [
  '# 毛玻璃统一说明',
  '',
  '这一段正文用来验证「毛玻璃底不许把背后的字透上来」：**粗体**、`行内代码`、列表。',
  '',
  '- 唯一标准：高不透明白底（α ≥ .90）+ 模糊 + 细边',
  '- 半透明透底形态（旧 .55/.60/.68/.88 四档）已判死刑',
  '',
  '> 判定：正文大字在玻璃底下应当完全读不出来。',
  '',
  ...Array.from({ length: 26 }, (_, i) =>
    `第 ${i + 1} 段：icrp74 的注量-剂量转换系数并做全身器官加权——那一行是所有剂量论据的唯一入口。` +
    '无论哪条曲线，危害贡献的主体都落在 1-10 MeV 段，且真谱越厚的壳越软。'),
].join('\n');

const MESSAGES = [
  { type: 'user', text: '把这份报告的重点整理成三条，并说明数据来源。', timestamp: 1757000000000 },
  {
    type: 'ai',
    text: '好的，三条重点如下：\n1. 先补视觉基线，给后续改动一张安全网；\n2. 再上 TypeScript 基建，类型面收敛；\n3. 最后拆 canvas.js 的单点巨型函数。',
  },
  { type: 'user', text: '继续。', timestamp: 1757000060000 },
  { type: 'ai', text: '已按计划推进到第二步：TypeScript 基建。' },
];

// [问, 答] 对 —— 每轮走一次真交互，四条 ⇒ 两个问答回合同屏（新泡 + 历史泡）。
const ASK_MSGS = [
  ['这段的 MeV/nuc 是什么意思？', 'MeV/nuc 就是「每个核子多少 MeV」，nuc 是 nucleon（核子）的缩写。它把不同核种拉到同一把尺子上比较。'],
  ['那它和总能量的关系呢？', '总能量除以核子数就是 MeV/nuc，所以它是强度量，总能量是广延量。'],
];

async function paneReady(page, tabId) {
  await page.waitForSelector(`#canvas-panel .canvas-tab-pane[data-tab-id="${tabId}"]`, { state: 'visible', timeout: 20000 });
}

async function openCanvas(page) {
  await page.evaluate((content) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: {
        id: 'file:/tmp/glass-unify-note.md', itemType: 'markdown', title: 'glass-unify-note.md',
        content, absPath: '/tmp/glass-unify-note.md', size: content.length, mtimeMs: 1757000000000,
      },
    }));
  }, CANVAS_MD);
  await paneReady(page, PANE_ID);
  await page.waitForTimeout(900);
}

const measurements = {};

async function record(page, key, fn) {
  measurements[`${TAG}:${key}`] = await page.evaluate(fn);
}

async function shootTo(session, scene, theme) {
  await session.page.screenshot({
    path: join(OUT, `${TAG}-${scene}-${theme}.png`),
    fullPage: false,
    animations: 'disabled',
  });
}

async function main() {
  // loadPlaywright() 返回 { playwright, from }（不是 module 本体）——
  // launchBrowser 读 pw.playwright || pw，这里显式展开以免歧义。
  const pwRes = loadPlaywright();
  const pw = pwRes.mod.playwright || pwRes.mod;
  const { browser } = await launchBrowser(pw);
  const allErrors = [];

  for (const theme of THEMES) {
    // ── 1. chat-quote：消息气泡 + 引用卡 ────────────────────────────────
    {
      const s = await createSession(browser, { colorScheme: theme, width: 1280, height: 860 });
      await s.boot({ messages: MESSAGES });
      await s.page.evaluate(async () => {
        const mod = await import('/js/msgQuote.js');
        const host = document.querySelector('#chat');
        const box = mod.renderQuoteBlock({
          refMessageId: 'm-42',
          excerpt: 'dispatcher 不终止：同源判定与 moved 噪音的处置',
          senderRole: 'user',
          senderName: 'Nebula',
        });
        const wrap = document.createElement('div');
        wrap.className = 'row user';
        wrap.appendChild(box);
        host.appendChild(wrap);
      });
      await settle(s.page, 300);
      await shootTo(s, 'chat-quote', theme);
      await record(s.page, 'quote', () => {
        const b = document.querySelector('.msg-quote-block');
        const ai = document.querySelector('.bubble.ai');
        const cs = (el) => (el ? getComputedStyle(el) : null);
        const g = (el, p) => (el && cs(el) ? cs(el).getPropertyValue(p) : null);
        return {
          quoteBg: g(b, 'background-color'),
          quoteBackdrop: g(b, 'backdrop-filter') || g(b, '-webkit-backdrop-filter'),
          bubbleBg: g(ai, 'background-color'),
          bubbleBackdrop: g(ai, 'backdrop-filter'),
        };
      });
      allErrors.push(...s.errors);
      await s.close();
    }

    // ── 2. canvas-bar：canvas 浮钮条 + 面板顶栏 ─────────────────────────
    {
      const s = await createSession(browser, { colorScheme: theme, width: 1440, height: 900 });
      await s.boot();
      await openCanvas(s.page);
      await settle(s.page, 300);
      await shootTo(s, 'canvas-bar', theme);
      // 缩放条只在图片/PDF 页签出现 ⇒ 再开一个图片页签量 .canvas-zoom-btn。
      await s.page.evaluate(() => {
        window.dispatchEvent(new CustomEvent('workspace-open-item', {
          detail: {
            id: 'file:/tmp/glass-unify-shot.svg', itemType: 'image', title: 'glass-unify-shot.svg',
            content: '<svg xmlns="http://www.w3.org/2000/svg" width="600" height="400"><rect width="600" height="400" fill="#c9d6ea"/><circle cx="300" cy="200" r="120" fill="#5b7fbf"/></svg>',
            absPath: '/tmp/glass-unify-shot.svg', size: 200, mtimeMs: 1757000000000,
          },
        }));
      });
      await s.page.waitForTimeout(900);
      await settle(s.page, 300);
      await shootTo(s, 'canvas-image-tab', theme);
      await record(s.page, 'canvasBar', () => {
        const bar = document.querySelector('.canvas-zoom-bar');
        const r = (el) => (el ? el.getBoundingClientRect() : null);
        return {
          zoomBar: bar ? { w: +r(bar).width.toFixed(1), h: +r(bar).height.toFixed(1), radius: getComputedStyle(bar).borderRadius, bg: getComputedStyle(bar).backgroundColor, backdrop: getComputedStyle(bar).backdropFilter } : null,
        };
      });
      await record(s.page, 'buttons', () => {
        const box = (sel) => {
          const el = document.querySelector(sel);
          if (!el) return { sel, missing: true };
          const r = el.getBoundingClientRect();
          const cs = getComputedStyle(el);
          const svg = el.querySelector('svg');
          const ir = svg ? svg.getBoundingClientRect() : null;
          return {
            sel,
            w: +r.width.toFixed(2),
            h: +r.height.toFixed(2),
            fontSize: cs.fontSize,
            radius: cs.borderRadius,
            iconW: ir ? +ir.width.toFixed(2) : null,
            iconH: ir ? +ir.height.toFixed(2) : null,
            padding: cs.padding,
            visible: r.width > 0 && r.height > 0,
          };
        };
        return [
          '#canvas-head-close', '#deck-fab',
          '.canvas-source-toggle', '.canvas-ref-select', '.canvas-find-toggle', '.canvas-refresh-toggle',
          '.canvas-zoom-btn', '.ask-fab', '.note-fab',
        ].map(box);
      });
      allErrors.push(...s.errors);
      await s.close();
    }

    // ── 3. ask-dock：气泡列 + 输入条 ───────────────────────────────────
    {
      const s = await createSession(browser, { colorScheme: theme, width: 1440, height: 900 });
      await s.boot();
      await openCanvas(s.page);
      // ask 会话的 entry 由**真实提问**建立（consumeFrame 按 sid 反查 map，
      // 凭空塞 askState 帧不命中 —— 这不是打桩缺陷，是产品的会话归属制）。
      // 所以这里走真交互：点开入口钮 → 输入框逐条提问 → 用产品发出去的 sid
      // 喂回 textDelta + done 造出答案泡。四轮 ⇒ 新泡与历史泡同屏。
      const fab = await s.page.$('.ask-fab');
      if (fab) await fab.click();
      await s.page.waitForTimeout(400);
      for (const [q, a] of ASK_MSGS) {
        await s.page.fill('.ask-input textarea', q);
        await s.page.press('.ask-input textarea', 'Enter');
        await s.page.waitForTimeout(250);
        const askSid = await s.page.evaluate(() => {
          const sent = (window.__wsSent || []).filter((f) => f && f.type === 'askQuestion');
          return sent.length ? sent[sent.length - 1].sessionId : null;
        });
        if (askSid) {
          await s.frame({ type: 'textDelta', sessionId: askSid, delta: a });
          await s.frame({ type: 'done', sessionId: askSid });
        }
        await s.page.waitForTimeout(250);
      }
      await s.page.waitForTimeout(400);
      await settle(s.page, 300);
      await shootTo(s, 'ask-dock', theme);
      // 紧一点的对照图：整块 dock（气泡列 + 输入条）——报告里 1:1 对照用。
      const dockEl = await s.page.$('.ask-dock');
      if (dockEl) await dockEl.screenshot({ path: join(OUT, `${TAG}-ask-dock-crop-${theme}.png`), animations: 'disabled' });
      await record(s.page, 'ask', () => {
        const col = document.querySelector('.ask-col');
        const wrap = document.querySelector('.ask-colwrap');
        const input = document.querySelector('.ask-input');
        const bubs = [...document.querySelectorAll('.ask-bub')];
        const r = (el) => (el ? el.getBoundingClientRect() : null);
        const cs = (el) => (el ? getComputedStyle(el) : null);
        const ir = r(input);
        return {
          bubbleCount: bubs.length,
          bubbleStyles: bubs.map((b) => ({
            cls: b.className,
            opacity: cs(b).opacity,
            bg: cs(b).backgroundColor,
            backdrop: cs(b).backdropFilter,
            dataAge: b.getAttribute('data-age'),
          })),
          inputTopY: ir ? +ir.top.toFixed(1) : null,
          inputH: ir ? +ir.height.toFixed(1) : null,
          inputMidY: ir ? +(ir.top + ir.height / 2).toFixed(1) : null,
          colBottomY: wrap ? +r(wrap).bottom.toFixed(1) : null,
          inputZ: cs(input) ? cs(input).zIndex : null,
          colZ: cs(wrap) ? cs(wrap).zIndex : null,
          inputBg: cs(input) ? cs(input).backgroundColor : null,
          inputBackdrop: cs(input) ? cs(input).backdropFilter : null,
          colH: col ? +r(col).height.toFixed(1) : null,
        };
      });
      allErrors.push(...s.errors);
      await s.close();
    }

    // ── 4. modal：弹层 ─────────────────────────────────────────────────
    {
      const s = await createSession(browser, { colorScheme: theme, width: 1280, height: 860 });
      await s.boot();
      await s.page.evaluate(() => {
        window.__showConfirm('删除后不可恢复，确定要删除这 3 个文件吗？', '删除确认', () => {}, {});
      });
      await s.page.waitForSelector('#modal-overlay.on', { timeout: 8000 });
      await settle(s.page, 300);
      await shootTo(s, 'modal', theme);
      allErrors.push(...s.errors);
      await s.close();
    }

    // ── 5. topbar：独立窗口顶栏 ────────────────────────────────────────
    {
      const s = await createSession(browser, { colorScheme: theme, width: 1280, height: 860 });
      await s.boot({ messages: MESSAGES });
      await settle(s.page, 250);
      const header = await s.page.$('#header');
      if (header) {
        await header.screenshot({ path: join(OUT, `${TAG}-topbar-${theme}.png`), animations: 'disabled' });
      }
      allErrors.push(...s.errors);
      await s.close();
    }
  }

  await browser.close();
  writeFileSync(join(OUT, `${TAG}-measure.json`), JSON.stringify(measurements, null, 2));
  if (allErrors.length) {
    console.error(`✗ 页面 JS 异常 ${allErrors.length} 条（采集不可信）：`);
    for (const e of allErrors.slice(0, 10)) console.error('  · ' + e);
    process.exit(1);
  }
  console.log(`✓ ${TAG} 采集完成 → ${OUT}`);
}

main().catch((e) => { console.error(e); process.exit(2); });

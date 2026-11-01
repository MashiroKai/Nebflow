#!/usr/bin/env node
// visual-baseline.mjs — 前端视觉基线（采集 + 比对），「改动前后效果不变」的自动安全网。
//
// 设计前提（勘察结论，见 docs/<本批>_frontend-foundation-plan.md §一.1）：
//   仓内 30 个截图脚本各写一份打桩配方，13 个会截图，但**像素比对 0 处、基准图 0 套**
//   （scripts/ 内 animations:'disabled' 0 处、document.fonts.ready 全仓 0 处）。
//   本脚本把配方收成一份（scripts/lib/visual-harness.mjs）并补上「基准 + 比对 + 差图」。
//
// 路线：零端口零进程（page.route 从磁盘服务 src/main/resources/web + MockWS 注入
// 真实 ws.js 分发路径）。不起服务 ⇒ 不需要 --port/--home，也就**结构上不可能**碰到
// 宿主实例 6138。
//
// 用法：
//   node scripts/visual-baseline.mjs --update            # 采集并写基准（人工评审后入库）
//   node scripts/visual-baseline.mjs --check             # 采集并比对基准（CI/改动后自检）
//   node scripts/visual-baseline.mjs --check --filter canvas   # 只跑名字含 canvas 的场景
//   node scripts/visual-baseline.mjs --check --determinism     # 同轮双拍自比对（验确定性）
//   node scripts/visual-baseline.mjs --list              # 列场景
//   选项：--theme dark|light  --tolerance <0-255>  --max-ratio <0-1>  --platform <key>
//
// 产物：
//   基准（入库）  tests/visual/baseline/<platform>/<scenario>-<theme>.png + manifest.json
//   运行/差图     .nebflow/visual-baseline/<platform>/  （.gitignore:17-18 已忽略 .nebflow/）
//
// 退出码：--check 通过 0；有超阈值差异 1；装置错误（无浏览器/无 playwright）2。

import { existsSync, mkdirSync, readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { ROOT, WEB, loadPlaywright, launchBrowser, createSession, settle, applyMasks, shoot } from './lib/visual-harness.mjs';
import { comparePng } from './lib/png-diff.mjs';

// ── 参数 ───────────────────────────────────────────────────────────────
const argv = process.argv.slice(2);
const hasFlag = (f) => argv.includes(f);
const valOf = (f, d = null) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const MODE = hasFlag('--update') ? 'update' : hasFlag('--list') ? 'list' : 'check';
const FILTER = valOf('--filter');
const THEME_ONLY = valOf('--theme');
const TOLERANCE = Number(valOf('--tolerance', '0'));
// 阈值默认**严格**（0 差异像素）：2026-11-01 实测 12/12 基准跨轮逐字节相同（两次
// 独立 --check 全 SAME），因此没有预支容差的理由。跨物理机 / 跨 CI runner 若出现
// 反走样漂移，用 `--max-ratio 0.0005` 显式放宽 —— 放宽必须是显式动作，不能是默认。
const MAX_RATIO = Number(valOf('--max-ratio', '0'));
const DETERMINISM = hasFlag('--determinism');
const PLATFORM_OVERRIDE = valOf('--platform');

// ── 场景表 ─────────────────────────────────────────────────────────────
// 选取口径：(a) 改动后最该「看起来一模一样」的三类面 —— 主壳/对话流/设置；
// (b) 后续拆分的目标面 —— canvas 页签 + viewer（经 openWorkspaceItem 打开）；
// (c) 面板态 —— 后台任务下拉。每条都在 scripts/ 里有可复用的打桩先例。
//
// 消息夹具形状取自 tests/fixtures/history-replay/fixture.json（仓内真夹具：
// user/ai/tool/system 四种行），但只取短行 —— 全量夹具含 4KB 工具正文，会让
// 画面高度随夹具漂移。
const MESSAGES = [
  { type: 'user', text: '把这份报告的重点整理成三条，并说明数据来源。', timestamp: 1757000000000 },
  {
    type: 'ai',
    text: '好的，三条重点如下：\n1. 先补视觉基线，给后续改动一张安全网；\n2. 再上 TypeScript 基建，类型面收敛；\n3. 最后拆 canvas.js 的单点巨型函数。\n\n数据来源：tests/fixtures/history-replay/fixture.json',
  },
  {
    type: 'tool',
    label: 'Read("docs/frontend-foundation-plan.md")',
    summary: '128 lines',
    isError: false,
    input: '{"path":"docs/frontend-foundation-plan.md"}',
    content: '1\t# 前端地基计划\n2\t\n3\t## 一、视觉基线\n',
  },
  { type: 'system', i18nKey: 'chat.compacted', params: { after: 12, before: 3, detail: '' }, content: 'Context compacted: 12 → 3 messages' },
  { type: 'user', text: '继续。', timestamp: 1757000060000 },
  { type: 'ai', text: '已按计划推进到第二步：TypeScript 基建。' },
];

const CANVAS_MD = [
  '# 视觉基线说明',
  '',
  '这一段用来验证 markdown viewer 的排版：**粗体**、`行内代码`、列表与引用。',
  '',
  '- 基准图存放：`tests/visual/baseline/<platform>/`',
  '- 比对阈值：单通道容差 0，差异像素占比 ≤ 0.3%',
  '- 差图落盘：`.nebflow/visual-baseline/<platform>/<name>-diff.png`',
  '',
  '> 判定口径：像素级差异首先被假定为「改动引入的回归」，由人复核后才允许更新基准。',
].join('\n');

const CANVAS_CODE = [
  '// visual-baseline fixture — 仅用于渲染回归比对',
  'export function pickViewer(itemType, ext) {',
  '  if (itemType === "markdown") return "markdown";',
  '  if (itemType === "image") return "image";',
  '  return ext === "json" ? "json" : "code";',
  '}',
].join('\n');

// 全场景共用桩：只桩「会在画面上留下随机/报错残影」的两条端点。
//   · /api/plugins   —— 画布面板默认页签就是插件清单（boot 即开），404 会让面板
//                      顶一条「加载失败」并每 6s 重试（plugins.js:127）。桩成空
//                      注册表 = 产品的真实空态，且消掉轮询噪声。
//   · /api/nf-authcheck —— ws.js cookie 探针，204 是 happy path
//                      （tests/helpers/replay-ws-server.mjs 同款）。
// 其余端点保持 404：产品的兜底态是静态的，比「桩一个假后端」更接近真实。
const BASE_STUBS = {
  '/api/plugins': { body: { plugins: [], rejected: [] } },
  '/api/nf-authcheck': { status: 204 },
};

// 全场景共用遮罩：语音球是 canvas rAF 动画（连 animations:'disabled' 也冻不住，
// 实测跨轮亮色主题差 14-20px），且它出现在每个带输入框的场景里 ⇒ 统一遮罩。
const BASE_MASK = ['.mic-orb-wrap'];

const SCENARIOS = [
  {
    name: 'app-empty',
    why: '主壳空态（顶栏 + 空对话 + 侧栏）—— 任何布局层改动的第一道闸',
    themes: ['dark', 'light'],
    setup: async (s) => { await s.boot(); },
    // 全页：改动的常见形态就是「某块高度变了」，元素图会漏掉这类回归
    // .mic-orb-wrap = 语音球（canvas rAF 动画，连 animations:'disabled' 也冻不住，
    // 实测亮色主题跨轮差 14px）——遮罩掉，其余像素严格比。
    mask: ['.mic-orb-wrap'],
  },
  {
    name: 'chat-messages',
    why: '对话流（user/ai/tool/system 四行 + 折叠）—— 消息渲染改动的闸',
    themes: ['dark', 'light'],
    setup: async (s) => { await s.boot({ messages: MESSAGES }); },
    mask: ['.mic-orb-wrap', '.msg-time', '[data-relative-time]'], // 语音球动画 + 相对时间（随机器时钟走）
  },
  {
    name: 'settings-llm-empty',
    why: '设置面板「LLM 服务商 / 模型回退链」空态（无 Provider）—— 空态与文案改动的闸',
    themes: ['dark', 'light'],
    apiStubs: { '/api/agents': { body: { agents: [] } } },
    setup: async (s) => {
      await s.boot({ config: { llm: { providers: {} }, features: { friends: false } } });
      await s.page.click('#settings-btn');
      await s.page.waitForTimeout(600);
      // 滚到 LLM 服务商段：本场景要拍的是「无 Provider 的空态」，停在面板顶等于拍错地方。
      // （批注：旧的 verify-preset-empty-state.cjs:230+ 打的 #preset-empty-hint 在新
      //   设置面板里已不存在 —— 该段已并入「模型回退链」#model-chain-section。）
      await s.page.evaluate(() => {
        const el = document.getElementById('provider-list') || document.getElementById('model-chain-section');
        if (el) el.scrollIntoView({ block: 'center' });
      });
      await s.page.waitForTimeout(400);
      await settle(s.page, 250);
    },
  },
  {
    name: 'bgtask-panel',
    why: '后台任务下拉（来源标签 + 状态阶梯）—— 面板/角标改动的闸',
    themes: ['dark', 'light'],
    selector: '#bg-dropdown',
    setup: async (s) => {
      const sid = await s.boot();
      const agents = [
        { agentId: 'a1', name: 'explorer', description: '扫描前端依赖', kind: 'local' },
        { agentId: 'a2', name: 'reviewer', description: '三维审查', kind: 'remote' },
      ];
      for (const a of agents) await s.frame({ type: 'agentStart', sessionId: sid, rootSessionId: sid, ...a });
      for (const t of [
        { taskId: 't1', description: '构建前端 bundle', startedAt: 1757000000000, kind: 'local', origin: 'nebula', originLabel: 'Nebula' },
        { taskId: 't2', description: '跑 e2e 冒烟', startedAt: 1757000001000, kind: 'remote', origin: 'node', originLabel: 'DESKTOP-V7EUCHT' },
      ]) {
        await s.frame({
          type: 'backgroundTaskUpdate', sessionId: sid, rootSessionId: sid,
          taskId: t.taskId, description: t.description, status: 'running',
          startedAt: t.startedAt, heartbeat: null, kind: t.kind, origin: t.origin, originLabel: t.originLabel,
        });
      }
      await s.page.waitForTimeout(300);
      await s.page.click('#bg-indicator');
      await s.page.waitForSelector('#bg-dropdown:not(.hidden) .bg-task-row', { timeout: 8000 });
      await settle(s.page, 250);
    },
    mask: ['.bg-task-uptime'], // uptime = now - startedAt，实时钟
  },
  {
    name: 'canvas-markdown-tab',
    why: 'canvas 页签 + markdown viewer（经 openWorkspaceItem 打开）—— 拆分安全网的核心面',
    themes: ['dark', 'light'],
    selector: '#canvas-panel',
    setup: async (s) => {
      await s.boot();
      await s.page.evaluate((content) => {
        window.dispatchEvent(new CustomEvent('workspace-open-item', {
          detail: {
            id: 'file:/tmp/visual-baseline-note.md', itemType: 'markdown', title: 'visual-baseline-note.md',
            content, absPath: '/tmp/visual-baseline-note.md', size: content.length, mtimeMs: 1757000000000,
          },
        }));
      }, CANVAS_MD);
      await paneReady(s.page, 'file:/tmp/visual-baseline-note.md');
      await s.page.waitForTimeout(900); // markdown 渲染 + 高亮批
      await settle(s.page, 300);
    },
  },
  {
    name: 'canvas-code-tab',
    why: 'canvas 页签 + 代码 viewer（monaco，经 openWorkspaceItem 打开）—— 拆分安全网的核心面',
    themes: ['dark', 'light'],
    selector: '#canvas-panel',
    setup: async (s) => {
      await s.boot();
      await s.page.evaluate((content) => {
        window.dispatchEvent(new CustomEvent('workspace-open-item', {
          detail: {
            id: 'file:/tmp/visual-baseline-fixture.js', itemType: 'code', title: 'visual-baseline-fixture.js',
            content, absPath: '/tmp/visual-baseline-fixture.js', size: content.length, mtimeMs: 1757000000000,
          },
        }));
      }, CANVAS_CODE);
      await paneReady(s.page, 'file:/tmp/visual-baseline-fixture.js');
      await s.page.waitForTimeout(1800); // monaco AMD 装载 + 首帧装饰
      await settle(s.page, 400);
    },
    mask: [], // 光标已由 shoot() 的 caret:'hide' 藏掉；当前行高亮是静态装饰，无需遮罩
  },
];

/**
 * 等某个 canvas 页签的 pane 真正可见。
 * 注意：`#canvas-panel .canvas-tab-pane` 会在 boot 就有匹配（默认「插件」页签），
 * 裸选择器等可见必然超时 —— 必须按 data-tab-id 精确打到目标页签。
 */
async function paneReady(page, tabId) {
  await page.waitForSelector(`#canvas-panel .canvas-tab-pane[data-tab-id="${tabId}"]`, { state: 'visible', timeout: 20000 });
}

// ── 落点 ───────────────────────────────────────────────────────────────
const BASELINE_ROOT = join(ROOT, 'tests', 'visual', 'baseline');
const OUT_ROOT = join(ROOT, '.nebflow', 'visual-baseline');

if (MODE === 'list') {
  for (const s of SCENARIOS) console.log(`${s.name.padEnd(24)} ${s.themes.join(',')}  — ${s.why}`);
  console.log(`\n${SCENARIOS.length} scenarios × themes = ${SCENARIOS.reduce((a, s) => a + s.themes.length, 0)} baselines`);
  process.exit(0);
}

const selected = SCENARIOS
  .filter((s) => !FILTER || s.name.includes(FILTER))
  .map((s) => ({ ...s, themes: THEME_ONLY ? s.themes.filter((t) => t === THEME_ONLY) : s.themes }))
  .filter((s) => s.themes.length > 0);

if (selected.length === 0) {
  console.error(`no scenario matched filter=${FILTER ?? '(none)'} theme=${THEME_ONLY ?? '(all)'}`);
  process.exit(2);
}

// ── 采集 ───────────────────────────────────────────────────────────────
async function captureAll() {
  const pwRes = loadPlaywright();
  const pw = pwRes.mod.playwright || pwRes.mod;
  const { browser, label, version } = await launchBrowser(pw);
  const platform = PLATFORM_OVERRIDE || `${process.platform}-${label}`;
  const baseDir = join(BASELINE_ROOT, platform);
  const outDir = join(OUT_ROOT, platform);
  mkdirSync(outDir, { recursive: true });
  if (MODE === 'update') mkdirSync(baseDir, { recursive: true });

  console.log(`visual-baseline [${MODE}] playwright=${pwRes.from} browser=${label} (${version}) platform=${platform}`);
  console.log(`  web    : ${relative(ROOT, WEB)}`);
  console.log(`  baseline: ${relative(ROOT, baseDir)}`);
  console.log(`  out    : ${relative(ROOT, outDir)}\n`);

  const rows = [];
  let failures = 0;

  for (const sc of selected) {
    for (const theme of sc.themes) {
      const tag = `${sc.name}-${theme}`;
      const t0 = Date.now();
      const session = await createSession(browser, { colorScheme: theme, apiStubs: { ...BASE_STUBS, ...(sc.apiStubs || {}) } });
      let buf;
      let second = null;
      const masks = [...BASE_MASK, ...(sc.mask || [])];
      try {
        await sc.setup(session);
        await applyMasks(session.page, masks);
        // 遮罩会改变布局（fixed 覆盖层不动布局，但保险起见再 settle 一次）
        await settle(session.page, 120);
        buf = await shoot(session.page, { selector: sc.selector, fullPage: !sc.selector });
        if (DETERMINISM) {
          await settle(session.page, 400);
          second = await shoot(session.page, { selector: sc.selector, fullPage: !sc.selector });
        }
      } finally {
        await session.close();
      }
      const errs = session.errors || [];
      const ms = Date.now() - t0;
      const curPath = join(outDir, `${tag}.png`);
      writeFileSync(curPath, buf);
      const notes = [];
      // pageError = 真 JS 异常：画面已不可信，直接判红（不放进 notes 而进 failures）
      if (errs.length) { failures++; notes.push(`PAGEERROR(${errs.length}): ${errs[0].slice(0, 120)}`); }

      if (DETERMINISM) {
        const d = comparePng(buf, second, { tolerance: TOLERANCE });
        if (!d.same) { failures++; notes.push(`NONDETERMINISTIC ${d.diffPixels}px (${(d.ratio * 100).toFixed(3)}%)`); }
        else notes.push('deterministic');
      }

      if (MODE === 'update') {
        const basePath = join(baseDir, `${tag}.png`);
        writeFileSync(basePath, buf);
        rows.push({ tag, scenario: sc.name, theme, bytes: buf.length, at: new Date().toISOString(), ms, mask: masks });
        console.log(`  UPDATE ${tag.padEnd(38)} ${relative(ROOT, basePath)}  (${ms}ms) ${notes.join(' ')}`);
        continue;
      }

      const basePath = join(baseDir, `${tag}.png`);
      if (!existsSync(basePath)) {
        failures++;
        console.log(`  MISS   ${tag.padEnd(38)} 无基准图 → 先跑 --update 并人工评审 ${relative(ROOT, curPath)}`);
        rows.push({ tag, status: 'missing' });
        continue;
      }
      const d = comparePng(readFileSync(basePath), buf, { tolerance: TOLERANCE });
      if (d.diffPng) writeFileSync(join(outDir, `${tag}-diff.png`), d.diffPng);
      const pass = d.same ? true : d.ratio <= MAX_RATIO;
      if (!pass) failures++;
      const verdict = d.same ? 'SAME  ' : pass ? 'PASS  ' : 'DIFF  ';
      if (!d.same) {
        console.log(`  ${verdict} ${tag.padEnd(38)} diff=${d.diffPixels}px (${(d.ratio * 100).toFixed(3)}%) bbox=${JSON.stringify(d.bbox)} ${d.reason || ''} ` +
          `${d.diffPng ? `→ ${relative(ROOT, join(outDir, `${tag}-diff.png`))}` : ''} ${notes.join(' ')}`);
      } else {
        console.log(`  ${verdict} ${tag.padEnd(38)} ${d.width}x${d.height} (${ms}ms) ${notes.join(' ')}`);
      }
      rows.push({ tag, status: pass ? 'pass' : 'diff', diffPixels: d.diffPixels, ratio: d.ratio });
    }
  }

  await browser.close();

  if (MODE === 'update') {
    writeFileSync(join(baseDir, 'manifest.json'), JSON.stringify({
      generatedBy: 'scripts/visual-baseline.mjs --update',
      generatedAt: new Date().toISOString(),
      platform,
      browser: { label, version },
      viewport: { deviceScaleFactor: 1 },
      tolerance: TOLERANCE,
      maxRatio: MAX_RATIO,
      entries: rows,
    }, null, 2) + '\n');
    console.log(`\nbaseline written: ${rows.length} image(s) → ${relative(ROOT, baseDir)}/ (manifest.json 同步)`);
    console.log('人评审 diff 前请勿直接 --update 覆盖既有基准：基准只应在「确认变化是期望的」后更新。');
  } else {
    console.log(`\n${failures === 0 ? 'VISUAL BASELINE PASS' : `VISUAL BASELINE FAIL: ${failures} 项超阈值/缺失`}`);
    if (failures) console.log(`  阈值：单通道容差 ${TOLERANCE}，差异像素占比上限 ${(MAX_RATIO * 100).toFixed(2)}%`);
  }

  process.exit(MODE === 'update' ? 0 : failures === 0 ? 0 : 1);
}

captureAll().catch((e) => {
  console.error('visual-baseline: harness error —', e && e.message ? e.message : e);
  if (e && e.stack) console.error(e.stack.split('\n').slice(1, 4).join('\n'));
  process.exit(2);
});

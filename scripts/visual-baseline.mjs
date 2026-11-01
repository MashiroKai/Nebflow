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
//   NEBFLOW_WEB_ROOT=build/web-dist node scripts/visual-baseline.mjs --check
//                                                        # 验证**产物形态**（B0-3）
//   选项：--theme dark|light  --tolerance <0-255>  --max-ratio <0-1>  --platform <key>
//         --web-root <dir>   # 静态根覆盖（等价 NEBFLOW_WEB_ROOT，CLI 优先）
//
// 产物：
//   基准（入库）  tests/visual/baseline/<platform>/<scenario>-<theme>.png + manifest.json
//   运行/差图     .nebflow/visual-baseline/<platform>/  （.gitignore:17-18 已忽略 .nebflow/）
//
// 退出码：--check 通过 0；有超阈值差异 1；装置错误（无浏览器/无 playwright）2。

import { existsSync, mkdirSync, readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { ROOT, webRoot, setWebRoot, loadPlaywright, launchBrowser, createSession, settle, applyMasks, shoot } from './lib/visual-harness.mjs';
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
// B0-3（G3）：验证对象可切换。显式 `--web-root` 优先于 NEBFLOW_WEB_ROOT（后者
// 在 visual-harness.mjs 加载时已消费）。缺省 = 源树，与改造前逐字节相同。
const WEB_ROOT_OVERRIDE = valOf('--web-root');
if (WEB_ROOT_OVERRIDE) setWebRoot(WEB_ROOT_OVERRIDE);

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

// 代码块着色面（文档渲染批 2026-11-01）：markdown 围栏的 hljs 着色是新增面，
// 上面那份 CANVAS_MD 夹具只有行内代码、没有围栏——所以另立一条场景，把
// 「多语言围栏 + 无语言围栏（不高亮）+ 行内代码（另一种底色）」钉进基准图，
// 主题切换下的 token 配色错位能被像素比对直接抓住。
const CANVAS_MD_CODE = [
  '# 代码块着色基线',
  '',
  '围栏带语言标签才着色，行内代码用另一套底色。',
  '',
  '```js',
  'const cache = new Map();',
  'export function put(k, v) {',
  '  if (!cache.has(k)) cache.set(k, v);',
  '  return cache.size;   // 注释也该有独立配色',
  '}',
  '```',
  '',
  '```python',
  'def total(rows):',
  '    return sum(r["n"] for r in rows)',
  '```',
  '',
  '```',
  '不加语言标签的围栏：保持原样，不做词法分析。',
  '```',
  '',
  '行内代码 `const x = 1` 用于对照。',
].join('\n');

// ── 渲染器覆盖面补强（阅读/编辑体验优化批 2026-11-01）────────────────────
// 修前 12 张基准只覆盖 14 个渲染器里的 2 个（markdown / monaco）。
// 下面三条各自打在**不同的 viewer 模块 + 不同的 CSS 面**上：
//   txt   → viewers/textReader.js（.canvas-txt-scroll，纯文本段落面）
//   html  → viewers/html.js（srcdoc 沙箱 iframe + 主题变量注入）
//   image → viewers/image.js + viewers/zoom.js（缩放引擎工具条 + fit 几何）
// 三者都由 openWorkspaceItem 的既有入口驱动（与 canvas-markdown-tab 同路），
// 无一需要新的打桩端点：txt/html 走纯文本内容，image 走 data: URI 的
// `objectUrl` 腿（nfFileCache.nfViewerSource 命中 objectUrl 即短路取数）。
const CANVAS_TXT = [
  '视觉基线夹具 — 纯文本阅读面',
  '',
  '这一段用来验证 .txt 阅读视图的排版：段落分隔、空行、段距。',
  '',
  '- 阅读面：viewers/textReader.js（.canvas-txt-scroll）',
  '- 段落：行 → <p>，textContent 防 XSS，空白 pre-wrap 保留',
  '',
  '\f',
  '第二段（\\f 章界之后）：只做段距，不进任何操控件。',
  '这是第三行，用来给视觉基线一个稳定的行高读数。',
].join('\n');

const CANVAS_HTML = `<!doctype html>
<html><head><meta charset="utf-8"><style>
  body{margin:0;padding:18px;font:14px/1.7 system-ui,-apple-system,"Segoe UI",sans-serif;color:#c9d3e0;background:#0f1420}
  h1{font-size:17px;margin:0 0 10px;font-weight:600}
  p{margin:0 0 10px}
  .card{border:1px solid #2a3648;border-radius:10px;padding:10px 12px;margin:10px 0}
  ul{margin:0;padding-left:18px}
  li{margin:3px 0}
  code{background:#1b2434;border-radius:4px;padding:1px 5px}
</style></head><body>
  <h1>HTML viewer fixture</h1>
  <p>srcdoc 沙箱渲染：样式、列表、卡片 —— 主题变量由父页注入。</p>
  <div class="card"><ul>
    <li>缩放引擎用 transform 作用在整帧上，不改帧内 DOM</li>
    <li>引用选择 / 查找条挂在本面之外的壳层</li>
    <li>行内代码：<code>viewers/html.js</code></li>
  </ul></div>
</body></html>`;

// 图片夹具用 data: URI 的 SVG（无外部取数、无字体依赖、无随机像素）。
// 形状/纯色块在截图里的抗锯齿是稳定的（与主题无关 —— 画面颜色由夹具自定）。
const CANVAS_IMAGE_URI = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" width="480" height="300" viewBox="0 0 480 300">'
  + '<rect width="480" height="300" fill="#1b2434"/>'
  + '<rect x="24" y="24" width="200" height="120" rx="12" fill="#3b6ea5"/>'
  + '<circle cx="356" cy="196" r="72" fill="#7f5ad4"/>'
  + '<rect x="24" y="196" width="120" height="80" rx="10" fill="#2f9e6f"/>'
  + '<rect x="176" y="196" width="120" height="80" rx="10" fill="#c9a227"/>'
  + '</svg>',
);

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
    // G1 真修（B0-1）：uptime 文本 = now - startedAt，**字符数**随墙上时钟增长
    // （9m → 10m、9h → 10h、9d → 10d），该行 min-content 变宽 ⇒ 整块下拉变宽；
    // `mask: ['.bg-task-uptime']` 只涂像素、不遮布局。所以钉的是时钟，不是基准图。
    clock: 1757000553000, // = startedAt + 9m13s（t1 显示 9分13秒 / t2 9分12秒）
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
    mask: ['.bg-task-uptime'], // 真修是 clock（见上）；遮罩保留 = 第二道保险（uptime 区不进像素比）
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
    name: 'canvas-markdown-code-tab',
    why: 'canvas 页签 + markdown 代码块着色面（hljs token 配色 / 无语言围栏对照）—— 代码块渲染批的新增闸',
    themes: ['dark', 'light'],
    selector: '#canvas-panel',
    setup: async (s) => {
      await s.boot();
      await s.page.evaluate((content) => {
        window.dispatchEvent(new CustomEvent('workspace-open-item', {
          detail: {
            id: 'file:/tmp/visual-baseline-code-note.md', itemType: 'markdown', title: 'visual-baseline-code-note.md',
            content, absPath: '/tmp/visual-baseline-code-note.md', size: content.length, mtimeMs: 1757000000000,
          },
        }));
      }, CANVAS_MD_CODE);
      await paneReady(s.page, 'file:/tmp/visual-baseline-code-note.md');
      await s.page.waitForTimeout(900); // markdown 渲染 + hljs 着色
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
  {
    name: 'canvas-txt-tab',
    why: 'canvas 页签 + 纯文本阅读面（viewers/textReader.js）—— .txt 排版面的闸',
    themes: ['dark', 'light'],
    selector: '#canvas-panel',
    setup: async (s) => {
      await s.boot();
      await s.page.evaluate((content) => {
        window.dispatchEvent(new CustomEvent('workspace-open-item', {
          detail: {
            id: 'file:/tmp/visual-baseline-note.txt', itemType: 'code', title: 'visual-baseline-note.txt',
            content, absPath: '/tmp/visual-baseline-note.txt', size: content.length, mtimeMs: 1757000000000,
          },
        }));
      }, CANVAS_TXT);
      await paneReady(s.page, 'file:/tmp/visual-baseline-note.txt');
      await s.page.waitForTimeout(400);
      await settle(s.page, 250);
    },
  },
  {
    name: 'canvas-html-tab',
    why: 'canvas 页签 + HTML viewer（srcdoc 沙箱 iframe + 主题变量注入）—— html.js 的闸',
    themes: ['dark', 'light'],
    selector: '#canvas-panel',
    setup: async (s) => {
      await s.boot();
      await s.page.evaluate((content) => {
        window.dispatchEvent(new CustomEvent('workspace-open-item', {
          detail: {
            id: 'file:/tmp/visual-baseline-page.html', itemType: 'html', title: 'visual-baseline-page.html',
            content, absPath: '/tmp/visual-baseline-page.html', size: content.length, mtimeMs: 1757000000000,
          },
        }));
      }, CANVAS_HTML);
      await paneReady(s.page, 'file:/tmp/visual-baseline-page.html');
      // iframe srcdoc 的 load 是异步的：等帧内 document 真的画出来再拍。
      await s.page.waitForFunction(() => {
        const f = document.querySelector('#canvas-panel .canvas-tab-pane[data-tab-id="file:/tmp/visual-baseline-page.html"] iframe');
        try { return !!(f && f.contentDocument && f.contentDocument.body && f.contentDocument.body.children.length); } catch { return false; }
      }, null, { timeout: 15000 });
      await s.page.waitForTimeout(400);
      await settle(s.page, 250);
    },
  },
  {
    name: 'canvas-image-tab',
    why: 'canvas 页签 + 图片查看器与缩放引擎（image.js + zoom.js 工具条/fit 几何）—— 缩放面的闸',
    themes: ['dark', 'light'],
    selector: '#canvas-panel',
    setup: async (s) => {
      await s.boot();
      await s.page.evaluate((objectUrl) => {
        window.dispatchEvent(new CustomEvent('workspace-open-item', {
          detail: {
            id: 'file:/tmp/visual-baseline-image.svg', itemType: 'image', title: 'visual-baseline-image.svg',
            content: '', absPath: '/tmp/visual-baseline-image.svg', size: 0, mtimeMs: 1757000000000,
            objectUrl, // 字节已在手：nfViewerSource 短路，零取数（打桩下也不碰票据链）
          },
        }));
      }, CANVAS_IMAGE_URI);
      await paneReady(s.page, 'file:/tmp/visual-baseline-image.svg');
      // 图片 decode → onload → 几何 fit 后才出现 .canvas-image-viewport 与缩放条。
      await s.page.waitForSelector('#canvas-panel .canvas-tab-pane[data-tab-id="file:/tmp/visual-baseline-image.svg"] .canvas-zoom-bar', { timeout: 15000 });
      await s.page.waitForTimeout(500);
      await settle(s.page, 250);
    },
  },

  // ── B0-2（G2）：改前零覆盖的三个面 + 设置面常态 ───────────────────────
  // 这四条存在的理由不是"多拍几张"，而是**B1/B2/B4 要改的正是这三个面**：
  // `explorer.js` 的行构造（div → role=treeitem + roving tabindex）、
  // `canvas.js:2253` 的环卡壳、`index.html` 的 9 个 overlay 壳。没有改前基线，
  // 「只改了该改的」这句话就无法成立（必改像素会被当成回归，或反过来把回归
  // 当成必改像素）。每条都用夹具数据，不依赖真实文件系统内容。
  {
    name: 'explorer-tree',
    why: '文件树：项目根 + 两级展开 + 文件行（B1 行构造/缩进的改前对照物）',
    themes: ['dark', 'light'],
    setup: async (s) => {
      await s.boot();
      // 根列表（MockWS 的 listDir 固定应答 docs/src/README.md/CONTRIBUTING.md）
      await s.page.waitForSelector('#explorer-tree .explorer-item', { timeout: 10000 });
      // 一级展开：docs（目录行 → 子列表，子项路径前缀 docs/）
      await s.page.click('#explorer-tree .explorer-folder[data-path="docs"]');
      await s.page.waitForSelector('.explorer-dir-wrapper[data-path="docs"] .explorer-children .explorer-item', { timeout: 10000 });
      // 二级展开：docs/docs（缩进第三档 —— B1 要动的正是这条 padding 阶梯）
      await s.page.click('.explorer-dir-wrapper[data-path="docs"] .explorer-children .explorer-folder[data-path="docs/docs"]');
      await s.page.waitForSelector('.explorer-dir-wrapper[data-path="docs/docs"] .explorer-children .explorer-item', { timeout: 10000 });
      await settle(s.page, 300);
    },
  },
  {
    name: 'deck-cards',
    why: '叠卡环（≥2 张卡 + fab）—— B2 卡壳 a11y 与中心环的改前对照物',
    themes: ['dark', 'light'],
    setup: async (s) => {
      await s.boot();
      for (const [id, title, content] of [
        ['file:/tmp/deck-a.md', 'deck-a.md', CANVAS_MD],
        ['file:/tmp/deck-b.txt', 'deck-b.txt', CANVAS_TXT],
        ['file:/tmp/deck-c.txt', 'deck-c.txt', CANVAS_TXT],
      ]) {
        await s.page.evaluate((d) => {
          window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: d }));
        }, { id, itemType: 'markdown', title, content, absPath: id.replace('file:', ''), size: content.length, mtimeMs: 1757000000000 });
        await paneReady(s.page, id);
      }
      await s.page.click('#deck-fab');
      // 环卡弹簧动画收敛后才算稳定（settled ⇒ deckLoop 停帧）
      await s.page.waitForSelector('#deck-layer .deck-card', { timeout: 15000 });
      await s.page.waitForTimeout(1600);
      await settle(s.page, 400);
    },
  },
  {
    name: 'modal-confirm',
    why: '确认模态（#modal-overlay.on：遮罩 + 玻璃卡 + 两枚按钮）—— B4 层栈的改前对照物',
    themes: ['dark', 'light'],
    setup: async (s) => {
      await s.boot();
      await s.page.evaluate(() => {
        window.__showConfirm('删除后不可恢复，确定要删除这 3 个文件吗？', '删除确认', () => {}, {});
      });
      await s.page.waitForSelector('#modal-overlay.on', { timeout: 8000 });
      await settle(s.page, 300);
    },
  },
  {
    name: 'settings-panel',
    why: '设置面板壳常态（#settings-overlay.on 顶部）—— B4 壳入层栈的改前对照物',
    themes: ['dark', 'light'],
    setup: async (s) => {
      await s.boot({ config: { llm: { providers: {} }, features: { friends: false } } });
      await s.page.click('#settings-btn');
      await s.page.waitForSelector('#settings-overlay:not(.hidden)', { timeout: 10000 }).catch(() => {});
      await s.page.waitForTimeout(700);
      await settle(s.page, 300);
    },
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
  console.log(`  web    : ${relative(ROOT, webRoot())}  (root = ${webRoot()})`);
  console.log(`  baseline: ${relative(ROOT, baseDir)}`);
  console.log(`  out    : ${relative(ROOT, outDir)}\n`);

  const rows = [];
  let failures = 0;

  for (const sc of selected) {
    for (const theme of sc.themes) {
      const tag = `${sc.name}-${theme}`;
      const t0 = Date.now();
      const session = await createSession(browser, { colorScheme: theme, clock: sc.clock, apiStubs: { ...BASE_STUBS, ...(sc.apiStubs || {}) } });
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

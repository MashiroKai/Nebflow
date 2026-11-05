#!/usr/bin/env node
// edge-render-regression.cjs — AgentFlow（flow map）连线渲染 + 卡内文字可读性回归
// （UI 缺陷批 2026-11-05，作者报障逐字：「然后就是agentflow的显示有问题。」+
//   同日 17:05 补充裁定「连文字一起修」）。
//
// 报障三现象：① 连线变成粗黑楔形/带状色块；② 黑条横穿节点卡；③ 卡内文字被切/糊。
// 本脚本是**改前/改后同数据对照 + 三种图形回归 + 机检断言**的唯一入口：
//   · 数据面 = 真实 delegate 链夹具（delegateChainStub.__setFixture 的 rows/chains/
//     children 三读口），零后端、零 sbt、零 6138；
//   · 渲染面 = 真实生产模块（delegateFlowMap.js → flowMapTab.js → fmNodeCard.js +
//     css/flowMap.css + js/flowCss.js 运行时注入的 FLOW_CSS），零替身、零手绘；
//   · 状态面：--state=after 用磁盘当前树；--state=before 把本批改动的文件换成
//     `git show <base>:<path>` 的基线字节（同数据、只换代码 ⇒ 天然 A/B 对照）。
//
// 断言（after 态硬断言，任一红 exit 1；before 态只读数不判定）：
//   E1 连线形态 …… 每条 path.flow-edge 的 computed fill === 'none'（黑楔形的直接成因）
//                   且 computed stroke-width ∈ (0, 2.5]、stroke 色来自令牌
//   E2 图层序   …… svg.solar-edges 的 z-index < 每张节点卡的 z-index（连线恒在节点下）
//   E3 无遮挡   …… 逐卡在卡面内 9 点采样 elementFromPoint，命中必须是卡（或其子孙），
//                   绝不可是连线层 ⇒「连线盖住节点内容」不可发生
//   E4 无墨渍   …… 截图像素机检：卡矩形内「近黑像素」占比 < 0.5%（before 态该值极高，
//                   即黑楔形从卡底透上来的实测量）
//   E5 对比度   …… 卡内六类文字样式对卡底色的 WCAG 比值 ≥ 4.5（AA 正文），双主题各一遍
//   E6 名不截断 …… 最长节点名完整在场（标签无纵向裁切、DOM 文本 = 全名、卡高 > 88）
//   E7 说明不静默 …… 长说明卡：.fm-desc 打上 .fm-desc--clipped 且同卡「全文」入口可见
//
// 用法：
//   node scripts/edge-render-regression.cjs --state=after  --out=.nebflow/shots-edges/after
//   node scripts/edge-render-regression.cjs --state=before --out=.nebflow/shots-edges/before
//   node scripts/edge-render-regression.cjs --state=after --url=http://127.0.0.1:8093
//        （--url = 真跑隔离实例：静态资源走实例本体，仅 map 挂在页面内由生产入口执行）

const { chromium } = require('playwright');
const { readFileSync, mkdirSync, writeFileSync, existsSync } = require('node:fs');
const { join, extname, normalize } = require('node:path');
const { execFileSync } = require('node:child_process');

const ROOT = join(__dirname, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const ARG = (name, dflt) => {
  const hit = process.argv.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.split('=').slice(1).join('=') : dflt;
};
const STATE = ARG('state', 'after');
const OUT = join(ROOT, ARG('out', `.nebflow/shots-edges/${STATE}`));
const URL_ARG = ARG('url', '');
// 基线 = 本批起点（分支基）：before 态逐字重放这些文件的基线字节。
const BASE = ARG('base', execFileSync('git', ['rev-parse', 'HEAD'], { cwd: ROOT }).toString().trim());
const CHANGED = [
  'css/flowMap.css',
  'js/fmNodeCard.js',
  'js/flowMapTab.js',
  'js/locales/zh-CN.js',
  'js/locales/en.js',
];
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const DSF = 2; // 截图清晰度：autoFit 下卡宽 ~56 CSS px，靠 2x 渲染保证可读
const ROOT_SID = 'edge-root-session';
const T0 = Date.now() - 3 * 60 * 60 * 1000;

// ── 夹具：真实 delegate 链形状（root 卡 + flow-step 成员，deps 定层）──────────
// 名字全部用作者截屏里的同族长名（term-*）——E6 的「最长名不截断」靠它们取证。
const N = (id, label, status, deps = [], description = '') => ({
  kind: 'flow-step', id, label, status, deps, description,
});
const CH_ROOT = (key, delegateId, label, status) => ({
  kind: 'delegate', id: delegateId, label, status,
});

/** ① 作者同款图形：4 路扇出 + 三层纵深 + 一个失败节点 + 排队位次胶囊。 */
function fixtureAuthor() {
  const key = '1603';
  const delegateId = 'delegate-term-a1b2c3d4';
  const nodes = [
    CH_ROOT(key, delegateId, '规范+术语收口', 'ready-to-merge'),
    N('term-recon', 'term-recon', 'completed', [delegateId], '① 现状核查回传：术语清单与消费面盘点'),
    N('term-scalatags', 'term-scalatags', 'completed', [delegateId], '④ Scala WikiTerm 标签族收敛'),
    N('term-merge', 'term-merge', 'completed', [delegateId], '只解 SKILL.md 内的术语映射'),
    N('term-commit', 'term-commit', 'completed', [delegateId], '完成合并提交 + 收口报告'),
    N('term-render', 'term-render', 'completed', ['term-recon'], '⑥ 词条渲染收口：卡片面与详情面同源'),
    N('term-syntax', 'term-syntax', 'completed', ['term-scalatags'], '① Python 抽取器语法收口'),
    N('term-gate', 'term-gate', 'completed', ['term-render'], '④ 门禁：引用文本比对与差异登记'),
    N('term-indexcli', 'term-indexcli', 'completed', ['term-syntax'], '②③ 索引库 + CLI 检索面'),
    N('term-inventory', 'term-inventory', 'completed', ['term-gate'], '⑥ 存量盘点回执：孤儿词条清单'),
    N('term-verify', 'term-verify', 'failed', ['term-gate', 'term-indexcli'], '验收：七件是否齐备并逐条取证'),
    N('term-enginedb', 'term-enginedb', 'completed', ['term-indexcli'], '② 引擎读侧验证：词条命中率'),
    N('term-report', 'term-report', 'completed', ['term-inventory', 'term-verify', 'term-enginedb'], '写收口报告（r4 版本，含留红说明）'),
  ];
  return {
    rows: [{
      taskNo: key, delegateId, title: '【规范+术语统一】词条渲染收口批',
      executor: 'nebflow', status: 'ready-to-merge', project: '', createdAt: T0,
      orchestrationMode: 'agentflow', archivedAt: null,
      mergeQueue: { ahead: 2, holders: [{ id: 'term-verify', name: 'term-verify', status: 'running' }] },
      mergeQueuePos: { position: 1, total: 3, queue: 'main', arrived: true, readyAt: T0, createdAt: T0 },
    }],
    chains: [{ taskId: key, delegateId, nodes,
      worktree: { worktree: '/tmp/wt-term', allocation: 'dispatcher' },
      plugins: ['stand-dev'], allocation: 'dispatcher' }],
    children: {},
  };
}

/** ② 窄链：5 层单链（每层 1 卡），验证「同一竖列上的边」不变形。 */
function fixtureChain() {
  const key = '2101';
  const delegateId = 'delegate-chain-2101a1b2';
  const ids = ['term-intake', 'term-normalize', 'term-indexcli', 'term-verify', 'term-report'];
  const nodes = [CH_ROOT(key, delegateId, '窄链回归', 'running')];
  let prev = delegateId;
  ids.forEach((id, i) => {
    nodes.push(N(id, id, i < 4 ? 'completed' : 'running', [prev], `第 ${i + 1} 层的说明文字`));
    prev = id;
  });
  return {
    rows: [{ taskNo: key, delegateId, title: '窄链图形回归', executor: 'nebflow', status: 'running', project: '', createdAt: T0, orchestrationMode: 'agentflow', archivedAt: null, mergeQueue: null, mergeQueuePos: null }],
    chains: [{ taskId: key, delegateId, nodes, worktree: { worktree: '/tmp/wt-chain', allocation: 'dispatcher' }, allocation: 'dispatcher' }],
    children: {},
  };
}

/** ③ 宽扇出：root 直连 8 个子节点（最大水平张角），验证楔形角度畸变面。 */
function fixtureFan() {
  const key = '2102';
  const delegateId = 'delegate-fan-2102c3d4';
  const nodes = [CH_ROOT(key, delegateId, '宽扇出回归', 'running')];
  for (let i = 0; i < 8; i += 1) {
    nodes.push(N(`term-fan-${String(i + 1).padStart(2, '0')}`, `term-fan-${i + 1}`, i % 3 === 0 ? 'completed' : (i % 3 === 1 ? 'running' : 'pending'), [delegateId], `扇出第 ${i + 1} 路的说明`));
  }
  return {
    rows: [{ taskNo: key, delegateId, title: '宽扇出图形回归', executor: 'nebflow', status: 'running', project: '', createdAt: T0, orchestrationMode: 'agentflow', archivedAt: null, mergeQueue: null, mergeQueuePos: null }],
    chains: [{ taskId: key, delegateId, nodes, worktree: { worktree: '/tmp/wt-fan', allocation: 'dispatcher' }, allocation: 'dispatcher' }],
    children: {},
  };
}

/** ④ 多层深链 + 两条分支：7 层（最长名 + 最长说明同卡），验证深层数量下不畸变。 */
function fixtureDeep() {
  const key = '2103';
  const delegateId = 'delegate-deep-2103e5f6';
  const nodes = [CH_ROOT(key, delegateId, '多层深链回归', 'running')];
  const spine = ['term-scmergeb', 'term-indexcli', 'term-inventory-reconcile', 'term-verify'];
  let prev = delegateId;
  for (let i = 0; i < 7; i += 1) {
    const id = i < spine.length ? spine[i] : `term-stage-${i + 1}`;
    const desc = i === 2
      ? '存量盘点回执：把词条索引、消费面、孤儿条目三张表逐条对账并登记差异（这段说明刻意写长，用来验证两行封顶后「全文」入口在场）'
      : `第 ${i + 1} 层：${id} 的说明`;
    nodes.push(N(id, i === 2 ? 'term-inventory-reconcile-2026' : id, i < 6 ? 'completed' : 'running', [prev], desc));
    prev = id;
  }
  nodes.push(N('term-side-branch', 'term-side-branch', 'blocked', ['term-indexcli'], '旁支：与主链并行的一层'));
  return {
    rows: [{ taskNo: key, delegateId, title: '多层深链图形回归', executor: 'nebflow', status: 'running', project: '', createdAt: T0, orchestrationMode: 'agentflow', archivedAt: null, mergeQueue: null, mergeQueuePos: null }],
    chains: [{ taskId: key, delegateId, nodes, worktree: { worktree: '/tmp/wt-deep', allocation: 'dispatcher' }, plugins: [], allocation: 'dispatcher' }],
    children: {},
  };
}

const SHAPES = [
  { name: 'author', key: '1603', fx: fixtureAuthor() },
  { name: 'chain', key: '2101', fx: fixtureChain() },
  { name: 'fan', key: '2102', fx: fixtureFan() },
  { name: 'deep', key: '2103', fx: fixtureDeep() },
];

// ── 基线字节（before 态）────────────────────────────────────────────────────
const baselineBytes = {};
if (STATE === 'before') {
  for (const rel of CHANGED) {
    baselineBytes['/' + rel] = execFileSync('git', ['show', `${BASE}:src/main/resources/web/${rel}`], { cwd: ROOT });
  }
}

async function serveApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
  });
  // 真跑隔离实例（--url）：静态资源由实例本体供给（真 HTTP、真 jar），只有数据面
  // `/api/*` 用夹具应答——「真实例 + 单一数据缝」是对 instance 类证据最省的诚实形态。
  if (URL_ARG) {
    await page.route('**/api/**', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }));
    await page.route('**/agents/**', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }));
  }
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (URL_ARG) return route.fallback(); // 实例态：除上面两条数据缝外全部放行到真实例
    if (baselineBytes[p]) {
      return route.fulfill({ status: 200, contentType: MIME[extname(p)] || 'application/octet-stream', body: baselineBytes[p] });
    }
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p.startsWith('/agents/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'nf' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: ROOT_SID,
    }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto(URL_ARG || 'http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.ws && s.ws.readyState === 1 && !!rootSid;
  }, ROOT_SID, { timeout: 20000 });
  await page.waitForTimeout(250);
}

/** 把 map 挂进页面（生产入口 renderDelegateFlowMap，非手搓 DOM）。 */
async function mountMap(page, fx, key) {
  await page.evaluate(async ({ f, k }) => {
    const stub = await import('/js/delegateChainStub.js');
    stub.__setFixture(f);
    // 复刻真机入口序：delegateChainView.openDelegateChainStructure 在挂图前调
    // ensureFlowCss()（delegateChainView.js:160）——FLOW_CSS 是 .solar-node 的
    // position/width/轨道语言与卡面配色的唯一来源，缺它整张图退化成静态竖排，
    // A/B 两态都会失真。本 harness 显式复刻该序（after 态另有 emitter 自持兜底）。
    const { ensureFlowCss } = await import('/js/flowCss.js');
    ensureFlowCss();
    const { renderDelegateFlowMap } = await import('/js/delegateFlowMap.js');
    document.querySelectorAll('#edge-harness-host').forEach((el) => el.remove());
    const host = document.createElement('div');
    host.id = 'edge-harness-host';
    // 宿主形态跟随真实容器：块级、满幅、有界高度（.flowmap-card 自身 flex column，
    // 视口高度由 .flowmap-card .fm-viewport 的 clamp 定）——不另加 flex 布局，
    // 否则 /.solar-canvas 的宽高语义与真机不同，截图不可比。
    host.style.cssText = 'position:fixed;inset:0;z-index:99999;background:var(--color-bg);overflow:hidden;';
    document.body.appendChild(host);
    renderDelegateFlowMap(host, k);
  }, { f: fx, k: key });
  await page.waitForSelector('#edge-harness-host .fm-node', { timeout: 15000 });
  // 实测高重排 + 相机 fit 两趟 rAF 落定后再取证
  await page.waitForTimeout(900);
  // 取证不做任何相机操作：保持 autoFit（= 用户打开链视图看到的第一帧，可控、可复现），
  // 清晰度靠 deviceScaleFactor=2 + 裁到图面边界拿到（见截图段）。
  // 光标撤离图面：hover 联动会把非邻接卡打进 .fm-dim（opacity .15）——那是**交互态**，
  // 不是静止态；取证必须取静止态（否则读到的是 dim 后的合成比，误判为对比度不足）。
  await page.mouse.move(4, 4);
  // 静止态落定：卡级 opacity 过渡 0.4s / hover 恢复 0.35s —— 读满 700ms 再取证，
  // 否则读到的是过渡中间值（实测 0.337 / 0.763，会误判成对比度不足）。
  await page.waitForTimeout(700);
}

// ── 页内读数：卡内文字样式 × 实际合成底色（令牌值全部来自 getComputedStyle）──
const READ_PROBE = () => {
  const parse = (s) => {
    const m = String(s).match(/rgba?\(([^)]+)\)/);
    if (!m) return [0, 0, 0, 1];
    const p = m[1].split(/[,/\s]+/).filter(Boolean).map(Number);
    return [p[0], p[1], p[2], p.length > 3 ? p[3] : 1];
  };
  const over = (fg, bg) => {
    const a = fg[3];
    return [fg[0] * a + bg[0] * (1 - a), fg[1] * a + bg[1] * (1 - a), fg[2] * a + bg[2] * (1 - a), 1];
  };
  // 元素自身 → 根：逐层 alpha 合成背景（bg 半透明就继续往上叠），并乘出 opacity 链
  const compose = (el) => {
    let acc = null; let opacity = 1;
    for (let n = el; n && n.nodeType === 1; n = n.parentElement) {
      const cs = getComputedStyle(n);
      const bg = parse(cs.backgroundColor);
      if (bg[3] > 0) acc = acc ? over(acc, bg) : (bg[3] >= 0.999 ? bg : bg);
      if (bg[3] >= 0.999) { acc = bg; break; }
      opacity *= parseFloat(cs.opacity || '1');
    }
    if (!acc) acc = [255, 255, 255, 1];
    // 收集链上 opacity（从元素到该 bg 层）
    let op = 1;
    for (let n = el; n && n.nodeType === 1; n = n.parentElement) {
      op *= parseFloat(getComputedStyle(n).opacity || '1');
      if (op === 0) break;
    }
    return { bg: acc, opacity: op };
  };
  const card = document.querySelector('#edge-harness-host .flowmap-card') || document.body;
  const pick = (sel, label) => {
    const el = card.querySelector(sel);
    if (!el) return null;
    const cs = getComputedStyle(el);
    const { bg, opacity } = compose(el);
    return {
      label, selector: sel,
      text: (el.textContent || '').trim().slice(0, 60),
      color: cs.color, colorRaw: parse(cs.color),
      bgComposite: bg, opacityChain: opacity,
      fontSize: cs.fontSize, fontWeight: cs.fontWeight, fontFamily: cs.fontFamily,
      fill: cs.fill, stroke: cs.stroke, strokeWidth: cs.strokeWidth,
    };
  };
  const samples = [
    pick('.fm-node .solar-node-label', '节点名'),
    pick('.fm-node .solar-node-sub', '预设·身份'),
    pick('.fm-node .fm-desc', '说明文字'),
    pick('.fm-node .fm-st-word', '状态词'),
    pick('.fm-node .fm-flag-badge.fm-flag-queue', '排队位次胶囊'),
    pick('.fm-node .fm-flag-badge', '类型徽标'),
  ].filter(Boolean);

  const paths = [...card.querySelectorAll('svg.solar-edges path.flow-edge')].map((p) => {
    const cs = getComputedStyle(p);
    const bb = p.getBBox();
    return {
      cls: p.getAttribute('class'), fill: cs.fill, stroke: cs.stroke,
      strokeWidth: cs.strokeWidth, bbox: { w: +bb.width.toFixed(1), h: +bb.height.toFixed(1) },
    };
  });
  const svg = card.querySelector('svg.solar-edges');
  const nodes = [...card.querySelectorAll('.fm-node')].map((el) => {
    const r = el.getBoundingClientRect();
    const label = el.querySelector('.solar-node-label');
    const desc = el.querySelector('.fm-desc');
    const more = el.querySelector('.fm-desc-more');
    return {
      id: el.getAttribute('data-node-id'),
      rect: { x: r.x, y: r.y, w: r.width, h: r.height },
      zIndex: getComputedStyle(el).zIndex,
      labelText: label ? label.textContent : '',
      labelClip: label ? { sh: label.scrollHeight, ch: label.clientHeight, sw: label.scrollWidth, cw: label.clientWidth } : null,
      descFull: desc ? (desc.getAttribute('title') || '') : '',
      descDomLen: desc ? (desc.textContent || '').length : 0,
      descClipped: desc ? desc.classList.contains('fm-desc--clipped') : false,
      moreVisible: more ? getComputedStyle(more).display !== 'none' : false,
      bg: getComputedStyle(el).backgroundColor,
    };
  });
  // E3：卡面内 9 点采样，命中者必须是卡（或其子孙）。采样点先与**视口内可见区域**
  // （.fm-viewport 的裁剪矩形 ∩ 视口）求交——视口外的点 elementFromPoint 恒 null，
  // 那不是「被遮挡」，是「不在屏幕上」，不能混判。
  const vpEl = card.querySelector('.fm-viewport') || card;
  const vpr = vpEl.getBoundingClientRect();
  const vis = {
    x0: Math.max(0, vpr.x), y0: Math.max(0, vpr.y),
    x1: Math.min(window.innerWidth, vpr.x + vpr.width), y1: Math.min(window.innerHeight, vpr.y + vpr.height),
  };
  const hits = [];
  let hitsSkipped = 0;
  for (const el of card.querySelectorAll('.fm-node')) {
    const r = el.getBoundingClientRect();
    for (const fx of [0.15, 0.5, 0.85]) {
      for (const fy of [0.15, 0.5, 0.85]) {
        const x = r.x + r.width * fx; const y = r.y + r.height * fy;
        if (x < vis.x0 || x > vis.x1 || y < vis.y0 || y > vis.y1) { hitsSkipped += 1; continue; }
        const hit = document.elementFromPoint(x, y);
        const inCard = !!hit && (hit === el || el.contains(hit));
        hits.push({ id: el.getAttribute('data-node-id'), x: Math.round(x), y: Math.round(y), inCard, hitTag: hit ? hit.tagName : null, hitCls: hit ? String(hit.getAttribute('class') || '') : null });
      }
    }
  }
  // 边采样点落入卡矩形的情况（连线本来就串在两卡之间：只记数量，判据交给 E2/E3）
  const edgeInCard = [];
  for (const p of card.querySelectorAll('svg.solar-edges path.flow-edge')) {
    const len = p.getTotalLength();
    let inside = 0; const samples = 60;
    for (let i = 0; i <= samples; i += 1) {
      const pt = p.getPointAtLength((len * i) / samples);
      const m = p.getScreenCTM();
      const sx = pt.x * m.a + pt.y * m.c + m.e; const sy = pt.x * m.b + pt.y * m.d + m.f;
      for (const el of card.querySelectorAll('.fm-node')) {
        const r = el.getBoundingClientRect();
        if (sx >= r.x && sx <= r.x + r.width && sy >= r.y && sy <= r.y + r.height) { inside += 1; break; }
      }
    }
    edgeInCard.push({ cls: p.getAttribute('class'), insideSamples: inside, total: samples + 1 });
  }
  return {
    samples, paths, nodes, hits, edgeInCard,
    svgZ: svg ? getComputedStyle(svg).zIndex : null,
    svgPosition: svg ? getComputedStyle(svg).position : null,
    svgPointerEvents: svg ? getComputedStyle(svg).pointerEvents : null,
    nodeCount: nodes.length,
  };
};

// ── WCAG ────────────────────────────────────────────────────────────────────
const srgb = (c) => {
  const v = c / 255;
  return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
};
const lum = ([r, g, b]) => 0.2126 * srgb(r) + 0.7152 * srgb(g) + 0.0722 * srgb(b);
const ratio = (a, b) => {
  const la = lum(a); const lb = lum(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
};
const overA = ([r, g, b, a], bg) => [r * a + bg[0] * (1 - a), g * a + bg[1] * (1 - a), b * a + bg[2] * (1 - a)];
const hex = (c) => `#${c.slice(0, 3).map((v) => Math.round(v).toString(16).padStart(2, '0')).join('')}`;

// ── 极简 PNG 解码（Chromium 截图：8bit / 非隔行 / colorType 2|6）──────────────
function decodePng(buf) {
  const zlib = require('node:zlib');
  let pos = 8; let w = 0; let h = 0; let ct = 6; const idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos); const type = buf.toString('ascii', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === 'IHDR') { w = data.readUInt32BE(0); h = data.readUInt32BE(4); ct = data[9]; }
    else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
    pos += 12 + len;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const bpp = ct === 6 ? 4 : 3;
  const stride = w * bpp;
  const out = Buffer.alloc(stride * h);
  let p = 0;
  for (let y = 0; y < h; y += 1) {
    const filter = raw[p]; p += 1;
    const line = raw.subarray(p, p + stride); p += stride;
    const cur = out.subarray(y * stride, (y + 1) * stride);
    const prev = y > 0 ? out.subarray((y - 1) * stride, y * stride) : null;
    for (let x = 0; x < stride; x += 1) {
      const a = x >= bpp ? cur[x - bpp] : 0;
      const b = prev ? prev[x] : 0;
      const c = prev && x >= bpp ? prev[x - bpp] : 0;
      let v = line[x];
      if (filter === 1) v += a;
      else if (filter === 2) v += b;
      else if (filter === 3) v += (a + b) >> 1;
      else if (filter === 4) {
        const pp = a + b - c; const pa = Math.abs(pp - a); const pb = Math.abs(pp - b); const pc = Math.abs(pp - c);
        v += (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
      }
      cur[x] = v & 0xff;
    }
  }
  return { w, h, bpp, data: out };
}

/**
 * E4：卡面「底色一致性」——卡矩形内逐点取 luma，以**众数档**（0.02 分箱）为「纸面」，
 * 统计偏离众数 > 0.15 的像素占比。
 * 为什么这样量：报障现象②③是「黑条横穿节点卡 / 文字被黑带切过」，它的可测形态就是
 * 「卡内本该恒定的纸面出现成片异色」。而卡内文字/徽标/说明 chip 只占少数像素（实测
 * ≤12%），连线楔形是成片的（远超 25%）。判据取 ≤25%，两态读数见报告对照表。
 * 直接量像素（截图解码），不用 DOM 推。
 */
function cardBleedRatio(png, rects, dpr) {
  const { w, h, bpp, data } = png;
  const lumaAt = (x, y) => {
    const i = (y * w + x) * bpp;
    return (0.2126 * data[i] + 0.7152 * data[i + 1] + 0.0722 * data[i + 2]) / 255;
  };
  const perCard = [];
  for (const r of rects) {
    const x0 = Math.max(0, Math.round(r.x * dpr) + 2); const y0 = Math.max(0, Math.round(r.y * dpr) + 2);
    const x1 = Math.min(w - 1, Math.round((r.x + r.w) * dpr) - 2); const y1 = Math.min(h - 1, Math.round((r.y + r.h) * dpr) - 2);
    if (x1 <= x0 || y1 <= y0) continue;
    const bins = new Map(); const vals = [];
    for (let y = y0; y <= y1; y += 2) {
      for (let x = x0; x <= x1; x += 2) {
        const l = lumaAt(x, y); vals.push(l);
        const k = Math.round(l / 0.02); bins.set(k, (bins.get(k) || 0) + 1);
      }
    }
    let modeBin = 0; let modeN = -1;
    for (const [k, n] of bins) if (n > modeN) { modeN = n; modeBin = k; }
    const mode = modeBin * 0.02;
    let off = 0;
    for (const l of vals) if (Math.abs(l - mode) > 0.15) off += 1;
    perCard.push({ mode: +mode.toFixed(3), offPct: +(100 * off / vals.length).toFixed(2), n: vals.length });
  }
  const worst = perCard.reduce((a, b) => (b.offPct > a.offPct ? b : a), { mode: 0, offPct: 0, n: 0 });
  const avg = perCard.length ? perCard.reduce((a, b) => a + b.offPct, 0) / perCard.length : 0;
  return { perCard, worst, avgPct: +avg.toFixed(2), cards: perCard.length };
}

// ── 主流程 ──────────────────────────────────────────────────────────────────
(async () => {
  const browser = await chromium.launch();
  const readouts = { state: STATE, base: BASE, url: URL_ARG || '(disk-served)', shapes: {}, contrast: {} };

  for (const scheme of ['light', 'dark']) {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: scheme, deviceScaleFactor: DSF });
    const page = await ctx.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message)));
    page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
    await serveApp(page);

    for (const shape of SHAPES) {
      await mountMap(page, shape.fx, shape.key);
      const ro = await page.evaluate(READ_PROBE);
      if (scheme === 'light') {
        // 截图（E4 用同一张图）
        const shot = join(OUT, `${shape.name}.png`);
        // 裁到图面（.flowmap-card 的矩形 + 8px 余量）：去掉大片空白，图面本身占满画面
        // 裁到**内容包围盒**（全部节点卡 rect 的并集 + 30px 余量，再与视口求交）：
        // 图面卡是满宽容器，直接裁卡边界会留下大片空白、字看不清。
        const clip = await page.evaluate(() => {
          const els = [...document.querySelectorAll('#edge-harness-host .fm-node')];
          if (!els.length) return { x: 0, y: 0, width: innerWidth, height: innerHeight };
          const rs = els.map((e) => e.getBoundingClientRect());
          const x0 = Math.max(0, Math.min(...rs.map((r) => r.x)) - 30);
          const y0 = Math.max(0, Math.min(...rs.map((r) => r.y)) - 30);
          const x1 = Math.min(innerWidth, Math.max(...rs.map((r) => r.x + r.width)) + 30);
          const y1 = Math.min(innerHeight, Math.max(...rs.map((r) => r.y + r.height)) + 30);
          return { x: x0, y: y0, width: x1 - x0, height: y1 - y0 };
        });
        await page.screenshot({ path: shot, clip });
        // 像素读数必须用**整页截图**坐标（节点 rect 是视口坐标）：另拍一张不做 clip 的
        // 缓冲图供 E4 用（DSF 同步换算），避免裁切偏移把卡矩形算错位。
        await page.screenshot({ path: join(OUT, `.${shape.name}-full.png`), fullPage: false });
        const png = decodePng(readFileSync(join(OUT, `.${shape.name}-full.png`)));
        ro.ink = cardBleedRatio(png, ro.nodes.map((n) => n.rect), DSF);
        readouts.shapes[shape.name] = { ...ro, shot, nodeCount: ro.nodes.length, edgeCount: ro.paths.length };
      } else {
        readouts.shapes[shape.name] = readouts.shapes[shape.name] || {};
        readouts.shapes[shape.name].darkTheme = { samples: ro.samples, nodes: ro.nodes, paths: ro.paths, svgZ: ro.svgZ };
        readouts.contrast[`${shape.name}/dark`] = ro.samples.map((s) => contrastOf(s));
      }
    }
    readouts.contrast[`author/light`] = readouts.shapes.author.samples.map((s) => contrastOf(s));
    readouts[`errors_${scheme}`] = errors;
    await ctx.close();
    if (scheme === 'light') break; // 先跑 light（截图 + 断言），dark 只补对比度读数
  }

  function contrastOf(s) {
    const bg = s.bgComposite;
    const fg = overA([s.colorRaw[0], s.colorRaw[1], s.colorRaw[2], s.colorRaw[3] * s.opacityChain], bg);
    const r = ratio(fg, bg);
    return {
      label: s.label, selector: s.selector, text: s.text,
      color: hex(s.colorRaw), opacityChain: +s.opacityChain.toFixed(3),
      bg: hex(bg), ratio: +r.toFixed(2),
      verdict: r >= 7 ? 'AAA' : r >= 4.5 ? 'AA' : 'FAIL',
      fontSize: s.fontSize, fontWeight: s.fontWeight,
    };
  }

  // dark 主题补读数（同一夹具，只采样式）
  {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'dark', deviceScaleFactor: DSF });
    const page = await ctx.newPage();
    await serveApp(page);
    for (const shape of SHAPES) {
      await mountMap(page, shape.fx, shape.key);
      const ro = await page.evaluate(READ_PROBE);
      readouts.contrast[`${shape.name}/dark`] = ro.samples.map(contrastOf);
      if (shape.name === 'author') {
        readouts.shapes.author.darkTheme = { samples: ro.samples, nodeCount: ro.nodes.length };
        await page.screenshot({ path: join(OUT, 'author-dark.png'), fullPage: false });
      }
    }
    await ctx.close();
  }
  readouts.contrast['author/light'] = readouts.shapes.author.samples.map(contrastOf);
  await browser.close();

  // ── 断言（after 态）──────────────────────────────────────────────────────
  const fails = [];
  const ok = (cond, msg) => { if (!cond) fails.push(msg); };
  const A = readouts.shapes.author;
  if (STATE === 'after') {
    for (const [name, s] of Object.entries(readouts.shapes)) {
      for (const p of s.paths) {
        ok(p.fill === 'none', `${name}: 边 fill=${p.fill}（必须 none——黑楔形成因）[${p.cls}]`);
        const sw = parseFloat(p.strokeWidth);
        ok(sw > 0 && sw <= 2.5, `${name}: 边 stroke-width=${p.strokeWidth} 超界（0,2.5]`);
      }
      const svgZ = parseInt(s.svgZ, 10);
      for (const n of s.nodes) {
        const nz = parseInt(n.zIndex, 10);
        ok(Number.isFinite(svgZ) && Number.isFinite(nz) && svgZ < nz, `${name}: 图层序 svg z=${s.svgZ} 未低于卡 z=${n.zIndex} (${n.id})`);
      }
      for (const h of s.hits) ok(h.inCard, `${name}: 卡面采样点被非卡元素命中 (${h.id} @${h.x},${h.y} → ${h.hitTag}.${h.hitCls})`);
      ok(s.ink.worst.offPct <= 25, `${name}: 卡面底色异色像素 ${s.ink.worst.offPct}% > 25%（有异物带透上/压在卡面上）`);
    }
    // E5 对比度（双主题）
    for (const [k, rows] of Object.entries(readouts.contrast)) {
      for (const r of rows) {
        if (['节点名', '预设·身份', '说明文字', '状态词'].includes(r.label)) {
          ok(r.ratio >= 4.5, `${k}: ${r.label} 对比度 ${r.ratio}:1 < 4.5 (${r.color} on ${r.bg})`);
        }
      }
    }
    // E6 长名不截断（author 与 deep 两张各取最长名）
    for (const name of ['author', 'deep']) {
      const s = readouts.shapes[name];
      const longest = s.nodes.slice().sort((a, b) => b.labelText.length - a.labelText.length)[0];
      ok(longest.labelClip.sh <= longest.labelClip.ch + 1, `${name}: 最长名「${longest.labelText}」纵向被裁 (sh=${longest.labelClip.sh} ch=${longest.labelClip.ch})`);
      ok(longest.labelText.length > 0, `${name}: 最长名为空`);
    }
    // E7 说明不静默省略
    const deep = readouts.shapes.deep;
    const withLongDesc = deep.nodes.find((n) => n.descFull && n.descFull.length > 40);
    ok(!!withLongDesc, 'deep: 未找到长说明节点（夹具异常）');
    if (withLongDesc) {
      ok(withLongDesc.descDomLen >= withLongDesc.descFull.length, `deep: 说明 DOM 文本被截（${withLongDesc.descDomLen} < ${withLongDesc.descFull.length}）`);
      ok(withLongDesc.descClipped, 'deep: 长说明未打 .fm-desc--clipped');
      ok(withLongDesc.moreVisible, 'deep: 「全文」入口不可见');
    }
  }

  const report = { ...readouts, fails, pass: fails.length === 0 };
  writeFileSync(join(OUT, 'readouts.json'), JSON.stringify(report, null, 2));
  const brief = {
    state: STATE, out: OUT, pass: fails.length === 0, fails,
    shapes: Object.fromEntries(Object.entries(readouts.shapes).map(([k, v]) => [k, {
      nodes: v.nodeCount, edges: v.edgeCount,
      edgeFills: [...new Set(v.paths.map((p) => p.fill))],
      edgeWidths: [...new Set(v.paths.map((p) => p.strokeWidth))],
      svgZ: v.svgZ, cardZ: [...new Set(v.nodes.map((n) => n.zIndex))],
      inkPct: v.ink.worst.offPct,
      hitAllInCard: v.hits.every((h) => h.inCard),
      longestName: v.nodes.slice().sort((a, b) => b.labelText.length - a.labelText.length)[0].labelText,
    }])),
    contrast_light: readouts.contrast['author/light'].map((r) => `${r.label} ${r.ratio}:1 ${r.verdict}`),
    contrast_dark: readouts.contrast['author/dark'].map((r) => `${r.label} ${r.ratio}:1 ${r.verdict}`),
  };
  console.log(JSON.stringify(brief, null, 2));
  if (STATE === 'after' && fails.length) process.exit(1);
})().catch((e) => { console.error('HARNESS ERROR', e); process.exit(2); });

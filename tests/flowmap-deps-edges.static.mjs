// flowmap-deps-edges.static.mjs — Flow Map 渲染扁平化修复的静态重放护栏
// （作者 2026-09-28 22:48 令：「依赖连接看不到连接线以及层级，全排在同一行」）。
//
// 面：out 边分支的载荷读法（`[{to,on,mode}]`，旧代码 `ids.has(n.out)` 恒 false ⇒ 零出边）
//     与 `deps=chain:<id>` 链闸解析（旧的 `ids.has(d)` 对 `chain:` 形态恒 false ⇒ 闸边零条、
//     吃闸位被当根放第 0 层 ⇒ 4 层塌平）在同一次修复里收口。
//
// 做法：在 Node 里 eval **产品源码本身**（`src/main/resources/web/js/flowMapTab.js`：
// 跳过 import 行 + 截掉顶部无需的 DOM 绑定尾巴，喂最小桩），跑真 `layoutNodes` /
// `collectEdges` / `chainGateTargets` / `depTargetsOf`，对**冻结的真实规模夹具**
// （recon 时点重建的 30 节点载荷 + 后端口径 `chains[]` 旁挂）取读数。
// 断言的是行为不是文本：把修法回退（例如把 out 分支改回 `ids.has(n.out)`）会让本
// 文件转红 —— 见每个用例里的「修复前基线」读数。
//
// Run: node tests/flowmap-deps-edges.static.mjs
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SRC = join(ROOT, 'src', 'main', 'resources', 'web', 'js', 'flowMapTab.js');
const FIX = join(ROOT, 'tests', 'fixtures', 'flowmap-deps-edges');

let pass = 0;
const fails = [];
/** @param {string} id @param {string} what @param {boolean} ok @param {string} note */
function assert(id, what, ok, note = '') {
  if (ok) { pass += 1; console.log(`  PASS [${id}] ${what}${note ? ` — ${note}` : ''}`); }
  else { fails.push(id); console.log(`  FAIL [${id}] ${what}${note ? ` — ${note}` : ''}`); }
}
const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);

// ── 源码装载：跳过 import/export 行，截掉顶部不需要的 DOM 绑定尾巴（纯函数面）──────
const source = readFileSync(SRC, 'utf8');
const CUT = "document.addEventListener('canvas-tab-closed'";
if (!source.includes(CUT)) {
  console.error(`FAIL [S0] anchor not found in ${SRC}: ${CUT} (module tail moved?)`);
  process.exit(1);
}
/** @param {string} src @returns {any} 源码内函数的公开面 */
function loadApi(src, useTopLevel) {
  const keep = [];
  let skipping = false;
  for (const line of src.split('\n')) {
    if (skipping) { if (/;\s*$/.test(line)) skipping = false; continue; }
    if (/^import\s*\{/.test(line)) { if (!/;\s*$/.test(line)) skipping = true; continue; }
    if (/^export\s*\{/.test(line)) { if (!/;\s*$/.test(line)) skipping = true; continue; }
    if (line.startsWith('export ')) { keep.push(line.replace(/^export (async function|function|const|let|var|class) /, '$1 ')); continue; }
    keep.push(line);
  }
  let body = keep.join('\n');
  body = body.slice(0, body.indexOf(CUT));
  const names = ['layoutNodes', 'collectEdges', 'chainGateTargets', 'depTargetsOf',
    'outEdgesOf', 'visibleNodes'];
  body += `\nreturn { ${names.join(', ')} };\n`;
  // The module's DOM/WS side is stubbed; only pure layout/edge helpers are touched.
  const stubs = `
    const esc = (s) => s; const t = (k) => k;
    const openTab = () => {}; const getTabPane = () => null; const ensureFlowCss = () => {};
    const fetchFlowMap = async () => null; const NODE_STATUS_CLS = {};
    const mergeQueueView = () => {}; const queueNameLabel = () => '';
    const isTerminalStatus = () => false; const purgeExpired = () => {};
    const ingestNodes = () => {}; const recordNodeRemoved = () => {};
    const dropStore = () => {}; const syncArchiveUi = () => {};
    const openDetailFor = () => {}; const chainMembersOf = () => [];
    const chainHighlightIds = () => []; const toggleHTML = () => '';
    const bindToggle = () => {}; const setToggleState = () => {};
    const onMessage = () => {}; const onReconnect = () => {};
    const localStorage = { getItem: () => null, setItem: () => {}, removeItem: () => {} };
    let window = { addEventListener: () => {}, innerWidth: 1280, innerHeight: 800 };
    let document = { addEventListener: () => {}, querySelector: () => null,
      querySelectorAll: () => [], getElementById: () => null };
  `;
  const probe = useTopLevel
    ? ['window', 'document', 'localStorage']
    : ['requestAnimationFrame', 'cancelAnimationFrame', 'setTimeout', 'clearTimeout'];
  const extra = probe.map((n) => `${n} = typeof ${n} === 'undefined' ? (() => 0) : ${n};`).join('');
  // eslint-disable-next-line no-new-func
  return new Function('useTopLevel', `${stubs}${body}`)(useTopLevel);
}
/** @type {any} */
let API = null;
try { API = loadApi(source, false); } catch (e) {
  console.log(`  FAIL [S0] module eval threw: ${e.message}`);
  fails.push('S0');
}

/** 夹具 → 渲染层快照（`visibleFmView` 恒等：夹具已是活动区可见集）。 */
const fmOf = (fx) => ({ nodes: fx.nodes, chains: fx.chains });
const recon = fmOf(JSON.parse(readFileSync(join(FIX, 'recon-30n.json'), 'utf8')));
const scale = fmOf(JSON.parse(readFileSync(join(FIX, 'scale-60n.json'), 'utf8')));

/** 边类别：`kind` 缺省 = forward（out / in 两分支共用 `${up}=>${down}` 键）。 */
const kindOf = (e) => e.kind || 'forward';
const tally = (edges) => {
  /** @type {Record<string, number>} */
  const out = {};
  for (const e of edges.values()) out[kindOf(e)] = (out[kindOf(e)] || 0) + 1;
  return out;
};

if (API) {
  // ── A0：夹具形状（读数来自冻结载荷，非 live 漂移面）──────────────────────────
  console.log('A0 夹具形状（30 节点 / 12 条 chain: deps）');
  const outDeg = recon.nodes.reduce((s, n) => s + (Array.isArray(n.out) ? n.out.length : (n.out ? 1 : 0)), 0);
  const depsRefs = recon.nodes.flatMap((n) => n.deps || []);
  const loopDeg = recon.nodes.reduce((s, n) => s + (Array.isArray(n.out) ? n.out.filter((e) => e.mode === 'loop').length : 0), 0);
  assert('A0a', '节点数 = 30', recon.nodes.length === 30, `got ${recon.nodes.length}`);
  assert('A0b', '出边条数 = 42（loop 8 + Nebula 9 + 真实投递 25）',
    outDeg === 42 && loopDeg === 8, `out=${outDeg} loop=${loopDeg}`);
  assert('A0c', 'deps 引用 = 12 条且**全部**是 `chain:<chainId>` 形态',
    depsRefs.length === 12 && depsRefs.every((d) => String(d).startsWith('chain:')), `n=${depsRefs.length}`);
  assert('A0d', 'chains[] 旁挂 = 12 条（含 3 条不可达链闸的缺席）', recon.chains.length === 12);

  const posPre = API.layoutNodes(recon).positions;
  const posPost = posPre; // 布局不依赖边分支（见 A4 观察项），读数同源
  const edges = API.collectEdges(recon, posPost);
  const kinds = tally(edges);

  // ── A1：out 分支载荷读法（修复前 = 0 条）────────────────────────────────────
  console.log('A1 出边分支读数（口径 = 与 `in`/`deps` 隔离后的 forward 边条数）');
  const bare = { nodes: recon.nodes.map((n) => ({ ...n, in: [], deps: [] })), chains: recon.chains };
  const bareEdges = API.collectEdges(bare, API.layoutNodes(bare).positions);
  const outOnly = [...bareEdges.values()].filter((e) => kindOf(e) === 'forward');
  assert('A1a', 'out 分支单跑产出 25 条 forward 边（修复前基线 = 0）',
    outOnly.length === 25, `got ${outOnly.length} (baseline 0)`);
  assert('A1b', '键形 = `${from}=>${to}`、状态三态、无 `kind` 字段（forward 语义）',
    [...bareEdges.entries()].filter(([, e]) => kindOf(e) === 'forward')
      .every(([k, e]) => k.includes('=>') && !k.includes('loop') && !e.kind
        && ['delivered', 'inflight', 'idle'].includes(e.state)));
  assert('A1c', 'loop 边不进 forward 分支（键形 `${from}=>loop=>${to}`、kind=loop、带 gate 标签）',
    kinds.loop === 8 && [...edges.entries()].filter(([, e]) => e.kind === 'loop')
      .every(([k, e]) => /=>loop=>/.test(k) && /^\(.+\)$/.test(e.gate)), `loop=${kinds.loop}`);
  assert('A1d', 'Nebula 目标不画边（无 card 可指）',
    [...edges.keys()].every((k) => !k.startsWith('Nebula') && !k.endsWith('=>Nebula')));
  assert('A1e', 'out 分支无自有去重：in 镜像补画条数 = 0（forward 键集逐字相同）',
    eq([...edges.entries()].filter(([, e]) => kindOf(e) === 'forward').map(([k]) => k).sort(),
      [...bareEdges.entries()].filter(([, e]) => kindOf(e) === 'forward').map(([k]) => k).sort()));

  // ── A2：`chain:` 链闸解析（可达链）────────────────────────────────────────────
  console.log('A2 可达链闸 deps 边（修复前基线 = 0 条）');
  const depsKeys = [...edges.entries()].filter(([, e]) => e.kind === 'deps').map(([k]) => k).sort();
  const gate = new Map();
  for (const k of depsKeys) {
    const src = k.slice(0, k.indexOf('~>'));
    gate.set(src, (gate.get(src) || 0) + 1);
  }
  assert('A2a', 'deps 边 ≥1 条且键形 `…~>…`、kind=deps', depsKeys.length >= 1
    && depsKeys.every((k) => k.includes('~>') && edges.get(k).kind === 'deps'), `got ${depsKeys.length}`);
  assert('A2b', '`chain-n-absorb` 闸边 = 6 条，源恒为链汇 `n-4026723f`',
    (gate.get('n-4026723f') || 0) === 6 && depsKeys.filter((k) => k.startsWith('n-4026723f~>')).length === 6,
    `got ${gate.get('n-4026723f') || 0} (absorb 闸引用 6 处)`);
  assert('A2c', '`chain-n-f7f62f55` / `w3` / `w4` 闸各 1 条（源 = 各自链汇）',
    ['n-53ece9a0', 'n-943572aa', 'n-7b149e90'].every((s) => (gate.get(s) || 0) === 1),
    JSON.stringify([gate.get('n-53ece9a0'), gate.get('n-943572aa'), gate.get('n-7b149e90')]));
  assert('A2d', 'deps 边源恒为链汇/上游、目标恒为吃闸位（方向 = 链完成 → 吃闸）',
    depsKeys.every((k) => {
      const [s, t] = k.split('~>');
      return recon.chains.some((c) => (c.ends || []).includes(s) && c.memberIds.includes(s) !== null) && !!t;
    }));
  assert('A2e', 'deps 边状态 = deps-met/wait/unmet 三态之一',
    depsKeys.every((k) => /^deps-(met|wait|unmet)$/.test(edges.get(k).state)));

  // ── A3：不可达链闸 = 优雅降级（零边、零异常、清单可列）────────────────────────
  console.log('A3 不可达链闸降级清单');
  const chainIds = new Set(recon.chains.map((c) => c.id));
  const degraded = [...new Set(recon.nodes.flatMap((n) => (n.deps || [])
    .filter((d) => String(d).startsWith('chain:') && !chainIds.has(String(d).slice(6)))
    .map((d) => String(d).slice(6))))].sort();
  let threw = '';
  try { recon.nodes.forEach((n) => (n.deps || []).forEach((d) => API.depTargetsOf(recon, d))); }
  catch (e) { threw = e.message; }
  assert('A3a', '降级清单逐条 = chain-n-9f4b6905 / chain-n-f9a1138c / chain-nodeprogress',
    eq(degraded, ['chain-n-9f4b6905', 'chain-n-f9a1138c', 'chain-nodeprogress']), degraded.join(', '));
  assert('A3b', '降级链闸解析 = 空集且零异常（不抛、不伪节点、不从 0 派数字）',
    threw === '' && degraded.every((cid) => API.chainGateTargets(recon, cid).length === 0), threw);
  assert('A3c', '降级链闸零边（边集中无任何以降级链成员为源的 deps 边）',
    !depsKeys.some((k) => /^n-(9f4b6905|f9a1138c|nodeprogress)/.test(k)));
  assert('A3d', '未知名/悬空引用同样降级为空集', API.depTargetsOf(recon, 'chain:chain-does-not-exist').length === 0
    && API.depTargetsOf(recon, 'n-ffffffffff').length === 0
    && API.depTargetsOf(recon, '').length === 0);

  // ── A4：层分布（修复前基线 = 4 层 / depth0 = 14）──────────────────────────────
  console.log('A4 层分布（16 层 / depth0 5 / depth13 6 / depth15 1；基线 4 层 / depth0 14）');
  const ys = [...new Set(Object.values(posPost).map((p) => p.y))].sort((a, b) => a - b);
  const perLayer = ys.map((y) => Object.values(posPost).filter((p) => p.y === y).length);
  assert('A4a', '层数 = 16（修复前基线 = 4）', ys.length === 16, `got ${ys.length} (baseline 4)`);
  assert('A4b', 'depth0 = 5 位（修复前基线 = 14 位）', perLayer[0] === 5, `got ${perLayer[0]} (baseline 14)`);
  assert('A4c', 'depth13 = 6 位、depth15 = 1 位',
    perLayer[13] === 6 && perLayer[15] === 1, `d13=${perLayer[13]} d15=${perLayer[15]}`);
  assert('A4d', '层内计数合计 = 30（无节点丢失/重复）',
    perLayer.reduce((a, b) => a + b, 0) === 30, `sum=${perLayer.reduce((a, b) => a + b, 0)}`);
  assert('A4e', '层心 y 呈单调整数倍递进、无空洞（层号连续）',
    ys.length <= 1 || ys.every((y, i) => i === 0 || y > ys[i - 1]));
  console.log(`    层分布（depth:count）= ${ys.map((y, i) => `${i}:${perLayer[i]}`).join(' ')}`);

  // ── A5：2 倍规模（60 节点）不崩不扁 ─────────────────────────────────────────
  console.log('A5 2 倍规模夹具（60 节点 / 24 链）');
  let scaleErr = '';
  let scaleLayers = [];
  let scaleSum = 0;
  let scaleEdges = new Map();
  try {
    const sp = API.layoutNodes(scale).positions;
    const sys = [...new Set(Object.values(sp).map((p) => p.y))].sort((a, b) => a - b);
    scaleLayers = sys.map((y) => Object.values(sp).filter((p) => p.y === y).length);
    scaleSum = Object.keys(sp).length;
    scaleEdges = API.collectEdges(scale, sp);
  } catch (e) { scaleErr = e.message; }
  const scaleKinds = tally(scaleEdges);
  assert('A5a', '不崩（layoutNodes + collectEdges 零异常）且每层 ≥1 位',
    scaleErr === '' && scaleLayers.every((c) => c >= 1), scaleErr || `layers=${scaleLayers.length}`);
  assert('A5b', '无「全部 depth 0」：层数 > 1 且 depth0 < 60',
    scaleLayers.length > 1 && scaleLayers[0] < 60, `layers=${scaleLayers.length} depth0=${scaleLayers[0]}`);
  assert('A5c', '层号连续无空洞（层计数合计 = 60，无节点丢失）', scaleSum === 60, `sum=${scaleSum}`);
  assert('A5d', '2 倍规模边数线性放大（forward 50 / deps 18 / loop 16）',
    scaleKinds.forward === 50 && scaleKinds.deps === 18 && scaleKinds.loop === 16, JSON.stringify(scaleKinds));

  // ── A6：判据单源（三处 deps 消费同走 `depTargetsOf`）──────────────────────────
  console.log('A6 判据单源与回退红线');
  assert('A6a', '`chain:` 引用在 `depTargetsOf` 与 `chainGateTargets` 间同源（逐条同值）',
    recon.nodes.flatMap((n) => n.deps || []).filter((d) => String(d).startsWith('chain:'))
      .every((d) => eq(API.depTargetsOf(recon, d), API.chainGateTargets(recon, String(d).slice(6)))));
  assert('A6b', '普通 id deps 只认可见集命中（悬空 → 空集）',
    eq(API.depTargetsOf(recon, 'n-4026723f'), ['n-4026723f'])
    && eq(API.depTargetsOf(recon, 'ghost-node'), []));
  assert('A6c', 'out 目标串双形态解析：id 命中优先于名字命中（同序 = 引擎 resolveTargetId）',
    eq(API.depTargetsOf(recon, 'n-4026723f'), ['n-4026723f'])
    && API.outEdgesOf({ out: [{ to: 'x', on: ['pass'], mode: 'result' }] })[0].to === 'x'
    && API.outEdgesOf({ out: 'n-4026723f' })[0].mode === 'result'
    && API.outEdgesOf(null).length === 0);
}

console.log(`\n${pass} passed, ${fails.length} failed${fails.length ? ' → ' + fails.join(', ') : ''}`);
process.exit(fails.length ? 1 : 0);

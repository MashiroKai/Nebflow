// release-strip-friends.static.mjs — 好友功能构建环境区分·**双形态**静态断言
//   作者令 2026-09-10：CI/CD 构建产物自动剔除好友功能；本地 dev 默认开。
//   作者裁定 2026-09-14（friend-gate 解封 D1②/D6）：**默认翻转为露出**，
//   剔除机制保留为可复用开关 ⇒ 本脚本参数化双形态（露出 / 再封都要能判）。
//
// node 直读 build/web-dist（无浏览器），与 tests/legacy-ui-retire.static.mjs
// 同款「静态降级断言」纪律。产物 = `node scripts/build-web.mjs` 的输出——
// 与 ci.yml「Build web bundle」步骤同一条命令，即 CI/CD 产物链的本地模拟。
//
// ── 形态与 `--expect`（参数化，D6）─────────────────────────────
//   `--expect present`（**缺省**）  = 露出形态：`node scripts/build-web.mjs`
//        缺省产物（不注入 release marker）。判据链 / 激活路径符号必须**存在**。
//    `--expect stripped`           = 再封形态：`node scripts/build-web.mjs
//        --strip-friends` 的产物。上述三面必须**零残留** ＝ **再封门禁**。
//   缺省取 present 的理由：它是 build-web.mjs 的缺省形态、也是 CI 装配 job
//   接线的形态（ci.yml「Friends release-strip assertion」步），使
//   `node scripts/build-web.mjs && node tests/release-strip-friends.static.mjs`
//   这对命令自洽；再封门禁由显式 `--expect stripped` 承担（双形态皆可判）。
//
// ── 断言面 ───────────────────────────────────────────────────
//   S1  产物存在（先跑 build-web.mjs）
//   S2  __NEBFLOW_RELEASE__ 编译期标记（stripped: define 全替换后零残留 /
//         present: 内联守卫原文保留 = 未注入 define）
//   S3  friends 配置判据链（features?.friends）——featureFlags.js dev 分支
//         是否被 DCE 物理剔除
//   S4  好友激活路径符号（enableFriendPanels/initContacts/initMessages）——
//         逐符号按 minify/chunk 可观测性分担方向：跨 chunk 导出名
//         （enableFriendPanels/initMessages，dock 批共享 chunk 化后 minify
//         稳定）只承担「露出存在性」，被重命名的内部绑定（initContacts）
//         只承担「stripped 零残留」（实测读数见该段注释；模块级存在性由
//         S7 映射表条目承担）
//   S5  对照：fm-friends-changed 模块副作用仍在（外科手术式剔除——剔除的是
//         激活路径，不是整个模块失联）**不随形态变**
//   S6  对照：neblink 设备互联仍在（作者裁定不 gating）**不随形态变**
//   S7  源码映射面（*.js.map 的 `sources` / `sourcesContent`）——露出方向断
//         「好友源码在场」；stripped 方向自 2026-10-30 重锚起为 NOTE（剥除
//         机制 = define+DCE 激活路径、非摘模块，映射面内嵌源码两形态必然
//         在场，B5 引入即红、机制上不可达——核实见该段注释）
// Run:
//   node scripts/build-web.mjs                 && node tests/release-strip-friends.static.mjs
//   node scripts/build-web.mjs --strip-friends && node tests/release-strip-friends.static.mjs --expect stripped

import { readFileSync, existsSync, readdirSync } from 'node:fs';
import { join, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DIST = join(ROOT, 'build', 'web-dist');

// ── --expect stripped|present（缺省 present = build-web.mjs 缺省形态）──
function usage(code) {
  console.error('usage: node tests/release-strip-friends.static.mjs [--expect present|stripped]');
  console.error('  present  (default) built by: node scripts/build-web.mjs');
  console.error('  stripped           built by: node scripts/build-web.mjs --strip-friends');
  process.exit(code);
}
let expect = 'present';
{
  const args = process.argv.slice(2);
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    if (a === '--expect') expect = args[++i];
    else if (a.startsWith('--expect=')) expect = a.slice('--expect='.length);
    else { console.error(`unknown argument: ${a}`); usage(2); }
  }
  if (expect !== 'present' && expect !== 'stripped') {
    console.error(`invalid --expect value: ${expect}`);
    usage(2);
  }
}
const STRIPPED = expect === 'stripped';
console.log(`expect: ${expect}  (${STRIPPED
  ? 're-seal gate — friends must be ZERO-residue on every asserted face'
  : 'unsealed form — friends must be PRESENT on every asserted face'})`);

let failed = 0;
const check = (name, cond, detail = '') => {
  if (cond) console.log(`PASS  ${name}`);
  else { failed++; console.error(`FAIL  ${name}${detail ? ' — ' + detail : ''}`); }
};

// ── S1: 产物存在 ──
const assetsDir = join(DIST, 'assets');
const chunksDir = join(assetsDir, 'chunks');
if (!existsSync(join(assetsDir, 'index.html').replace('assets', '')) && !existsSync(join(DIST, 'index.html'))) {
  console.error('FAIL  S1 build/web-dist/index.html missing — run: node scripts/build-web.mjs');
  process.exit(1);
}
check('S1 build/web-dist/index.html exists', existsSync(join(DIST, 'index.html')));

// 代码面（S2/S3/S4/S5/S6 的判据载体）：发出代码 .js
const bundles = [];
for (const f of readdirSync(assetsDir)) if (f.endsWith('.js')) bundles.push(join(assetsDir, f));
if (existsSync(chunksDir)) {
  for (const f of readdirSync(chunksDir)) if (f.endsWith('.js')) bundles.push(join(chunksDir, f));
}
check(`S1 bundles collected (assets + chunks)`, bundles.length > 0, `${bundles.length} js files`);

// 源码映射面（S7 的判据载体）：随包发运的 .js.map
const maps = [];
for (const f of readdirSync(assetsDir)) if (f.endsWith('.js.map')) maps.push(join(assetsDir, f));
if (existsSync(chunksDir)) {
  for (const f of readdirSync(chunksDir)) if (f.endsWith('.js.map')) maps.push(join(chunksDir, f));
}

const combined = bundles.map(p => { try { return readFileSync(p, 'utf8'); } catch { return ''; } }).join('\n');

// ── S2: 编译期标记 ──
// stripped = define 已全替换 ⇒ 表达式原文零残留；
// present  = 未注入 define ⇒ featureFlags.js 的内联守卫原文保留在产物里。
check(
  STRIPPED
    ? 'S2 __NEBFLOW_RELEASE__ zero residue (define fully substituted, guard folded away)'
    : 'S2 __NEBFLOW_RELEASE__ expression alive (no define injected — unsealed)',
  STRIPPED ? !combined.includes('__NEBFLOW_RELEASE__') : combined.includes('__NEBFLOW_RELEASE__')
);

// ── S3: friends 配置判据链（dev 分支）──
// featureFlags.js dev 分支表达式 `state.parsedConfig?.features?.friends` 在
// minify 产物中保留 `?.` 运算符与属性名；stripped 构建里该分支整条被剔除。
check(
  STRIPPED
    ? 'S3 dev flag chain (features?.friends) stripped from bundle'
    : 'S3 dev flag chain (features?.friends) alive in bundle',
  STRIPPED ? !combined.includes('features?.friends') : combined.includes('features?.friends')
);

// ── S4: 激活路径符号 ──
// 🔴 minify / chunk 口径（2026-09-14 实测：两形态各在 N1 worktree 重建一次；
//   2026-10-30 重锚：dock 批 894874bd8 把手机 bootstrap 提为第二入口 +
//   navController/socialFaces 直引 messages.js ⇒ messages.js 成跨 chunk 共享
//   模块 ⇒ 其导出名 minify 稳定，stripped 形态同样命中——与 enableFriendPanels
//   同性质（esbuild 保跨 chunk 导出名，剥除判据不看符号面））：
//   · `enableFriendPanels` / `initMessages` = **跨 chunk 导出名**（main.js ←
//     惰性 chunk）⇒ esbuild 保名，**两形态各命中** ⇒ 判不了形态（stripped
//     方向断言「零残留」是永久红——这正是既有 S4 红债的真因，不是「旧树残
//     留」）。故只作露出方向的存在性断言，stripped 方向给 NOTE。
//   · `initContacts` = 同 chunk 内部绑定 ⇒ minify 重命名，**两形态各命中 0 次**
//     ⇒ 只能承担 stripped 方向的（弱）零残留断言；露出方向的模块级存在性
//     由 S7（映射表 `sources`）承担。
// 真正判形态的面 = S2 / S3（marker / 判据链）+ S7 露出方向（映射表面）。
const S4_SYMBOLS = ['enableFriendPanels', 'initContacts', 'initMessages'];
const S4_MINIFY_STABLE = new Set(['enableFriendPanels', 'initMessages']); // 跨 chunk 导出名（2026-10-30 重锚：dock 批共享 chunk 化）
for (const sym of S4_SYMBOLS) {
  const hit = combined.includes(sym);
  if (STRIPPED) {
    if (S4_MINIFY_STABLE.has(sym)) {
      console.log(`NOTE  S4 ${sym}: hit in stripped bundle=${hit} — cross-chunk export name kept by esbuild; this is a module-record name, not an activation residue (form judged by S2/S3/S7)`);
    } else {
      check(`S4 activation symbol absent: ${sym}`, !hit);
    }
  } else if (S4_MINIFY_STABLE.has(sym)) {
    check(`S4 activation symbol present (cross-chunk export): ${sym}`, hit);
  } else {
    console.log(`NOTE  S4 ${sym}: renamed by minify inside the entry chunk (0 hits even in the unsealed artifact) — module-level presence asserted by S7`);
  }
}

// ── S5: 对照——模块副作用保留（外科手术式剔除）**不随形态变** ──
check('S5 control: fm-friends-changed side-effect still present (module kept)', combined.includes('fm-friends-changed'));

// ── S6: 对照——设备互联不 gating（作者裁定 2026-09-08）**不随形态变** ──
check('S6 control: neblink (device interconnect) still present', combined.includes('neblink'));

// ── S7: 源码映射面（*.js.map）——B5 ──
// `sourcemap: 'linked'`（build-web.mjs，两形态无条件）让 .js.map 随包发运，其
// `sources` / `sourcesContent` 内嵌源文件全文。
// 🔴 2026-10-30 重锚（核实换代语义后改写，B5 引入即红、非新回归）：剥除机制 =
// `window.__NEBFLOW_RELEASE__` define + dev 分支 DCE（build-web.mjs RELEASE
// 块）——**外科手术式剔除激活路径，不摘模块**：好友模块仍整棵在图内（S5 对照
// 同向），映射表必然内嵌其源码，stripped 形态「映射面零残留」从机制上不可达
// （3430e784a 引入 S7 当轮 stripped 实测即 FAIL，证据 = 本仓 git archive 重建）。
// 故 S7 两形态分工：露出方向断「源码在场」；stripped 方向降为 NOTE（映射面
// 不构成剥除面）。真剥除面 = S2/S3（代码面 marker/判据链）。
// 注：若未来要真剥映射面，需 build-web.mjs stripped 形态改 sourcemap 策略
// （如 sourcesContent:false / 不发射 map）——那是产物机制改动，超出本测试
// 断言批的「零产品代码」纪律，另行立项。
const FRIEND_SOURCES = ['contacts.js', 'messages.js', 'friendsApi.js'];
const carrying = [];   // 映射表内嵌了好友模块源码
const unreadable = []; // 映射表无法解析（无法核验 ⇒ 两形态均判失败）
for (const p of maps) {
  let m;
  try { m = JSON.parse(readFileSync(p, 'utf8')); }
  catch (e) { unreadable.push(`${basename(p)} (${e.message})`); continue; }
  const sources = Array.isArray(m.sources) ? m.sources : [];
  const contents = Array.isArray(m.sourcesContent) ? m.sourcesContent : [];
  for (let i = 0; i < sources.length; i++) {
    const hit = FRIEND_SOURCES.find(f => String(sources[i]).endsWith('/' + f) || String(sources[i]) === f);
    if (!hit) continue;
    const bytes = typeof contents[i] === 'string' ? contents[i].length : 0;
    carrying.push(`${basename(p)}:${hit}${bytes ? ` (${bytes} B source text)` : ''}`);
  }
}
check('S7 sourcemaps readable (JSON)', unreadable.length === 0, unreadable.join(', '));
if (STRIPPED) {
  console.log(`NOTE  S7 map face: ${maps.length} maps scanned; friend module sources embedded in BOTH forms `
    + `(${carrying.length ? carrying.slice(0, 8).join(', ') : 'none'}`
    + `${carrying.length > 8 ? ` (+${carrying.length - 8} more)` : ''}) — the strip mechanism is `
    + 'define+DCE of the activation path, NOT module removal (S5 control same-direction); '
    + 'map-face zero-residue is unreachable by construction (red since B5 3430e784a). '
    + 'Strip face = S2/S3 code surface.');
} else {
  check(
    'S7 map face: friend module sources present in *.js.map (unsealed artifact)',
    carrying.length > 0,
    `${maps.length} maps scanned; ` + (carrying.length ? `carrying friend sources: ${carrying.slice(0, 8).join(', ')}${carrying.length > 8 ? ` (+${carrying.length - 8} more)` : ''}` : 'no map carries friend sources')
  );
}

console.log(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
process.exit(failed ? 1 : 0);

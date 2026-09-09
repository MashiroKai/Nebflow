// release-strip-friends.static.mjs — 好友功能构建环境区分·发布产物剔除断言
// （作者令 2026-09-10：CI/CD 构建产物自动剔除好友功能；本地 dev 默认开）。
//
// node 直读 build/web-dist（无浏览器），与 tests/legacy-ui-retire.static.mjs
// 同款「静态降级断言」纪律。产物 = `node scripts/build-web.mjs` 的输出——
// 与 ci.yml「Build web bundle」步骤同一条命令，即 CI/CD 产物链的本地模拟。
//
// 断言面：
//   S1  产物存在（先跑 build-web.mjs）
//   S2  __NEBFLOW_RELEASE__ 编译期标记零残留（define 全替换）
//   S3  friends 配置判据链（features?.friends）零残留——featureFlags.js dev
//       分支被 DCE 物理剔除，friendsEnabled() 折叠为恒 false
//   S4  好友激活路径符号（enableFriendPanels/initContacts/initMessages）零残留
//   S5  对照：fm-friends-changed 模块副作用仍在（外科手术式剔除——剔除的是
//       激活路径，不是整个模块失联）
//   S6  对照：neblink 设备互联仍在（作者裁定不 gating）
// Run: node scripts/build-web.mjs && node tests/release-strip-friends.static.mjs

import { readFileSync, existsSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DIST = join(ROOT, 'build', 'web-dist');

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

const bundles = [];
for (const f of readdirSync(assetsDir)) if (f.endsWith('.js')) bundles.push(join(assetsDir, f));
if (existsSync(chunksDir)) {
  for (const f of readdirSync(chunksDir)) if (f.endsWith('.js')) bundles.push(join(chunksDir, f));
}
check(`S1 bundles collected (assets + chunks)`, bundles.length > 0, `${bundles.length} js files`);

const combined = bundles.map(p => { try { return readFileSync(p, 'utf8'); } catch { return ''; } }).join('\n');

// ── S2: 编译期标记零残留 ──
check('S2 __NEBFLOW_RELEASE__ zero residue (define fully substituted)', !combined.includes('__NEBFLOW_RELEASE__'));

// ── S3: friends 配置判据链零残留（dev 分支 DCE 剔除） ──
// featureFlags.js dev 分支表达式 `state.parsedConfig?.features?.friends` 在
// minify 产物中保留 `?.` 运算符与属性名；release 构建里该分支整条被剔除。
check('S3 dev flag chain (features?.friends) stripped from bundle', !combined.includes('features?.friends'));

// ── S4: 激活路径符号零残留 ──
for (const sym of ['enableFriendPanels', 'initContacts', 'initMessages']) {
  check(`S4 activation symbol stripped: ${sym}`, !combined.includes(sym));
}

// ── S5: 对照——模块副作用保留（外科手术式剔除） ──
check('S5 control: fm-friends-changed side-effect still present (module kept)', combined.includes('fm-friends-changed'));

// ── S6: 对照——设备互联不 gating（作者裁定 2026-09-08） ──
check('S6 control: neblink (device interconnect) still present', combined.includes('neblink'));

console.log(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
process.exit(failed ? 1 : 0);

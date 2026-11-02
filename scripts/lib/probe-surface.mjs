// probe-surface.mjs — 键盘闸的「验证对象」解析层（2026-11-01 装置批 / G6）。
//
// 要解决的问题（上一批实测，见 dist-kb.log）：
//   `keyboard-check` 按**源路径**取模块句柄（`import('/js/keymap.js')`）。源树形态下
//   这没问题（index.html 直接加载 js/main.js，URL 就是源路径 ⇒ 与产品同一实例）；
//   但 `NEBFLOW_WEB_ROOT=build/web-dist` 下没有 `/js/` 路由（模块合进 entry chunk，
//   导出名还被压成单字母别名），实测报 `Failed to fetch dynamically imported module`。
//   于是「视觉闸已经做到验的=跑的，键盘闸没做到」。
//
// 本层提供**一套**口径（不新造第二套 webRoot / 第二套仓根）：
//   · 根：直接沿用 visual-harness 的 `webRoot()` / `setWebRoot()` / `ROOT`（G3 已建好）。
//   · 模块句柄：页面内 `window.__nfProbeModule(path)` —— **先读产品侧登记表
//     `window.__nfProbeSurface`**（见 js/probeSurface.js），再回退到 `import(path)`。
//     登记表由产品自己维护模块图 ⇒ 两种形态下都拿到**产品正在用的那一份实例**。
//   · 静态扫描（接线点计数 / CSS 配对）：源树形态读磁盘源文件；产物形态读
//     **产物自带的 sourcemap `sourcesContent`**（= 打进 bundle 的那份字节）与
//     **产物 CSS bundle**（build-web.mjs 用 `/* ==== css/x.css ==== */` 分段保留
//     文件身份）。这样产物形态下扫的是「跑的那份」，而不是「源树那份」。
//
// 未闭合的残余（必须显式报出，不得静默）：登记表是**产品侧**新增物 —— 重新打包
// 之前的旧产物里没有它，那些产物上的模块句柄取不到，相关断言只能 SKIP 并给出
// 原因。这不是脚本的缺陷，是产物的版本事实（脚本会把陈旧度一并打印出来）。

import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { ROOT, webRoot } from './visual-harness.mjs';

/** 产品侧登记表的全局键（与 js/probeSurface.js 同名）。 */
export const PROBE_SURFACE_GLOBAL = '__nfProbeSurface';
/** 本层认识的登记表版本。 */
export const PROBE_SURFACE_VERSION = 1;

/**
 * 当前被验证的形态：源树（有 js/main.js）还是打包产物（有 assets/ 且无 js/）。
 * @returns {'source'|'dist'|'unknown'}
 */
export function webForm() {
  const root = webRoot();
  if (existsSync(join(root, 'js', 'main.js'))) return 'source';
  if (existsSync(join(root, 'assets'))) return 'dist';
  return 'unknown';
}

/** 一句话描述当前验证对象（进脚本头，与 visual-baseline 的 `web :` 行同口径）。 */
export function describeWebRoot() {
  const root = webRoot();
  const form = webForm();
  let extra = '';
  if (form === 'dist') {
    try {
      const assets = readdirSync(join(root, 'assets'));
      const appJs = assets.filter((f) => /^app-[^/]+\.js$/.test(f));
      const appCss = assets.filter((f) => /^app-[^/]+\.css$/.test(f));
      extra = `, app=${appJs.join(',') || '<none>'}, css=${appCss.join(',') || '<none>'}`;
    } catch { extra = ', assets unreadable'; }
  }
  return `web    : ${relative(ROOT, root) || root}  (form=${form}${extra})`;
}

// ── 静态扫描面：js 源码 / css 文本 ───────────────────────────────────────────

/** 任意源路径 → `js/xxx.js` 这种 web 根相对形式（非 web 源返回 null）。 */
function normRel(/** @type {string} */ s) {
  const p = s.replace(/\\/g, '/');
  const i = p.indexOf('resources/web/');
  return i < 0 ? null : p.slice(i + 'resources/web/'.length);
}

/** 递归收集目录下某扩展名的文件。 */
function walk(/** @type {string} */ dir, /** @type {string} */ ext) {
  /** @type {string[]} */
  const out = [];
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) out.push(...walk(p, ext));
    else if (e.name.endsWith(ext)) out.push(p);
  }
  return out;
}

/**
 * 产物自带的 sourcemap 索引：web 根相对路径（`js/xxx.js`）→ 打进 bundle 的那份文本。
 * @returns {{ via: 'sourcemap'|'none', files: Map<string, string>, maps: number, dupes: string[] }}
 */
function sourcemapIndex() {
  const assets = join(webRoot(), 'assets');
  /** @type {Map<string, string>} */
  const files = new Map();
  /** @type {string[]} */
  const dupes = [];
  if (!existsSync(assets)) return { via: 'none', files, maps: 0, dupes };
  let used = 0;
  for (const mp of walk(assets, '.js.map')) {
    /** @type {any} */
    let m;
    try { m = JSON.parse(readFileSync(mp, 'utf8')); } catch { continue; }
    if (!Array.isArray(m.sources) || !Array.isArray(m.sourcesContent)) continue;
    used++;
    for (let i = 0; i < m.sources.length; i++) {
      const rel = normRel(String(m.sources[i]));
      const text = m.sourcesContent[i];
      if (!rel || typeof text !== 'string') continue;
      if (files.has(rel)) {
        // 同一源出现在多个 chunk 图里是正常的（共享 chunk）；内容必须一致 ——
        // 不一致说明产物内部自相矛盾（半途构建的残渣），要报出来而不是取第一份了事。
        if (files.get(rel) !== text) dupes.push(rel);
        continue;
      }
      files.set(rel, text);
    }
  }
  return { via: used > 0 ? 'sourcemap' : 'none', files, maps: used, dupes };
}

/**
 * js 源码面：源树形态读磁盘（`<root>/js/**`），产物形态读 sourcemap sourcesContent。
 * @returns {{ via: 'tree'|'sourcemap', entries: {rel: string, text: string}[], notes: string[] }}
 */
export function jsSources() {
  const root = webRoot();
  if (webForm() === 'source') {
    const entries = walk(join(root, 'js'), '.js').map((p) => ({
      rel: relative(root, p).replace(/\\/g, '/'),
      text: readFileSync(p, 'utf8'),
    }));
    return { via: 'tree', entries, notes: [] };
  }
  const idx = sourcemapIndex();
  /** @type {string[]} */
  const notes = [];
  if (idx.dupes.length) notes.push(`sourcemap 内部不一致（同名源在不同 map 内容不同）：${idx.dupes.slice(0, 5).join(', ')}`);
  if (idx.via === 'none') notes.push('产物里没有可用的 .js.map / sourcesContent ⇒ 静态扫描无对象');
  const entries = [...idx.files.entries()]
    .filter(([rel]) => rel.startsWith('js/'))
    .map(([rel, text]) => ({ rel, text }));
  return { via: 'sourcemap', entries, notes };
}

/**
 * css 文本面：源树形态读 `<root>/css/*.css`；产物形态读 `assets/app-*.css` 并按
 * build-web.mjs 的分段注释还原成「每文件一段」（文件身份因此保留）。
 * @returns {{ via: 'tree'|'bundle', entries: {rel: string, text: string}[], notes: string[] }}
 */
export function cssSources() {
  const root = webRoot();
  if (webForm() === 'source') {
    const dir = join(root, 'css');
    const entries = existsSync(dir)
      ? readdirSync(dir).filter((f) => f.endsWith('.css')).map((f) => ({ rel: `css/${f}`, text: readFileSync(join(dir, f), 'utf8') }))
      : [];
    return { via: 'tree', entries, notes: [] };
  }
  const assets = join(root, 'assets');
  /** @type {string[]} */
  const notes = [];
  /** @type {{rel: string, text: string}[]} */
  const entries = [];
  if (!existsSync(assets)) return { via: 'bundle', entries, notes: ['产物缺 assets/'] };
  const bundles = readdirSync(assets).filter((f) => /^app-[^/]+\.css$/.test(f));
  if (!bundles.length) return { via: 'bundle', entries, notes: ['产物缺 app-*.css'] };
  const text = readFileSync(join(assets, bundles[0]), 'utf8');
  // 分段标记：`/* ==== css/x.css ==== */`（build-web.mjs 第 5 步逐文件前缀）
  const marks = [...text.matchAll(/\/\*\s*====\s*(css\/[^\s*]+\.css)\s*====\s*\*\//g)]
    .map((m) => ({ rel: m[1], at: Number(m.index), bodyStart: Number(m.index) + m[0].length }));
  for (let i = 0; i < marks.length; i++) {
    const end = i + 1 < marks.length ? marks[i + 1].at : text.length;
    entries.push({ rel: marks[i].rel, text: text.slice(marks[i].bodyStart, end) });
  }
  if (!entries.length) {
    // 分段标记在产物里**不存在**：build-web.mjs 拼完 CSS 后过一遍 esbuild 的
    // `minifyWhitespace`，注释被整条删掉（实测 app-541b93e0.css 里 0 条 `====`）。
    // ⇒ 产物形态只能把整包当一个单元扫，文件身份丢失。这不是脚本缺陷，是产物的
    // 既有事实；由此产生的口径差异（同一 `:focus-visible` 出现在**别的**文件里也
    // 算配对）由调用方在断言 detail 里写明，并在汇总里可见。
    entries.push({ rel: `css/${bundles[0]}`, text });
    notes.push('产物 CSS 无分段标记（注释被 minifyWhitespace 删除）⇒ 整包当一个单元扫，文件身份不可用');
  }
  notes.push(`bundle=${bundles[0]}`);
  return { via: 'bundle', entries, notes };
}

/**
 * 产物陈旧度诊断：sourcemap 里「打进 bundle 的字节」与仓内源树当前文件是否一致。
 * 不一致 = 这份产物不是由当前源码构建的 ⇒ 产物形态的读数只能归因于**产物本身**。
 * @param {{entries: {rel: string, text: string}[]}} js jsSources() 的返回值
 * @returns {{same: number, diff: string[], missing: number}}
 */
export function shippedVsWorktree(js) {
  /** @type {string[]} */
  const diff = [];
  let same = 0;
  let missing = 0;
  if (webForm() === 'source') return { same: 0, diff, missing: 0 };
  for (const e of js.entries) {
    const srcPath = join(ROOT, 'src', 'main', 'resources', 'web', e.rel);
    if (!existsSync(srcPath)) { missing++; continue; }
    if (readFileSync(srcPath, 'utf8') === e.text) same++;
    else diff.push(e.rel);
  }
  return { same, diff, missing };
}

// ── 页面侧：模块句柄解析器 ───────────────────────────────────────────────────

/**
 * 给页面装 `window.__nfProbeModule(path)`：先读产品侧登记表，再回退 `import(path)`。
 * 必须在 `page.goto` 之前调用（addInitScript 语义）。
 * @param {import('playwright-core').Page} page
 */
export async function installModuleResolver(page) {
  await page.addInitScript((cfg) => {
    /** @type {any} */
    const w = window;
    w.__nfProbeModule = async (/** @type {string} */ path) => {
      const s = w[cfg.key];
      if (s && s.version === cfg.version && s.modules && s.modules[path]) {
        return { ok: true, via: 'surface', ns: s.modules[path] };
      }
      try {
        return { ok: true, via: 'import', ns: await import(path) };
      } catch (e) {
        const hasSurface = !!s;
        return {
          ok: false,
          via: 'none',
          reason: hasSurface
            ? `登记表在但缺 ${path}（probeSurface.js 未登记该模块）`
            : `无登记表且 ${path} 取不到（${String(/** @type {any} */ (e)?.message || e)}）`,
        };
      }
    };
  }, { key: PROBE_SURFACE_GLOBAL, version: PROBE_SURFACE_VERSION });
}

/** 一句话说明「产物形态下模块句柄为什么可能取不到」，进 SKIP 明细用。 */
export function surfaceHint() {
  return '模块句柄取不到：该产物无 window.__nfProbeSurface，且 dist 形态下 /js/* 路由不存在'
    + ' —— 需要重新打包一次才会带登记表；本断言**未执行**，不得读作通过';
}

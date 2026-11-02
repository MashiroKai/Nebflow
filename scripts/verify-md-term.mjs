#!/usr/bin/env node
// verify-md-term.mjs — 正文裸词识别（B5-1）的可自动化断言。
//
// 断言组（逐条对应 `js/termLexicon.js` 的匹配规则 + `viewers/markdown.js`
// `renderAutoTerms` 的排除面）：
//   ① 已注册词在正文首次出现 → 生成 `.nf-term`，文本保真（原文大小写）
//   ② 代码围栏 / 行内代码内 **0 命中**（把代码里的符号标成链接是最坏的坏法）
//   ③ KaTeX 公式内 **0 命中**（含其 TeX 源 <annotation>）
//   ④ 标题内 **0 命中**（结构面；词条首现留在正文段落）
//   ⑤ 未注册词 **0 命中**
//   ⑥ 最长优先（GCR 不被 GC 截断）
//   ⑦ 词边界（GCRs 不命中，括号里的 GCR 算正文）
//   ⑧ 逐文首现（同一键一篇文档只链一次）
//   ⑨ 别名键（DCL（DOMContentLoaded）的派生键命中）
//   ⑩ 消解数据到位（data-wiki-resolved 带 absPath + data-wiki-anchor 带块锚）
//   ⑪ hover 出泡（复用 termTip 面，不是另造一份）
//   ⑫ 显式 [[词条]] 不被二次链
//   ⑬ 无注册表 ⇒ 零链接（输出回到改造前的面）
//   ⑭ 万级词条库 bench：单段 4000 字匹配 p95 ≤ 10ms
//
// 装置：复用 scripts/lib/visual-harness.mjs（零端口零进程，结构上碰不到宿主实例）。
// 用法：NEBFLOW_PW_PATH=<playwright|playwright-core> node scripts/verify-md-term.mjs
// 退出码：全绿 0；任一断言失败 1；装置错误 2。

import { loadPlaywright, launchBrowser, createSession, settle } from './lib/visual-harness.mjs';

const results = [];
const ok = (name, cond, extra = '') => { results.push({ name, pass: !!cond, extra }); };

const MD_ID = 'file:/tmp/verify-term.md';
const MD_ABS = '/tmp/verify-term.md';
const VAULT = 'D:/tmp/verify-vault';

const TERMS = [
  {
    name: 'GCR', aliases: [], rel: 'glossary.md', anchor: '#^term-gcr', line: 4, endLine: 6,
    source: 'glossary', definition: '图形捕获区域：词条夹具里的主词条。',
  },
  {
    name: 'GC', aliases: [], rel: 'glossary.md', anchor: '#^term-gc', line: 8, endLine: 9,
    source: 'glossary', definition: '垃圾回收：与 GCR 共用前缀，用来验证最长优先。',
  },
  {
    name: 'DCL（DOMContentLoaded）', aliases: ['DCL', 'DOMContentLoaded'],
    rel: 'glossary.md', anchor: '#^term-dcl', line: 11, endLine: 12,
    source: 'glossary', definition: 'DOM 解析完成事件：用来验证括号别名键。',
  },
];

function graphFixture() {
  return {
    generatedAt: 1757000000000,
    projects: [{ name: 'verify', device: 'self', workspace: 'D:/tmp/verify-vault', vault: VAULT }],
    childVaults: [],
    nodes: [
      {
        id: 'verify:docs/glossary.md', project: 'verify', device: 'self', rel: 'glossary.md',
        title: 'glossary', stub: false, kind: 'doc', terms: TERMS,
        headings: [], blocks: [], paragraphs: [], external: false,
      },
    ],
    edges: [],
    stats: {},
  };
}

const MD = [
  '# 标题里的 GCR 不链',
  '',
  '正文首段谈 GCR 采集，第二处 GCR 也不该重复上链（逐文首现）。',
  '',
  'GCRs 这个复数词不命中（词边界）。',
  '',
  '行内代码 `GCR` 不链。',
  '',
  '公式 $GCR + 1$ 里的符号不链。',
  '',
  '未注册词 ZZZ 不链。',
  '',
  '别名命中：DOMContentLoaded 是浏览器事件。',
  '',
  '```js',
  'const GCR = 1; // 代码里的 GCR 不链',
  '```',
  '',
  '显式引用 [[GCR]] 保持 wikilink 单一面。',
].join('\n');

const paneSel = (id) => `#canvas-panel .canvas-tab-pane[data-tab-id="${id}"]`;

async function openDoc(page, graph) {
  await page.evaluate((d) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: d }));
  }, {
    id: MD_ID, itemType: 'markdown', title: 'verify-term.md', pinned: true,
    content: MD, absPath: MD_ABS, size: MD.length, mtimeMs: 1757000000000,
  });
  await page.waitForSelector(`${paneSel(MD_ID)} .canvas-md-scroll`, { state: 'visible', timeout: 20000 });
  // renderWikilinks / renderAutoTerms 是 post-render 异步面：等链接出现或超时（无注册表就是不出现）。
  await page.waitForFunction((sel) => {
    const host = document.querySelector(sel);
    return !!host && (host.querySelector('a.nf-term') || host.querySelector('a.nf-wikilink'));
  }, paneSel(MD_ID), { timeout: 12000 }).catch(() => {});
  await settle(page, 300);
  void graph;
}

async function main() {
  const pwRes = loadPlaywright();
  const pw = pwRes.mod.playwright || pwRes.mod;
  const { browser, label } = await launchBrowser(pw);

  // ── A. 有注册表：正面 + 排除面 ──────────────────────────────────────
  const s = await createSession(browser, { colorScheme: 'dark', apiStubs: { '/api/wiki/graph': { body: graphFixture() } } });
  try {
    await s.boot();
    const { page } = s;
    await openDoc(page, graphFixture());

    const v = await page.evaluate((sel) => {
      const host = document.querySelector(sel);
      if (!host) return null;
      const links = [...host.querySelectorAll('a.nf-term')].map((a) => ({
        text: a.textContent,
        term: a.dataset.term || '',
        resolved: a.dataset.wikiResolved || '',
        anchor: a.dataset.wikiAnchor || '',
        inCode: !!a.closest('pre, code'),
        inKatex: !!a.closest('.katex'),
        inHeading: !!a.closest('h1, h2, h3, h4, h5, h6'),
        inAnchor: !!a.closest('a.nf-wikilink'),
      }));
      return {
        links,
        wikilinks: [...host.querySelectorAll('a.nf-wikilink')].length,
        katexCount: host.querySelectorAll('.katex').length,
      };
    }, paneSel(MD_ID));

    const gcr = v ? v.links.filter((l) => l.text === 'GCR') : [];
    ok('1a 正文首次出现生成 .nf-term', !!v && v.links.length >= 1, v ? `links=${v.links.length}` : 'no host');
    ok('1b 链接文本保真（原文大小写）', gcr.length === 1, `GCR links=${gcr.length}`);
    ok('2a 代码围栏内 0 命中', !!v && v.links.every((l) => !l.inCode), v ? `inCode=${v.links.filter((l) => l.inCode).length}` : '');
    ok('3a KaTeX 内 0 命中', !!v && v.links.every((l) => !l.inKatex),
      v ? `inKatex=${v.links.filter((l) => l.inKatex).length} katex=${v.katexCount}` : '');
    ok('3b 公式确实渲染成 KaTeX（不是回落原文）', !!v && v.katexCount > 0, v ? `katex=${v.katexCount}` : '');
    ok('4a 标题内 0 命中', !!v && v.links.every((l) => !l.inHeading), v ? `inHeading=${v.links.filter((l) => l.inHeading).length}` : '');
    ok('5a 未注册词 0 命中', !!v && !v.links.some((l) => /ZZZ/.test(l.text)), '');
    ok('6a 最长优先：GCR 命中且 GC 不抢', !!v && gcr.length === 1 && !v.links.some((l) => l.text === 'GC'),
      v ? `links=${v.links.map((l) => l.text).join('|')}` : '');
    ok('7a 词边界：GCRs 不命中', !!v && !v.links.some((l) => l.text === 'GCRs'), '');
    ok('8a 逐文首现：两处 GCR 只链 1 次', gcr.length === 1, `count=${gcr.length}`);
    ok('9a 别名键命中（DOMContentLoaded）', !!v && v.links.some((l) => l.text === 'DOMContentLoaded'),
      v ? `links=${v.links.map((l) => l.text).join('|')}` : '');
    ok('10a 消解数据带 absPath（深链可跳）', gcr.length === 1 && /glossary\.md/.test(gcr[0].resolved),
      gcr.length === 1 ? gcr[0].resolved.slice(0, 90) : 'no GCR link');
    ok('10b 消解数据带块锚 ^term-gcr', gcr.length === 1 && gcr[0].anchor === '^term-gcr', gcr.length === 1 ? gcr[0].anchor : '');
    ok('12a 显式 [[GCR]] 仍是 wikilink 且未被二次链',
      !!v && v.links.every((l) => !l.inAnchor) && v.wikilinks >= 1, v ? `wikilinks=${v.wikilinks}` : '');

    if (gcr.length) {
      await page.hover(`${paneSel(MD_ID)} a.nf-term >> nth=0`);
      await page.waitForTimeout(500);
      const tip = await page.evaluate(() => {
        const el = document.querySelector('.nf-term-tip');
        if (!el || el.style.display === 'none') return null;
        return { name: el.querySelector('.tt')?.textContent || '', def: el.querySelector('.ts')?.textContent || '' };
      });
      ok('11a hover 出泡且带词条定义', !!tip && !!tip.def, tip ? `${tip.name}: ${tip.def.slice(0, 20)}` : 'no tip');
    } else {
      ok('11a hover 出泡且带词条定义', false, 'no GCR link to hover');
    }

    // 建表（前缀树）一次/文档；匹配步一次/文本节点。两段分开测，别用一个
    // 合成数字掩盖任何一段的退化。
    const bench = await page.evaluate(async () => {
      const mod = await import('/js/termLexicon.js');
      const terms = [];
      for (let i = 0; i < 10000; i++) terms.push({ name: 'TERM' + i + 'X', aliases: [] });
      const text = ('这是一段用来压测的中文正文，里面混着 TERM7X 与 TERM999X 这样的裸词，' +
        '以及 ordinary english words that must not match anything at all. ').repeat(40);
      const build = [];
      const find = [];
      let hits = 0;
      for (let k = 0; k < 20; k++) {
        const t0 = performance.now();
        const mm = mod.buildTermMatcher(terms);
        build.push(performance.now() - t0);
        const t1 = performance.now();
        hits = mm.find(text).length;
        find.push(performance.now() - t1);
      }
      const p95 = (arr) => { const a = [...arr].sort((x, y) => x - y); return a[Math.max(0, Math.ceil(a.length * 0.95) - 1)]; };
      return { buildP95: p95(build), findP95: p95(find), len: text.length, hits };
    });
    ok('14a 万级词条匹配 p95 ≤ 10ms', bench.findP95 <= 10,
      `find p95=${bench.findP95.toFixed(2)}ms len=${bench.len} hits=${bench.hits}`);
    ok('14b 万级词条建表 p95 ≤ 25ms（一次/文档）', bench.buildP95 <= 25, `build p95=${bench.buildP95.toFixed(2)}ms`);
  } finally {
    await s.close();
  }

  // ── B. 无注册表：零链接（输出回到改造前的面）──────────────────────────
  const s2 = await createSession(browser, {
    colorScheme: 'dark',
    apiStubs: { '/api/wiki/graph': { body: { nodes: [], projects: [], edges: [], generatedAt: 0 } } },
  });
  try {
    await s2.boot();
    await openDoc(s2.page, { nodes: [], projects: [], edges: [] });
    const n = await s2.page.evaluate((sel) => (document.querySelector(sel)?.querySelectorAll('a.nf-term').length ?? -1), paneSel(MD_ID));
    ok('13a 无词条注册表 ⇒ 0 个 a.nf-term', n === 0, `count=${n}`);
  } finally {
    await s2.close();
    await browser.close();
  }

  const failed = results.filter((r) => !r.pass);
  for (const r of results) console.log(`  ${r.pass ? 'PASS' : 'FAIL'}  ${r.name}${r.extra ? `  (${r.extra})` : ''}`);
  console.log(`\nverify-md-term: ${results.length - failed.length}/${results.length} passed (browser=${label})`);
  process.exit(failed.length ? 1 : 0);
}

main().catch((e) => {
  console.error('verify-md-term: harness error —', e && e.message ? e.message : e);
  if (e && e.stack) console.error(e.stack.split('\n').slice(1, 4).join('\n'));
  process.exit(2);
});

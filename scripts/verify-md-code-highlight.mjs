#!/usr/bin/env node
// verify-md-code-highlight.mjs — 代码块语法高亮的可自动化断言（文档渲染批 2026-11-01）。
//
// 断言组（逐条对应 utils.js _renderMarkdownInternal 的 ```lang 高亮补丁）：
//   ① 带已知语言标签的围栏 → <code> 带 hljs 标记 + 出现 hljs-* token span
//   ② 代码文本保真：<pre><code> 的 textContent 与源围栏正文逐字节相同
//      （防实体解码把 `&`/`<`/`$` 弄坏——marked 已转义、hljs 再转义一轮，双向必须可逆）
//   ③ 复制按钮链路不受影响（.code-block-wrap > .code-copy-btn 仍在，且包住同一个 pre）
//   ④ 未知语言标签 → 不标 hljs、文本原样（hljs.getLanguage 缺席即不进高亮腿）
//   ⑤ 无语言标签围栏 → 不高亮（刻意口径：不做 highlightAuto，成本与确定性都不可控）
//   ⑥ ```mermaid 围栏仍变 .nf-mermaid 占位，不被高亮腿吃掉
//
// 装置：复用 scripts/lib/visual-harness.mjs（零端口零进程：page.route 从磁盘服务
// web/，MockWS 走真实 ws.js 分发路径）—— 结构上不可能碰到宿主实例。
//
// 用法：NEBFLOW_PW_PATH=<playwright|playwright-core> node scripts/verify-md-code-highlight.mjs
// 退出码：全绿 0；任一断言失败 1；装置错误 2。

import { loadPlaywright, launchBrowser, createSession, settle } from './lib/visual-harness.mjs';

const results = [];
const ok = (name, cond, extra = '') => { results.push({ name, pass: !!cond, extra }); };

const MD_ID = 'file:/tmp/verify-code-highlight.md';

const JS_SRC = 'const a = 1 < 2 && "$x";\nfunction greet(name) { return `hi ${name}`; }';
const UNKNOWN_SRC = 'plain <b>not html</b> & stuff';
const BARE_SRC = 'no language fence';
const MERMAID_SRC = 'graph TD; A-->B;';

const MD = [
  '# 代码块高亮夹具',
  '',
  '这一段用来验证文档面的代码块渲染。',
  '',
  '```js',
  JS_SRC,
  '```',
  '',
  '```nosuchlang',
  UNKNOWN_SRC,
  '```',
  '',
  '```',
  BARE_SRC,
  '```',
  '',
  '```mermaid',
  MERMAID_SRC,
  '```',
].join('\n');

const paneSel = (id) => `#canvas-panel .canvas-tab-pane[data-tab-id="${id}"]`;

/** marked 的围栏正文自带一个尾换行（渲染器行为，不是本批改动面）。比对时只削尾
 *  换行，块内每一个字节（`&`、`<`、`$`、反引号）都必须逐字符相同。 */
const noTailNl = (t) => (t == null ? t : t.replace(/\n+$/, ''));

async function main() {
  const pwRes = loadPlaywright();
  const pw = pwRes.mod.playwright || pwRes.mod;
  const { browser, label } = await launchBrowser(pw);
  const s = await createSession(browser, { colorScheme: 'dark' });
  try {
    await s.boot();
    const { page } = s;

    await page.evaluate((d) => {
      window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: d }));
    }, {
      id: MD_ID, itemType: 'markdown', title: 'verify-code-highlight.md', pinned: true,
      content: MD, absPath: '/tmp/verify-code-highlight.md', size: MD.length, mtimeMs: 1757000000000,
    });
    await page.waitForSelector(`${paneSel(MD_ID)} .canvas-md-scroll pre`, { state: 'visible', timeout: 15000 });
    await settle(page, 300);

    const read = () => page.evaluate((sel) => {
      const host = document.querySelector(sel);
      const blocks = [...host.querySelectorAll('pre')].map((pre) => {
        const code = pre.querySelector('code');
        const wrap = pre.parentElement && pre.parentElement.classList.contains('code-block-wrap');
        return {
          lang: code ? [...code.classList].filter((c) => c.startsWith('language-')).join(' ') : '',
          classes: code ? [...code.classList] : [],
          text: code ? code.textContent : null,
          tokenSpans: code ? code.querySelectorAll('[class*="hljs-"]').length : 0,
          wrapped: !!wrap,
          copyBtn: !!(pre.parentElement && pre.parentElement.querySelector(':scope > .code-copy-btn')),
        };
      });
      return {
        blocks,
        mermaid: host.querySelectorAll('.nf-mermaid').length,
        hljsBlocks: host.querySelectorAll('pre code.hljs').length,
      };
    }, paneSel(MD_ID));

    const v = await read();
    const byLang = (l) => v.blocks.find((b) => b.classes.includes('language-' + l));
    const js = byLang('js');
    const unknown = byLang('nosuchlang');
    const bare = v.blocks.find((b) => !b.lang);
    const box = paneSel(MD_ID);

    // ① 已知语言 → 高亮
    ok('①a ```js 代码块存在', !!js);
    ok('①b ```js 的 <code> 带 hljs 标记', !!js && js.classes.includes('hljs'), js ? js.classes.join(' ') : 'missing');
    ok('①c ```js 内出现 hljs-* token span（真高亮，不是只加类名）',
      !!js && js.tokenSpans > 0, js ? `spans=${js.tokenSpans}` : 'missing');

    // ② 文本保真（实体解码 + hljs 重新转义可逆）
    ok('②a ```js 代码文本与源围栏逐字节相同', !!js && noTailNl(js.text) === JS_SRC, js ? JSON.stringify(js.text) : 'missing');
    ok('②b 未知语言块文本保真', !!unknown && noTailNl(unknown.text) === UNKNOWN_SRC,
      unknown ? JSON.stringify(unknown.text) : 'missing');
    ok('②c 无语言块文本保真', !!bare && noTailNl(bare.text) === BARE_SRC, bare ? JSON.stringify(bare.text) : 'missing');

    // ③ 复制按钮链路
    ok('③a 所有 pre 仍被 .code-block-wrap 包裹', v.blocks.every((b) => b.wrapped),
      `wrapped=${v.blocks.filter((b) => b.wrapped).length}/${v.blocks.length}`);
    ok('③b 所有 pre 仍带复制按钮', v.blocks.length > 0 && v.blocks.every((b) => b.copyBtn),
      `withBtn=${v.blocks.filter((b) => b.copyBtn).length}/${v.blocks.length}`);
    ok('③c 复制按钮取到的是源码而非高亮 markup',
      !!js && noTailNl(js.text) === JS_SRC, 'textContent 去标签后应与源一致');

    // ④ 未知语言 → 不高亮
    ok('④a 未知语言块不标 hljs', !!unknown && !unknown.classes.includes('hljs'),
      unknown ? unknown.classes.join(' ') : 'missing');
    ok('④b 未知语言块无 token span', !!unknown && unknown.tokenSpans === 0, unknown ? `spans=${unknown.tokenSpans}` : 'missing');

    // ⑤ 无语言标签 → 不高亮（刻意口径）
    ok('⑤a 无语言围栏不标 hljs', !!bare && !bare.classes.includes('hljs'),
      bare ? bare.classes.join(' ') : 'missing');

    // ⑥ mermaid 仍是图表，不是代码
    ok('⑥a ```mermaid 仍渲染为 .nf-mermaid 占位', v.mermaid === 1, `count=${v.mermaid}`);
    ok('⑥b 全文档恰好 1 个高亮块（无越界高亮）', v.hljsBlocks === 1, `count=${v.hljsBlocks}`);

    void box;
  } finally {
    await s.close();
    await browser.close();
  }

  const failed = results.filter((r) => !r.pass);
  for (const r of results) {
    console.log(`  ${r.pass ? 'PASS' : 'FAIL'}  ${r.name}${r.extra ? `  (${r.extra})` : ''}`);
  }
  console.log(`\nverify-md-code-highlight: ${results.length - failed.length}/${results.length} passed (browser=${label})`);
  process.exit(failed.length ? 1 : 0);
}

main().catch((e) => {
  console.error('verify-md-code-highlight: harness error —', e && e.message ? e.message : e);
  if (e && e.stack) console.error(e.stack.split('\n').slice(1, 4).join('\n'));
  process.exit(2);
});

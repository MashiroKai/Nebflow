// verify-ref-p12.cjs — #303 P1/P2 已实施项：B5(task ref shape) + C2(card click jump).
//
// reference.js is a self-contained module (imports i18n only, no DOM side
// effects at load), so we import it directly into a blank harness page served
// from the worktree web/ root and assert:
//   B5  makeReference(refType:'task') → type:'ref', refType:'task',
//       display.label === '@#<id> <subject>', source.taskId/sessionId/title
//   B5b renderRefBlock(mode:'input') of the task ref shows '@#<id>' mention
//   C2  renderRefBlock(mode:'message') card click dispatches 'workspace-open-item'
//       with the correct open-file detail (file ref) / open-url (html-element ref)
//   C2b card click on a document ref dispatches with absPath for readFile fetch

const { chromium } = require('playwright');
const BASE = 'http://127.0.0.1:8193';

const results = [];
function ok(name, cond, detail) {
  results.push({ name, pass: !!cond, detail });
  console.log((cond ? 'PASS ' : 'FAIL ') + name + (detail ? ' — ' + detail : ''));
}

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push(String(e)));

  // Serve a minimal module page that imports the real reference.js.
  await page.goto(BASE + '/index.html', { waitUntil: 'domcontentloaded' }).catch(() => {});
  // Load reference.js as a module and run the assertions in-page.
  const out = await page.evaluate(async () => {
    const { makeReference, renderRefBlock } = await import('/js/reference.js');
    const res = {};
    const evts = [];
    window.addEventListener('workspace-open-item', (e) => evts.push(e.detail));

    // B5: task reference shape
    const tref = makeReference({ refType: 'task', source: { kind: 'task', taskId: 101, sessionId: 'sess-x', title: '修复登录' } });
    res.tref = {
      exists: !!tref,
      type: tref && tref.type,
      refType: tref && tref.refType,
      label: tref && tref.display && tref.display.label,
      taskId: tref && tref.source && tref.source.taskId,
      sessionId: tref && tref.source && tref.source.sessionId,
      title: tref && tref.source && tref.source.title,
      metaIcon: tref && tref.meta && tref.meta.icon,
    };

    // B5b: input-mode card shows the @# mention
    const inputCard = tref ? renderRefBlock(tref, { mode: 'input' }) : null;
    res.inputCard = inputCard ? {
      refType: inputCard.dataset.refType,
      text: inputCard.textContent,
    } : null;

    // C2: message card click → workspace-open-item for a file ref
    const fref = makeReference({ refType: 'file', source: { kind: 'workspace', path: 'src/a.txt', fileName: 'a.txt', mimeType: 'text/plain' } });
    const fCard = fref ? renderRefBlock(fref, { mode: 'message' }) : null;
    if (fCard) fCard.click();
    res.fileJump = evts[0] || null;

    // C2b: document ref → absPath dispatch (for readFile fetch)
    const dref = makeReference({ refType: 'document', source: { kind: 'canvas', path: 'docs/spec.md', fileName: 'spec.md', mimeType: 'text/markdown' }, anchor: { pageStart: 3, pageEnd: 5 } });
    const dCard = dref ? renderRefBlock(dref, { mode: 'message' }) : null;
    if (dCard) dCard.click();
    res.docJump = evts[1] || null;

    // C2c: html-element ref → url dispatch
    const href = makeReference({ refType: 'html-element', source: { kind: 'web', url: 'https://example.com/post', title: 'Post' }, anchor: { selector: 'p:nth-of-type(2)', text: 'Some text' } });
    const hCard = href ? renderRefBlock(href, { mode: 'message' }) : null;
    if (hCard) hCard.click();
    res.htmlJump = evts[2] || null;

    return res;
  });

  ok('B5 task ref exists + type/refType', out.tref && out.tref.exists && out.tref.type === 'ref' && out.tref.refType === 'task', JSON.stringify(out.tref));
  ok('B5 label is @#<id> <subject>', out.tref && out.tref.label === '@#101 修复登录', 'label=' + (out.tref && out.tref.label));
  ok('B5 source carries taskId/sessionId/title', out.tref && out.tref.taskId === 101 && out.tref.sessionId === 'sess-x' && out.tref.title === '修复登录');
  ok('B5 meta icon clipboard', out.tref && out.tref.metaIcon === 'clipboard');
  ok('B5b input card refType=task + @# mention', out.inputCard && out.inputCard.refType === 'task' && out.inputCard.text.includes('@#101'), 'text=' + (out.inputCard && out.inputCard.text));
  ok('C2 file card click → workspace-open-item (file)', out.fileJump && out.fileJump.absPath === 'src/a.txt' && out.fileJump.id === 'file:src/a.txt', JSON.stringify(out.fileJump));
  ok('C2b document card click → absPath dispatch', out.docJump && out.docJump.absPath === 'docs/spec.md', JSON.stringify(out.docJump));
  ok('C2c html-element card click → url dispatch', out.htmlJump && out.htmlJump.itemType === 'url' && out.htmlJump.url === 'https://example.com/post', JSON.stringify(out.htmlJump));

  ok('no page errors', errs.length === 0, errs.slice(0, 3).join(' | '));

  await browser.close();
  const failed = results.filter(r => !r.pass);
  console.log('\n=== SUMMARY ===');
  console.log(`${results.length - failed.length}/${results.length} PASS`);
  process.exit(failed.length ? 1 : 0);
})();

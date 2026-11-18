// runtime.mjs — shared prototype runtime: reconstruct the two diff sides from the
// real fixtures and expose a candidate-agnostic render contract.
//
// Every candidate renders the SAME two payloads:
//   · delegate — real engine file-changes report (one file, unified diff text)
//   · ask      — real git commit (base text / current text + hunks)

/** Parse a unified diff body → { base, current } full texts. */
export function unifiedToSides(diffText) {
  const base = [], cur = [];
  for (const line of String(diffText).split('\n')) {
    if (line.startsWith('@@')) continue;
    if (line.startsWith('diff --git') || line.startsWith('index ') || line.startsWith('--- ') || line.startsWith('+++ ') || line.startsWith('\\')) continue;
    if (line.startsWith('+')) cur.push(line.slice(1));
    else if (line.startsWith('-')) base.push(line.slice(1));
    else { base.push(line.slice(1)); cur.push(line.slice(1)); }
  }
  return { base: base.join('\n'), current: cur.join('\n') };
}

/** Build a unified patch from two texts + hunks (3 lines of context). */
export function sidesToPatch(baseText, curText, hunks, fileName, ctx = 3) {
  const bl = baseText.split('\n'), cl = curText.split('\n');
  let out = `--- a/${fileName}\n+++ b/${fileName}\n`;
  for (const h of hunks) {
    const bs = Math.max(0, h.oldStart - ctx), be = Math.min(bl.length, h.oldStart + h.oldCount + ctx);
    const cs = Math.max(0, h.newStart - ctx), ce = Math.min(cl.length, h.newStart + h.newCount + ctx);
    const oldCount = be - bs, newCount = ce - cs;
    out += `@@ -${bs + 1},${oldCount} +${cs + 1},${newCount} @@\n`;
    for (let i = bs; i < h.oldStart; i++) out += ' ' + bl[i] + '\n';
    for (let i = h.oldStart; i < h.oldStart + h.oldCount; i++) out += '-' + bl[i] + '\n';
    for (let i = h.newStart; i < h.newStart + h.newCount; i++) out += '+' + cl[i] + '\n';
    for (let i = h.oldStart + h.oldCount; i < be; i++) out += ' ' + bl[i] + '\n';
  }
  return out;
}

export function loadFixtures() {
  const f = window.__FIXTURES__;
  const delegate = unifiedToSides(f.delegate.files[0].diffText);
  return {
    delegatePath: f.delegate.files[0].path,
    delegateDiff: f.delegate.files[0].diffText,
    delegateBase: delegate.base,
    delegateCurrent: delegate.current,
    ask: f.ask,
    askPatch: sidesToPatch(f.ask.baseText, f.ask.currentText, f.ask.hunks, f.ask.fileName),
  };
}

/** Build the page shell: two 375x812 screens (delegate, ask). */
export function mountShell(render) {
  const fx = loadFixtures();
  const url = new URL(location.href);
  const theme = url.searchParams.get('theme') || 'dark';
  document.documentElement.dataset.theme = theme;
  const label = document.getElementById('cand').textContent;
  document.title = `${label} · ${theme}`;
  document.getElementById('s-delegate-hdr').textContent =
    `delegate diff · ${fx.delegatePath}`;
  document.getElementById('s-ask-hdr').textContent =
    `canvas ask diff · ${fx.ask.fileName} (${fx.ask.hunks.length} hunks)`;
  render({
    delegate: { el: document.getElementById('delegate-root'), base: fx.delegateBase, current: fx.delegateCurrent, patch: fx.delegateDiff, path: fx.delegatePath, theme },
    ask: { el: document.getElementById('ask-root'), base: fx.ask.baseText, current: fx.ask.currentText, patch: fx.askPatch, path: fx.ask.fileName, theme },
  });
}

/** Report measured facts back to the capture harness. */
export function reportResult(key, data) { (window.__RESULTS__ ||= {})[key] = data; }

// Candidate B — diff2html (DOM renderer over a unified patch).
import { html } from 'diff2html';
import { mountShell, reportResult } from './runtime.mjs';

function render(el, patch, opts = {}) {
  el.innerHTML = html(patch, {
    drawFileList: false,
    matching: 'lines',
    outputFormat: opts.sideBySide ? 'side-by-side' : 'line-by-line',
    renderNothingWhenEmpty: false,
  });
  return el;
}

mountShell(({ delegate, ask }) => {
  const t0 = performance.now();
  const d = render(delegate.el, delegate.patch, { sideBySide: false });
  const a = render(ask.el, ask.patch, { sideBySide: false });
  reportResult('diff2html', {
    mountMs: +(performance.now() - t0).toFixed(1),
    delegateRows: d.querySelectorAll('.d2h-file-line, .d2h-code-line').length,
    askRows: a.querySelectorAll('.d2h-file-line, .d2h-code-line').length,
    delegateScrollW: d.scrollWidth, delegateClientW: d.clientWidth,
    askScrollW: a.scrollWidth, askClientW: a.clientWidth,
    hasLineNumbers: !!d.querySelector('.d2h-code-linenumber'),
    hasWordDiff: !!d.querySelector('.d2h-code-line del, .d2h-code-line ins'),
  });
  // second pass: side-by-side, kept off-screen for a separate shot
  const host = document.getElementById('sbs-root');
  if (host) {
    host.innerHTML = html(delegate.patch, { drawFileList: false, matching: 'lines', outputFormat: 'side-by-side' });
    reportResult('diff2html-sbs', { scrollW: host.scrollWidth, clientW: host.clientWidth, rows: host.querySelectorAll('.d2h-file-side-diff').length });
  }
});

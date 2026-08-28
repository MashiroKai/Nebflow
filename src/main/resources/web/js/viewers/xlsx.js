// viewers/xlsx.js — XLSX viewer (SheetJS, lazily loaded, multi-sheet tabs).
// Migrated verbatim from fileViewers.js (viewXlsx).

import { getToken, escapeHtml } from './shared.js';

/** XLSX viewer — fetch binary, parse with SheetJS, render first sheet as table */
async function viewXlsx(pane, { absPath, fileName }) {
  pane.innerHTML = `<div class="canvas-md-viewer" style="display:flex;align-items:center;justify-content:center;color:var(--color-text-muted);opacity:0.5;">Loading spreadsheet...</div>`;
  const tok = getToken();
  const url = `/api/nf-file?path=${encodeURIComponent(absPath)}&token=${encodeURIComponent(tok)}`;
  try {
    const resp = await fetch(url);
    if (!resp.ok) {
      const detail = await resp.text().catch(() => '');
      throw new Error(`HTTP ${resp.status}${detail ? ': ' + detail : ''}`);
    }
    const arrayBuffer = await resp.arrayBuffer();
    // Load SheetJS lazily (non-module script, attaches to window.XLSX)
    if (typeof window.XLSX === 'undefined') {
      await new Promise((resolve, reject) => {
        const s = document.createElement('script');
        s.src = 'vendor/xlsx.full.min.js';
        s.onload = resolve;
        s.onerror = () => reject(new Error('Failed to load SheetJS'));
        document.head.appendChild(s);
      });
    }
    const wb = window.XLSX.read(arrayBuffer, { type: 'array' });
    const sheetName = wb.SheetNames[0];
    if (!sheetName) {
      pane.innerHTML = `<div class="canvas-error">Spreadsheet has no sheets.</div>`;
      return;
    }
    // #303 C5-A4: record per-sheet dimensions for the reference anchor -
    // the anchor of an xlsx tab is { sheet, cellRange } (active sheet's full
    // table when there is no cell selection).
    /** @type {{active:string, dims:Object<string,string>}} */
    const xr = { active: sheetName, dims: {} };
    wb.SheetNames.forEach(n => { xr.dims[n] = (wb.Sheets[n] && wb.Sheets[n]['!ref']) || ''; });
    pane._xlsxRef = xr;
    const sheet = wb.Sheets[sheetName];
    const html = window.XLSX.utils.sheet_to_html(sheet, { editable: false });
    const sheetTabs = wb.SheetNames.map((name, i) =>
      `<button class="xlsx-sheet-tab ${i === 0 ? 'active' : ''}" data-sheet="${escapeHtml(name)}">${escapeHtml(name)}</button>`
    ).join('');
    pane.innerHTML = `
      <div class="canvas-xlsx-viewer">
        <div class="xlsx-sheet-bar">${sheetTabs}</div>
        <div class="xlsx-sheet-content">${html}</div>
      </div>`;
    pane.classList.add('scrollable');
    // Wire sheet tabs
    pane.querySelectorAll('.xlsx-sheet-tab').forEach(btn => {
      btn.addEventListener('click', () => {
        const name = btn.dataset.sheet;
        const s2 = wb.Sheets[name];
        const html2 = window.XLSX.utils.sheet_to_html(s2, { editable: false });
        pane.querySelector('.xlsx-sheet-content').innerHTML = html2;
        pane.querySelectorAll('.xlsx-sheet-tab').forEach(b => b.classList.toggle('active', b === btn));
        xr.active = name;
      });
    });
  } catch (err) {
    pane.innerHTML = `<div class="canvas-error">Failed to render XLSX: ${escapeHtml(err.message)}</div>`;
  }
}

export default {
  name: 'xlsx',
  label: 'XLSX',
  extensions: ['.xls', '.xlsx', '.xlsm'],
  binary: true,
  priority: 0,
  render: viewXlsx,
};

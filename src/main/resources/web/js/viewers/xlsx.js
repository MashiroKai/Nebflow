// viewers/xlsx.js — XLSX viewer (SheetJS, lazily loaded, multi-sheet tabs).
// Migrated verbatim from fileViewers.js (viewXlsx).

import { escapeHtml } from './shared.js';
import { ticketUrl, reMint } from '../nfTicket.js';

/** XLSX viewer — fetch binary, parse with SheetJS, render first sheet as table
 *
 *  `objectUrl` = 附件预览腿（attachmentPreview.js）：整件字节已由应用内鉴权路由取回，
 *  blob: URL 直接交给 SheetJS（fetch 后 `arrayBuffer()`），无本机路径 ⇒ 不触票据链。
 *  与 `pdf.js` / `image.js` 的 `objectUrl` 腿**同构**（同一模式，非第二套取字节路）。
 *
 *  canvas-office-render-fix 批（2026-10-06）：取件失败的自愈与 image.js/video.js
 *  **同构**（T3/T4 纪律）——票据腿取件失败 ⇒ 恰好一次 reMint 重取票＋重试；第二败
 *  即定局，落既有错误面板。blob 腿无可刷新的凭据 ⇒ 不重试（image.js 同守卫）。 */
async function viewXlsx(pane, { absPath, fileName, objectUrl }) {
  pane.innerHTML = `<div class="canvas-md-viewer" style="display:flex;align-items:center;justify-content:center;color:var(--color-text-muted);opacity:0.5;">Loading spreadsheet...</div>`;
  const fetchBytes = async (/** @type {string} */ url) => {
    const resp = await fetch(url);
    if (!resp.ok) {
      const detail = await resp.text().catch(() => '');
      throw new Error(`HTTP ${resp.status}${detail ? ': ' + detail : ''}`);
    }
    return resp.arrayBuffer();
  };
  let retried = false;
  try {
    let arrayBuffer;
    try {
      arrayBuffer = await fetchBytes(objectUrl || await ticketUrl(absPath));
    } catch (err) {
      if (retried || !absPath || objectUrl) throw err;
      retried = true;
      arrayBuffer = await fetchBytes(await reMint(absPath));
    }
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

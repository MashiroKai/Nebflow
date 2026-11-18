// js/diffView/index.js — 手机端两个差异面的**共享组件单源**（作者裁定
// 2026-11-18「手机diff 按推荐」；选型报告
// .stand/docs/mobile-diff-view-survey__20261118_232537.md §三 主推）。
//
// 底座 = CodeMirror 6 + @codemirror/merge（随包分发，见
// scripts/build-vendor-codemirror-merge.mjs 与 package.json.vendorAssets）。
// 两个面（delegate diff = js/diffCanvas.js、canvas ask diff =
// js/askEditReview.js）都**调用本组件**，不各留一套渲染器 —— 硬不变式①。
//
// 组件回答报告的 D1–D9：
//   D1 折行开关（lineWrapping，偏好持久化；手机缺省开）
//   D2 行号（lineNumbers）
//   D3 词级差异（highlightChanges ⇒ .cm-changedText）
//   D6 逐块接受/拒绝按钮改壳到 ≥44px（mergeControls 自定义元素）
//   D7 正文/行高 token 化（≥13px / 1.5）
//   D8 未变段折叠（collapseUnchanged）+ VCS 元信息在解析期丢弃（unifiedDiff.js）
//   D9 视口虚拟化由编辑面承接（不再自研按帧分块）
//   D4（让位带）与 D5（对比度）由容器 token 与主题 token 承担，见
//      css/diffViewCm.css `.nf-dv-band` / css/stand-tokens.css `--nf-dv-*`。
//
// 调用契约：
//   await mountDiff(host, {
//     original, current,            // 基线 / 现文（review 面直给）
//     diffText,                     // read 面：统一差异文本（自动转双文档，丢 VCS 元信息）
//     mode: 'read' | 'review',
//     hunks,                        // review 面：可审块表 [{id, oldStart, oldCount, newStart, newCount}]
//     onChunkAction(hunkId, action) // review 面：'accept' | 'reject'
//   }) -> { view, destroy(), setWrap(bool), getWrap(). }

import { loadCodeMirror } from './cmLoader.js';
import { diffTheme } from './theme.js';
import { unifiedDiffToDocs } from './unifiedDiff.js';

export { unifiedDiffToDocs } from './unifiedDiff.js';

const WRAP_PREF_KEY = 'nf_diff_wrap';

/** Wrap preference (persisted; default ON — 手机缺省折行，D1). */
export function getWrapPref() {
  try {
    const v = localStorage.getItem(WRAP_PREF_KEY);
    if (v === '0') return false;
    if (v === '1') return true;
  } catch { /* private mode */ }
  return true;
}
function setWrapPref(v) {
  try { localStorage.setItem(WRAP_PREF_KEY, v ? '1' : '0'); } catch { /* ignore */ }
}

/** pane → mount generation; a newer mount invalidates an in-flight async load. */
const mountGen = new WeakMap();

function tt(zh, en) {
  let lang = '';
  try { lang = localStorage.getItem('nebflow_locale') || localStorage.getItem('neblink_locale') || ''; } catch { /* ignore */ }
  if (!lang) lang = document.documentElement.lang || navigator.language || '';
  return /^zh/i.test(lang) ? zh : en;
}

/** Resolve which review hunk a chunk button belongs to, from its DOM anchor.
 *  The merge view renders one `.cm-deletedChunk` anchor per diff chunk; we map
 *  its document position to the chunk list, then to the hunk table by the
 *  current-side line number (fallback: document order index). */
function resolveHunk(cm, view, btn, hunks) {
  const dom = btn.closest('.cm-deletedChunk') || btn.closest('.cm-changedLine');
  let idx = -1;
  if (dom) {
    let pos = -1;
    try { pos = view.posAtDOM(dom); } catch { pos = -1; }
    if (pos >= 0) {
      // getChunks(state) → { chunks, side } | null（@codemirror/merge 6.12）。
      const res = cm.getChunks(view.state);
      const chunks = (res && res.chunks) || [];
      let best = Infinity;
      chunks.forEach((c, i) => {
        const d = pos < c.fromB ? c.fromB - pos : (pos > c.toB ? pos - c.toB : 0);
        if (d < best) { best = d; idx = i; }
      });
      if (idx >= 0) {
        let lineNo = -1;
        try { lineNo = view.state.doc.lineAt(Math.min(pos, view.state.doc.length)).number - 1; } catch { lineNo = -1; }
        if (lineNo >= 0) {
          const byLine = hunks.findIndex((h) => Number(h.newStart) === lineNo);
          if (byLine >= 0) return hunks[byLine].id;
        }
      }
    }
  }
  if (idx >= 0 && idx < hunks.length) return hunks[idx].id;
  return hunks.length === 1 ? hunks[0].id : null;
}

/** Mount the shared diff component into `host`.
 *  @param {HTMLElement} host
 *  @param {object} opts
 *  @returns {Promise<{view:any, destroy:Function, setWrap:Function, getWrap:Function}>} */
export async function mountDiff(host, opts = {}) {
  const cm = await loadCodeMirror();
  const { EditorState, EditorView, Compartment, lineNumbers, unifiedMergeView } = cm;
  const gen = (mountGen.get(host) || 0) + 1;
  mountGen.set(host, gen);

  // 双文档：review 面直给 original/current；read 面从统一差异文本转（D8 丢元信息）。
  let original = String(opts.original ?? '');
  let current = String(opts.current ?? '');
  if (opts.diffText != null && opts.original == null) {
    const docs = unifiedDiffToDocs(opts.diffText);
    original = docs.original;
    current = docs.current;
  }
  const mode = opts.mode === 'review' ? 'review' : 'read';
  const hunks = Array.isArray(opts.hunks) ? opts.hunks : [];
  const onChunkAction = typeof opts.onChunkAction === 'function' ? opts.onChunkAction : null;

  host.textContent = '';
  const root = document.createElement('div');
  root.className = 'nf-dv';
  root.setAttribute('data-mode', mode);
  const toolbar = document.createElement('div');
  toolbar.className = 'nf-dv-toolbar';
  const wrapBtn = document.createElement('button');
  wrapBtn.type = 'button';
  wrapBtn.className = 'nf-dv-wrapbtn';
  const editorHost = document.createElement('div');
  editorHost.className = 'nf-dv-editor';
  toolbar.append(wrapBtn);
  root.append(toolbar, editorHost);
  host.append(root);

  const wrapComp = new Compartment();
  let wrap = opts.wrap == null ? getWrapPref() : !!opts.wrap;

  const controls = mode === 'review'
    ? (name /*, cmHandler */) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'nf-dv-chunkbtn nf-dv-chunkbtn-' + name;
      b.setAttribute('data-chunk-action', name);
      b.setAttribute('aria-label', name === 'accept'
        ? tt('接受本块（保留该改动）', 'Accept this block (keep the change)')
        : tt('拒绝本块（回退该改动）', 'Reject this block (revert the change)'));
      b.textContent = name === 'accept' ? tt('接受', 'Accept') : tt('拒绝', 'Reject');
      return b;
    }
    : false;

  const mergeExt = unifiedMergeView({
    original,
    mergeControls: controls,
    gutter: true,
    highlightChanges: true,
    syntaxHighlightDeletions: false,
    collapseUnchanged: { margin: 3, minSize: 4 },
  });

  const view = new EditorView({
    parent: editorHost,
    state: EditorState.create({
      doc: current,
      extensions: [
        lineNumbers(),
        mergeExt,
        EditorView.editable.of(false),
        EditorState.readOnly.of(true),
        wrapComp.of(wrap ? EditorView.lineWrapping : []),
        diffTheme(cm),
      ],
    }),
  });

  const syncWrapBtn = () => {
    wrapBtn.textContent = wrap ? tt('折行', 'Wrap') : tt('不折行', 'No wrap');
    wrapBtn.setAttribute('aria-pressed', wrap ? 'true' : 'false');
    wrapBtn.title = wrap ? tt('关闭自动折行', 'Disable line wrapping') : tt('开启自动折行', 'Enable line wrapping');
  };
  const setWrap = (v) => {
    wrap = !!v;
    view.dispatch({ effects: wrapComp.reconfigure(wrap ? EditorView.lineWrapping : []) });
    setWrapPref(wrap);
    syncWrapBtn();
  };
  syncWrapBtn();
  wrapBtn.addEventListener('click', () => setWrap(!wrap));

  // 逐块动作：capture 阶段拦截 mousedown（merge 把 acceptChunk/rejectChunk 挂在
  // 我们造的按钮的 onmousedown 上；不拦就会真改文档 —— ask 面的语义是"标记已审/
  // 回写回退"，走 WS，不是本地合并）。
  const onMouseDown = (e) => {
    const el = /** @type {HTMLElement|null} */ (e.target instanceof Element ? e.target : null);
    const btn = el && el.closest('.nf-dv-chunkbtn');
    if (!btn) return;
    e.preventDefault();
    e.stopPropagation();
    const action = btn.getAttribute('data-chunk-action') === 'reject' ? 'reject' : 'accept';
    const hunkId = resolveHunk(cm, view, btn, hunks);
    if (onChunkAction && hunkId) onChunkAction(hunkId, action);
  };
  editorHost.addEventListener('mousedown', onMouseDown, true);

  const destroy = () => {
    editorHost.removeEventListener('mousedown', onMouseDown, true);
    if (mountGen.get(host) === gen) { try { view.destroy(); } catch { /* noop */ } }
    host.textContent = '';
  };

  // 挂载后若已被后续 mount 取代，立即拆掉（切换文件竞态）。
  if (mountGen.get(host) !== gen) destroy();

  const api = { view, destroy, setWrap, getWrap: () => wrap };
  // 测试/取证接缝：把组件 api 挂在编辑宿主上（规格与截图脚本读取虚拟化读数）。
  /** @type {any} */ (editorHost).__nfDiffApi = api;
  return api;
}

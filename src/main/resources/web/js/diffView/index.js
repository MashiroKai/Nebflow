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
//      css/diffView.css `.nf-dv-band` / css/stand-tokens.css `--nf-dv-*`。
//
// ── 本批（作者令 2026-11-19「diff 的逐块接受和全部接受的 UI 要设计一下 然后 diff
//    要展示 canvas 适配以及多文件 diff 适配 只有 ask 是有逐块接受 正常只查看 diff」）
// ───────────────────────────────────────────────────────────────────────────
// ① 审阅动作区**唯一落点在本组件**（delegate 只读模式零控件 = 模式闸门）：
//      · 逐块接受/拒绝 = 每个差异块内的行内动作带（mergeControls，≥44px）；
//      · 全部接受/全部拒绝 = 工具条左端两颗同档钮（.nf-dv-accept-all / -reject-all）。
//    「全部接受」走**既有通道同一语义**（= 把逐块动作批量化）：onChunkAction
//    (null, 'accept')，消费面（askEditReview）据此发 askEditAccept **不带 hunkId**
//    —— 既有协议已把「无 hunkId」定义为全量，故零新帧/零新参数。
// ② 状态可见：动作发出即就地给该块落 data-chunk-state="accepted|rejected" 并挂
//    状态标签（已接受/已拒绝）；等待期（防双发）由 setWaiting(true) 整组禁用。
// ③ 多文件：一段差异文本含多文件段时按文件分节（文件列表 + 每节计数 + 单文件
//    折叠/单选），逐块动作归属到**当前节**的 hunk 表（不落到别的文件）。
//
// 调用契约：
//   await mountDiff(host, {
//     original, current,            // 单文件 review 面直给基线/现文
//     diffText,                     // read 面：统一差异文本（可含多文件段；丢 VCS 元信息）
//     files,                        // 多文件面：[{path, original, current, hunks, added, removed, changeType}]
//     mode: 'read' | 'review',
//     hunks,                        // review 面：可审块表 [{id, oldStart, oldCount, newStart, newCount}]
//     onChunkAction(hunkId, action) // review 面：'accept' | 'reject'；hunkId=null ⇒ 全量
//     wrap, waiting
//   }) -> { view, destroy(), setWrap(bool), getWrap(), setWaiting(bool), loadFile(i) }

import { loadCodeMirror } from './cmLoader.js';
import { diffTheme } from './theme.js';
import { parseUnifiedDiffFiles, unifiedDiffToDocs } from './unifiedDiff.js';

export { unifiedDiffToDocs, parseUnifiedDiffFiles } from './unifiedDiff.js';

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

/** 顶部标题数据（canvas 头部 chrome 的**文本面**；本批 2026-11-19）——
 *  只有调用面显式给 `title` 才构建标题段：delegate 阅读面自带 `.fcv-headpill`
 *  （同一条 canvas chrome，见 diffView.css §文件头胶囊批），此处再出一颗就是
 *  同屏两处标题。取文口径 = 文件名（单文件；与 canvas 页签名同源 baseName 读法）
 *  /「N 个文件」（多文件）+ 增删计数 + 审阅状态（review 面「N 块待审」）。 */
function normalizeTitle(t) {
  if (t == null) return null;
  if (typeof t === 'string') return t ? { file: t, counts: '', status: '', path: '' } : null;
  if (typeof t !== 'object') return null;
  const out = {
    file: String(t.file ?? t.name ?? ''),
    counts: String(t.counts ?? ''),
    status: String(t.status ?? ''),
    path: String(t.path ?? ''),
  };
  if (!out.file && !out.counts && !out.status) return null;
  return out;
}

function tt(zh, en) {
  let lang = '';
  try { lang = localStorage.getItem('nebflow_locale') || localStorage.getItem('neblink_locale') || ''; } catch { /* ignore */ }
  if (!lang) lang = document.documentElement.lang || navigator.language || '';
  return /^zh/i.test(lang) ? zh : en;
}

/** Mount options → 文件节表（顺序即呈现顺序）。三类输入互斥优先：
 *  files[] > original/current（单文件 review）> diffText（read 面，可多文件段）。 */
function normalizeSections(opts) {
  if (Array.isArray(opts.files) && opts.files.length) {
    return opts.files.map((f, i) => {
      const o = f && typeof f === 'object' ? f : {};
      return {
        path: String(o.path ?? ''),
        original: String(o.original ?? ''),
        current: String(o.current ?? ''),
        hunks: Array.isArray(o.hunks) ? o.hunks : [],
        added: Number(o.added) || 0,
        removed: Number(o.removed) || 0,
        changeType: String(o.changeType || 'modified'),
        index: i,
      };
    });
  }
  if (opts.original != null || opts.current != null) {
    return [{
      path: String(opts.path ?? ''),
      original: String(opts.original ?? ''),
      current: String(opts.current ?? ''),
      hunks: Array.isArray(opts.hunks) ? opts.hunks : [],
      added: 0, removed: 0, changeType: 'modified', index: 0,
    }];
  }
  if (opts.diffText != null) {
    const parsed = parseUnifiedDiffFiles(opts.diffText);
    return parsed.files.map((f, i) => ({
      path: f.path, original: f.docs.original, current: f.docs.current,
      hunks: [], added: f.added, removed: f.removed, changeType: f.changeType, index: i,
    }));
  }
  return [];
}

/** Resolve which review hunk a chunk button belongs to, from its DOM anchor.
 *  The merge view renders one `.cm-deletedChunk` anchor per diff chunk; we map
 *  its document position to the chunk list, then to the hunk table by the
 *  current-side line number (fallback: document order index).
 *  `hunks` is the **active file section's** table — 多文件下逐块归属因此天然正确。 */
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
 *  @returns {Promise<{view:any, destroy:Function, setWrap:Function, getWrap:Function,
 *                     setWaiting:Function, loadFile:Function, sections:Array}>} */
export async function mountDiff(host, opts = {}) {
  const cm = await loadCodeMirror();
  const { EditorState, EditorView, Compartment, lineNumbers, unifiedMergeView } = cm;
  const gen = (mountGen.get(host) || 0) + 1;
  mountGen.set(host, gen);

  let sections = normalizeSections(opts);
  if (!sections.length) {
    sections = [{ path: '', original: '', current: '', hunks: [], added: 0, removed: 0, changeType: 'modified', index: 0 }];
  }
  const mode = opts.mode === 'review' ? 'review' : 'read';
  const onChunkAction = typeof opts.onChunkAction === 'function' ? opts.onChunkAction : null;
  const multi = sections.length > 1;

  host.textContent = '';
  const root = document.createElement('div');
  root.className = 'nf-dv';
  root.setAttribute('data-mode', mode);
  // 动作带形态（作者令 2026-11-19 两形态对照）：'flow'（缺省/推荐）= 流内右对齐玻璃
  // 控制带（零 position，与悬浮控件零交叠，谁都不让位）；'floating'（备选）= 同一
  // DOM/同一几何族，改由**对照页本地样式**把它摆成 canvas 右下角悬浮控件族 —— 组件
  // 只落 `data-chrome` 接缝，生产样式表零新增定位选择器（不动定位棘轮台账）。
  root.setAttribute('data-chrome', opts.chrome === 'floating' ? 'floating' : 'flow');
  if (multi) root.setAttribute('data-multifile', '1');
  // ── 顶部标题（canvas 特质批 2026-11-19）：与 canvas 卡头胶囊同一 chrome
  //    （居中玻璃胶囊 / 同一 --ds-* 间距与 --glass-* 配色 token）。
  const titleInfo = normalizeTitle(opts.title);
  if (titleInfo) {
    const head = document.createElement('div');
    head.className = 'nf-dv-head';
    const pill = document.createElement('div');
    pill.className = 'nf-dv-title';
    const fileEl = document.createElement('span');
    fileEl.className = 'nf-dv-title-file';
    fileEl.textContent = titleInfo.file;
    if (titleInfo.path) fileEl.title = titleInfo.path;
    pill.append(fileEl);
    if (titleInfo.counts) {
      const countsEl = document.createElement('span');
      countsEl.className = 'nf-dv-title-counts';
      countsEl.textContent = titleInfo.counts;
      pill.append(countsEl);
    }
    if (titleInfo.status) {
      const statusEl = document.createElement('span');
      statusEl.className = 'nf-dv-title-status';
      statusEl.textContent = titleInfo.status;
      pill.append(statusEl);
    }
    head.append(pill);
    root.append(head);
  }

  const toolbar = document.createElement('div');
  toolbar.className = 'nf-dv-toolbar';
  const wrapBtn = document.createElement('button');
  wrapBtn.type = 'button';
  wrapBtn.className = 'nf-dv-wrapbtn';

  // 动作钮工厂（review 专用；read 模式根本不建 —— DOM 里零残留）。
  const mkAction = (kind, label, aria) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'nf-dv-allbtn nf-dv-actionbtn nf-dv-' + kind;
    b.setAttribute('data-chunk-action', kind === 'accept-all' ? 'accept' : 'reject');
    b.setAttribute('data-scope', 'all');
    b.setAttribute('aria-label', aria);
    b.textContent = label;
    return b;
  };
  // 工具区（折行开关）留在顶部工具条；**动作区已迁 canvas 右下角控件带**
  // （review 专用，DOM 末尾建 —— 见下方 allbar 段）。read 模式只有工具区 ⇒
  // 折行钮仍在右缘，与迁移批几何逐值一致（零回归）。
  const tools = document.createElement('div');
  tools.className = 'nf-dv-tools';
  tools.append(wrapBtn);
  toolbar.append(tools);

  const editorHost = document.createElement('div');
  editorHost.className = 'nf-dv-editor';
  root.append(toolbar);

  // 多文件：文件列表（每节一行，含路径/计数，≥44px 触控档）在编辑面之上。
  let filesEl = /** @type {HTMLDivElement|null} */ (null);
  if (multi) {
    filesEl = document.createElement('div');
    filesEl.className = 'nf-dv-files';
    filesEl.setAttribute('role', 'list');
    root.append(filesEl);
  }
  root.append(editorHost);

  // ── 审阅动作区（作者令 2026-11-19：「一次全部接受或者拒绝」= canvas 右下角控件）──
  //    复用 canvas 底部控件组惯例：玻璃胶囊组容器 + 成员钮（材质单源 = css/glass.css
  //    的「按钮组：一份实现、两个消费族」共享声明；几何/落位留本组件样式表）。
  //    read 模式根本不建这些节点 ⇒ DOM 零残留（模式闸门）。
  //    与既有 canvas 右下角控件（.ask-fab / .note-fab）的共存规则：本带是组件内
  //    **流内控制带**（零 position），占自己的布局行 ⇒ 与任何 canvas 悬浮控件零
  //    交叠（谁也不用让位）；且两个 diff 面都是无 absPath 的面板页签，
  //    ask/note 入口钮本就吃 .gate-off 可见性闸 ⇒ 同屏不会出现两颗「右下角钮」。
  if (mode === 'review') {
    const actions = document.createElement('div');
    actions.className = 'nf-dv-allbar';
    const group = document.createElement('div');
    group.className = 'nf-dv-allgroup';
    group.append(
      mkAction('accept-all', tt('全部接受', 'Accept all'), tt('全部接受（保留全部改动）', 'Accept all (keep every change)')),
      mkAction('reject-all', tt('全部拒绝', 'Reject all'), tt('全部拒绝（回退全部改动）', 'Reject all (revert every change)')),
    );
    actions.append(group);
    root.append(actions);
  }
  host.append(root);

  const wrapComp = new Compartment();
  const mergeComp = new Compartment();
  let wrap = opts.wrap == null ? getWrapPref() : !!opts.wrap;

  const controls = mode === 'review'
    ? (name /*, cmHandler */) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'nf-dv-chunkbtn nf-dv-chunkbtn-' + name;
      b.setAttribute('data-chunk-action', name);
      b.setAttribute('data-scope', 'chunk');
      b.setAttribute('aria-label', name === 'accept'
        ? tt('接受本块（保留该改动）', 'Accept this block (keep the change)')
        : tt('拒绝本块（回退该改动）', 'Reject this block (revert the change)'));
      b.textContent = name === 'accept' ? tt('接受', 'Accept') : tt('拒绝', 'Reject');
      return b;
    }
    : false;

  const mkMerge = (s) => unifiedMergeView({
    original: s.original,
    mergeControls: controls,
    gutter: true,
    highlightChanges: true,
    syntaxHighlightDeletions: false,
    collapseUnchanged: { margin: 3, minSize: 4 },
  });

  let activeIdx = 0;
  const view = new EditorView({
    parent: editorHost,
    state: EditorState.create({
      doc: sections[activeIdx].current,
      extensions: [
        lineNumbers(),
        mergeComp.of(mkMerge(sections[activeIdx])),
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

  // ── 多文件：文件列表 + 单选装载（单文件折叠 = 只展开被选中的那一节）──
  /** @type {HTMLButtonElement[]} */ const fileRows = [];
  const syncFileRows = () => {
    fileRows.forEach((row, i) => {
      const on = i === activeIdx;
      row.setAttribute('aria-expanded', on ? 'true' : 'false');
      if (on) row.setAttribute('data-active', '1'); else row.removeAttribute('data-active');
    });
  };
  const loadFile = (i) => {
    const idx = Math.min(Math.max(Number(i) || 0, 0), sections.length - 1);
    if (idx !== activeIdx) {
      activeIdx = idx;
      const s = sections[idx];
      view.dispatch({
        changes: { from: 0, to: view.state.doc.length, insert: s.current },
        effects: mergeComp.reconfigure(mkMerge(s)),
        selection: { anchor: 0 },
      });
      try { view.scrollDOM.scrollTop = 0; } catch { /* noop */ }
      syncFileRows();
    }
    return activeIdx;
  };
  if (multi && filesEl) {
    sections.forEach((s, i) => {
      const row = document.createElement('button');
      row.type = 'button';
      row.className = 'nf-dv-file';
      row.setAttribute('data-file-index', String(i));
      row.setAttribute('data-file-path', s.path);
      const pathEl = document.createElement('span');
      pathEl.className = 'nf-dv-filepath';
      pathEl.textContent = s.path || '(unknown)';
      pathEl.title = s.path;
      const counts = document.createElement('span');
      counts.className = 'nf-dv-filecounts';
      const add = document.createElement('span');
      add.className = 'nf-dv-fileadd';
      add.textContent = '+' + (s.added || 0);
      const del = document.createElement('span');
      del.className = 'nf-dv-filedel';
      del.textContent = '−' + (s.removed || 0);
      counts.append(add, document.createTextNode(' '), del);
      row.append(pathEl, counts);
      row.addEventListener('click', () => loadFile(i));
      fileRows.push(row);
      filesEl.append(row);
    });
    syncFileRows();
  }

  // ── 逐块动作：跳过 merge 自带的本地合并（ask 面的语义是「标记已审 / 回写回退」，
  //    走 WS，不是本地合并）。动作发出即就地落**可见状态**（已接受/已拒绝）。──
  const chunkElOf = (btn) => btn.closest('.cm-deletedChunk') || btn.closest('.cm-changedLine');
  const paintChunk = (chunkEl, action) => {
    if (!chunkEl) return;
    const state = action === 'reject' ? 'rejected' : 'accepted';
    chunkEl.setAttribute('data-chunk-state', state);
    const band = chunkEl.querySelector('.cm-chunkButtons') || chunkEl;
    let lbl = band.querySelector('.nf-dv-chunkstate');
    if (!lbl) {
      lbl = document.createElement('span');
      lbl.className = 'nf-dv-chunkstate';
      band.append(lbl);
    }
    lbl.textContent = state === 'accepted' ? tt('已接受', 'Accepted') : tt('已拒绝', 'Rejected');
  };
  const paintAll = (action) => {
    root.querySelectorAll('.cm-deletedChunk').forEach((el) => paintChunk(el, action));
  };

  const onMouseDown = (e) => {
    const el = /** @type {HTMLElement|null} */ (e.target instanceof Element ? e.target : null);
    const btn = el && el.closest('.nf-dv-chunkbtn');
    if (!btn) return;
    e.preventDefault();
    e.stopPropagation();
    const action = btn.getAttribute('data-chunk-action') === 'reject' ? 'reject' : 'accept';
    const hunkId = resolveHunk(cm, view, btn, sections[activeIdx].hunks);
    paintChunk(chunkElOf(btn), action);
    if (onChunkAction && hunkId) onChunkAction(hunkId, action);
  };
  editorHost.addEventListener('mousedown', onMouseDown, true);

  // 全量动作：**同一回调、同一语义**（hunkId=null ⇒ 消费面发「无 hunkId」全量帧）。
  const onAllClick = (e) => {
    const el = /** @type {HTMLElement|null} */ (e.target instanceof Element ? e.target : null);
    const hit = el && el.closest('.nf-dv-actionbtn');
    const btn = /** @type {HTMLButtonElement|null} */ (hit);
    if (!btn || btn.disabled) return;
    e.preventDefault();
    const action = btn.getAttribute('data-chunk-action') === 'reject' ? 'reject' : 'accept';
    paintAll(action);
    if (onChunkAction) onChunkAction(null, action);
  };
  root.addEventListener('click', onAllClick);

  // 等待面（防双发）：整组动作钮禁用；不动折行开关与文件切换（可继续观察）。
  const setWaiting = (w) => {
    const on = !!w;
    root.setAttribute('data-waiting', on ? '1' : '0');
    root.querySelectorAll('.nf-dv-chunkbtn, .nf-dv-actionbtn').forEach((el) => {
      /** @type {HTMLButtonElement} */ (el).disabled = on;
    });
  };
  setWaiting(opts.waiting);

  const destroy = () => {
    editorHost.removeEventListener('mousedown', onMouseDown, true);
    root.removeEventListener('click', onAllClick);
    if (mountGen.get(host) === gen) { try { view.destroy(); } catch { /* noop */ } }
    host.textContent = '';
  };

  // 挂载后若已被后续 mount 取代，立即拆掉（切换文件竞态）。
  if (mountGen.get(host) !== gen) destroy();

  const api = { view, destroy, setWrap, getWrap: () => wrap, setWaiting, loadFile, sections };
  // 测试/取证接缝：把组件 api 挂在编辑宿主上（规格与截图脚本读取虚拟化读数）。
  /** @type {any} */ (editorHost).__nfDiffApi = api;
  return api;
}

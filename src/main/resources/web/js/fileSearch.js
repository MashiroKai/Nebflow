// fileSearch.js — In-file search for Canvas tabs (Ctrl/Cmd+F retarget).
//
// Author's 2026-10-14 ruling: Ctrl/Cmd+F must search the CONTENT of the file
// open in the active Canvas tab (code, markdown, html) — not the chat history.
// The chat history search modal stays reachable: Ctrl+F falls back to it when
// no searchable file tab is active (chatSearch.js owns that fallback).
//
// Three search faces, one bar:
//   · Monaco source tabs (code / source mode) → the editor's own find widget
//     (`editor.getAction('actions.find')`) — best-in-class, nothing rebuilt.
//   · Rendered markdown tabs → DOM range walk over `.canvas-md-viewer` with
//     <mark> highlights and scroll-into-view done the scroll-container-safe
//     way (the `scrollIntoView` leak discipline from viewers/markdown.js).
//   · HTML tabs (sandboxed srcdoc iframe) → the same walk runs INSIDE the
//     frame via an injected script + a postMessage channel (same family as
//     the image-preview and local-link bridges in viewers/shared.js).
//
// The find bar chrome follows the floating-glass control family
// (`.canvas-source-toggle`, sapphire.css glass tokens).

import { t } from './i18n.js';

const MARK_CLASS = 'nf-find-hit';
const CURRENT_CLASS = 'nf-find-current';

/** @type {{bar:HTMLElement, input:HTMLInputElement, count:HTMLElement, pane:HTMLElement, mode:'dom'|'frame', scopeSel:string, iframe:HTMLIFrameElement|null, hits:number, index:number, observer:MutationObserver|null, pendingFrame:boolean}|null} */
let active = null;

// ── Boot: global Ctrl/Cmd+F routing + Escape ─────────────────

let initialized = false;
export function initFileSearch() {
  if (initialized) return;
  initialized = true;
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && active) {
      closeFind();
      return;
    }
    if (e.key !== 'Enter' || !active || !active.bar.isConnected) return;
    // Enter / Shift+Enter inside the find input = next / previous hit.
    if (e.target && e.target instanceof Element && e.target.closest('.nf-findbar')) {
      e.preventDefault();
      navigate(e.shiftKey ? -1 : 1);
    }
  });
}

/**
 * Ctrl/Cmd+F entry point. Returns true when the active Canvas tab handled the
 * keystroke (file content search); false = caller falls back to chat search.
 */
export async function findInActiveTab() {
  const { activeCanvasPane } = await import('./canvas.js');
  const pane = activeCanvasPane();
  if (!pane) return false;

  // Monaco (code viewer, or a md/html tab in source mode) — the editor's own
  // find widget. Focus first: the widget binds to the focused editor.
  const handle = /** @type {any} */ (pane)._editorHandle;
  if (handle && handle.editor && typeof handle.editor.getAction === 'function') {
    handle.focus();
    const action = handle.editor.getAction('actions.find');
    if (action) { action.run(); return true; }
  }

  const type = pane.dataset.type || '';
  if (type === 'markdown') { openFindBar(pane, 'dom', '.canvas-md-viewer', null); return true; }
  if (type === 'html' || type === 'largeText') {
    const iframe = pane.querySelector('iframe');
    if (iframe) { openFindBar(pane, 'frame', null, iframe); return true; }
    return false;
  }
  return false;
}

/** Entry point for the viewer find toggle button (viewers/shared.js). */
export function openFindForPane(pane) {
  if (!pane) return;
  const handle = /** @type {any} */ (pane)._editorHandle;
  if (handle && handle.editor && typeof handle.editor.getAction === 'function') {
    handle.focus();
    const action = handle.editor.getAction('actions.find');
    if (action) action.run();
    return;
  }
  const type = pane.dataset.type || '';
  if (type === 'html' || type === 'largeText') {
    const iframe = /** @type {HTMLIFrameElement|null} */ (pane.querySelector('iframe'));
    if (iframe) { openFindBar(pane, 'frame', null, iframe); return; }
  }
  openFindBar(pane, 'dom', '.canvas-md-viewer, .epub-reader-content', null);
}

// ── Find bar ─────────────────────────────────────────────────

function openFindBar(pane, mode, scopeSel, iframe) {
  closeFind();
  if (getComputedStyle(pane).position === 'static') pane.style.position = 'relative';

  const bar = document.createElement('div');
  bar.className = 'nf-findbar';

  const input = document.createElement('input');
  input.type = 'text';
  input.className = 'nf-findbar-input';
  input.placeholder = t('find.placeholder');
  input.spellcheck = false;

  const count = document.createElement('span');
  count.className = 'nf-findbar-count';

  const prevBtn = document.createElement('button');
  prevBtn.className = 'nf-findbar-btn';
  prevBtn.title = t('find.previous');
  prevBtn.textContent = '↑';

  const nextBtn = document.createElement('button');
  nextBtn.className = 'nf-findbar-btn';
  nextBtn.title = t('find.next');
  nextBtn.textContent = '↓';

  const closeBtn = document.createElement('button');
  closeBtn.className = 'nf-findbar-btn nf-findbar-close';
  closeBtn.title = t('find.close');
  closeBtn.textContent = '×';

  bar.append(input, count, prevBtn, nextBtn, closeBtn);
  pane.appendChild(bar);

  active = { bar, input, count, pane, mode, scopeSel, iframe, hits: 0, index: -1, observer: null, pendingFrame: false };

  let debounce = 0;
  input.addEventListener('input', () => {
    clearTimeout(debounce);
    debounce = setTimeout(() => runSearch(input.value), 220);
  });
  prevBtn.addEventListener('click', () => navigate(-1));
  nextBtn.addEventListener('click', () => navigate(1));
  closeBtn.addEventListener('click', () => closeFind());

  // A viewer re-render wipes pane children — the bar dies with it. Clean the
  // module state so a stale `active` can never route messages into nothing.
  active.observer = new MutationObserver(() => {
    if (active && !active.bar.isConnected) closeFind();
  });
  active.observer.observe(pane, { childList: true });

  input.focus();
}

function setCount(text) {
  if (active && active.count) active.count.textContent = text;
}

async function runSearch(q) {
  if (!active) return;
  active.index = -1;
  if (active.mode === 'dom') {
    active.hits = domSearch(active.pane.querySelector(active.scopeSel), q);
    setCount(active.hits ? `${active.index + 1 < 0 ? 0 : 1}/${active.hits}` : '0');
    if (active.hits) navigate(0);
  } else {
    active.pendingFrame = true;
    active.hits = -1;
    setCount('…');
    postToFrame({ _nfFind: { q } });
  }
}

function navigate(dir) {
  if (!active) return;
  if (active.mode === 'dom') {
    if (!active.hits) return;
    // First navigation lands on the first (dir>0) or last (dir<0) hit.
    active.index = active.index < 0
      ? (dir >= 0 ? 0 : active.hits - 1)
      : (active.index + dir + active.hits) % active.hits;
    const marks = active.bar.isConnected
      ? (active.pane.querySelector(active.scopeSel)?.querySelectorAll(`.${MARK_CLASS}`) || [])
      : [];
    marks.forEach((m, i) => m.classList.toggle(CURRENT_CLASS, i === active.index));
    const cur = marks[active.index];
    if (cur) scrollWithin(cur);
    setCount(`${active.index + 1}/${active.hits}`);
  } else {
    postToFrame({ _nfFindNav: { dir } });
  }
}

function closeFind() {
  if (!active) return;
  if (active.mode === 'dom') domClear(active.pane.querySelector(active.scopeSel));
  else postToFrame({ _nfFindClear: {} });
  active.observer?.disconnect();
  active.bar.remove();
  active = null;
}

function postToFrame(msg) {
  if (!active || !active.iframe) return;
  try {
    active.iframe.contentWindow?.postMessage(msg, '*');
  } catch (e) { /* frame torn down mid-search */ }
}

/**
 * Scroll `el` to the top of its scroll container WITHOUT touching any other
 * scrollable ancestor — the `scrollIntoView` leak discipline documented in
 * viewers/markdown.js (scrollHeadingToTop): write exactly one container's
 * scrollTop, clamped.
 */
function scrollWithin(el) {
  const scroller = el.closest('.canvas-md-scroll') || el.closest('.epub-reader-scroll') ||
    (el.parentElement && el.parentElement.closest('[style*="overflow"]'));
  if (!scroller) return;
  const max = Math.max(0, scroller.scrollHeight - scroller.clientHeight);
  const delta = el.getBoundingClientRect().top - scroller.getBoundingClientRect().top;
  scroller.scrollTo({
    top: Math.max(0, Math.min(max, scroller.scrollTop + delta - 48)),
    behavior: 'smooth',
  });
}

// ── DOM walk face (rendered markdown / epub) ─────────────────

function domClear(scope) {
  if (!scope) return;
  scope.querySelectorAll(`mark.${MARK_CLASS}`).forEach((m) => {
    const parent = m.parentNode;
    if (!parent) return;
    parent.replaceChild(document.createTextNode(m.textContent || ''), m);
    parent.normalize();
  });
}

function domSearch(scope, q) {
  if (!scope) return 0;
  domClear(scope);
  const needle = q.trim().toLowerCase();
  if (!needle) { setCount('0'); return 0; }
  const walker = document.createTreeWalker(scope, NodeFilter.SHOW_TEXT, {
    acceptNode: (node) => {
      if (!node.nodeValue || !node.nodeValue.trim()) return NodeFilter.FILTER_REJECT;
      const p = node.parentElement;
      if (!p) return NodeFilter.FILTER_REJECT;
      const tag = p.tagName;
      if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'MARK') return NodeFilter.FILTER_REJECT;
      return p.closest(`mark.${MARK_CLASS}`) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
    },
  });
  const hits = [];
  // Collect FIRST (wrapping invalidates the walker's position).
  const nodes = [];
  while (walker.nextNode()) nodes.push(walker.currentNode);
  for (const textNode of nodes) {
    const value = textNode.nodeValue || '';
    const lower = value.toLowerCase();
    let from = 0;
    let at = lower.indexOf(needle, from);
    while (at !== -1) {
      hits.push({ node: textNode, start: at, end: at + needle.length });
      from = at + needle.length;
      at = lower.indexOf(needle, from);
    }
  }
  // Wrap from the last hit backwards so earlier offsets stay valid.
  for (let i = hits.length - 1; i >= 0; i--) {
    const { node, start, end } = hits[i];
    const parent = node.parentNode;
    if (!parent) continue;
    const range = document.createRange();
    range.setStart(node, start);
    range.setEnd(node, end);
    const mark = document.createElement('mark');
    mark.className = MARK_CLASS;
    try {
      range.surroundContents(mark);
    } catch (e) {
      // Split across entity boundaries — fall back to manual extraction.
      const textNode = /** @type {Text} */ (node);
      const mid = textNode.splitText(start);
      mid.splitText(end - start);
      parent.replaceChild(mark, mid);
      mark.appendChild(mid);
    }
  }
  return hits.length;
}

// ── iframe face (sandboxed HTML viewer) ──────────────────────

// Results channel: the frame reports { _nfFindResult: { count, index } } —
// verify the sender is a frame this app renders (shared.js isRenderedFrame
// discipline), otherwise any page could spoof find state.
window.addEventListener('message', (e) => {
  const d = e.data;
  if (!d || !d._nfFindResult || !active || active.mode !== 'frame') return;
  const frames = /** @type {NodeListOf<HTMLIFrameElement>} */ (document.querySelectorAll('iframe[data-nf-canvas-html], iframe.html-card-iframe'));
  let ours = false;
  for (const f of frames) if (f.contentWindow === e.source) { ours = true; break; }
  if (!ours) return;
  active.hits = d._nfFindResult.count || 0;
  active.index = d._nfFindResult.index ?? -1;
  setCount(active.hits ? `${active.index + 1}/${active.hits}` : '0');
});

/**
 * Frame-side find script, injected into the HTML viewer's srcdoc (same
 * injection family as localLinkNavScript / imgClickScript in viewers/shared.js).
 * Template-literal rules (WebJsModuleSyntaxSpec): no backticks, no `${`,
 * regex backslashes doubled.
 */
export function frameFindScript() {
  return `<script>
(function(){
  var MARK='nf-find-hit', CUR='nf-find-current';
  function scope(){ return document.body || document.documentElement; }
  function clear(){
    var marks=document.querySelectorAll('mark.'+MARK);
    for(var i=0;i<marks.length;i++){
      var m=marks[i], p=m.parentNode;
      if(!p) continue;
      p.replaceChild(document.createTextNode(m.textContent||''), m);
      p.normalize();
    }
  }
  function scrollTopOf(el){
    var max=Math.max(0,document.documentElement.scrollHeight-window.innerHeight);
    var delta=el.getBoundingClientRect().top;
    window.scrollTo({top:Math.max(0,Math.min(max,window.scrollY+delta-48)),behavior:'smooth'});
  }
  function search(q){
    clear();
    var needle=String(q||'').trim().toLowerCase();
    if(!needle){ report(0,-1); return; }
    var walker=document.createTreeWalker(scope(),NodeFilter.SHOW_TEXT,null);
    var nodes=[];
    while(walker.nextNode()){
      var p=walker.currentNode.parentElement;
      if(!p) continue;
      var tag=p.tagName;
      if(tag==='SCRIPT'||tag==='STYLE'||tag==='MARK') continue;
      nodes.push(walker.currentNode);
    }
    var hits=[];
    for(var i=0;i<nodes.length;i++){
      var v=nodes[i].nodeValue||'', lower=v.toLowerCase(), from=0, at=lower.indexOf(needle,from);
      while(at!==-1){ hits.push({node:nodes[i],start:at,end:at+needle.length}); from=at+needle.length; at=lower.indexOf(needle,from); }
    }
    for(var j=hits.length-1;j>=0;j--){
      var h=hits[j], parent=h.node.parentNode;
      if(!parent) continue;
      try{
        var range=document.createRange();
        range.setStart(h.node,h.start); range.setEnd(h.node,h.end);
        var mark=document.createElement('mark'); mark.className=MARK;
        range.surroundContents(mark);
      }catch(err){}
    }
    report(hits.length, hits.length?0:-1);
    if(hits.length){
      var first=document.querySelector('mark.'+MARK);
      if(first){ first.classList.add(CUR); scrollTopOf(first); }
    }
  }
  function nav(dir){
    var marks=document.querySelectorAll('mark.'+MARK);
    if(!marks.length){ report(0,-1); return; }
    var cur=-1;
    for(var i=0;i<marks.length;i++){ if(marks[i].classList.contains(CUR)){ cur=i; break; } }
    var next=((cur<0?0:cur+dir)+marks.length)%marks.length;
    for(var k=0;k<marks.length;k++){ marks[k].classList.toggle(CUR,k===next); }
    scrollTopOf(marks[next]);
    report(marks.length,next);
  }
  function report(count,index){
    parent.postMessage({_nfFindResult:{count:count,index:index}},'*');
  }
  document.addEventListener('keydown',function(e){
    if(e.key==='Escape'){ clear(); parent.postMessage({_nfFindResult:{count:0,index:-1}},'*'); }
  });
  window.addEventListener('message',function(e){
    var d=e.data;
    if(!d) return;
    if(d._nfFind) search(d._nfFind.q);
    else if(d._nfFindNav) nav(d._nfFindNav.dir?d._nfFindNav.dir:1);
    else if(d._nfFindClear) clear();
  });
})();
<\/script>`;
}

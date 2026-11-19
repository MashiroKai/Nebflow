// termCite.js — ```human 卡内的词条短形引用接 hover 气泡（human-face 批
// 2026-10-30；设计稿 §5.2 二期：人面子保留术语写 [[词条名]]，hover 出释义）。
//
// 共享渲染管线（utils.js）只把卡内 `[[名]]` 当字面文本留下；本模块把它
// 变成可 hover 的词条引用并挂 termTip.js 的气泡（API 兜底腿——聊天气泡、
// flow/report 卡片等面没有 wiki graph 载荷，注册表单查即出泡）。机制零新造：
// 气泡本体 = 词条引用批第二批 F3/F4 的既有实现（原 viewers/markdown.js，
// 现共享件 termTip.js）。
//
// 与 docs viewer 面的分工：viewers/markdown.js renderWikilinks 只做全消解
// （graph 主腿 + 词条第三腿），且只挂 md/html 文档面；本模块只扫
// .nf-human-card、只认无 `#` 的短形（`[[doc#^term-x]]` 指名形态与
// `![[embed]]` 保持字面，交 docs viewer 的消解 pass）。两面都跳过 <a> 父
// 节点下的文本，因此任意执行顺序都收敛、不双处理。
//
// 全局自动接线照 mermaidRenderer.js 的 armMermaidAutoHydrate 先例：一个挂
// document.body 的 MutationObserver，任何面把含 .nf-human-card 的 HTML 挂
// 进 DOM 即自动 linkify（data-term-cites 幂等守卫）。

import { attachTermFace, hideTermTip, termTapExpand } from './termTip.js';

const CITE_RE = /\[\[([^\[\]]+?)\]\]/g;

/** Linkify `[[term]]` / `[[term|display]]` short forms inside every human card
 *  under `root` that has not been processed yet. */
export function hydrateTermCitesIn(root) {
  const scope = root || document;
  const cards = scope.querySelectorAll('.nf-human-card:not([data-term-cites])');
  for (const card of cards) {
    card.dataset.termCites = '1';
    const walker = document.createTreeWalker(card, NodeFilter.SHOW_TEXT, {
      acceptNode: (node) => {
        const p = node.parentElement;
        if (!p) return NodeFilter.FILTER_REJECT;
        const tag = p.tagName;
        if (tag === 'CODE' || tag === 'PRE' || tag === 'SCRIPT' || tag === 'STYLE' || tag === 'A') return NodeFilter.FILTER_REJECT;
        if (p.closest('pre, code, a.nf-wikilink')) return NodeFilter.FILTER_REJECT;
        return node.nodeValue && node.nodeValue.indexOf('[[') !== -1
          ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
      },
    });
    const textNodes = [];
    while (walker.nextNode()) textNodes.push(walker.currentNode);
    for (const node of textNodes) {
      const value = node.nodeValue || '';
      CITE_RE.lastIndex = 0;
      if (!CITE_RE.test(value)) continue;
      CITE_RE.lastIndex = 0;
      const frag = document.createDocumentFragment();
      let lastIdx = 0;
      let m;
      while ((m = CITE_RE.exec(value)) !== null) {
        if (m.index > lastIdx) frag.appendChild(document.createTextNode(value.slice(lastIdx, m.index)));
        const inner = m[1];
        const pipe = inner.indexOf('|');
        const body = pipe >= 0 ? inner.slice(0, pipe) : inner;
        const hash = body.indexOf('#');
        const target = (hash >= 0 ? body.slice(0, hash) : body).trim();
        const alias = pipe >= 0 ? inner.slice(pipe + 1).trim() : '';
        if (!target || hash >= 0) {
          // 指名/锚形态或空目标：保持字面（docs viewer 的消解 pass 负责）。
          frag.appendChild(document.createTextNode(m[0]));
        } else {
          const a = document.createElement('a');
          a.className = 'nf-wikilink';
          a.href = '#';
          a.textContent = alias || target;
          // 无图谱面不做深链导航：点击只收泡；触屏首击开泡（termTapExpand）。
          a.addEventListener('click', (e) => {
            e.preventDefault();
            if (termTapExpand(a, null)) return;
            hideTermTip();
          });
          attachTermFace(a, null, { fallbackName: target });
          frag.appendChild(a);
        }
        lastIdx = m.index + m[0].length;
      }
      if (value.length > lastIdx) frag.appendChild(document.createTextNode(value.slice(lastIdx)));
      if (node.parentNode) node.parentNode.replaceChild(frag, node);
    }
  }
}

let _citeArmed = false;

/** Global auto-hydration: one body-level MutationObserver (mermaidRenderer
 *  precedent) — any face mounting .nf-human-card HTML gets its term cites
 *  linkified without per-face wiring. */
export function armTermCiteAutoHydrate() {
  if (_citeArmed) return;
  _citeArmed = true;
  const scan = () => hydrateTermCitesIn(document.body);
  const obs = new MutationObserver((muts) => {
    for (const mu of muts) {
      if (mu.addedNodes && mu.addedNodes.length) { scan(); return; }
    }
  });
  obs.observe(document.body, { childList: true, subtree: true });
  scan();
}

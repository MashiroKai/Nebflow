// chat.js 拆分(FE组件化批次三 2026-09-27):折叠助手(bindCollapsibleToggle + chevronSvg) 可复用模块(行为保持)。

/** Shared keyboard-activatable toggle for thinking labels and the #346 turn
 *  summary bar (spec §8: both implementations share one helper). */
export function bindCollapsibleToggle(el, getContent, onToggle) {
  el.setAttribute('role', 'button');
  el.setAttribute('tabindex', '0');
  el.setAttribute('aria-expanded', el.classList.contains('expanded') ? 'true' : 'false');
  const toggle = () => {
    const content = getContent();
    if (!content) return;
    const visible = content.style.display !== 'none';
    content.style.display = visible ? 'none' : '';
    el.classList.toggle('expanded', !visible);
    el.setAttribute('aria-expanded', String(!visible));
    onToggle?.(!visible);
  };
  el.onclick = toggle;
  el.onkeydown = (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); }
  };
}

/** 12px inline chevron (currentColor) used by the #346 turn summary bar —
 *  no icon library, muted color follows the text. (2026-08-24 ruling: thinking
 *  labels and injected-bubble headers no longer use it.) */
export function chevronSvg() {
  const span = document.createElement('span');
  span.className = 'nf-chevron';
  span.setAttribute('aria-hidden', 'true');
  span.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>';
  return span;
}

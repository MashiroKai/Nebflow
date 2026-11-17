// Rendered row-center audit. A row is a horizontal flex row or a single-track
// grid, with at least two visible element children sharing a vertical interval.
// Centers are compared pairwise in CSS pixels; >1px fails. One CSS pixel leaves
// room for subpixel layout/rounding while still detecting visible displacement.
// Scope is the rendered DOM supplied by the caller, not unvisited UI states.
export function scanRowCenters(root = document, tolerance = 1) {
  const selector = (el) => {
    const parts = [];
    while (el && el.nodeType === 1) {
      let part = el.localName;
      if (el.id) { parts.unshift(`${part}#${CSS.escape(el.id)}`); break; }
      if (el.classList.length) part += [...el.classList].map(c => `.${CSS.escape(c)}`).join('');
      if (el.parentElement) part += `:nth-child(${[...el.parentElement.children].indexOf(el) + 1})`;
      parts.unshift(part); el = el.parentElement;
    }
    return parts.join(' > ');
  };
  const violations = [];
  for (const el of root.querySelectorAll('*')) {
    const style = getComputedStyle(el);
    const flex = (style.display === 'flex' || style.display === 'inline-flex') && style.flexDirection === 'row';
    const grid = (style.display === 'grid' || style.display === 'inline-grid') && style.gridTemplateRows.split(' ').length === 1;
    if (!flex && !grid) continue;
    const children = [...el.children].filter(child => {
      const s = getComputedStyle(child), r = child.getBoundingClientRect();
      return s.display !== 'none' && s.visibility === 'visible' && Number(s.opacity) > 0 && !['absolute', 'fixed'].includes(s.position) && r.width > 0 && r.height > 0;
    }).map(child => ({ selector: selector(child), rect: child.getBoundingClientRect() }));
    if (children.length < 2 || Math.max(...children.map(c => c.rect.top)) >= Math.min(...children.map(c => c.rect.bottom))) continue;
    const centers = children.map(c => ({ selector: c.selector, center: (c.rect.top + c.rect.bottom) / 2 }));
    const delta = Math.max(...centers.map(c => c.center)) - Math.min(...centers.map(c => c.center));
    if (delta > tolerance) violations.push({ container: selector(el), delta, children: centers });
  }
  return violations;
}

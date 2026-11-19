// Audit rendered horizontal rows: row-direction flex, single-track grid, and
// inline text+element rows (the latter catches legacy bare text). At least two
// visible in-flow items must share a vertical interval. Range.getClientRects()
// measures each bare text fragment; element border boxes use getBoundingClientRect().
// Pairwise center spread >1 CSS px fails: this covers observed subpixel rounding
// (0.016px on the fixed row), without masking a 4px displacement.
// Explicit exemption: body is the page shell, whose children are full panels,
// not items in a content row. Out-of-flow children are not flex/grid items.
// This only audits the DOM states rendered by the caller, not the whole product.
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
  const violations = [], exemptions = [];
  for (const el of root.querySelectorAll('*')) {
    const style = getComputedStyle(el);
    const flex = (style.display === 'flex' || style.display === 'inline-flex') && style.flexDirection === 'row';
    const grid = (style.display === 'grid' || style.display === 'inline-grid') && style.gridTemplateRows.trim().split(/\s+/).length === 1;
    const inlineText = [...el.childNodes].some(n => n.nodeType === Node.TEXT_NODE && n.textContent.trim()) && [...el.children].some(c => ['inline', 'inline-block', 'inline-flex'].includes(getComputedStyle(c).display));
    if (!flex && !grid && !inlineText) continue;
    const children = [];
    for (const node of el.childNodes) {
      if (node.nodeType === Node.TEXT_NODE && node.textContent.trim()) {
        const range = document.createRange(); range.selectNodeContents(node);
        for (const rect of range.getClientRects()) if (rect.width > 0 && rect.height > 0) children.push({ selector: `${selector(el)} > #text`, rect });
      } else if (node.nodeType === Node.ELEMENT_NODE) {
        const s = getComputedStyle(node), rect = node.getBoundingClientRect();
        if (s.display !== 'none' && s.visibility === 'visible' && Number(s.opacity) > 0 && !['absolute', 'fixed'].includes(s.position) && rect.width > 0 && rect.height > 0) children.push({ selector: selector(node), rect });
      }
    }
    if (children.length < 2 || Math.max(...children.map(c => c.rect.top)) >= Math.min(...children.map(c => c.rect.bottom))) continue;
    const row = el.getBoundingClientRect(), rowCenter = (row.top + row.bottom) / 2;
    const centers = children.map(c => ({ selector: c.selector, center: (c.rect.top + c.rect.bottom) / 2, fromRow: (c.rect.top + c.rect.bottom) / 2 - rowCenter }));
    const delta = Math.max(...centers.map(c => c.center)) - Math.min(...centers.map(c => c.center));
    if (delta <= tolerance) continue;
    const result = { container: selector(el), delta, rowCenter, children: centers };
    if (el.matches('body')) exemptions.push({ ...result, reason: 'body is the page-level panel layout, not a content row' });
    else violations.push(result);
  }
  return { violations, exemptions };
}

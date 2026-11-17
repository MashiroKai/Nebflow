// Shared lifecycle for a single-choice popup whose items are native buttons.
export function openDropdownMenu(button, menu) {
  document.body.append(menu);
  const rect = button.getBoundingClientRect();
  menu.style.left = `${Math.max(8, Math.min(rect.right - menu.offsetWidth, innerWidth - menu.offsetWidth - 8))}px`;
  menu.style.top = `${Math.max(8, Math.min(rect.bottom + 4, innerHeight - menu.offsetHeight - 8))}px`;
  button.setAttribute('aria-expanded', 'true');
  let closed = false;
  const close = () => {
    if (closed) return;
    closed = true;
    menu.remove();
    button.setAttribute('aria-expanded', 'false');
    document.removeEventListener('pointerdown', outside);
    document.removeEventListener('keydown', key);
  };
  const outside = event => {
    if (!menu.contains(event.target) && !button.contains(event.target)) close();
  };
  const key = event => {
    if (event.key === 'Escape') { event.preventDefault(); close(); button.focus(); }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      const items = [...menu.querySelectorAll('button:not(:disabled)')];
      if (!items.length) return;
      event.preventDefault();
      const index = items.indexOf(document.activeElement);
      const next = index < 0 ? (event.key === 'ArrowDown' ? 0 : items.length - 1)
        : (index + (event.key === 'ArrowDown' ? 1 : items.length - 1)) % items.length;
      items[next].focus();
    }
  };
  document.addEventListener('pointerdown', outside);
  document.addEventListener('keydown', key);
  menu.querySelector('button:not(:disabled)')?.focus();
  return close;
}

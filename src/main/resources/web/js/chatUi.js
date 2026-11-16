// Shared chat UI structural primitives. Surface-specific geometry and behavior stay
// with their owners until the frozen visual differences have been reviewed.
export function chatFooter(extraClass = '') {
  const el = document.createElement('div');
  el.className = extraClass ? `${extraClass} duration-badge` : 'duration-badge';
  return el;
}

export function chatFooterDivider(extraClass = '') {
  const el = document.createElement('span');
  el.className = extraClass ? `${extraClass} duration-badge-divider` : 'duration-badge-divider';
  return el;
}

export function chatFooterTime(extraClass = '', text = '') {
  const el = document.createElement('span');
  el.className = extraClass ? `${extraClass} duration-badge-time` : 'duration-badge-time';
  el.textContent = text;
  return el;
}

// Standard main-chat bubbles keep their original classes and order. Unlike the
// message-window side factory, this does not add a side class to existing rows.
export function mainBubbleClass(role, extra = '') {
  return `bubble ${role}${extra ? ' ' + extra : ''}`;
}

// D1: Ask's glass bubble is intentionally distinct from the standard bubble.
export function askBubbleClass(role) {
  return 'ask-bub nf-glass ' + (role === 'user' ? 'user' : 'ai');
}

// D2: shell geometry stays surface-owned; the material is the common token class.
export function chatComposerClass(surface) {
  if (surface === 'ask') return 'ask-input nf-glass';
  if (surface === 'messages') return 'fm-input-bar nf-glass';
  throw new Error('Unknown chat composer surface');
}

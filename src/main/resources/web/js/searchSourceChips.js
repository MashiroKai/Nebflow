// searchSourceChips.js — source-filter row for the chat search media grid.
//
// Lifecycle / method contract:
//   <nf-search-source-chips> owns the light-DOM row, chip nodes and listeners.
//   mount(container, options) connects the element and provides its public API.
//   sync({visible}) refreshes labels, pressed/active state and visibility after
//     tab or locale changes; it does not own search state or filtering.
//   unmount() disconnects and removes the element; repeated calls are safe.
//
// Ownership boundary: chatSearch owns modal lifetime, query/source state,
// results, calendar, focus/navigation and filtering. This component owns only
// the source-chip row DOM and forwards source selection through onChange.

import { define } from './component.js';
import { h, frag } from './dom.js';
import { t } from './i18n.js';

const SOURCES = ['', 'user', 'agent'];
const OPTIONS = new WeakMap();

/** @type {string[]} */
export const INTERNAL_CLASSES = ['search-source-chip'];

/** @typedef {{getSource: () => string, onChange: (source: string) => void}} SourceChipOptions */

define({
  tag: 'nf-search-source-chips',
  template: () => h('div', { id: 'search-source-chips', role: 'group', hidden: true },
    frag(...SOURCES.map((source) => h('button', {
      type: 'button',
      class: 'search-source-chip',
      'data-source': source,
    })))),
  state: () => ({ options: null, buttons: [] }),
  mount(el, ctx) {
    ctx.state.options = OPTIONS.get(el) || null;
    OPTIONS.delete(el);
    const component = /** @type {HTMLElement & {sync?: (opts: {visible: boolean}) => void, unmount?: () => void}} */ (el);
    const buttons = Array.from(el.querySelectorAll('button'));
    ctx.state.buttons = buttons;
    for (const [i, button] of buttons.entries()) {
      ctx.on(button, 'click', () => ctx.state.options?.onChange(SOURCES[i]));
    }
    component.sync = ({ visible }) => {
      el.querySelector('div').hidden = !visible;
      for (const [i, button] of buttons.entries()) {
        const source = SOURCES[i];
        const active = source === ctx.state.options?.getSource();
        button.classList.toggle('active', active);
        button.setAttribute('aria-pressed', active ? 'true' : 'false');
        button.textContent = t(source === 'user' ? 'search.sourceUser'
          : source === 'agent' ? 'search.sourceAgent' : 'search.sourceAll');
      }
    };
    component.unmount = () => component.remove();
  },
  unmount(el) {
    const component = /** @type {HTMLElement & {sync?: (opts: {visible: boolean}) => void, unmount?: () => void}} */ (el);
    delete component.sync;
    delete component.unmount;
  },
});

/**
 * Connect one source-filter component. The element exposes sync/unmount only.
 * @param {HTMLElement} container
 * @param {SourceChipOptions} options
 * @returns {{sync: (opts: {visible: boolean}) => void, unmount: () => void}}
 */
export function mountSearchSourceChips(container, options) {
  const element = /** @type {HTMLElement & {sync?: (opts: {visible: boolean}) => void, unmount?: () => void}} */ (
    document.createElement('nf-search-source-chips'));
  OPTIONS.set(element, options);
  element.style.display = 'contents';
  container.appendChild(element);
  return {
    sync: (opts) => element.sync?.(opts),
    unmount: () => element.unmount?.(),
  };
}

// depVisibility.js — Conditional-display widget shared by the AskUser card
// (chat.js showOptions) and the /model panel (modelPanel.js).
//
// Extracted from showOptions' shouldShow/updateVisibility pair: the trio of
// "conditional display + hide-reset + reveal animation" is one mechanism, and
// both consumers now share it. The contract is shape-identical on both sides —
// block visibility = f(mode, fork state), just like question visibility =
// f(previous answer). The OWNER owns its state and the visibility predicate;
// this widget owns the DOM mechanics: inline display toggling, an onHide hook
// so the owner can reset a hidden item's state, and the reveal animation
// (the shared .ob-q-reveal keyframes in chat.css, auto-disabled under
// prefers-reduced-motion).

/**
 * Create a conditional-display group over an ordered list of elements.
 * @param {object} opts
 * @param {(i: number) => boolean} opts.isVisible visibility predicate for element i
 *   (pure read of owner state — evaluated fresh on every update()).
 * @param {(i: number, el: HTMLElement) => void} [opts.onHide] called whenever
 *   element i is (or stays) hidden — the owner's reset hook. Called on every
 *   update() pass while hidden, so the callback must be idempotent.
 * @param {string} [opts.animClass] class restarted on reveal
 *   (remove → forced reflow → add). Pass '' to disable.
 * @returns {{ register: (el: HTMLElement) => void, update: () => void }}
 */
export function createDepVisibility({ isVisible, onHide, animClass = 'ob-q-reveal' }) {
  /** @type {HTMLElement[]} */
  const els = [];

  /** Register an element (in visibility order — i follows registration order). */
  function register(el) {
    els.push(el);
  }

  /** Re-evaluate every registered element against the current owner state. */
  function update() {
    els.forEach((el, i) => {
      const visible = isVisible(i);
      const wasHidden = el.style.display === 'none';
      el.style.display = visible ? '' : 'none';
      // Reveal animates in; hide is instant. The first update() typically runs
      // before the container enters the DOM, so the animation cannot fire on
      // initial render (wasHidden is false there).
      if (visible && wasHidden && animClass) {
        el.classList.remove(animClass);
        void el.offsetWidth; // reflow to restart the animation
        el.classList.add(animClass);
      }
      if (!visible) onHide?.(i, el);
    });
  }

  return { register, update };
}

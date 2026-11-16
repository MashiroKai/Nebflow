// Draft primitive for whole-page recycling. This is intentionally not wired into
// chat until page data can be reconstructed after the detached-page cap is hit.
// Callers must pass margin-free page wrappers and call restoreVisible on scroll.
export function createHistoryPageRecycler(chat, { maxDetachedPages = 40 } = {}) {
  const detached = new Map();

  function collect() {
    let count = 0;
    const bottom = chat.getBoundingClientRect().bottom + chat.clientHeight;
    for (const page of [...chat.children]) {
      if (detached.size >= maxDetachedPages) break;
      if (!page.classList.contains('history-page')) continue;
      const rect = page.getBoundingClientRect();
      if (rect.top <= bottom || rect.height <= 0) continue;
      const placeholder = document.createElement('div');
      placeholder.dataset.historyPlaceholder = '';
      placeholder.style.height = `${rect.height}px`;
      placeholder.style.pointerEvents = 'none';
      page.replaceWith(placeholder);
      detached.set(placeholder, page);
      count++;
    }
    return count;
  }

  function restoreVisible() {
    let count = 0;
    const viewport = chat.getBoundingClientRect();
    for (const [placeholder, page] of detached) {
      if (!placeholder.isConnected || !chat.contains(placeholder)) {
        detached.delete(placeholder);
        continue;
      }
      const rect = placeholder.getBoundingClientRect();
      if (rect.bottom < viewport.top - chat.clientHeight ||
          rect.top > viewport.bottom + chat.clientHeight) continue;
      placeholder.replaceWith(page);
      detached.delete(placeholder);
      count++;
    }
    return count;
  }

  function dispose() {
    for (const [placeholder, page] of detached) {
      if (chat.contains(placeholder)) placeholder.replaceWith(page);
    }
    detached.clear();
  }

  return { collect, restoreVisible, dispose };
}

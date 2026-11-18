// cmLoader.js — 单飞装载随包分发的 CodeMirror 差异底座（js/diffView 共享组件
// 的底层依赖面）。
//
// 契约：运行期从**同源**路径 /vendor/codemirror-merge/codemirror-merge.min.js
// 注入一次脚本（无 CDN、无运行期 npm；见 package.json.vendorAssets 台账），该
// 脚本在 globalThis.NfCodeMirror 上挂命名导出。装载失败进 rejected 缓存（重试
// 时重注入，不静默吞）。

const VENDOR_SRC = 'vendor/codemirror-merge/codemirror-merge.min.js';

/** @type {Promise<any> | null} */
let pending = null;
/** @type {any} */ let mod = null;

/** Base URL of the web root (index.html lives at its root; module URL depth is
 *  stable because this module is loaded from /js/diffView/). */
function vendorUrl() {
  try {
    return new URL('../../' + VENDOR_SRC, import.meta.url).href;
  } catch {
    return '/' + VENDOR_SRC;
  }
}

/** Load the vendored CodeMirror bundle once (single-flight). Resolves with the
 *  `globalThis.NfCodeMirror` namespace object. */
export function loadCodeMirror() {
  if (mod) return Promise.resolve(mod);
  if (pending) return pending;
  pending = new Promise((resolve, reject) => {
    const done = () => {
      const g = /** @type {any} */ (globalThis).NfCodeMirror;
      if (g) { mod = g; resolve(g); }
      else reject(new Error('codemirror-merge vendor bundle loaded but NfCodeMirror is absent'));
    };
    const s = document.createElement('script');
    s.src = vendorUrl();
    s.async = true;
    s.dataset.nfCodemirrorMerge = '1';
    s.onload = done;
    s.onerror = () => reject(new Error('failed to load ' + VENDOR_SRC));
    document.head.appendChild(s);
  });
  // 失败不粘死：清空 pending 以便下次重试。
  pending.catch(() => { pending = null; });
  return pending;
}

/** Test seam: current global namespace (null before first load). */
export function loadedCodeMirror() { return mod; }

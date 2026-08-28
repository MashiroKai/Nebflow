// vendor-globals.d.ts — P2-3 core-contract typings for the vendor libraries
// that index.html loads via plain <script> tags (no ES modules). These
// declarations let checkJs type-check utils.js (and any other file touching
// the vendors) without importing anything at runtime.
//
// Keep these minimal and permissive — they describe the call surface we
// actually use, not the full vendor APIs. Widen to `any` rather than
// fighting upstream type shapes.

/** lottie-web: spinner animations in tool-call rows */
declare const lottie: {
  loadAnimation(opts: Record<string, unknown>): {
    play(): void;
    stop(): void;
    destroy(): void;
  };
};

/** marked: markdown → HTML */
declare const marked: {
  setOptions(opts: Record<string, unknown>): void;
  parse(src: string, opts?: Record<string, unknown>): string;
};

/** KaTeX: TeX → HTML */
declare const katex: {
  renderToString(tex: string, opts?: Record<string, unknown>): string;
};

/** lucide: static icon set; icons map kebab→[tag, attrs, children] tuples */
declare const lucide: {
  icons: Record<string, [string, Record<string, any>, any[]]>;
  createIcons(): void;
  createElement(icon: [string, Record<string, any>, any[]]): SVGElement;
};

/** highlight.js: syntax highlighting */
declare const hljs: {
  getLanguage(name: string): unknown;
  highlight(code: string, opts: { language: string; ignoreIllegals?: boolean }): { value: string; language?: string };
  highlightAuto(code: string): { value: string; language?: string };
};

/** Global click handler for code-block copy buttons (inline onclick) */
interface Window {
  copyCode(btn: HTMLElement): void;
}

/** Global toast helper (set by main.js) */
interface Window {
  __showToast?: (message: string, kind?: string) => void;
}

/** Global confirm dialog (set by modal.js initModals) */
interface Window {
  __showConfirm?: (title: string, message: string, onConfirm: () => void) => void;
}

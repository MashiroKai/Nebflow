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

/** qrcode-generator (web/vendor/qrcode.js, MIT): QR encoder for the Feishu
 *  scan-bind overlay in socialPanel.js. Call surface is exactly what the
 *  overlay uses (auto type number + the canvas draw pair). */
declare const qrcode: (
  typeNumber: 0 | 1 | 2 | 3 | 4,
  errorCorrectionLevel: 'L' | 'M' | 'Q' | 'H'
) => {
  addData(data: string): void;
  make(): void;
  getModuleCount(): number;
  isDark(row: number, col: number): boolean;
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

/** Global confirm dialog (set by modal.js initModals). Signature mirrors
 *  modal.js showConfirm — including the optional opts (tone: 'danger'
 *  default | 'neutral'; confirmLabel: per-call confirm-button word, the social
 *  unbind flow's 「解除绑定」). */
interface Window {
  __showConfirm?: (
    title: string,
    message: string,
    onConfirm: () => void,
    opts?: { tone?: 'danger' | 'neutral', confirmLabel?: string }
  ) => void;
}

/** Thinking-placeholder timer stopper (set by main.js, read by chat.js). */
interface Window {
  __stopThinkingTimer?: () => void;
}

/** 感知域 ask 卡增强（mail-perception-impl 批；由 perception.js 注册，
 *  chat.js renderAskUser 可选调用——缺席 = 卡面行为与改前逐字节相同）。 */
interface Window {
  __perceptionEnhanceAskCard?: (
    bubble: HTMLElement,
    items: Array<Record<string, unknown>>,
    requestId: string,
    targetSid: string
  ) => void;
}

/** NebLink settings-panel refresh interval handle (set/cleared by sidebar.js). */
interface Window {
  _neblinkRefreshTimer?: number | null;
}

/** 渲染遮蔽门诊断/spec 读数缝（perf 台账 #922；chat/renderGate.js 注册：
 *  begin/end 持门、busy 读数、depth 嵌套深度——ws.js 心跳遮蔽与 spec 用）。 */
interface Window {
  __nfRenderGate?: {
    begin(): void;
    end(): void;
    busy(): boolean;
    depth(): number;
  };
}

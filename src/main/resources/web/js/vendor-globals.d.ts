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
    opts?: {
      tone?: 'danger' | 'neutral',
      confirmLabel?: string,
      /** Second (alternate) leg — plugins.js uninstall offers 「保留目录」 next to the
       *  destructive default (uninstallDeleteLabel). */
      alt?: { label: string, onAlt: () => void }
    }
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

/** 热更新地基诊断/spec 缝（台账 #919；hotStamp.js 注册：stamp 轮询自愈的
 *  基线/远端读数、忙挂起态、checkNow 判路缝——tests/hot-stamp-reload 用）。 */
interface Window {
  __nfHotStamp?: {
    setBoot(stamp: string): void;
    checkNow(): Promise<string>;
    state(): {
      bootStamp: string | null;
      lastRemote: string | null;
      pendingReload: boolean;
      pollMs: number;
      guardMs: number;
      busy: boolean;
    };
  };
}

/** 入场加载进度门（index.html 行内门脚本注册：`window.__nfEntry = { mark }`；
 *  ws.js 回报 host 阶段、main.js 回报 data/ui 阶段。缺席 = 门脚本未装，调用点
 *  本就做 typeof 防御，声明为可选即为真实契约）。 */
interface Window {
  __nfEntry?: {
    mark(id: string, state: string): void;
  };
}

/** 会话/文件夹删除确认弹窗的跨模块入口（modal.js initModals 末尾挂载，
 *  sidebar.js 右键菜单消费；缺席 = 弹窗未初始化，消费点已做 in 防御）。 */
interface Window {
  __showDeleteModal?: (sessionId: string, sessionName: string) => void;
  __showDeleteFolderModal?: (folderId: string, folderName: string) => void;
}

/** 本页被 serve 时注入的前端资源指纹（StaticRoutes.scala:306 注入
 *  `<script>window.__WEB_FINGERPRINT__=...</script>`；main.js 拿它与 serverConfig
 *  帧的 webFingerprint 比对以识别旧版标签页。缺席 = 旧引擎/无注入脸）。 */
interface Window {
  __WEB_FINGERPRINT__?: string;
}

/** 本页被 serve 时注入的服务端真实家目录绝对路径（StaticRoutes.scala 的
 *  homeScriptTag 注入 `<script>window.NF_HOME="..."</script>`，与 __BRAND__ /
 *  __WEB_FINGERPRINT__ 同点同载体）。真实存在的契约，非死代码开脱：消费面
 *  delegatePanoramaView.js 的 shortPath() 用它把工作区路径缩成 `~`。
 *  形态：该机器原生分隔符（Windows 为反斜杠）、**无尾分隔符**（服务端已剥）；
 *  缺席（旧引擎 / 静态测试树 / user.home 不可得）= 不注入，消费点原样回显。 */
interface Window {
  NF_HOME?: string;
}

/** 顶栏自适应断点的 settled 读数（main.js 写在每次布局结算后；生产代码不读，
 *  只有 scripts/e2e-header-collision.cjs 的 dbg 通道与 spec 读它）。 */
interface Window {
  __headerLayout?: {
    cut: number;
    total: number;
    hiddenIds: Array<string | undefined>;
    free: number;
    need: number;
    safe: number;
  };
}

/** explorer.js 的 Esc 绑定幂等闩（模块内 latch：只绑一次 document keydown）。 */
interface Window {
  __explorerEscBound?: boolean;
}

/** 玻璃滚动降级的 spec/诊断通道（scrollUnderGlass.js 安装：
 *  degrade(on) 强制单向降级、state() 读四项判定；tests/uifix-glass* 使用）。 */
interface Window {
  __scrollUnderGlass?: {
    degrade(on: boolean): void;
    state(): {
      degraded: boolean;
      noSupport: boolean;
      supported: boolean;
      probing: boolean;
    };
  };
}

/** 下游反适配面（R1 门面化 2026-11-02；由 js/nfAdaptApi.js 在模块体末尾注册，
 *  消费方 = **跨仓** triple-star 镜像内的 `ui/js/nf-adapt.js`）。存在理由：镜像
 *  装的 UI 是产物树 build/web-dist（assets/*.js，**没有 js/ 目录**），下游那份
 *  脚本跑在 bundle 之外，任何 `import('./ws.js')` 都不解析 —— 它只能从这里取。
 *
 *  契约纪律：字段名即契约。改名会静默打断镜像侧（跨仓 grep 不到），必须先给
 *  下游留降级期。懒加载两族是函数（保持 import() 的 chunk 边界）。 */
interface NfAdaptApi {
  readonly ws: {
    readonly sendWs: (msg: unknown) => void;
    readonly onMessage: (type: string, handler: (msg: any) => void) => () => void;
  };
  readonly state: Record<string, any>;
  readonly sidebar: {
    readonly renderSessionSidebar: (sessionData: any, activeId: string | null) => void;
    readonly saveNewProvider: (name: string, data: any) => void;
  };
  readonly modelPanel: {
    readonly getAllModelRefs: () => string[];
  };
  readonly providersUi: {
    readonly showProviderModal: (
      existingName: string | null,
      existingData: any,
      onSave: (name: string, data: any) => void
    ) => void;
  };
  readonly loadFlowMapTab: () => Promise<{ openFlowMapTab(projectName: string): void }>;
  readonly loadDelegateChainView: () => Promise<{
    openDelegateChainTask(
      taskKey: string,
      meta?: Record<string, any>,
      opts?: Record<string, any>
    ): void;
  }>;
}

interface Window {
  __nfAdaptApi?: NfAdaptApi;
}

/** 遗留的跨模块 API 面（main.js 末尾一次性装配 window.Nebflow = {...}：卡片渲染
 *  / 主题 / 注入消息等几十个入口）。逐项类型化是独立的迁移工作，本闸只需承认
 *  这个装配点的存在，故按不透明字典声明（消费方当前仅测试脚本）。 */
interface Window {
  Nebflow?: Record<string, unknown>;
}

/** 浏览器语音识别（Chrome/Edge/Safari 的 SpeechRecognition）。lib.dom 至今未
 *  收录该 API，而 voiceEngine.js 还会在实例上挂自己的 per-instance 探针字段
 *  （inst.__vs），逐成员声明会把探针字段逼成索引签名（比 any 更差的类型面）
 *  —— 这里的任何口子属「浏览器 API 无 d.ts」，不是本仓契约缺口。 */
interface Window {
  SpeechRecognition?: { new (): any };
  webkitSpeechRecognition?: { new (): any };
}

/** Monaco Editor（vendor/monaco via AMD loader，非 ESM）。只声明本仓用到的
 *  调用面：主题定义、模型缓存与编辑器实例的 focus/dispose。 */
interface NfMonacoModel {
  uri: { toString(): string; path: string };
  isDisposed(): boolean;
  dispose(): void;
  getValue(): string;
  getValueLength(): number;
  setValue(value: string): void;
  onDidChangeContent(listener: () => void): { dispose(): void };
}

interface NfMonacoEditor {
  focus(): void;
  dispose(): void;
}

interface NfMonacoApi {
  Uri: { parse(value: string): NfMonacoModel['uri'] };
  editor: {
    defineTheme(
      name: string,
      theme: { base: string; inherit: boolean; rules: Array<unknown>; colors: Record<string, string> }
    ): void;
    setTheme(name: string): void;
    getModels(): NfMonacoModel[];
    getModel(uri: NfMonacoModel['uri']): NfMonacoModel | null;
    createModel(value: string, language: string, uri: NfMonacoModel['uri']): NfMonacoModel;
    create(container: HTMLElement, options: Record<string, unknown>): NfMonacoEditor;
  };
}

interface Window {
  monaco?: NfMonacoApi;
  /** Monaco loader.js 的 AMD 加载器（window.require）。 */
  require?: {
    config(options: { paths: Record<string, string> }): void;
    (deps: string[], onLoad: () => void): void;
  };
  MonacoEnvironment?: { getWorkerUrl(): string };
}

/** mammoth.js（docx 预览，vendor/mammoth.min.js：UMD 包，由 viewers/docx.js
 *  以 new Function 投到 window 全局）。只声明用到的转 HTML 面。 */
interface Window {
  mammoth?: {
    convertToHtml(
      input: { arrayBuffer: ArrayBuffer },
      options?: Record<string, unknown>
    ): Promise<{ value: string; messages: Array<unknown> }>;
    images: {
      imgElement(
        convert: (image: { contentType: string; read(encoding: string): Promise<string> }) => unknown
      ): unknown;
    };
  };
}

/** SheetJS（xlsx 预览，vendor/xlsx.full.min.js）。单元格对象形态动态（v/t/w/s
 *  等等），逐格类型化无收益，故工作表按不透明字典声明。 */
interface Window {
  XLSX?: {
    read(
      data: ArrayBuffer,
      options: { type: string }
    ): {
      SheetNames: string[];
      Sheets: Record<string, Record<string, any>>;
    };
    utils: {
      sheet_to_html(sheet: Record<string, any>, options?: { editable?: boolean }): string;
    };
  };
}

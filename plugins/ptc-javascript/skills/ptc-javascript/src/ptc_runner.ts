/**
 * ptc_runner —— PTC 代码运行时（受控子进程），与 ptc-python 的 ptc_runner.py 逐条对齐。
 *
 * 由 ptc_executor 以 `node --experimental-permission --allow-fs-read=… --allow-fs-write=… ptc_runner.js`
 * 拉起，唯一通信面是 stdio 上的 `ptc.wire/v1`（NDJSON）。
 *
 * 三层限制：
 *   ① Node 权限模型（内核外的进程级护栏）：fs 读写限定在 --allow-fs-*，child_process / worker 默认拒绝；
 *   ② 本文件的网络 patch：net/dns/tls/http/https/fetch 一律 PTC_POLICY_NETWORK（Node 权限模型不管网络）；
 *   ③ vm 上下文只给 tools / ptc / console，没有 require / process / Buffer（vm 不是安全边界，见契约 §9）。
 */
import * as fs from 'node:fs';
import * as vm from 'node:vm';
import * as net from 'node:net';
import * as dns from 'node:dns';
import * as tls from 'node:tls';
import * as http from 'node:http';
import * as https from 'node:https';

const WIRE = 'wire/1';

type Category =
  | 'SYNTAX_ERROR' | 'RUNTIME_ERROR' | 'TOOL_CALL_ERROR' | 'TIMEOUT'
  | 'LIMIT_EXCEEDED' | 'POLICY_DENIED' | 'INVALID_REQUEST' | 'RUNTIME_UNAVAILABLE' | 'INTERNAL_ERROR';

interface WireError { category: Category; code: string; phase: string; message: string; detail?: unknown }

class PolicyDenied extends Error {
  code: string;
  constructor(code: string, message: string) { super(message); this.code = code; }
}

class ToolCallError extends Error {
  category: Category; code: string;
  constructor(category: Category, code: string, message: string) { super(message); this.category = category; this.code = code; }
}

const state = {
  profile: 'readonly', roots: [] as string[], tools: [] as string[], known: [] as string[],
  args: {} as Record<string, unknown>, execId: null as string | null, write: false,
};

// ── 同步协议 IO（fd 0/1 直读直写；用户输出走 log 帧）──────────────────────────
const inBuf: Buffer[] = [];
let inOff = 0;

function readLine(): string | null {
  for (;;) {
    const joined = Buffer.concat(inBuf);
    const nl = joined.indexOf(10);
    if (nl >= 0) {
      const line = joined.subarray(0, nl).toString('utf8');
      const rest = joined.subarray(nl + 1);
      inBuf.length = 0; inBuf.push(rest); inOff = 0;
      return line;
    }
    const chunk = Buffer.allocUnsafe(65536);
    let n = 0;
    try { n = fs.readSync(0, chunk, 0, chunk.length, null); } catch (e: any) { if (e?.code === 'EAGAIN') continue; return null; }
    if (n === 0) return null;
    inBuf.push(chunk.subarray(0, n));
  }
}

function emit(obj: Record<string, unknown>): void {
  fs.writeSync(1, `${JSON.stringify({ ptc: WIRE, ...obj })}\n`);
}

const logTo = (stream: 'stdout' | 'stderr') => (...args: unknown[]) => {
  emit({ type: 'log', stream, data: args.map((a) => (typeof a === 'string' ? a : safeStr(a))).join(' ') + '\n' });
};

function safeStr(v: unknown): string {
  try { return JSON.stringify(v); } catch { return String(v); }
}

// ── 网络护栏（Node 权限模型不覆盖网络）──────────────────────────────────────
function denyNet(label: string): never {
  throw new PolicyDenied('PTC_POLICY_NETWORK', `network access is denied inside a PTC run (${label})`);
}

function installNetworkGuard(): void {
  // ESM 的模块命名空间是只读的，所以只能改原型/全局：所有 TCP/TLS/UDP 连接最终都过 net.Socket.connect
  const patch = (target: any, key: string, label: string) => {
    try { target[key] = () => denyNet(label); } catch { /* 只读面：退回 Socket 层拦截 */ }
  };
  try { net.Socket.prototype.connect = function () { return denyNet('net.Socket.connect'); } as any; } catch { /* noop */ }
  try { (net.Server.prototype as any).listen = () => denyNet('net.Server.listen'); } catch { /* noop */ }
  patch(globalThis as any, 'fetch', 'fetch');
  try { (globalThis as any).WebSocket = function WebSocket() { denyNet('WebSocket'); }; } catch { /* noop */ }
  for (const [mod, label] of [[tls, 'tls'], [http, 'http'], [https, 'https'], [dns, 'dns']] as const) {
    for (const key of ['connect', 'request', 'get', 'lookup', 'resolve']) patch(mod as any, key, `${label}.${key}`);
  }
}

// ── 工具代理（同步阻塞调用；一次一问一答）────────────────────────────────────
let seq = 0;

function callTool(tool: string, args: unknown): unknown {
  if (!state.tools.includes(tool)) {
    if (state.known.includes(tool)) {
      throw new PolicyDenied('PTC_TOOL_DENIED',
        `tool '${tool}' exists but is not authorised for this ${state.profile} execution (authorised: ${state.tools.join(', ')})`);
    }
    throw new PolicyDenied('PTC_TOOL_DENIED', `unknown tool '${tool}' (authorised: ${state.tools.join(', ')})`);
  }
  seq += 1;
  const mySeq = seq;
  emit({ type: 'tool_call', seq: mySeq, tool, args: (args && typeof args === 'object') ? args : {} });
  for (;;) {
    const line = readLine();
    if (line === null) throw new ToolCallError('RUNTIME_ERROR', 'PTC_RUNTIME', 'executor closed the channel during a tool call');
    let frame: any;
    try { frame = JSON.parse(line); } catch { continue; }
    if (frame.type !== 'tool_result' || frame.seq !== mySeq) continue;
    if (frame.ok) return frame.value;
    const e = frame.error ?? {};
    throw new ToolCallError(
      (['TOOL_CALL_ERROR', 'POLICY_DENIED', 'TIMEOUT', 'LIMIT_EXCEEDED'] as string[]).includes(e.category)
        ? e.category : 'TOOL_CALL_ERROR',
      e.code ?? 'PTC_TOOL_ERROR', e.message ?? 'tool call failed');
  }
}

/**
 * 上下文内的引导脚本：`tools` / `ptc` / `console` **在 vm 上下文里**创建，
 * 只通过一个 bridge 回宿主。这样 `tools.read.constructor` 拿到的是上下文 realm 的
 * Function（拿不到宿主的 process），工具返回值也经 JSON 字符串在新 realm 里重建
 * （避免宿主对象沿 prototype 链漏出去）。vm 依然不是安全边界，真正的护栏在
 * 权限模型 + 网络 patch，见契约 §9。
 */
const BOOT = `(function (bridge) {
  var cfg = JSON.parse(bridge.cfg);
  function contextError(message, category, code) {
    var e = new Error(message);
    e.ptcCategory = category; e.ptcCode = code;
    return e;
  }
  function mkTool(name) {
    return function (args) {
      var raw;
      try { raw = bridge.tool(name, JSON.stringify(args === undefined || args === null ? {} : args)); }
      catch (e) { throw contextError('bridge failure while calling ' + name, 'INTERNAL_ERROR', 'PTC_INTERNAL'); }
      var r = JSON.parse(raw);
      if (r.ok) return r.value;
      var err = r.error || {};
      throw contextError(err.message || 'tool call failed', err.category || 'TOOL_CALL_ERROR', err.code || 'PTC_TOOL_ERROR');
    };
  }
  var tools = {};
  cfg.allowed.forEach(function (n) { tools[n] = mkTool(n); });
  cfg.denied.forEach(function (n) {
    tools[n] = function () {
      throw contextError('tool ' + n + ' exists but is not authorised for this ' + cfg.profile + ' execution',
        'POLICY_DENIED', 'PTC_TOOL_DENIED');
    };
  });
  function str(x) { if (typeof x === 'string') return x; try { return JSON.stringify(x); } catch (e) { return String(x); } }
  function mk(stream) {
    return function () {
      var out = '';
      for (var i = 0; i < arguments.length; i += 1) { if (i) out += ' '; out += str(arguments[i]); }
      bridge.log(stream, out + '\\n');
    };
  }
  return {
    tools: tools,
    ptc: JSON.parse(bridge.ptc),
    console: { log: mk('stdout'), info: mk('stdout'), warn: mk('stderr'), error: mk('stderr'), debug: mk('stderr') }
  };
})`;

// ── 执行 ───────────────────────────────────────────────────────────────────
function errFmt(category: Category, code: string, phase: string, e: unknown, message?: string): WireError {
  const ex = e as any;
  return {
    category, code, phase,
    message: message ?? (ex && ex.message ? String(ex.message) : String(e)),
    detail: { type: ex?.name ?? typeof e, stack: typeof ex?.stack === 'string' ? ex.stack.slice(0, 4000) : null },
  };
}

async function main(): Promise<void> {
  const initLine = readLine();
  if (initLine === null) return;
  const init = JSON.parse(initLine);
  state.profile = init.profile ?? 'readonly';
  state.roots = init.fs_roots ?? [];
  state.write = state.profile === 'readwrite';
  state.tools = init.allowed_tools ?? [];
  state.known = init.known_tools ?? state.tools;
  state.args = init.args ?? {};
  state.execId = init.exec_id ?? null;

  const ctx = vm.createContext({});
  const boot = vm.runInContext(BOOT, ctx, { filename: '<ptc-boot>' });
  const api = boot({
    cfg: JSON.stringify({ allowed: state.tools, denied: state.known.filter((t) => !state.tools.includes(t)), profile: state.profile }),
    ptc: JSON.stringify({ exec_id: state.execId, profile: state.profile, tools: [...state.tools], args: state.args }),
    tool: (name: string, argsJson: string) => {
      try { return JSON.stringify({ ok: true, value: callTool(name, JSON.parse(argsJson)) }); }
      catch (e: any) {
        return JSON.stringify({ ok: false, error: {
          category: e?.ptcCategory ?? e?.category ?? 'TOOL_CALL_ERROR',
          code: e?.ptcCode ?? e?.code ?? 'PTC_TOOL_ERROR',
          message: e?.message ?? String(e),
        } });
      }
    },
    log: (stream: string, data: string) => emit({ type: 'log', stream: stream === 'stderr' ? 'stderr' : 'stdout', data }),
  });
  ctx.tools = api.tools;
  ctx.ptc = api.ptc;
  ctx.console = api.console;

  installNetworkGuard();
  const code: string = String(init.code ?? '');

  // ① 脚本模式（同步，完成值 = 最后一个表达式）
  try {
    new vm.Script(code, { filename: '<ptc>' });
  } catch (e: any) {
    if (e?.name === 'SyntaxError' && /await is only valid|await/i.test(String(e.message))) {
      // ② async 包装模式（用了 top-level await；返回值 = return 的值）
      const wrapped = `(async () => {\n${code}\n})()`;
      try {
        new vm.Script(wrapped, { filename: '<ptc>' });
      } catch (e2: any) {
        emit({ type: 'done', ok: false, error: errFmt('SYNTAX_ERROR', 'PTC_SYNTAX', 'compile', e2,
          `${e2?.name ?? 'SyntaxError'}: ${e2?.message ?? String(e2)}`) });
        return;
      }
      try {
        const value = await vm.runInContext(wrapped, ctx, { filename: '<ptc>' });
        emit({ type: 'done', ok: true, value: jsonSafe(value) });
      } catch (e3: any) {
        emitDone(e3);
      }
      return;
    }
    emit({ type: 'done', ok: false, error: errFmt('SYNTAX_ERROR', 'PTC_SYNTAX', 'compile', e) });
    return;
  }

  try {
    const value = vm.runInContext(code, ctx, { filename: '<ptc>' });
    emit({ type: 'done', ok: true, value: jsonSafe(value) });
  } catch (e) {
    emitDone(e);
  }
}

function jsonSafe(v: unknown): unknown {
  if (v === undefined) return null;
  try { JSON.stringify(v); return v; } catch { return String(v); }
}

function emitDone(e: unknown): void {
  const anyE = e as any;
  // 上下文 realm 里的错误带着我们写进去的 ptcCategory / ptcCode（跨 realm 不能靠 instanceof）
  const cat = anyE?.ptcCategory ?? anyE?.category;
  const code = anyE?.ptcCode ?? anyE?.code;
  if (cat) {
    const toolSourced = cat === 'TOOL_CALL_ERROR' || /^PTC_(TOOL_|POLICY_TOOL)/.test(String(code ?? ''));
    emit({ type: 'done', ok: false, error: { category: cat, code: code ?? 'PTC_RUNTIME',
      phase: toolSourced ? 'tool_call' : 'run', message: anyE?.message ?? '', detail: null } });
    return;
  }
  if (anyE instanceof PolicyDenied) {
    emit({ type: 'done', ok: false, error: { category: 'POLICY_DENIED', code: anyE.code, phase: 'run', message: anyE.message, detail: null } });
    return;
  }
  if (anyE instanceof ToolCallError) {
    emit({ type: 'done', ok: false, error: { category: anyE.category, code: anyE.code, phase: 'tool_call', message: anyE.message, detail: null } });
    return;
  }
  if (/dynamic import callback/i.test(String(anyE?.message ?? ''))) {
    emit({ type: 'done', ok: false, error: { category: 'POLICY_DENIED', code: 'PTC_POLICY_IMPORT', phase: 'run',
      message: 'module loading (import) is not available inside a PTC run', detail: null } });
    return;
  }
  if (anyE?.code === 'ERR_ACCESS_DENIED') {
    emit({ type: 'done', ok: false, error: { category: 'POLICY_DENIED', code: 'PTC_POLICY_FS', phase: 'run', message: anyE.message, detail: null } });
    return;
  }
  if (anyE instanceof RangeError && /heap out of memory|Invalid string length/i.test(String(anyE.message))) {
    emit({ type: 'done', ok: false, error: { category: 'LIMIT_EXCEEDED', code: 'PTC_LIMIT_MEMORY', phase: 'run', message: anyE.message, detail: null } });
    return;
  }
  emit({ type: 'done', ok: false, error: errFmt('RUNTIME_ERROR', 'PTC_RUNTIME', 'run', e) });
}

try {
  await main();
} catch (e) {
  emit({ type: 'done', ok: false, error: { category: 'INTERNAL_ERROR', code: 'PTC_INTERNAL', phase: 'teardown', message: String((e as any)?.message ?? e), detail: null } });
} finally {
  process.exit(0);
}

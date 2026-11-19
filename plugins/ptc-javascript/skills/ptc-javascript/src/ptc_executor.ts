/**
 * ptc_executor —— PTC 插件（ptc-javascript）的 MCP server = 执行器。
 * 与 plugins/ptc-python 的 ptc_executor.py **逐条对齐**（同一份契约 contracts/ptc/v1）：
 *   工具：ptc_run（只读档）/ ptc_run_rw（读写档）
 *   反向通道：执行器自己是工具 MCP server 的 client —— 代码里的 tools.read(...) 由宿主侧
 *             ptc_tools_fs 执行，全程标准 MCP，无协议扩展。
 */
import { spawn, execFile } from 'node:child_process';
import * as fs from 'node:fs';
import * as path from 'node:path';
import * as readline from 'node:readline';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PLUGIN_ROOT = path.resolve(process.env.PLUGIN_ROOT ?? path.join(HERE, '..', '..', '..'));
// PLUGIN_DATA 由宿主注入（<dataRoot>/plugin-data/<plugin>）；独立跑测试时落系统临时目录，
// 绝不默认写进插件包根（包根未知条目会被装载器告警、且污染包 digest）。
const PLUGIN_DATA = path.resolve(process.env.PLUGIN_DATA
  ?? path.join(process.env.TEMP ?? process.env.TMP ?? '/tmp', 'ptc-plugin-data', 'ptc-javascript'));
const PROTOCOL_VERSION = '2025-06-18';
const SERVER_NAME = 'ptc-javascript';
const SERVER_VERSION = '1.0.0';
const CONTRACT = 'ptc/v1';
const RUNNER = path.join(HERE, 'ptc_runner.js');

const manifest = JSON.parse(fs.readFileSync(path.join(PLUGIN_ROOT, 'plugin.json'), 'utf8'));
const PTC = manifest.extensions.ptc;

function expand<T>(value: T): T {
  if (typeof value === 'string') {
    return value
      .replaceAll('${PLUGIN_ROOT}', PLUGIN_ROOT)
      .replaceAll('${PLUGIN_DATA}', PLUGIN_DATA)
      .replaceAll('${RUNTIME_SELF}', process.execPath) as unknown as T;
  }
  if (Array.isArray(value)) return value.map((v) => expand(v)) as unknown as T;
  if (value && typeof value === 'object') {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) out[k] = expand(v);
    return out as T;
  }
  return value;
}

const DEFAULT_CWD_MANIFEST = expand<string>(PTC.workspace.default_cwd);

/** 规范化到真实路径；不存在时退回 resolve（PTC_FS_ROOTS 里写错不炸启动）。 */
const toReal = (p: string): string => {
  try { return fs.realpathSync(path.resolve(p)); } catch { return path.resolve(p); }
};

// ── fs_roots 覆盖（缺陷③，2026-11-02；与 ptc-python 的 ptc_executor.py 逐条同义）─
// 缺省：唯一根 = `${PLUGIN_DATA}/workspace`（插件自己的沙盒目录）。delegate 会话里
// 这不够用（工作区是它的 worktree，读自己 notes/ 全量 PolicyDenied）。覆盖面：
//   PTC_FS_ROOTS        pathsep 分隔的额外根（并入缺省根，成为新的上界）
//   PTC_FS_ROOTS_OPEN=1 允许请求级 fs_roots 点名任意**已存在目录**（per-call 收窄；
//                       每一次 ptc_run 仍过宿主 McpToolGate 审批）
//   PTC_CWD             缺省 cwd 覆盖（须落在最终 fs_roots 内）
// mcp.json 的 env 同样可承载三者（装载器展开 ${PLUGIN_ROOT}/${PLUGIN_DATA}）。
const EXTRA_ROOTS = (process.env.PTC_FS_ROOTS ?? '')
  .split(path.delimiter).map((s) => s.trim()).filter(Boolean).map((r) => toReal(r));
const ROOTS_OPEN = ['1', 'true', 'TRUE', 'yes'].includes((process.env.PTC_FS_ROOTS_OPEN ?? '0').trim());
const DEFAULT_ROOTS = Array.from(new Set([
  ...(expand<string[]>(PTC.workspace.fs_roots)).map((r) => toReal(path.resolve(r))),
  ...EXTRA_ROOTS,
]));
const DEFAULT_CWD = process.env.PTC_CWD?.trim()
  ? toReal(process.env.PTC_CWD.trim())
  : DEFAULT_CWD_MANIFEST;
const KNOWN_TOOLS = Array.from(new Set(Object.values(PTC.profiles as Record<string, { tools: string[] }>)
  .flatMap((p) => p.tools))).sort();
const TOOL_PROFILES: Record<string, string> = Object.fromEntries(
  (PTC.tools as { name: string; profile: string }[]).map((t) => [t.name, t.profile]));
fs.mkdirSync(DEFAULT_CWD, { recursive: true });

// ── 工具 server 的 MCP client（每次执行新建，执行结束即拆）──────────────────
class ToolServer {
  private proc: any;
  private pending = new Map<number, (msg: any) => void>();
  private nextId = 0;
  initError: string | null = null;

  constructor(spec: any, roots: string[], write: boolean) {
    const env = {
      PATH: process.env.PATH ?? '', SYSTEMROOT: process.env.SYSTEMROOT ?? '',
      PTC_FS_ROOTS: roots.join(path.delimiter), PTC_FS_WRITE: write ? '1' : '0',
      PTC_MAX_VALUE_BYTES: '16384', ...(spec.env ?? {}),
    };
    this.proc = spawn(spec.command, spec.args ?? [], { cwd: PLUGIN_ROOT, env, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
    const rl = readline.createInterface({ input: this.proc.stdout });
    rl.on('line', (line: string) => {
      if (!line.trim()) return;
      let msg: any;
      try { msg = JSON.parse(line); } catch { return; }
      const slot = this.pending.get(msg.id);
      if (slot) { this.pending.delete(msg.id); slot(msg); }
    });
    this.proc.stderr.on('data', (d: Buffer) => process.stderr.write(`[ptc-tools] ${d}`));
    this.proc.on('error', (e: Error) => { this.initError = e.message; });
  }

  async init(): Promise<void> {
    if (this.initError) return;
    try {
      await this.request('initialize', { protocolVersion: PROTOCOL_VERSION, capabilities: {}, clientInfo: { name: `${SERVER_NAME}-executor`, version: SERVER_VERSION } }, 10000);
      this.notify('notifications/initialized', {});
    } catch (e: any) {
      this.initError = `${e?.name ?? 'Error'}: ${e?.message ?? e}`;
    }
  }

  private send(obj: unknown): void {
    try { this.proc.stdin.write(`${JSON.stringify(obj)}\n`); } catch { /* channel closed */ }
  }

  notify(method: string, params: unknown): void { this.send({ jsonrpc: '2.0', method, params }); }

  request(method: string, params: unknown, timeoutMs: number): Promise<any> {
    const id = ++this.nextId;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`tool server timeout after ${timeoutMs}ms`)); }, timeoutMs);
      this.pending.set(id, (msg) => {
        clearTimeout(timer);
        if (msg.error) reject(new Error(`tool server error: ${msg.error.message}`));
        else resolve(msg.result ?? {});
      });
      this.send({ jsonrpc: '2.0', id, method, params });
    });
  }

  async call(name: string, args: unknown, timeoutMs: number): Promise<any> {
    const result = await this.request('tools/call', { name, arguments: args }, timeoutMs);
    const text = (result.content ?? []).filter((b: any) => typeof b?.text === 'string').map((b: any) => b.text).join('');
    if (!text) return { ok: false, error: { code: 'PTC_TOOL_ERROR', message: 'empty tool result' } };
    try { return JSON.parse(text); } catch { return { ok: true, value: text, bytes: Buffer.byteLength(text, 'utf8') }; }
  }

  close(): void { try { this.proc?.kill(); } catch { /* already gone */ } }
}

// ── 资源观测 ───────────────────────────────────────────────────────────────
function rssKb(pid: number): Promise<number | null> {
  return new Promise((resolve) => {
    const win = process.platform === 'win32';
    const cmd = win ? 'tasklist' : 'ps';
    const args = win ? ['/FI', `PID eq ${pid}`, '/FO', 'CSV', '/NH'] : ['-o', 'rss=', '-p', String(pid)];
    execFile(cmd, args, { windowsHide: true }, (e, stdout) => {
      if (e) return resolve(null);
      if (win) {
        const m = /"([^"]+)","([^"]+)","([^"]+)","([^"]+)","([\d,]+) K"/.exec(stdout);
        return resolve(m ? Number(m[5].replace(/,/g, '')) : null);
      }
      const v = Number(stdout.trim().split(/\s+/)[0]);
      return resolve(Number.isFinite(v) ? v : null);
    });
  });
}

// ── 请求校验 ───────────────────────────────────────────────────────────────
class Invalid extends Error {}

const real = (p: string) => fs.realpathSync(path.resolve(p));
const inside = (p: string, roots: string[]) => roots.some((r) => p === r || p.startsWith(r + path.sep));

function validate(args: any, toolProfile: string): any {
  const limitsCfg = PTC.limits;
  const clamped: string[] = [];
  if (args.contract !== undefined && args.contract !== CONTRACT) throw new Invalid(`contract '${args.contract}' is not supported (this server speaks ${CONTRACT})`);
  if (args.language !== undefined && args.language !== PTC.runtime.kind) throw new Invalid(`language '${args.language}' does not match this plugin's runtime '${PTC.runtime.kind}'`);
  const code = args.code;
  if (typeof code !== 'string' || !code.trim()) throw new Invalid("'code' must be a non-empty string");
  if (Buffer.byteLength(code, 'utf8') > 262144) throw new Invalid("'code' exceeds the 256 KiB request cap");

  const profile = args.profile ?? toolProfile;
  if (!['readonly', 'readwrite'].includes(profile)) throw new Invalid('profile must be readonly|readwrite');
  if (toolProfile === 'readonly' && profile === 'readwrite') {
    throw new Invalid('ptc_run is the readonly entry point; use ptc_run_rw for a write-capable execution');
  }
  const ceiling: string[] = PTC.profiles[profile].tools;
  let allowed: string[] = ceiling;
  if (args.allowed_tools !== undefined) {
    if (!Array.isArray(args.allowed_tools) || args.allowed_tools.some((t: unknown) => typeof t !== 'string')) throw new Invalid('allowed_tools must be a string array');
    const extra = args.allowed_tools.filter((t: string) => !ceiling.includes(t));
    if (extra.length) throw new Invalid(`allowed_tools ${JSON.stringify(extra)} is not within the ${profile} ceiling ${JSON.stringify(ceiling)}`);
    allowed = ceiling.filter((t) => args.allowed_tools.includes(t));
  }

  const budget: any = { ...limitsCfg };
  for (const key of ['max_tool_calls', 'max_output_bytes', 'max_memory_mb', 'max_cpu_ms']) {
    if (args.limits?.[key] !== undefined) {
      const v = args.limits[key];
      if (!Number.isInteger(v) || v <= 0) throw new Invalid(`limits.${key} must be a positive integer`);
      if (v < limitsCfg[key]) budget[key] = v; else clamped.push(`limits.${key}`);
    }
  }

  let timeoutMs = args.timeout_ms ?? limitsCfg.default_timeout_ms;
  if (args.timeout_ms !== undefined && (!Number.isInteger(args.timeout_ms) || args.timeout_ms <= 0)) throw new Invalid('timeout_ms must be a positive integer');
  if (timeoutMs > limitsCfg.max_timeout_ms) { timeoutMs = limitsCfg.max_timeout_ms; clamped.push('timeout_ms'); }

  let roots: string[] = DEFAULT_ROOTS;
  if (args.fs_roots !== undefined) {
    if (!Array.isArray(args.fs_roots) || args.fs_roots.length === 0 || args.fs_roots.some((r: unknown) => typeof r !== 'string'))
      throw new Invalid('fs_roots must be a non-empty string array');
    roots = (args.fs_roots as string[]).map((r) => real(r));
    // 缺陷③：默认仍要求「请求级 fs_roots ⊆ 已知根」；只有显式 PTC_FS_ROOTS_OPEN=1
    // 才允许点名任意**已存在目录**（delegate 自助声明工作区）。
    if (!ROOTS_OPEN) {
      if (!roots.every((r) => inside(r, DEFAULT_ROOTS)))
        throw new Invalid(`fs_roots must stay inside the manifest roots ${JSON.stringify(DEFAULT_ROOTS)} `
          + '(set PTC_FS_ROOTS=<path> to add a root, or PTC_FS_ROOTS_OPEN=1 to let the request declare its own)');
    } else {
      const missing = roots.filter((r) => { try { return !fs.statSync(r).isDirectory(); } catch { return true; } });
      if (missing.length) throw new Invalid(`fs_roots entries must be existing directories: ${JSON.stringify(missing)}`);
    }
  }

  // cwd：显式 > 请求自带 fs_roots 的首个根 > 缺省 cwd（插件 workspace / PTC_CWD）。
  const cwd = real(args.cwd ?? (args.fs_roots !== undefined ? roots[0] : DEFAULT_CWD));
  if (!inside(cwd, roots)) throw new Invalid(`cwd '${cwd}' is outside fs_roots`);
  if (!fs.statSync(cwd).isDirectory()) throw new Invalid(`cwd '${cwd}' is not an existing directory`);

  return { code, profile, allowed, budget, timeoutMs, cwd, roots, args: (args.args && typeof args.args === 'object') ? args.args : {}, clamped };
}

// ── 执行 ───────────────────────────────────────────────────────────────────
function baseResponse(execId: string | null, profile: string) {
  return {
    contract: CONTRACT, exec_id: execId, language: PTC.runtime.kind, profile,
    stdout: '', stderr: '', stdout_truncated: false, stderr_truncated: false,
    tool_calls: [] as any[], tool_call_count: 0, files_written: [] as string[],
    value: null as unknown, clamped: [] as string[],
  };
}

async function execute(rawArgs: any, toolProfile: string): Promise<any> {
  const t0 = Date.now();
  const execId = rawArgs?.exec_id ?? `ptc-${Math.random().toString(16).slice(2, 10)}`;
  let plan: any;
  try {
    plan = validate(rawArgs ?? {}, toolProfile);
  } catch (e: any) {
    const body = { ...baseResponse(execId, toolProfile), ok: false, duration_ms: Date.now() - t0,
      error: { category: 'INVALID_REQUEST', code: 'PTC_INVALID_REQUEST', phase: 'validate', message: e.message, tool: null, detail: null },
      usage: { wall_ms: Date.now() - t0, cpu_ms: null, max_rss_kb: null, tool_calls: 0, output_bytes: 0 } };
    return body;
  }
  const budget = plan.budget;
  const server = new ToolServer(expand<any>(PTC.tool_servers[0]), plan.roots, plan.profile === 'readwrite');
  await server.init();
  if (server.initError) {
    server.close();
    return { ...baseResponse(execId, plan.profile), ok: false, duration_ms: Date.now() - t0, clamped: plan.clamped,
      error: { category: 'RUNTIME_UNAVAILABLE', code: 'PTC_RUNTIME_UNAVAILABLE', phase: 'validate', message: `tool server failed to start: ${server.initError}`, tool: null, detail: null },
      usage: { wall_ms: Date.now() - t0, cpu_ms: null, max_rss_kb: null, tool_calls: 0, output_bytes: 0 } };
  }

  const permArgs = [
    '--experimental-permission', '--no-warnings',
    `--max-old-space-size=${budget.max_memory_mb}`,
    // 运行器自身（及其 dist 目录的 package.json）必须可读，否则模块加载器都起不来
    `--allow-fs-read=${RUNNER}`,
    `--allow-fs-read=${path.dirname(RUNNER)}`,
    ...plan.roots.map((r: string) => `--allow-fs-read=${r}`),
    ...plan.roots.map((r: string) => `--allow-fs-write=${r}`),
    RUNNER,
  ];
  const runner = spawn(process.execPath, permArgs, {
    cwd: plan.cwd, env: { PATH: process.env.PATH ?? '', SYSTEMROOT: process.env.SYSTEMROOT ?? '' },
    stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true,
  });

  const ledger: any[] = [];
  const out: { stdout: string[]; stderr: string[] } = { stdout: [], stderr: [] };
  let outBytes = 0;
  let truncated = false;
  let peakRss: number | null = null;
  let done: any = null;
  let outcome: { category: string; code: string; phase: string; message: string } | null = null;

  const append = (text: string, isErr: boolean) => {
    const room = budget.max_output_bytes - outBytes;
    if (room <= 0) { truncated = true; return; }
    const chunk = text.length <= room ? text : text.slice(0, room);
    if (chunk.length < text.length) truncated = true;
    outBytes += Buffer.byteLength(chunk, 'utf8');
    (isErr ? out.stderr : out.stdout).push(chunk);
  };

  runner.stderr.on('data', (d: Buffer) => append(d.toString('utf8'), true));
  runner.stdin.on('error', () => { /* channel closed */ });

  const deadline = t0 + plan.timeoutMs;
  const memTimer = setInterval(async () => {
    const rss = await rssKb(runner.pid as number);
    if (rss === null) return;
    peakRss = Math.max(peakRss ?? 0, rss);
    if (rss > budget.max_memory_mb * 1024 && outcome === null) {
      outcome = { category: 'LIMIT_EXCEEDED', code: 'PTC_LIMIT_MEMORY', phase: 'run', message: `peak RSS ${rss} KiB exceeded max_memory_mb=${budget.max_memory_mb}` };
      try { runner.kill(); } catch { /* gone */ }
    }
  }, 250);

  const timeoutTimer = setTimeout(() => {
    if (outcome === null) {
      outcome = { category: 'TIMEOUT', code: 'PTC_TIMEOUT', phase: 'run', message: `execution exceeded timeout_ms=${plan.timeoutMs}` };
    }
    try { runner.kill(); } catch { /* gone */ }
  }, plan.timeoutMs);

  const rl = readline.createInterface({ input: runner.stdout });
  const finished = new Promise<void>((resolve) => {
    rl.on('line', async (line: string) => {
      if (!line.trim() || done) return;
      let frame: any;
      try { frame = JSON.parse(line); } catch { append(`${line}\n`, false); return; }
      if (frame.type === 'log') append(String(frame.data ?? ''), frame.stream === 'stderr');
      else if (frame.type === 'tool_call') {
        const started = Date.now();
        const reply = await dispatchTool(frame.tool, frame.args ?? {}, plan, budget, ledger, server, execId);
        reply.seq = frame.seq;
        reply.duration_ms = Date.now() - started;
        if (ledger.length) ledger[ledger.length - 1].duration_ms = reply.duration_ms;
        try { runner.stdin.write(`${JSON.stringify(reply)}\n`); } catch { /* channel closed */ }
      } else if (frame.type === 'done') {
        done = frame;
        resolve();
      }
    });
    runner.on('exit', () => resolve());
    runner.on('error', (e: Error) => { outcome = outcome ?? { category: 'INTERNAL_ERROR', code: 'PTC_INTERNAL', phase: 'teardown', message: e.message }; resolve(); });
  });

  try {
    runner.stdin.write(`${JSON.stringify({
      type: 'init', code: plan.code, language: PTC.runtime.kind, profile: plan.profile,
      allowed_tools: plan.allowed, known_tools: KNOWN_TOOLS, limits: budget,
      cwd: plan.cwd, fs_roots: plan.roots, args: plan.args, exec_id: execId,
    })}\n`);
    await finished;
  } catch (e: any) {
    outcome = outcome ?? { category: 'INTERNAL_ERROR', code: 'PTC_INTERNAL', phase: 'teardown', message: `${e?.name}: ${e?.message}` };
  } finally {
    clearTimeout(timeoutTimer);
    clearInterval(memTimer);
    try { runner.kill(); } catch { /* gone */ }
    server.close();
  }

  const durationMs = Date.now() - t0;
  const stdout = out.stdout.join('');
  const stderr = out.stderr.join('');
  const response: any = {
    ...baseResponse(execId, plan.profile), clamped: plan.clamped,
    stdout, stderr, stdout_truncated: truncated, stderr_truncated: false,
    tool_calls: ledger, tool_call_count: ledger.length,
    files_written: ledger.filter((c) => c.ok && ['write', 'edit', 'remove'].includes(c.tool)).map((c) => c.args?.path),
    duration_ms: durationMs,
    usage: { wall_ms: durationMs, cpu_ms: null, max_rss_kb: peakRss, tool_calls: ledger.length,
      output_bytes: Buffer.byteLength(stdout, 'utf8') + Buffer.byteLength(stderr, 'utf8') },
  };
  if (outcome) {
    response.ok = false;
    response.error = { ...outcome, tool: null, detail: null };
  } else {
    const e = done?.error;
    response.ok = Boolean(done?.ok);
    response.value = done?.value ?? null;
    response.error = e ? { category: e.category ?? 'RUNTIME_ERROR', code: e.code ?? 'PTC_RUNTIME',
      phase: e.phase ?? 'run', message: e.message ?? '', tool: null, detail: e.detail ?? null } : null;
    if (!done && !outcome) {
      response.ok = false;
      // V8 堆上限（--max-old-space-size）撞线时进程直接 abort，没有 done 帧：按内存限额归类
      const oom = /heap out of memory|JavaScript heap/i.test(stderr);
      response.error = oom
        ? { category: 'LIMIT_EXCEEDED', code: 'PTC_LIMIT_MEMORY', phase: 'run',
            message: `runner aborted on the V8 heap cap (max_memory_mb=${budget.max_memory_mb})`, tool: null, detail: null }
        : { category: 'RUNTIME_ERROR', code: 'PTC_RUNTIME', phase: 'run',
            message: 'runner exited without a done frame (killed or crashed)', tool: null, detail: null };
    }
  }
  return response;
}

async function dispatchTool(tool: string, args: any, plan: any, budget: any, ledger: any[],
                            server: ToolServer, _execId: string): Promise<any> {
  const entry: any = { seq: ledger.length + 1, tool, args: (args && typeof args === 'object') ? args : {}, ok: false, duration_ms: 0 };
  if (!plan.allowed.includes(tool)) {
    const message = `tool '${tool}' is not authorised (allowed: ${plan.allowed.join(', ')})`;
    ledger.push({ ...entry, error: { category: 'POLICY_DENIED', code: 'PTC_TOOL_DENIED', message } });
    return { type: 'tool_result', ok: false, error: { category: 'POLICY_DENIED', code: 'PTC_TOOL_DENIED', message } };
  }
  if (ledger.length >= budget.max_tool_calls) {
    const message = `max_tool_calls=${budget.max_tool_calls} reached`;
    ledger.push({ ...entry, error: { category: 'LIMIT_EXCEEDED', code: 'PTC_LIMIT_TOOL_CALLS', message } });
    return { type: 'tool_result', ok: false, error: { category: 'LIMIT_EXCEEDED', code: 'PTC_LIMIT_TOOL_CALLS', message } };
  }
  try {
    const envelope = await server.call(tool, entry.args, PTC.tool_servers[0].timeout_ms ?? 10000);
    if (envelope.ok) {
      ledger.push({ ...entry, ok: true, truncated: Boolean(envelope.truncated), result_bytes: Number(envelope.bytes ?? 0) });
      return { type: 'tool_result', ok: true, value: envelope.value };
    }
    const code = envelope.error?.code ?? 'PTC_TOOL_ERROR';
    const category = code.startsWith('PTC_POLICY') ? 'POLICY_DENIED' : 'TOOL_CALL_ERROR';
    const message = envelope.error?.message ?? 'tool call failed';
    ledger.push({ ...entry, error: { category, code, message } });
    return { type: 'tool_result', ok: false, error: { category, code, message } };
  } catch (e: any) {
    const message = `tool server failure: ${e?.name ?? 'Error'}: ${e?.message ?? e}`;
    ledger.push({ ...entry, error: { category: 'TOOL_CALL_ERROR', code: 'PTC_TOOL_ERROR', message } });
    return { type: 'tool_result', ok: false, error: { category: 'TOOL_CALL_ERROR', code: 'PTC_TOOL_ERROR', message } };
  }
}

// ── MCP stdio 骨架 ─────────────────────────────────────────────────────────
function send(obj: unknown): void { process.stdout.write(`${JSON.stringify(obj)}\n`); }

function inputSchema(): unknown {
  return {
    type: 'object',
    properties: {
      code: { type: 'string', description: 'Code to execute. Inside it, call host tools as tools.<name>(args).' },
      language: { enum: [PTC.runtime.kind] },
      profile: { enum: ['readonly', 'readwrite'] },
      allowed_tools: { type: 'array', items: { type: 'string' }, description: 'Subset of the profile ceiling.' },
      timeout_ms: { type: 'integer' },
      limits: { type: 'object', properties: { max_tool_calls: { type: 'integer' }, max_output_bytes: { type: 'integer' }, max_memory_mb: { type: 'integer' }, max_cpu_ms: { type: 'integer' } } },
      cwd: { type: 'string' },
      fs_roots: { type: 'array', items: { type: 'string' } },
      args: { type: 'object', description: 'Bound to ptc.args inside the code.' },
      exec_id: { type: 'string' },
    },
    required: ['code'],
  };
}

function toolsList(): unknown {
  return (PTC.tools as any[]).map((t) => ({
    name: t.name,
    description:
      `PTC v1 executor (${SERVER_NAME}, ${t.profile} profile). Execute ${PTC.runtime.kind} code that ORCHESTRATES host tool calls: `
      + `inside the code call tools.<name>(args) for ${PTC.profiles[t.profile].tools.join(', ')}. `
      + 'One call returns the program stdout, its completion value and a per-call tool ledger. '
      + 'Use it when a task needs several tool calls in a loop/chain — one code round-trip instead of many tool round-trips. '
      + 'Network, subprocesses and paths outside fs_roots are denied.',
    inputSchema: inputSchema(),
  }));
}

const rlIn = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
rlIn.on('line', async (line) => {
  if (!line.trim()) return;
  let req: any;
  try { req = JSON.parse(line); } catch { return; }
  const { method, id } = req;
  if (method === 'initialize') {
    send({ jsonrpc: '2.0', id, result: { protocolVersion: PROTOCOL_VERSION, capabilities: { tools: {} }, serverInfo: { name: SERVER_NAME, version: SERVER_VERSION } } });
  } else if (method === 'tools/list') {
    send({ jsonrpc: '2.0', id, result: { tools: toolsList() } });
  } else if (method === 'tools/call') {
    const params = req.params ?? {};
    const name = params.name;
    if (!(name in TOOL_PROFILES)) {
      send({ jsonrpc: '2.0', id, error: { code: -32602, message: `unknown tool: ${name}` } });
      return;
    }
    let response: any;
    try {
      response = await execute(params.arguments ?? {}, TOOL_PROFILES[name]);
    } catch (e: any) {
      response = { ...baseResponse(params.arguments?.exec_id ?? null, TOOL_PROFILES[name]), ok: false, duration_ms: 0,
        error: { category: 'INTERNAL_ERROR', code: 'PTC_INTERNAL', phase: 'teardown', message: `${e?.name ?? 'Error'}: ${e?.message ?? e}` },
        usage: { wall_ms: 0, cpu_ms: null, max_rss_kb: null, tool_calls: 0, output_bytes: 0 } };
    }
    send({ jsonrpc: '2.0', id, result: { content: [{ type: 'text', text: JSON.stringify(response) }], isError: response.ok !== true } });
  } else if (method === 'ping') {
    send({ jsonrpc: '2.0', id, result: {} });
  } else if (method?.startsWith?.('notifications/')) {
    /* no-op */
  } else if (id !== undefined) {
    send({ jsonrpc: '2.0', id, error: { code: -32601, message: `method not found: ${method}` } });
  }
});

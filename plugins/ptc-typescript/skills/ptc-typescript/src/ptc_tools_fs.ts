/**
 * ptc_tools_fs —— 宿主侧文件系统工具面（MCP server, stdio, 零第三方依赖）。
 *
 * 与 plugins/ptc-python 的 ptc_tools_fs.py **行为逐条对齐**（契约 contracts/ptc/v1）：
 *   环境变量 PTC_FS_ROOTS / PTC_FS_WRITE / PTC_MAX_VALUE_BYTES
 *   返回 content[0].text = JSON {"ok":true,"value":…,"bytes":n,"truncated":bool}
 *                     或  {"ok":false,"error":{"code":"PTC_POLICY_FS"|"PTC_TOOL_ERROR","message":"…"}}
 * 工具集：read / write / edit / glob / grep / stat / remove
 */
import * as fs from 'node:fs';
import * as path from 'node:path';
import * as readline from 'node:readline';

const PROTOCOL_VERSION = '2025-06-18';
const SERVER_NAME = 'ptc-tools-fs';
const SERVER_VERSION = '1.0.0';

const ROOTS = (process.env.PTC_FS_ROOTS || '')
  .split(path.delimiter).filter(Boolean).map((p) => fs.realpathSync(path.resolve(p)));
if (ROOTS.length === 0) ROOTS.push(fs.realpathSync(process.cwd()));
const WRITE = process.env.PTC_FS_WRITE === '1';
const MAX_VALUE_BYTES = Number(process.env.PTC_MAX_VALUE_BYTES || 16384);
const MAX_READ_BYTES = 262144;

type Envelope = Record<string, unknown>;

const ok = (value: unknown, truncated = false): Envelope => ({
  ok: true, value, truncated, bytes: Buffer.byteLength(JSON.stringify(value), 'utf8'),
});
const err = (code: string, message: string): Envelope => ({ ok: false, error: { code, message } });

class Denied extends Error {}

/** 规范化到真实路径；目标尚不存在时（write 新建）沿祖先回溯，保持与 python `os.path.realpath` 同义。 */
function safeReal(target: string): string {
  try { return fs.realpathSync(target); } catch {
    const parent = path.dirname(target);
    if (parent === target) return target;
    return path.join(safeReal(parent), path.basename(target));
  }
}

function resolvePath(p: unknown, mustWrite = false): string {
  if (typeof p !== 'string' || !p) throw new Denied('path must be a non-empty string');
  const abs = safeReal(path.isAbsolute(p) ? p : path.join(ROOTS[0], p));
  const inside = ROOTS.some((r) => abs === r || abs.startsWith(r + path.sep));
  if (!inside) throw new Denied(`path escapes the authorised fs_roots: ${p}`);
  if (mustWrite && !WRITE) throw new Denied('write is not authorised for this execution (readonly profile)');
  return abs;
}

function walk(base: string, sink: string[]): void {
  for (const entry of fs.readdirSync(base, { withFileTypes: true })) {
    if (entry.name.startsWith('.')) continue;
    const full = path.join(base, entry.name);
    if (entry.isDirectory()) walk(full, sink);
    else sink.push(full);
  }
}

function globToRe(glob: string): RegExp {
  const esc = glob.replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*').replace(/\?/g, '.');
  return new RegExp(`^${esc}$`);
}

const TOOLS: Record<string, { desc: string; props: Record<string, unknown>; required: string[]; run: (a: any) => Envelope }> = {
  read: {
    desc: 'Read a UTF-8 text file inside the authorised roots.',
    props: { path: { type: 'string' }, offset: { type: 'integer' }, limit: { type: 'integer' } },
    required: ['path'],
    run: (a) => {
      const p = resolvePath(a.path);
      let buf = fs.readFileSync(p).subarray(0, MAX_READ_BYTES + 1);
      let truncated = buf.length > MAX_READ_BYTES;
      buf = buf.subarray(0, MAX_READ_BYTES);
      let lines = buf.toString('utf8').split(/\r?\n/);
      if (a.offset) lines = lines.slice(Number(a.offset));
      if (a.limit) lines = lines.slice(0, Number(a.limit));
      let text = lines.join('\n');
      if (Buffer.byteLength(text, 'utf8') > MAX_VALUE_BYTES) {
        text = Buffer.from(text, 'utf8').subarray(0, MAX_VALUE_BYTES).toString('utf8');
        truncated = true;
      }
      return ok({ path: p, text, lines: lines.length }, truncated);
    },
  },
  write: {
    desc: 'Write a UTF-8 text file (creates parent dirs). Read-write profile only.',
    props: { path: { type: 'string' }, content: { type: 'string' }, mkdirs: { type: 'boolean' } },
    required: ['path', 'content'],
    run: (a) => {
      const p = resolvePath(a.path, true);
      const content = typeof a.content === 'string' ? a.content : '';
      const existed = fs.existsSync(p);
      if (a.mkdirs !== false) fs.mkdirSync(path.dirname(p), { recursive: true });
      fs.writeFileSync(p, content, 'utf8');
      return ok({ path: p, bytes: Buffer.byteLength(content, 'utf8'), created: !existed });
    },
  },
  edit: {
    desc: 'Replace one occurrence of `old` with `new` in a file. Read-write profile only.',
    props: { path: { type: 'string' }, old: { type: 'string' }, new: { type: 'string' }, count: { type: 'integer' } },
    required: ['path', 'old', 'new'],
    run: (a) => {
      const p = resolvePath(a.path, true);
      if (typeof a.old !== 'string' || !a.old) return err('PTC_TOOL_ERROR', 'edit requires a non-empty string old');
      const text = fs.readFileSync(p, 'utf8');
      if (!text.includes(a.old)) return err('PTC_TOOL_ERROR', `edit: 'old' text not found in ${p}`);
      const limit = a.count === 0 ? Number.POSITIVE_INFINITY : Number(a.count ?? 1);
      let next = text;
      let replaced = 0;
      let idx = next.indexOf(a.old);
      while (idx >= 0 && replaced < limit) {
        next = next.slice(0, idx) + a.new + next.slice(idx + a.old.length);
        idx = next.indexOf(a.old, idx + a.new.length);
        replaced += 1;
      }
      fs.writeFileSync(p, next, 'utf8');
      return ok({ path: p, replaced });
    },
  },
  glob: {
    desc: 'List paths under a directory matching a shell glob (max 200).',
    props: { pattern: { type: 'string' }, path: { type: 'string' } },
    required: ['pattern'],
    run: (a) => {
      const base = resolvePath(a.path ?? '.');
      const rx = globToRe(String(a.pattern ?? '*'));
      const files: string[] = [];
      walk(fs.statSync(base).isDirectory() ? base : path.dirname(base), files);
      const rel = files.map((f) => path.relative(base, f).split(path.sep).join('/'));
      const hits = rel.filter((r) => rx.test(r) || rx.test(path.basename(r))).slice(0, 200);
      return ok({ paths: hits.sort(), base }, rel.length > 200);
    },
  },
  grep: {
    desc: 'Regex search file contents under a path; returns {path,line,text} rows (max 50).',
    props: { pattern: { type: 'string' }, path: { type: 'string' }, max_results: { type: 'integer' } },
    required: ['pattern'],
    run: (a) => {
      if (typeof a.pattern !== 'string' || !a.pattern) return err('PTC_TOOL_ERROR', "grep requires a 'pattern' string");
      let rx: RegExp;
      try { rx = new RegExp(a.pattern); } catch (e: any) { return err('PTC_TOOL_ERROR', `grep: invalid regex: ${e.message}`); }
      const base = resolvePath(a.path ?? '.');
      const max = Number(a.max_results ?? 50);
      const files: string[] = [];
      if (fs.statSync(base).isDirectory()) walk(base, files); else files.push(base);
      const matches: any[] = [];
      for (const full of files) {
        let text: string;
        try {
          if (fs.statSync(full).size > MAX_READ_BYTES) continue;
          text = fs.readFileSync(full, 'utf8');
        } catch { continue; }
        const lines = text.split(/\r?\n/);
        for (let i = 0; i < lines.length; i += 1) {
          if (rx.test(lines[i])) {
            matches.push({ path: path.relative(base, full).split(path.sep).join('/'), line: i + 1, text: lines[i].slice(0, MAX_VALUE_BYTES) });
            if (matches.length >= max) return ok({ matches, count: matches.length }, true);
          }
        }
      }
      return ok({ matches, count: matches.length });
    },
  },
  stat: {
    desc: 'Stat a path: exists/is_dir/size/mtime_ms.',
    props: { path: { type: 'string' } }, required: ['path'],
    run: (a) => {
      const p = resolvePath(a.path);
      if (!fs.existsSync(p)) return ok({ path: p, exists: false, is_dir: false, size: 0, mtime_ms: 0 });
      const st = fs.statSync(p);
      return ok({ path: p, exists: true, is_dir: st.isDirectory(), size: st.size, mtime_ms: Math.floor(st.mtimeMs) });
    },
  },
  remove: {
    desc: 'Delete a file or (recursive=true) a directory. Read-write profile only.',
    props: { path: { type: 'string' }, recursive: { type: 'boolean' } }, required: ['path'],
    run: (a) => {
      const p = resolvePath(a.path, true);
      if (!fs.existsSync(p)) return err('PTC_TOOL_ERROR', `remove: path does not exist: ${p}`);
      if (fs.statSync(p).isDirectory() && a.recursive !== true) {
        return err('PTC_TOOL_ERROR', `remove: ${p} is a directory (pass recursive=true)`);
      }
      fs.rmSync(p, { recursive: a.recursive === true, force: true });
      return ok({ path: p, removed: true });
    },
  },
};

function send(obj: unknown): void { process.stdout.write(`${JSON.stringify(obj)}\n`); }

function callTool(name: string, args: any): Envelope {
  const t = TOOLS[name];
  if (!t) return err('PTC_TOOL_ERROR', `unknown tool: ${name}`);
  try { return t.run(args ?? {}); }
  catch (e: any) {
    if (e instanceof Denied) return err('PTC_POLICY_FS', e.message);
    if (e?.code === 'ENOENT') return err('PTC_TOOL_ERROR', `not found: ${args?.path}`);
    return err('PTC_TOOL_ERROR', `${e?.name ?? 'Error'}: ${e?.message ?? String(e)}`);
  }
}

const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
rl.on('line', (line) => {
  if (!line.trim()) return;
  let req: any;
  try { req = JSON.parse(line); } catch { return; }
  const { method, id } = req;
  if (method === 'initialize') {
    send({ jsonrpc: '2.0', id, result: { protocolVersion: PROTOCOL_VERSION, capabilities: { tools: {} }, serverInfo: { name: SERVER_NAME, version: SERVER_VERSION } } });
  } else if (method === 'tools/list') {
    send({ jsonrpc: '2.0', id, result: { tools: Object.entries(TOOLS).map(([name, t]) => ({ name, description: t.desc, inputSchema: { type: 'object', properties: t.props, required: t.required } })) } });
  } else if (method === 'tools/call') {
    const p = req.params ?? {};
    const env = callTool(p.name, p.arguments ?? {});
    send({ jsonrpc: '2.0', id, result: { content: [{ type: 'text', text: JSON.stringify(env) }], isError: env.ok !== true } });
  } else if (method === 'ping') {
    send({ jsonrpc: '2.0', id, result: {} });
  } else if (method?.startsWith?.('notifications/')) {
    /* no-op */
  } else if (id !== undefined) {
    send({ jsonrpc: '2.0', id, error: { code: -32601, message: `method not found: ${method}` } });
  }
});

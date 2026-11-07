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
if (ROOTS.length === 0)
    ROOTS.push(fs.realpathSync(process.cwd()));
const WRITE = process.env.PTC_FS_WRITE === '1';
const MAX_VALUE_BYTES = Number(process.env.PTC_MAX_VALUE_BYTES || 16384);
const MAX_READ_BYTES = 262144;
const ok = (value, truncated = false) => ({
    ok: true, value, truncated, bytes: Buffer.byteLength(JSON.stringify(value), 'utf8'),
});
const err = (code, message) => ({ ok: false, error: { code, message } });
class Denied extends Error {
}
/** 规范化到真实路径；目标尚不存在时（write 新建）沿祖先回溯，保持与 python `os.path.realpath` 同义。 */
function safeReal(target) {
    try {
        return fs.realpathSync(target);
    }
    catch {
        const parent = path.dirname(target);
        if (parent === target)
            return target;
        return path.join(safeReal(parent), path.basename(target));
    }
}
function resolvePath(p, mustWrite = false) {
    if (typeof p !== 'string' || !p)
        throw new Denied('path must be a non-empty string');
    const abs = safeReal(path.isAbsolute(p) ? p : path.join(ROOTS[0], p));
    const inside = ROOTS.some((r) => abs === r || abs.startsWith(r + path.sep));
    if (!inside)
        throw new Denied(`path escapes the authorised fs_roots: ${p}`);
    if (mustWrite && !WRITE)
        throw new Denied('write is not authorised for this execution (readonly profile)');
    return abs;
}
function walk(base, sink) {
    // 稳定序遍历（目录名/文件名均排序）+ 点开头条目（文件与目录）一律跳过——
    // 与 ptc-python 的 _walk_files 逐条同义（缺陷⑤）。
    const entries = fs.readdirSync(base, { withFileTypes: true })
        .filter((e) => !e.name.startsWith('.'))
        .sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
    for (const entry of entries) {
        const full = path.join(base, entry.name);
        if (entry.isDirectory())
            walk(full, sink);
        else
            sink.push(full);
    }
}
/** 段式 shell glob：`**` 跨段（含零段），`*`/`?`/`[...]` 段内（与 python _glob_match 同义）。 */
function segToRe(seg) {
    let out = '';
    for (let i = 0; i < seg.length; i += 1) {
        const c = seg[i];
        if (c === '*')
            out += '[^/]*';
        else if (c === '?')
            out += '[^/]';
        else if (c === '[') {
            const j = seg.indexOf(']', i + 1);
            if (j < 0)
                out += '\\[';
            else {
                const body = seg.slice(i + 1, j);
                out += `[${body.startsWith('!') ? `^${body.slice(1)}` : body}]`;
                i = j;
            }
        }
        else
            out += c.replace(/[.+^${}()|[\]\\]/g, '\\$&');
    }
    return new RegExp(`^${out}$`);
}
function globMatch(pattern, rel) {
    const pats = pattern.replace(/\\/g, '/').split('/').filter((s) => s !== '' && s !== '.');
    const segs = rel.split('/');
    const walkMatch = (pi, si) => {
        let p = pi;
        let s = si;
        while (p < pats.length) {
            if (pats[p] === '**') {
                if (p === pats.length - 1)
                    return true;
                for (let k = s; k <= segs.length; k += 1)
                    if (walkMatch(p + 1, k))
                        return true;
                return false;
            }
            if (s >= segs.length || !segToRe(pats[p]).test(segs[s]))
                return false;
            p += 1;
            s += 1;
        }
        return s === segs.length;
    };
    return walkMatch(0, 0);
}
const GLOB_MAX = 200;
const TOOLS = {
    read: {
        desc: 'Read a UTF-8 text file inside the authorised roots.',
        props: { path: { type: 'string' }, offset: { type: 'integer' }, limit: { type: 'integer' } },
        required: ['path'],
        run: (a) => {
            const p = resolvePath(a.path);
            let buf = fs.readFileSync(p).subarray(0, MAX_READ_BYTES + 1);
            let truncated = buf.length > MAX_READ_BYTES;
            buf = buf.subarray(0, MAX_READ_BYTES);
            const full = buf.toString('utf8');
            // 契约：text = 按行切片后的**原始子串**（行终止符随行保留）。按 "\n" 切（保留
            // 行内 "\r"），末元素在文件以 "\n" 结尾时是空串——与 python 版逐字同义。
            const pieces = full.split('\n');
            const endsNl = full.endsWith('\n');
            const total = endsNl && full !== '' ? pieces.length - 1 : pieces.length;
            const start = Math.min(Number(a.offset || 0) > 0 ? Number(a.offset) : 0, total);
            const end = Number(a.limit || 0) <= 0 ? total : Math.min(total, start + Number(a.limit));
            const selected = pieces.slice(start, end);
            let text = selected.join('\n');
            if (end === total && endsNl && total > 0)
                text += '\n';
            if (Buffer.byteLength(text, 'utf8') > MAX_VALUE_BYTES) {
                text = Buffer.from(text, 'utf8').subarray(0, MAX_VALUE_BYTES).toString('utf8');
                truncated = true;
            }
            return ok({ path: p, text, lines: selected.length }, truncated);
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
            if (a.mkdirs !== false)
                fs.mkdirSync(path.dirname(p), { recursive: true });
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
            if (typeof a.old !== 'string' || !a.old)
                return err('PTC_TOOL_ERROR', 'edit requires a non-empty string old');
            const text = fs.readFileSync(p, 'utf8');
            if (!text.includes(a.old))
                return err('PTC_TOOL_ERROR', `edit: 'old' text not found in ${p}`);
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
            const root = fs.statSync(base).isDirectory() ? base : path.dirname(base);
            const files = [];
            walk(root, files);
            const rel = files.map((f) => path.relative(root, f).split(path.sep).join('/'));
            const hits = rel.filter((r) => globMatch(String(a.pattern ?? '*'), r)).sort();
            const truncated = hits.length > GLOB_MAX;
            return ok({ paths: hits.slice(0, GLOB_MAX), base: root }, truncated);
        },
    },
    grep: {
        desc: 'Regex search file contents under a path; returns {path,line,text} rows (max 50).',
        props: { pattern: { type: 'string' }, path: { type: 'string' }, max_results: { type: 'integer' } },
        required: ['pattern'],
        run: (a) => {
            if (typeof a.pattern !== 'string' || !a.pattern)
                return err('PTC_TOOL_ERROR', "grep requires a 'pattern' string");
            let rx;
            try {
                rx = new RegExp(a.pattern);
            }
            catch (e) {
                return err('PTC_TOOL_ERROR', `grep: invalid regex: ${e.message}`);
            }
            const base = resolvePath(a.path ?? '.');
            const max = Number(a.max_results ?? 50);
            const isDir = fs.statSync(base).isDirectory();
            const targets = [];
            if (isDir) {
                const files = [];
                walk(base, files);
                for (const full of files)
                    targets.push({ rel: path.relative(base, full).split(path.sep).join('/'), full });
            }
            else
                targets.push({ rel: path.basename(base), full: base });
            const matches = [];
            for (const { rel, full } of targets) {
                let text;
                try {
                    if (fs.statSync(full).size > MAX_READ_BYTES)
                        continue;
                    text = fs.readFileSync(full, 'utf8');
                }
                catch {
                    continue;
                }
                const lines = text.split('\n');
                for (let i = 0; i < lines.length; i += 1) {
                    if (!rx.test(lines[i]))
                        continue;
                    const shown = lines[i].endsWith('\r') ? lines[i].slice(0, -1) : lines[i];
                    matches.push({ path: rel, line: i + 1, text: shown.slice(0, MAX_VALUE_BYTES) });
                }
            }
            matches.sort((x, y) => (x.path < y.path ? -1 : x.path > y.path ? 1 : x.line - y.line));
            const truncated = matches.length > max;
            const kept = matches.slice(0, max);
            return ok({ matches: kept, count: kept.length }, truncated);
        },
    },
    stat: {
        desc: 'Stat a path: exists/is_dir/size/mtime_ms.',
        props: { path: { type: 'string' } }, required: ['path'],
        run: (a) => {
            const p = resolvePath(a.path);
            if (!fs.existsSync(p))
                return ok({ path: p, exists: false, is_dir: false, size: 0, mtime_ms: 0 });
            const st = fs.statSync(p);
            return ok({ path: p, exists: true, is_dir: st.isDirectory(), size: st.size, mtime_ms: Math.floor(st.mtimeMs) });
        },
    },
    remove: {
        desc: 'Delete a file or (recursive=true) a directory. Read-write profile only.',
        props: { path: { type: 'string' }, recursive: { type: 'boolean' } }, required: ['path'],
        run: (a) => {
            const p = resolvePath(a.path, true);
            if (!fs.existsSync(p))
                return err('PTC_TOOL_ERROR', `remove: path does not exist: ${p}`);
            if (fs.statSync(p).isDirectory() && a.recursive !== true) {
                return err('PTC_TOOL_ERROR', `remove: ${p} is a directory (pass recursive=true)`);
            }
            fs.rmSync(p, { recursive: a.recursive === true, force: true });
            return ok({ path: p, removed: true });
        },
    },
};
function send(obj) { process.stdout.write(`${JSON.stringify(obj)}\n`); }
function callTool(name, args) {
    const t = TOOLS[name];
    if (!t)
        return err('PTC_TOOL_ERROR', `unknown tool: ${name}`);
    try {
        return t.run(args ?? {});
    }
    catch (e) {
        if (e instanceof Denied)
            return err('PTC_POLICY_FS', e.message);
        if (e?.code === 'ENOENT')
            return err('PTC_TOOL_ERROR', `not found: ${args?.path}`);
        return err('PTC_TOOL_ERROR', `${e?.name ?? 'Error'}: ${e?.message ?? String(e)}`);
    }
}
const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
rl.on('line', (line) => {
    if (!line.trim())
        return;
    let req;
    try {
        req = JSON.parse(line);
    }
    catch {
        return;
    }
    const { method, id } = req;
    if (method === 'initialize') {
        send({ jsonrpc: '2.0', id, result: { protocolVersion: PROTOCOL_VERSION, capabilities: { tools: {} }, serverInfo: { name: SERVER_NAME, version: SERVER_VERSION } } });
    }
    else if (method === 'tools/list') {
        send({ jsonrpc: '2.0', id, result: { tools: Object.entries(TOOLS).map(([name, t]) => ({ name, description: t.desc, inputSchema: { type: 'object', properties: t.props, required: t.required } })) } });
    }
    else if (method === 'tools/call') {
        const p = req.params ?? {};
        const env = callTool(p.name, p.arguments ?? {});
        send({ jsonrpc: '2.0', id, result: { content: [{ type: 'text', text: JSON.stringify(env) }], isError: env.ok !== true } });
    }
    else if (method === 'ping') {
        send({ jsonrpc: '2.0', id, result: {} });
    }
    else if (method?.startsWith?.('notifications/')) {
        /* no-op */
    }
    else if (id !== undefined) {
        send({ jsonrpc: '2.0', id, error: { code: -32601, message: `method not found: ${method}` } });
    }
});

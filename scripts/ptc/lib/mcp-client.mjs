#!/usr/bin/env node
/**
 * 最小 MCP stdio 客户端（零依赖）—— 与引擎的 `McpClient` 走同一条路径：
 * 读插件 mcp.json 的 server 描述 → spawn → initialize → notifications/initialized → tools/list → tools/call。
 *
 * 用途：① 契约一致性用例（run-conformance）；② PTC 最小闭环的宿主侧（closed-loop）。
 * 它**不是**引擎，只是"宿主半边"的可执行参照实现（引擎侧同形状：core/mcp/McpClient.scala）。
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';

export const PROTOCOL_VERSION = '2025-06-18';

/** 从 `<plugin>/mcp.json` 取 server 描述，展开 ${PLUGIN_ROOT} 之类的占位符。 */
export function serverSpec(pluginDir, serverName = 'ptc', overrides = {}) {
  // 测试/独立跑时的 PLUGIN_DATA 落系统临时目录，不要写进插件包根（包根未知条目会污染 digest）
  const fallbackData = path.join(os.tmpdir(), 'ptc-plugin-data', path.basename(pluginDir));
  const cfg = JSON.parse(fs.readFileSync(path.join(pluginDir, 'mcp.json'), 'utf8'));
  const spec = cfg.mcpServers[serverName];
  if (!spec) throw new Error(`mcp.json has no server '${serverName}' (found: ${Object.keys(cfg.mcpServers).join(', ')})`);
  const expand = (s) => String(s)
    .replaceAll('${PLUGIN_ROOT}', pluginDir)
    .replaceAll('${PLUGIN_DATA}', overrides.pluginData ?? fallbackData);
  const command = overrides.command ?? spec.command;
  const args = (spec.args ?? []).map(expand);
  return {
    command,
    args,
    timeoutMs: spec.timeoutMs ?? 600000,
    env: {
      ...process.env,
      PLUGIN_ROOT: pluginDir,
      PLUGIN_DATA: overrides.pluginData ?? fallbackData,
      ...(spec.env ?? {}),
    },
    cwd: overrides.cwd ?? pluginDir,
  };
}

export class McpStdioClient {
  constructor(spec, { label = 'mcp' } = {}) {
    this.label = label;
    this.nextId = 0;
    this.pending = new Map();
    this.buffer = '';
    this.stderr = [];
    this.proc = spawn(spec.command, spec.args, {
      cwd: spec.cwd, env: spec.env, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true,
    });
    this.proc.stdout.on('data', (d) => this._onData(d));
    this.proc.stderr.on('data', (d) => this.stderr.push(d.toString()));
    this.exited = new Promise((resolve) => this.proc.on('exit', (code, signal) => resolve({ code, signal })));
  }

  _onData(chunk) {
    this.buffer += chunk.toString('utf8');
    let idx;
    while ((idx = this.buffer.indexOf('\n')) >= 0) {
      const line = this.buffer.slice(0, idx);
      this.buffer = this.buffer.slice(idx + 1);
      if (!line.trim()) continue;
      let msg;
      try { msg = JSON.parse(line); } catch { continue; }
      const slot = this.pending.get(msg.id);
      if (slot) { this.pending.delete(msg.id); slot(msg); }
    }
  }

  request(method, params, timeoutMs = 600000) {
    const id = ++this.nextId;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`${this.label}: '${method}' timed out after ${timeoutMs}ms`)); }, timeoutMs);
      this.pending.set(id, (msg) => { clearTimeout(timer); resolve(msg); });
      this.proc.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
    });
  }

  notify(method, params) {
    this.proc.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method, params })}\n`);
  }

  async initialize() {
    const res = await this.request('initialize', {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: { name: 'stand-ptc-harness', version: '1.0.0' },
    }, 30000);
    this.notify('notifications/initialized', {});
    if (res.error) throw new Error(`${this.label}: initialize failed: ${res.error.message}`);
    return res.result;
  }

  async listTools() {
    const res = await this.request('tools/list', {}, 30000);
    return res.result?.tools ?? [];
  }

  /** 与引擎一致：content[].text 拼接；isError 一并回传，由上层决定判读。 */
  async callTool(name, args) {
    const res = await this.request('tools/call', { name, arguments: args }, 900000);
    if (res.error) return { isError: true, text: `[MCP Error ${res.error.code}] ${res.error.message}`, json: null };
    const text = (res.result?.content ?? []).filter((c) => typeof c.text === 'string').map((c) => c.text).join('\n');
    let json = null;
    try { json = JSON.parse(text); } catch { /* 非 JSON 结果（工具面工具）保持文本 */ }
    return { isError: Boolean(res.result?.isError), text, json };
  }

  async close() {
    try { this.proc.kill(); } catch { /* already gone */ }
    await Promise.race([this.exited, new Promise((r) => setTimeout(r, 2000))]);
  }
}

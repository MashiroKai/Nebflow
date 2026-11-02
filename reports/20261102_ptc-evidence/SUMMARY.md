# PTC 最小闭环 —— 实证摘要

生成时间：2026-11-02T06:42:37.770Z　脚本：`scripts/ptc/closed-loop.mjs`

## 总览

| 语言 | 插件 | 轮次 | PTC 执行次数 | 代码内用到的工具 | 代码内工具调用数 | 产物 | 闭环 |
|---|---|---|---|---|---|---|---|
| python | ptc-python | 3 | 2 | glob, read, grep, write | 7 | summary.json | ✅ |
| typescript | ptc-typescript | 3 | 2 | glob, read, grep, write | 7 | summary.json | ✅ |

## python（插件 `ptc-python`）

- 模型：`USTC/deepseek-v4-flash-ascend`　MCP 协议：`2025-06-18`
- 暴露给模型的工具：`mcp__plugin_ptc-python_ptc__ptc_run`, `mcp__plugin_ptc-python_ptc__ptc_run_rw`
- 总耗时：136696 ms

### 逐轮

| 轮 | finish_reason | 模型耗时(ms) | tokens | 代码执行 | 代码内工具台账 |
|---|---|---|---|---|---|
| 1 | tool_calls | 47567 | 2877 | ptc_run_rw (79ms) | glob, read, read, read, grep, write |
| 2 | tool_calls | 13297 | 3595 | ptc_run (64ms) | grep |
| 3 | stop | 75689 | 4370 | — | — |

### 模型生成的代码（原文）

```python
import json, os

# 1) 列文件：所有 .md（一次 glob，而非逐个手写路径）
found = tools.glob({"pattern": "**/*.md"})
paths = sorted(p for p in (found.get("paths") or found.get("matches") or []) if p.lower().endswith(".md"))
print("md files:", paths)

# 2) 检索 + 3) 读取 + 统计，循环在手内完成
todo_total = 0
per_file = {}
chars_total = 0
for p in paths:
    doc = tools.read({"path": p})
    text = doc.get("text", "")
    chars_total += len(text)
    n = sum(1 for line in text.splitlines() if "TODO" in line)
    if n:
        per_file[p] = n
    todo_total += n

# 顺便用 grep 交叉校验 TODO 行数
g = tools.grep({"pattern": "TODO", "path": ".", "glob": "**/*.md"})
grep_hits = g.get("count") if isinstance(g, dict) else None
print("grep count:", grep_hits)

summary = {
    "todo_lines": todo_total,
    "files": sorted(per_file.keys()),
    "chars": chars_total,
}
w = tools.write({"path": "summary.json", "content": json.dumps(summary, ensure_ascii=False, indent=2)})

{"summary": summary, "per_file": per_file, "grep_count": grep_hits, "written": w}
```

### 这一次 ptc_run 的响应（契约 responseschema 原样）

```json
{
  "contract": "ptc/v1",
  "exec_id": "ptc-9dddf30e",
  "language": "python",
  "profile": "readwrite",
  "stdout": "md files: ['notes/alpha.md', 'notes/beta.md', 'notes/gamma.md']\ngrep count: 4\n",
  "stderr": "",
  "stdout_truncated": false,
  "stderr_truncated": false,
  "tool_calls": [
    {
      "seq": 1,
      "tool": "glob",
      "args": {
        "pattern": "**/*.md"
      },
      "ok": true,
      "duration_ms": 0,
      "truncated": false,
      "result_bytes": 231
    },
    {
      "seq": 2,
      "tool": "read",
      "args": {
        "path": "notes/alpha.md"
      },
      "ok": true,
      "duration_ms": 0,
      "truncated": false,
      "result_bytes": 278
    },
    {
      "seq": 3,
      "tool": "read",
      "args": {
        "path": "notes/beta.md"
      },
      "ok": true,
      "duration_ms": 0,
      "truncated": false,
      "result_bytes": 287
    },
    {
      "seq": 4,
      "tool": "read",
      "args": {
        "path": "notes/gamma.md"
      },
      "ok": true,
      "duration_ms": 0,
      "truncated": false,
      "result_bytes": 271
    },
    {
      "seq": 5,
      "tool": "grep",
      "args": {
        "pattern": "TODO",
        "path": ".",
        "glob": "**/*.md"
      },
      "ok": true,
      "duration_ms": 0,
      "truncated": false,
      "result_bytes": 411
    },
    {
      "seq": 6,
      "tool": "write",
      "args": {
        "path": "summary.json",
        "content": "{\n  \"todo_lines\": 3,\n  \"files\": [\n    \"notes/alpha.md\",\n    \"notes/beta.md\",\n    \"notes/gamma.md\"\n  ],\n  \"chars\": 163\n}"
      },
      "ok": true,
      "duration_ms": 0,
      "truncated": false,
      "result_bytes": 212
    }
  ],
  "tool_call_count": 6,
  "files_written": [
    "summary.json"
  ],
  "value": {
    "summary": {
      "todo_lines": 3,
      "files": [
        "notes/alpha.md",
        "notes/beta.md",
        "notes/gamma.md"
      ],
      "chars": 163
    },
    "per_file": {
      "notes/alpha.md": 1,
      "notes/beta.md": 1,
      "notes/gamma.md": 1
    },
    "grep_count": 4,
    "written": {
      "path": "D:\\workspace@zcode\\nebflow\\nebflow-stand\\.nebflow\\worktrees\\delegate-nebflow-226f28aa\\reports\\20261102_ptc-evidence\\python\\plugin-data\\workspace\\summary.json",
      "bytes": 119,
      "created": true
    }
  },
  "clamped": [],
  "duration_ms": 77,
  "usage": {
    "wall_ms": 77,
    "cpu_ms": null,
    "max_rss_kb": 2552,
    "tool_calls": 6,
    "output_bytes": 78
  },
  "ok": true,
  "error": null
}
```

### 产物

```json
{
  "todo_lines": 3,
  "files": [
    "notes/alpha.md",
    "notes/beta.md",
    "notes/gamma.md"
  ],
  "chars": 163
}
```

### 模型最终回复

```
✅ 完成。

一次代码执行（`ptc_run_rw`，6 次工具调用：1 次 glob 列出文件 → 3 次 read 读取并统计 → 1 次 grep 校验 → 1 次 write 写文件）的结果：

| 项 | 值 |
|---|---|
| 含 TODO 的行数 | **3**（notes/alpha.md 1 行、notes/beta.md 1 行、notes/gamma.md 1 行） |
| .md 文件字符数合计 | **163** |
| 产出 | `/summary.json`（119 字节，已落盘） |

两点说明：
- **grep 的 `glob` 过滤没生效**（`**/*.md` 仍把 `logs/ignore.log` 的第 1 行「mentions TODO once」计了进去，得到 count=4）；因此统计口径按 `.md` 扩展名精确过滤，去掉非 md 那条后恰好 3 条，与逐文件读取结果完全吻合。
- `summary.json` 的 `files` 只列真正含 TODO 的几个 md 文件（本例中恰好等于全部 md 文件）。

**一句话回报：** 三个 md 文件共 163 字符，各含 1 行 TODO（合计 3 行），已写入根目录 `summary.json`。
```

## typescript（插件 `ptc-typescript`）

- 模型：`USTC/deepseek-v4-flash-ascend`　MCP 协议：`2025-06-18`
- 暴露给模型的工具：`mcp__plugin_ptc-typescript_ptc__ptc_run`, `mcp__plugin_ptc-typescript_ptc__ptc_run_rw`
- 总耗时：151210 ms

### 逐轮

| 轮 | finish_reason | 模型耗时(ms) | tokens | 代码执行 | 代码内工具台账 |
|---|---|---|---|---|---|
| 1 | tool_calls | 86963 | 3148 | ptc_run_rw (116ms) | — |
| 2 | tool_calls | 48783 | 3763 | ptc_run_rw (119ms) | glob, read, read, read, grep, write, read |
| 3 | stop | 15229 | 4723 | — | — |

### 模型生成的代码（原文）

```js
const md = tools.glob({ pattern: '**/*.md' });
// glob may return {paths:[]} or array-like; normalize
const paths: string[] = (md as any).paths || (md as any).files || (Array.isArray(md) ? md as any : []);
console.log('glob keys:', Object.keys(md as any).join(','));
console.log('md files:', JSON.stringify(paths));

const perFile: {file: string, todoLines: number, chars: number}[] = [];
let totalTodo = 0, totalChars = 0;
for (const p of paths) {
  const doc = tools.read({ path: p });
  const text: string = (doc as any).text ?? (doc as any).content ?? '';
  const lines = text.split('\n');
  const n = lines.filter(l => l.includes('TODO')).length;
  perFile.push({ file: p, todoLines: n, chars: text.length });
  totalTodo += n;
  totalChars += text.length;
}

// cross-check with grep
const g = tools.grep({ pattern: 'TODO', path: '.' });
console.log('grep count:', (g as any).count, 'matches:', JSON.stringify(((g as any).matches || []).map((m: any) => m.file ?? m.path)));

console.log(JSON.stringify(perFile, null, 2));

const filesWithTodo = perFile.filter(f => f.todoLines > 0).map(f => f.file).sort();
const summary = { todo_lines: totalTodo, files: filesWithTodo, chars: totalChars };
const w = tools.write({ path: 'summary.json', content: JSON.stringify(summary, null, 2) + '\n' });
console.log('written:', JSON.stringify(w));
({ summary, perFile, grepCount: (g as any).count });
```

### 这一次 ptc_run 的响应（契约 responseschema 原样）

```json
{
  "contract": "ptc/v1",
  "exec_id": "ptc-8289a5dd",
  "language": "typescript",
  "profile": "readwrite",
  "stdout": "",
  "stderr": "",
  "stdout_truncated": false,
  "stderr_truncated": false,
  "tool_calls": [],
  "tool_call_count": 0,
  "files_written": [],
  "value": null,
  "clamped": [],
  "duration_ms": 115,
  "usage": {
    "wall_ms": 115,
    "cpu_ms": null,
    "max_rss_kb": null,
    "tool_calls": 0,
    "output_bytes": 0
  },
  "ok": false,
  "error": {
    "category": "SYNTAX_ERROR",
    "code": "PTC_SYNTAX",
    "phase": "compile",
    "message": "Missing initializer in const declaration",
    "tool": null,
    "detail": {
      "type": "SyntaxError",
      "stack": "<ptc>:3\nconst paths: string[] = (md as any).paths || (md as any).files || (Array.isArray(md) ? md as any : []);\n      ^^^^^\n\nSyntaxError: Missing initializer in const declaration\n    at new Script (node:vm:117:7)\n    at main (file:///D:/workspace@zcode/nebflow/nebflow-stand/.nebflow/worktrees/delegate-nebflow-226f28aa/plugins/ptc-typescript/skills/ptc-typescript/dist/ptc_runner.js:233:9)\n    at file:///D:/workspace@zcode/nebflow/nebflow-stand/.nebflow/worktrees/delegate-nebflow-226f28aa/plugins/ptc-typescript/skills/ptc-typescript/dist/ptc_runner.js:312:11\n    at ModuleJob.run (node:internal/modules/esm/module_job:325:25)\n    at async ModuleLoader.import (node:internal/modules/esm/loader:606:24)\n    at async asyncRunEntryPointWithESMLoader (node:internal/modules/run_main:117:5)"
    }
  }
}
```

### 产物

```json
{
  "todo_lines": 3,
  "files": [
    "notes/alpha.md",
    "notes/beta.md",
    "notes/gamma.md"
  ],
  "chars": 147
}
```

### 模型最终回复

```
notes/ 目录三份 `.md` 文件共 3 行含 TODO（alpha.md、beta.md、gamma.md 各 1 行；`logs/ignore.log` 里另有 1 处 TODO 已按「只看 .md」的约束排除），字符数合计 147，已写入 `summary.json` 并回读校验一致。
```


/**
 * handlers.ts — 工具执行实现（参考 Claude Code 的硬编码模式）
 *
 * 没有 ExecutorRegistry，没有接口，没有动态注册。
 * 直接 switch/case 匹配工具名，调用对应函数。
 */

import { readFileSync, writeFileSync, existsSync, readdirSync } from "node:fs";
import { join, resolve } from "node:path";
import { execSync } from "node:child_process";
import type { ToolCall } from "../shared/protocol.js";

function resolvePath(inputPath: string, projectRoot: string): string {
  if (inputPath.startsWith("/")) return inputPath;
  return resolve(projectRoot, inputPath);
}

function readFile(path: string, projectRoot: string): string {
  const fullPath = resolvePath(path, projectRoot);
  if (!existsSync(fullPath)) return `文件不存在: ${path}`;
  try {
    return readFileSync(fullPath, "utf-8");
  } catch (e) {
    return `读取失败: ${e instanceof Error ? e.message : String(e)}`;
  }
}

function writeFile(path: string, content: string, projectRoot: string): string {
  const fullPath = resolvePath(path, projectRoot);
  try {
    writeFileSync(fullPath, content, "utf-8");
    return `已写入: ${path}`;
  } catch (e) {
    return `写入失败: ${e instanceof Error ? e.message : String(e)}`;
  }
}

function bash(command: string, timeout = 30000): string {
  try {
    const result = execSync(command, {
      encoding: "utf-8",
      timeout,
      cwd: process.cwd(),
    });
    return result || "[命令执行成功，无输出]";
  } catch (e: any) {
    return `执行失败: ${e.message}\n${e.stderr || ""}`;
  }
}

function glob(pattern: string, projectRoot: string): string {
  // 支持 *.ext 和 **/*.ext 两种简单模式
  const ext = pattern.startsWith("**/") ? pattern.slice(3) : pattern;
  const isWild = ext.startsWith("*");
  const suffix = isWild ? ext.slice(1) : ext;
  const results: string[] = [];

  function walk(dir: string) {
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch { return; }
    for (const entry of entries) {
      if (entry.name === "node_modules" || entry.name === ".git") continue;
      const fullPath = join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(fullPath);
      } else if (isWild ? entry.name.endsWith(suffix) : entry.name === suffix) {
        results.push(fullPath.replace(projectRoot + "/", ""));
      }
    }
  }

  walk(projectRoot);
  return results.join("\n") || "无匹配文件";
}

function grep(pattern: string, searchPath: string, projectRoot: string): string {
  let regex: RegExp;
  try {
    regex = new RegExp(pattern);
  } catch (e) {
    return `无效的正则表达式: ${e instanceof Error ? e.message : String(e)}`;
  }

  const results: string[] = [];

  function walk(dir: string) {
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch { return; }
    for (const entry of entries) {
      if (entry.name === "node_modules" || entry.name === ".git") continue;
      const fullPath = join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(fullPath);
      } else {
        try {
          const content = readFileSync(fullPath, "utf-8");
          const lines = content.split("\n");
          lines.forEach((line, idx) => {
            if (regex.test(line)) {
              results.push(`${fullPath.replace(projectRoot + "/", "")}:${idx + 1}: ${line.trim()}`);
            }
          });
        } catch { /* 二进制文件等跳过 */ }
      }
    }
  }

  const target = searchPath ? resolvePath(searchPath, projectRoot) : projectRoot;
  walk(target);
  return results.slice(0, 50).join("\n") || "无匹配结果";
}

export async function executeTool(
  call: ToolCall,
  projectRoot: string,
): Promise<string> {
  switch (call.name) {
    case "read_file":
      return readFile(call.input.path as string, projectRoot);
    case "write_file":
      return writeFile(call.input.path as string, call.input.content as string, projectRoot);
    case "bash":
      return bash(call.input.command as string, (call.input.timeout as number) || 30000);
    case "glob":
      return glob(call.input.pattern as string, projectRoot);
    case "grep":
      return grep(call.input.pattern as string, (call.input.path as string) || ".", projectRoot);
    default:
      return `未知工具: ${call.name}`;
  }
}

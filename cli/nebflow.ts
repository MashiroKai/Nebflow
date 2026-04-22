/**
 * nebflow.ts — CLI 入口
 *
 * 两种模式：
 * 1. 单次调用：`nebflow "帮我分析一下 package.json"`
 * 2. 交互式 REPL：`nebflow`
 */

import { createInterface } from "node:readline";
import { join, dirname } from "node:path";
import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { createLlm } from "../llm/interface.js";
import { runRepl } from "../core/repl.js";

const args = process.argv.slice(2);

const __dirname = dirname(fileURLToPath(import.meta.url));

/** 查找 .nebflow/nebflow.json 配置文件路径 */
function findConfigPath(startDir = process.cwd()): string | null {
  let dir = startDir;
  while (true) {
    const path = join(dir, ".nebflow", "nebflow.json");
    if (existsSync(path)) return path;
    const parent = join(dir, "..");
    if (parent === dir) break;
    dir = parent;
  }
  return null;
}

async function main() {
  const configPath = findConfigPath();

  if (!configPath) {
    console.error("未找到 .nebflow/nebflow.json。请在项目根目录运行，或创建 .nebflow/nebflow.json。");
    process.exit(1);
  }

  const projectRoot = process.cwd();

  const llm = createLlm({ configPath });

  // === 模式 1：单次调用 ===
  if (args.length > 0) {
    const input = args.join(" ");
    try {
      await runRepl(input, llm, projectRoot);
    } catch (e) {
      console.error("错误:", e instanceof Error ? e.message : String(e));
      process.exit(1);
    }
    return;
  }

  // === 模式 2：交互式 REPL ===
  console.log("Nebflow — 输入你的问题，或按 Ctrl+C 退出\n");

  const rl = createInterface({
    input: process.stdin,
    output: process.stdout,
    prompt: "> ",
  });

  rl.prompt();

  rl.on("line", async (line) => {
    const input = line.trim();
    if (!input) {
      rl.prompt();
      return;
    }

    try {
      await runRepl(input, llm, projectRoot);
      console.log("\n");
    } catch (e) {
      console.error("\n错误:", e instanceof Error ? e.message : String(e));
    }

    rl.prompt();
  });

  rl.on("close", () => {
    console.log("\n再见");
    process.exit(0);
  });
}

main();

#!/usr/bin/env node
/**
 * motion-toolkit · Remotion 渲染封装
 *
 * 用法（在本文件所在目录或任意 cwd 均可）:
 *   node render-remotion.mjs <projectDir> <compositionId> <out.mp4|png|gif> [remotion 附加参数...]
 * 例:
 *   node render-remotion.mjs remotion-scaffold MotionSmoke C:/Users/Kai/.nebflow/plots/motion-toolkit-smoke/remotion_smoke.mp4
 *   node render-remotion.mjs remotion-scaffold MotionSmoke out.mp4 --props="--title=另一标题"  (props JSON 见 Remotion 文档)
 *
 * 固化行为（来自 2026-10-04 环境实测，勿凭记忆改）:
 *   - 浏览器必须 --browser-executable 指本机 Chrome（chrome-headless-shell 在本网络下载 ~17KB/s 不可用）;
 *   - 覆盖浏览器路径: 设环境变量 REMOTION_BROWSER_EXECUTABLE;
 *   - npx 拉 remotion CLI; 安装依赖用 remotion-scaffold/setup.cmd（npmmirror 镜像）。
 */
import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import path from "node:path";

const candidates = [
  process.env.REMOTION_BROWSER_EXECUTABLE,
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
].filter(Boolean);
const browser = candidates.find((p) => existsSync(p));

const [projectDir, compositionId, outPath, ...extra] = process.argv.slice(2);
if (!projectDir || !compositionId || !outPath) {
  console.error(
    "用法: node render-remotion.mjs <projectDir> <compositionId> <out.mp4|png|gif> [remotion 附加参数...]",
  );
  process.exit(2);
}
if (!browser) {
  console.error(
    "未找到本机 Chrome/Edge，设 REMOTION_BROWSER_EXECUTABLE=<浏览器exe绝对路径> 后重试。",
  );
  process.exit(2);
}
if (!existsSync(path.join(path.resolve(projectDir), "src", "index.ts"))) {
  console.error(`projectDir 下无 src/index.ts: ${path.resolve(projectDir)}`);
  process.exit(2);
}

const args = [
  "remotion",
  "render",
  "src/index.ts",
  compositionId,
  outPath,
  "--browser-executable",
  browser,
  "--log=error",
  ...extra,
];
// Windows 下 npx 必须 shell:true，而 shell 模式不做任何加引号 —— 含空格参数会被拆断，逐个手动加引号。
const q = (s) => (/[\s"]/.test(s) ? `"${s.replace(/"/g, '\\"')}"` : s);
console.log(`[render-remotion] cwd=${path.resolve(projectDir)}\n  npx ${args.map(q).join(" ")}`);
const r = spawnSync("npx", args.map(q), {
  cwd: path.resolve(projectDir),
  stdio: "inherit",
  shell: true,
});
process.exit(r.status ?? 1);

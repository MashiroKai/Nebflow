// social-html-shot.mjs — 社交出站「截图兜底腿」：html 产物 → 整页 PNG（social-webshot batch, 2026-10-09；
// 同日用户裁定后降为兜底：html 现以文件消息直发，仅文件腿发送失败才走本截图）。
//
// 被谁用：src/main/scala/nebflow/social/outbound/WebPageShot.scala（NodePlaywright 实现）
// 以 `node scripts/social-html-shot.mjs <input.html> <output.png> [width]` 子进程调用。
//
// 🔴 playwright 从仓库根 node_modules 解析（与 tests/*.spec.mjs 同款依赖，本仓库
// 根 package.json/node_modules 提供）——ESM 解析按【脚本所在目录】向上找
// node_modules，所以本脚本必须留在仓内：脚本被移出仓库 ⇒ 解析不到 playwright
// ⇒ Node 非零退出 ⇒ Scala 侧按「渲染件不可用」降级到 b 层友好文案，不会崩。
//
// 契约：
//   成功：写 outPng（fullPage 整页截图，尺寸由页面内容决定），stdout 一行
//         `ok <outPng>`，exit 0；
//   失败：stderr 带原因，exit 非 0（Scala 侧把非零/超时归入封闭原因词汇表，
//         用户侧永远只见友好中文一行，机制细节进日志）。
//
// 用法：node scripts/social-html-shot.mjs <input.html> <output.png> [width]
//   width 视口宽，缺省 900，夹在 [480, 2400]；高度不设限（fullPage）。
//   暗色页面按页面自己的配色渲染（本腿不做主题改写——「含暗色渲染即可」）。

import { chromium } from 'playwright';
import { pathToFileURL } from 'node:url';

const [, , input, output, widthArg] = process.argv;
if (!input || !output) {
  console.error('usage: node scripts/social-html-shot.mjs <input.html> <output.png> [width]');
  process.exit(2);
}

const timeoutMs = Number(process.env.SHOT_TIMEOUT_MS || 25000);
const parsedWidth = Number.parseInt(widthArg ?? '900', 10);
const width = Math.min(Math.max(Number.isFinite(parsedWidth) ? parsedWidth : 900, 480), 2400);

const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width, height: 900 } });
  // file:// 加载原件：相对引用的同目录资源（snapshot-data.js、图片等）按原件
  // 位置解析——这正是要传「文件路径」而不是 HTML 字符串的原因。
  await page.goto(pathToFileURL(input).href, { waitUntil: 'load', timeout: timeoutMs });
  await page.waitForTimeout(250); // 给晚到的布局/字体一拍稳定时间
  await page.screenshot({ path: output, fullPage: true, timeout: timeoutMs });
  console.log(`ok ${output}`);
} finally {
  await browser.close();
}

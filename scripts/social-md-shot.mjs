// social-md-shot.mjs — 社交出站「文件类长图腿」：md/markdown 产物 → 单张长图 PNG
// (pop-social-files batch, 2026-10-20；作者令：Pop 的文件类产物投递社交腿 =
//  md/html→单张长图 PNG，转不动降级「文件名+本地路径+一句话摘要」文本卡)。
//
// 被谁用：src/main/scala/nebflow/social/outbound/FileLongShot.scala（NodeShot 的
// md 腿）以 `node scripts/social-md-shot.mjs <input.md> <output.png> [width]`
// 子进程调用；html/htm 走同款 sibling 脚本 social-html-shot.mjs。
//
// 🔴 marked.min.js 从仓库前端 vendor 面解析（src/main/resources/web/vendor/
//    marked.min.js，与聊天渲染同一份 marked v12——同一渲染口径，不新造第二份）。
//    发现顺序：环境变量 NEBFLOW_MARKED_JS > 从本脚本所在目录向上 ≤6 级找仓库
//    vendor 面。找不到 ⇒ exit 3 ⇒ Scala 侧按封闭原因词汇降级文本卡，不会崩。
//
// 契约（与 social-html-shot.mjs 完全同款）：
//   成功：写 outPng（fullPage 整页截图，尺寸由内容决定），stdout 一行
//         `ok <outPng>`，exit 0；
//   失败：stderr 带原因，exit 非 0（Scala 侧把非零/超时归入封闭原因词汇表，
//         用户侧只见「文件名+本地路径+一句话摘要」文本卡）。
//
// 相对引用：临时 HTML 落在输出目录（数据根 tmp 面），内嵌 `<base href="file://
// <md 所在目录>/>"` —— md 里的相对图片/链接按原件位置解析，原件目录零写入。
// 暗色页面按页面自己配色渲染（本腿不做主题改写）。

import { chromium } from 'playwright';
import { createRequire } from 'node:module';
import { existsSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const [, , input, output, widthArg] = process.argv;
if (!input || !output) {
  console.error('usage: node scripts/social-md-shot.mjs <input.md> <output.png> [width]');
  process.exit(2);
}

const timeoutMs = Number(process.env.SHOT_TIMEOUT_MS || 25000);
const parsedWidth = Number.parseInt(widthArg ?? '900', 10);
const width = Math.min(Math.max(Number.isFinite(parsedWidth) ? parsedWidth : 900, 480), 2400);

function findMarked() {
  const rel = join('src', 'main', 'resources', 'web', 'vendor', 'marked.min.js');
  if (process.env.NEBFLOW_MARKED_JS && existsSync(process.env.NEBFLOW_MARKED_JS)) {
    return process.env.NEBFLOW_MARKED_JS;
  }
  let dir = dirname(fileURLToPath(import.meta.url)); // <repo>/scripts
  for (let i = 0; i < 6; i++) {
    const cand = join(dir, rel);
    if (existsSync(cand)) return cand;
    const up = dirname(dir);
    if (up === dir) break;
    dir = up;
  }
  return null;
}

const markedPath = findMarked();
if (!markedPath) {
  console.error('marked.min.js not found (NEBFLOW_MARKED_JS unset; walked up from scripts/)');
  process.exit(3);
}
// marked v12 ships a UMD build: CommonJS-compatible, load through createRequire.
const { marked } = createRequire(import.meta.url)(markedPath);

const md = readFileSync(input, 'utf8');
const body = marked.parse(md, { async: false, gfm: true, breaks: true });

// GitHub-ish readability styles: system font, code blocks, tables, quotes.
const style = `
  :root { color-scheme: light; }
  * { box-sizing: border-box; }
  body { margin: 0; padding: 32px 36px; background: #ffffff; color: #1f2328;
         font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC",
                      "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
         font-size: 16px; line-height: 1.65; word-wrap: break-word; }
  h1, h2, h3, h4, h5, h6 { line-height: 1.3; margin: 24px 0 12px; font-weight: 600; }
  h1 { font-size: 28px; padding-bottom: 8px; border-bottom: 1px solid #d1d9e0; }
  h2 { font-size: 22px; padding-bottom: 6px; border-bottom: 1px solid #d1d9e0; }
  h3 { font-size: 18px; }
  p { margin: 0 0 12px; }
  a { color: #0969da; text-decoration: none; }
  code { font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace;
         font-size: 85%; background: #f6f8fa; padding: 2px 5px; border-radius: 5px; }
  pre { background: #f6f8fa; padding: 14px 16px; border-radius: 8px; overflow: visible;
        margin: 0 0 14px; line-height: 1.5; }
  pre code { background: transparent; padding: 0; font-size: 13px; }
  blockquote { margin: 0 0 14px; padding: 0 14px; color: #59636e;
               border-left: 4px solid #d1d9e0; }
  table { border-collapse: collapse; margin: 0 0 14px; display: block; max-width: 100%;
          overflow-x: visible; }
  th, td { border: 1px solid #d1d9e0; padding: 6px 12px; }
  th { background: #f6f8fa; font-weight: 600; }
  img { max-width: 100%; }
  hr { border: 0; height: 1px; background: #d1d9e0; margin: 20px 0; }
  ul, ol { margin: 0 0 14px; padding-left: 26px; }
  li { margin: 3px 0; }
`;

const baseHref = `${pathToFileURL(resolve(input)).href}`.replace(/\/[^/]*$/, '/');
const pageHtml = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<base href="${baseHref}">
<style>${style}</style>
</head>
<body>
<article class="md">${body}</article>
</body>
</html>`;

const tmpHtml = `${output.replace(/\.png$/i, '')}.auto.html`;
writeFileSync(tmpHtml, pageHtml);

const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width, height: 900 } });
  await page.goto(pathToFileURL(tmpHtml).href, { waitUntil: 'load', timeout: timeoutMs });
  await page.waitForTimeout(250); // 给晚到的布局/字体一拍稳定时间
  await page.screenshot({ path: output, fullPage: true, timeout: timeoutMs });
  console.log(`ok ${output}`);
} finally {
  await browser.close();
  try { rmSync(tmpHtml, { force: true }); } catch {}
}

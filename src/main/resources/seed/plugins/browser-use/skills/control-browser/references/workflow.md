# 一次交互任务的完整步骤（workflow）

下面的骨架按「一次调用一到两件事」设计：每轮脚本重新建立、每轮把**观测结果打出来**给下一轮用。

## 第 0 步：自检载体

```bash
node -e "console.log('playwright', require('playwright/package.json').version)"
ls ~/Library/Caches/ms-playwright 2>/dev/null
```

缺失 ⇒ 停手报确切错误。**不要**改用别的手段假装完成了页面工作。

## 第 1 步：起被测服务（若需要）

被测面是本地页面时，用**自有端口 + 自有目录**：

```bash
.nebflow/tools/wave-janitor.sh roster --node <nodeId> --tag httpd -- \
  python3 -m http.server <自有端口> --directory <静态根>
```

已被别的进程起的服务（比如开发 server 已经跑着）⇒ 直接用，别重复起。
**宿主的 `:8080` 网关监听者永不触碰。**

## 第 2 步：开页面并确认加载

```js
// step2-open.mjs —— 打开 + 确认加载 + 打出目标事实
// 🔴 脚本文件要落在**能解析 `playwright` 的目录内**（放仓库内，如 <ws>/.nebflow/tmp/）：
//    模块解析沿脚本所在路径向上找 node_modules；放 /tmp 之类仓库外目录 ⇒
//    `ERR_MODULE_NOT_FOUND: Cannot find package 'playwright'`
import { chromium } from 'playwright';

const [base] = process.argv.slice(2);
const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
const page = await context.newPage();

await page.goto(base, { waitUntil: 'domcontentloaded' });
await page.waitForLoadState('domcontentloaded');   // 显式确认，不用 networkidle、不用固定 sleep

// 只打「事实」：标题、URL、以及你要用的目标的可见性/数量
console.log(JSON.stringify({
  url: page.url(),
  title: await page.title(),
}, null, 2));

await browser.close();
```

**不要**在这一步同时拍快照又拍截图；先拿结构事实。

## 第 3 步：从观测事实里挑目标，做**一个**状态变更动作

- 目标必须出现在第 2 步（或上一次）的实际输出里 —— **不许猜**。
- 唯一性不是显然的 ⇒ 先 `count()`，按结果分支（0 重观测 / >1 收紧作用域）。
- 一轮只做一个会改状态的动作。

```js
// step3-act.mjs
const target = page.getByRole('button', { name: '<观测里逐字出现过的可访问名>' });
const n = await target.count();                     // 先数
if (n !== 1) { console.log(JSON.stringify({ count: n })); await browser.close(); process.exit(0); }
await target.click();
await page.waitForLoadState('domcontentloaded');     // 若有导航
// 取「能回答下一个问题的最便宜观测」
console.log(JSON.stringify({ url: page.url(), title: await page.title() }, null, 2));
```

## 第 4 步：判成功 / 判失败

- **看期望效果是否出现**，不看「有没有报错」「列表是不是非空」。
- 期望效果可能在别处出现（新页/新状态）⇒ **同一轮**把两个来源都读出来再决策。
- 失败 ⇒ 见 `troubleshooting.md`：**不要原样重试**。

## 第 5 步：视觉证据（需要时）

```js
// step5-shot.mjs —— 截图必须落盘，并把绝对路径打出来
import { resolve } from 'node:path';
const out = resolve(process.argv[3], 't1_after.png');
await page.screenshot({ path: out });
console.log(JSON.stringify({ shot: out }));
```

然后用 `Read` **读这个图片文件**。报告里引用该绝对路径（或 `file://` URI）。

## 第 6 步：收尾

- 只关你**有意**开的页面/上下文；`await browser.close()` 结束本次脚本持有的浏览器。
- 自起的被测服务收尾自清（`trap EXIT`），并给**清理后现读**。
- 报告里写清：跑了什么、读数是什么、哪些没跑成、为什么。

## 反模式（见到就停）

| 反模式 | 为什么错 |
|---|---|
| 一次脚本里塞进整个测试矩阵 | 失败后无法定位是哪一步；一次调用一个决策点 |
| 猜一个选择器「试试」 | 猜出来的东西不是探针，是把失败甩给页面 |
| 定位失败后原样重试 | 页面事实没变，重试只会再失败（而且浪费时间盒） |
| `waitForTimeout(3000)` 当常规等待 | 掩盖了真实条件；优先等具体状态 |
| 截图不落盘 / 落盘不读 | 视觉结论无证据 |
| 用 `:8080` 或别人的端口做被测面 | 越界；自有端口是硬要求 |

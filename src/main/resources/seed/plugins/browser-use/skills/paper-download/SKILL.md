---
name: paper-download
description: 论文批量下载工作流——输入 DOI/URL 列表，逐篇串行处理：打开文章页、识别登录墙与反爬拦截、按观测事实定位 PDF 链接/按钮、拟人点击并捕获下载（回退到带浏览器 cookie 的请求与直接导航）、%PDF- 头与体积校验后落盘，逐篇输出六态状态（SUCCESS/NEEDS_LOGIN/BLOCKED_ANTIBOT/NO_PDF/INVALID_PDF/DOWNLOAD_FAILED）并落盘报告。Use when 任务需要按清单批量获取论文 PDF、需要逐篇状态与失败分类、需要把下载件校验后归档到指定目录。
when_to_use: 给定 DOI/URL 列表要批量下载论文 PDF；单篇下载并校验；需要区分"要登录/被反爬拦/没有 PDF/下载失败"四类失败的场景。不需要：网页交互测试（control-browser）；登录态本身的建立（先跑 browser-session 登录流程，再回来重跑剩余条目）。
audience: nebflow-project
language: zh
status: active
---

# Paper Download — 论文下载工作流

载体与会话来自 **browser-session**（同一包内：有头 Chrome + 持久 profile；会话不在时本工作流
会自动启动）。本 skill 管的是**清单到文件**的流程纪律与逐篇状态机；定位与失败姿态沿
control-browser 规约（观测即真相、禁猜选择器、两次失败即停）。

## 一、输入与调用

清单文件（JSON 数组或 `{items:[...]}`），每条目 `{id?, doi?, url?}` 至少其一：

```json
[
  {"id": "lssr-2023", "doi": "10.1016/j.lssr.2023.01.003"},
  {"id": "some-article", "url": "https://example.com/article/123"}
]
```

```bash
node ${SKILL_DIR}/scripts/paper-download.mjs --list <清单.json> --out-dir <输出目录> \
  --session paper --report <报告路径>
# 单篇：--doi <doi> 或 --url <url>；--min-bytes/--timeout-ms/--min-delay-ms/--max-delay-ms 可调
```

行为：会话不在 ⇒ 自动启动有头 Chrome；串行处理；**每篇处理完立即把报告写盘再继续**
（中断也有已完成部分的状态）；篇间随机延时（默认 5-15s）；结束默认关会话
（`--keep-open` 保留，便于接着跑 NEEDS_LOGIN 的登录流程）。

## 二、逐篇流程与状态机

每篇恰好一个终态，逐篇即时打印 `PAPER i/n id=… status=…`：

| 状态 | 含义 | 下一步（你的责任） |
|---|---|---|
| SUCCESS | 已校验落盘（%PDF- 头 + ≥min-bytes） | 记录文件路径 |
| NEEDS_LOGIN | 页面或返回内容呈登录墙 | 跑 browser-session 登录流程，完成后重跑该篇 |
| BLOCKED_ANTIBOT | 识别到反爬拦截页（cloudflare/perimeterx/datadome/文案特征） | 如实报告；请用户在可见窗口人工通过后重跑；不得假装成功 |
| NO_PDF | 页面上未观测到任何 PDF 目标 | 换入口页/人工确认该文是否开放全文；不猜链接 |
| INVALID_PDF | 拿到内容但不是有效 PDF（体积/头不符） | 看报告 detail 与截图，人工判断入口 |
| DOWNLOAD_FAILED | 导航失败/三条获取路径全部未取得内容 | 报确切错误；重跑单篇而非整单 |

DOI 条目经 `https://doi.org/<doi>` 解析跳转。**curl 已能直取的公开直链场景不归本工作流**
（浏览器留给登录态/反爬/点击交互三类不可替代场景）。

## 三、PDF 目标定位（观测即真相）

只从当次页面 DOM 收集事实，按稳定度排序取用：

1. `meta[name=citation_pdf_url]`（学术出版页通用元数据，最稳）；
2. `a[href]` 以 `.pdf` 结尾（精确 href）；
3. 可见文案含 PDF 的链接（`PDF` / `View PDF` / `下载全文` 等）；
4. 已知直链形态（如 ScienceDirect 的 `pdfft` 路径）——标注为线索，仍以观测命中为准。

命中后按序获取：**拟人点击 + CDP 下载捕获**（下载行为被指到会话 staging 目录，捕获新文件）
→ **带浏览器 cookie 的上下文请求**（UA/cookie 与浏览器一致，适配点击无下载事件的站点）
→ **直接导航取响应体**。三条路径取得的字节都过同一校验；非 PDF 的 HTML 内容按特征
二次分流为 NEEDS_LOGIN / BLOCKED_ANTIBOT / INVALID_PDF，不笼统报失败。

**入口即 PDF 的直链**（URL 本身指向 .pdf）：导航后按响应 content-type 识别为直链，
取数优先走上下文请求——Chrome 会用内置查看器渲染 PDF，此时页面级响应体是**查看器 HTML**
（约 0.5KB）而非 PDF 字节，直接采信会误判；若响应带 attachment 触发下载（导航以
"Download is starting" 告终），从 staging 捕获下载件。

## 四、校验与产物

- 校验器：`${SKILL_DIR}/scripts/pdf-validate.mjs <file> [--min-bytes N]`（可独立使用）。
  判据：前 1024 字节含 `%PDF-` 头 + 体积 ≥ min-bytes（默认 10240）+ HTML 误装识别。
- 产物命名 `<id 的 slug>.pdf` 落 `--out-dir`；报告 JSON 含逐篇记录（状态/细节/截图路径/
  文件与大小）与汇总计数，路径见命令末行 `RESULT`。
- 截图按篇落盘（含拦截页证据）；下结论前用 `Read` 读关键截图（双通道铁律）。

## 五、速率与礼仪（不可协商）

- 串行处理，绝不并发；篇间随机延时（默认 5-15s）。
- 单篇失败不轰炸：不做同 URL 连续重试；重跑以单篇为单位。
- 仅用于用户有合法访问权限的内容（本校订阅范围）；下载节奏尊重站点承载。

## 六、配套件

- `references/site-notes.md` — 常见出版平台（ScienceDirect/IEEE/Springer/知网等）的
  PDF 定位线索与登录/拦截表现笔记。处理新平台前先读，命中线索也要现场观测确认。

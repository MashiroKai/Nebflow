# 出版平台 PDF 定位笔记（site-notes）

本文是 paper-download 的按需 Read 面：**线索不是结论**——命中任何线索仍须当次观测确认
（control-browser 观测即真相）。平台改版会使下述形态失效；失效时按 SKILL.md §三 的通用
定位序重新观测，并把新事实回写本文（注明更新日期）。

## 通用定位序（所有平台先走一遍）

1. `meta[name=citation_pdf_url]` —— Elsevier/Springer/Wiley/Taylor&Francis 等学术出版页
   普遍携带，最稳的第一落点。
2. `a[href]` 以 `.pdf` 结尾；3. 可见文案含 PDF 的链接/按钮；4. 平台特化形态（下表）。

## 平台特化线索

| 平台 | 文章页特征 | PDF 获取线索 | 登录/拦截表现（实测前仅作预期） |
|---|---|---|---|
| ScienceDirect | `/science/article/pii/<PII>` | 链接含 `pdfft`（true PDF 链路）；citation_pdf_url | 机构 IP 内多为直取；个人账号走 SIGN IN 会员墙 |
| IEEE Xplore | `/document/<编号>` | 文档内 `pdf` 按钮打开 `/stamp/stamp.jsp` 视图页 | 校园 IP 通常直取；无权限时弹订阅墙 |
| Springer | `/article/<doi>` | citation_pdf_url；`/content/pdf/<doi>.pdf` 形态 | 机构 IP 直取；否则 BUY/Access 按钮 |
| Wiley | `/doi/<doi>` | citation_pdf_url；`/doi/pdfdirect/<doi>` | 同上 |
| 知网 CNKI | 需登录机构账号 | 详情页「PDF 下载」按钮（中文文案命中 §三文案序） | 全程需登录；验证码频出 ⇒ browser-session 人在环 |
| doi.org 中转 | 302 到出版商 | 无自身页面，等落地后按通用序 | — |

## 失败形态对照（对应逐篇状态）

| 页面表现 | 判定 | 依据 |
|---|---|---|
| 「Just a moment…」/转圈盾牌页 | BLOCKED_ANTIBOT（cloudflare） | challenge-platform/cf-challenge 标记 |
| 「Press & Hold」滑块/按住验证 | BLOCKED_ANTIBOT（perimeterx） | px-captcha/_pxAppId 标记 |
| 跳到 SIGN IN / 登录表单 | NEEDS_LOGIN | 密码框或登录 URL 特征 |
| 文章页存在但无任何 PDF 线索 | NO_PDF | 通用定位序零命中（可能确实无开放全文） |

## 更新日志（回写区）

- 初版：随 browser-use 2.0 建档；各平台表现均标注为「实测前预期」， campus 环境首跑后
  请把实测结果逐行回写并注明日期。

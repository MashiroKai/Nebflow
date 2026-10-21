---
type: glossary
status: current
---

# 词汇表

本文件是 docs/ 文档库（[[#^vault]]）的**唯一定义源**：跨文档复用的名词概念在此定义（每条以块锚结尾），其余文档首次使用时一律引用、不得重定义。条款校验工具 `check_wiki_docs.py` 随 visual-report 插件的 wiki-docs skill 分发（`skills/wiki-docs/scripts/`）。

引用语法一览（语法示例必须写在代码围栏内，正文里裸写双方括号会被当真链接解析）：

```
[[目标]]           引用整篇文档
[[目标#标题]]       引用某一节
[[目标#^块锚]]      引用某个概念定义块（本文档的词条就是块锚）
[[目标|别名]]       带显示别名
![[文件名]]         内嵌附件（图/PDF）
[文本](x.md)       普通 markdown 链接（指向库内 .md 时同为图谱边）
```

文档库（vault） — 项目工作区内 `docs/` 目录整体：Obsidian 兼容的文档库，文档、词表与附件（[[#^attachment]]）都住在这里，图谱视图（[[#^graph-view]]）只扫这个目录。 ^vault

front-matter — 文档开头 `---` 围起的键值元数据块（如 `type`/`status`），供图谱过滤与状态识别；正文行号不含它。 ^front-matter

wiki-link — Obsidian 双链引用（语法见上文一览）：文档间引用关系（[[#^citation-edge]]）的载体，图谱的边由它自动抽取。 ^wiki-link

引用关系（引用边） — 一条从引用方文档指向被引方文档的有向边，由 wiki-link（[[#^wiki-link]]）或指向库内 markdown 文件的普通链接自动抽取；图谱视图的边即它。 ^citation-edge

概念首现 — 名词概念在某篇文档中第一次出现的位置；纪律要求该处要么就地一句话解释，要么引用已解释它的文档（[[#^citation-edge]]），禁止多篇文档各自定义同一概念。 ^concept-first-use

图谱视图（wiki graph） — 把文档库渲染成「节点=文档、边=引用关系」的力导向图，控制台 Canvas 的 `wiki-graph` 页签；数据由引擎 `GET /api/wiki/graph` 自动抽取，无需手工维护。 ^graph-view

力导向布局 — 图布局算法：节点间斥力 + 引用边弹簧力 + 向心力迭代收敛，连接密的文档自然聚簇；图谱视图（[[#^graph-view]]）用它自动排布，免手工摆位。 ^force-layout

未解析引用（断链） — 指向不存在文档/标题锚/块锚的引用；极简图谱默认不为它立节点，只在状态行列计数（Obsidian 会画虚线幽灵节点）。 ^unresolved-ref

附件（attachment） — 被文档引用的图片/PDF/数据文件，统一放 `docs/attachments/`，用内嵌附件语法（见上文一览）引用；附件被引用时才作为节点进图谱视图（[[#^graph-view]]）。 ^attachment

样张 — 只做设计演示、不进产品代码的静态 HTML 样例（数据真实、交互可点）；本库样张与设计稿成对放 `docs/design/`。 ^mockup-sample

重启窗 — 引擎（sbt 跑的 JVM 进程）下次安全重启的时间窗口：编译进 jar 的改动（Scala、web dist）要等重启窗才生效，静态 web 源码树改动则刷新即生效。 ^restart-window

中继 ingress 平面 — link.mystand.space 反向链路上的 Rust link-server 鉴权层：对每个 `/api/*`、`/ws` 先验中继自己那套登录（require_user），过闸才转发到宿主引擎。与宿主会话平面（[[#^host-session-plane]]）是两套独立会话。 ^relay-ingress-plane

宿主会话平面 — 宿主引擎（本机 8080 网关）自己的会话鉴权层，纯内存、每次引擎重启即清空；控制台请求要同时过中继 ingress 平面（[[#^relay-ingress-plane]]）与它两道闸。 ^host-session-plane

DCL（DOMContentLoaded 事件） — 浏览器解析完 HTML、首次可交互的时间点；本仓控制台加载性能以「冷/温 DCL」为主指标（冷 = 清缓存首开，温 = 缓存命中再开）。 ^dcl

Service Worker（SW） — 浏览器在页面网络栈前注册的脚本代理，可缓存静态资源实现温访问秒开；本仓 `web/sw.js` 对静态面走 SWR 策略（[[#^swr]]），`/api`、`/ws` 零接触。 ^service-worker

SWR（stale-while-revalidate） — 缓存策略：命中缓存立即应答（stale），同时后台拉新版本对账（revalidate）；本仓 Service Worker（[[#^service-worker]]）对 `/js/ /css/ /vendor/` 等静态面用此策略。 ^swr

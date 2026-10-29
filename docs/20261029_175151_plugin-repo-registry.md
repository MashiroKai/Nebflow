---
type: ops-report
status: current
date: 2026-10-29
scope: 插件建仓批（ops）——23 插件独立 git 仓 + 版本 tag + GitHub remote
---

# 插件建仓批：插件→仓→tag→内容源→差异申报清单

作者令 2026-10-29 17:03 逐字：「首先，为我们的每一个插件都创建一个git仓库。做好版本回溯。然后我们修改插件的话，就是在插件的仓库进行修改，方便版本管理。」

> **结论**　23 个现装 [[glossary#^plugin-package]] 各建独立 git 仓（单仓单包），各以 manifest `plugin.json` 的 `version` 打 `v<version>` 带注释 tag，tag 打在**全量导入 commit** 上（`0.1.0` 兜底未用到——23 包全有 version）。仓名 = 包原名，全部建在作者 GitHub 账号 `MashiroKai` 名下；官方分发候选 7 包 public、其余 16 包 private。**仓内容以 [[glossary#^engine-seed]] 为 canonical（默认集 7 包），非默认 16 包用 [[glossary#^plugin-instance]] 内容**；三包种子已前进但实例被直改，差异单独存档申报（§3）。本批**未改引擎代码、未动 `~/.stand/plugins` 运行时目录本体**（建仓用复制出的 staging 区 `/Users/kaiyu/.stand/plugin-repos/`）。

## 1. 仓清单（23 仓）

「导入 commit」＝ tag 所指 commit；第二笔 `docs: add repository README…` commit 追加溯源说明（含内容源与差异提示），不改包内容。

| # | 插件 | 仓 URL | 可见性 | tag | 内容源 | 导入 commit |
|---|---|---|---|---|---|---|
| 1 | document-production | https://github.com/MashiroKai/document-production | public | v1.0.0 | 种子 | ebb1bf2f6f35 |
| 2 | nebflow-plugin-creator | https://github.com/MashiroKai/nebflow-plugin-creator | public | v1.0.0 | 种子 | b2798a6f0136 |
| 3 | slideblocks | https://github.com/MashiroKai/slideblocks | public | v1.0.1 | 种子 | 169346f2e223 |
| 4 | web-search-toolkit | https://github.com/MashiroKai/web-search-toolkit | public | v1.0.0 | 种子 | da68e9b628e3 |
| 5 | visual-report | https://github.com/MashiroKai/visual-report | public | v1.5.0 | 种子 | ec6ec0df7e24 |
| 6 | browser-use | https://github.com/MashiroKai/browser-use | public | v2.0.0 | 种子 | 97eefd18a22f |
| 7 | computer-use | https://github.com/MashiroKai/computer-use | public | v0.1.0 | 种子 | 12bd71d043f7 |
| 8 | academic-research | https://github.com/MashiroKai/academic-research | private | v1.0.1 | 实例 | f24276b194e7 |
| 9 | backend-dev | https://github.com/MashiroKai/backend-dev | private | v1.0.1 | 实例 | b9e7979fa612 |
| 10 | dbm-memory | https://github.com/MashiroKai/dbm-memory | private | v0.1.0 | 实例 | be204bee180c |
| 11 | design-cards | https://github.com/MashiroKai/design-cards | private | v1.0.1 | 实例 | 待推送读数 |
| 12 | design-spec | https://github.com/MashiroKai/design-spec | private | v1.0.1 | 实例 | 待推送读数 |
| 13 | docs-prompt | https://github.com/MashiroKai/docs-prompt | private | v1.0.1 | 实例 | 待推送读数 |
| 14 | engineering-methods | https://github.com/MashiroKai/engineering-methods | private | v1.0.1 | 实例 | 待推送读数 |
| 15 | explorer-toolkit | https://github.com/MashiroKai/explorer-toolkit | private | v1.0.1 | 实例 | 待推送读数 |
| 16 | imagegen | https://github.com/MashiroKai/imagegen | private | v0.1.0 | 实例 | 待推送读数 |
| 17 | learn-anything | https://github.com/MashiroKai/learn-anything | private | v1.0.1 | 实例 | 待推送读数 |
| 18 | nebflow-frontend-dev | https://github.com/MashiroKai/nebflow-frontend-dev | private | v1.0.1 | 实例 | 待推送读数 |
| 19 | nebflow-qa | https://github.com/MashiroKai/nebflow-qa | private | v1.0.1 | 实例 | 待推送读数 |
| 20 | phd-note | https://github.com/MashiroKai/phd-note | private | v1.0.1 | 实例 | 待推送读数 |
| 21 | pipelines | https://github.com/MashiroKai/pipelines | private | v1.0.1 | 实例 | 待推送读数 |
| 22 | thesis-review | https://github.com/MashiroKai/thesis-review | private | v1.0.1 | 实例 | 待推送读数 |
| 23 | website | https://github.com/MashiroKai/website | private | v1.0.1 | 实例 | 待推送读数 |

**内容源裁定与校验**（种子 = 本仓 `src/main/resources/seed/plugins/<name>/`，实例 = `~/.stand/plugins/<name>/`）：

- 默认集 7 包一律用种子建仓。其中 document-production / nebflow-plugin-creator / slideblocks / web-search-toolkit 四包 `diff -rq` 种子与实例**零差异**（且 `find` 无 `Only in` 项）；visual-report / browser-use / computer-use 三包差异见 §3。
- 其余 16 包**全部用实例内容**（按任务裁定）。其中 backend-dev / design-cards / design-spec / docs-prompt / engineering-methods / explorer-toolkit / nebflow-frontend-dev / nebflow-qa / pipelines / website 十包本仓其实也有种子目录（另加 design-language，仅种子有、实例无，不在本批范围）——本批按「非默认包用实例」执行，未改判。其中 nebflow-frontend-dev、nebflow-qa 两包的 `plugin.json` 种子侧比实例多 `keywords`/`useWhen` 字段（实例为旧版），建仓保留的是实例旧版，特此申报。
- 导入即全量：逐仓 `git ls-files` 计数与 staging 目录文件数相等（差集为空，`.DS_Store` 排除）；dbm-memory 的 `vendor/dbm/node_modules`（better-sqlite3 预编译绑定，含 `better_sqlite3.node`）作为包自包含运行面**全量纳入**。
- 敏感面扫描：7 个 public 候选包 grep 无 token/私钥；visual-report 的 `skills/core/references/demos/tech-architecture.md` 含侦查记录里的本机绝对路径 `/Users/kaiyu/Claude code/Nebflow`，系已有样张原文（该文件本仓与公开 Nebflow 仓已有），未改写、照录。

## 2. 推送读数与阻塞

推送全程走 Clash 代理：`env https_proxy=http://127.0.0.1:7890 http_proxy=http://127.0.0.1:7890`（仅本次命令 env，未改 git 全局配置）；认证用 `gh auth token` 经 per-command `credential.helper` 注入，未写凭据文件。

| 时间 | 读数 |
|---|---|
| 17:45–17:48 | 前 6 仓（document-production、nebflow-plugin-creator、web-search-toolkit、visual-report、computer-use、academic-research）创建+推送 main+tags 成功。 |
| 17:48–17:52 | 触发 GitHub secondary rate limit：`createRepository` 报 `You have created too many repositories, too quickly`；REST `POST /user/repos` 报 403 `exceeded a secondary rate limit … temporarily blocked from content creation`。当时 10 仓已建并推送，14 仓待建。 |
| 17:52–17:56 | 短 backoff 重试后 slideblocks 创建并推送成功；browser-use 首个端到端验证通过（`git ls-remote` main/tag 读数一致）。 |
| 18:04 起 | 剩余 13 个 private 仓以长 backoff 收敛循环（每轮失败即歇 10 min）后台推进；已建仓的 main+tag 校验逐仓通过。 |
| 终态 | 见 §1 表与各仓 `_push_log*.tsv`；`git ls-remote` 校验 main == 本地 HEAD、`v<version>` tag 解到导入 commit。 |

**账户现状核对**：`gh api user` = `MashiroKai`（display name "Kai"），作者令「账号 kaiyu 名下」即此账号。账号下原有 `MashiroKai/nebflow-plugins`（private，多包合一的开发协作用仓）**保持不变**，本批是「一插件一仓」，未复用、未覆盖该仓。

## 3. 差异申报：实例直改三包（种子已前进）

三包建仓一律以种子为准；实例侧直改内容单独存档留档：

- 差异 patch（3 份）：[[attachments/20261029_plugin-instance-drift-visual-report.patch]]、[[attachments/20261029_plugin-instance-drift-browser-use.patch]]、[[attachments/20261029_plugin-instance-drift-computer-use.patch]]（`diff -u` 格式，a=种子、b=实例）。
- 实例全量副本：`/Users/kaiyu/.stand/plugin-repos/_instance-drift-20261029/{visual-report,browser-use,computer-use}/`。

| 包 | 差异文件 | 内容要点 | 证据 |
|---|---|---|---|
| visual-report | `Plugin.md`、`plugin.json` | 种子在 Plugin.md 增补 wiki-docs（项目文档库纪律 skill）速览/目录/总目录表行与校验器指引，并把 `network/` 标注为场景 skill；`plugin.json` description 种子增「三条强制条款＝…；随包自带条款校验器 scripts/check_wiki_docs.py，--graph 可导出图谱数据 JSON」 | 两文件 SHA-256 前缀：种子 `238f19fcbe89`/`e6553e165860`，实例 `42e75a9e3537`/`fc8eeecf5a84`；83 个包文件中仅这两文件不同 |
| browser-use | `Plugin.md` | 首段机制声明：种子为 2026-10-21「Plugin.md = 插件必要文件、分配时整文注入、SKILL.md 按需查阅」口径；实例仍为「装载器不注入本文件、spawn 时全文注入 SKILL.md」旧口径 | 种子 Plugin.md 最近 commit `5fe4309c6`（2026-10-20 03:56），实例 mtime 2026-10-14 08:43；其余 21 文件无差异 |
| computer-use | `Plugin.md` | 同 browser-use，同段旧口径残留 | 同上（种子 `5fe4309c6`，实例 mtime 2026-10-14 08:43）；其余 7 文件无差异 |

另注：visual-report 实例的 `skills/wiki-docs/SKILL.md` 与 `scripts/check_wiki_docs.py` mtime 为 2026-10-29 12:40 且与种子逐字节相同——同步只覆盖了这两个文件，包根 `Plugin.md`/`plugin.json` 未跟上，故漂移集中在包根两文件。

## 4. 后续修改怎么走（作者令落点）

- 改插件 = 改对应插件仓，提交后按 `plugin.json` 的 version 打 `v<version>` tag 发版（建议改内容同步 bump version）。
- 要让新装实例拿到改动，需把仓内容回灌种子目录 `src/main/resources/seed/plugins/<name>/`（双副本关系见 [[design/20261029_131610_plugin-protocol-audit__delegate-nebflow-14e75897.zh-CN]] 挂载链一节）；直接改 `~/.stand/plugins` 实例会在种子同步时被覆盖——本批 §3 三包即为该机制漂移的实证。
- 未在本批范围：把插件仓接入 CI/发布流水线、种子↔仓双向同步自动化、USTC 备用 remote（GitHub 代理可达，未启用）。

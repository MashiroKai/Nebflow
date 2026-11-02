---
type: batch
status: done
related: "[[20261101_151034_ustc-cicd-migration-assessment__delegate-nebflow-7f8e8dec]]"
---

# 密钥能力三件套批：CLI + 提示词教义 + 面板层级收进（2026-11-01）

> 作者令 2026-11-01 15:36：密钥能力三件套小批——A `stand secrets` CLI；B agent/分发器提示词加「密钥的使用与创建」一节；C 设置·密钥面板 UI 优化（作者实拍）。全程在 delegate worktree 完成，交付走合并队列。

## 0. 交付一览（先行）

| 件 | 内容 | 落点 |
|---|---|---|
| A | `stand secrets`（bare = 用法；`list` = 键名+变量名+写入时间+用途，零值） | [[#1 CLI 件]] |
| B | 提示词「密钥教义」always 段（创建/消费/铁律三条款） | [[#2 提示词件]] |
| C | 面板四整改：层级收进父面板 / 红标退役 / 网格化 / 杂质剔除 | [[#3 面板件]] |

验收（全绿，证据见 [[#4 验收与证据]]）：CLI 真跑三面 + 断网面；面板 chromium×firefox × 深浅双主题四格矩阵硬断言全过（子面板矩形完整落进父面板内 24px 收进带、零红标、零 rollback 杂质、键名列/元信息列双列栅格对齐、面板作用窗零 console error）；spec 新增 V5/C16/order-377 三钉，四套件 135 测试全绿。

## 1. CLI 件（A）

- 新文件 `src/main/scala/nebflow/cli/SecretsCommand.scala`：`SecretsCommand`（subcommands: `usage`/`list`；`defaultSubcommand = usage` ⇒ bare `stand secrets` 出用法提示，不静默跑 list）。`list` 走 `SecretsStore.list()` **元数据唯一读面**（结构上无值字段可读），列名/变量/更新时间/用途（目录 description），`--json` 机读面。
- 离线归类：`CliRouter.OfflineSubcommands` 增 `("secrets","list")`/`("secrets","usage")`——本机元数据读面（health/logs 同款判据，网关不在时恰恰更该可用）。实测网关死后 `list` 照常出表。
- 注册：`CommandRegistry` append-only 一行（密钥 CLI 批注记）。

## 2. 提示词件（B）

- `Defaults.SecretsRule` 单点文本（`── Secrets (credentials) ──` 头 + CREATE/CONSUME/IRON RULES 三条款，引牌不引文——机制权威 = AskUserQuestion 工具 description）。
- 注入面 = `PromptSections` **order-377 always 段**：与 order-375/376（root-only）刻意不同——凭证经任务文本流进每张面（分发器写 brief、节点 Bash 消费），纪律必须在每张面（root/分发器/delegate/节点/team 一视同仁）。静态体落 systemStable，前缀缓存友好（order-350 溯源段同读数）。
- spec：`PromptSectionsSpec` 钉「三张会话形态全注入 + 逐字 SecretsRule + 段序 377>376 + 静态体」。

## 3. 面板件（C，作者实拍四整改）

1. **层级收进**：专用弹窗架构不变（2026-09-27 裁定），几何服从父级——CSS 基线 `width 512 = 父(#settings-modal 560) − 2×24`、`max-height 85vh−48px`；`openSecretsPanel` 新增 `fitToParent()` 现读父实矩形再钳（短设置面板永不被溢出），resize 重入、关闭清内联样式；手机层铺满屏形态不受影响（`body.mobile-layer` 的 `!important` 全幅规则压过一切，JS 也跳过）。两壳同为视口居中 ⇒ 恒小一号的居中子框几何必在父框内。
2. **红标退役**：「权限过宽」徽标整概念退役（作者原话「不知道什么意思」）——`.secrets-mode-badge` 规则删除、`secretsPanel.js rowHTML` 不再渲染、zh/en 两 locale 键同批同删（键集 26/26 平衡保持）；wire 的 `mode` 字段保留不读。
3. **网格化重排**：rest 行 flex → 两列 grid（键名列 minmax(0,1fr) 省略号｜元信息列右对齐 tabular-nums），行距统一 10px 12px；元信息（size·mtime）自展开详情上移 rest 面，详情只余值域+引用行+操作行；saved-flash 绝对定锚覆盖元信息列（`[data-flashing]` 隐藏 meta，零位移）。
4. **杂质剔除**：后端单点 `SecretsStore.isArchiveBackup`（名字含 `rollback`，大小写不敏感）——list/目录 rows/消费指针/CLI 四面同源过滤；🔴 只滤**视图**不动物理文件（归档另论）。实拍列（email-connector-rollback-*.json、logto-logout-rollback.json、vercel-…-rollback.json）全部不再出现。
5. 滚动区内收：`.secrets-modal-body` 改 flex:1+min-height:0，60vh 旧帽退役，滚动由壳的 max-height 约束。

## 4. 验收与证据

- **CLI 真跑**：`evidence/20261101_secrets-trio/cli-secrets-list.txt`（12 行清单表，含用途列；16 文件中 4 个 rollback 备份零出现）+ `cli-secrets-usage-json.txt`（bare 用法 / `--json` / 断网面）+ `cli-secrets-list.png`（渲染实拍）。
- **面板四格矩阵**：`evidence/20261101_secrets-trio/shots/`（chromium/firefox × dark/light 各一张 + 展开态八张）。断言器 `scripts/shot-secrets-panel.cjs`（提交进仓）对**隔离真实例**（临时 STAND_HOME + 独占端口 6198，REST 播种 11 + 直写 5）跑：`contained=true`（child 512×594 ⊂ parent 560×680）、`badges=0`、`rollback=false`、`nameX/metaR=true`、面板作用窗 console error=0（含眼形 `GET /api/secrets/{name}/value` 取值调用）。隔离实例收尾：PID 验身（`--home /tmp/secrets-batch-home --port 6198`）后 kill，lsof 复查端口释放。
- **spec**：`SecretsStoreSpec` V5（rollback 隐藏于 list + 文件原地不动 + 判据不过杀）、`SecretsCatalogSpec` C16（目录/指针零 rollback）、`PromptSectionsSpec` order-377 三钉；四套件 135 全绿。`checkJs`/i18n-sweep 的存量红（ws.js/modelPanel.js/main.js/scheduled-task.js、zh-only 8 键）经主仓对照为在飞批既有态，本批 secrets 键 26/26 平衡。

## 5. 边界与备注

- 隔离无头实例上 ws.js 聊天轨握手 403/重连噪声（双引擎皆现、时序不定）为既有面，与本批无关；断言器已按域豁免并注明。
- 归档目录（rollback 文件的迁置）作者明示「另论，不属本批」。
- 相关：[[20261101_151034_ustc-cicd-migration-assessment__delegate-nebflow-7f8e8dec]]（同日并行批，无交叠）。

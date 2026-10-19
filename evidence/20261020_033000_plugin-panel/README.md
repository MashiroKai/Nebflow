# 插件面板七条（作者令 2026-10-21 批）— 证据集

仓库 `Stand` @ `721b305f1` · 分支 `delegate/delegate-nebflow-a2c16807` · 执行：delegate-nebflow-a2c16807

## 七条令落点

| # | 令 | 落点 |
|---|---|---|
| ① | 只看纯插件 | `web/js/plugins.js`：agent 区块（`plugins-section-agents`/`renderAgentRow`/`loadAgentSummaries`/`data-detail-agent` 深链/`PANEL_HIDDEN_AGENTS` 枚举过滤）整段退役；`sidebar.css` `.plugins-agent-*` 族删除；locale 键 `plugins.agents/agentsHint/noAgents` 删除 |
| ② | plugin.md=插件必要文件 | 引擎注入链切换：`PluginInjection.injectedPluginBlock` 改读包根 `Plugin.md`（小写 `plugin.md` 回退同认），注入块 `<plugin name>` 无 skill 属性；缺文件 = Left（fail-closed，节点级失败列路径）。机制 spec：`docs/design/plugin-md-required-file-spec.zh-CN.md`；断言：`PluginInjectionSpec`（注入内容/缺文件/小写回退三钉）+ `NodePluginChainSpec` §B.8-1 + `PluginDispatchFaceSpec`。存量补齐：seed `nebflow-qa`（新件）+ 线上根 4 包（academic-research/learn-anything/phd-note/thesis-review，见 §4）；16 包 Plugin.md 头部旧机制声明（「装载器不注入/作业指令在 SKILL.md spawn 全文注入」）统一改为新机制声明（seed 全 16 + 线上非清单 9；线上 7 清单包**故意不改**——种子镜像下次启动自动刷新，手改会永久阻塞该刷新）；打包技能 §3.4 + pluginmd 模板 + 校验脚本 C11b（缺 Plugin.md=FAIL）同步新机制 |
| ③ | 展开态布局收紧 | `sidebar.css`：展开态行摘要 `visibility:hidden`（占位留白）→ `display:none`（空间塌缩）；详情区 gap 8→6、padding-top 10→8 |
| ④ | 不再提示变更 | `plugins.js`：`pluginStatus/pillOf/applyPluginCardState/cardSignature` 全部去 contentChanged 消费；amber 药丸档、`.plugins-card-hint.changed`、卡片 changed 类退役；`sidebar.css` 对应规则删除；locale `plugins.stateChanged/changedHint` 删除。🔴 API 契约字段 `contentChanged` 保留，本页零消费 |
| ⑤ | 统计口径 | 顶部统计 `[data-plugins-stats]` = skill 总数 + MCP server 总数（全注册表面求和）；locale 键 `plugins.statSkills/statMcp`（zh：N 个技能 / N 个 MCP） |
| ⑥ | skill 列表改胶囊 | 详情区 skills = `.plugins-skill-capsule`（胶囊小图标 + 描述，`data-skill-doc`/`data-skill-id` 钩子）；点击取 `/api/plugin-assets/<name>/skills/<skill>/SKILL.md` → 既有 Canvas 通路（workspace-open-item → markdown viewer）打开；404 → toast 零静默。旧 id+描述+preview 内联渲染（`.plugins-skill-item` 族 + `.plugins-skill-preview`）退役 |
| ⑦ | nebflow.space 残留 | 数据链来源 = seed/线上 `website` 包文案（plugin.json description 面板行摘要直读 + SKILL.md frontmatter description 胶囊文本直读 + Plugin.md 入口文档）。全域清扫 `nebflow.space` → `mystand.space`（Stand 现役域，brand.conf 为准）；保留：`neblink.nebflow.space`（设备中继服务地址）、deploy SKILL 一行历史实测证据（标注「时值旧域」）。静态扫描钉：`tests/plugins-panel-pure-plugins.spec.mjs` ⑦（seed 全包 description/Plugin.md/SKILL.md frontmatter 零裸域） |

## 1. 面板新形态截图（暗/亮）

- `shots/panel-dark.png` / `shots/panel-light.png` — 默认态：纯插件视角（零 agent 区块）、顶部统计（N 个技能 · 0 个 MCP——seed 全包无 mcp.json，0 为真实值）、条目行+行尾开关
- `shots/panel-dark-expanded.png` / `shots/panel-light-expanded.png` — 展开态：中部留白塌缩、组件面 chip、Plugin.md 入口、skill 胶囊列表（图标+描述）
- 截图环境 = worktree web/ 静态服务（127.0.0.1:8187，非宿主端口）+ 真实 seed 清单为数据；`shoot.mjs` 为一次性 harness（浏览器/服务已清理）

## 2. 测试

- Scala：`core.plugin.* + core.jev.* + core.seed.*` 161/161 绿；`core.project.* + core.tools.*` 1824/1824 绿（1 skip 存量）；`gateway.*` 740/741（1 失败 = `GatewayAuthSpec` 品牌头断言，**基线同败**，与本次改动无关）
- 前端插件面既有测试族全绿：itemize 8、autosync 7 过 8 skip（skip=需隔离实例的 real-backend 腿，**基线同skip**）、redesign/two-actions 同族、sidebar-plugins 5；新增 `plugins-panel-pure-plugins.spec.mjs` 6/6（①③④⑤⑥⑦ 断言链）
- checkJs（tsc）错误数基线对比：258 → 258（零新增）

## 3. 前端改动生效口径（如实报告）

`/js/**` 走 `StaticFile.fromResource`（classpath）——**运行中的实例刷新不生效**，需重新打包 + 实例重启后生效（`-Dstand.webdist=dev` 的 dev tree 仅影响 index/dist 入口，/js 路由恒 classpath）。本批验证全部走 worktree 静态服务 harness；线上面板形态升级以上线动作为准。⑦ 的数据侧修复已直接落线上数据根（见 §4），数据部分刷新即生效。

## 4. 线上数据根（~/.nebflow/plugins）同批动作（实例侧，不入库）

- 补齐缺 Plugin.md 的 5 包：**nebflow-qa**（seed 字节直同步）+ **academic-research / learn-anything / phd-note / thesis-review**（自各自 description 提炼新件）——至此 21 包全有 Plugin.md
- `website` 包 4 文件品牌清扫直同步（该包不在 seed 清单内，种子永不触碰它，手改是唯一生效路径）
- 非清单 8 包 Plugin.md 头部机制声明手改（backend-dev/design-cards/design-spec/docs-prompt/engineering-methods/explorer-toolkit/nebflow-frontend-dev/pipelines）；**7 清单包未手改**（种子镜像下次启动自动刷新：runtime 干净 ⇒ 备份 + mirror + re-approve；手改反而会把它们标成 user-modified 永久阻塞刷新）
- 线上全包扫描：裸 `nebflow.space` 零命中

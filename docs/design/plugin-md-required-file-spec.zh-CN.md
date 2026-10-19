# plugin.md = 插件必要文件机制 spec（作者令 2026-10-21 批 · 条②）

## 1. 机制定义

- **插件必要文件**：每个插件目录必须有包根 `Plugin.md`，内容 = 该插件的功能描述。
  既有 16 个 seed/线上包生态的既有命名是 `Plugin.md`（大写 P），本机制沿用之（零迁移）；
  小写拼写 `plugin.md` 同认（读取时回退一查，覆盖手写小写名的包）。
- **注入语义**：插件分配时注入给 agent 的提示词 = **Plugin.md 的内容**（整文）。
  旧链（逐 skill 读 SKILL.md 全文拼 `<plugin name skill>` 块）随本批退役；
  SKILL.md 回归「按需查阅」件（面板胶囊深链打开），不再随分配整文注入。
- **实现单点**：`src/main/scala/nebflow/core/project/PluginInjection.scala`
  `injectedPluginBlock`（解析块由 `resolvePreparation` 聚合，`NodeStarter.prepareNodePlugins`
  委托同件）。注入块形态：

  ```
  <injected-plugins>
  <plugin name="<包名>">
  …Plugin.md 原文（`{{data_root}}` 渲染为实例数据根）…
  </plugin>
  </injected-plugins>
  ```

## 2. 缺文件行为（fail-closed，断言钉）

| 场景 | 行为 | 断言钉（spec） |
|---|---|---|
| 包根无 `Plugin.md` 也无 `plugin.md` | **Left**（节点级失败：spawn 显式拒，错误列明路径）——不静默降级、不回落 description | `PluginInjectionSpec` · "a package missing the required Plugin.md is an explicit refusal (Left) naming the path" |
| 文件存在但不可读（IO 错误） | 同上 Left（同一 no-silent-degradation 纪律） | 同上（missing/unreadable 同一错误面） |
| 只有小写 `plugin.md` | 正常注入（同一文件机制双拼写同认） | `PluginInjectionSpec` · "injection accepts the lowercase plugin.md spelling" |
| 分配选中包被封禁 | 既有 `PLUGIN_BLOCKED` Left（本批零改动） | `PluginInjectionSpec`（封禁腿，沿用） |
| SKILL.md 存在与否 | **不影响注入**（SKILL.md 退出注入链；缺 SKILL.md 的纯 MCP 包照常注入 Plugin.md） | `PluginInjectionSpec` · "injection source = Plugin.md"（SKILL.md 正文不在注入块内） |

装载面（PluginRegistry 扫描）**不**因缺 Plugin.md 拒载——缺文件在**分配注入时**
fail-closed（「装了就是信任」 doctrine 不破坏；面板可见性由 Plugin.md 入口探针承担：
404 ⇒ 入口永不在场）。

## 3. 注入内容 = plugin.md 断言（spec 钉）

- `PluginInjectionSpec` · "injection source = Plugin.md"：
  - 注入块含 `<plugin name="<包名">`（**无** `skill=` 属性——旧形态退役）；
  - 注入块含 Plugin.md 正文 marker；
  - 注入块**不含** SKILL.md 正文（负例断言）。
- `NodePluginChainSpec` §B.8-1（真实 NodeEdit → NodeEngine 链）：节点首条用户消息
  含 `<injected-plugins>` 全文 = Plugin.md 内容；不含 SKILL.md 正文。
- `PluginDispatchFaceSpec` ③：已派发节点提示词注入全文在位（Plugin.md marker）。

## 4. 存量补齐（本批落地）

| 包 | 处置 |
|---|---|
| seed `nebflow-qa` | 新增 `Plugin.md`（自 plugin.json description + 五 skill 提炼；作者可后续精修） |
| 其余 15 个 seed 包 | 已有 `Plugin.md`；本批统一修正文件头「装载器不注入本文件/作业指令在 SKILL.md（spawn 时全文注入）」的旧机制声明 → 新机制声明（分配注入 = 本文件） |
| 线上数据根缺文件的包（academic-research / learn-anything / phd-note / thesis-review / nebflow-qa） | 实例侧同批补齐（内容自各自 description/SKILL.md 提炼，见交付报告） |

## 5. 与面板的关系（条⑥联动）

- 面板 Plugin.md 入口（`[data-plugin-doc]`）：探针 `/api/plugin-assets/<name>/Plugin.md`
  200 ⇒ 在场；点击经既有 Canvas 通路打开——机制不变，本批零改动。
- skill 胶囊（新）：点击取 `/api/plugin-assets/<name>/skills/<skill>/SKILL.md` 进
  Canvas——SKILL.md 的「按需查阅」消费面。

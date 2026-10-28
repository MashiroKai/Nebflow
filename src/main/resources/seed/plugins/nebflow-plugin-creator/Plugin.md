# nebflow-plugin-creator — 插件使用指导（Plugin.md）

> 本文件是 nebflow-plugin-creator 插件的**包根使用指导件**（Plugin.md）：**插件分配时注入给 agent 的提示词 = 本文件内容**（作者令 2026-10-21「plugin.md = 插件必要文件机制」批；SKILL.md 回归按需查阅件——面板胶囊点击打开，不再随分配整文注入）。选配判定仍看 plugin.json 的 description；逐 skill 作业指令见各 `skills/<skill>/SKILL.md`（按需查阅）——**本文件是导览不是手册，与 SKILL.md 不一致时以 SKILL.md 为准并回改本文**。文内路径均为**自包根的文件相对形式**（不使用 `${SKILL_DIR}`）。

## 1. 速览

nebflow-plugin-creator 是插件封装器包：节点获得把能力需求封装为合规 Nebflow 插件包的全流程执行能力——三输入分析（既有 skill 迁移 / MCP server 配置【实验性】/ 口头能力描述）→ 包生成 → 机械自检 → 落盘即生效的交付自检。内含 2 个 skill：

- `skills/plugin-packaging/`——封装主流程手册（三输入→生成→自检→交付，含六分叉处置、交付前自检与来源/digest 报告口径），并携带校验脚本 `scripts/validate_plugin.py` 与模板资产 `assets/`。
- `skills/description-quality/`——manifest 五段式描述规范（唯一描述源口径、触发词核心词表圈定法、M1-M15 判定口径、好坏范例四例）。

**包纪律一句话**：装载为「在位即生效」（无人工确认环节）——封装产出的终检就是最后一道门，执行节点自己就是那道人审；脚本 PASS 只覆盖格式面，内容面必须逐件读过再落盘。

## 2. 安装与启用

- **安装**：本包属 Nebflow seed 官方分发（官方保留 namespace `nebflow-`，装载器按分发内置允许列表对目录 digest 现算验身份），随实例 seed mirror 自动下发，无需手工安装。
- **启用/选配**：分发器按 description 触发词选配（把已有 skill 或能力封装成插件、做一个能 Y 的新插件、写 plugin.json 与 SKILL.md、插件描述质量体检、跑包校验脚本）；分配时注入本文件（Plugin.md），两份 SKILL.md 为按需查阅件（面板胶囊深链打开，不再随分配整文注入）。
- **停用**：面板「更多 → 封禁该插件」/ `POST /api/plugins/nebflow-plugin-creator/revoke` / `nebflow plugin revoke nebflow-plugin-creator`。
- **内容变更**：包内任何字节改动都会漂移 digest，面板把该包标为「内容已变更」（可见性提示，不拦截装载；新内容对新派发即刻生效）。

## 3. 目录结构

```
nebflow-plugin-creator/  （包根）
  plugin.json
  Plugin.md              ← 本文件（包根使用指导件：速览 / 安装启用 / 流程导览 / 资产登记）
  skills/
    plugin-packaging/    ← 封装主流程手册（按需查阅件）
      SKILL.md
      assets/
        manifest.template.json   ← manifest 起步模板（description 已含五段式骨架句）
        skill-template/          ← skill 目录骨架（SKILL.md + references/ + assets/）
      scripts/
        validate_plugin.py       ← 机械校验脚本（M1-M15 + C 组检测，三档 profile）
    description-quality/ ← 描述规范手册（按需查阅件）
      SKILL.md
```

## 4. 封装流程导览（怎么用本包造一个合规插件包）

> 全量口径以 `skills/plugin-packaging/SKILL.md` 为准（按需查阅件，含六分叉处置表与交付报告模板）；本节是跨 skill 的管线速查。

**四步管线**：

| 步 | 做什么 | 关键判据 | 全量口径 |
|---|---|---|---|
| ① 判定输入形态 | A 迁移封装（已有 SKILL.md/skill 目录）/ B MCP 配置（JSON 片段/命令行/URL，**实验性**）/ C 全新创作（口头能力描述） | 按输入实际内容判定，不按用户措辞；可组合（如 A+B） | plugin-packaging 第一步 |
| ② 能力域分析与拆分 | 能力域分析 → skill 拆分 | one skill = one purpose；深内容下沉 references/、脚本放 scripts/、模板放 assets/ | 第二步 |
| ③ 逐件生成 | manifest（五段式 description）→ skill 目录（frontmatter 下限 name+description）→ mcp.json（仅形态 B，实验性） | description 自检五段齐备；保留前缀 `nebflow-plugin-` 生成期拦截；落盘前探测同名目录、不静默覆盖 | 第三步 |
| ④ 落盘→自检→交付 | 直写 `<data_root>/plugins/<name>/`（落盘即生效）→ 机械自检 → 终检报告并**冻结目录** | **自检做完再落盘，落盘后不得再写包内文件**；终检报告含包结构/来源/digest/停止手段 | 第四~六步 |

**机械自检与交付闸（validate_plugin.py CLI 速查）**：

```bash
python3 skills/plugin-packaging/scripts/validate_plugin.py --profile creator <包目录>
```

- 三档 profile：`--profile official` 官方面硬闸（退出码≠0 即阻断；WARN 逐条处置或挂豁免记录）/ `--profile creator` creator 交付闸（默认档，**退出码 0 ＝ 交付闸**；惯例面警告 C1-C3/C6 须逐条处置或说明）/ `--profile user` 用户自装包提示级（退出码恒 0，建议配 `--report-file` 落文件）。
- 脚本按档输出 M1-M15 全量 ＋ C 组检测（C1-C16，C7 缓）逐条 PASS/FAIL/WARN ＋命中原文，并复算包 digest（可与 `GET /api/plugins` 的 `digest` 字段比对闭环）。
- FAIL → 按失败明细自修 → 重跑，最多 2 轮；2 轮后仍 FAIL ＝ **blocked 上报**（附逐条失败项原文），禁止降级交付（落盘即生效，没有后续人审兜底）。

**描述规范入口（五段式速查）**：

```text
<定位句：XX 包——节点获得<一句话核心能力>>          ← 含谓词，禁形容词（M6）
适用于<触发场景：什么任务/什么信号出现时选配它>。    ← 必含段，核心词全落字
内含 skills：<skill-a>（一句话括注）、<skill-b>（一句话括注）。
不适用于<边界>。                                   ← 边界句（M9）：自足即达标，不提名他插件
组件面：无 mcp.json、无工具扩展。                  ← 组件面声明句固定收尾
```

- 长度 ≤400 字符（M7）；写不完的细节进 skill 正文或包根 Plugin.md，不进描述。
- 触发词按**核心词表圈定法**逐词落字（分发器做字面匹配，近义词不互相兜底）。
- M1-M15 判定口径速查表、好坏范例四例：全量见 `skills/description-quality/SKILL.md`（按需查阅件；需要时 Read 原件）。

## 5. 各技能总目录

| skill 路径（自 skills/） | 一句话括注 |
|---|---|
| plugin-packaging/SKILL.md | 三输入→生成→自检→交付的封装主流程，含六分叉处置、交付前自检与来源/digest 报告口径；携带校验脚本与模板资产 |
| description-quality/SKILL.md | manifest 五段式描述规范：模板、好坏范例、M1-M15 判定口径与核心词表圈定法 |

## 6. 选 skill 决策树

- 任务是**封装/造一个插件包**（既有 skill 迁移、MCP 配置打包、全新创作、manifest/skill 生成、落盘交付、六分叉处置）→ `plugin-packaging`。
- 任务是**写/改/体检 plugin.json description**（五段式、触发词核心词表落字、M1-M15 判定）→ `description-quality`。
- 两者常连用：封装主流程第三步内嵌引用 description-quality（描述质量的完整口径）；独立的描述体检任务可只挂 description-quality。

## 7. 模板与脚本资产（assets/ / scripts/）

| 资产 | 用途 | 使用方 |
|---|---|---|
| `skills/plugin-packaging/assets/manifest.template.json` | manifest 起步模板（name 规则、五段式 description 骨架句内嵌） | 封装节点（第三步 3.1） |
| `skills/plugin-packaging/assets/skill-template/` | skill 目录骨架（SKILL.md + references/ + assets/） | 封装节点（第三步 3.2） |
| `skills/plugin-packaging/scripts/validate_plugin.py` | 机械校验：M1-M15 + C 组检测、三档 profile、digest 复算 | 封装节点（第五步）；维护者（协议面回归） |

> 用法与全量口径以 `skills/plugin-packaging/SKILL.md` 为准（本节只登记资产与普适用法，防漂移）。

## 8. 扩展位（未来候选）

| 候选项 | 现状 | 备注 |
|---|---|---|
| mcp.json 形态生产首例 | 实验性（装载校验规则完备，生产样本尚待首例） | 首例产出建议在隔离实例验证 MCP 启动链后再交付 |
| 包级行数预算（C7） | 暂缓 | 待有观测数据后定阈值 |
| 包根 Plugin.md 起步模板（assets/pluginmd.template.md） | 已在位（`skills/plugin-packaging/assets/pluginmd.template.md`） | 与包根第五件（Plugin.md）协议配套；生成 Plugin.md 时从模板起步 |

## 9. 维护约定

- **模板与手册同步**：改 `assets/` 模板须同步 plugin-packaging SKILL.md 的引用描述；M15 全包扫描覆盖脚本自身。
- **本文件定位**：Plugin.md 是分配注入体（插件分配时整文进节点提示词）——SKILL.md 是按需查阅件（内容权威）；两者不一致时以 SKILL.md 为准并回改本文；跨 skill 共用的包级内容才进本文，单 skill 专属细节留在该 skill。

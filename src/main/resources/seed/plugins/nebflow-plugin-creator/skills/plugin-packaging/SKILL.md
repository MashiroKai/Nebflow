---
name: plugin-packaging
description: 把能力需求封装为合规 Nebflow 插件包的执行手册——三输入形态判定（既有 skill 迁移 / MCP server 配置 / 口头能力描述）→生成→机械自检→信任门交付全流程，含六分叉处置与 approve 审批衔接；适用于节点被分配「封装插件 / 做插件」类任务时按本手册执行。
---

# 插件封装执行手册（plugin-packaging）

你是被分配了本插件的节点，任务是把一个能力需求封装为合规的 Nebflow 插件包。
按本手册逐步执行；机械规则交给校验脚本把关，你负责内容质量与流程纪律。

**产出纪律（不可协商）**：

1. 产出停在 untrusted——**绝不代批**。审批是用户的人审动作，你只交付包 + 审批指引。
2. **终检 PASS → 冻结目录 → 交付后不得再写包内任何文件**。包内任何字节改动都会
   让 digest 漂移、自动回到待审态（升级即重审机制）。approve 前发现要改：改完必须
   重跑校验脚本重走终检，approve 以终检后的 digest 为准。
3. name 命中保留前缀 `nebflow-plugin-` 且不在官方白名单 → 直接换名重生成（见分叉④）。

## 第一步：判定输入形态（三选一）

| 形态 | 输入特征 | 路径 |
|------|---------|------|
| A 迁移封装 | 已有 SKILL.md / skill 目录 | 读现有 frontmatter+正文 → 评估注入体量 → 按目录契约重组 → 生成 manifest |
| B MCP 配置 | MCP server 配置（JSON 片段 / 命令行 / URL） | 生成 mcp.json → 逐 entry 自查 → manifest（description 写明 server 能力面与凭据要求） |
| C 全新创作 | 口头能力描述 | 能力域分析 → skill 拆分 → 逐 skill 写 SKILL.md → 生成 manifest |

形态判定依据任务输入的实际内容，不是用户措辞；一个任务可能同时含多形态（如
「把这个 skill 和一个 MCP server 打包」→ A+B 组合）。

## 第二步：能力域分析与 skill 拆分（形态 C 为主，A/B 亦适用）

- **one skill = one purpose**：一个 skill 只承载一个目的；两个不相关能力拆成两个 skill。
- **体量红线**：每个 SKILL.md ≤300 行（节点在 spawn 时一次性收到全文注入，不是按需
  读取——超限即挤占上下文）。深内容下沉 `references/`（节点按需读），脚本放
  `scripts/`，模板资源放 `assets/`。
- 每个 skill 必须有真实工作内容：scripts 干实事，不写散文式伪步骤。

## 第三步：逐件生成

### 3.1 生成 manifest

从模板起步：`${SKILL_DIR}/assets/manifest.template.json`。description 按
description-quality skill 的五段式规范撰写（该 skill 是描述质量的完整口径：
模板、好坏范例、核心词表圈定法）。写完自检五段齐备：定位句（含谓词）、触发
场景句（核心词全落字）、内含 skills 明细（与实际目录一致）、边界/分流句、组件
面声明句。

**保留前缀拦截（生成期）**：manifest name 命中 `nebflow-plugin-` 前缀且不在官方
白名单（当前官方批次：`nebflow-plugin-creator` 及 seed manifest 声明集）→ 不落盘，
换名后重生成。这是外部导入渠道拦截在生成侧的对偶防线。

### 3.2 生成 skill 目录

骨架复制：`${SKILL_DIR}/assets/skill-template/`（SKILL.md + references/ + assets/）。
frontmatter 下限：name（等于目录名）+ description（一句话说清 what 与 when）——
缺任一键装载器会静默 skip 该 skill。校验脚本 M13 会在落盘前拦住。

### 3.3 生成 mcp.json（实验性）

输入形态 B 时生成。注意：**此输入形态当前标注为实验性**——装载校验规则完备，但
磁盘上尚无生产样本，首例产出建议在隔离实例验证 MCP 启动链后再交付。

- `$schema` 必须恰为 canonical mcp schema（模板见校验脚本 M14 的期望值输出）。
- 逐 entry 自查：`type` ∈ stdio | streamable-http | sse；stdio 必带单一可执行
  `command`（无空白）；`env` 是 string→string object 且**禁止声明**
  `PLUGIN_ROOT` / `PLUGIN_DATA`（客户端保留占位键，声明即拒）；`cwd` 须以
  `./` 或 `${PLUGIN_ROOT}` 或 `${PLUGIN_DATA}` 开头；http 类 transport 的 url
  须为绝对 http/https 地址。
- 凭据类 env 键名与 shell/网络类 command 不拦截但会触发审批清单红标——在交付
  报告里主动向用户标出这些人审点。
- manifest description 须写明 server 能力面与凭据要求，让人审有依据。

## 第四步：落盘（直写 + 同名探测）

- 直写目标：`{{data_root}}/plugins/<name>/`（节点直写是既定流程能力；信任门保证
  未 approve 的包不可被分配，直写风险已被门控覆盖）。
- **落盘前必须探测同名目录**，命中即走分叉①，不覆盖。

## 第五步：机械自检

```bash
python3 ${SKILL_DIR}/scripts/validate_plugin.py {{data_root}}/plugins/<name>
```

- 脚本按 M1-M15 逐条输出 PASS/FAIL + 命中原文，并复算包 digest。
- FAIL → 按失败明细自修 → 重跑，最多 2 轮。
- 2 轮后仍 FAIL → **blocked 上报**（附逐条失败项原文），交分发器/用户仲裁。
  禁止降级交付——带病交付 = 把格式问题转移给审批人。

## 第六步：终检报告与冻结

PASS 后输出终检报告并**冻结目录**（此后不得再写包内任何文件）：

```
包结构：<目录树>
digest：sha256:<值>（files=<N>）
状态：untrusted，待用户审批
审批指引：<三入口原文，见下节>
```

digest 可与 approve 后 `GET /api/plugins` 返回的 digest 人工比对闭环。

## 六分叉处置表

| # | 分叉 | 处置 |
|---|------|------|
| ① | 同名冲突：`{{data_root}}/plugins/<name>/` 已存在 | 先判定用户意图：(a) 更新既有插件 → 在**副本**上改，改完整体替换 + 明确提示「digest 将漂移，需重新 approve；建议 bump version」；(b) 新包撞名 → 换名重生成。任何情况下不静默覆盖 |
| ② | digest 漂移 / 产出后再改动 | 流程定序强制「终检 → 冻结 → 交付」；交付后不得再写。approve 前要改：改完重跑校验重走终检，approve 以终检后 digest 为准 |
| ③ | 描述质量不合格 | 校验脚本 M6-M11 机械拦截 → 自修 ≤2 轮 → 仍不过 = blocked 上报（附逐条失败原文），禁止降级交付 |
| ④ | name 非法 / 保留前缀冲突 | 生成期即校验（M3/M4）：非法 → 按 §5.5 规则改名；保留前缀命中且非官方 → 换名。不落盘才发现 |
| ⑤ | 装载拒载（scan 时被官方装载器拒绝） | 自证路径：`GET /api/plugins` 看 rejected 原因（或 `nebflow plugin list`）→ 按原因修包。M1-M5/M14 已前置镜像绝大多数拒载规则，此项是兜底 |
| ⑥ | mcp.json 组件违规 | 组件违规不拒整包但会静默降级——校验脚本 M14 按装载规则逐 entry 镜像检查，不让「装上了但 MCP 组件无效」蒙混过终检 |

## approve 衔接（default-deny 必经，人审）

终检 PASS 后，在交付报告固定附审批指引原文：

> 本插件包已落盘 `{{data_root}}/plugins/<name>/`，当前为 untrusted（信任门默认拒绝）。
> 请审阅包内容后任选其一完成审批：Plugin 面板开关、
> `POST /api/plugins/<name>/approve`、`nebflow plugin approve <name>`。
> 审批记录当前目录 digest；此后任何文件改动都会重新触发审批。

人审要点提示（供你转述给用户）：审批人审的是**包内容**（skill 全文、manifest、
脚本），不是格式合规——格式已由校验脚本把关。审批清单会展示元信息、skills 预览、
红标项与变更摘要。

**生效时机**：approve 后，下一次分发器 spawn 的插件目录注入才会带上新插件
（活跃分发器会话不刷新目录快照）——须向用户说明「approve 后新派任务才会分配到
新插件」。

## 交付报告模板

```
① 产物：{{data_root}}/plugins/<name>/（包结构树）
② 校验：M1-M15 全 PASS（逐条摘要 + digest sha256:<值> files=<N>）
③ 状态：untrusted 待批（绝不代批）
④ 审批指引：面板 / REST approve / CLI approve 三入口 + 生效时机说明
⑤ 红标项（如有）：凭据类 env、shell 类 command、实验性 mcp.json 形态
```

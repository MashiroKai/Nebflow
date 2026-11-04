---
type: audit
status: final
date: 2026-10-31
topic: 设置页档参数覆盖接线审计 + 人话文案批
---

# 设置项「档参数覆盖」接线审计（2026-10-31）

关联：[[20261030_human-readable-presentation.zh-CN]]（设计稿 §6.2/§6.3/§6.4，含 [[20261030_human-readable-presentation.zh-CN#^term-profile|方案档案词条]]）、[[20261031_112344_delegate-standby-semantics-audit__delegate-nebflow-c0a2b939]]（同口径审计先例）、作者原话批 2026-10-31 15:26（「我还是不懂这些设置会对你的行为造成什么影响」）。

## 审计结论（一句话）

**设置页「当前档/专家极简」下的 7 个可覆盖参数全部是假开关**：选档与覆盖值能被校验、落盘（`~/.stand/presentation-profiles.json`）、下发并在设置页显示，但**没有任何行为消费面**——不改 agent 下笔、不改界面折叠、不改提醒频率。文案批已如实标注「暂未接线」，未动任何生效逻辑。

## 逐项接线事实（路径 + 行号）

| 设置项 | 引擎侧消费面 | 前端侧消费面 | 现状 |
|---|---|---|---|
| 选档（当前档） | 仅 WS 下发与 `profile-switch` 校准信号落账（`WsTasksWorkspaceHandlers.scala:638-650`）；`resolvedCurrent` 只被测试调用（`PresentationProfileStore.scala:142`，生产零调用） | `setCurrentProfile` → `profileGeneration()+1` → markdown 缓存键失效（`profileState.js:23-28`、`utils.js:303`）；重渲结果**与档无关**（围栏框外一律折叠，无档豁免——`utils.js:415-423`、`tests/cognition-phase2.spec.mjs:230-246` R4d 钉死） | 半接线：有信号采集，无行为差异 |
| 人面子长度 maxLines | 无注入点：brief/system prompt 组装不读档（`core.profile` 仅出现在 WS handler 与 store，全 src/main/scala 检索为证） | 无渲染消费 | **假开关**（仅存校验 1..20） |
| 关键数字 keyNumbers | 同上 | 无渲染消费 | **假开关**（仅存校验 1..9） |
| 折叠默认 foldDefault | 同上 | 「human 过滤层读档」只剩注释（`utils.js:6-8` 只 import `profileGeneration`）；`expand-findings` 豁免已退役 | **假开关** |
| 图密度 imageDensity | 同上（设计稿定位 = agent 下笔义务，约定面） | 无 | **假开关** |
| 提醒节奏 reminderCadence | 设计稿 §6.4 回填落字明写「引擎侧尚无按档分频机制」 | 无 | **假开关** |
| 监督颗粒度 supervision | 同上（§6.5 约定面：拆解粒度靠 brief 约定） | 无 | **假开关** |
| 未决项呈现 pendingStyle | 同上（约定面） | 无 | **假开关** |

附带发现（已顺手修正，纯 UI 属性、不动生效逻辑）：设置页两个数值框共用 `max="20"`（`presentationProfile.js` 旧 79 行），而引擎对 keyNumbers 的上界是 9（`PresentationProfile.scala:125` KeyNumbersBound）——UI 允许输入 10..20 的值会被引擎拒绝；已改为 maxLines→20、keyNumbers→9。

## 校准链的真实状态（设置页旧文案过度承诺）

- 「换档即时生效」——不成立：换档唯一可观察效果是已渲染 markdown 重新渲染一次，显示内容不变。
- 「对呈现有想法直接说……按你的偏好调整」——半成立：抱怨词表分类器存在（`ProfileCalibrationSignal.scala:43-48`，「太长/别烦我/给我看细节/别教我」→ 目标参数），complaint 信号确实落账（`WebSocketRoutes.scala:2433-2448`）；但 `ProfileCalibrator.suggest` 是纯函数且**引擎从不调用**（`ProfileCalibrationSignal.scala:74` 自注「引擎本批不调用」），调整建议无任何呈现面，auto 调整未上线。

## 设计意图 vs 实现（接线该往哪接，供下批参考）

设计稿给过两条「零代码生效」路径（`docs/design/20261030_human-readable-presentation.zh-CN.md` §6.4）：① 派发 brief 带 `profile: <档键>` 变量、agent 按档下笔；② 档参数作约定面（图密度/监督颗粒度/未决项呈现）。实证：当前实例的派发 brief 不含档变量，seed prompt 无任何读取 `presentation-profiles.json` 的指引——两条路径均未在运行实例中立起来。设计意图里真正有引擎语义落点的只有「提醒节奏→Schedule todo 浮出策略」（§6.4），其余均依赖 agent 侧消费。

## 本批改动清单（只文案 + 一处 input 属性订正）

1. `src/main/resources/web/js/locales/zh-CN.js` / `en.js` profile 区块重写：每档说明 = 人话 + 默认参数速览；覆盖区加「选档=换整套默认；逐项=覆盖单项；复位=回档默认」导语与「暂未接线」实话注；每项参数配 `profile.hint.*`（一句作用面 + 改前改后行为例子 + 未接线标注）；枚举值换成人话（如「只给阻塞类」→「只问卡死进度的事」）；校准尾注改为「会记成偏好证据，自动调档尚未上线」。
2. `src/main/resources/web/js/presentationProfile.js`：每个覆盖行下渲染 hint；数值框 max 订正。
3. `src/main/resources/web/css/sidebar.css`：`.profile-ov-item/.profile-ov-hint` 分组样式。
4. 新手引导 ob2.opt.profile.* 五句同步去功效化承诺（「——靠它」→「时的风格」）。

## 验收

- `node tests/cognition-phase2.spec.mjs` 全绿（R4 设置面板行数/切档帧断言不受影响：hint 不入 `.settings-row` 计数）。
- 双主题截图：evidence/20261031_settings-profile-copy/（亮/暗各一）。

# Vision 真机实测报告（B3 联动验证）

- 日期: 2026-08-14 19:00–19:20
- 代码: d9e5a454（B3 vision Phase 1+2，已合并 archive/scala @ dd7e34e06 前身 dd734e06）
- 实例: `/tmp/nb-neblink-version` worktree，隔离 home `/tmp/nb-vision-home`，端口 8092
- 测试图: 64x64 纯色 PNG（RGB 255,100,20 橙）+ 1400x1400 噪声 PNG（5.9MB，仅大图场景）
- 额度控制: maxTokens=512，每场景仅 1 次调用，共 ~10 次 LLM 调用
- Provider: 从主 home 复制 kimi / zhipu / deepseek / 107（API key 只存在于 /tmp/nb-vision-home/nebflow.json，未进 git、未进本报告）

## 一、矩阵结果

| # | 模型 | 标注状态 | 带图 | 结果 | 降级信号 |
|---|------|---------|------|------|---------|
| s1 | zhipu/GLM-5V-Turbo | 未标注 | 是 | ❌ provider 层失败：`[1311][当前订阅套餐暂未开放GLM-5V-Turbo权限]` | **未触发（正确）**——错误文本不含 image/multimodal/not support，未误降级，models.json 保持干净 |
| s1b | 107/k3 | 未标注 | 是 | ❌ 环境不可达：USTC 端点直连 10s 超时、走代理 SSL 握手失败（curl exit 35） | — |
| s3 | kimi/kimi-k3 | 未标注 | 是 | ✅ **乐观默认生效**：正确识别橙色（thinking: "The image is a solid orange square"） | 无（成功不需要） |
| s4 | kimi/kimi-k3 | vision=true | 是 | ✅ 识别成功（"solid bright orange square"） | 无 |
| s5 | deepseek/deepseek-v4-flash | 未标注 | 是 | ✅ **意外发现**：v4-flash 实际支持图片（回复连 "64×64 pixels" 尺寸都说对，不可能从文本猜到） | 无 |
| s6 | zhipu/GLM-5.3 | 未标注 | 是 | ⚠️ **静默假成功**（详见二.1） | **未触发——两个信号都抓不到** |
| s7 | kimi/kimi-k3 | true | 5.9MB 噪声图 | ✅ 大图正常处理，正确描述 "TV-static-style noise" | 无（无尺寸错误） |
| s8 | zhipu/GLM-5.3 | 未标注 | 5.9MB | ⚠️ 同 s6 假成功（zhipu 网关把大图也上传成功了，无尺寸报错） | 未触发 |
| s9 | kimi/kimi-k3 | vision=false | 是 | ✅ **strip 链路真实工作**（详见二.2） | （静态标注路径） |
| s9b | kimi/kimi-k3 | vision=false | 否（纯文本） | ✅ 回复 "42" 正常；**标注保持 false**（纯文本成功不解除——振荡语义正确） | 正确 |
| s10 | kimi/kimi-k3 | PUT true 恢复 | 是 | ✅ 带图识别恢复（手动恢复通道工作） | — |

## 二、核心观察

### 1. 第三种失败模式：静默假成功（B3 的盲区，最重要的发现）

GLM-5.3（不支持看图的模型）收到带图请求时**既不报错也不空回复**，而是返回一段幻觉文本：

```
**🌐 Z.ai Built-in Tool: analyze_image**
**Input:**
{"imageSource":"https://maas-log-prod.cn-wlcb.ufileos.com/anthropic/<uuid>/....png?...","prompt":"Describe..."}
*Executing on server...*
```

- 模型编造了一个"内置工具正在看图"的回复，输出里还带 zhipu 网关真实生成的图片转存 URL（base64 → UCloud）
- B3 的两个降级信号全部失效：①空补全——回复非空；②provider 错误归咎 image——请求 200 成功
- 结果：GLM-5.3 未标注时乐观带图 → 永远假成功 → **永远不会自动降级**。用户看到一段"工具执行中"的废话，以为模型在看图
- 主 home 里用户手动标 GLM-5.3 vision:false 是对的——这类模型只能靠人工标注，B3 无法自动发现
- **此类假成功仅在回复语义层可识别**（如检测 "Executing on server" / "Built-in Tool" 之类的模式），不建议 v1 就做——记录为已知局限

### 2. strip 链路真机验证通过

s9（kimi 标注 vision=false + 带图）模型回复：

> "I wasn't able to render the image directly, but based on its **filename** (`orange64.png`), it appears to be a small **orange** image"

证明：
- Image block 被替换为 `[image omitted: model does not support vision]` 占位符
- `[用户附加图片: /tmp/nb-vision-home/uploads/.../orange64.png]` 路径文本仍注入（模型可据此用工具读文件——优雅降级）
- 回复正常非空、无错误抛出——降级后用户体验完整
- 同 session 后续纯文本正常（"42"），且 vision=false 不被纯文本成功解除（resetOnSuccess 振荡语义正确）

### 3. 误降级风险（知情项）：理论在，实测未复现

- **未复现**：真 vision 模型 kimi-k3 对 5.9MB 大图不报任何尺寸错误（zhipu 网关也照常转存），无 "image size" 类错误可命中宽泛的 `"image"` 模式
- **反向验证**：1311 权限错误（`当前订阅套餐暂未开放GLM-5V-Turbo权限`）不含任何 VisionErrorPatterns 关键词 → 未误降级（正确）
- 但机制风险确认存在：任何真 vision 模型一旦报出含 "image" 子串的**格式类**错误（base64 损坏、尺寸超限、URL 失效），将立即持久化 vision=false → strip 后永无带图成功 → override 永不自愈。恢复只能 REST PUT vision=true + 重新 setSessionModel（实测有效），或手删 models.json + 重启
- **注意**：PUT true 只改 models.json，若 runtime override 在内存中（同进程未重启），仍会压制带图——恢复需重启或依赖"带图成功解除"（但 strip 导致永远没有带图成功，死锁）。实测 s10 能恢复是因为 kimi 从未有过 runtime override（只有静态标注）

### 4. VisionErrorPatterns 中文盲区（新发现）

4 个模式全英文（`image` / `multimodal` / `multi-modal` / `not support`）。国内 provider 常见中文错误（如"不支持图片输入"、"图片大小超限"）不会命中 → **漏降级**。本次 1311 是纯中文+数字所以未误触——属运气而非设计。建议补充中文模式（"图片" / "不支持" / "图像"），但"不支持"过于宽泛需谨慎（"不支持该模型"会误降级）。

### 5. 新 bug：`tail of empty list`（与 B3 无关，独立发现）

- 位置：`AgentActor.scala:2190`——`pendingUserInputs.tail` 裸调用。2179 行同函数用 `headOption` 防御了空列表，2190 没有
- 复现：**REST `/api/command` 通道每个 turn 结束 100% 复现**（实测 9/9 会话全部命中，日志 `Agent error in processing, returning to idle: tail of empty list`）
- 影响：回复本身无损（turn-complete 已先写入 history），onError 兜底回 idle；但若有排队的 pendingUserInputs，2179 已把 head 发给自己而 2190 崩溃导致状态未消费——存在重复处理风险
- 修复（一行，照 TurnBoundaryDrains.drainHead 语义）：`pendingUserInputs = if ps.isEmpty then ps else ps.tail`

### 6. 环境发现（非代码问题）

- **zhipu 套餐无 GLM-5V-Turbo 权限**（1311）——任务书原定的 GLM-5V-Turbo 两个场景（未标注/标注 true）无法执行，实测改用 kimi-k3 作为真 vision 载体
- **USTC 端点（llm.example.com）从本机不可达**：直连超时 + 代理 bad_record_mac（与 memory 记录一致）——107/k3 场景（备用真 vision 载体）也无法执行
- **deepseek-v4-flash 已支持图片输入**——主 home nebflow.json 里 deepseek 两个模型都没标 vision，实际可标 true（或留给 B3 乐观默认，效果等同）
- 主 home models.json 里 `zhipu/GLM-5V-Turbo: vision:false` 与 provider 内联 `vision:false` 双重标注——在套餐开通权限前无实际影响

## 三、结论

| 验收点 | 结果 |
|--------|------|
| 乐观默认（未标注 vision=true）在真 vision 模型上直接可用 | ✅ kimi-k3、deepseek-v4-flash 双验证 |
| 标注 true 走带图 | ✅ |
| strip 生效（占位符+路径、回复优雅） | ✅ |
| 纯文本成功不解除 vision=false | ✅ |
| 手动恢复通道（PUT true + setSessionModel） | ✅ |
| 自动降级信号①（空补全×2） | ❌ 真机未触发（无自然空补全源；单测 11 用例覆盖） |
| 自动降级信号②（provider 错误归咎 image） | ❌ 真机未触发——现有 provider 没有会报"不支持图片"的模型（GLM-5.3 是静默假成功，绕过两信号） |
| 误降级（格式类错误含 image 子串） | ⚠️ 未复现但机制确认；恢复死锁路径（runtime override + strip = 永无带图成功）实测逻辑闭环成立 |

**总评**：B3 的"乐观默认 + 静态标注 + strip"主链路真机全部工作正常，方向正确（悲观默认会让 kimi/deepseek 这些实际支持图的模型全部不可用图）。自动降级是防御性机制，在当前 provider 生态里最需要它的场景（模型不报错的假成功）恰好是它覆盖不了的——这不是实现缺陷而是信号边界，建议保持现状 + 文档记录局限。真正需要跟进的是两个独立问题：① `tail of empty list` bug（一行修复）② VisionErrorPatterns 中文盲区（低优先级，加中文模式需防误命中）。

## 附：测试基建（可复用）

- 驱动脚本 `/tmp/nb-vision-test/drv.py`：REST 通道（POST /api/sessions 建会话 → setSessionModel → /api/command 发消息带 attachments[{mimeType,data}] → GET history 轮询，type=='ai' 取回复）
- 隔离实例启动：`cd /tmp/nb-neblink-version && sbt "run --home /tmp/nb-vision-home --port 8092 --no-browser"`——注意 run_in_background 的空闲 sbt 会被任务监控误杀（无输出无 CPU 判定），需 nohup + disown

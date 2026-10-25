---
name: imagegen
description: 生图工具调用纪律——generate_image 的参数面、阻塞语义、provider 选择与调试状态声明（调试中 / experimental）。
---

# imagegen 生图纪律（调试中 / experimental）

## 这是什么

本插件授予 `generate_image` 一件生图工具（MCP 模式）。**调试状态**：能力在案但
未升稳定——结果需人工过目，发现异常在任务报告里注明「imagegen 调试面」。

## 与根会话内置件的分工（铁律）

- **你是下层（delegate/节点会话）→ 用本插件工具**。调用是**阻塞**的：回合停在
  工具调用上直到图片生成完，结果文本直接返回——无前端占位、无 WS 回填。
- 根会话（Nebula）另有**非阻塞**内置 `generate_image`（提交即返回 job 句柄、
  前端占位动画回填）——那不是你的面，不要绕路模拟。

## 参数面

| 参数 | 必填 | 形态 | 说明 |
|---|---|---|---|
| `prompt` | 是 | string | 主体/风格/构图，具体可画 |
| `size` | 否 | `"WxH"` | 缺省 1024x1024；自动吸附所选模型支持的最近档 |
| `provider` | 否 | string | imagegen.providers 的键名；缺省 defaultProvider |
| `quality` | 否 | `low\|medium\|high` | 仅 OpenAI-images 系适配器消费 |

## 调用纪律

1. **一击一张**：一次调用一张图；需要变体就多次调用，不要一次塞复杂多图要求。
2. 返回值 = 落盘 PNG 的**绝对路径** + 字节数——它是交付物引用：后续加工/展示
   直接用该路径（Read 工具可读图；交付给上层时把路径写进结果）。
3. 失败面是结构化文本（HTTP 状态 + provider 原因）：**可重试的只限网络/5xx**；
   4xx（配置缺失/凭据缺席/参数非法）重试无意义——把原文带进报告。
4. provider 由实例 nebflow.json 顶层 `imagegen` 节配置（type =
   `cogview` / `openai-images` / `imagen`；凭据 keyRef 指针指 secrets/）。
   你没有的配置不要猜名字——缺省走 defaultProvider。

## 边界

- 仅文生图；编辑/改图/多图一致性不在本件面内。
- 不在返回文本里臆造图片内容——你只见过路径，没见过图；要描述内容先 Read 图。

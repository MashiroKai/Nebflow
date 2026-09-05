# STT 协议支持（SttService 双协议自动识别）

**状态**：已实现（2026-08-21，feat/stt-mimo-chat-protocol）｜**配置入口**：Settings → STT（advance 折叠面板），落盘 `~/.nebflow/stt-config.json`

## 协议识别规则

**不加配置字段**，按 `endpoint` 后缀自动分流（trim 后判定）：

| endpoint 形态 | 协议 | 适用服务 |
|---|---|---|
| 以 `/chat/completions` 结尾 | chat 协议（JSON） | 小米 MiMo 等「OpenAI API Compatibility = chat」的服务 |
| 其他（如 `/v1/audio/transcriptions`） | multipart transcriptions（原路径） | OpenAI Whisper 风格服务 |

## chat 协议（MiMo 实测定稿，0.8s 中文转写验证）

MiMo 的「OpenAI API Compatibility」是 **chat/completions 协议**而非 transcriptions——不存在 `/v1/audio/transcriptions`（全部 404）。

```jsonc
// POST {endpoint}  Authorization: Bearer <key>  Content-Type: application/json
{
  "model": "mimo-v2.5-asr",
  "messages": [{
    "role": "user",
    "content": [{
      "type": "input_audio",
      "input_audio": {
        "data": "data:audio/wav;base64,<AUDIO_B64>",   // WAV 16kHz mono
        "format": "wav"
      }
    }]
  }],
  "asr_options": { "language": "zh" }   // 仅接受 auto/zh/en
}
// 响应：标准 chat completion，转写文本在 choices[0].message.content
```

**语言映射**：前端传 BCP-47（`zh-CN`/`en-US`/...）→ 后端 `startsWith` 前缀映射（zh→zh、en→en、其余含空→auto）——MiMo 只接受三值。

**MiMo 配置示例**：endpoint=`https://api.xiaomimimo.com/v1/chat/completions`，model=`mimo-v2.5-asr`，key 见平台。

## multipart transcriptions 协议（原有路径，未改）

`file`（audio.wav）+ `model` + `language`（原样 BCP-47）multipart 表单；响应 `{"text": "..."}`（parse 失败回退纯文本）。兼容 OpenAI Whisper / zhipu `audio/transcriptions` 等。

## 前端契约（零改动）

voiceEngine.js 发 WAV 16kHz mono base64（WS `{type:'transcribe', audio, language}`）——两协议共用，前端无感知。apiKey 永不回传前端（serverConfig 仅 endpoint/model）。

## 错误处理

非 200 → `warnSync` 日志（`STT API error <code>: <body 截断>`）+ `Left` 错误消息回传前端 toast。**注意**：`IO.blocking` 语句位必须用 `*Sync` 变体——裸 `logger.warn` 返回 `IO[Unit]` 在语句位被丢弃是死日志（死日志家族「语句位」盲区，grep 模式抓不到，由 `SttServiceProtocolSpec` ListAppender 钉子把门）。

## 测试

`src/test/scala/nebflow/gateway/SttServiceProtocolSpec.scala`（7 用例）：协议分流请求形状（Content-Type/JSON 字段/data URL 精确值/base64 往返）、语言映射、choices 解析、畸形响应、非 200 错误回传 + WARN 实发钉子、multipart 回归、空音频零请求。变异验红：协议识别失效/语言映射失效/warnSync 退化为死日志三路独立红。

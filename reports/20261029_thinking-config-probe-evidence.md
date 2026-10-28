# thinking-config 探测定性证据（脱敏）——2026-10-29

批：thinking-config 实施批（设计 `docs/design/20261028_215449_thinking-config-research__delegate-nebflow-ca5f09f4.zh-CN.md` §6-1/§6-2/§6-8 探测腿）。
方法：空/极小请求（max_tokens 16，例外 Z1=1024）看参数接受度与 4xx 报错原文；凭据从 live
`~/.stand/nebflow.json` 读入内存，所有转录已把 key 字符串替换为 REDACTED（req.json 不入库）。
配额面：每请求 ≤16 可见 token（Z1 ≤1024），合计极小；无真实推理负载。

## 逐请求结论速览

| # | 端点 / 模型 | 探针 | 结果 | 定性 |
|---|---|---|---|---|
| Z0 | zhipu anthropic 面 / glm-5.3-flash | `thinking:{type:enabled}`（无 budget_tokens） | 200 | **第一段 glm 新 wire 形态安全**（thinking 块照常返回） |
| Z1 | 同上 | budget==max（1024/1024） | 200 | **zhipu 不执行 Anthropic budget<max_tokens 不变式** ⇒ budget 未被深度校验/按忽略处理方向 |
| Z2 | 同上 | 顶层 `reasoning_effort:"high"` | 200 | **effort 接受** ⇒ glmEffort 门置真 |
| Z3 | 同上 | 顶层 `reasoning_effort:"medium"` | 200 | 接受（档位词表不强拒；客户端仍按官方映射 medium→high） |
| Z4 | 同上 | `output_config:{effort:"high"}` | 200 | 接受/容忍（未采用） |
| K1 | api.kimi.com/coding / kimi-for-coding | `thinking:{type:enabled}`（无 budget_tokens） | 200 | **第一段 kimi-for-coding 新 wire 形态安全** |
| K2 | 同上 | `thinking:{type:disabled}` | 200 | **该端点对 disabled 不 400**（比 platform.kimi.com 官方 k2.7 文档宽松）——「disabled→400」前提在此端点被证伪；引擎仍走省略/兜底 enabled 的保守形态 |
| K3 | 同上 / k3.1-pro-highspeed | **旧 wire 形态** `thinking:{type:enabled,budget_tokens:32000}` | **000 超时×3**（40s 零字节） | **旧形态在该端点挂死** ⇒ 第一段「K3 移除 thinking 配置」获强佐证 |
| K4 | 同上 | 无 thinking 键 | 200 | **第一段 k3.1 新 wire 形态安全**（秒回，thinking 块照常） |
| K5 | 同上 | 顶层 `reasoning_effort:"high"` | 200 | **effort 接受** ⇒ kimiK31Effort 门置真 |
| K6 | 同上 | `reasoning_effort:"medium"` | 200 | 接受（客户端映射 medium→high） |
| C1 | cmdcode openai 面 / deepseek-v4.1-flash | `reasoning_effort:"high"` | 200 + `reasoning_content` | 现配面（openai 协议）effort 参数**已在产线语义内**（即旧 budgetToEffort 直发档位的现状） |
| C2 | 同上 | 无附加参数 | 200 | 基线 |
| C3 | cmdcode **anthropic 面** /v1/messages | 最小请求 | **400** `Model "deepseek/deepseek-v4.1-flash" is not supported on this endpoint. Use /provider/v1/chat/completions for OpenAI and OSS models.` | **cmdcode 无可服务 anthropic 面** ⇒ requireThinkingPassback 风暴在该链路结构性不可达；output_config.effort 无送达面 ⇒ deepseekEffort 门保持关 |

## glm 现网疑点定性（§6-2：budget 32000 是否被计入 max_tokens）

- Z1：budget==max_tokens 不被拒 ⇒ zhipu 兼容面**不执行** Anthropic budget 不变式（解析即强校验的形态排除）。
- 生产行为（`logs/nebflow.2026-10-15.0.log`，1020 个 glm turn 的 message_delta outputTokens）：
  p50=626 / p90=3607 / **max=20760**（< budget 32000）——若「budget 计入 max_tokens 且模型吃满预算」
  的饥饿形态成立，应出现大量 ≈32768 钉顶 turn；观测不符。
- 结论：**无证据支持饥饿形态**（倾向忽略/宽容处理）；且第一段起 glm wire 不再发 budget_tokens，
  该疑点在新线上形态下不可达。族帽 32768 按令不动。

## 附：cmdcode 协议面考古修正（对调研文档 §1.5/§2.4 的勘误）

live 配置 `cmdcode.protocol = "openai"`（baseUrl `/provider/v1/`）——调研文档「三家现配全走
anthropic 协议」对 cmdcode 不成立：其思考参数**今日走 OpenAiAdapter.thinkingBody →
顶层 reasoning_effort（budgetToEffort(32000)="high"）**，budget_tokens 从未在 cmdcode 链上
发出；「cmdcode 不发 budget_tokens」结构性已满足。anthropic 面的 passback 机制对 cmdcode
不生效（registry 只为 anthropic 协议构造该开关）。

## 原始转录（已脱敏）


### Z0-glm-thinking-no-budget
```
== Z0-glm-thinking-no-budget status=200
{"id":"msg_2026102900055133feff90e10d4939","type":"message","role":"assistant","model":"glm-5.3-flash","content":[{"type":"thinking","thinking":"Let me consider how to respond to this greeting effectively.\n\nThe user has initiated contact","signature":"fc8b282e563a4fd5b85d65a3"}],"stop_reason":"max_tokens","stop_sequence":null,"usage":{"input_tokens":13,"output_tokens":16,"cache_read_input_tokens":0,"server_tool_use":{"web_search_requests":0},"service_tier":"standard"}}
```

### Z1-glm-budget-eq-max
```
== Z1-glm-budget-eq-max status=200
{"id":"msg_202610290005539c267d1d6a7645d3","type":"message","role":"assistant","model":"glm-5.3-flash","content":[{"type":"thinking","thinking":"Let me consider how to respond to this greeting effectively.\n\nThe user has initiated contact with a simple \"hi,\" which suggests they may be testing the waters or just starting the conversation. This calls for a warm and inviting response that encourages further interaction.\n\nI should acknowledge their greeting in a friendly way and make them feel welcome to share more about what they need help with. It would be good to keep the tone approachable and professional, while also being open to various types of questions they might have.\n\nSince they haven't specified any particular topic or question, I should keep my response flexible and inviting. Perhaps I could offer some guidance on what I can assist with, while leaving the door open for them to direct the conversation.\n\nThe response should be concise yet welcoming, making it easy for them to continue the dialogue if they wish. I want to strike a balance between being helpful and not overwhelming them with too much information.\n\nLet me craft a response that is warm, professional,
```

### Z2-glm-effort-high
```
== Z2-glm-effort-high status=200
{"id":"msg_2026102900055951a370c1472c4f7c","type":"message","role":"assistant","model":"glm-5.3-flash","content":[{"type":"thinking","thinking":"Let me consider how to respond to this greeting effectively.\n\nThe user has initiated contact","signature":"4edf1b11a868487bb5aadbe4"}],"stop_reason":"max_tokens","stop_sequence":null,"usage":{"input_tokens":13,"output_tokens":16,"cache_read_input_tokens":0,"server_tool_use":{"web_search_requests":0},"service_tier":"standard"}}
```

### Z3-glm-effort-medium
```
== Z3-glm-effort-medium status=200
{"id":"msg_2026102900060149f5c7145c3842f7","type":"message","role":"assistant","model":"glm-5.3-flash","content":[{"type":"thinking","thinking":"Let me consider how to respond to this greeting effectively.\n\nThe user has initiated contact","signature":"6ea9b99a57a842fe9d8b0996"}],"stop_reason":"max_tokens","stop_sequence":null,"usage":{"input_tokens":13,"output_tokens":16,"cache_read_input_tokens":0,"server_tool_use":{"web_search_requests":0},"service_tier":"standard"}}
```

### Z4-glm-output-config
```
== Z4-glm-output-config status=200
{"id":"msg_20261029000603e47575ee1da74ad0","type":"message","role":"assistant","model":"glm-5.3-flash","content":[{"type":"thinking","thinking":"Let me consider how to respond to this greeting effectively.\n\nThe user has initiated contact","signature":"2d5fbc2a786a4324aa73e024"}],"stop_reason":"max_tokens","stop_sequence":null,"usage":{"input_tokens":13,"output_tokens":16,"cache_read_input_tokens":0,"server_tool_use":{"web_search_requests":0},"service_tier":"standard"}}
```

### K1-kfc-thinking-no-budget
```
== K1-kfc-thinking-no-budget status=200
{"id":"msg_IpyYwBV6B27FZWK8cH3AbeU5","type":"message","role":"assistant","content":[{"type":"thinking","thinking":"Simple greeting.","signature":"6sFMr/CN5M7P9WyPlutfuIVKeA5lt4soKyG6DBbqzQUtTkg4e1ANQpQetbUg+9uDtFHdDD6kXa0u315LdOeXgbz94vaEYNe8zdqTWdMWvY0EXlM/+2/L6rJER0PC8CgsRrTPx5ZVJtbuxmCwmmuUgfpEVJw74dpQgyC6swCuk5KMhCL1tryOISuTgZWHVydrZGpESdovp/bQq9ha9R43WBS8CNxeVHdKAGOE7yjPT+uWlwjrlCg5GbE+MBJymxIWL7cY/ltq3QB65f4GgaPQ8M6vBsdJYvTdo5kJFrcW1phAipvtZkFOOgzQJrjANYZv9mFVpQ+/RDGPFMuIsQsWV3Bn/mvF72/bXshOHM2LHWOmuYIUs53TKOSzZKuA3mY70+vpOS2FpZIrvtvrmO3CuOBhEBMS5tp97tKDETFGbLAYYx5DgrAj5V/gCOVpPRwO2qTRw9J7r/+hDx9OiDHlDFcB34FWOvlkftK0eXQvu7FvibQCUXB5Rdk4s+EfwOWE6zQWOpyJ2KdPtnHKtFCL1OUB7SxJ5GDl/hFRJjyiJWRSDVWMDQZ0OqKWDkEfEapFHtWVPhO+AnRWoOy0rX2JTRCbmcn+BTB66qpbpAcS31UtDerr1+oU5wg7lzsaeem+Lj+DnjN9ecZHtZLljHY/4yDwi4L44t6YDLggaODnBIta9UlLwtZmqhzFuOmguOqh1kUXTTmfxDoOA1Q6yi+n/qlAPg/fItDxxU2lHCNFSACtQhep7Eyp2CT5Jim81CzDjjrCdzLtUS+fIzdKphuMXOubPvVT7XZQYaa0IgvCph3dhamMiLPky7G+PyCEM362MjbZ9v35jUjyFltbekCjpmXNJ+gt2serBnJssb2SeZhHFFDRTfqdF295kTYlZlCmwYKuOqDGn+vG+kwKFUsukk0+PDMqa07hoy4A4QupQpt2GFSEV3Dks0Ie1AZAQZX8gXp5oGG7mv2MnlcMHJIC/JEm1XobJ92E8BeCueNFY+9rNbhnWd8/CyI2WP9WUy9mYUxyABxQeq7JB6wH28ZHo5KPC2uSEt
```

### K2-kfc-thinking-disabled
```
== K2-kfc-thinking-disabled status=200
{"id":"msg_MywAejmbWXlY2jXoMMhQ1tRa","type":"message","role":"assistant","content":[{"type":"text","text":"Hi there! 👋 How are you doing today? Is there"}],"model":"kimi-for-coding","stop_reason":"max_tokens","stop_sequence":null,"usage":{"input_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":19,"output_tokens":16,"service_tier":"standard","inference_geo":"not_available"}}
```

### K4-k31-no-thinking
```
== K4-k31-no-thinking status=200
{"id":"msg_ENIx3rKHlccyfoAZWVR0LxZs","type":"message","role":"assistant","content":[{"type":"thinking","thinking":"I need respond to user hi. Should consider identity instruction only if","signature":"8LlWogO3HV5zyfLlEflqwLR689RCxjFjI/g9rQoinp0V8kM6YGd2X2FsPWGvt9ORHcwI8SHqmw26QXr6BslcfzvciczBT/jJ00+no8gb00nse9KH2Q2/M1kwv9fP9G/hQ7SuhoDEB+xYw4hr35gwCFYFmTVElY2RdLpNoxh0BPwhUvcllS2OvViQUQRc5TxqL2P+mdM94K4/TdPxFL/0qMi3r8ZR0K+c/4DpKHrEYNbT6zxXsns3Xm273uBvo/GnphE4XLFzU3gWdqJUSHJ7Fe/6b1rjQMJnoqy3bbxMXXgCkbG0tn22OGvvf1DaqvH41AV3aCe9F025xH5Qv9sbz8lUIVCpxp/T86G+06C0xWAC2Ah0GlIBGQwIBmeL0LYkZwtm9D6oLW8HioS+t4wti21jVQj0OffAQXRQhzg3JkT4wLh1R2ZJaQpU+XeuYVzPUA4YEkhFT1+wwaqhVTqM2O2Iabo1PUfUQaDR2trxJCfFXy1WVDFqS6J74kkBYn/y1F2VUW/NqCJG9D2M/mgHfqdKYg47PJz3+6VgsAwyu4c0YqNzXQV8WgBHcvlVl+DY85OZxM4Zc9ZJHY+PBCZbRnccy+h9wxhDDukDT8+4hefjgoov1iI/UO5eg3Px2BsxsTIFCJEy6SMlY33kMZfhilE5D2naNqyE21pyHNRuSLHOFp7waO+iw3GhXfwwZr1cDAMArviNohm6kz/g5xVPV7ux5hOfu59orNjJIBId/OsXG2X65126VvXZzLY3Rb9IIQb1fTyUYM6jKRsh6Iv5hukvRBkJIX4xqsK//y3/Rn4pT3fZXvF6f2nEX6zNgenp2aIHY3ePNsnVfL8kie3EK2Aq7pVnXxutF1sN7vfpz5jIhX631mPOWtMcj22dj7R/yZdy6ZFcumofllqnlxLAfyK6bF53h3O4p3sFdNnKOhv+O8jf2g9D1jHLDcNkUHvlZVKuZoo9JbkAymUYV3gnvVzrpVA7DZNyugPOHfz
```

### K5-k31-effort-high
```
== K5-k31-effort-high status=200
{"id":"msg_e2DOdKK6JkwEfc4GkHmTmraL","type":"message","role":"assistant","content":[{"type":"thinking","thinking":"I need respond to user hi. Should consider instructions identity? Just","signature":"FMjj7A/byVTGHSco8aEWNNJLB7ctRr8nLZPTmgeS5iV3fgx8/THQVbOY6hZEbVKkdvKi49EflKoGX59QXsBQiDtfAC4i4Xgl0xkfUlYdPNl5omRmT9Sztiunaq/8lfHu523gSicgOzk68NkaHWN6mq9Ksx/bZEKESUgJP2K+3zn5PeqBMDhMAnws7K45W/zuKwLsDfGl/JRK690VuDtageSkWrWGZCh/fL05o1vJLDI8TkU+BcTI83nJpdPHCUUR3EOyr39BfVIz/5TG7nfUemNPPgnWdnBzHFqF81mOQvDmOT2xvH+AFTctaUEftd8d0DoPaIPXYbw2opC3yhqShb4XJ4TMM/4XEzUKY4i8z3NqQSwYvUM5MefGBAfglHL6bnrAreNmlPmM+b7fR2DGKg5UEQwP7rrIyN774g3Bw3RqPVWGJHlhfP/DuVkRAoa4pB+XWkzurzEXXDNrAIcQkSgrmWRJLAD21SoPgt4JOsxP4p+Do+rBDIGNDiXUN0p+P9CIxJvIAtqitgFxedDuLnxxlF08dW4pC7eOsTynSKsK0tlWqhh9T27DCU4VUt0z2fCv2AAHbrA7pVl6zrQicZeU8FYMbKaZ43Biss2ybpg6lO/qr28fVRyfcaFMyrnmD2CkPeeD6JbZ+MxgEAiU9OBRuQ0GQVi0N49ADhatjuZwfsjhN4hWvIQ8AoCr1vyPPEDrgpSgcV8PI7i9pEerP572pUHE45/0dolaqWVtc4lcuaYazGq1AVRcRvw2NxtDZw/dl4C5J2KiLNhtrPMY/SqJfKYRFr2V9Rs5SRcUrBn0o9EkEDE59+cYUp6BlsSMbtMuTx38vXmdAe47BaeEppEfoFM8juO9p3m+Rk/7Pi24mmBdEgQmlFxzOj3XHtJ0m0pnx2TA3m84mg+wK6TI1GzebbDhYgK9I+gHiqTN35unDgdLaj4BaY7viUgcvnIudlg6Qz4efM8mDu/uTqy/dhfXYLtSB+TaYaJOYCYW
```

### K6-k31-effort-medium
```
== K6-k31-effort-medium status=200
{"id":"msg_MrcywvXtyqppKQob1XsGKpdS","type":"message","role":"assistant","content":[{"type":"thinking","thinking":"Kimi needs to respond to user hi. Should follow identity perhaps","signature":"lA6PcSyiyw+xZv1enEPONdfysT+fVypEq82fjjut9R7z0TTmjl8oOgmKHbNv33Jb8lW98BghmRJ3mWChi2KmK9EAKm0GnKp8/u08cm6Pb9V34E0rYmgZj8bgQW9oThHJaJqXunmMNSsDPJO5SQmzf8NeUWoP17A89aUtRVvqGNYzGOl3o2Tv3kp6/Y2Eew2nD4tnydTPboUNC5Xd92NCoX6xK/OtNu3q1vWZd9c6llTNLA/XmjwOkHt2U3AoLjx9rbLWeWbxxD3TAQGG/1QF2/w2jfDym2tUaz8ANmNQ5Idn0lDSDw57LKrPM4S0WDZqoJPE4EpTIg1p4ZLijkSA/WQGVhb3L6d2LRBYlhX2Nc7j56zvBJuShfC0U/L2wNmUFefYt0EZMPpr+vTp4vPF8xcuHdUTwhq01E8gNFjHLjGT4lLzIINp+RolEZxfT1mtjrauYp9dTYKe25FBVj+/emvYYqvcgIzHRExtMpldY7K38Zx5A4XG49BeZ2pjNp5W5UC0f56yfXDSgHUtfk8YUfUp1unCUIbEs5FXrG5dPFH+OwT0nmdq0jDXC+jL35ovLBj5NkGyXlOfc52TgwUMSaOIbPX0byg8IAe3mapDx8QorhkG/LRwGA0DaIrbQD7QnYZJ1uTn0kcB8pvPxFUY9bgM7av1KOswvirG/J95YjQ3pB627zR5YP3ptIVX5MGsOzWQ/42a4Yqax6FT3eweQMqJ9nvEBa1m4fGcY5hspyDjdHBPnUvfTib0qbpvCFK5X4+M9tLO5W+Du9JVPGgwDiICodXRbqqb7Ei45gyFFHrdaJjZOZ9os2bbWHkWCXVuc019nH0yx/HEAQ8kAqU73nS5q1PYpbUR5HdQfYkwuNEwz1XbMlTdL5119wDJ4Ud7gxiDDh/0MN9MZ2bP46+a9bEYfSjZf1vqEbPnvjscb1roEq8mfcBV69G8dCoL9C+LSD1jgpCs0kEYz3t8V9XLakkmarvLkEDVTLTW6CnL2/HdYC
```

### C1-cmd-oai-effort-high
```
== C1-cmd-oai-effort-high status=200
{"id":"4466db9520e321a90b31e1745c1b3616","object":"chat.completion","created":1793203624,"model":"deepseek/deepseek-v4.1-flash","choices":[{"index":0,"message":{"role":"assistant","content":"","reasoning_content":"The user just said \"hi\". I should respond warmly and briefly, maybe ask"},"finish_reason":"length"}],"usage":{"prompt_tokens":31,"completion_tokens":16,"total_tokens":47,"prompt_tokens_details":{"audio_tokens":0,"cached_tokens":0,"cache_write_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"text_tokens":0,"image_tokens":0,"video_tokens":0},"completion_tokens_details":{"audio_tokens":0,"reasoning_tokens":16,"accepted_prediction_tokens":0,"rejected_prediction_tokens":0,"text_tokens":0,"image_tokens":0,"video_tokens":0}},"system_fingerprint":""}
```

### C2-cmd-oai-no-extra
```
== C2-cmd-oai-no-extra status=200
{"id":"d7a434f887f223a3cd4e5ae416a2d755","object":"chat.completion","created":1793203627,"model":"deepseek/deepseek-v4.1-flash","choices":[{"index":0,"message":{"role":"assistant","content":"","reasoning_content":"The user just said \"hi\". I should respond warmly and briefly, inviting them"},"finish_reason":"length"}],"usage":{"prompt_tokens":31,"completion_tokens":16,"total_tokens":47,"prompt_tokens_details":{"audio_tokens":0,"cached_tokens":0,"cache_write_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"text_tokens":0,"image_tokens":0,"video_tokens":0},"completion_tokens_details":{"audio_tokens":0,"reasoning_tokens":16,"accepted_prediction_tokens":0,"rejected_prediction_tokens":0,"text_tokens":0,"image_tokens":0,"video_tokens":0}},"system_fingerprint":""}
```

### C3-cmd-anthropic-face
```
== C3-cmd-anthropic-face status=400
{"type":"error","error":{"type":"invalid_request_error","message":"Model \"deepseek/deepseek-v4.1-flash\" is not supported on this endpoint. Use /provider/v1/chat/completions for OpenAI and OSS models."}}
```

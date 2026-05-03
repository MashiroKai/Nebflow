# Issue 003: Token 估算不准确导致上下文压缩时机不当

## 问题描述

`AgentActor.estimateTokens` 使用简单的字符数除法估算 token 数量：

```scala
// AgentActor.scala:807-821
private def estimateTokens(messages: List[Message]): Int =
  val totalChars = messages.map { m =>
    m.content match
      case Left(text) => text.length
      case Right(blocks) =>
        blocks.map {
          case ContentBlock.Text(t) => t.length
          case ContentBlock.ToolUse(_, _, input) => input.toString.length
          case ContentBlock.ToolResult(_, content, _) => content.length
          case ContentBlock.Image(data, _) => data.length / 10
          case ContentBlock.Thinking(t, _) => t.length
        }.sum
  }.sum
  totalChars / 3
```

使用固定除数 `3`（约 4 chars/token）对英文/ASCII 大致合理，但对 **CJK 字符**（中文、日文、韩文）严重低估。现代 tokenizer 对 CJK 的处理约为 **1-2 字符/token**，而非 4 字符/token。

这导致：
- 中文对话场景下，实际 token 数可能是估算值的 **2-3 倍**
- 自动压缩阈值（80% 上下文窗口）被**延迟触发**，可能在已超出窗口后才执行压缩
- LLM 调用失败或响应被截断

## 当前行为示例

假设 contextWindow = 128K，阈值 = 102.4K tokens：

| 场景 | 实际字符数 | 估算 tokens (`/3`) | 实际 tokens (Claude) | 触发压缩？ | 结果 |
|------|-----------|-------------------|---------------------|-----------|------|
| 中文技术文档 | 300K | 100K | ~150K-200K | 否 | 超出窗口，调用失败 |
| 英文代码 | 300K | 100K | ~75K-100K | 否 | 正常 |
| 混合中英文 | 300K | 100K | ~120K | 否 | 可能超出 |

## 期望行为

Token 估算应：
1. 区分 CJK 字符和 ASCII 字符分别计算
2. 对图片内容使用更合理的估算（当前 `data.length / 10` 对 base64 图片不准确）
3. 考虑 tokenizer 的 overhead（message 结构、system prompt、工具定义等）

## 建议实现方案

### 方案 A：字符分类估算（推荐，零依赖）

```scala
private def estimateTokens(messages: List[Message]): Int =
  val totalChars = messages.map { m =>
    m.content match
      case Left(text) => estimateTextTokens(text)
      case Right(blocks) =>
        blocks.map {
          case ContentBlock.Text(t) => estimateTextTokens(t)
          case ContentBlock.ToolUse(_, _, input) => estimateTextTokens(input.toString)
          case ContentBlock.ToolResult(_, content, _) => estimateTextTokens(content)
          case ContentBlock.Image(data, _) => estimateImageTokens(data)
          case ContentBlock.Thinking(t, _) => estimateTextTokens(t)
        }.sum
  }.sum
  // 添加结构 overhead：每条消息 ~4 tokens，system prompt ~200 tokens，工具定义 ~500 tokens
  val messageOverhead = messages.size * 4
  val fixedOverhead = 700  // system prompt + tools approximation
  totalChars + messageOverhead + fixedOverhead

private def estimateTextTokens(text: String): Int =
  val cjkCount = text.count(isCjk)
  val asciiCount = text.length - cjkCount
  // CJK: ~1.5 chars/token, ASCII: ~4 chars/token
  (cjkCount / 1.5).toInt + (asciiCount / 4)

private def isCjk(c: Char): Boolean =
  // Unicode ranges for CJK Unified Ideographs and extensions
  (c >= '\u4E00' && c <= '\u9FFF') ||    // CJK Unified Ideographs
  (c >= '\u3400' && c <= '\u4DBF') ||    // CJK Extension A
  (c >= '\uF900' && c <= '\uFAFF') ||    // CJK Compatibility Ideographs
  (c >= '\u3000' && c <= '\u303F') ||    // CJK Symbols and Punctuation
  (c >= '\u3040' && c <= '\u309F') ||    // Hiragana
  (c >= '\u30A0' && c <= '\u30FF') ||    // Katakana
  (c >= '\uAC00' && c <= '\uD7AF')       // Hangul Syllables

private def estimateImageTokens(data: String): Int =
  // Base64 图片：估算实际像素数
  // base64 编码后约为原始大小的 4/3，去除 data URI 前缀
  val base64Data = data.split(",").lastOption.getOrElse(data)
  val bytes = base64Data.length * 3 / 4
  // Claude: ~150 tokens per tile (512x512), approximate
  (bytes / 750).toInt.max(150).min(1600)
```

### 方案 B：引入 Tiktoken / Tokenizer 库

使用 `com.knuddels:jtokkit` 或类似库进行精确 token 计数：

```scala
libraryDependencies += "com.knuddels" % "jtokkit" % "1.1.0"
```

```scala
private val encoder = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE)

private def estimateTokens(messages: List[Message]): Int =
  val text = extractAllText(messages)
  encoder.countTokens(text) + overhead
```

优点：最准确  
缺点：增加依赖，不同模型使用不同 tokenizer（Claude 用 claude tokenizer，GPT 用 tiktoken）

### 方案 C：保守估算（最小改动）

简单降低除数，使估算更保守：

```scala
totalChars / 2  // 而非 /3
```

优点：一行改动  
缺点：英文场景会过早触发压缩，浪费上下文窗口

## 需要修改的文件

1. **`src/main/scala/nebflow/agent/AgentActor.scala`**
   - 重写 `estimateTokens` 方法
   - 增加 `estimateTextTokens`、`isCjk`、`estimateImageTokens` 辅助方法

2. **`src/main/scala/nebflow/shared/cjk.scala`**（如存在）或新建
   - 将 CJK 检测逻辑提取为可复用工具

3. **测试**
   - 添加单元测试验证不同语言场景的估算准确性
   - 对比实际 tokenizer 输出作为基准

## 优先级

**中** — 对中文用户影响显著，英文用户影响较小。建议在 Issue 001 之后处理。

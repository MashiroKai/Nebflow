/**
 * protocol.ts — LLM 通信协议类型
 *
 * 跨模块共享的类型定义。所有 LLM 相关类型统一从此文件导入。
 */

/** 消息 */
export interface Message {
  role: "system" | "user" | "assistant" | "tool";
  content: string;
}

/** 工具定义 */
export interface ToolDefinition {
  name: string;
  description: string;
  input_schema: {
    type: "object";
    properties?: Record<string, unknown>;
    required?: string[];
    [key: string]: unknown;
  };
}

/** 工具调用 */
export interface ToolCall {
  id: string;
  name: string;
  input: Record<string, unknown>;
}

/** Fallback 链步骤 */
export interface FallbackStep {
  providerId: string;
  model: string;
  reason: string | null;
  durationMs: number;
}

/** LLM 请求 */
export interface LlmRequest {
  messages: Message[];
  sessionId: string;
  agentId: string;
  tools?: ToolDefinition[];
  maxTokens?: number;
}

/** LLM 响应元数据 */
export interface LlmMeta {
  sessionId: string;
  agentId: string;
  providerId: string;
  model: string;
  durationMs: number;
  fallbackChain?: FallbackStep[];
}

/** Token 用量 */
export interface TokenUsage {
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens?: number;
  cacheWriteTokens?: number;
}

/** LLM 响应 */
export interface LlmResponse {
  reply: string;
  toolCalls: ToolCall[];
  usage?: TokenUsage;
  meta: LlmMeta;
}

/** LLM 工厂配置 */
export interface LlmOptions {
  configPath?: string;
}

/** 流式输出块 */
export interface StreamChunk {
  /** 块类型 */
  type: "text" | "toolCall" | "done";
  /** text 增量（type: "text" 时） */
  delta?: string;
  /** 完整的 tool call（type: "toolCall" 时） */
  toolCall?: ToolCall;
  /** 最终元数据（type: "done" 时） */
  meta?: LlmMeta;
  /** 最终用量（type: "done" 时） */
  usage?: TokenUsage;
}

/** LLM 句柄 */
export interface LlmHandle {
  send(req: LlmRequest): Promise<LlmResponse>;
  sendStream(req: LlmRequest): AsyncIterable<StreamChunk>;
}

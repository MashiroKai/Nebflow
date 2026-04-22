import type {
  Message, ToolDefinition, ToolCall, TokenUsage, StreamChunk,
} from "../protocol.js";

export interface SendMessageParams {
  messages: Message[];
  model: string;
  tools?: ToolDefinition[];
  maxTokens?: number;
}

export interface AdapterResponse {
  reply: string;
  toolCalls: ToolCall[];
  usage?: TokenUsage;
}

export interface ProviderAdapter {
  sendMessage(params: SendMessageParams): Promise<AdapterResponse>;
  sendMessageStream(params: SendMessageParams): AsyncIterable<StreamChunk>;
}

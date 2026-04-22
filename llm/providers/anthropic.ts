import Anthropic from "@anthropic-ai/sdk";
import type {
  Message, ToolDefinition, ToolCall, StreamChunk,
} from "../protocol.js";
import type { ProviderAdapter, SendMessageParams, AdapterResponse } from "./adapter.js";

export async function sendMessage(
  client: Anthropic,
  messages: Message[],
  model: string,
  options?: { tools?: ToolDefinition[]; maxTokens?: number },
): Promise<{ reply: string; toolCalls: ToolCall[]; usage?: { inputTokens: number; outputTokens: number; cacheReadTokens?: number; cacheWriteTokens?: number }; rawResponse: Anthropic.Messages.Message }> {
  const systemMsg = messages.find((m) => m.role === "system");
  const otherMsgs = messages.filter((m) => m.role !== "system");

  const response = await client.messages.create({
    model,
    system: systemMsg?.content,
    messages: otherMsgs as Anthropic.Messages.MessageParam[],
    tools: options?.tools,
    max_tokens: options?.maxTokens ?? 4096,
  });

  const textBlocks = response.content.filter((c) => c.type === "text");
  const toolUseBlocks = response.content.filter((c) => c.type === "tool_use");

  return {
    reply: textBlocks.map((c) => c.text).join(""),
    toolCalls: toolUseBlocks.map((c) => ({ id: c.id, name: c.name, input: c.input as Record<string, unknown> })),
    usage: response.usage ? {
      inputTokens: response.usage.input_tokens,
      outputTokens: response.usage.output_tokens,
      cacheReadTokens: (response.usage as any).cache_read_input_tokens,
      cacheWriteTokens: (response.usage as any).cache_creation_input_tokens,
    } : undefined,
    rawResponse: response,
  };
}

async function* sendMessageStream(
  client: Anthropic,
  messages: Message[],
  model: string,
  options?: { tools?: ToolDefinition[]; maxTokens?: number },
): AsyncIterable<StreamChunk> {
  const systemMsg = messages.find((m) => m.role === "system");
  const otherMsgs = messages.filter((m) => m.role !== "system");

  const stream = client.messages.stream({
    model,
    system: systemMsg?.content,
    messages: otherMsgs as Anthropic.Messages.MessageParam[],
    tools: options?.tools,
    max_tokens: options?.maxTokens ?? 4096,
  });

  const toolCallInputs = new Map<number, { id: string; name: string; input: string }>();

  for await (const event of stream) {
    switch (event.type) {
      case "content_block_start": {
        if (event.content_block.type === "tool_use") {
          toolCallInputs.set(event.index, {
            id: event.content_block.id,
            name: event.content_block.name,
            input: "",
          });
        }
        break;
      }
      case "content_block_delta": {
        if (event.delta.type === "text_delta") {
          yield { type: "text", delta: event.delta.text };
        } else if (event.delta.type === "input_json_delta") {
          const tc = toolCallInputs.get(event.index);
          if (tc) tc.input += event.delta.partial_json;
        }
        break;
      }
      case "content_block_stop": {
        const tc = toolCallInputs.get(event.index);
        if (tc) {
          yield {
            type: "toolCall",
            toolCall: { id: tc.id, name: tc.name, input: JSON.parse(tc.input || "{}") },
          };
        }
        break;
      }
    }
  }

  const message = await stream.finalMessage();
  yield {
    type: "done",
    usage: message.usage ? {
      inputTokens: message.usage.input_tokens,
      outputTokens: message.usage.output_tokens,
      cacheReadTokens: (message.usage as any).cache_read_input_tokens,
      cacheWriteTokens: (message.usage as any).cache_creation_input_tokens,
    } : undefined,
  };
}

export function createAnthropicAdapter(baseUrl: string, apiKey: string): ProviderAdapter {
  const client = new Anthropic({
    baseURL: baseUrl,
    apiKey,
    authToken: null,
    defaultHeaders: { 'X-Agent': 'nebflow' },
  });

  return {
    async sendMessage(params: SendMessageParams): Promise<AdapterResponse> {
      const result = await sendMessage(client, params.messages, params.model, params);
      return { reply: result.reply, toolCalls: result.toolCalls, usage: result.usage };
    },
    sendMessageStream(params: SendMessageParams): AsyncIterable<StreamChunk> {
      return sendMessageStream(client, params.messages, params.model, params);
    },
  };
}

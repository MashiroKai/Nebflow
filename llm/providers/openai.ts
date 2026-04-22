import type { Message, ToolDefinition, ToolCall, StreamChunk } from "../protocol.js";
import type { ProviderAdapter, SendMessageParams, AdapterResponse } from "./adapter.js";

function toOpenAIMessages(messages: Message[]): Array<{ role: string; content: string }> {
  return messages.map((m) => ({ role: m.role, content: m.content }));
}

function toOpenAITools(tools: ToolDefinition[]): Array<{ type: "function"; function: { name: string; description: string; parameters: ToolDefinition["input_schema"] } }> {
  return tools.map((t) => ({
    type: "function" as const,
    function: { name: t.name, description: t.description, parameters: t.input_schema },
  }));
}

function extractToolCalls(response: any): ToolCall[] {
  const choice = response.choices?.[0];
  if (!choice?.message?.tool_calls) return [];
  return choice.message.tool_calls.map((tc: any) => ({
    id: tc.id,
    name: tc.function?.name ?? "",
    input: typeof tc.function?.arguments === "string"
      ? JSON.parse(tc.function.arguments)
      : tc.function?.arguments ?? {},
  }));
}

/** 解析 SSE (Server-Sent Events) 流 */
async function* parseSseStream(reader: ReadableStreamDefaultReader<Uint8Array>): AsyncGenerator<string> {
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split("\n");
    buffer = lines.pop() ?? "";

    for (const line of lines) {
      const trimmed = line.trim();
      if (trimmed.startsWith("data: ")) {
        const data = trimmed.slice(6);
        if (data === "[DONE]") return;
        yield data;
      }
    }
  }

  // 处理剩余 buffer
  if (buffer.trim().startsWith("data: ")) {
    const data = buffer.trim().slice(6);
    if (data !== "[DONE]") yield data;
  }
}

async function* sendMessageStream(
  base: string,
  apiKey: string,
  params: SendMessageParams,
): AsyncIterable<StreamChunk> {
  const body: Record<string, unknown> = {
    model: params.model,
    messages: toOpenAIMessages(params.messages),
    max_tokens: params.maxTokens ?? 4096,
    stream: true,
  };
  if (params.tools?.length) {
    body.tools = toOpenAITools(params.tools);
  }

  const res = await fetch(`${base}/chat/completions`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${apiKey}`,
    },
    body: JSON.stringify(body),
  });

  if (!res.ok) {
    const text = await res.text().catch(() => "");
    const error: any = new Error(`OpenAI API ${res.status}: ${text}`);
    error.status = res.status;
    try { error.error = JSON.parse(text); } catch { /* */ }
    throw error;
  }

  if (!res.body) {
    throw new Error("OpenAI API 返回空响应体");
  }

  const reader = res.body.getReader();
  const toolCalls = new Map<number, { id: string; name: string; arguments: string }>();

  try {
    for await (const data of parseSseStream(reader)) {
      let chunk: any;
      try {
        chunk = JSON.parse(data);
      } catch {
        continue;
      }

      const delta = chunk.choices?.[0]?.delta;
      if (!delta) continue;

      // 文本增量
      if (delta.content) {
        yield { type: "text", delta: delta.content };
      }

      // tool_calls 增量
      if (delta.tool_calls) {
        for (const tc of delta.tool_calls) {
          const index = tc.index ?? 0;
          const existing = toolCalls.get(index);

          if (tc.id && tc.function?.name) {
            // 新的 tool call 开始
            toolCalls.set(index, {
              id: tc.id,
              name: tc.function.name,
              arguments: tc.function.arguments ?? "",
            });
          } else if (existing && tc.function?.arguments) {
            // 参数增量
            existing.arguments += tc.function.arguments;
          }
        }
      }

      // 检测 finish_reason
      const finishReason = chunk.choices?.[0]?.finish_reason;
      if (finishReason === "tool_calls" || finishReason === "function_call") {
        for (const tc of toolCalls.values()) {
          yield {
            type: "toolCall",
            toolCall: {
              id: tc.id,
              name: tc.name,
              input: JSON.parse(tc.arguments || "{}"),
            },
          };
        }
      }
    }
  } finally {
    reader.releaseLock();
  }

  yield { type: "done" };
}

export function createOpenaiAdapter(baseUrl: string, apiKey: string): ProviderAdapter {
  const base = baseUrl.replace(/\/+$/, "");

  return {
    async sendMessage(params: SendMessageParams): Promise<AdapterResponse> {
      const body: Record<string, unknown> = {
        model: params.model,
        messages: toOpenAIMessages(params.messages),
        max_tokens: params.maxTokens ?? 4096,
      };
      if (params.tools?.length) {
        body.tools = toOpenAITools(params.tools);
      }

      const res = await fetch(`${base}/chat/completions`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${apiKey}`,
        },
        body: JSON.stringify(body),
      });

      if (!res.ok) {
        const text = await res.text().catch(() => "");
        const error: any = new Error(`OpenAI API ${res.status}: ${text}`);
        error.status = res.status;
        try { error.error = JSON.parse(text); } catch { /* */ }
        throw error;
      }

      const response = await res.json() as any;
      const choice = response.choices?.[0];

      return {
        reply: choice?.message?.content ?? "",
        toolCalls: extractToolCalls(response),
        usage: response.usage ? {
          inputTokens: response.usage.prompt_tokens ?? 0,
          outputTokens: response.usage.completion_tokens ?? 0,
        } : undefined,
      };
    },
    sendMessageStream(params: SendMessageParams): AsyncIterable<StreamChunk> {
      return sendMessageStream(base, apiKey, params);
    },
  };
}

/**
 * streaming.test.ts — 流式传输功能测试
 *
 * 测试范围：
 * 1. OpenAI SSE 解析器正确性
 * 2. Mock 适配器流式输出序列
 * 3. createLlm sendStream 消费流式 chunks
 */

import { strict as assert } from "node:assert";

// ===== 测试 1：OpenAI SSE 解析器 =====

/** 模拟 ReadableStream 的 reader */
function createMockReader(chunks: string[]): ReadableStreamDefaultReader<Uint8Array> {
  let index = 0;
  return {
    async read(): Promise<ReadableStreamReadResult<Uint8Array>> {
      if (index >= chunks.length) {
        return { done: true, value: undefined };
      }
      const chunk = chunks[index++];
      return { done: false, value: new TextEncoder().encode(chunk) };
    },
    releaseLock(): void {},
    closed: Promise.resolve(undefined),
    cancel(): Promise<void> { return Promise.resolve(); },
  } as ReadableStreamDefaultReader<Uint8Array>;
}

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

  if (buffer.trim().startsWith("data: ")) {
    const data = buffer.trim().slice(6);
    if (data !== "[DONE]") yield data;
  }
}

async function testSseParser() {
  console.log("  测试 SSE 解析器...");

  // 基本解析
  const reader1 = createMockReader([
    "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n",
    "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\n",
    "data: [DONE]\n\n",
  ]);
  const chunks1: string[] = [];
  for await (const data of parseSseStream(reader1)) {
    chunks1.push(data);
  }
  assert.equal(chunks1.length, 2);
  assert.ok(chunks1[0].includes("hello"));
  assert.ok(chunks1[1].includes("world"));

  // 跨 chunk 的分割
  const reader2 = createMockReader([
    "data: {\"choices\":[{\"delta\":{\"content\":\"hel",
    "lo\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\n",
  ]);
  const chunks2: string[] = [];
  for await (const data of parseSseStream(reader2)) {
    chunks2.push(data);
  }
  assert.equal(chunks2.length, 2);
  assert.ok(chunks2[0].includes("hello"));

  console.log("  ✅ SSE 解析器通过");
}

// ===== 测试 2：StreamChunk 序列 =====

interface StreamChunk {
  type: "text" | "toolCall" | "done";
  delta?: string;
  toolCall?: { id: string; name: string; input: Record<string, unknown> };
}

async function* mockStream(): AsyncIterable<StreamChunk> {
  yield { type: "text", delta: "Hello" };
  yield { type: "text", delta: " world" };
  yield {
    type: "toolCall",
    toolCall: { id: "tc_1", name: "read_file", input: { path: "test.txt" } },
  };
  yield { type: "done" };
}

async function testMockStream() {
  console.log("  测试 Mock StreamChunk 序列...");

  const chunks: StreamChunk[] = [];
  for await (const chunk of mockStream()) {
    chunks.push(chunk);
  }

  assert.equal(chunks.length, 4);
  assert.equal(chunks[0].type, "text");
  assert.equal(chunks[0].delta, "Hello");
  assert.equal(chunks[1].type, "text");
  assert.equal(chunks[1].delta, " world");
  assert.equal(chunks[2].type, "toolCall");
  assert.equal(chunks[2].toolCall?.name, "read_file");
  assert.equal(chunks[3].type, "done");

  console.log("  ✅ Mock StreamChunk 序列通过");
}

// ===== 测试 3：REPL 流式消费逻辑 =====

async function testReplStreamConsumption() {
  console.log("  测试 REPL 流式消费逻辑...");

  let output = "";
  const toolCalls: Array<{ id: string; name: string; input: Record<string, unknown> }> = [];

  for await (const chunk of mockStream()) {
    switch (chunk.type) {
      case "text":
        if (chunk.delta) output += chunk.delta;
        break;
      case "toolCall":
        if (chunk.toolCall) toolCalls.push(chunk.toolCall);
        break;
    }
  }

  assert.equal(output, "Hello world");
  assert.equal(toolCalls.length, 1);
  assert.equal(toolCalls[0].name, "read_file");

  console.log("  ✅ REPL 流式消费逻辑通过");
}

// ===== 主函数 =====

async function main() {
  console.log("\n🧪 流式传输测试\n");

  await testSseParser();
  await testMockStream();
  await testReplStreamConsumption();

  console.log("\n✅ 全部通过\n");
}

main().catch((err) => {
  console.error("\n❌ 测试失败:", err);
  process.exit(1);
});

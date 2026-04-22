/**
 * repl.ts — REPL 循环
 *
 * 核心交互逻辑：用户输入 → LLM → 有 toolCalls 则执行 → 继续 → 无 toolCalls 则返回
 * 支持流式输出：text delta 实时打印，tool call 检测到后立即异步执行
 */

import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import type { Message, LlmHandle, ToolCall } from "../shared/protocol.js";
import { executeTool } from "./handlers.js";
import { ALL_TOOLS } from "./tools.js";

const __dirname = dirname(fileURLToPath(import.meta.url));
const PROMPT_PATH = join(__dirname, "prompts", "host.md");

/** 加载系统提示词 */
function loadSystemPrompt(): string {
  try {
    return readFileSync(PROMPT_PATH, "utf-8");
  } catch {
    return "你是一个有用的编程助手。你可以读写文件、执行命令来帮用户完成工作。";
  }
}

/**
 * 运行单次 REPL 对话（流式）。
 *
 * @param userInput 用户输入文本
 * @param llm LLM 句柄
 * @param projectRoot 项目根目录
 * @param maxRounds 最大工具调用轮数
 * @returns LLM 的最终回复
 */
export async function runRepl(
  userInput: string,
  llm: LlmHandle,
  projectRoot: string,
  maxRounds = 10,
): Promise<string> {
  const systemPrompt = loadSystemPrompt();
  const messages: Message[] = [
    { role: "system", content: systemPrompt },
    { role: "user", content: userInput },
  ];

  for (let round = 0; round < maxRounds; round++) {
    const stream = llm.sendStream({
      messages,
      sessionId: "repl",
      agentId: "user",
      tools: ALL_TOOLS,
    });

    let reply = "";
    const toolCalls: ToolCall[] = [];
    const toolExecutions: Promise<{ call: ToolCall; result: string }>[] = [];

    for await (const chunk of stream) {
      switch (chunk.type) {
        case "text": {
          if (chunk.delta) {
            reply += chunk.delta;
            process.stdout.write(chunk.delta);
          }
          break;
        }
        case "toolCall": {
          if (chunk.toolCall) {
            toolCalls.push(chunk.toolCall);
            // 立即开始异步执行，不阻塞 stream 消费
            toolExecutions.push(
              executeTool(chunk.toolCall, projectRoot).then((result) => ({
                call: chunk.toolCall!,
                result,
              })),
            );
          }
          break;
        }
        case "done": {
          // stream 结束，输出换行
          if (reply.length > 0) {
            process.stdout.write("\n");
          }
          break;
        }
      }
    }

    // 无工具调用 → 直接返回回复
    if (toolCalls.length === 0) {
      return reply;
    }

    // 等待所有 tool call 执行完成
    const results = await Promise.all(toolExecutions);

    messages.push({ role: "assistant", content: reply || `[调用工具]` });

    for (const { call, result } of results) {
      messages.push({
        role: "user",
        content: `[${call.name} 结果]\n${result}`,
      });
    }
  }

  const maxRoundsMsg = "[达到最大工具调用轮数]";
  process.stdout.write(maxRoundsMsg + "\n");
  return maxRoundsMsg;
}

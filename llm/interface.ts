import { loadServiceConfig } from "./config.js";
import { ProviderRegistry } from "./providers/provider-registry.js";
import { tryProviderWithFallback, FallbackExhaustedError } from "./providers/fallback.js";
import type {
  LlmRequest, LlmResponse, LlmMeta, LlmOptions, LlmHandle, FallbackStep,
  StreamChunk,
} from "./protocol.js";

export type {
  LlmRequest, LlmResponse, LlmMeta, LlmOptions, LlmHandle, FallbackStep,
  Message, ToolDefinition, ToolCall, TokenUsage, StreamChunk,
} from "./protocol.js";
export { loadServiceConfig } from "./config.js";

export function createLlm(options?: LlmOptions): LlmHandle {
  const config = loadServiceConfig(options?.configPath);
  const registry = new ProviderRegistry(config);

  const sessionOverrides = new Map<string, ReturnType<typeof registry.getPrimary>>();

  return {
    async send(req: LlmRequest): Promise<LlmResponse> {
      const start = Date.now();

      const override = sessionOverrides.get(req.sessionId);
      const candidates = override ? [override] : registry.getCandidates();

      const result = await tryProviderWithFallback(
        candidates,
        (candidate) => registry.getAdapter(candidate.providerId).sendMessage({
          messages: req.messages,
          model: candidate.model,
          tools: req.tools,
          maxTokens: req.maxTokens,
        }),
      );

      const durationMs = Date.now() - start;
      const { usedCandidate, attempts } = result;

      const failedAttempts = attempts.filter((a) => a.reason !== null);
      const fallbackChain: FallbackStep[] | undefined =
        failedAttempts.length > 0
          ? attempts.map((a) => ({
              providerId: a.providerId,
              model: a.model,
              reason: a.reason,
              durationMs: a.durationMs,
            }))
          : undefined;

      if (failedAttempts.length > 0) {
        sessionOverrides.set(req.sessionId, usedCandidate);
      }

      return {
        reply: result.data.reply,
        toolCalls: result.data.toolCalls,
        usage: result.data.usage,
        meta: {
          sessionId: req.sessionId,
          agentId: req.agentId,
          providerId: usedCandidate.providerId,
          model: usedCandidate.model,
          durationMs,
          fallbackChain,
        },
      };
    },

    async *sendStream(req: LlmRequest): AsyncIterable<StreamChunk> {
      const start = Date.now();
      const override = sessionOverrides.get(req.sessionId);
      const candidates = override ? [override] : registry.getCandidates();

      let lockedCandidate: ReturnType<typeof registry.getPrimary> | null = null;

      for (const candidate of candidates) {
        try {
          const stream = registry.getAdapter(candidate.providerId).sendMessageStream({
            messages: req.messages,
            model: candidate.model,
            tools: req.tools,
            maxTokens: req.maxTokens,
          });

          // 消费流：连接阶段错误在这里捕获并 fallback
          let hasYielded = false;

          for await (const chunk of stream) {
            if (!hasYielded) {
              hasYielded = true;
              lockedCandidate = candidate;
            }
            yield chunk;
          }

          // 流正常结束
          return;
        } catch (err) {
          // 如果已经 yield 过 chunks，不能 fallback，直接抛出
          if (lockedCandidate) throw err;

          // 连接阶段失败，继续尝试下一个 candidate
          // 静默处理，由外层控制是否继续
        }
      }

      // 所有 candidate 都失败了
      throw new FallbackExhaustedError(
        candidates.map((c) => ({
          providerId: c.providerId,
          model: c.model,
          reason: "unknown",
          permanence: "transient",
          durationMs: Date.now() - start,
          retriesUsed: 0,
          timestamp: new Date().toISOString(),
        })),
      );
    },
  };
}

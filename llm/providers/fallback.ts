import type { ModelCandidate } from "./provider-registry.js";

export type FailoverReason =
  | "auth" | "rate_limit" | "overloaded" | "server_error"
  | "model_not_found" | "provider_error" | "format"
  | "connection_reset" | "timeout" | "empty_stream" | "unknown";

export type ErrorPermanence = "transient" | "permanent";

export interface ErrorClassification {
  reason: FailoverReason;
  permanence: ErrorPermanence;
  statusCode?: number;
  message?: string;
}

const JITTER_MIN_MS = 1000;
const JITTER_MAX_MS = 3000;
const DEFAULT_TIMEOUT_MS = 120_000;

export function classifyError(error: unknown, providerErrorCodes: string[] = []): ErrorClassification {
  const err = error as any;
  const statusCode: number | undefined = err?.status ?? err?.statusCode ?? err?.error?.status;
  const message: string = err?.message ?? String(error);
  const errorCode: string | undefined = err?.error?.error?.type ?? err?.error?.error?.code;

  if (err?.code === "ECONNRESET" || err?.code === "ETIMEDOUT" || err?.code === "ECONNREFUSED" || err?.code === "EPIPE") {
    return { reason: "connection_reset", permanence: "transient", message };
  }
  if (message.includes("timeout") || err?.code === "UND_ERR_CONNECT_TIMEOUT") {
    return { reason: "timeout", permanence: "transient", message };
  }
  if (statusCode) {
    if (statusCode === 401 || statusCode === 403) return { reason: "auth", permanence: "permanent", statusCode, message };
    if (statusCode === 429) return { reason: "rate_limit", permanence: "transient", statusCode, message };
    if (statusCode === 529) return { reason: "overloaded", permanence: "transient", statusCode, message };
    if (statusCode >= 500) return { reason: "server_error", permanence: "transient", statusCode, message };
    if (statusCode === 400) {
      if (errorCode && providerErrorCodes.length > 0 && providerErrorCodes.includes(errorCode))
        return { reason: "provider_error", permanence: "transient", statusCode, message };
      if (errorCode === "invalid_request_error" || !errorCode)
        return { reason: "format", permanence: "permanent", statusCode, message };
      return { reason: "provider_error", permanence: "transient", statusCode, message };
    }
    if (statusCode === 404) return { reason: "model_not_found", permanence: "permanent", statusCode, message };
  }
  return { reason: "unknown", permanence: "transient", message };
}

export interface FallbackAttempt {
  providerId: string;
  model: string;
  reason: FailoverReason | null;
  permanence?: ErrorPermanence;
  durationMs: number;
  retriesUsed: number;
  timestamp: string;
}

export interface FallbackResult<T> {
  data: T;
  attempts: FallbackAttempt[];
  usedCandidate: ModelCandidate;
}

export class FallbackExhaustedError extends Error {
  attempts: FallbackAttempt[];
  constructor(attempts: FallbackAttempt[]) {
    const summary = attempts.map((a) => `  ${a.providerId}/${a.model}: ${a.reason}`).join("\n");
    super(`所有 provider 均失败:\n${summary}`);
    this.name = "FallbackExhaustedError";
    this.attempts = attempts;
  }
}

function sleepWithJitter(minMs: number, maxMs: number): Promise<void> {
  const delay = minMs + Math.random() * (maxMs - minMs);
  return new Promise((resolve) => setTimeout(resolve, delay));
}

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("timeout")), ms);
    promise.then(
      (val) => { clearTimeout(timer); resolve(val); },
      (err) => { clearTimeout(timer); reject(err); },
    );
  });
}

export async function tryProviderWithFallback<T>(
  candidates: ModelCandidate[],
  action: (candidate: ModelCandidate) => Promise<T>,
  onAttempt?: (attempt: FallbackAttempt) => void,
): Promise<FallbackResult<T>> {
  const attempts: FallbackAttempt[] = [];

  for (const candidate of candidates) {
    const start = Date.now();
    try {
      const result = await withTimeout(action(candidate), DEFAULT_TIMEOUT_MS);
      const attempt: FallbackAttempt = {
        providerId: candidate.providerId, model: candidate.model, reason: null,
        durationMs: Date.now() - start, retriesUsed: 0, timestamp: new Date().toISOString(),
      };
      attempts.push(attempt);
      onAttempt?.(attempt);
      return { data: result, attempts, usedCandidate: candidate };
    } catch (error) {
      const classification = classifyError(error);
      const durationMs = Date.now() - start;

      if (classification.reason === "rate_limit") {
        try {
          await sleepWithJitter(JITTER_MIN_MS, JITTER_MAX_MS);
          const result = await withTimeout(action(candidate), DEFAULT_TIMEOUT_MS);
          const attempt: FallbackAttempt = {
            providerId: candidate.providerId, model: candidate.model, reason: null,
            durationMs: Date.now() - start, retriesUsed: 1, timestamp: new Date().toISOString(),
          };
          attempts.push(attempt);
          onAttempt?.(attempt);
          return { data: result, attempts, usedCandidate: candidate };
        } catch { /* 重试也失败，继续 fallback */ }
      }

      const attempt: FallbackAttempt = {
        providerId: candidate.providerId, model: candidate.model, reason: classification.reason,
        permanence: classification.permanence, durationMs, retriesUsed: 0, timestamp: new Date().toISOString(),
      };
      attempts.push(attempt);
      onAttempt?.(attempt);

      if (classification.permanence === "permanent") break;
    }
  }

  throw new FallbackExhaustedError(attempts);
}

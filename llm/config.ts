import { readFileSync, existsSync } from "fs";
import { join } from "path";

export interface ProviderConfig {
  baseUrl: string;
  apiKey: string;
  protocol: "anthropic" | "openai";
}

export type ModelRef = string;

export interface ModelChainConfig {
  primary: ModelRef;
  fallbacks: ModelRef[];
}

export interface ServiceLlmConfig {
  providers: Record<string, ProviderConfig>;
  model: ModelChainConfig;
}

export interface NebflowServiceConfig {
  llm: ServiceLlmConfig;
}

interface NebflowFileConfig {
  llm?: {
    providers?: Record<string, ProviderConfig>;
    model?: ModelChainConfig | string;
    baseUrl?: string;
    apiKey?: string;
  };
}

export function resolveEnvVars(str: string): string {
  if (typeof str !== "string") return str;
  return str.replace(/\$\{([^}]+)\}/g, (_, name) => process.env[name] || "");
}

export function parseModelRef(ref: ModelRef): { providerId: string; modelId: string } {
  const idx = ref.indexOf("/");
  if (idx === -1) {
    throw new Error(`无效的模型引用 "${ref}"，格式应为 "providerId/modelId"`);
  }
  return { providerId: ref.slice(0, idx), modelId: ref.slice(idx + 1) };
}

export function loadServiceConfig(configPath?: string): NebflowServiceConfig {
  const filePath = configPath || join(process.cwd(), "nebflow.json");
  if (!existsSync(filePath)) {
    throw new Error(`配置文件不存在: ${filePath}`);
  }

  let raw: NebflowFileConfig;
  try {
    raw = JSON.parse(readFileSync(filePath, "utf-8")) as NebflowFileConfig;
  } catch {
    throw new Error(`配置文件不是有效的 JSON: ${filePath}`);
  }

  const llm = raw.llm || {};

  if (llm.providers && llm.model && typeof llm.model === "object") {
    const providers: Record<string, ProviderConfig> = {};
    for (const [id, provider] of Object.entries(llm.providers)) {
      providers[id] = {
        baseUrl: provider.baseUrl,
        apiKey: resolveEnvVars(provider.apiKey),
        protocol: provider.protocol || "anthropic",
      };
    }
    return { llm: { providers, model: llm.model } };
  }

  const apiKey = process.env.NEBFLOW_LLM_API_KEY?.trim() || resolveEnvVars(llm.apiKey || "");
  const baseUrl = process.env.NEBFLOW_LLM_BASE_URL?.trim() || llm.baseUrl?.trim();
  const modelStr = typeof llm.model === "string" ? llm.model : undefined;
  const model = process.env.NEBFLOW_LLM_MODEL?.trim() || modelStr?.trim();

  if (!apiKey || !baseUrl || !model) {
    throw new Error("请配置 nebflow.json 的 llm.providers + llm.model");
  }

  return {
    llm: {
      providers: { default: { baseUrl, apiKey, protocol: "anthropic" } },
      model: { primary: `default/${model}`, fallbacks: [] },
    },
  };
}

import type { ProviderConfig, NebflowServiceConfig, ModelRef } from "../config.js";
import { parseModelRef } from "../config.js";
import type { ProviderAdapter } from "./adapter.js";
import { createAnthropicAdapter } from "./anthropic.js";
import { createOpenaiAdapter } from "./openai.js";

export interface ModelCandidate {
  providerId: string;
  provider: ProviderConfig;
  model: string;
}

function createAdapter(provider: ProviderConfig): ProviderAdapter {
  switch (provider.protocol) {
    case "openai":
      return createOpenaiAdapter(provider.baseUrl, provider.apiKey);
    case "anthropic":
    default:
      return createAnthropicAdapter(provider.baseUrl, provider.apiKey);
  }
}

export class ProviderRegistry {
  private config: NebflowServiceConfig;
  private adapters: Map<string, ProviderAdapter> = new Map();

  constructor(config: NebflowServiceConfig) {
    this.config = config;
  }

  getAdapter(providerId: string): ProviderAdapter {
    let adapter = this.adapters.get(providerId);
    if (adapter) return adapter;

    const provider = this.config.llm.providers[providerId];
    if (!provider) throw new Error(`未知的 provider: ${providerId}`);

    adapter = createAdapter(provider);
    this.adapters.set(providerId, adapter);
    return adapter;
  }

  getProvider(providerId: string): ProviderConfig {
    const provider = this.config.llm.providers[providerId];
    if (!provider) throw new Error(`未知的 provider: ${providerId}`);
    return provider;
  }

  getProviderIds(): string[] {
    return Object.keys(this.config.llm.providers);
  }

  getCandidates(): ModelCandidate[] {
    const chain: ModelRef[] = [this.config.llm.model.primary, ...this.config.llm.model.fallbacks];
    return chain.map((ref) => {
      const { providerId, modelId } = parseModelRef(ref);
      const provider = this.config.llm.providers[providerId];
      if (!provider) throw new Error(`模型引用 "${ref}" 指向不存在的 provider "${providerId}"`);
      return { providerId, provider, model: modelId };
    });
  }

  getPrimary(): ModelCandidate {
    return this.getCandidates()[0];
  }
}

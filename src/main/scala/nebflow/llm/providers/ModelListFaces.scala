package nebflow.llm.providers

import nebflow.shared.LlmProtocol

/**
 * The model-list endpoint of each protocol face, as declared by the adapter
 * that owns that face — never derived by the gateway from a raw `baseUrl`.
 *
 * Why this indirection exists: `baseUrl` is a *chat* prefix, and the two faces
 * disagree about where the version segment lives (the OpenAI adapter posts to
 * `{base}/chat/completions`, the Anthropic adapter appends `/v1/messages`
 * itself), so a single `URI.create(baseUrl + "models")` cannot be right for
 * both. Measured on the 2026-09-13 settings dialog path, the old shape gave
 * kimi `HTTP 404` and zhipu an `HTTP 200` body carrying `404 NOT_FOUND`, while
 * the Anthropic face's own `{base}/v1/models` answers both with their real
 * catalogues.
 *
 * Each declaration is an ORDERED candidate list: vendor gateways differ about
 * where the version root is, so a face may name more than one endpoint. The
 * caller probes them in order and advances only on a "no such endpoint"
 * verdict, so a later candidate can never mask a real error. An empty list is
 * the face's explicit "no model list" state: callers must refuse with
 * `noEndpointMessage` instead of reporting an empty list as a success.
 */
object ModelListFaces:

  /** Endpoints declared by `protocol` for `baseUrl`, in probe order. */
  def models(protocol: LlmProtocol, baseUrl: String): List[String] =
    protocol match
      case LlmProtocol.Anthropic => AnthropicAdapter.modelListUrls(baseUrl)
      case LlmProtocol.OpenAI => OpenAiAdapter.modelListUrls(baseUrl)

  /** Readable refusal for a face that declares no model list at all. */
  def noEndpointMessage(protocol: LlmProtocol): String =
    s"This provider does not expose a model list for the '${protocol.name}' face: enter model ids manually"
end ModelListFaces

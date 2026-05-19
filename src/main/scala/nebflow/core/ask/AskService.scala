package nebflow.core.ask

import nebflow.shared.*

/**
 * Inline Ask service — builds the ask-reminder message for injection into
 * the agent's pipeLlmCall path.  No independent LLM loop needed.
 *
 * The /ask turn reuses the agent's cached system prompt + tool definitions,
 * avoiding a full-price separate LLM call.
 */
object AskService:

  /**
   * Build the ask-reminder message that instructs the model to treat this
   * as a single, ephemeral Q&A turn.
   */
  def buildAskReminder(question: String): Message =
    Message(
      MessageRole.User,
      Left(
        s"""<system-reminder>
         |This is a single, ephemeral follow-up question. Rules:
         |- Answer the question directly and concisely.
         |- Use tools when needed to find accurate information.
         |- Your response will NOT be saved to the conversation history.
         |- This is a single exchange: answer the question, then stop.
         |- Do NOT continue the previous task. Focus ONLY on the question below.
         |</system-reminder>
         |
         |$question""".stripMargin
      )
    )

end AskService

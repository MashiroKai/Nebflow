//! AskUserQuestion tool — parses and validates a structured question request.
//!
//! The Scala original forwards the questions to the frontend and suspends the
//! agent until the user answers. The Rust backend has no frontend channel yet,
//! so this tool parses and validates the questions structure and returns a
//! normalized representation.

use async_trait::async_trait;
use serde_json::{Map, Value};

use crate::types::{ToolContext, ToolError};
use crate::Tool;

pub struct AskUserQuestionTool {
    schema: Map<String, Value>,
}

impl AskUserQuestionTool {
    pub fn new() -> Self {
        let schema = serde_json::json!({
            "type": "object",
            "properties": {
                "questions": {
                    "type": "array",
                    "description": "List of questions to ask the user",
                    "items": {
                        "type": "object",
                        "properties": {
                            "question": {"type": "string"},
                            "options": {
                                "type": "array",
                                "items": {"type": "string"}
                            },
                            "multiSelect": {"type": "boolean"}
                        },
                        "required": ["question"]
                    }
                }
            },
            "required": ["questions"]
        });
        Self {
            schema: schema.as_object().unwrap().clone(),
        }
    }
}

impl Default for AskUserQuestionTool {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Tool for AskUserQuestionTool {
    fn name(&self) -> &str {
        "AskUserQuestion"
    }

    fn description(&self) -> &str {
        "Asks the user one or more structured questions. Parses and validates \
         the questions payload; the runtime owning the frontend channel \
         delivers it and supplies the user's answers."
    }

    fn input_schema(&self) -> &Map<String, Value> {
        &self.schema
    }

    async fn call(
        &self,
        input: &Map<String, Value>,
        _ctx: &ToolContext,
    ) -> Result<String, ToolError> {
        let questions = input
            .get("questions")
            .and_then(|v| v.as_array())
            .ok_or_else(|| ToolError::InvalidInput("questions is required (array)".into()))?;

        if questions.is_empty() {
            return Err(ToolError::InvalidInput(
                "questions must contain at least one question".into(),
            ));
        }

        let mut normalized = Vec::with_capacity(questions.len());
        for (i, q) in questions.iter().enumerate() {
            let text = q
                .get("question")
                .and_then(|v| v.as_str())
                .map(|s| s.trim())
                .filter(|s| !s.is_empty())
                .ok_or_else(|| {
                    ToolError::InvalidInput(format!(
                        "questions[{}].question is required (non-empty string)",
                        i
                    ))
                })?;

            let option_count = q
                .get("options")
                .and_then(|v| v.as_array())
                .map(|a| a.len())
                .unwrap_or(0);

            // If options are provided they must all be non-empty strings
            if let Some(options) = q.get("options").and_then(|v| v.as_array()) {
                for (j, opt) in options.iter().enumerate() {
                    match opt.as_str() {
                        Some(s) if !s.trim().is_empty() => {}
                        _ => {
                            return Err(ToolError::InvalidInput(format!(
                                "questions[{}].options[{}] must be a non-empty string",
                                i, j
                            )));
                        }
                    }
                }
            }

            let multi = q
                .get("multiSelect")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);

            normalized.push(format!(
                "  {}. \"{}\" ({} option(s){})",
                i + 1,
                text,
                option_count,
                if multi { ", multi-select" } else { "" }
            ));
        }

        Ok(format!(
            "Validated {} question(s):\n{}\n\
             Note: the Rust backend has no frontend channel yet; \
             the runtime must deliver these questions and supply answers.",
            questions.len(),
            normalized.join("\n")
        ))
    }

    fn summarize(&self, input: &Map<String, Value>) -> String {
        let count = input
            .get("questions")
            .and_then(|v| v.as_array())
            .map(|a| a.len())
            .unwrap_or(0);
        format!("AskUserQuestion({} question(s))", count)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx() -> ToolContext {
        ToolContext {
            session_id: "s1".into(),
            agent_id: "a1".into(),
            working_directory: "/tmp".into(),
            depth: 0,
            ..Default::default()
        }
    }

    fn input_with_questions(questions: Value) -> Map<String, Value> {
        let mut m = Map::new();
        m.insert("questions".into(), questions);
        m
    }

    #[tokio::test]
    async fn valid_questions_accepted() {
        let tool = AskUserQuestionTool::new();
        let input = input_with_questions(serde_json::json!([
            {"question": "Pick a color", "options": ["red", "blue"]},
            {"question": "Any comments?"}
        ]));
        let result = tool.call(&input, &ctx()).await.unwrap();
        assert!(result.contains("2 question(s)"));
        assert!(result.contains("Pick a color"));
    }

    #[tokio::test]
    async fn empty_questions_rejected() {
        let tool = AskUserQuestionTool::new();
        let input = input_with_questions(serde_json::json!([]));
        let result = tool.call(&input, &ctx()).await;
        assert!(matches!(result, Err(ToolError::InvalidInput(_))));
    }

    #[tokio::test]
    async fn empty_question_text_rejected() {
        let tool = AskUserQuestionTool::new();
        let input = input_with_questions(serde_json::json!([{"question": "  "}]));
        let result = tool.call(&input, &ctx()).await;
        assert!(matches!(result, Err(ToolError::InvalidInput(_))));
    }

    #[tokio::test]
    async fn empty_option_rejected() {
        let tool = AskUserQuestionTool::new();
        let input = input_with_questions(serde_json::json!([
            {"question": "q", "options": ["ok", ""]}
        ]));
        let result = tool.call(&input, &ctx()).await;
        assert!(matches!(result, Err(ToolError::InvalidInput(_))));
    }
}

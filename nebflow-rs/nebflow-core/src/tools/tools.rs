//! Built-in tool implementations and registration.
//! Sub-modules: read, write, edit, glob, grep, bash, web_search, web_fetch, curl,
//! pop, card, ask_user, delegate, mail, flow_report, load, transfer,
//! task_create, task_update, schedule, save_workspace, remove_unnecessary.

pub mod ask_user;
pub mod bash;
pub mod card;
pub mod curl;
pub mod delegate;
pub mod edit;
pub mod flow_report;
pub mod glob;
pub mod grep;
pub mod load;
pub mod loader;
pub mod mail;
pub mod pop;
pub mod read;
pub mod remove_unnecessary;
pub mod save_workspace;
pub mod schedule;
pub mod task_create;
pub mod task_update;
pub mod transfer;
pub mod web_fetch;
pub mod web_search;
pub mod write;

use crate::Tool;
use std::sync::Arc;

/// Return all built-in tools as Arc<dyn Tool>.
pub fn all_builtin_tools() -> Vec<Arc<dyn Tool>> {
    vec![
        Arc::new(read::ReadTool::new()),
        Arc::new(write::WriteTool::new()),
        Arc::new(edit::EditTool::new()),
        Arc::new(glob::GlobTool::new()),
        Arc::new(grep::GrepTool::new()),
        Arc::new(bash::BashTool::new()),
        Arc::new(web_search::WebSearchTool::new()),
        Arc::new(web_fetch::WebFetchTool::new()),
        Arc::new(curl::CurlTool::new()),
        Arc::new(pop::PopTool::new()),
        Arc::new(card::CardTool::new()),
        Arc::new(ask_user::AskUserQuestionTool::new()),
        Arc::new(delegate::DelegateTool::new()),
        Arc::new(mail::MailTool::new()),
        Arc::new(flow_report::FlowReportTool::new()),
        Arc::new(load::LoadTool::new()),
        Arc::new(transfer::TransferFileTool::new()),
        Arc::new(task_create::TaskCreateTool::new()),
        Arc::new(task_update::TaskUpdateTool::new()),
        Arc::new(schedule::ScheduleTool::new()),
        Arc::new(save_workspace::SaveWorkspaceItemTool::new()),
        Arc::new(remove_unnecessary::RemoveUnnecessaryTool::new()),
    ]
}

/// Helper: extract a string field from a JSON object.
pub fn get_str<'a>(
    input: &'a serde_json::Map<String, serde_json::Value>,
    key: &str,
) -> Option<&'a str> {
    input.get(key).and_then(|v| v.as_str())
}

/// Helper: extract a boolean field from a JSON object.
pub fn get_bool(input: &serde_json::Map<String, serde_json::Value>, key: &str) -> Option<bool> {
    input.get(key).and_then(|v| v.as_bool())
}

/// Helper: extract an integer field from a JSON object.
pub fn get_int(input: &serde_json::Map<String, serde_json::Value>, key: &str) -> Option<i64> {
    input.get(key).and_then(|v| v.as_i64())
}

/// Helper: extract a usize field from a JSON object.
pub fn get_usize(input: &serde_json::Map<String, serde_json::Value>, key: &str) -> Option<usize> {
    input.get(key).and_then(|v| v.as_u64()).map(|n| n as usize)
}

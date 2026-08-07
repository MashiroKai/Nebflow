//! WebSocket handler — mirrors Scala WebSocketRoutes.scala message handling.
//!
//! Processes incoming WS messages from clients and dispatches to the
//! appropriate session/agent operations.

use axum::extract::ws::Message;
use serde_json::{json, Value};
use std::sync::Arc;
use tokio::sync::mpsc;
use tracing::warn;

use nebflow_agent::AgentEvent;

use crate::agent_manager::AgentManager;
use crate::auth::Auth;
use crate::ratelimit::RateLimiter;
use crate::session::{SessionStore, UiMessage};
use crate::ws_hub::WsHub;

/// Maximum WS message size (10 MB — base64 images can be large).
pub const MAX_MESSAGE_SIZE: usize = 10 * 1024 * 1024;

/// Shared application state available to WS handlers.
#[derive(Clone)]
pub struct WsState {
    pub auth: Arc<Auth>,
    pub rate_limiter: Arc<RateLimiter>,
    pub session_store: Arc<SessionStore>,
    pub ws_hub: Arc<WsHub>,
    /// Live agent registry. Optional so unit tests and minimal deployments
    /// can run without an LLM backend.
    pub agent_manager: Option<Arc<AgentManager>>,
}

/// Result of handling an incoming WS text message.
#[derive(Debug)]
pub enum HandleResult {
    /// Message was handled successfully.
    Ok,
    /// Message was too large.
    TooLarge,
    /// Message was invalid JSON.
    InvalidJson,
}

/// Handle a single incoming WS text message.
///
/// Parses the JSON, dispatches by `type` field, and sends responses
/// via the provided sender.
pub async fn handle_message(
    text: &str,
    tx: &mpsc::UnboundedSender<String>,
    state: &WsState,
) -> HandleResult {
    if text.len() > MAX_MESSAGE_SIZE {
        warn!("Dropping oversized WS message ({} bytes)", text.len());
        return HandleResult::TooLarge;
    }

    let parsed: Value = match serde_json::from_str(text) {
        Ok(v) => v,
        Err(_) => return HandleResult::InvalidJson,
    };

    let msg_type = parsed.get("type").and_then(|v| v.as_str()).unwrap_or("");

    match msg_type {
        "ping" => {
            let _ = tx.send(serde_json::json!({ "type": "pong" }).to_string());
        }

        "command" => {
            let command = parsed.get("command").and_then(|v| v.as_str()).unwrap_or("");
            let session_id = parsed
                .get("sessionId")
                .and_then(|v| v.as_str())
                .unwrap_or("");

            if command == "clear" && !session_id.is_empty() {
                let _ = state
                    .session_store
                    .save_session_messages(session_id, &[])
                    .await;
                let _ = state
                    .session_store
                    .append_ui_messages(
                        session_id,
                        vec![UiMessage::System {
                            text: "Context cleared. LLM memory reset.".into(),
                            class_hint: Some("slash.clearDone".into()),
                            extra: None,
                            timestamp: now_ms(),
                        }],
                    )
                    .await;
                let _ = tx.send(
                    serde_json::json!({
                        "type": "taskListUpdate",
                        "tasks": [],
                        "sessionId": session_id
                    })
                    .to_string(),
                );
            }
        }

        "switchSession" => {
            let session_id = parsed
                .get("sessionId")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            if !session_id.is_empty() {
                match state.session_store.switch_session(session_id).await {
                    Ok(_) => {
                        send_session_list(tx, &state.session_store).await;
                    }
                    Err(e) => {
                        let _ = tx.send(
                            serde_json::json!({
                                "type": "error",
                                "message": e
                            })
                            .to_string(),
                        );
                    }
                }
            }
        }

        "createSession" => {
            let name = parsed
                .get("name")
                .and_then(|v| v.as_str())
                .unwrap_or("New Session");
            let agent_name = parsed
                .get("agentName")
                .and_then(|v| v.as_str())
                .map(|s| s.to_string());
            let folder_id = parsed
                .get("folderId")
                .and_then(|v| v.as_str())
                .map(|s| s.to_string());

            match state
                .session_store
                .create_session(name, agent_name, folder_id)
                .await
            {
                Ok(_) => {
                    send_session_list(tx, &state.session_store).await;
                }
                Err(e) => {
                    let _ = tx.send(
                        serde_json::json!({
                            "type": "error",
                            "message": e.to_string()
                        })
                        .to_string(),
                    );
                }
            }
        }

        "deleteSession" => {
            let session_id = parsed
                .get("sessionId")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            if !session_id.is_empty() {
                let _ = state.session_store.delete_session(session_id).await;
                send_session_list(tx, &state.session_store).await;
            }
        }

        "renameSession" => {
            let session_id = parsed
                .get("sessionId")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            let new_name = parsed.get("name").and_then(|v| v.as_str()).unwrap_or("");
            if !session_id.is_empty() && !new_name.is_empty() {
                let _ = state
                    .session_store
                    .rename_session(session_id, new_name)
                    .await;
                send_session_list(tx, &state.session_store).await;
            }
        }

        "setSafetyMode" => {
            let session_id = parsed
                .get("sessionId")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            let mode = parsed
                .get("safetyMode")
                .and_then(|v| v.as_str())
                .unwrap_or("confirm-edits");
            if !session_id.is_empty() {
                let _ = state.session_store.set_safety_mode(session_id, mode).await;
            }
        }

        "interrupt" => {
            let session_id = parsed
                .get("sessionId")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            if !session_id.is_empty() {
                if let Some(mgr) = &state.agent_manager {
                    let handle = mgr.get_or_create(session_id).await;
                    let send_result = {
                        let h = handle.lock().await;
                        h.send_message(nebflow_agent::AgentMessage::Interrupt).await
                    };
                    if send_result.is_err() {
                        mgr.remove(session_id).await;
                        send_session_busy(tx, session_id, false);
                    }
                }
            }
        }

        "userInput" => {
            handle_user_input(&parsed, tx, state).await;
        }

        "getHistory" => {
            let session_id = parsed
                .get("sessionId")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            let limit = parsed.get("limit").and_then(|v| v.as_u64()).unwrap_or(50) as usize;
            let before_index = parsed
                .get("beforeIndex")
                .and_then(|v| v.as_u64())
                .map(|n| n as usize);

            if !session_id.is_empty() {
                let (msgs, total, offset, has_more) = state
                    .session_store
                    .get_history_page(session_id, limit, before_index)
                    .await;
                let _ = tx.send(
                    serde_json::json!({
                        "type": "historyPage",
                        "sessionId": session_id,
                        "messages": msgs,
                        "total": total,
                        "offset": offset,
                        "hasMore": has_more
                    })
                    .to_string(),
                );
            }
        }

        "recallMessage" => {
            let session_id = parsed
                .get("sessionId")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            if !session_id.is_empty() {
                let mut msgs = state.session_store.load_ui_messages(session_id).await;
                // Find last User message, remove it if no AI message after
                let last_user_idx = msgs
                    .iter()
                    .rposition(|m| matches!(m, UiMessage::User { .. }));
                let deleted = if let Some(idx) = last_user_idx {
                    let has_ai_after = msgs[idx + 1..]
                        .iter()
                        .any(|m| matches!(m, UiMessage::Ai { .. }));
                    if !has_ai_after {
                        msgs.remove(idx);
                        let _ = state
                            .session_store
                            .save_ui_messages(session_id, &msgs)
                            .await;
                        true
                    } else {
                        false
                    }
                } else {
                    false
                };
                let _ = tx.send(
                    serde_json::json!({
                        "type": "messageRecalled",
                        "sessionId": session_id,
                        "success": deleted
                    })
                    .to_string(),
                );
            }
        }

        "getSkills" => {
            let data_root = nebflow_core::config::data_root();
            let cwd = std::env::current_dir().unwrap_or_default();
            let skills = crate::routes::skills::scan_all_skills(&data_root, &cwd);
            let _ = tx.send(
                json!({
                    "type": "skillList",
                    "skills": skills
                })
                .to_string(),
            );
        }

        "getTeams" => {
            let teams_dir = nebflow_core::config::data_root().join("teams");
            let mut teams: Vec<Value> = vec![];
            if let Ok(entries) = std::fs::read_dir(&teams_dir) {
                for entry in entries.flatten() {
                    let team_json = entry.path().join("team.json");
                    if let Ok(content) = std::fs::read_to_string(&team_json) {
                        if let Ok(t) = serde_json::from_str::<Value>(&content) {
                            teams.push(t);
                        }
                    }
                }
            }
            let _ = tx.send(
                json!({
                    "type": "teamList",
                    "teams": teams
                })
                .to_string(),
            );
        }

        "getConfig" => {
            let config = nebflow_core::config::load_service_config();
            let _ = tx.send(
                json!({
                    "type": "configData",
                    "config": config
                })
                .to_string(),
            );
        }

        "getModelOptions" => {
            let config = nebflow_core::config::load_service_config();
            let models = &config.llm.model;
            let _ = tx.send(
                json!({
                    "type": "modelOptions",
                    "models": {
                        "default": models.default,
                        "fallbacks": models.fallbacks
                    }
                })
                .to_string(),
            );
        }

        "memoryStatus" => {
            let _ = tx.send(
                json!({
                    "type": "memoryStatus",
                    "enabled": false
                })
                .to_string(),
            );
        }

        "getLlmLog" => {
            let _ = tx.send(
                json!({
                    "type": "llmLogState",
                    "enabled": false
                })
                .to_string(),
            );
        }

        "setThinking" => {
            // Silently accept — store state in SharedResources.thinking_config (P2)
        }

        "setVoiceMuted" => {
            // Silently accept
        }

        _ => {
            // Unknown message type — silently ignore for forward compatibility
        }
    }

    HandleResult::Ok
}

/// Send a `sessionBusy` event (Scala AgentSession.scala:30-42).
fn send_session_busy(tx: &mpsc::UnboundedSender<String>, session_id: &str, busy: bool) {
    let _ = tx
        .send(json!({ "type": "sessionBusy", "sessionId": session_id, "busy": busy }).to_string());
}

/// Convert an `AgentEvent` to the exact JSON shape the web UI expects
/// (Scala protocol.scala `toJson`, root-agent branch `isSubagent = false`).
pub fn agent_event_to_ws_json(event: &AgentEvent, session_id: &str) -> Value {
    match event {
        AgentEvent::TextDelta { text } => {
            json!({ "type": "textDelta", "sessionId": session_id, "delta": text })
        }
        AgentEvent::ToolStart { label } => {
            json!({ "type": "toolStart", "sessionId": session_id, "label": label })
        }
        AgentEvent::ToolEnd {
            label,
            summary,
            content,
            is_error,
            input,
        } => {
            let mut o = json!({
                "type": "toolEnd",
                "sessionId": session_id,
                "label": label,
                "summary": summary,
                "content": content,
                "isError": is_error,
            });
            if let Some(input) = input {
                o["input"] = input.clone();
            }
            o
        }
        // Sub-agent lifecycle events — pass through with the session id
        // (the frontend keys these off agentId for flow/sub-agent panes).
        AgentEvent::AgentStart {
            agent_name,
            agent_type,
            task_description,
        } => {
            let mut o = json!({
                "type": "agentStart",
                "agentId": agent_name,
                "name": agent_name,
                "agentType": agent_type,
            });
            if let Some(desc) = task_description {
                o["taskDescription"] = Value::String(desc.clone());
            }
            o
        }
        AgentEvent::AgentEnd { agent_name } => {
            json!({ "type": "agentEnd", "agentId": agent_name, "name": agent_name })
        }
        AgentEvent::Thinking => json!({ "type": "thinking", "sessionId": session_id }),
        AgentEvent::ToolCallDetected { name } => {
            json!({ "type": "toolCallDetected", "sessionId": session_id, "name": name })
        }
        AgentEvent::RetryStatus { message } => {
            json!({ "type": "retryStatus", "sessionId": session_id, "message": message })
        }
        AgentEvent::Done {
            model,
            context_window,
            input_tokens,
            compact_threshold,
        } => {
            let mut o = json!({ "type": "done", "sessionId": session_id });
            if let Some(m) = model {
                o["model"] = Value::String(m.clone());
            }
            if let Some(cw) = context_window {
                o["contextWindow"] = json!(cw);
            }
            if let Some(it) = input_tokens {
                o["inputTokens"] = json!(it);
            }
            if let Some(ct) = compact_threshold {
                o["compactThreshold"] = json!(ct);
            }
            o
        }
        AgentEvent::UsageUpdate {
            input_tokens,
            context_window,
            compact_threshold,
        } => json!({
            "type": "usageUpdate",
            "sessionId": session_id,
            "inputTokens": input_tokens,
            "contextWindow": context_window,
            "compactThreshold": compact_threshold,
        }),
        AgentEvent::CompactStart {
            mode,
            input_tokens,
            threshold,
        } => json!({
            "type": "compactStart",
            "sessionId": session_id,
            "mode": mode,
            "inputTokens": input_tokens,
            "threshold": threshold,
        }),
        AgentEvent::CompactComplete {
            before,
            after,
            report_path,
        } => {
            let mut o = json!({
                "type": "compactComplete",
                "sessionId": session_id,
                "before": before,
                "after": after,
            });
            if let Some(p) = report_path {
                o["reportPath"] = Value::String(p.clone());
            }
            o
        }
        AgentEvent::CompactFailed {
            reason,
            attempt,
            max_attempts,
        } => json!({
            "type": "compactFailed",
            "sessionId": session_id,
            "reason": reason,
            "attempt": attempt,
            "maxAttempts": max_attempts,
        }),
        AgentEvent::BackgroundTaskUpdate {
            task_id,
            description,
            status,
        } => json!({
            "type": "backgroundTaskUpdate",
            "taskId": task_id,
            "description": description,
            "status": status,
            "sessionId": session_id,
        }),
        AgentEvent::ExternalEventReceived {
            source,
            event_type,
            correlation_id,
        } => {
            let mut o = json!({
                "type": "externalEventReceived",
                "source": source,
                "eventType": event_type,
                "sessionId": session_id,
            });
            if let Some(c) = correlation_id {
                o["correlationId"] = Value::String(c.clone());
            }
            o
        }
        AgentEvent::Interrupted => json!({ "type": "interrupted", "sessionId": session_id }),
    }
}

/// Handle a `userInput` WS message: forward the prompt to the session's agent
/// and spawn a bridge task that forwards `AgentEvent`s back over this WS
/// connection (Scala WebSocketRoutes.scala fallback branch).
async fn handle_user_input(parsed: &Value, tx: &mpsc::UnboundedSender<String>, state: &WsState) {
    let content = parsed
        .get("content")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string();
    let session_id = parsed
        .get("sessionId")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string();
    if content.is_empty() || session_id.is_empty() {
        return;
    }
    let client_message_id = parsed
        .get("clientMessageId")
        .and_then(Value::as_str)
        .map(str::to_string);
    let chat_width = parsed.get("chatWidth").and_then(Value::as_u64).unwrap_or(0) as u32;

    // Persist the user message in the UI transcript (session history).
    let _ = state
        .session_store
        .append_ui_messages(
            &session_id,
            vec![UiMessage::User {
                text: content.clone(),
                attachments: vec![],
                timestamp: now_ms(),
            }],
        )
        .await;

    let Some(mgr) = state.agent_manager.as_ref() else {
        let _ =
            tx.send(json!({ "type": "error", "message": "Agent runtime unavailable" }).to_string());
        return;
    };

    send_session_busy(tx, &session_id, true);

    let handle = mgr.get_or_create(&session_id).await;

    let send_result = {
        let h = handle.lock().await;
        h.send_message(nebflow_agent::AgentMessage::UserInput {
            text: content,
            client_message_id,
            blocks: None,
            chat_width,
        })
        .await
    };
    if send_result.is_err() {
        tracing::error!(session_id = %session_id, "agent channel closed");
        mgr.remove(&session_id).await;
        send_session_busy(tx, &session_id, false);
        let _ = tx.send(json!({ "type": "error", "message": "Agent is not running" }).to_string());
        return;
    }

    // Bridge task: forward agent events to this WS connection until the turn
    // finishes (Done / Interrupted) or the agent channel closes.
    let tx2 = tx.clone();
    let sid = session_id.clone();
    tokio::spawn(async move {
        loop {
            let event = {
                let mut guard = handle.lock().await;
                guard.recv().await
            };
            match event {
                Some(ev) => {
                    let terminal = matches!(ev, AgentEvent::Done { .. } | AgentEvent::Interrupted);
                    let _ = tx2.send(agent_event_to_ws_json(&ev, &sid).to_string());
                    if terminal {
                        break;
                    }
                }
                None => break,
            }
        }
        send_session_busy(&tx2, &sid, false);
    });
}

/// Send the session list as a WS message.
async fn send_session_list(tx: &mpsc::UnboundedSender<String>, store: &SessionStore) {
    let sessions = store.list_sessions().await;
    let folders = store.list_all_folders().await;
    let active_id = store.get_active_id().await;
    let _ = tx.send(
        serde_json::json!({
            "type": "sessionList",
            "sessions": sessions,
            "folders": folders,
            "activeId": active_id
        })
        .to_string(),
    );
}

/// Build the initial serverConfig message sent on WS connection.
pub fn build_server_config_message(version: &str) -> String {
    serde_json::json!({
        "type": "serverConfig",
        "streamTimeoutMs": 600_000_u64,
        "version": version
    })
    .to_string()
}

/// Convert an axum WS Message to a text string (if it's text).
pub fn message_to_text(msg: &Message) -> Option<&str> {
    match msg {
        Message::Text(s) => Some(s.as_str()),
        _ => None,
    }
}

/// Get current time in milliseconds since UNIX epoch.
fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as u64
}

// ============================================================
// Axum WebSocket handler
// ============================================================

use axum::extract::ws::{WebSocket, WebSocketUpgrade};
use axum::extract::Query;
use axum::response::IntoResponse;
use axum::Extension;
use futures::{SinkExt, StreamExt};
use serde::Deserialize;

/// Query parameters for the WS endpoint.
#[derive(Debug, Deserialize)]
pub struct WsQuery {
    pub token: Option<String>,
}

/// GET /ws — WebSocket upgrade handler.
///
/// Validates the token from query param (or cookie), then upgrades
/// to a WebSocket connection. On connect, sends serverConfig + sessionList.
pub async fn ws_handler(
    ws: WebSocketUpgrade,
    Query(query): Query<WsQuery>,
    Extension(state): Extension<WsState>,
    headers: axum::http::HeaderMap,
) -> impl IntoResponse {
    // Check token from query param, fall back to cookie
    let token = query.token.or_else(|| extract_token_from_cookie(&headers));
    let provided = token.unwrap_or_default();
    if !state.auth.validate(&provided) {
        return axum::http::StatusCode::FORBIDDEN.into_response();
    }

    ws.on_upgrade(move |socket| handle_ws_connection(socket, state))
}

/// Extract the nebflow token from the Cookie header.
fn extract_token_from_cookie(headers: &axum::http::HeaderMap) -> Option<String> {
    let cookie = headers.get("cookie").and_then(|v| v.to_str().ok())?;
    for pair in cookie.split(';') {
        let pair = pair.trim();
        if let Some(token) = pair.strip_prefix("nebflow_token=") {
            return Some(token.to_string());
        }
    }
    None
}

/// Handle a single WebSocket connection lifecycle.
async fn handle_ws_connection(socket: WebSocket, state: WsState) {
    tracing::info!("WebSocket client connected");

    // Split into sender and receiver
    let (mut ws_sender, mut ws_receiver) = socket.split();

    // Create an unbounded channel for outbound messages
    let (tx, mut rx) = mpsc::unbounded_channel::<String>();

    // Register with the hub
    let conn_id = state.ws_hub.register(tx.clone()).await;

    // Send initial serverConfig
    let server_config = build_server_config_message("0.1.0");
    let _ = ws_sender
        .send(axum::extract::ws::Message::Text(server_config.into()))
        .await;

    // Send initial session list
    let sessions = state.session_store.list_sessions().await;
    let folders = state.session_store.list_all_folders().await;
    let active_id = state.session_store.get_active_id().await;
    let session_list = serde_json::json!({
        "type": "sessionList",
        "sessions": sessions,
        "folders": folders,
        "activeId": active_id
    });
    let _ = ws_sender
        .send(axum::extract::ws::Message::Text(
            session_list.to_string().into(),
        ))
        .await;

    // Spawn a task to forward channel messages to the WS sender
    let mut send_task = {
        let mut ws_sender = ws_sender;
        tokio::spawn(async move {
            while let Some(msg) = rx.recv().await {
                if ws_sender
                    .send(axum::extract::ws::Message::Text(msg.into()))
                    .await
                    .is_err()
                {
                    break;
                }
            }
        })
    };

    // Spawn a task to handle incoming messages
    let recv_state = state.clone();
    let recv_tx = tx.clone();
    let mut recv_task = tokio::spawn(async move {
        while let Some(Ok(msg)) = ws_receiver.next().await {
            if let Some(text) = message_to_text(&msg) {
                handle_message(text, &recv_tx, &recv_state).await;
            }
        }
    });

    // Wait for either task to finish (connection closed)
    tokio::select! {
        _ = &mut send_task => {},
        _ = &mut recv_task => {},
    }

    // Cleanup
    send_task.abort();
    recv_task.abort();
    state.ws_hub.unregister(&conn_id).await;
    tracing::info!("WebSocket client disconnected (conn_id={})", conn_id);
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    fn make_state() -> (WsState, tempfile::TempDir) {
        let dir = tempdir().unwrap();
        let store = Arc::new(SessionStore::new(dir.path().to_path_buf()));
        let auth = Arc::new(Auth::with_token("test-token".into()));
        let rl = Arc::new(RateLimiter::new());
        let hub = Arc::new(WsHub::new());
        (
            WsState {
                auth,
                rate_limiter: rl,
                session_store: store,
                ws_hub: hub,
                agent_manager: None,
            },
            dir,
        )
    }

    #[tokio::test]
    async fn ping_returns_pong() {
        let (state, _dir) = make_state();
        let (tx, mut rx) = mpsc::unbounded_channel();
        handle_message(r#"{"type":"ping"}"#, &tx, &state).await;
        let msg = rx.recv().await.unwrap();
        assert!(msg.contains("pong"));
    }

    #[tokio::test]
    async fn invalid_json_returns_invalid() {
        let (state, _dir) = make_state();
        let (tx, _rx) = mpsc::unbounded_channel();
        let result = handle_message("not json", &tx, &state).await;
        assert!(matches!(result, HandleResult::InvalidJson));
    }

    #[tokio::test]
    async fn oversized_returns_too_large() {
        let (state, _dir) = make_state();
        let (tx, _rx) = mpsc::unbounded_channel();
        let big = format!(
            r#"{{"type":"ping","data":"{}"}}"#,
            "x".repeat(MAX_MESSAGE_SIZE)
        );
        let result = handle_message(&big, &tx, &state).await;
        assert!(matches!(result, HandleResult::TooLarge));
    }

    #[tokio::test]
    async fn create_session_sends_session_list() {
        let (state, _dir) = make_state();
        state.session_store.load().await.unwrap();
        let (tx, mut rx) = mpsc::unbounded_channel();
        handle_message(
            r#"{"type":"createSession","name":"MySession"}"#,
            &tx,
            &state,
        )
        .await;
        let msg = rx.recv().await.unwrap();
        assert!(msg.contains("sessionList"));
        assert!(msg.contains("MySession"));
    }

    #[tokio::test]
    async fn get_history_returns_page() {
        let (state, _dir) = make_state();
        state.session_store.load().await.unwrap();
        let meta = state
            .session_store
            .create_session("Test", None, None)
            .await
            .unwrap();
        state
            .session_store
            .save_ui_messages(
                &meta.id,
                &[
                    UiMessage::User {
                        text: "hello".into(),
                        attachments: vec![],
                        timestamp: 1000,
                    },
                    UiMessage::Ai {
                        text: "world".into(),
                        model: None,
                        duration_ms: None,
                        timestamp: 2000,
                    },
                ],
            )
            .await
            .unwrap();

        let (tx, mut rx) = mpsc::unbounded_channel();
        let req = format!(
            r#"{{"type":"getHistory","sessionId":"{}","limit":10}}"#,
            meta.id
        );
        handle_message(&req, &tx, &state).await;
        let msg = rx.recv().await.unwrap();
        assert!(msg.contains("historyPage"));
        assert!(msg.contains("hello"));
        assert!(msg.contains("world"));
    }

    #[tokio::test]
    async fn switch_session_returns_session_list() {
        let (state, _dir) = make_state();
        state.session_store.load().await.unwrap();
        let sessions = state.session_store.list_sessions().await;
        let default_id = sessions[0].id.clone();

        let (tx, mut rx) = mpsc::unbounded_channel();
        let req = format!(r#"{{"type":"switchSession","sessionId":"{}"}}"#, default_id);
        handle_message(&req, &tx, &state).await;
        let msg = rx.recv().await.unwrap();
        assert!(msg.contains("sessionList"));
    }

    #[tokio::test]
    async fn unknown_type_silently_ignored() {
        let (state, _dir) = make_state();
        let (tx, _rx) = mpsc::unbounded_channel();
        let result = handle_message(r#"{"type":"unknownFutureType"}"#, &tx, &state).await;
        assert!(matches!(result, HandleResult::Ok));
    }

    #[test]
    fn build_server_config_has_required_fields() {
        let msg = build_server_config_message("1.0.0-test");
        let json: Value = serde_json::from_str(&msg).unwrap();
        assert_eq!(json["type"], "serverConfig");
        assert_eq!(json["version"], "1.0.0-test");
        assert!(json["streamTimeoutMs"].as_u64().is_some());
    }

    #[test]
    fn message_to_text_extracts_text() {
        let msg = Message::Text("hello".into());
        assert_eq!(message_to_text(&msg), Some("hello"));
    }

    #[tokio::test]
    async fn command_clear_clears_messages() {
        let (state, _dir) = make_state();
        state.session_store.load().await.unwrap();
        let meta = state
            .session_store
            .create_session("Test", None, None)
            .await
            .unwrap();
        // Save some UI messages
        state
            .session_store
            .save_ui_messages(
                &meta.id,
                &[UiMessage::User {
                    text: "hello".into(),
                    attachments: vec![],
                    timestamp: 1000,
                }],
            )
            .await
            .unwrap();

        let (tx, mut rx) = mpsc::unbounded_channel();
        let req = format!(
            r#"{{"type":"command","command":"clear","sessionId":"{}"}}"#,
            meta.id
        );
        handle_message(&req, &tx, &state).await;
        // Should receive a taskListUpdate
        let msg = rx.recv().await.unwrap();
        assert!(msg.contains("taskListUpdate"));
        // UI messages should have the system message
        let ui = state.session_store.load_ui_messages(&meta.id).await;
        assert!(ui.iter().any(|m| matches!(m, UiMessage::System { .. })));
    }

    #[tokio::test]
    async fn recall_message_removes_last_user() {
        let (state, _dir) = make_state();
        state.session_store.load().await.unwrap();
        let meta = state
            .session_store
            .create_session("Test", None, None)
            .await
            .unwrap();
        state
            .session_store
            .save_ui_messages(
                &meta.id,
                &[UiMessage::User {
                    text: "hello".into(),
                    attachments: vec![],
                    timestamp: 1000,
                }],
            )
            .await
            .unwrap();

        let (tx, mut rx) = mpsc::unbounded_channel();
        let req = format!(r#"{{"type":"recallMessage","sessionId":"{}"}}"#, meta.id);
        handle_message(&req, &tx, &state).await;
        let msg = rx.recv().await.unwrap();
        assert!(msg.contains("messageRecalled"));
        assert!(msg.contains(r#""success":true"#));
    }

    #[tokio::test]
    async fn delete_session_sends_updated_list() {
        let (state, _dir) = make_state();
        state.session_store.load().await.unwrap();
        let meta = state
            .session_store
            .create_session("ToDelete", None, None)
            .await
            .unwrap();

        let (tx, mut rx) = mpsc::unbounded_channel();
        let req = format!(r#"{{"type":"deleteSession","sessionId":"{}"}}"#, meta.id);
        handle_message(&req, &tx, &state).await;
        let msg = rx.recv().await.unwrap();
        assert!(msg.contains("sessionList"));
        let sessions = state.session_store.list_sessions().await;
        assert_eq!(sessions.len(), 1); // Only default remains
    }

    // ============================================================
    // agent_event_to_ws_json — must match Scala protocol.scala toJson
    // (root-agent branch, isSubagent = false) field for field.
    // ============================================================

    #[test]
    fn text_delta_json_matches_frontend() {
        let ev = AgentEvent::TextDelta { text: "hi".into() };
        let j = agent_event_to_ws_json(&ev, "s1");
        assert_eq!(
            j,
            json!({ "type": "textDelta", "sessionId": "s1", "delta": "hi" })
        );
    }

    #[test]
    fn tool_start_json_matches_frontend() {
        let ev = AgentEvent::ToolStart {
            label: "Read".into(),
        };
        let j = agent_event_to_ws_json(&ev, "s1");
        assert_eq!(
            j,
            json!({ "type": "toolStart", "sessionId": "s1", "label": "Read" })
        );
    }

    #[test]
    fn tool_end_without_input_omits_input_field() {
        let ev = AgentEvent::ToolEnd {
            label: "Read".into(),
            summary: "read file".into(),
            content: "data".into(),
            is_error: false,
            input: None,
        };
        let j = agent_event_to_ws_json(&ev, "s1");
        assert_eq!(
            j,
            json!({
                "type": "toolEnd",
                "sessionId": "s1",
                "label": "Read",
                "summary": "read file",
                "content": "data",
                "isError": false,
            })
        );
        assert!(j.get("input").is_none());
    }

    #[test]
    fn tool_end_with_input_includes_input() {
        let ev = AgentEvent::ToolEnd {
            label: "Read".into(),
            summary: "read file".into(),
            content: "data".into(),
            is_error: true,
            input: Some(json!({ "path": "/tmp/x" })),
        };
        let j = agent_event_to_ws_json(&ev, "s1");
        assert_eq!(j["isError"], true);
        assert_eq!(j["input"], json!({ "path": "/tmp/x" }));
    }

    #[test]
    fn thinking_and_tool_call_detected_json() {
        assert_eq!(
            agent_event_to_ws_json(&AgentEvent::Thinking, "s1"),
            json!({ "type": "thinking", "sessionId": "s1" })
        );
        let ev = AgentEvent::ToolCallDetected {
            name: "Bash".into(),
        };
        assert_eq!(
            agent_event_to_ws_json(&ev, "s1"),
            json!({ "type": "toolCallDetected", "sessionId": "s1", "name": "Bash" })
        );
    }

    #[test]
    fn done_omits_absent_optional_fields() {
        let ev = AgentEvent::Done {
            model: None,
            context_window: None,
            input_tokens: None,
            compact_threshold: None,
        };
        let j = agent_event_to_ws_json(&ev, "s1");
        assert_eq!(j, json!({ "type": "done", "sessionId": "s1" }));

        let ev = AgentEvent::Done {
            model: Some("claude".into()),
            context_window: Some(200_000),
            input_tokens: Some(1234),
            compact_threshold: Some(0.8),
        };
        let j = agent_event_to_ws_json(&ev, "s1");
        assert_eq!(
            j,
            json!({
                "type": "done",
                "sessionId": "s1",
                "model": "claude",
                "contextWindow": 200_000,
                "inputTokens": 1234,
                "compactThreshold": 0.8,
            })
        );
    }

    #[test]
    fn usage_update_json_matches_frontend() {
        let ev = AgentEvent::UsageUpdate {
            input_tokens: 42_000,
            context_window: 200_000,
            compact_threshold: 0.7,
        };
        let j = agent_event_to_ws_json(&ev, "s1");
        assert_eq!(
            j,
            json!({
                "type": "usageUpdate",
                "sessionId": "s1",
                "inputTokens": 42_000,
                "contextWindow": 200_000,
                "compactThreshold": 0.7,
            })
        );
    }

    #[test]
    fn compact_events_json_match_frontend() {
        let ev = AgentEvent::CompactStart {
            mode: "auto".into(),
            input_tokens: Some(150_000),
            threshold: Some(160_000),
        };
        assert_eq!(
            agent_event_to_ws_json(&ev, "s1"),
            json!({
                "type": "compactStart",
                "sessionId": "s1",
                "mode": "auto",
                "inputTokens": 150_000,
                "threshold": 160_000,
            })
        );

        let ev = AgentEvent::CompactComplete {
            before: 100,
            after: 40,
            report_path: Some("/tmp/report.md".into()),
        };
        assert_eq!(
            agent_event_to_ws_json(&ev, "s1"),
            json!({
                "type": "compactComplete",
                "sessionId": "s1",
                "before": 100,
                "after": 40,
                "reportPath": "/tmp/report.md",
            })
        );

        let ev = AgentEvent::CompactFailed {
            reason: "llm error".into(),
            attempt: 1,
            max_attempts: 3,
        };
        assert_eq!(
            agent_event_to_ws_json(&ev, "s1"),
            json!({
                "type": "compactFailed",
                "sessionId": "s1",
                "reason": "llm error",
                "attempt": 1,
                "maxAttempts": 3,
            })
        );
    }

    #[test]
    fn background_task_and_external_event_json() {
        let ev = AgentEvent::BackgroundTaskUpdate {
            task_id: "t1".into(),
            description: "build".into(),
            status: "running".into(),
        };
        assert_eq!(
            agent_event_to_ws_json(&ev, "s1"),
            json!({
                "type": "backgroundTaskUpdate",
                "taskId": "t1",
                "description": "build",
                "status": "running",
                "sessionId": "s1",
            })
        );

        let ev = AgentEvent::ExternalEventReceived {
            source: "mail".into(),
            event_type: "INFO".into(),
            correlation_id: Some("c1".into()),
        };
        assert_eq!(
            agent_event_to_ws_json(&ev, "s1"),
            json!({
                "type": "externalEventReceived",
                "source": "mail",
                "eventType": "INFO",
                "sessionId": "s1",
                "correlationId": "c1",
            })
        );
    }

    #[test]
    fn interrupted_json_matches_frontend() {
        assert_eq!(
            agent_event_to_ws_json(&AgentEvent::Interrupted, "s1"),
            json!({ "type": "interrupted", "sessionId": "s1" })
        );
    }

    #[tokio::test]
    async fn session_busy_format() {
        let (tx, mut rx) = mpsc::unbounded_channel();
        send_session_busy(&tx, "s1", true);
        let msg: Value = serde_json::from_str(&rx.recv().await.unwrap()).unwrap();
        assert_eq!(
            msg,
            json!({ "type": "sessionBusy", "sessionId": "s1", "busy": true })
        );
    }

    #[tokio::test]
    async fn user_input_without_agent_manager_returns_error() {
        let (state, _dir) = make_state();
        state.session_store.load().await.unwrap();
        let meta = state
            .session_store
            .create_session("Test", None, None)
            .await
            .unwrap();
        let (tx, mut rx) = mpsc::unbounded_channel();
        let req = format!(
            r#"{{"type":"userInput","sessionId":"{}","content":"hello"}}"#,
            meta.id
        );
        handle_message(&req, &tx, &state).await;
        let msg: Value = serde_json::from_str(&rx.recv().await.unwrap()).unwrap();
        assert_eq!(msg["type"], "error");
        assert_eq!(msg["message"], "Agent runtime unavailable");
        // User message still persisted to UI transcript.
        let ui = state.session_store.load_ui_messages(&meta.id).await;
        assert!(ui.iter().any(|m| matches!(m, UiMessage::User { .. })));
    }

    #[tokio::test]
    async fn user_input_empty_content_ignored() {
        let (state, _dir) = make_state();
        let (tx, mut rx) = mpsc::unbounded_channel();
        handle_message(
            r#"{"type":"userInput","sessionId":"s1","content":""}"#,
            &tx,
            &state,
        )
        .await;
        assert!(rx.try_recv().is_err());
    }

    #[tokio::test]
    async fn memory_status_returns_disabled() {
        let (state, _dir) = make_state();
        let (tx, mut rx) = mpsc::unbounded_channel();
        handle_message(r#"{"type":"memoryStatus"}"#, &tx, &state).await;
        let msg: Value = serde_json::from_str(&rx.recv().await.unwrap()).unwrap();
        assert_eq!(msg["type"], "memoryStatus");
        assert_eq!(msg["enabled"], false);
    }

    #[tokio::test]
    async fn get_llm_log_returns_disabled() {
        let (state, _dir) = make_state();
        let (tx, mut rx) = mpsc::unbounded_channel();
        handle_message(r#"{"type":"getLlmLog"}"#, &tx, &state).await;
        let msg: Value = serde_json::from_str(&rx.recv().await.unwrap()).unwrap();
        assert_eq!(msg["type"], "llmLogState");
        assert_eq!(msg["enabled"], false);
    }

    #[tokio::test]
    async fn get_skills_returns_list() {
        let (state, _dir) = make_state();
        let (tx, mut rx) = mpsc::unbounded_channel();
        handle_message(r#"{"type":"getSkills"}"#, &tx, &state).await;
        let msg: Value = serde_json::from_str(&rx.recv().await.unwrap()).unwrap();
        assert_eq!(msg["type"], "skillList");
        assert!(msg["skills"].is_array());
    }

    #[tokio::test]
    async fn get_teams_returns_list() {
        let (state, _dir) = make_state();
        let (tx, mut rx) = mpsc::unbounded_channel();
        handle_message(r#"{"type":"getTeams"}"#, &tx, &state).await;
        let msg: Value = serde_json::from_str(&rx.recv().await.unwrap()).unwrap();
        assert_eq!(msg["type"], "teamList");
        assert!(msg["teams"].is_array());
    }

    #[tokio::test]
    async fn get_config_returns_config_data() {
        let (state, _dir) = make_state();
        let (tx, mut rx) = mpsc::unbounded_channel();
        handle_message(r#"{"type":"getConfig"}"#, &tx, &state).await;
        let msg: Value = serde_json::from_str(&rx.recv().await.unwrap()).unwrap();
        assert_eq!(msg["type"], "configData");
        assert!(msg["config"].is_object());
    }

    #[tokio::test]
    async fn get_model_options_returns_models() {
        let (state, _dir) = make_state();
        let (tx, mut rx) = mpsc::unbounded_channel();
        handle_message(r#"{"type":"getModelOptions"}"#, &tx, &state).await;
        let msg: Value = serde_json::from_str(&rx.recv().await.unwrap()).unwrap();
        assert_eq!(msg["type"], "modelOptions");
        assert!(msg["models"]["default"].is_string());
    }

    #[tokio::test]
    async fn set_thinking_silently_accepted() {
        let (state, _dir) = make_state();
        let (tx, mut rx) = mpsc::unbounded_channel();
        handle_message(r#"{"type":"setThinking","enabled":true}"#, &tx, &state).await;
        assert!(
            rx.try_recv().is_err(),
            "setThinking should not send a response"
        );
    }

    #[tokio::test]
    async fn set_voice_muted_silently_accepted() {
        let (state, _dir) = make_state();
        let (tx, mut rx) = mpsc::unbounded_channel();
        handle_message(r#"{"type":"setVoiceMuted","muted":true}"#, &tx, &state).await;
        assert!(
            rx.try_recv().is_err(),
            "setVoiceMuted should not send a response"
        );
    }

    #[test]
    fn extract_token_from_cookie_valid() {
        let mut headers = axum::http::HeaderMap::new();
        headers.insert(
            "cookie",
            "other=val; nebflow_token=abc123; foo=bar".parse().unwrap(),
        );
        let token = extract_token_from_cookie(&headers);
        assert_eq!(token.as_deref(), Some("abc123"));
    }

    #[test]
    fn extract_token_from_cookie_missing() {
        let headers = axum::http::HeaderMap::new();
        assert!(extract_token_from_cookie(&headers).is_none());
    }

    #[test]
    fn extract_token_from_cookie_no_nebflow_token() {
        let mut headers = axum::http::HeaderMap::new();
        headers.insert("cookie", "other=val".parse().unwrap());
        assert!(extract_token_from_cookie(&headers).is_none());
    }
}

//! Session command — `nebflow session list|delete|rename|history`.
//!
//! Communicates with the running Gateway via REST API.
//! Mirrors Scala SessionCommand.scala.

use crate::client::GatewayClient;

/// `nebflow session list` — list all sessions.
pub async fn list(client: &GatewayClient, json_mode: bool) -> i32 {
    match client.get("/api/sessions").await {
        Ok(sessions) => {
            if json_mode {
                println!("{}", serde_json::to_string_pretty(&sessions).unwrap());
            } else {
                let arr = sessions.as_array();
                if arr.map(|a| a.is_empty()).unwrap_or(true) {
                    println!("No sessions");
                } else if let Some(sessions) = arr {
                    println!("ID        Name");
                    for s in sessions {
                        let id = s
                            .get("id")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .chars()
                            .take(8)
                            .collect::<String>();
                        let name = s.get("name").and_then(|v| v.as_str()).unwrap_or("-");
                        println!("{id:<10}{name}");
                    }
                }
            }
            0
        }
        Err(e) => {
            eprintln!("Error: {e}");
            1
        }
    }
}

/// `nebflow session delete <id>` — delete a session.
pub async fn delete(client: &GatewayClient, id: &str, json_mode: bool) -> i32 {
    match client.delete(&format!("/api/sessions/{id}")).await {
        Ok(resp) => {
            if json_mode {
                println!("{}", serde_json::to_string_pretty(&resp).unwrap());
            } else {
                println!("Session {id} deleted");
            }
            0
        }
        Err(e) => {
            eprintln!("Error: {e}");
            1
        }
    }
}

/// `nebflow session rename <id> --name <name>` — rename a session.
pub async fn rename(client: &GatewayClient, id: &str, name: &str, json_mode: bool) -> i32 {
    let payload = serde_json::json!({ "name": name });
    match client
        .post(&format!("/api/sessions/{id}/rename"), &payload)
        .await
    {
        Ok(resp) => {
            if json_mode {
                println!("{}", serde_json::to_string_pretty(&resp).unwrap());
            } else {
                println!("Session renamed to '{name}'");
            }
            0
        }
        Err(e) => {
            eprintln!("Error: {e}");
            1
        }
    }
}

/// `nebflow session history <id> --limit <n>` — show session history.
pub async fn history(client: &GatewayClient, id: &str, limit: usize, json_mode: bool) -> i32 {
    let path = format!("/api/sessions/{id}/history?limit={limit}");
    match client.get(&path).await {
        Ok(resp) => {
            if json_mode {
                println!("{}", serde_json::to_string_pretty(&resp).unwrap());
            } else {
                let messages = resp
                    .get("messages")
                    .and_then(|v| v.as_array())
                    .cloned()
                    .unwrap_or_default();
                if messages.is_empty() {
                    println!("No messages");
                } else {
                    for msg in &messages {
                        if let Some(text) = msg.get("text").and_then(|v| v.as_str()) {
                            let msg_type = msg.get("type").and_then(|v| v.as_str()).unwrap_or("?");
                            println!("[{msg_type}] {text}");
                        }
                    }
                }
            }
            0
        }
        Err(e) => {
            eprintln!("Error: {e}");
            1
        }
    }
}

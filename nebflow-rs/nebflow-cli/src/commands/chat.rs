//! Chat command — `nebflow chat <message>`.
//!
//! Sends a chat message to the running Gateway via REST API.
//! Mirrors Scala ChatCommands.scala.

use crate::client::GatewayClient;

/// `nebflow chat <message>` — send a single chat message.
pub async fn send(
    client: &GatewayClient,
    message: &str,
    session: Option<&str>,
    json_mode: bool,
) -> i32 {
    let payload = serde_json::json!({
        "message": message,
        "session_id": session,
    });

    match client.post("/api/v1/chat", &payload).await {
        Ok(resp) => {
            if json_mode {
                println!("{}", serde_json::to_string_pretty(&resp).unwrap());
            } else if let Some(reply) = resp.get("reply").and_then(|v| v.as_str()) {
                println!("{reply}");
            } else {
                println!("{}", serde_json::to_string_pretty(&resp).unwrap());
            }
            0
        }
        Err(e) => {
            eprintln!("Error: {e}");
            1
        }
    }
}

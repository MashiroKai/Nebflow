//! Agent command — `nebflow agent-list` and `nebflow agent-show <name>`.
//!
//! Lists agents and shows agent details via the Gateway REST API.
//! Mirrors Scala AgentCommand.scala.

use crate::client::GatewayClient;

/// `nebflow agent-list` — list registered agents.
pub async fn list(client: &GatewayClient, json_mode: bool) -> i32 {
    match client.get("/api/agents").await {
        Ok(agents) => {
            if json_mode {
                println!("{}", serde_json::to_string_pretty(&agents).unwrap());
            } else {
                let arr = agents.as_array();
                if arr.map(|a| a.is_empty()).unwrap_or(true) {
                    println!("No agents");
                } else if let Some(agents) = arr {
                    println!("Agents:");
                    for a in agents {
                        let name = a.get("name").and_then(|v| v.as_str()).unwrap_or("?");
                        let display = a
                            .get("displayName")
                            .and_then(|v| v.as_str())
                            .unwrap_or(name);
                        println!("  {name}  ({display})");
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

/// `nebflow agent-show <name>` — show agent details.
pub async fn show(client: &GatewayClient, name: &str, json_mode: bool) -> i32 {
    let payload = serde_json::json!({ "name": name });
    match client.post("/api/agents/show", &payload).await {
        Ok(resp) => {
            if json_mode {
                println!("{}", serde_json::to_string_pretty(&resp).unwrap());
            } else if let Some(prompt) = resp.get("systemMd").and_then(|v| v.as_str()) {
                if !prompt.is_empty() {
                    println!("Agent: {name}\n\n{prompt}");
                } else {
                    println!("Agent: {name}\n\n(no system prompt)");
                }
            } else {
                println!("Agent: {name}\n\n(no details available)");
            }
            0
        }
        Err(e) => {
            eprintln!("Error: {e}");
            1
        }
    }
}

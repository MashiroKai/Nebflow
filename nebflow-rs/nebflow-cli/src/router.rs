//! Command router — dispatches CLI commands to their handlers.
//!
//! Mirrors Scala Main.scala + CliRouter.scala dispatch logic.

use crate::client::GatewayClient;
use crate::commands;
use crate::{Cli, Commands, ConfigAction, SessionAction};

/// Dispatch the parsed CLI command. Returns exit code (0 = success).
pub async fn dispatch(cli: Cli) -> i32 {
    let json_mode = cli.json;
    let _quiet_mode = cli.quiet;

    match cli.command {
        // No command → start gateway (backward compatible with Scala)
        None => start_gateway(cli).await,

        Some(Commands::Agent) | Some(Commands::Start) => start_gateway(cli).await,

        Some(Commands::Stop) => {
            crate::process::stop();
            0
        }

        Some(Commands::Status) => {
            // Check if the gateway port is listening (not PID-based)
            let gw_config = nebflow_gateway::GatewayConfig::from_env();
            let port = cli.port.unwrap_or(gw_config.port);
            let addr = format!("127.0.0.1:{}", port);
            match std::net::TcpStream::connect(&addr) {
                Ok(_) => {
                    if json_mode {
                        println!(r#"{{"running":true,"port":{}}}"#, port);
                    } else {
                        println!("nebflow is running on port {port}");
                    }
                    0
                }
                Err(_) => {
                    if json_mode {
                        println!(r#"{{"running":false}}"#);
                    } else {
                        println!("nebflow is not running");
                    }
                    crate::process::remove_pid();
                    0
                }
            }
        }

        Some(Commands::Config { ref action }) => match action {
            ConfigAction::Show => commands::config::show().await,
            ConfigAction::Get { key } => commands::config::get(key).await,
            ConfigAction::Set { key, value } => commands::config::set(key, value).await,
        },

        Some(Commands::Session { ref action }) => {
            let client = match create_client().await {
                Some(c) => c,
                None => {
                    eprintln!("Gateway not running. Start with 'nebflow agent'");
                    return 1;
                }
            };
            match action {
                SessionAction::List => commands::session::list(&client, json_mode).await,
                SessionAction::Delete { id } => {
                    commands::session::delete(&client, id, json_mode).await
                }
                SessionAction::Rename { id, name } => {
                    commands::session::rename(&client, id, name, json_mode).await
                }
                SessionAction::History { id, limit } => {
                    commands::session::history(&client, id, *limit, json_mode).await
                }
            }
        }

        Some(Commands::Chat {
            ref message,
            ref session,
        }) => {
            let client = match create_client().await {
                Some(c) => c,
                None => {
                    eprintln!("Gateway not running. Start with 'nebflow agent'");
                    return 1;
                }
            };
            commands::chat::send(&client, message, session.as_deref(), json_mode).await
        }

        Some(Commands::AgentList) => {
            let client = match create_client().await {
                Some(c) => c,
                None => {
                    eprintln!("Gateway not running. Start with 'nebflow agent'");
                    return 1;
                }
            };
            commands::agent::list(&client, json_mode).await
        }

        Some(Commands::AgentShow { ref name }) => {
            let client = match create_client().await {
                Some(c) => c,
                None => {
                    eprintln!("Gateway not running. Start with 'nebflow agent'");
                    return 1;
                }
            };
            commands::agent::show(&client, name, json_mode).await
        }
    }
}

/// Start the gateway server.
async fn start_gateway(cli: Cli) -> i32 {
    // Load config
    let mut gw_config = nebflow_gateway::GatewayConfig::from_env();
    if let Some(port) = cli.port {
        gw_config = gw_config.with_port(port);
    }
    let addr = gw_config.socket_addr();

    // Port detection: try to bind the port. If it fails, another instance
    // is already running (or the port is occupied).
    match tokio::net::TcpListener::bind(&addr).await {
        Ok(listener) => {
            // Port is available — drop the listener and continue.
            drop(listener);
        }
        Err(e) => {
            eprintln!(
                "nebflow is already running or port {} is in use: {e}",
                gw_config.port
            );
            return 1;
        }
    }

    // Write PID file for informational purposes (not used for single-instance lock)
    let pid = std::process::id();
    crate::process::write_pid(pid);

    // Ensure config directory exists
    let config_path = nebflow_core::config::default_config_path();
    if !config_path.exists() {
        if let Some(parent) = config_path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let _ = std::fs::write(&config_path, "{}");
    }

    // Open browser (unless --no-browser)
    if !cli.no_browser {
        let token = nebflow_gateway::Auth::new()
            .map(|auth| auth.token().to_string())
            .unwrap_or_default();
        let url = format!("http://localhost:{}?token={}", gw_config.port, token);
        #[cfg(target_os = "macos")]
        let _ = std::process::Command::new("open").arg(&url).spawn();
        #[cfg(target_os = "linux")]
        let _ = std::process::Command::new("xdg-open").arg(&url).spawn();
        #[cfg(target_os = "windows")]
        let _ = std::process::Command::new("cmd")
            .args(["/C", "start", &url])
            .spawn();
    }

    let result = nebflow_gateway::run_server(gw_config).await;

    // Cleanup PID file on shutdown
    crate::process::remove_pid();

    match result {
        Ok(()) => {
            if !cli.quiet {
                println!("nebflow stopped.");
            }
            0
        }
        Err(e) => {
            eprintln!("Gateway error: {e}");
            1
        }
    }
}

/// Create a GatewayClient if the gateway is running.
async fn create_client() -> Option<GatewayClient> {
    GatewayClient::create().await
}

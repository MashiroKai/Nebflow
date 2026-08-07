//! nebflow-cli — CLI entry point (Phase 7).
//!
//! Implements the command-line interface using clap derive.
//! Commands: agent (start), config (show/set), session (list/delete), chat.

mod client;
mod commands;
mod process;
mod router;

#[cfg(test)]
mod test_util {
    use std::sync::{Mutex, OnceLock};

    /// Global mutex to serialize tests that modify the HOME environment variable.
    /// All test modules share this single instance to prevent cross-module races.
    pub fn home_mutex() -> &'static Mutex<()> {
        static M: OnceLock<Mutex<()>> = OnceLock::new();
        M.get_or_init(|| Mutex::new(()))
    }
}

use clap::{Parser, Subcommand};

/// nebflow — AI Agent Orchestration Platform.
#[derive(Parser, Debug)]
#[command(
    name = "nebflow",
    version,
    about = "AI Agent Orchestration Platform",
    long_about = None
)]
pub struct Cli {
    /// Set the data root directory (default: ~/.nebflow)
    #[arg(long, global = true)]
    home: Option<String>,

    /// Override the gateway port
    #[arg(long, global = true)]
    port: Option<u16>,

    /// Skip opening the browser on startup
    #[arg(long, global = true)]
    no_browser: bool,

    /// Output as JSON
    #[arg(long, global = true)]
    json: bool,

    /// Suppress non-essential output
    #[arg(long, global = true)]
    quiet: bool,

    #[command(subcommand)]
    command: Option<Commands>,
}

#[derive(Subcommand, Debug)]
pub enum Commands {
    /// Start the gateway server
    Agent,

    /// Alias for 'agent' — start the gateway server
    Start,

    /// Stop the running gateway server
    Stop,

    /// Show server status
    Status,

    /// Manage configuration
    Config {
        #[command(subcommand)]
        action: ConfigAction,
    },

    /// Manage sessions
    Session {
        #[command(subcommand)]
        action: SessionAction,
    },

    /// Send a chat message
    Chat {
        /// The message to send
        message: String,

        /// Session ID to use
        #[arg(short, long)]
        session: Option<String>,
    },

    /// List available agents
    AgentList,

    /// Show agent details
    AgentShow {
        /// Agent name
        name: String,
    },
}

#[derive(Subcommand, Debug)]
pub enum ConfigAction {
    /// Show full configuration
    Show,

    /// Get a specific config value
    Get {
        /// Dot-separated key path (e.g. llm.model.default)
        key: String,
    },

    /// Set a config value
    Set {
        /// Dot-separated key path
        key: String,
        /// Value to set (strings, numbers, booleans, JSON)
        value: String,
    },
}

#[derive(Subcommand, Debug)]
pub enum SessionAction {
    /// List all sessions
    List,

    /// Delete a session
    Delete {
        /// Session ID
        id: String,
    },

    /// Rename a session
    Rename {
        /// Session ID
        id: String,
        /// New name
        #[arg(short, long)]
        name: String,
    },

    /// Show session history
    History {
        /// Session ID
        id: String,
        /// Max messages to show
        #[arg(short, long, default_value = "50")]
        limit: usize,
    },
}

fn main() {
    let cli = Cli::parse();

    // Set data root if --home specified
    if let Some(ref home) = cli.home {
        let expanded = expand_home(home);
        std::env::set_var("HOME", &expanded);
    }

    // Set port if --port specified
    if let Some(port) = cli.port {
        std::env::set_var("NEBFLOW_GATEWAY_PORT", port.to_string());
    }

    // Initialize logging
    if !cli.quiet {
        let _ = tracing_subscriber::fmt()
            .with_env_filter(
                tracing_subscriber::EnvFilter::try_from_default_env()
                    .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("warn")),
            )
            .try_init();
    }

    // Dispatch
    let rt = tokio::runtime::Runtime::new().expect("failed to create tokio runtime");
    let exit_code = rt.block_on(router::dispatch(cli));

    std::process::exit(exit_code);
}

/// Expand ~ to the user's home directory.
fn expand_home(path: &str) -> String {
    if path == "~" {
        std::env::var("HOME").unwrap_or_else(|_| path.to_string())
    } else if let Some(rest) = path.strip_prefix("~/") {
        format!("{}/{rest}", std::env::var("HOME").unwrap_or_default())
    } else {
        path.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use clap::Parser;

    #[test]
    fn parse_no_command() {
        let cli = Cli::parse_from(["nebflow"]);
        assert!(cli.command.is_none());
    }

    #[test]
    fn parse_agent() {
        let cli = Cli::parse_from(["nebflow", "agent"]);
        assert!(matches!(cli.command, Some(Commands::Agent)));
    }

    #[test]
    fn parse_start() {
        let cli = Cli::parse_from(["nebflow", "start"]);
        assert!(matches!(cli.command, Some(Commands::Start)));
    }

    #[test]
    fn parse_stop() {
        let cli = Cli::parse_from(["nebflow", "stop"]);
        assert!(matches!(cli.command, Some(Commands::Stop)));
    }

    #[test]
    fn parse_config_show() {
        let cli = Cli::parse_from(["nebflow", "config", "show"]);
        assert!(matches!(
            cli.command,
            Some(Commands::Config {
                action: ConfigAction::Show
            })
        ));
    }

    #[test]
    fn parse_config_get() {
        let cli = Cli::parse_from(["nebflow", "config", "get", "llm.model.default"]);
        assert!(matches!(
            cli.command,
            Some(Commands::Config { action: ConfigAction::Get { key } }) if key == "llm.model.default"
        ));
    }

    #[test]
    fn parse_config_set() {
        let cli = Cli::parse_from(["nebflow", "config", "set", "key", "value"]);
        assert!(matches!(
            cli.command,
            Some(Commands::Config { action: ConfigAction::Set { key, value } })
                if key == "key" && value == "value"
        ));
    }

    #[test]
    fn parse_session_list() {
        let cli = Cli::parse_from(["nebflow", "session", "list"]);
        assert!(matches!(
            cli.command,
            Some(Commands::Session {
                action: SessionAction::List
            })
        ));
    }

    #[test]
    fn parse_session_delete() {
        let cli = Cli::parse_from(["nebflow", "session", "delete", "abc-123"]);
        assert!(matches!(
            cli.command,
            Some(Commands::Session { action: SessionAction::Delete { id } }) if id == "abc-123"
        ));
    }

    #[test]
    fn parse_chat() {
        let cli = Cli::parse_from(["nebflow", "chat", "hello world"]);
        assert!(matches!(
            cli.command,
            Some(Commands::Chat { message, session: None }) if message == "hello world"
        ));
    }

    #[test]
    fn parse_chat_with_session() {
        let cli = Cli::parse_from(["nebflow", "chat", "--session", "sid-123", "hello"]);
        assert!(matches!(
            cli.command,
            Some(Commands::Chat { message, session: Some(s) })
                if message == "hello" && s == "sid-123"
        ));
    }

    #[test]
    fn parse_global_flags() {
        let cli = Cli::parse_from(["nebflow", "--port", "9090", "--no-browser", "--json"]);
        assert_eq!(cli.port, Some(9090));
        assert!(cli.no_browser);
        assert!(cli.json);
    }

    #[test]
    fn parse_home_flag() {
        let cli = Cli::parse_from(["nebflow", "--home", "/tmp/test", "agent"]);
        assert_eq!(cli.home.as_deref(), Some("/tmp/test"));
    }

    #[test]
    fn expand_home_tilde() {
        let _g = crate::test_util::home_mutex().lock().unwrap();
        std::env::set_var("HOME", "/Users/test");
        assert_eq!(expand_home("~"), "/Users/test");
        assert_eq!(expand_home("~/nebflow"), "/Users/test/nebflow");
    }

    #[test]
    fn expand_home_absolute() {
        assert_eq!(expand_home("/var/data"), "/var/data");
    }
}

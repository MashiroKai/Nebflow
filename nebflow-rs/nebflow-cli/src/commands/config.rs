//! Config command — `nebflow config show|get|set`.
//!
//! Reads/writes `~/.nebflow/nebflow.json` directly.
//! Mirrors Scala ConfigCommand.scala.

/// `nebflow config show` — display the full configuration.
pub async fn show() -> i32 {
    let path = nebflow_core::config::default_config_path();
    let content = nebflow_core::config::read_raw_config(&path);

    // Pretty-print if valid JSON
    match serde_json::from_str::<serde_json::Value>(&content) {
        Ok(json) => {
            println!("{}", serde_json::to_string_pretty(&json).unwrap_or(content));
        }
        Err(_) => {
            println!("{content}");
        }
    }
    0
}

/// `nebflow config get <key>` — get a specific config value.
pub async fn get(key: &str) -> i32 {
    let path = nebflow_core::config::default_config_path();
    let content = nebflow_core::config::read_raw_config(&path);

    let json: serde_json::Value = serde_json::from_str(&content).unwrap_or(serde_json::json!({}));
    match nebflow_core::config::get_config_value(&json, key) {
        Some(value) => {
            println!(
                "{}",
                serde_json::to_string_pretty(value).unwrap_or_else(|_| value.to_string())
            );
            0
        }
        None => {
            eprintln!("Key not found: {key}");
            1
        }
    }
}

/// `nebflow config set <key> <value>` — set a config value.
pub async fn set(key: &str, value: &str) -> i32 {
    let path = nebflow_core::config::default_config_path();
    let content = nebflow_core::config::read_raw_config(&path);

    let mut json: serde_json::Value =
        serde_json::from_str(&content).unwrap_or(serde_json::json!({}));

    nebflow_core::config::set_config_value(&mut json, key, value);

    // Save
    if let Some(parent) = path.parent() {
        if let Err(e) = std::fs::create_dir_all(parent) {
            eprintln!("Failed to create config directory: {e}");
            return 1;
        }
    }

    let pretty = serde_json::to_string_pretty(&json).unwrap_or_else(|_| content.clone());
    if let Err(e) = std::fs::write(&path, pretty) {
        eprintln!("Failed to write config: {e}");
        return 1;
    }

    println!("Config updated: {key} = {value}");
    0
}

#[cfg(test)]
mod tests {
    use super::*;

    // Helper: set HOME to a temp dir, write config, return dir to keep alive.
    fn setup_config_home(content: &str) -> tempfile::TempDir {
        let _g = crate::test_util::home_mutex().lock().unwrap();
        let dir = tempfile::tempdir().unwrap();
        std::env::set_var("HOME", dir.path());
        let nebflow_dir = dir.path().join(".nebflow");
        std::fs::create_dir_all(&nebflow_dir).unwrap();
        if !content.is_empty() {
            std::fs::write(nebflow_dir.join("nebflow.json"), content).unwrap();
        }
        dir
    }

    // Helper: set HOME to empty temp dir (no config file).
    fn setup_empty_home() -> tempfile::TempDir {
        let _g = crate::test_util::home_mutex().lock().unwrap();
        let dir = tempfile::tempdir().unwrap();
        std::env::set_var("HOME", dir.path());
        dir
    }

    #[tokio::test]
    async fn show_prints_config() {
        let _dir = setup_config_home(r#"{"test": "value"}"#);
        let code = show().await;
        assert_eq!(code, 0);
    }

    #[tokio::test]
    async fn show_handles_missing_config() {
        let _dir = setup_empty_home();
        let code = show().await;
        assert_eq!(code, 0);
    }

    #[tokio::test]
    async fn get_existing_key() {
        let _dir = setup_config_home(r#"{"llm": {"model": {"default": "openai/gpt-4o"}}}"#);
        let code = get("llm.model.default").await;
        assert_eq!(code, 0);
    }

    #[tokio::test]
    async fn get_missing_key_returns_error() {
        let _dir = setup_config_home(r#"{"a": 1}"#);
        let code = get("nonexistent.key").await;
        assert_eq!(code, 1);
    }

    #[tokio::test]
    async fn set_creates_nested_key() {
        let dir = setup_config_home(r#"{}"#);

        let code = set("llm.model.default", "anthropic/claude").await;
        assert_eq!(code, 0);

        // Verify it was saved
        let content =
            std::fs::read_to_string(dir.path().join(".nebflow").join("nebflow.json")).unwrap();
        let json: serde_json::Value = serde_json::from_str(&content).unwrap();
        assert_eq!(json["llm"]["model"]["default"], "anthropic/claude");
    }

    #[tokio::test]
    async fn set_updates_existing_key() {
        let dir = setup_config_home(r#"{"llm": {"model": {"default": "old-value"}}}"#);

        let code = set("llm.model.default", "new-value").await;
        assert_eq!(code, 0);

        let content =
            std::fs::read_to_string(dir.path().join(".nebflow").join("nebflow.json")).unwrap();
        let json: serde_json::Value = serde_json::from_str(&content).unwrap();
        assert_eq!(json["llm"]["model"]["default"], "new-value");
    }
}

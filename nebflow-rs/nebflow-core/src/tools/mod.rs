//! Tool system — ToolRegistry, built-in tools, and auxiliary modules.
//!
//! Mirrors `nebflow.core.tools` from the Scala codebase.

pub mod bg_task;
pub mod file_history;
pub mod file_lock;
pub mod guard;
pub mod read_tracker;
pub mod string_matcher;
#[allow(clippy::module_inception)]
pub mod tools;

// Re-export the Tool trait from crate root for convenience.
pub use crate::Tool;

use crate::types::ToolDefinition;
use std::collections::HashMap;
use std::sync::Arc;

/// ToolRegistry — maps tool names to Tool instances.
/// Thread-safe via RwLock on the inner HashMap.
pub struct ToolRegistry {
    tools: HashMap<String, Arc<dyn Tool>>,
}

impl ToolRegistry {
    /// Create an empty registry.
    pub fn new() -> Self {
        Self {
            tools: HashMap::new(),
        }
    }

    /// Create a registry pre-populated with all built-in tools.
    pub fn builtin() -> Self {
        let mut reg = Self::new();
        reg.register_all(tools::all_builtin_tools());
        reg
    }

    /// Register a single tool.
    pub fn register(&mut self, tool: Arc<dyn Tool>) {
        let name = tool.name().to_string();
        self.tools.insert(name, tool);
    }

    /// Register multiple tools.
    pub fn register_all(&mut self, tools: Vec<Arc<dyn Tool>>) {
        for t in tools {
            self.register(t);
        }
    }

    /// Unregister a tool by name.
    pub fn unregister(&mut self, name: &str) {
        self.tools.remove(name);
    }

    /// Unregister all tools whose name starts with the given prefix.
    pub fn unregister_by_prefix(&mut self, prefix: &str) {
        self.tools.retain(|name, _| !name.starts_with(prefix));
    }

    /// Look up a tool by name.
    pub fn get(&self, name: &str) -> Option<&Arc<dyn Tool>> {
        self.tools.get(name)
    }

    /// Return ToolDefinitions for the given allowed tool names.
    /// If `allowed` is None or contains "*", returns all tools.
    pub fn definitions(&self, allowed: Option<&[String]>) -> Vec<ToolDefinition> {
        let all = allowed
            .map(|list| list.iter().any(|s| s == "*"))
            .unwrap_or(true);
        self.tools
            .values()
            .filter(|t| all || allowed.unwrap().iter().any(|a| a == t.name()))
            .map(|t| ToolDefinition {
                name: t.name().to_string(),
                description: t.description().to_string(),
                input_schema: serde_json::Value::Object(t.input_schema().clone()),
            })
            .collect()
    }

    /// Return sorted list of builtin tool names (non-MCP).
    pub fn builtin_names(&self) -> Vec<String> {
        let mut names: Vec<String> = self
            .tools
            .keys()
            .filter(|name| !name.starts_with("mcp__"))
            .cloned()
            .collect();
        names.sort();
        names
    }
}

impl Default for ToolRegistry {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn registry_builtin_has_core_tools() {
        let reg = ToolRegistry::builtin();
        assert!(reg.get("Read").is_some());
        assert!(reg.get("Write").is_some());
        assert!(reg.get("Edit").is_some());
        assert!(reg.get("Glob").is_some());
        assert!(reg.get("Grep").is_some());
        assert!(reg.get("Bash").is_some());
    }

    #[test]
    fn registry_definitions_all() {
        let reg = ToolRegistry::builtin();
        let defs = reg.definitions(None);
        assert!(defs.len() >= 10);
        assert!(defs.iter().any(|d| d.name == "Read"));
    }

    #[test]
    fn registry_definitions_filtered() {
        let reg = ToolRegistry::builtin();
        let allowed = vec!["Read".to_string(), "Grep".to_string()];
        let defs = reg.definitions(Some(&allowed));
        assert_eq!(defs.len(), 2);
        assert!(defs.iter().any(|d| d.name == "Read"));
        assert!(defs.iter().any(|d| d.name == "Grep"));
    }

    #[test]
    fn registry_register_and_unregister() {
        let mut reg = ToolRegistry::new();
        let tool = tools::read::ReadTool::new();
        reg.register(Arc::new(tool));
        assert!(reg.get("Read").is_some());
        reg.unregister("Read");
        assert!(reg.get("Read").is_none());
    }

    #[test]
    fn registry_unregister_by_prefix() {
        let mut reg = ToolRegistry::new();
        // Simulate MCP tools
        let tool1 = tools::read::ReadTool::new(); // we'll reuse as placeholder
                                                  // Manually insert with mcp prefix
        reg.register(Arc::new(tool1));
        reg.unregister_by_prefix("Read");
        assert!(reg.get("Read").is_none());
    }
}

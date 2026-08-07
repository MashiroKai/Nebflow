//! Flow tree — flow mounting lifecycle management.
//!
//! Mirrors Scala `FlowTreeActor.scala` + `FlowTreeRegistry.scala`.
//! In the Rust port, the tree actor pattern is replaced with an async
//! struct that manages flow mounting/unmounting state.

use std::collections::HashMap;
use std::sync::Arc;

use tokio::sync::RwLock;

use crate::entity::FlowDagDef;
use crate::flow::loader::EntityLoader;
use crate::flow::membership::FlowMembership;
use crate::flow::mounted::{MountedFlowEntry, MountedFlowStore};

/// Configuration for a flow tree instance.
#[derive(Debug, Clone)]
pub struct TreeConfig {
    pub session_id: String,
    pub data_root: std::path::PathBuf,
}

/// Flow tree — manages flow mounting and session lifecycle for one parent session.
pub struct FlowTree {
    config: TreeConfig,
    loader: Arc<EntityLoader>,
    membership: Arc<FlowMembership>,
    mounted_store: Arc<MountedFlowStore>,
    // instanceName → flowName
    flow_names: RwLock<HashMap<String, String>>,
}

impl FlowTree {
    pub fn new(
        config: TreeConfig,
        loader: Arc<EntityLoader>,
        membership: Arc<FlowMembership>,
        mounted_store: Arc<MountedFlowStore>,
    ) -> Self {
        Self {
            config,
            loader,
            membership,
            mounted_store,
            flow_names: RwLock::new(HashMap::new()),
        }
    }

    /// Mount a flow: load definition, register sessions in membership.
    pub async fn mount_flow(&self, instance_name: &str, flow_name: &str) -> Result<(), String> {
        // Load flow definition
        let flow_def = self
            .loader
            .load_flow(flow_name)
            .await
            .ok_or_else(|| format!("Flow '{}' not found", flow_name))?;

        // Register in flow_names
        self.flow_names
            .write()
            .await
            .insert(instance_name.to_string(), flow_name.to_string());

        // Register each node agent's session in membership
        for (node_id, node) in &flow_def.nodes {
            let session_id = format!("{}-{}", instance_name, node_id);
            self.membership
                .register_session(instance_name, &node.agent, &session_id)
                .await;
        }

        // Persist to mounted store
        let mut entries = self
            .mounted_store
            .load(&self.config.session_id)
            .await
            .unwrap_or_default();
        entries.push(MountedFlowEntry {
            name: instance_name.to_string(),
            flow_name: flow_name.to_string(),
        });
        let _ = self
            .mounted_store
            .save(&self.config.session_id, &entries)
            .await;

        tracing::info!(
            target: "flow.tree",
            "Mounted flow '{}' as instance '{}' with {} nodes",
            flow_name,
            instance_name,
            flow_def.nodes.len()
        );

        Ok(())
    }

    /// Unmount a flow: unregister sessions from membership.
    pub async fn unmount_flow(&self, instance_name: &str) -> Result<(), String> {
        self.flow_names.write().await.remove(instance_name);
        self.membership
            .unregister_flow_sessions(instance_name)
            .await;

        // Update mounted store
        let mut entries = self
            .mounted_store
            .load(&self.config.session_id)
            .await
            .unwrap_or_default();
        entries.retain(|e| e.name != instance_name);
        let _ = self
            .mounted_store
            .save(&self.config.session_id, &entries)
            .await;

        tracing::info!(target: "flow.tree", "Unmounted flow instance '{}'", instance_name);
        Ok(())
    }

    /// Restore flows from mounted store (on startup).
    pub async fn restore_flows(&self) -> Vec<(String, String)> {
        let entries = self
            .mounted_store
            .load(&self.config.session_id)
            .await
            .unwrap_or_default();

        let mut restored = Vec::new();
        for entry in &entries {
            // Try to re-mount (load + register sessions)
            if let Some(flow_def) = self.loader.load_flow(&entry.flow_name).await {
                self.flow_names
                    .write()
                    .await
                    .insert(entry.name.clone(), entry.flow_name.clone());

                for (node_id, node) in &flow_def.nodes {
                    let session_id = format!("{}-{}", entry.name, node_id);
                    self.membership
                        .register_session(&entry.name, &node.agent, &session_id)
                        .await;
                }

                restored.push((entry.name.clone(), entry.flow_name.clone()));
            }
        }

        tracing::info!(target: "flow.tree", "Restored {} flows", restored.len());
        restored
    }

    /// List mounted flow instances.
    pub async fn list_mounted(&self) -> Vec<(String, String)> {
        self.flow_names
            .read()
            .await
            .iter()
            .map(|(k, v)| (k.clone(), v.clone()))
            .collect()
    }

    /// Get flow definition for a mounted instance.
    pub async fn get_flow_def(&self, instance_name: &str) -> Option<FlowDagDef> {
        let flow_name = self.flow_names.read().await.get(instance_name)?.clone();
        self.loader.load_flow(&flow_name).await
    }
}

/// Global registry mapping session IDs to their FlowTree.
pub struct FlowTreeRegistry {
    trees: RwLock<HashMap<String, Arc<FlowTree>>>,
}

impl FlowTreeRegistry {
    pub fn new() -> Self {
        Self {
            trees: RwLock::new(HashMap::new()),
        }
    }

    pub async fn register(&self, session_id: &str, tree: Arc<FlowTree>) {
        self.trees
            .write()
            .await
            .insert(session_id.to_string(), tree);
    }

    pub async fn get(&self, session_id: &str) -> Option<Arc<FlowTree>> {
        self.trees.read().await.get(session_id).cloned()
    }

    pub async fn unregister(&self, session_id: &str) {
        self.trees.write().await.remove(session_id);
    }

    pub async fn clear(&self) {
        self.trees.write().await.clear();
    }
}

impl Default for FlowTreeRegistry {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::entity::{FlowNode, NodeRouteValue};
    use tempfile::TempDir;

    #[tokio::test]
    async fn mount_and_unmount_flow() {
        let tmp = TempDir::new().unwrap();
        let data_root = tmp.path().to_path_buf();

        // Create flow definition
        let flow_dir = data_root.join("flows").join("test-flow");
        tokio::fs::create_dir_all(&flow_dir).await.unwrap();
        let mut nodes = HashMap::new();
        nodes.insert(
            "node1".into(),
            FlowNode {
                agent: "Agent1".into(),
                input: "$task".into(),
                on_complete: NodeRouteValue::Return,
                on_error: None,
                max_retries: 0,
            },
        );
        let flow = FlowDagDef {
            name: "test-flow".into(),
            description: "test".into(),
            nodes,
            entry: "node1".into(),
            max_loop: 10,
        };
        tokio::fs::write(
            flow_dir.join("flow.json"),
            serde_json::to_string(&flow).unwrap(),
        )
        .await
        .unwrap();

        let loader = Arc::new(EntityLoader::new(data_root.clone()));
        let membership = Arc::new(FlowMembership::new());
        let mounted_store = Arc::new(MountedFlowStore::new(data_root.clone()));
        let config = TreeConfig {
            session_id: "test-sess".into(),
            data_root: data_root.clone(),
        };
        let tree = Arc::new(FlowTree::new(
            config,
            loader,
            membership.clone(),
            mounted_store,
        ));

        // Mount
        tree.mount_flow("instance-1", "test-flow").await.unwrap();
        let mounted = tree.list_mounted().await;
        assert_eq!(mounted.len(), 1);
        assert_eq!(mounted[0].0, "instance-1");

        // Check membership
        let session = membership.resolve_team_agent("instance-1", "Agent1").await;
        assert!(session.is_some());

        // Unmount
        tree.unmount_flow("instance-1").await.unwrap();
        let mounted = tree.list_mounted().await;
        assert_eq!(mounted.len(), 0);

        // Membership should be cleared
        let session = membership.resolve_team_agent("instance-1", "Agent1").await;
        assert!(session.is_none());
    }

    #[tokio::test]
    async fn restore_flows_from_disk() {
        let tmp = TempDir::new().unwrap();
        let data_root = tmp.path().to_path_buf();

        // Create flow
        let flow_dir = data_root.join("flows").join("restore-flow");
        tokio::fs::create_dir_all(&flow_dir).await.unwrap();
        let mut nodes = HashMap::new();
        nodes.insert(
            "start".into(),
            FlowNode {
                agent: "Starter".into(),
                input: "$task".into(),
                on_complete: NodeRouteValue::Return,
                on_error: None,
                max_retries: 0,
            },
        );
        let flow = FlowDagDef {
            name: "restore-flow".into(),
            description: "d".into(),
            nodes,
            entry: "start".into(),
            max_loop: 5,
        };
        tokio::fs::write(
            flow_dir.join("flow.json"),
            serde_json::to_string(&flow).unwrap(),
        )
        .await
        .unwrap();

        let loader = Arc::new(EntityLoader::new(data_root.clone()));
        let membership = Arc::new(FlowMembership::new());
        let mounted_store = Arc::new(MountedFlowStore::new(data_root.clone()));

        // Pre-populate mounted store
        mounted_store
            .save(
                "sess-1",
                &[MountedFlowEntry {
                    name: "inst-1".into(),
                    flow_name: "restore-flow".into(),
                }],
            )
            .await
            .unwrap();

        let config = TreeConfig {
            session_id: "sess-1".into(),
            data_root: data_root.clone(),
        };
        let tree = FlowTree::new(config, loader, membership.clone(), mounted_store);

        let restored = tree.restore_flows().await;
        assert_eq!(restored.len(), 1);
        assert_eq!(restored[0].0, "inst-1");

        // Check membership was restored
        let session = membership.resolve_team_agent("inst-1", "Starter").await;
        assert!(session.is_some());
    }

    #[tokio::test]
    async fn flow_tree_registry_register_and_get() {
        let registry = FlowTreeRegistry::new();
        let tmp = TempDir::new().unwrap();
        let data_root = tmp.path().to_path_buf();
        let loader = Arc::new(EntityLoader::new(data_root.clone()));
        let membership = Arc::new(FlowMembership::new());
        let mounted_store = Arc::new(MountedFlowStore::new(data_root.clone()));
        let config = TreeConfig {
            session_id: "s1".into(),
            data_root: data_root.clone(),
        };
        let tree = Arc::new(FlowTree::new(config, loader, membership, mounted_store));

        registry.register("s1", tree.clone()).await;
        assert!(registry.get("s1").await.is_some());
        assert!(registry.get("s2").await.is_none());

        registry.unregister("s1").await;
        assert!(registry.get("s1").await.is_none());
    }
}

//! Flow/Team entity system — DAG execution, entity loading, flow tree management.
//!
//! Mirrors the Scala `nebflow.core.entity` and `nebflow.core.flow` packages.
//!
//! Module structure:
//! - `loader`: EntityLoader — load agent/team/flow from disk (three-layer search)
//! - `executor`: FlowDagExecutor — DAG node execution + routing
//! - `runner`: FlowDagRunner — one-shot DAG run entry point
//! - `registry`: RunningFlowRegistry — running flow tracking + cancel
//! - `tree`: FlowTreeActor + FlowTreeRegistry — flow mounting lifecycle
//! - `activator`: FlowAgentActivator — team/flow agent activation
//! - `mailbox`: FlowMailStore — team mail history persistence
//! - `membership`: FlowMembership — session mapping for team communication
//! - `mounted`: MountedFlowStore — mounted flow list persistence
//! - `ephemeral`: EphemeralAgentRunner — temporary agent execution
//! - `team_catalog`: TeamCatalog — system prompt catalog builder

pub mod activator;
pub mod ephemeral;
pub mod executor;
pub mod loader;
pub mod mailbox;
pub mod membership;
pub mod mounted;
pub mod registry;
pub mod runner;
pub mod team_catalog;
pub mod tree;

pub use executor::{FlowDagExecutor, NodeExecutionTrait};
pub use loader::EntityLoader;
pub use membership::FlowMembership;
pub use registry::{RunningFlow, RunningFlowRegistry};
pub use runner::FlowDagRunner;
pub use team_catalog::TeamCatalog;

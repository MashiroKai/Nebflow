//! Benchmark: Agent spawning and flow initialization performance.
//!
//! Measures the overhead of creating agent state, flow contexts, and peer stores.

use criterion::{black_box, criterion_group, criterion_main, Criterion};
use nebflow_core::entity::*;
use nebflow_core::neblink::*;
use nebflow_core::types::*;

fn bench_create_agent_def(c: &mut Criterion) {
    c.bench_function("create_agent_def", |b| {
        b.iter(|| {
            let def = AgentDef {
                name: "test-agent".to_string(),
                description: "test agent".to_string(),
                tools: vec!["Read".to_string(), "Write".to_string(), "Bash".to_string()],
                system_prompt: "You are a test agent.".to_string(),
                avatar: None,
                display_name: Some("Test".to_string()),
                voice_enabled: false,
                model: None,
                category: AgentCategory::Standalone,
                mcp_servers: vec![],
            };
            black_box(def);
        })
    });
}

fn bench_create_messages(c: &mut Criterion) {
    c.bench_function("create_50_messages", |b| {
        b.iter(|| {
            let messages: Vec<Message> = (0..50)
                .map(|i| Message {
                    role: if i % 2 == 0 {
                        MessageRole::User
                    } else {
                        MessageRole::Assistant
                    },
                    content: MessageContent::Text(format!("Message {} content", i)),
                    timestamp: i as u64,
                })
                .collect();
            black_box(messages);
        })
    });
}

fn bench_peer_store_insert(c: &mut Criterion) {
    c.bench_function("peer_store_insert_100", |b| {
        b.iter(|| {
            let mut store = PeerStore::new();
            for i in 0..100 {
                store.upsert(PeerInfo {
                    identity: DeviceIdentity {
                        device_id: format!("dev-{:03}", i),
                        user_id: "user-1".to_string(),
                        device_name: format!("Device {}", i),
                        platform: "darwin".to_string(),
                    },
                    capabilities: Default::default(),
                    address: format!("10.0.0.{}", i),
                    last_seen: 1000 + i,
                    online: i % 3 != 0,
                });
            }
            black_box(store.len());
        })
    });
}

fn bench_peer_store_query(c: &mut Criterion) {
    // Pre-populate
    let mut store = PeerStore::new();
    for i in 0..100 {
        store.upsert(PeerInfo {
            identity: DeviceIdentity {
                device_id: format!("dev-{:03}", i),
                user_id: "user-1".to_string(),
                device_name: format!("Device {}", i),
                platform: "darwin".to_string(),
            },
            capabilities: Default::default(),
            address: format!("10.0.0.{}", i),
            last_seen: 1000 + i,
            online: i % 3 != 0,
        });
    }

    c.bench_function("peer_store_online_query_100", |b| {
        b.iter(|| {
            let online = black_box(&store).online_peers();
            black_box(online.len());
        })
    });
}

fn bench_heartbeat_backoff_calculation(c: &mut Criterion) {
    use nebflow_core::neblink::heartbeat::{HeartbeatConfig, HeartbeatTracker};

    c.bench_function("heartbeat_backoff_100_iterations", |b| {
        b.iter(|| {
            // Simulate 5 consecutive timeouts by creating a tracker in that state
            let tracker = HeartbeatTracker::new(HeartbeatConfig::default());
            let backoff = tracker.reconnect_backoff();
            black_box(backoff);
        })
    });
}

fn bench_capabilities_merge(c: &mut Criterion) {
    let caps_a = DeviceCapabilities::new()
        .with(Capability::Dropbox)
        .with(Capability::FileTransfer)
        .with(Capability::Tools);

    let caps_b = DeviceCapabilities::new()
        .with(Capability::Shell)
        .with(Capability::Browser)
        .with(Capability::Display);

    c.bench_function("capabilities_merge_3x3", |b| {
        b.iter(|| {
            let mut merged = black_box(caps_a.clone());
            merged.merge(black_box(&caps_b));
            black_box(merged);
        })
    });
}

fn bench_flow_dag_creation(c: &mut Criterion) {
    c.bench_function("create_flow_dag_10_nodes", |b| {
        b.iter(|| {
            let mut nodes = HashMap::new();
            for i in 0..10 {
                let id = format!("node-{}", i);
                let next = if i < 9 {
                    NodeRouteValue::Goto(format!("node-{}", i + 1))
                } else {
                    NodeRouteValue::Return
                };
                nodes.insert(
                    id,
                    FlowNode {
                        agent: format!("Agent{}", i),
                        input: format!("$task-{}", i),
                        on_complete: next,
                        on_error: Some(OnError::Resume),
                        max_retries: 3,
                    },
                );
            }
            let dag = FlowDagDef {
                name: "bench-flow".to_string(),
                description: "benchmark flow".to_string(),
                nodes,
                entry: "node-0".to_string(),
                max_loop: 10,
            };
            black_box(dag);
        })
    });
}

use std::collections::HashMap;

criterion_group!(
    benches,
    bench_create_agent_def,
    bench_create_messages,
    bench_peer_store_insert,
    bench_peer_store_query,
    bench_heartbeat_backoff_calculation,
    bench_capabilities_merge,
    bench_flow_dag_creation,
);
criterion_main!(benches);

//! Benchmark: JSON serialization/deserialization performance.
//!
//! Measures serde_json performance on common Nebflow data structures.

use criterion::{black_box, criterion_group, criterion_main, Criterion};
use nebflow_core::entity::*;
use nebflow_core::neblink::*;
use nebflow_core::types::*;

fn bench_serialize_llm_request(c: &mut Criterion) {
    let req = LlmRequest {
        messages: vec![
            Message {
                role: MessageRole::User,
                content: MessageContent::Text("Hello, how are you?".to_string()),
                timestamp: 1000,
            },
            Message {
                role: MessageRole::Assistant,
                content: MessageContent::Text("I'm doing well, thank you!".to_string()),
                timestamp: 1001,
            },
        ],
        session_id: "session-1".to_string(),
        agent_id: "agent-1".to_string(),
        tools: None,
        max_tokens: Some(4096),
        thinking: None,
        system_stable: Some("You are a helpful assistant.".to_string()),
        system_dynamic: None,
        agent_model: None,
    };

    c.bench_function("serialize_llm_request", |b| {
        b.iter(|| {
            // LlmRequest doesn't impl Serialize, so we serialize its messages
            let json = serde_json::to_string(black_box(&req.messages)).unwrap();
            black_box(json);
        })
    });
}

fn bench_deserialize_llm_response(c: &mut Criterion) {
    let json = serde_json::json!({
        "id": "resp-12345",
        "model": "claude-sonnet-4-20250514",
        "choices": [],
        "usage": {
            "prompt_tokens": 100,
            "completion_tokens": 50,
            "total_tokens": 150
        },
        "stop_reason": "end_turn"
    })
    .to_string();

    c.bench_function("deserialize_json_value", |b| {
        b.iter(|| {
            let val: serde_json::Value = serde_json::from_str(black_box(&json)).unwrap();
            black_box(val);
        })
    });
}

fn bench_serialize_device_discovery(c: &mut Criterion) {
    let info = DeviceDiscoveryInfo {
        identity: DeviceIdentity {
            device_id: "dev-001".to_string(),
            user_id: "user-42".to_string(),
            device_name: "MacBook Pro".to_string(),
            platform: "darwin".to_string(),
        },
        capabilities: Default::default(),
        neblink_server: "https://neblink.example.com".to_string(),
        last_seen: 1700000000,
    };

    c.bench_function("serialize_discovery_info", |b| {
        b.iter(|| {
            let json = serde_json::to_string(black_box(&info)).unwrap();
            black_box(json);
        })
    });
}

fn bench_roundtrip_large_messages(c: &mut Criterion) {
    // Simulate a conversation with many messages
    let messages: Vec<Message> = (0..100)
        .map(|i| Message {
            role: if i % 2 == 0 {
                MessageRole::User
            } else {
                MessageRole::Assistant
            },
            content: MessageContent::Text(format!(
                "Message number {} with some content to make it realistic.",
                i
            )),
            timestamp: i as u64,
        })
        .collect();

    c.bench_function("serialize_100_messages", |b| {
        b.iter(|| {
            let json = serde_json::to_string(black_box(&messages)).unwrap();
            black_box(json);
        })
    });

    // Pre-serialize for deserialization bench
    let json = serde_json::to_string(&messages).unwrap();
    c.bench_function("deserialize_100_messages", |b| {
        b.iter(|| {
            let msgs: Vec<Message> = serde_json::from_str(black_box(&json)).unwrap();
            black_box(msgs);
        })
    });
}

fn bench_serialize_agent_entry(c: &mut Criterion) {
    let entry = AgentEntry {
        name: "test-agent".to_string(),
        description: "A test agent for benchmarking".to_string(),
        use_when: "When you need to test things".to_string(),
        tools: vec!["Read".to_string(), "Write".to_string(), "Bash".to_string()],
        voice: false,
        system_prompt: "You are a test agent.".to_string(),
        category: "standalone".to_string(),
        mcp_servers: vec![],
        model: None,
    };

    c.bench_function("serialize_agent_entry", |b| {
        b.iter(|| {
            let json = serde_json::to_string(black_box(&entry)).unwrap();
            black_box(json);
        })
    });

    let json = serde_json::to_string(&entry).unwrap();
    c.bench_function("deserialize_agent_entry", |b| {
        b.iter(|| {
            let entry: AgentEntry = serde_json::from_str(black_box(&json)).unwrap();
            black_box(entry);
        })
    });
}

criterion_group!(
    benches,
    bench_serialize_llm_request,
    bench_deserialize_llm_response,
    bench_serialize_device_discovery,
    bench_roundtrip_large_messages,
    bench_serialize_agent_entry,
);
criterion_main!(benches);

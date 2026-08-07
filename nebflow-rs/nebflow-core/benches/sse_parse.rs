//! Benchmark: SSE (Server-Sent Events) stream parsing performance.
//!
//! Measures how fast we can parse SSE-formatted chunks from an LLM streaming response.

use criterion::{black_box, criterion_group, criterion_main, Criterion};

/// Parse SSE-formatted text into individual event data lines.
///
/// SSE format:
/// ```text
/// data: {"chunk": 1}
///
/// data: {"chunk": 2}
///
/// ```
fn parse_sse_lines(input: &str) -> Vec<&str> {
    input
        .lines()
        .filter(|line| line.starts_with("data: "))
        .map(|line| &line[6..]) // skip "data: "
        .collect()
}

/// Parse SSE events, handling multi-line data fields.
fn parse_sse_events(input: &str) -> Vec<String> {
    let mut events = Vec::new();
    let mut current_data = String::new();

    for line in input.lines() {
        if line.is_empty() {
            // Event boundary
            if !current_data.is_empty() {
                events.push(std::mem::take(&mut current_data));
            }
        } else if let Some(rest) = line.strip_prefix("data: ") {
            if !current_data.is_empty() {
                current_data.push('\n');
            }
            current_data.push_str(rest);
        }
    }

    // Don't forget trailing data
    if !current_data.is_empty() {
        events.push(current_data);
    }

    events
}

fn bench_parse_sse_simple(c: &mut Criterion) {
    let sse_text = (0..50)
        .map(|i| format!("data: {{\"text\": \"chunk {}\", \"index\": {}}}\n\n", i, i))
        .collect::<String>();

    c.bench_function("parse_sse_lines_50_events", |b| {
        b.iter(|| {
            let lines = parse_sse_lines(black_box(&sse_text));
            black_box(lines);
        })
    });
}

fn bench_parse_sse_events(c: &mut Criterion) {
    let sse_text = (0..100)
        .map(|i| format!("data: {{\"text\": \"chunk {}\", \"index\": {}}}\n\n", i, i))
        .collect::<String>();

    c.bench_function("parse_sse_events_100", |b| {
        b.iter(|| {
            let events = parse_sse_events(black_box(&sse_text));
            black_box(events);
        })
    });
}

fn bench_parse_sse_large_payload(c: &mut Criterion) {
    // Simulate a large streaming response with big chunks
    let chunk = "x".repeat(500);
    let sse_text = (0..20)
        .map(|i| format!("data: {{\"text\": \"{}\", \"index\": {}}}\n\n", chunk, i))
        .collect::<String>();

    c.bench_function("parse_sse_large_20x500chars", |b| {
        b.iter(|| {
            let events = parse_sse_events(black_box(&sse_text));
            black_box(events);
        })
    });
}

fn bench_json_parse_sse_data(c: &mut Criterion) {
    let sse_text = (0..50)
        .map(|i| format!("data: {{\"text\": \"chunk {}\", \"index\": {}}}\n\n", i, i))
        .collect::<String>();

    c.bench_function("parse_sse_and_json_50", |b| {
        b.iter(|| {
            for line in sse_text.lines() {
                if let Some(rest) = line.strip_prefix("data: ") {
                    if let Ok(val) = serde_json::from_str::<serde_json::Value>(rest) {
                        black_box(&val);
                    }
                }
            }
        })
    });
}

criterion_group!(
    benches,
    bench_parse_sse_simple,
    bench_parse_sse_events,
    bench_parse_sse_large_payload,
    bench_json_parse_sse_data,
);
criterion_main!(benches);

//! Rate limiter — mirrors Scala ratelimit.scala.
//!
//! Sliding window rate limiter. Tracks timestamps per key (e.g. IP address
//! or "bridge" for bridge messages). Allows up to `max_requests` within
//! `window_ms` milliseconds.

use std::collections::HashMap;
use std::time::{Duration, Instant};
use tokio::sync::Mutex;

/// Rate limiter with a sliding window per key.
pub struct RateLimiter {
    max_requests: usize,
    window: Duration,
    timestamps: Mutex<HashMap<String, Vec<Instant>>>,
}

impl RateLimiter {
    /// Create a rate limiter with default settings (60 requests / 60 seconds).
    pub fn new() -> Self {
        Self::with_limits(60, Duration::from_millis(60_000))
    }

    /// Create a rate limiter with custom limits.
    pub fn with_limits(max_requests: usize, window: Duration) -> Self {
        Self {
            max_requests,
            window,
            timestamps: Mutex::new(HashMap::new()),
        }
    }

    /// Check if a request for the given key is allowed.
    /// Returns `true` if allowed, `false` if rate limited.
    pub async fn check(&self, key: &str) -> bool {
        let now = Instant::now();
        let mut timestamps = self.timestamps.lock().await;
        let entries = timestamps.entry(key.to_string()).or_default();

        // Remove timestamps outside the window
        entries.retain(|t| now.duration_since(*t) < self.window);

        if entries.len() < self.max_requests {
            entries.push(now);
            true
        } else {
            false
        }
    }

    /// Get the current count for a key (for diagnostics).
    pub async fn count(&self, key: &str) -> usize {
        let timestamps = self.timestamps.lock().await;
        timestamps.get(key).map(|v| v.len()).unwrap_or(0)
    }
}

impl Default for RateLimiter {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn allows_under_limit() {
        let rl = RateLimiter::with_limits(5, Duration::from_secs(60));
        for _ in 0..5 {
            assert!(rl.check("user1").await);
        }
        // 6th request should be denied
        assert!(!rl.check("user1").await);
    }

    #[tokio::test]
    async fn different_keys_independent() {
        let rl = RateLimiter::with_limits(2, Duration::from_secs(60));
        assert!(rl.check("a").await);
        assert!(rl.check("a").await);
        assert!(!rl.check("a").await);
        // "b" still has its full allowance
        assert!(rl.check("b").await);
    }

    #[tokio::test]
    async fn window_expiry_allows_again() {
        let rl = RateLimiter::with_limits(1, Duration::from_millis(50));
        assert!(rl.check("k").await);
        assert!(!rl.check("k").await);
        // Wait for window to expire
        tokio::time::sleep(Duration::from_millis(60)).await;
        assert!(rl.check("k").await);
    }

    #[tokio::test]
    async fn count_returns_current() {
        let rl = RateLimiter::with_limits(10, Duration::from_secs(60));
        rl.check("x").await;
        rl.check("x").await;
        rl.check("x").await;
        assert_eq!(rl.count("x").await, 3);
    }

    #[tokio::test]
    async fn default_is_60_per_minute() {
        let rl = RateLimiter::default();
        for _ in 0..60 {
            assert!(rl.check("default").await);
        }
        assert!(!rl.check("default").await);
    }

    #[tokio::test]
    async fn zero_key_uses_empty_string() {
        let rl = RateLimiter::with_limits(3, Duration::from_secs(60));
        // Empty key should work
        assert!(rl.check("").await);
        assert!(rl.check("").await);
        assert!(rl.check("").await);
        assert!(!rl.check("").await);
    }
}

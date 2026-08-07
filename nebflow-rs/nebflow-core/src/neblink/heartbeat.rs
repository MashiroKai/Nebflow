//! Heartbeat state machine for presence tracking.
//!
//! Scala: heartbeat logic within `NelinkPresenceService`.

use std::time::{Duration, Instant};
use tokio::time::interval;
use tracing::warn;

/// Configuration for heartbeat timing.
///
/// Scala defaults: ping every 5s, timeout at 10s.
#[derive(Debug, Clone)]
pub struct HeartbeatConfig {
    /// Interval between heartbeats (Scala default: 5s).
    pub interval: Duration,
    /// If no response within this duration, peer is considered offline (Scala default: 10s).
    pub timeout: Duration,
    /// Maximum backoff before reconnect attempt (Scala default: 30s).
    pub max_backoff: Duration,
}

impl Default for HeartbeatConfig {
    fn default() -> Self {
        Self {
            interval: Duration::from_secs(5),
            timeout: Duration::from_secs(10),
            max_backoff: Duration::from_secs(30),
        }
    }
}

/// State of the heartbeat for a single connection.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum HeartbeatState {
    /// Waiting for the next heartbeat interval.
    Idle,
    /// Heartbeat sent, waiting for response.
    Pending,
    /// Heartbeat response received, connection healthy.
    Healthy,
    /// Timed out waiting for response, peer considered offline.
    TimedOut,
}

/// Tracks heartbeat state for a single peer connection.
pub struct HeartbeatTracker {
    config: HeartbeatConfig,
    state: HeartbeatState,
    last_pong: Instant,
    consecutive_timeouts: u32,
}

impl HeartbeatTracker {
    pub fn new(config: HeartbeatConfig) -> Self {
        Self {
            config,
            state: HeartbeatState::Idle,
            last_pong: Instant::now(),
            consecutive_timeouts: 0,
        }
    }

    /// Record that a heartbeat (ping) was sent.
    pub fn on_ping_sent(&mut self) {
        self.state = HeartbeatState::Pending;
    }

    /// Record that a heartbeat response (pong) was received.
    pub fn on_pong(&mut self) {
        self.state = HeartbeatState::Healthy;
        self.last_pong = Instant::now();
        self.consecutive_timeouts = 0;
    }

    /// Check if the connection has timed out (no pong within timeout duration).
    /// Returns true if state transitioned to TimedOut.
    pub fn check_timeout(&mut self) -> bool {
        if self.state == HeartbeatState::Pending {
            let elapsed = self.last_pong.elapsed();
            if elapsed > self.config.timeout {
                self.state = HeartbeatState::TimedOut;
                self.consecutive_timeouts += 1;
                warn!(
                    target: "neblink::heartbeat",
                    timeouts = self.consecutive_timeouts,
                    elapsed_ms = elapsed.as_millis(),
                    "heartbeat timed out"
                );
                return true;
            }
        }
        false
    }

    /// Reset to idle state (e.g., after a successful reconnect).
    pub fn reset(&mut self) {
        self.state = HeartbeatState::Idle;
        self.last_pong = Instant::now();
        self.consecutive_timeouts = 0;
    }

    /// Get the current heartbeat state.
    pub fn state(&self) -> &HeartbeatState {
        &self.state
    }

    /// Get the number of consecutive timeouts.
    pub fn consecutive_timeouts(&self) -> u32 {
        self.consecutive_timeouts
    }

    /// Is the connection currently considered alive?
    pub fn is_alive(&self) -> bool {
        !matches!(self.state, HeartbeatState::TimedOut)
    }

    /// Calculate the reconnect backoff duration based on consecutive timeouts.
    ///
    /// Scala: exponential backoff 0s → 1s → 2s → 4s → ... → max_backoff
    pub fn reconnect_backoff(&self) -> Duration {
        if self.consecutive_timeouts == 0 {
            return Duration::ZERO;
        }
        let base_secs: u64 = 1u64
            .checked_shl(self.consecutive_timeouts.saturating_sub(1))
            .unwrap_or(u64::MAX);
        let backoff = Duration::from_secs(base_secs);
        backoff.min(self.config.max_backoff)
    }

    /// Get the heartbeat interval.
    pub fn interval(&self) -> Duration {
        self.config.interval
    }
}

/// Asynchronous heartbeat loop helper.
///
/// This is NOT a full event loop — it just provides the timing mechanism.
/// The actual ping/pong message passing is handled by the presence service.
pub struct HeartbeatLoop {
    config: HeartbeatConfig,
}

impl HeartbeatLoop {
    pub fn new(config: HeartbeatConfig) -> Self {
        Self { config }
    }

    /// Create a tokio interval timer for heartbeats.
    pub fn timer(&self) -> tokio::time::Interval {
        interval(self.config.interval)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn heartbeat_initial_state() {
        let tracker = HeartbeatTracker::new(HeartbeatConfig::default());
        assert_eq!(*tracker.state(), HeartbeatState::Idle);
        assert!(tracker.is_alive());
        assert_eq!(tracker.consecutive_timeouts(), 0);
    }

    #[test]
    fn heartbeat_ping_pong_cycle() {
        let mut tracker = HeartbeatTracker::new(HeartbeatConfig::default());
        tracker.on_ping_sent();
        assert_eq!(*tracker.state(), HeartbeatState::Pending);
        tracker.on_pong();
        assert_eq!(*tracker.state(), HeartbeatState::Healthy);
        assert_eq!(tracker.consecutive_timeouts(), 0);
    }

    #[test]
    fn heartbeat_reconnect_backoff() {
        let mut tracker = HeartbeatTracker::new(HeartbeatConfig::default());

        // 0 timeouts → 0s
        assert_eq!(tracker.reconnect_backoff(), Duration::ZERO);

        // 1 timeout → 1s
        tracker.consecutive_timeouts = 1;
        assert_eq!(tracker.reconnect_backoff(), Duration::from_secs(1));

        // 2 timeouts → 2s
        tracker.consecutive_timeouts = 2;
        assert_eq!(tracker.reconnect_backoff(), Duration::from_secs(2));

        // 3 timeouts → 4s
        tracker.consecutive_timeouts = 3;
        assert_eq!(tracker.reconnect_backoff(), Duration::from_secs(4));

        // 10 timeouts → capped at max_backoff (30s)
        tracker.consecutive_timeouts = 10;
        assert_eq!(tracker.reconnect_backoff(), Duration::from_secs(30));
    }

    #[test]
    fn heartbeat_reset() {
        let mut tracker = HeartbeatTracker::new(HeartbeatConfig::default());
        tracker.on_ping_sent();
        tracker.consecutive_timeouts = 5;
        tracker.reset();
        assert_eq!(*tracker.state(), HeartbeatState::Idle);
        assert_eq!(tracker.consecutive_timeouts(), 0);
    }

    #[test]
    fn heartbeat_config_default() {
        let cfg = HeartbeatConfig::default();
        assert_eq!(cfg.interval, Duration::from_secs(5));
        assert_eq!(cfg.timeout, Duration::from_secs(10));
        assert_eq!(cfg.max_backoff, Duration::from_secs(30));
    }

    #[test]
    fn heartbeat_pong_resets_timeouts() {
        let mut tracker = HeartbeatTracker::new(HeartbeatConfig::default());
        tracker.consecutive_timeouts = 3;
        tracker.on_pong();
        assert_eq!(tracker.consecutive_timeouts(), 0);
    }
}

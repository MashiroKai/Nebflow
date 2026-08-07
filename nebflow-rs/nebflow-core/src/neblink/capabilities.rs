//! Device capability advertisement.
//!
//! Scala: `nebflow.neblink.DeviceCapabilities`

use serde::{Deserialize, Serialize};
use std::collections::HashSet;

/// A single capability a device can advertise.
///
/// Scala: capability flags on DeviceCapabilities.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum Capability {
    /// Can send/receive Dropbox messages.
    Dropbox,
    /// Can transfer files.
    FileTransfer,
    /// Can run tools.
    Tools,
    /// Can execute flows.
    Flow,
    /// Has a display (can show cards / pop).
    Display,
    /// Can take screenshots.
    Screenshot,
    /// Can run shell commands.
    Shell,
    /// Has a browser / web fetch.
    Browser,
}

impl Capability {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Dropbox => "dropbox",
            Self::FileTransfer => "file_transfer",
            Self::Tools => "tools",
            Self::Flow => "flow",
            Self::Display => "display",
            Self::Screenshot => "screenshot",
            Self::Shell => "shell",
            Self::Browser => "browser",
        }
    }
}

/// The set of capabilities a device advertises to peers.
///
/// Scala: `nebflow.neblink.DeviceCapabilities`
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct DeviceCapabilities {
    #[serde(default)]
    pub capabilities: HashSet<String>,
    #[serde(default)]
    pub max_file_size: Option<u64>,
    #[serde(default)]
    pub supported_schemas: Vec<String>,
}

impl DeviceCapabilities {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn with(mut self, cap: Capability) -> Self {
        self.capabilities.insert(cap.as_str().to_string());
        self
    }

    pub fn has(&self, cap: Capability) -> bool {
        self.capabilities.contains(cap.as_str())
    }

    pub fn add(&mut self, cap: Capability) {
        self.capabilities.insert(cap.as_str().to_string());
    }

    pub fn remove(&mut self, cap: Capability) {
        self.capabilities.remove(cap.as_str());
    }

    pub fn is_empty(&self) -> bool {
        self.capabilities.is_empty()
    }

    pub fn len(&self) -> usize {
        self.capabilities.len()
    }

    /// Merge another capability set into this one (union).
    pub fn merge(&mut self, other: &Self) {
        self.capabilities.extend(other.capabilities.iter().cloned());
        if other.max_file_size.is_some() {
            self.max_file_size = other.max_file_size;
        }
        for s in &other.supported_schemas {
            if !self.supported_schemas.contains(s) {
                self.supported_schemas.push(s.clone());
            }
        }
    }

    /// Check if this device can satisfy all required capabilities.
    pub fn satisfies(&self, required: &[Capability]) -> bool {
        required.iter().all(|c| self.has(*c))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn capability_as_str() {
        assert_eq!(Capability::Dropbox.as_str(), "dropbox");
        assert_eq!(Capability::Shell.as_str(), "shell");
    }

    #[test]
    fn device_capabilities_builder() {
        let caps = DeviceCapabilities::new()
            .with(Capability::Dropbox)
            .with(Capability::FileTransfer);
        assert!(caps.has(Capability::Dropbox));
        assert!(caps.has(Capability::FileTransfer));
        assert!(!caps.has(Capability::Shell));
        assert_eq!(caps.len(), 2);
    }

    #[test]
    fn device_capabilities_add_remove() {
        let mut caps = DeviceCapabilities::new();
        caps.add(Capability::Tools);
        assert!(caps.has(Capability::Tools));
        caps.remove(Capability::Tools);
        assert!(!caps.has(Capability::Tools));
    }

    #[test]
    fn device_capabilities_merge() {
        let mut a = DeviceCapabilities::new().with(Capability::Dropbox);
        let b = DeviceCapabilities::new().with(Capability::Shell);
        a.merge(&b);
        assert!(a.has(Capability::Dropbox));
        assert!(a.has(Capability::Shell));
    }

    #[test]
    fn device_capabilities_satisfies() {
        let caps = DeviceCapabilities::new()
            .with(Capability::Dropbox)
            .with(Capability::FileTransfer);
        assert!(caps.satisfies(&[Capability::Dropbox]));
        assert!(caps.satisfies(&[Capability::Dropbox, Capability::FileTransfer]));
        assert!(!caps.satisfies(&[Capability::Shell]));
    }
}

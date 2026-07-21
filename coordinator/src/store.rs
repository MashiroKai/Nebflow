use crate::model::*;
use base64::Engine;
use dashmap::DashMap;
use sha2::{Digest, Sha256};
use std::time::{Duration, Instant};

pub struct Store {
    networks: DashMap<String, Network>,
    devices: DashMap<String, RegisteredDevice>, // sessionToken -> device
}

impl Store {
    pub fn new() -> Self {
        Self {
            networks: DashMap::new(),
            devices: DashMap::new(),
        }
    }

    // ===== Network operations =====

    pub fn create_network(&self, name: &str) -> CreateNetworkResponse {
        let network_id = uuid::Uuid::new_v4().to_string();
        let secret = generate_secret();
        let secret_hash = sha256_hex(&secret);

        self.networks.insert(
            network_id.clone(),
            Network {
                network_id: network_id.clone(),
                name: name.to_string(),
                secret_hash,
            },
        );

        tracing::info!("Created network: {} (name={})", network_id, name);
        CreateNetworkResponse { network_id, secret }
    }

    // ===== Device operations =====

    pub fn login(&self, req: LoginRequest) -> Result<LoginResponse, String> {
        // Verify network + secret
        let network = self
            .networks
            .get(&req.network_id)
            .ok_or_else(|| format!("Network not found: {}", req.network_id))?;

        let provided_hash = sha256_hex(&req.secret);
        if !constant_time_eq(provided_hash.as_bytes(), network.secret_hash.as_bytes()) {
            return Err("Invalid secret".to_string());
        }
        drop(network);

        // Create session
        let token = generate_token();
        let device = RegisteredDevice {
            device_id: req.device_id.clone(),
            device_name: req.device_name.clone(),
            platform: req.platform.clone(),
            network_id: req.network_id.clone(),
            endpoints: req.endpoints.clone(),
            session_token: token.clone(),
            last_seen: Instant::now(),
        };

        self.devices.insert(token.clone(), device);

        let peers = self.get_peers(&req.network_id, &req.device_id);
        tracing::info!("Device logged in: {} ({})", req.device_id, req.device_name);

        Ok(LoginResponse {
            token,
            network_id: req.network_id,
            device_id: req.device_id,
            peers,
        })
    }

    pub fn heartbeat(&self, token: &str) -> Result<HeartbeatResponse, String> {
        let device = self.devices.get(token).ok_or("Invalid or expired token")?;
        let network_id = device.network_id.clone();
        let device_id = device.device_id.clone();
        drop(device);

        // Update last_seen
        if let Some(mut device) = self.devices.get_mut(token) {
            device.last_seen = Instant::now();
        }

        let peers = self.get_peers(&network_id, &device_id);
        Ok(HeartbeatResponse { peers })
    }

    pub fn get_peer_list(&self, token: &str) -> Result<Vec<PeerInfo>, String> {
        let device = self.devices.get(token).ok_or("Invalid or expired token")?;
        let peers = self.get_peers(&device.network_id, &device.device_id);
        Ok(peers)
    }

    pub fn update_endpoints(&self, token: &str, endpoints: Vec<DeviceEndpoint>) -> Result<(), String> {
        let mut device = self.devices.get_mut(token).ok_or("Invalid or expired token")?;
        device.endpoints = endpoints;
        device.last_seen = Instant::now();
        Ok(())
    }

    pub fn logout(&self, token: &str) {
        if let Some((_, device)) = self.devices.remove(token) {
            tracing::info!("Device logged out: {} (token={}...)", device.device_id, &token[..8.min(token.len())]);
        }
    }

    fn get_peers(&self, network_id: &str, exclude_device_id: &str) -> Vec<PeerInfo> {
        self.devices
            .iter()
            .filter(|d| d.network_id == network_id && d.device_id != exclude_device_id)
            .map(|d| d.to_peer_info())
            .collect()
    }

    pub fn purge_stale(&self, timeout: Duration) -> Vec<String> {
        let now = Instant::now();
        let mut removed = Vec::new();

        self.devices.retain(|_, device| {
            if now.duration_since(device.last_seen) > timeout {
                removed.push(device.device_name.clone());
                false
            } else {
                true
            }
        });

        removed
    }
}

// ===== Crypto helpers =====

fn generate_secret() -> String {
    use rand::RngCore;
    let mut bytes = [0u8; 24];
    rand::thread_rng().fill_bytes(&mut bytes);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

fn generate_token() -> String {
    use rand::RngCore;
    let mut bytes = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut bytes);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

fn sha256_hex(input: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(input.as_bytes());
    let result = hasher.finalize();
    result.iter().map(|b| format!("{:02x}", b)).collect()
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

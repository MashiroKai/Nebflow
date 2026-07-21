use serde::{Deserialize, Serialize};

// ===== Public API models =====

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceEndpoint {
    pub address: String,
    pub port: i32,
    pub kind: String,
    #[serde(default)]
    pub label: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PeerInfo {
    pub device_id: String,
    pub device_name: String,
    pub platform: String,
    pub endpoints: Vec<DeviceEndpoint>,
    pub online: bool,
}

// ===== Request / Response types =====

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateNetworkRequest {
    pub name: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateNetworkResponse {
    pub network_id: String,
    pub secret: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LoginRequest {
    pub network_id: String,
    pub secret: String,
    pub device_id: String,
    pub device_name: String,
    pub platform: String,
    pub endpoints: Vec<DeviceEndpoint>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LoginResponse {
    pub token: String,
    pub network_id: String,
    pub device_id: String,
    pub peers: Vec<PeerInfo>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HeartbeatResponse {
    pub peers: Vec<PeerInfo>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateEndpointsRequest {
    pub endpoints: Vec<DeviceEndpoint>,
}

#[derive(Serialize)]
pub struct ErrorResponse {
    pub error: String,
}

impl ErrorResponse {
    pub fn new(msg: impl Into<String>) -> Self {
        Self { error: msg.into() }
    }
}

// ===== Internal models =====

#[derive(Clone)]
pub struct Network {
    pub network_id: String,
    pub name: String,
    pub secret_hash: String,
}

#[derive(Clone)]
pub struct RegisteredDevice {
    pub device_id: String,
    pub device_name: String,
    pub platform: String,
    pub network_id: String,
    pub endpoints: Vec<DeviceEndpoint>,
    pub session_token: String,
    pub last_seen: std::time::Instant,
}

impl RegisteredDevice {
    pub fn to_peer_info(&self) -> PeerInfo {
        PeerInfo {
            device_id: self.device_id.clone(),
            device_name: self.device_name.clone(),
            platform: self.platform.clone(),
            endpoints: self.endpoints.clone(),
            online: true,
        }
    }
}

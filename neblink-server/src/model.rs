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

// ===== User types =====

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UserInfo {
    pub user_id: String,
    pub email: String,
}

// ===== Network management types =====

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NetworkInfo {
    pub network_id: String,
    pub name: String,
    pub online_devices: usize,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceInfo {
    pub device_id: String,
    pub device_name: String,
    pub platform: String,
    pub online: bool,
    pub last_seen: i64,
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

// ===== Pairing / enrollment =====

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairCodeResponse {
    pub pair_code: String,
    pub expires_in_secs: u32,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EnrollRequest {
    pub pair_code: String,
    pub device_id: String,
    pub device_name: String,
    pub platform: String,
    // Endpoints aren't used at enroll time (they're sent on the first
    // /api/device/session call) but are accepted for forward compatibility.
    #[serde(default)]
    #[allow(dead_code)]
    pub endpoints: Vec<DeviceEndpoint>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EnrollResponse {
    /// Long-lived per-device secret. Persisted client-side; verifies sessions.
    pub device_token: String,
    pub network_id: String,
    pub device_id: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceSessionRequest {
    pub network_id: String,
    pub device_id: String,
    /// Per-device credential from enroll (or legacy: network secret).
    pub device_token: String,
    #[serde(default)]
    pub endpoints: Vec<DeviceEndpoint>,
}

// ===== Device authorization flow (RFC 8628 / Tailscale-style) =====

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceCodeRequest {
    pub device_id: String,
    pub device_name: String,
    pub platform: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceCodeResponse {
    pub device_code: String,
    pub user_code: String,
    /// URL the user opens in a browser to authorize the device.
    pub verification_uri: String,
    pub expires_in: u64,
    /// Polling interval in seconds.
    pub interval: u64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceTokenRequest {
    pub device_code: String,
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
pub struct RegisteredDevice {
    pub device_id: String,
    pub device_name: String,
    pub platform: String,
    pub network_id: String,
    /// Owner user id — for "account = network" peer discovery. Populated from
    /// the network's `owner_id` at session creation.
    pub user_id: String,
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

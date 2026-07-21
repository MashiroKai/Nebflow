use crate::auth;
use crate::model::*;
use base64::Engine;
use dashmap::DashMap;
use rusqlite::{params, Connection};
use sha2::{Digest, Sha256};
use std::sync::Mutex;
use std::time::{Duration, Instant};

pub struct Store {
    db: Mutex<Connection>,
    // Ephemeral sessions: sessionToken -> device
    devices: DashMap<String, RegisteredDevice>,
}

impl Store {
    pub fn new() -> Self {
        let db_path = std::env::var("NEBLINK_DB_PATH").unwrap_or_else(|_| "neblink.db".to_string());
        let conn = Connection::open(&db_path).unwrap_or_else(|e| {
            tracing::error!("Failed to open database '{db_path}': {e}");
            std::process::exit(1);
        });

        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS networks (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                secret_hash TEXT NOT NULL,
                owner_id TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS devices (
                id TEXT NOT NULL,
                network_id TEXT NOT NULL,
                device_name TEXT NOT NULL,
                platform TEXT NOT NULL,
                last_seen INTEGER NOT NULL,
                PRIMARY KEY (id, network_id)
            );",
        )
        .unwrap_or_else(|e| {
            tracing::error!("Failed to create tables: {e}");
            std::process::exit(1);
        });

        tracing::info!("Database ready: {}", db_path);

        Self {
            db: Mutex::new(conn),
            devices: DashMap::new(),
        }
    }

    // ===== User operations =====

    /// Verify a JWT token and return (user_id, email) from claims.
    pub fn verify_user_token(&self, token: &str) -> Option<(String, String)> {
        let secret = std::env::var("NEBFLOW_JWT_SECRET").ok()?;
        let claims = auth::verify_jwt(token, &secret)?;
        Some((claims.sub, claims.email))
    }

    // ===== Network operations =====

    pub fn create_network(&self, name: &str, owner_id: &str) -> CreateNetworkResponse {
        let network_id = uuid::Uuid::new_v4().to_string();
        let secret = generate_secret();
        let secret_hash = sha256_hex(&secret);
        let now = now_secs();

        let db = self.db.lock().unwrap();
        db.execute(
            "INSERT INTO networks (id, name, secret_hash, owner_id, created_at) VALUES (?1, ?2, ?3, ?4, ?5)",
            params![&network_id, name, &secret_hash, owner_id, now],
        )
        .expect("Failed to insert network");
        drop(db);

        tracing::info!("Created network: {} (name={}, owner={})", network_id, name, owner_id);
        CreateNetworkResponse { network_id, secret }
    }

    pub fn list_networks(&self, owner_id: &str) -> Vec<NetworkInfo> {
        let mut result: Vec<NetworkInfo> = {
            let db = self.db.lock().unwrap();
            let mut stmt = db
                .prepare("SELECT id, name FROM networks WHERE owner_id = ?1 ORDER BY created_at")
                .unwrap();
            let rows = stmt
                .query_map(params![owner_id], |row| {
                    Ok(NetworkInfo {
                        network_id: row.get(0)?,
                        name: row.get(1)?,
                        online_devices: 0,
                    })
                })
                .unwrap();
            rows.filter_map(|r| r.ok()).collect()
        }; // db lock released here

        // Count online devices per network from in-memory sessions
        for net in &mut result {
            net.online_devices = self
                .devices
                .iter()
                .filter(|d| d.network_id == net.network_id)
                .count();
        }

        result
    }

    pub fn delete_network(&self, network_id: &str, owner_id: &str) -> Result<(), String> {
        let db = self.db.lock().unwrap();
        let changed = db.execute(
            "DELETE FROM networks WHERE id = ?1 AND owner_id = ?2",
            params![network_id, owner_id],
        )
        .map_err(|e| format!("Database error: {e}"))?;
        drop(db);

        if changed == 0 {
            return Err("Network not found or not owned by you".into());
        }

        // Also clean up device records
        let db = self.db.lock().unwrap();
        let _ = db.execute(
            "DELETE FROM devices WHERE network_id = ?1",
            params![network_id],
        );
        drop(db);

        // Kick all online devices from this network
        let to_remove: Vec<String> = self
            .devices
            .iter()
            .filter(|d| d.network_id == network_id)
            .map(|d| d.session_token.clone())
            .collect();
        for token in to_remove {
            self.devices.remove(&token);
        }

        tracing::info!("Network deleted: {}", network_id);
        Ok(())
    }

    /// Rotate network secret. All existing device sessions are invalidated.
    pub fn rotate_secret(&self, network_id: &str, owner_id: &str) -> Result<String, String> {
        let secret = generate_secret();
        let secret_hash = sha256_hex(&secret);

        let db = self.db.lock().unwrap();
        let changed = db.execute(
            "UPDATE networks SET secret_hash = ?1 WHERE id = ?2 AND owner_id = ?3",
            params![&secret_hash, network_id, owner_id],
        )
        .map_err(|e| format!("Database error: {e}"))?;
        drop(db);

        if changed == 0 {
            return Err("Network not found or not owned by you".into());
        }

        // Kick all existing sessions — they'll need to re-login with the new secret
        let to_remove: Vec<String> = self
            .devices
            .iter()
            .filter(|d| d.network_id == network_id)
            .map(|d| d.session_token.clone())
            .collect();
        for token in to_remove {
            self.devices.remove(&token);
        }

        tracing::info!("Secret rotated for network: {}", network_id);
        Ok(secret)
    }

    // ===== Network ownership checks =====

    /// Verify that a network exists and the secret matches.
    fn verify_network_secret(&self, network_id: &str, secret: &str) -> bool {
        let db = self.db.lock().unwrap();
        let result = db.query_row(
            "SELECT secret_hash FROM networks WHERE id = ?1",
            params![network_id],
            |row| row.get::<_, String>(0),
        );
        drop(db);

        match result {
            Ok(stored_hash) => {
                let provided_hash = sha256_hex(secret);
                constant_time_eq(provided_hash.as_bytes(), stored_hash.as_bytes())
            }
            Err(_) => false,
        }
    }

    /// Check that a user owns a network.
    pub fn check_network_owner(&self, network_id: &str, owner_id: &str) -> bool {
        let db = self.db.lock().unwrap();
        let count: i64 = db
            .query_row(
                "SELECT COUNT(*) FROM networks WHERE id = ?1 AND owner_id = ?2",
                params![network_id, owner_id],
                |row| row.get(0),
            )
            .unwrap_or(0);
        drop(db);
        count > 0
    }

    // ===== Device session operations =====

    pub fn login(&self, req: LoginRequest) -> Result<LoginResponse, String> {
        // Verify network + secret against DB
        if !self.verify_network_secret(&req.network_id, &req.secret) {
            return Err("Invalid network ID or secret".into());
        }

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

        // Persist device record (for management listing)
        let now = now_secs();
        let db = self.db.lock().unwrap();
        let _ = db.execute(
            "INSERT OR REPLACE INTO devices (id, network_id, device_name, platform, last_seen) VALUES (?1, ?2, ?3, ?4, ?5)",
            params![&req.device_id, &req.network_id, &req.device_name, &req.platform, now],
        );
        drop(db);

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
        let (network_id, device_id) = {
            let device = self.devices.get(token).ok_or("Invalid or expired token")?;
            (device.network_id.clone(), device.device_id.clone())
        };

        // Update last_seen
        if let Some(mut device) = self.devices.get_mut(token) {
            device.last_seen = Instant::now();
        }

        // Update persisted last_seen
        let now = now_secs();
        let db = self.db.lock().unwrap();
        let _ = db.execute(
            "UPDATE devices SET last_seen = ?1 WHERE id = ?2 AND network_id = ?3",
            params![now, &device_id, &network_id],
        );
        drop(db);

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
            tracing::info!(
                "Device logged out: {} (token={}...)",
                device.device_id,
                &token[..8.min(token.len())]
            );
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

    // ===== Device management (user-facing) =====

    /// List all devices in a network (from DB, with online status from sessions).
    pub fn list_devices(&self, network_id: &str) -> Vec<DeviceInfo> {
        let mut result: Vec<DeviceInfo> = {
            let db = self.db.lock().unwrap();
            let mut stmt = db
                .prepare(
                    "SELECT id, device_name, platform, last_seen FROM devices WHERE network_id = ?1 ORDER BY last_seen DESC",
                )
                .unwrap();
            let rows = stmt.query_map(params![network_id], |row| {
                Ok(DeviceInfo {
                    device_id: row.get(0)?,
                    device_name: row.get(1)?,
                    platform: row.get(2)?,
                    online: false,
                    last_seen: row.get(3)?,
                })
            });
            match rows {
                Ok(r) => r.filter_map(|r| r.ok()).collect(),
                Err(_) => return vec![],
            }
        }; // db lock released here

        // Mark online devices
        let online_ids: std::collections::HashSet<String> = self
            .devices
            .iter()
            .filter(|d| d.network_id == network_id)
            .map(|d| d.device_id.clone())
            .collect();

        for dev in &mut result {
            dev.online = online_ids.contains(&dev.device_id);
        }

        result
    }

    /// Revoke a device: remove from DB + kick active session.
    pub fn revoke_device(&self, network_id: &str, device_id: &str) -> Result<(), String> {
        let db = self.db.lock().unwrap();
        let changed = db.execute(
            "DELETE FROM devices WHERE id = ?1 AND network_id = ?2",
            params![device_id, network_id],
        )
        .map_err(|e| format!("Database error: {e}"))?;
        drop(db);

        if changed == 0 {
            return Err("Device not found".into());
        }

        // Kick active session if any
        let to_remove: Vec<String> = self
            .devices
            .iter()
            .filter(|d| d.network_id == network_id && d.device_id == device_id)
            .map(|d| d.session_token.clone())
            .collect();
        for token in to_remove {
            self.devices.remove(&token);
        }

        tracing::info!("Device revoked: {} (network={})", device_id, network_id);
        Ok(())
    }
}

// ===== Helpers =====

fn now_secs() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

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

use crate::auth;
use crate::model::*;
use base64::Engine;
use dashmap::DashMap;
use rusqlite::{params, Connection};
use sha2::{Digest, Sha256};
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// An in-memory pairing code: 6-digit code -> (network_id, owner_id, expiry).
/// Consumed on first use; expires after 10 minutes.
struct PairCode {
    network_id: String,
    owner_id: String,
    expires_at: Instant,
}

pub struct Store {
    db: Mutex<Connection>,
    // Ephemeral sessions: sessionToken -> device
    devices: DashMap<String, RegisteredDevice>,
    // Ephemeral pairing codes: code -> PairCode
    pair_codes: DashMap<String, PairCode>,
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
                secret_plain TEXT NOT NULL DEFAULT '',
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
            );
            CREATE TABLE IF NOT EXISTS users (
                id TEXT PRIMARY KEY,
                github_id INTEGER UNIQUE NOT NULL,
                email TEXT,
                name TEXT,
                avatar_url TEXT,
                created_at INTEGER NOT NULL,
                last_login_at INTEGER
            );
            CREATE TABLE IF NOT EXISTS refresh_tokens (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                revoked INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS device_credentials (
                device_id TEXT NOT NULL,
                network_id TEXT NOT NULL,
                credential_hash TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (device_id, network_id)
            );"
        )
        .unwrap_or_else(|e| {
            tracing::error!("Failed to create tables: {e}");
            std::process::exit(1);
        });

        // Migration: add secret_plain column to old databases (ignore error if already exists)
        let _ = conn.execute(
            "ALTER TABLE networks ADD COLUMN secret_plain TEXT NOT NULL DEFAULT ''",
            [],
        );

        // Migration: clear plaintext secrets from old databases (one-time wipe; future
        // writes always store the empty string). Existing devices keep working via
        // the secret_hash column; the get_network_secret endpoint is deprecated.
        let cleared = conn
            .execute("UPDATE networks SET secret_plain = '' WHERE secret_plain != ''", [])
            .unwrap_or(0);
        if cleared > 0 {
            tracing::info!(
                "Cleared {cleared} plaintext secret(s) from legacy networks.secret_plain"
            );
        }

        tracing::info!("Database ready: {}", db_path);

        Self {
            db: Mutex::new(conn),
            devices: DashMap::new(),
            pair_codes: DashMap::new(),
        }
    }

    // ===== User operations =====

    /// Verify a JWT token and return (user_id, email) from claims.
    pub fn verify_user_token(&self, token: &str) -> Option<(String, String)> {
        let secret = std::env::var("NEBFLOW_JWT_SECRET").ok()?;
        let claims = auth::verify_jwt(token, &secret)?;
        Some((claims.sub, claims.email))
    }

    /// Upsert a user by GitHub ID. Returns (user_id, email).
    pub fn upsert_github_user(
        &self,
        github_id: i64,
        email: Option<&str>,
        name: Option<&str>,
        avatar_url: Option<&str>,
    ) -> (String, Option<String>) {
        let now = now_secs();
        let db = self.db.lock().unwrap();

        // Try to find existing user by github_id.
        let existing: Option<(String, Option<String>)> = db
            .query_row(
                "SELECT id, email FROM users WHERE github_id = ?1",
                params![github_id],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .ok();

        let (user_id, final_email) = if let Some((uid, _)) = existing {
            // Update mutable fields + last_login_at.
            let _ = db.execute(
                "UPDATE users
                 SET email = COALESCE(?1, email),
                     name = COALESCE(?2, name),
                     avatar_url = COALESCE(?3, avatar_url),
                     last_login_at = ?4
                 WHERE id = ?5",
                params![email, name, avatar_url, now, uid],
            );
            let updated_email: Option<String> = db
                .query_row(
                    "SELECT email FROM users WHERE id = ?1",
                    params![uid],
                    |row| row.get(0),
                )
                .unwrap_or(None);
            (uid, updated_email)
        } else {
            let user_id = uuid::Uuid::new_v4().to_string();
            db.execute(
                "INSERT INTO users (id, github_id, email, name, avatar_url, created_at, last_login_at)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?6)",
                params![&user_id, github_id, email, name, avatar_url, now],
            )
            .expect("Failed to insert user");
            (user_id, email.map(|s| s.to_string()))
        };
        drop(db);

        tracing::info!("User upserted: github_id={github_id} user_id={user_id}");
        (user_id, final_email)
    }

    // ===== Refresh token operations =====

    /// Look up a user's email by id (used when minting a fresh access token).
    pub fn user_email(&self, user_id: &str) -> Option<String> {
        let db = self.db.lock().unwrap();
        let result = db.query_row(
            "SELECT email FROM users WHERE id = ?1",
            params![user_id],
            |row| row.get::<_, Option<String>>(0),
        );
        drop(db);
        result.ok().flatten()
    }

    /// Persist a refresh token (hashed). Returns nothing; the caller stores the
    /// raw value in the cookie. We only keep the SHA-256 hash so a DB leak
    /// cannot be replayed.
    pub fn save_refresh_token(&self, raw_token: &str, user_id: &str, expires_at: i64) {
        let token_id = sha256_hex(raw_token);
        let now = now_secs();
        let db = self.db.lock().unwrap();
        let _ = db.execute(
            "INSERT INTO refresh_tokens (id, user_id, created_at, expires_at, revoked)
             VALUES (?1, ?2, ?3, ?4, 0)",
            params![&token_id, user_id, now, expires_at],
        );
        drop(db);
    }

    /// Validate a refresh token: must exist, not be revoked, not be expired.
    /// On success, rotates it (revokes the old id, caller mints a new token).
    /// Returns Some(user_id) on success.
    pub fn consume_refresh_token(&self, raw_token: &str) -> Option<String> {
        let token_id = sha256_hex(raw_token);
        let now = now_secs();
        let db = self.db.lock().unwrap();
        let row: Result<(String, i64, i64), rusqlite::Error> = db.query_row(
            "SELECT user_id, expires_at, revoked FROM refresh_tokens WHERE id = ?1",
            params![&token_id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        );
        match row {
            Ok((user_id, expires_at, revoked))
                if revoked == 0 && expires_at > now =>
            {
                // Mark consumed (revoked) so the same refresh token can't be replayed.
                let _ = db.execute(
                    "UPDATE refresh_tokens SET revoked = 1 WHERE id = ?1",
                    params![&token_id],
                );
                drop(db);
                Some(user_id)
            }
            _ => None,
        }
    }

    /// Revoke a refresh token (used by /logout).
    pub fn revoke_refresh_token(&self, raw_token: &str) {
        if raw_token.is_empty() {
            return;
        }
        let token_id = sha256_hex(raw_token);
        let db = self.db.lock().unwrap();
        let _ = db.execute(
            "UPDATE refresh_tokens SET revoked = 1 WHERE id = ?1",
            params![&token_id],
        );
        drop(db);
    }

    /// Revoke all refresh tokens for a user (e.g. on "logout everywhere").
    pub fn revoke_all_refresh_tokens(&self, user_id: &str) {
        let db = self.db.lock().unwrap();
        let _ = db.execute(
            "UPDATE refresh_tokens SET revoked = 1 WHERE user_id = ?1",
            params![user_id],
        );
        drop(db);
    }

    // ===== Network operations =====

    pub fn create_network(&self, name: &str, owner_id: &str) -> CreateNetworkResponse {
        let network_id = uuid::Uuid::new_v4().to_string();
        let secret = generate_secret();
        let secret_hash = sha256_hex(&secret);
        let now = now_secs();

        let db = self.db.lock().unwrap();
        db.execute(
            "INSERT INTO networks (id, name, secret_hash, secret_plain, owner_id, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            // secret_plain intentionally empty: we no longer persist the raw secret.
            params![&network_id, name, &secret_hash, "", owner_id, now],
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
            "UPDATE networks SET secret_hash = ?1, secret_plain = ?2 WHERE id = ?3 AND owner_id = ?4",
            // secret_plain intentionally empty: we no longer persist the raw secret.
            params![&secret_hash, "", network_id, owner_id],
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

    /// Get the plaintext secret for a network (requires ownership).
    pub fn get_network_secret(&self, network_id: &str, owner_id: &str) -> Option<String> {
        let db = self.db.lock().unwrap();
        let result = db.query_row(
            "SELECT secret_plain FROM networks WHERE id = ?1 AND owner_id = ?2",
            params![network_id, owner_id],
            |row| row.get::<_, String>(0),
        );
        drop(db);
        result.ok().filter(|s| !s.is_empty())
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

        // Also drop any per-device credential so it can't re-establish a session.
        self.revoke_device_credential(network_id, device_id);

        tracing::info!("Device revoked: {} (network={})", device_id, network_id);
        Ok(())
    }

    // ===== Pairing codes & device credentials =====

    /// Issue a 6-digit pairing code bound to a network. The code is single-use
    /// and expires after 10 minutes. Returns the human-typed code.
    pub fn issue_pair_code(&self, network_id: &str, owner_id: &str) -> String {
        use rand::Rng;
        let mut rng = rand::thread_rng();
        // Try a few times to avoid (very unlikely) collisions.
        for _ in 0..8 {
            let code: u32 = rng.gen_range(100000..1000000);
            let key = code.to_string();
            if self.pair_codes.contains_key(&key) {
                continue;
            }
            self.pair_codes.insert(
                key.clone(),
                PairCode {
                    network_id: network_id.to_string(),
                    owner_id: owner_id.to_string(),
                    expires_at: Instant::now() + Duration::from_secs(10 * 60),
                },
            );
            return key;
        }
        // Extremely unlikely fallback: a longer random token.
        let key = generate_token()[..12].to_string();
        self.pair_codes.insert(
            key.clone(),
            PairCode {
                network_id: network_id.to_string(),
                owner_id: owner_id.to_string(),
                expires_at: Instant::now() + Duration::from_secs(10 * 60),
            },
        );
        key
    }

    /// Consume a pairing code: validates ownership + expiry, returns the
    /// network_id on success. Single-use: removed whether or not valid.
    pub fn consume_pair_code(&self, code: &str, owner_id: &str) -> Result<String, String> {
        let (_, entry) = self
            .pair_codes
            .remove(code)
            .ok_or_else(|| "Invalid or expired pairing code".to_string())?;
        if entry.owner_id != owner_id {
            return Err("Pairing code does not belong to this user".into());
        }
        if entry.expires_at <= Instant::now() {
            return Err("Pairing code has expired".into());
        }
        Ok(entry.network_id)
    }

    /// Register a per-device credential and return the raw secret (shown to
    /// the caller once, to hand back to the device). The stored hash is what
    /// `/api/device/session` verifies later.
    pub fn enroll_device(
        &self,
        network_id: &str,
        device_id: &str,
    ) -> Result<String, String> {
        // Revoke any existing session for this device first (kick-on-re-enroll).
        let to_remove: Vec<String> = self
            .devices
            .iter()
            .filter(|d| d.network_id == network_id && d.device_id == device_id)
            .map(|d| d.session_token.clone())
            .collect();
        for token in to_remove {
            self.devices.remove(&token);
        }

        let raw_secret = generate_token(); // 32 bytes
        let cred_hash = sha256_hex(&raw_secret);
        let now = now_secs();
        let db = self.db.lock().unwrap();
        db.execute(
            "INSERT OR REPLACE INTO device_credentials
                (device_id, network_id, credential_hash, created_at)
             VALUES (?1, ?2, ?3, ?4)",
            params![device_id, network_id, &cred_hash, now],
        )
        .map_err(|e| format!("Database error: {e}"))?;
        drop(db);
        tracing::info!("Device enrolled: {} (network={})", device_id, network_id);
        Ok(raw_secret)
    }

    /// Verify a per-device credential. Returns true iff it matches.
    pub fn verify_device_credential(
        &self,
        network_id: &str,
        device_id: &str,
        raw_secret: &str,
    ) -> bool {
        let provided = sha256_hex(raw_secret);
        let db = self.db.lock().unwrap();
        let result = db.query_row(
            "SELECT credential_hash FROM device_credentials
             WHERE device_id = ?1 AND network_id = ?2",
            params![device_id, network_id],
            |row| row.get::<_, String>(0),
        );
        drop(db);
        match result {
            Ok(stored) => constant_time_eq(provided.as_bytes(), stored.as_bytes()),
            Err(_) => false,
        }
    }

    /// Revoke a device credential (called by revoke_device via DB cascade).
    pub fn revoke_device_credential(&self, network_id: &str, device_id: &str) {
        let db = self.db.lock().unwrap();
        let _ = db.execute(
            "DELETE FROM device_credentials WHERE device_id = ?1 AND network_id = ?2",
            params![device_id, network_id],
        );
        drop(db);
    }

    /// Drop expired pairing codes. Called from the background cleanup loop.
    pub fn purge_expired_pair_codes(&self) {
        let now = Instant::now();
        self.pair_codes.retain(|_, pc| pc.expires_at > now);
    }

    /// Consume a pairing code without a user session hint: find the entry by
    /// code, verify expiry, return its network_id. The owner binding is weak
    /// here (anyone holding the code can enroll), which is the intended UX —
    /// codes are 6 digits and expire in 10 minutes.
    pub fn consume_pair_code_any(&self, code: &str) -> Result<String, String> {
        let (_, entry) = self
            .pair_codes
            .remove(code)
            .ok_or_else(|| "Invalid or expired pairing code".to_string())?;
        if entry.expires_at <= Instant::now() {
            return Err("Pairing code has expired".into());
        }
        Ok(entry.network_id)
    }

    /// Insert/refresh the persisted device row shown in management listings.
    pub fn upsert_device_row(
        &self,
        device_id: &str,
        network_id: &str,
        device_name: &str,
        platform: &str,
        now: i64,
    ) {
        let db = self.db.lock().unwrap();
        let _ = db.execute(
            "INSERT OR REPLACE INTO devices (id, network_id, device_name, platform, last_seen)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![device_id, network_id, device_name, platform, now],
        );
        drop(db);
    }

    /// Build an ephemeral session for a device that has already been
    /// authenticated via a per-device credential (no network-secret check).
    /// Mirrors `login` but skips `verify_network_secret`.
    pub fn session_for_credential(&self, req: LoginRequest) -> Result<LoginResponse, String> {
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

        // Persist the device row for management listing (name/platform may be
        // empty if the caller didn't supply them — preserve existing values).
        let now = now_secs();
        let db = self.db.lock().unwrap();
        let _ = db.execute(
            "INSERT INTO devices (id, network_id, device_name, platform, last_seen)
             VALUES (?1, ?2, ?3, ?4, ?5)
             ON CONFLICT(id, network_id) DO UPDATE SET last_seen = ?5",
            params![&req.device_id, &req.network_id, &req.device_name, &req.platform, now],
        );
        drop(db);

        let peers = self.get_peers(&req.network_id, &req.device_id);
        tracing::info!(
            "Device session via credential: {} ({})",
            req.device_id,
            req.device_name
        );
        Ok(LoginResponse {
            token,
            network_id: req.network_id,
            device_id: req.device_id,
            peers,
        })
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

//! STT (speech-to-text) service — mirrors Scala SttService.scala.
//!
//! OpenAI-compatible audio transcription API client.
//!
//! Config file: `~/.nebflow/stt-config.json`
//! ```json
//! {
//!   "apiKey": "your-key",
//!   "model": "glm-asr-2512",
//!   "endpoint": "https://open.bigmodel.cn/api/paas/v4/audio/transcriptions"
//! }
//! ```

use std::path::Path;
use std::time::Duration;

use tracing::{info, warn};

const DEFAULT_MODEL: &str = "glm-asr-2512";
const DEFAULT_ENDPOINT: &str = "https://open.bigmodel.cn/api/paas/v4/audio/transcriptions";

/// STT speech recognition service.
pub struct SttService {
    api_key: String,
    model: String,
    endpoint: String,
    client: reqwest::Client,
}

impl SttService {
    /// Load from the default config path (`~/.nebflow/stt-config.json`).
    /// Returns None when the file is missing or has no valid `apiKey`.
    pub fn create() -> Option<Self> {
        Self::create_from(&nebflow_core::config::data_root().join("stt-config.json"))
    }

    /// Load from an explicit config path. Separated from `create()` for
    /// testability (avoids depending on the real `$HOME`).
    pub fn create_from(path: &Path) -> Option<Self> {
        if !path.exists() {
            return None;
        }
        let raw = std::fs::read_to_string(path).ok()?;
        let svc = Self::parse_config(&raw);
        if svc.is_some() {
            info!("STT service initialized");
        }
        svc
    }

    /// Parse config JSON. Pure function for testability.
    /// Requires a non-empty `apiKey`; `model`/`endpoint` have defaults.
    pub fn parse_config(raw: &str) -> Option<Self> {
        let json: serde_json::Value = serde_json::from_str(raw).ok()?;
        let api_key = json
            .get("apiKey")
            .and_then(|v| v.as_str())
            .filter(|s| !s.is_empty())?
            .to_string();
        let model = json
            .get("model")
            .and_then(|v| v.as_str())
            .unwrap_or(DEFAULT_MODEL)
            .to_string();
        let endpoint = json
            .get("endpoint")
            .and_then(|v| v.as_str())
            .unwrap_or(DEFAULT_ENDPOINT)
            .to_string();
        Some(Self::new(api_key, model, endpoint))
    }

    /// Construct directly (used by parse_config and tests).
    pub fn new(api_key: String, model: String, endpoint: String) -> Self {
        let client = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(30))
            .build()
            .unwrap_or_else(|_| reqwest::Client::new());
        Self {
            api_key,
            model,
            endpoint,
            client,
        }
    }

    /// Transcribe WAV audio bytes, returning the recognized text.
    /// `language` is an optional language code (zh, en, ...).
    pub async fn transcribe(
        &self,
        wav_bytes: &[u8],
        language: Option<&str>,
    ) -> Result<String, String> {
        if wav_bytes.is_empty() {
            return Err("Empty audio data".into());
        }

        let file_part = reqwest::multipart::Part::bytes(wav_bytes.to_vec())
            .file_name("audio.wav")
            .mime_str("audio/wav")
            .map_err(|e| e.to_string())?;
        let mut form = reqwest::multipart::Form::new()
            .text("model", self.model.clone())
            .part("file", file_part);
        if let Some(lang) = language.filter(|s| !s.is_empty()) {
            form = form.text("language", lang.to_string());
        }

        let response = self
            .client
            .post(&self.endpoint)
            .bearer_auth(&self.api_key)
            .multipart(form)
            .send()
            .await
            .map_err(|e| {
                warn!("STT transcription failed: {e}");
                e.to_string()
            })?;

        let status = response.status();
        let body = response.text().await.map_err(|e| e.to_string())?;
        if !status.is_success() {
            warn!("STT API error {status}: {}", &body[..body.len().min(300)]);
            return Err(format!("STT API error: {status}"));
        }

        // Prefer JSON `{"text": "..."}`; some providers return plain text.
        if let Ok(json) = serde_json::from_str::<serde_json::Value>(&body) {
            if let Some(text) = json.get("text").and_then(|v| v.as_str()) {
                return Ok(text.trim().to_string());
            }
            return Err("STT API returned unexpected format".into());
        }
        let text = body.trim();
        if text.is_empty() {
            Err("Empty response from STT API".into())
        } else {
            Ok(text.to_string())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn create_from_missing_file_returns_none() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("stt-config.json");
        assert!(SttService::create_from(&path).is_none());
    }

    #[test]
    fn parse_config_requires_api_key() {
        assert!(SttService::parse_config("{}").is_none());
        assert!(SttService::parse_config(r#"{"apiKey": ""}"#).is_none());
        assert!(SttService::parse_config("not json").is_none());
    }

    #[test]
    fn parse_config_applies_defaults() {
        let svc = SttService::parse_config(r#"{"apiKey": "k"}"#).unwrap();
        assert_eq!(svc.model, DEFAULT_MODEL);
        assert_eq!(svc.endpoint, DEFAULT_ENDPOINT);
    }

    #[test]
    fn parse_config_reads_custom_values() {
        let svc =
            SttService::parse_config(r#"{"apiKey": "k", "model": "m1", "endpoint": "http://x/y"}"#)
                .unwrap();
        assert_eq!(svc.model, "m1");
        assert_eq!(svc.endpoint, "http://x/y");
    }

    #[test]
    fn create_from_valid_file_returns_some() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("stt-config.json");
        std::fs::write(&path, r#"{"apiKey": "k", "model": "m1"}"#).unwrap();
        let svc = SttService::create_from(&path).unwrap();
        assert_eq!(svc.model, "m1");
    }

    #[tokio::test]
    async fn transcribe_rejects_empty_audio() {
        let svc = SttService::new("k".into(), "m".into(), "http://127.0.0.1:1/".into());
        let err = svc.transcribe(&[], None).await.unwrap_err();
        assert_eq!(err, "Empty audio data");
    }
}

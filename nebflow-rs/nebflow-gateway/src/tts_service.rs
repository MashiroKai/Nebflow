//! TTS (text-to-speech) service — mirrors Scala TtsService.scala.
//!
//! MiMo-compatible TTS API client. Voice is selected by text language
//! (CJK → zh voice, otherwise en voice) unless explicitly overridden.
//!
//! Config file: `~/.nebflow/tts-config.json`
//! ```json
//! {
//!   "apiKey": "your-key",
//!   "model": "mimo-v2.5-tts",
//!   "endpoint": "https://api.xiaomimimo.com/v1/chat/completions",
//!   "voices": { "zh": "冰糖", "en": "Mia" }
//! }
//! ```
//! Top-level `zhVoice`/`enVoice` keys are also accepted as fallbacks.

use std::path::Path;
use std::time::Duration;

use base64::{engine::general_purpose::STANDARD as B64, Engine as _};
use serde_json::json;
use tracing::{info, warn};

const DEFAULT_MODEL: &str = "mimo-v2.5-tts";
const DEFAULT_ENDPOINT: &str = "https://api.xiaomimimo.com/v1/chat/completions";
const DEFAULT_ZH_VOICE: &str = "冰糖";
const DEFAULT_EN_VOICE: &str = "Mia";

/// TTS speech synthesis service.
pub struct TtsService {
    api_key: String,
    model: String,
    endpoint: String,
    zh_voice: String,
    en_voice: String,
    client: reqwest::Client,
}

impl TtsService {
    /// Load from the default config path (`~/.nebflow/tts-config.json`).
    /// Returns None when the file is missing or has no valid `apiKey`.
    pub fn create() -> Option<Self> {
        Self::create_from(&nebflow_core::config::data_root().join("tts-config.json"))
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
            info!("TTS service initialized");
        }
        svc
    }

    /// Parse config JSON. Pure function for testability.
    /// Voices are read from the `voices` map (Scala format), falling back to
    /// top-level `zhVoice`/`enVoice`, then to built-in defaults.
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
        let voices = json.get("voices");
        let voice = |key: &str, top_level: &str, default: &str| {
            voices
                .and_then(|m| m.get(key))
                .and_then(|v| v.as_str())
                .or_else(|| json.get(top_level).and_then(|v| v.as_str()))
                .unwrap_or(default)
                .to_string()
        };
        Some(Self::new(
            api_key,
            model,
            endpoint,
            voice("zh", "zhVoice", DEFAULT_ZH_VOICE),
            voice("en", "enVoice", DEFAULT_EN_VOICE),
        ))
    }

    /// Construct directly (used by parse_config and tests).
    pub fn new(
        api_key: String,
        model: String,
        endpoint: String,
        zh_voice: String,
        en_voice: String,
    ) -> Self {
        let client = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(30))
            .build()
            .unwrap_or_else(|_| reqwest::Client::new());
        Self {
            api_key,
            model,
            endpoint,
            zh_voice,
            en_voice,
            client,
        }
    }

    /// Detect text language: contains CJK characters → "zh", otherwise "en".
    pub fn detect_language(text: &str) -> &'static str {
        if text.chars().any(|c| ('\u{4e00}'..='\u{9fff}').contains(&c)) {
            "zh"
        } else {
            "en"
        }
    }

    /// Synthesize speech, returning WAV bytes.
    /// `lang` overrides auto-detection ("zh"/"en"). Returns None on any
    /// failure (silent degradation — mirrors Scala).
    pub async fn synthesize(&self, text: &str, lang: Option<&str>) -> Option<Vec<u8>> {
        if text.is_empty() {
            return None;
        }
        let lang = lang
            .filter(|s| *s == "zh" || *s == "en")
            .unwrap_or_else(|| Self::detect_language(text));
        let voice = if lang == "zh" {
            &self.zh_voice
        } else {
            &self.en_voice
        };

        let body = json!({
            "model": self.model,
            "messages": [
                { "role": "user", "content": "" },
                { "role": "assistant", "content": text }
            ],
            "audio": { "format": "wav", "voice": voice }
        });

        let response = self
            .client
            .post(&self.endpoint)
            .header("api-key", &self.api_key)
            .json(&body)
            .send()
            .await
            .map_err(|e| {
                warn!("TTS synthesis failed: {e}");
                e
            })
            .ok()?;

        let status = response.status();
        let resp_body = response.text().await.ok()?;
        if !status.is_success() {
            warn!(
                "TTS API error {status}: {}",
                &resp_body[..resp_body.len().min(200)]
            );
            return None;
        }

        let json: serde_json::Value = serde_json::from_str(&resp_body).ok()?;
        let data = json
            .pointer("/choices/0/message/audio/data")
            .and_then(|v| v.as_str())?;
        B64.decode(data).ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn create_from_missing_file_returns_none() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("tts-config.json");
        assert!(TtsService::create_from(&path).is_none());
    }

    #[test]
    fn parse_config_requires_api_key() {
        assert!(TtsService::parse_config("{}").is_none());
        assert!(TtsService::parse_config(r#"{"apiKey": ""}"#).is_none());
        assert!(TtsService::parse_config("not json").is_none());
    }

    #[test]
    fn parse_config_applies_defaults() {
        let svc = TtsService::parse_config(r#"{"apiKey": "k"}"#).unwrap();
        assert_eq!(svc.model, DEFAULT_MODEL);
        assert_eq!(svc.endpoint, DEFAULT_ENDPOINT);
        assert_eq!(svc.zh_voice, DEFAULT_ZH_VOICE);
        assert_eq!(svc.en_voice, DEFAULT_EN_VOICE);
    }

    #[test]
    fn parse_config_reads_voices_map() {
        let svc =
            TtsService::parse_config(r#"{"apiKey": "k", "voices": {"zh": "小冰", "en": "Alice"}}"#)
                .unwrap();
        assert_eq!(svc.zh_voice, "小冰");
        assert_eq!(svc.en_voice, "Alice");
    }

    #[test]
    fn parse_config_accepts_top_level_voice_keys() {
        let svc =
            TtsService::parse_config(r#"{"apiKey": "k", "zhVoice": "小冰", "enVoice": "Alice"}"#)
                .unwrap();
        assert_eq!(svc.zh_voice, "小冰");
        assert_eq!(svc.en_voice, "Alice");
    }

    #[test]
    fn detect_language_by_cjk() {
        assert_eq!(TtsService::detect_language("你好世界"), "zh");
        assert_eq!(TtsService::detect_language("hello world"), "en");
        assert_eq!(TtsService::detect_language("mixed 混合 text"), "zh");
    }

    #[tokio::test]
    async fn synthesize_rejects_empty_text() {
        let svc = TtsService::new(
            "k".into(),
            "m".into(),
            "http://127.0.0.1:1/".into(),
            "z".into(),
            "e".into(),
        );
        assert!(svc.synthesize("", None).await.is_none());
    }
}

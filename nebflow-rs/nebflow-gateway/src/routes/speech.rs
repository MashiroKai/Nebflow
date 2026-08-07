//! Speech REST API — TTS synthesis and STT transcription.
//!
//! - `POST /api/tts` — mirrors Scala RestApiRoutes `POST /tts`: returns raw
//!   WAV bytes (`Content-Type: audio/wav`) so the frontend can `resp.blob()`.
//! - `POST /api/stt` — REST counterpart of the WS `transcribe` message:
//!   accepts base64 audio, returns `{"text": "..."}`.
//!
//! Neither endpoint requires auth (internal calls, same as Scala).

use axum::extract::State;
use axum::http::{header, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::Json;
use base64::{engine::general_purpose::STANDARD as B64, Engine as _};
use serde::Deserialize;
use serde_json::json;

use crate::routes::AppState;

#[derive(Debug, Deserialize)]
pub struct TtsRequest {
    #[serde(default)]
    pub text: String,
    /// Optional language override ("zh"/"en"); auto-detected when absent.
    pub lang: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct SttRequest {
    /// Base64-encoded audio (WAV).
    #[serde(default)]
    pub audio: String,
    /// Audio format hint (currently only wav is passed through upstream).
    #[allow(dead_code)]
    pub format: Option<String>,
    /// Optional language code forwarded to the STT provider.
    pub language: Option<String>,
}

fn error_response(status: StatusCode, message: &str) -> Response {
    (status, Json(json!({ "error": message }))).into_response()
}

/// POST /api/tts — synthesize speech, returning raw WAV bytes.
pub async fn tts_handler(State(state): State<AppState>, Json(body): Json<TtsRequest>) -> Response {
    let Some(svc) = &state.tts_service else {
        return error_response(StatusCode::NOT_FOUND, "TTS not configured");
    };
    if body.text.is_empty() {
        return error_response(StatusCode::BAD_REQUEST, "Empty text");
    }
    match svc.synthesize(&body.text, body.lang.as_deref()).await {
        Some(bytes) => ([(header::CONTENT_TYPE, "audio/wav")], bytes).into_response(),
        None => error_response(StatusCode::NOT_FOUND, "TTS synthesis failed"),
    }
}

/// POST /api/stt — transcribe base64 audio, returning `{"text": "..."}`.
pub async fn stt_handler(State(state): State<AppState>, Json(body): Json<SttRequest>) -> Response {
    let Some(svc) = &state.stt_service else {
        return error_response(StatusCode::NOT_FOUND, "STT not configured");
    };
    if body.audio.is_empty() {
        return error_response(StatusCode::BAD_REQUEST, "Empty audio");
    }
    let wav_bytes = match B64.decode(&body.audio) {
        Ok(b) => b,
        Err(_) => return error_response(StatusCode::BAD_REQUEST, "Invalid base64 audio"),
    };
    match svc.transcribe(&wav_bytes, body.language.as_deref()).await {
        Ok(text) => Json(json!({ "text": text })).into_response(),
        Err(err) => error_response(StatusCode::BAD_GATEWAY, &err),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::auth::Auth;
    use crate::ratelimit::RateLimiter;
    use crate::session::SessionStore;
    use crate::stt_service::SttService;
    use crate::tts_service::TtsService;
    use crate::ws_hub::WsHub;
    use axum::body::to_bytes;
    use axum::routing::post;
    use std::sync::Arc;
    use tempfile::tempdir;

    fn test_state() -> AppState {
        let dir = tempdir().unwrap();
        AppState::new(
            Arc::new(Auth::with_token("test".into())),
            Arc::new(RateLimiter::new()),
            Arc::new(SessionStore::new(dir.path().to_path_buf())),
            Arc::new(WsHub::new()),
            "1.0.0-test",
            None,
            None,
            None,
        )
    }

    /// Spawn a mock HTTP server returning a fixed JSON body; yields its URL.
    async fn spawn_mock_json(response: serde_json::Value) -> String {
        let app = axum::Router::new().route(
            "/",
            post(move || {
                let body = response.clone();
                async move { Json(body) }
            }),
        );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });
        format!("http://{addr}/")
    }

    async fn body_string(resp: Response) -> String {
        let bytes = to_bytes(resp.into_body(), usize::MAX).await.unwrap();
        String::from_utf8(bytes.to_vec()).unwrap()
    }

    #[tokio::test]
    async fn tts_without_service_returns_404() {
        let state = test_state();
        let resp = tts_handler(
            State(state),
            Json(TtsRequest {
                text: "hello".into(),
                lang: None,
            }),
        )
        .await;
        assert_eq!(resp.status(), StatusCode::NOT_FOUND);
        assert!(body_string(resp).await.contains("TTS not configured"));
    }

    #[tokio::test]
    async fn stt_without_service_returns_404() {
        let state = test_state();
        let resp = stt_handler(
            State(state),
            Json(SttRequest {
                audio: B64.encode(b"fake"),
                format: Some("wav".into()),
                language: None,
            }),
        )
        .await;
        assert_eq!(resp.status(), StatusCode::NOT_FOUND);
        assert!(body_string(resp).await.contains("STT not configured"));
    }

    #[tokio::test]
    async fn tts_with_mock_service_returns_wav_bytes() {
        let audio = b"fake-wav-data";
        let endpoint = spawn_mock_json(json!({
            "choices": [{ "message": { "audio": { "data": B64.encode(audio) } } }]
        }))
        .await;
        let mut state = test_state();
        state.tts_service = Some(Arc::new(TtsService::new(
            "k".into(),
            "m".into(),
            endpoint,
            "zh".into(),
            "en".into(),
        )));
        let resp = tts_handler(
            State(state),
            Json(TtsRequest {
                text: "hello".into(),
                lang: None,
            }),
        )
        .await;
        assert_eq!(resp.status(), StatusCode::OK);
        assert_eq!(
            resp.headers().get(header::CONTENT_TYPE).unwrap(),
            "audio/wav"
        );
        let bytes = to_bytes(resp.into_body(), usize::MAX).await.unwrap();
        assert_eq!(bytes.as_ref(), audio);
    }

    #[tokio::test]
    async fn stt_with_mock_service_returns_text() {
        let endpoint = spawn_mock_json(json!({ "text": "你好世界" })).await;
        let mut state = test_state();
        state.stt_service = Some(Arc::new(SttService::new("k".into(), "m".into(), endpoint)));
        let resp = stt_handler(
            State(state),
            Json(SttRequest {
                audio: B64.encode(b"fake-wav"),
                format: Some("wav".into()),
                language: Some("zh".into()),
            }),
        )
        .await;
        assert_eq!(resp.status(), StatusCode::OK);
        let body: serde_json::Value = serde_json::from_str(&body_string(resp).await).unwrap();
        assert_eq!(body["text"], "你好世界");
    }

    #[tokio::test]
    async fn stt_rejects_invalid_base64() {
        let mut state = test_state();
        state.stt_service = Some(Arc::new(SttService::new(
            "k".into(),
            "m".into(),
            "http://127.0.0.1:1/".into(),
        )));
        let resp = stt_handler(
            State(state),
            Json(SttRequest {
                audio: "!!!not-base64!!!".into(),
                format: None,
                language: None,
            }),
        )
        .await;
        assert_eq!(resp.status(), StatusCode::BAD_REQUEST);
    }
}

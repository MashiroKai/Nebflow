//! Static file serving for the web UI.
//!
//! The Scala gateway serves the SPA from bundled classpath resources
//! (`web/index.html`, `web/css/...`, `web/js/...`, `web/vendor/...`). In Rust we
//! serve the same directory tree from the filesystem via tower-http `ServeDir`,
//! with an SPA fallback to `index.html` for extension-less paths.
//!
//! Web root resolution order:
//! 1. `NEBFLOW_WEB_ROOT` environment variable (explicit override)
//! 2. `<repo>/src/main/resources/web` relative to `CARGO_MANIFEST_DIR`
//!    (nebflow-rs/nebflow-gateway → repo root → Scala web resources)

use std::path::{Path, PathBuf};

use axum::body::Body;
use axum::http::Uri;
use axum::http::{header, HeaderValue, StatusCode};
use axum::response::{Html, IntoResponse, Response};
use tower_http::services::ServeDir;

/// Fallback handler for all non-API, non-WS GET requests.
///
/// - Existing files under the web root are served with the correct MIME type.
/// - `/` and extension-less paths (SPA client routes) return `index.html`.
/// - Anything else returns 404.
pub async fn static_handler(uri: Uri) -> Response {
    let Some(root) = web_root() else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let path = uri.path().trim_start_matches('/');

    // "/" or extension-less path → SPA entry point.
    if path.is_empty() || Path::new(path).extension().is_none() {
        return match std::fs::read_to_string(root.join("index.html")) {
            Ok(html) => no_cache(Html(html).into_response()),
            Err(_) => StatusCode::NOT_FOUND.into_response(),
        };
    }

    // ServeDir handles path-traversal rejection and MIME types internally.
    let path = uri.path().to_string();
    let req = axum::extract::Request::builder()
        .uri(path)
        .body(Body::empty())
        .expect("valid static request");
    match ServeDir::new(&root).try_call(req).await {
        Ok(resp) if resp.status() != StatusCode::NOT_FOUND => resp.into_response(),
        _ => StatusCode::NOT_FOUND.into_response(),
    }
}

/// Resolve the web root directory, or None if no usable root exists.
pub fn web_root() -> Option<PathBuf> {
    if let Ok(dir) = std::env::var("NEBFLOW_WEB_ROOT") {
        let p = PathBuf::from(dir);
        if p.is_dir() {
            return Some(p);
        }
    }
    // CARGO_MANIFEST_DIR = nebflow-rs/nebflow-gateway → ../../ = repo root.
    let repo_web = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../src/main/resources/web");
    repo_web.is_dir().then_some(repo_web)
}

/// Static assets that can change between builds must revalidate every load
/// (mirrors the Scala `Cache-Control: no-cache` on index/css/js).
fn no_cache(resp: Response) -> Response {
    let mut resp = resp;
    resp.headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-cache"));
    resp
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn index_html_is_served_for_root() {
        let resp = static_handler(Uri::from_static("/")).await;
        // The Scala web resources exist in this repo, so this must succeed.
        assert_eq!(resp.status(), StatusCode::OK);
        let body = axum::body::to_bytes(resp.into_body(), usize::MAX)
            .await
            .unwrap();
        let html = String::from_utf8(body.to_vec()).unwrap();
        assert!(
            html.contains("<html"),
            "index.html should be an HTML document"
        );
    }

    #[tokio::test]
    async fn spa_fallback_for_extensionless_path() {
        let resp = static_handler(Uri::from_static("/settings")).await;
        assert_eq!(resp.status(), StatusCode::OK);
        assert_eq!(
            resp.headers().get(header::CACHE_CONTROL).unwrap(),
            "no-cache"
        );
    }

    #[tokio::test]
    async fn serves_css_asset() {
        let resp = static_handler(Uri::from_static("/css/base.css")).await;
        assert_eq!(resp.status(), StatusCode::OK);
        let content_type = resp
            .headers()
            .get(header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_string();
        assert!(
            content_type.contains("css"),
            "expected css content type, got {content_type}"
        );
    }

    #[tokio::test]
    async fn missing_file_returns_404() {
        let resp = static_handler(Uri::from_static("/js/does-not-exist.js")).await;
        assert_eq!(resp.status(), StatusCode::NOT_FOUND);
    }

    #[test]
    fn web_root_prefers_env_override() {
        let dir = tempfile::tempdir().unwrap();
        std::env::set_var("NEBFLOW_WEB_ROOT", dir.path());
        assert_eq!(web_root(), Some(dir.path().to_path_buf()));
        std::env::remove_var("NEBFLOW_WEB_ROOT");
    }

    #[test]
    fn web_root_falls_back_to_repo_web_dir() {
        std::env::remove_var("NEBFLOW_WEB_ROOT");
        let root = web_root().expect("repo web dir should exist");
        assert!(root.join("index.html").is_file());
    }
}

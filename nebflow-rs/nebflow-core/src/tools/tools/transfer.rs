//! TransferFile tool — transfers files between devices.
//!
//! Local-to-local copies are handled directly. Cross-device transfers use
//! the `DeviceTransfer` trait (backed by NebLink's peer-to-peer HTTP API).

use async_trait::async_trait;
use serde_json::{Map, Value};
use std::path::Path;

use crate::types::{ToolContext, ToolError};
use crate::Tool;

const MAX_TRANSFER_BYTES: u64 = 100 * 1024 * 1024; // 100MB

pub struct TransferFileTool {
    schema: Map<String, Value>,
}

impl TransferFileTool {
    pub fn new() -> Self {
        let schema = serde_json::json!({
            "type": "object",
            "properties": {
                "sourcePath": {
                    "type": "string",
                    "description": "Absolute path of the source file"
                },
                "sourceDevice": {
                    "type": "string",
                    "description": "Source device name (omit or 'local' for the local device)"
                },
                "destPath": {
                    "type": "string",
                    "description": "Absolute destination path"
                },
                "destDevice": {
                    "type": "string",
                    "description": "Destination device name (omit or 'local' for the local device)"
                }
            },
            "required": ["sourcePath", "destPath"]
        });
        Self {
            schema: schema.as_object().unwrap().clone(),
        }
    }

    /// Local-to-local file copy.
    fn local_copy(&self, source_path: &str, dest_path: &str) -> Result<String, ToolError> {
        let src = Path::new(source_path);
        if !src.exists() {
            return Err(ToolError::NotFound(format!(
                "Source file does not exist: {source_path}"
            )));
        }
        if src.is_dir() {
            return Err(ToolError::InvalidInput(format!(
                "Source path is a directory: {source_path}. Transfer individual files."
            )));
        }

        let size = std::fs::metadata(src).map(|m| m.len()).unwrap_or(0);
        if size > MAX_TRANSFER_BYTES {
            return Err(ToolError::InvalidInput(format!(
                "File too large to transfer: {} ({:.1}MB, limit {}MB)",
                source_path,
                size as f64 / 1024.0 / 1024.0,
                MAX_TRANSFER_BYTES / 1024 / 1024
            )));
        }

        let dst = Path::new(dest_path);
        if let Some(parent) = dst.parent() {
            if !parent.as_os_str().is_empty() {
                std::fs::create_dir_all(parent).map_err(|e| {
                    ToolError::Execution(format!("Failed to create destination directory: {e}"))
                })?;
            }
        }

        std::fs::copy(src, dst).map_err(|e| ToolError::Execution(format!("Copy failed: {e}")))?;

        Ok(format!(
            "Transferred {} → {} ({:.1}KB)",
            source_path,
            dest_path,
            size as f64 / 1024.0
        ))
    }
}

impl Default for TransferFileTool {
    fn default() -> Self {
        Self::new()
    }
}

fn is_local(device: Option<&str>) -> bool {
    matches!(device, None | Some("") | Some("local"))
}

#[async_trait]
impl Tool for TransferFileTool {
    fn name(&self) -> &str {
        "TransferFile"
    }

    fn description(&self) -> &str {
        "Transfers a file between devices. Local-to-local copies are supported; \
         cross-device transfers require NebLink."
    }

    fn input_schema(&self) -> &Map<String, Value> {
        &self.schema
    }

    async fn call(
        &self,
        input: &Map<String, Value>,
        ctx: &ToolContext,
    ) -> Result<String, ToolError> {
        let source_path = input
            .get("sourcePath")
            .and_then(|v| v.as_str())
            .unwrap_or("");
        let dest_path = input.get("destPath").and_then(|v| v.as_str()).unwrap_or("");

        if source_path.is_empty() || dest_path.is_empty() {
            return Err(ToolError::InvalidInput(
                "sourcePath and destPath are required".into(),
            ));
        }

        let source_device = input.get("sourceDevice").and_then(|v| v.as_str());
        let dest_device = input.get("destDevice").and_then(|v| v.as_str());

        let src_local = is_local(source_device);
        let dst_local = is_local(dest_device);

        // ── Local → Local ──
        if src_local && dst_local {
            return self.local_copy(source_path, dest_path);
        }

        // ── Cross-device transfer ──
        let transfer = match &ctx.device_transfer {
            Some(t) => t,
            None => {
                return Err(ToolError::Execution(
                    "Cross-device transfer requires NebLink DeviceTransfer, \
                     which is not configured. Only local-to-local copies are \
                     available."
                        .into(),
                ));
            }
        };

        if !src_local && dst_local {
            // ── Remote → Local: fetch file from remote device ──
            let device = source_device.unwrap();
            let data = transfer
                .fetch_remote(device, source_path)
                .await
                .map_err(|e| ToolError::Execution(format!("Remote fetch failed: {e}")))?;

            let size = data.len() as u64;
            if size > MAX_TRANSFER_BYTES {
                return Err(ToolError::InvalidInput(format!(
                    "File too large: {:.1}MB, limit {}MB",
                    size as f64 / 1024.0 / 1024.0,
                    MAX_TRANSFER_BYTES / 1024 / 1024
                )));
            }

            let dst = Path::new(dest_path);
            if let Some(parent) = dst.parent() {
                if !parent.as_os_str().is_empty() {
                    std::fs::create_dir_all(parent).map_err(|e| {
                        ToolError::Execution(format!("Failed to create destination directory: {e}"))
                    })?;
                }
            }
            std::fs::write(dst, &data)
                .map_err(|e| ToolError::Execution(format!("Write failed: {e}")))?;

            Ok(format!(
                "Transferred {}:{} → {} ({:.1}KB)",
                device,
                source_path,
                dest_path,
                size as f64 / 1024.0
            ))
        } else if src_local && !dst_local {
            // ── Local → Remote: push file to remote device ──
            let device = dest_device.unwrap();
            let src = Path::new(source_path);
            if !src.exists() {
                return Err(ToolError::NotFound(format!(
                    "Source file does not exist: {source_path}"
                )));
            }
            if src.is_dir() {
                return Err(ToolError::InvalidInput(format!(
                    "Source path is a directory: {source_path}. Transfer individual files."
                )));
            }
            let data = std::fs::read(src)
                .map_err(|e| ToolError::Execution(format!("Failed to read source: {e}")))?;

            let size = data.len() as u64;
            if size > MAX_TRANSFER_BYTES {
                return Err(ToolError::InvalidInput(format!(
                    "File too large: {:.1}MB, limit {}MB",
                    size as f64 / 1024.0 / 1024.0,
                    MAX_TRANSFER_BYTES / 1024 / 1024
                )));
            }

            transfer
                .push_remote(device, dest_path, data)
                .await
                .map_err(|e| ToolError::Execution(format!("Remote push failed: {e}")))?;

            Ok(format!(
                "Transferred {} → {}:{} ({:.1}KB)",
                source_path,
                device,
                dest_path,
                size as f64 / 1024.0
            ))
        } else {
            // ── Remote → Remote: fetch then push ──
            let src_device = source_device.unwrap();
            let dst_device = dest_device.unwrap();
            let data = transfer
                .fetch_remote(src_device, source_path)
                .await
                .map_err(|e| ToolError::Execution(format!("Remote fetch failed: {e}")))?;

            let size = data.len() as u64;
            if size > MAX_TRANSFER_BYTES {
                return Err(ToolError::InvalidInput(format!(
                    "File too large: {:.1}MB, limit {}MB",
                    size as f64 / 1024.0 / 1024.0,
                    MAX_TRANSFER_BYTES / 1024 / 1024
                )));
            }

            transfer
                .push_remote(dst_device, dest_path, data)
                .await
                .map_err(|e| ToolError::Execution(format!("Remote push failed: {e}")))?;

            Ok(format!(
                "Transferred {}:{} → {}:{} ({:.1}KB)",
                src_device,
                source_path,
                dst_device,
                dest_path,
                size as f64 / 1024.0
            ))
        }
    }

    fn summarize(&self, input: &Map<String, Value>) -> String {
        let src = input
            .get("sourcePath")
            .and_then(|v| v.as_str())
            .unwrap_or("");
        let short = src.rsplit('/').next().unwrap_or(src);
        format!("TransferFile({})", short)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::DeviceTransfer;
    use std::io::Write;
    use std::sync::Arc;

    fn ctx() -> ToolContext {
        ToolContext {
            session_id: "test".into(),
            agent_id: "test".into(),
            working_directory: "/tmp".into(),
            depth: 0,
            ..Default::default()
        }
    }

    /// Mock DeviceTransfer that returns canned data.
    struct MockTransfer {
        fetch_data: Result<Vec<u8>, String>,
        push_result: Result<(), String>,
    }

    #[async_trait::async_trait]
    impl DeviceTransfer for MockTransfer {
        async fn fetch_remote(&self, _device: &str, _remote_path: &str) -> Result<Vec<u8>, String> {
            self.fetch_data.clone()
        }

        async fn push_remote(
            &self,
            _device: &str,
            _remote_path: &str,
            _data: Vec<u8>,
        ) -> Result<(), String> {
            self.push_result.clone()
        }
    }

    fn ctx_with_transfer(mock: MockTransfer) -> ToolContext {
        let transfer: Arc<dyn DeviceTransfer> = Arc::new(mock);
        ToolContext {
            session_id: "test".into(),
            agent_id: "test".into(),
            working_directory: "/tmp".into(),
            depth: 0,
            device_transfer: Some(transfer),
            ..Default::default()
        }
    }

    #[tokio::test]
    async fn local_copy_works() {
        let mut src = tempfile::NamedTempFile::new().unwrap();
        src.write_all(b"transfer me").unwrap();
        let src_path = src.path().to_string_lossy().to_string();

        let dst_dir = tempfile::tempdir().unwrap();
        let dst_path = dst_dir
            .path()
            .join("sub/dir/copied.txt")
            .to_string_lossy()
            .to_string();

        let tool = TransferFileTool::new();
        let mut input = Map::new();
        input.insert("sourcePath".into(), Value::String(src_path));
        input.insert("destPath".into(), Value::String(dst_path.clone()));
        let result = tool.call(&input, &ctx()).await.unwrap();
        assert!(result.contains("Transferred"));
        assert_eq!(std::fs::read_to_string(&dst_path).unwrap(), "transfer me");
    }

    #[tokio::test]
    async fn cross_device_without_transfer_rejected() {
        let tool = TransferFileTool::new();
        let mut input = Map::new();
        input.insert("sourcePath".into(), Value::String("/tmp/a".into()));
        input.insert("destPath".into(), Value::String("/tmp/b".into()));
        input.insert("destDevice".into(), Value::String("phone".into()));
        let result = tool.call(&input, &ctx()).await;
        assert!(matches!(result, Err(ToolError::Execution(_))));
        assert!(result.unwrap_err().to_string().contains("NebLink"));
    }

    #[tokio::test]
    async fn cross_device_fetch_from_remote() {
        let tool = TransferFileTool::new();
        let mut input = Map::new();
        input.insert(
            "sourcePath".into(),
            Value::String("/remote/file.txt".into()),
        );
        input.insert("sourceDevice".into(), Value::String("phone".into()));
        input.insert(
            "destPath".into(),
            Value::String("/tmp/local-copy.txt".into()),
        );

        let ctx = ctx_with_transfer(MockTransfer {
            fetch_data: Ok(b"remote content".to_vec()),
            push_result: Ok(()),
        });

        let result = tool.call(&input, &ctx).await.unwrap();
        assert!(result.contains("phone:/remote/file.txt"));
        assert!(result.contains("/tmp/local-copy.txt"));
        assert_eq!(
            std::fs::read_to_string("/tmp/local-copy.txt").unwrap(),
            "remote content"
        );
        // Cleanup
        let _ = std::fs::remove_file("/tmp/local-copy.txt");
    }

    #[tokio::test]
    async fn cross_device_push_to_remote() {
        let tool = TransferFileTool::new();
        let mut src = tempfile::NamedTempFile::new().unwrap();
        src.write_all(b"push me").unwrap();
        let src_path = src.path().to_string_lossy().to_string();

        let mut input = Map::new();
        input.insert("sourcePath".into(), Value::String(src_path));
        input.insert("destPath".into(), Value::String("/remote/dest.txt".into()));
        input.insert("destDevice".into(), Value::String("tablet".into()));

        let ctx = ctx_with_transfer(MockTransfer {
            fetch_data: Err("not used".into()),
            push_result: Ok(()),
        });

        let result = tool.call(&input, &ctx).await.unwrap();
        assert!(result.contains("tablet:/remote/dest.txt"));
    }

    #[tokio::test]
    async fn cross_device_fetch_failure() {
        let tool = TransferFileTool::new();
        let mut input = Map::new();
        input.insert(
            "sourcePath".into(),
            Value::String("/remote/missing.txt".into()),
        );
        input.insert("sourceDevice".into(), Value::String("phone".into()));
        input.insert("destPath".into(), Value::String("/tmp/dst.txt".into()));

        let ctx = ctx_with_transfer(MockTransfer {
            fetch_data: Err("device offline".into()),
            push_result: Ok(()),
        });

        let result = tool.call(&input, &ctx).await;
        assert!(result.is_err());
        assert!(result.unwrap_err().to_string().contains("device offline"));
    }

    #[tokio::test]
    async fn cross_device_push_failure() {
        let tool = TransferFileTool::new();
        let mut src = tempfile::NamedTempFile::new().unwrap();
        src.write_all(b"data").unwrap();
        let src_path = src.path().to_string_lossy().to_string();

        let mut input = Map::new();
        input.insert("sourcePath".into(), Value::String(src_path));
        input.insert("destPath".into(), Value::String("/remote/dst.txt".into()));
        input.insert("destDevice".into(), Value::String("phone".into()));

        let ctx = ctx_with_transfer(MockTransfer {
            fetch_data: Err("not used".into()),
            push_result: Err("disk full".into()),
        });

        let result = tool.call(&input, &ctx).await;
        assert!(result.is_err());
        assert!(result.unwrap_err().to_string().contains("disk full"));
    }

    #[tokio::test]
    async fn missing_source() {
        let tool = TransferFileTool::new();
        let mut input = Map::new();
        input.insert(
            "sourcePath".into(),
            Value::String("/nonexistent/src.txt".into()),
        );
        input.insert("destPath".into(), Value::String("/tmp/dst.txt".into()));
        let result = tool.call(&input, &ctx()).await;
        assert!(matches!(result, Err(ToolError::NotFound(_))));
    }
}

//! Process manager — mirrors Scala ProcessManager.scala.
//!
//! Manages the PID file at `~/.nebflow/.pid` for single-instance enforcement.

use std::path::PathBuf;

/// Get the PID file path.
fn pid_file() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
    PathBuf::from(home).join(".nebflow").join(".pid")
}

/// Read the PID from the PID file. Returns None if file doesn't exist or is invalid.
pub fn read_pid() -> Option<u32> {
    let path = pid_file();
    match std::fs::read_to_string(&path) {
        Ok(content) => content.trim().parse::<u32>().ok(),
        Err(_) => None,
    }
}

/// Write the current PID to the PID file.
pub fn write_pid(pid: u32) {
    let path = pid_file();
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    let _ = std::fs::write(&path, pid.to_string());
}

/// Remove the PID file.
pub fn remove_pid() {
    let path = pid_file();
    let _ = std::fs::remove_file(path);
}

/// Check if a process with the given PID is running.
#[cfg(unix)]
pub fn is_running(pid: u32) -> bool {
    // On Unix, send signal 0 (check existence). Using libc::kill.
    // SAFETY: kill with signal 0 is a standard POSIX check.
    unsafe { libc::kill(pid as i32, 0) == 0 }
}

#[cfg(not(unix))]
pub fn is_running(pid: u32) -> bool {
    // On non-Unix, we can't easily check. Assume running if PID is valid.
    pid > 0
}

/// Stop the running gateway process gracefully.
pub fn stop() {
    match read_pid() {
        None => {
            println!("nebflow is not running");
        }
        Some(pid) if !is_running(pid) => {
            println!("nebflow is not running (removing stale pid file)");
            remove_pid();
        }
        Some(pid) => {
            println!("Stopping nebflow (pid: {pid})...");

            #[cfg(unix)]
            {
                // Send SIGTERM
                unsafe {
                    libc::kill(pid as i32, libc::SIGTERM);
                }

                // Wait up to 5 seconds for graceful shutdown
                let mut waited = 0;
                while is_running(pid) && waited < 50 {
                    std::thread::sleep(std::time::Duration::from_millis(100));
                    waited += 1;
                }

                // Force kill if still running
                if is_running(pid) {
                    println!("Force killing...");
                    unsafe {
                        libc::kill(pid as i32, libc::SIGKILL);
                    }
                    let mut waited = 0;
                    while is_running(pid) && waited < 50 {
                        std::thread::sleep(std::time::Duration::from_millis(100));
                        waited += 1;
                    }
                }
            }

            remove_pid();

            if is_running(pid) {
                println!("Failed to stop nebflow");
            } else {
                println!("nebflow stopped");
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn read_pid_returns_none_for_nonexistent() {
        let _g = crate::test_util::home_mutex().lock().unwrap();
        let dir = tempfile::tempdir().unwrap();
        std::env::set_var("HOME", dir.path());
        assert!(read_pid().is_none());
    }

    #[test]
    fn write_and_read_pid() {
        let _g = crate::test_util::home_mutex().lock().unwrap();
        let dir = tempfile::tempdir().unwrap();
        std::env::set_var("HOME", dir.path());

        write_pid(12345);
        let pid = read_pid();
        assert_eq!(pid, Some(12345));

        remove_pid();
        assert!(read_pid().is_none());
    }

    #[test]
    fn remove_pid_is_idempotent() {
        let _g = crate::test_util::home_mutex().lock().unwrap();
        let dir = tempfile::tempdir().unwrap();
        std::env::set_var("HOME", dir.path());

        // Should not panic even when file doesn't exist
        remove_pid();
        remove_pid();
    }

    #[test]
    fn is_running_nonexistent_pid() {
        // PID 999999 is very unlikely to exist
        assert!(!is_running(999_999));
    }
}

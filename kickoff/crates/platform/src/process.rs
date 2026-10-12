//! Starting helper programs (such as the Piper voice engine).

use std::path::Path;
use std::process::Command;

/// A command for a background helper. On Windows it runs without flashing a
/// console window.
pub fn background_command(program: &Path) -> Command {
    #[allow(unused_mut)]
    let mut command = Command::new(program);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        command.creation_flags(CREATE_NO_WINDOW);
    }
    command
}

/// File name of an executable on this platform (`piper` → `piper.exe`).
pub fn executable_name(stem: &str) -> String {
    format!("{stem}{}", std::env::consts::EXE_SUFFIX)
}

#![cfg_attr(all(windows, not(test)), windows_subsystem = "windows")]

#[cfg(any(windows, test))]
mod adb;
#[cfg(any(windows, test))]
mod device;
#[cfg(any(windows, test))]
mod releases;
#[cfg(windows)]
mod windows;

#[cfg(windows)]
fn main() {
    windows::run();
}

#[cfg(not(windows))]
fn main() {
    eprintln!("Open Voyah Controls Installer requires Windows.");
}

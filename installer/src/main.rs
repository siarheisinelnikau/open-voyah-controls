#![cfg_attr(all(windows, not(test)), windows_subsystem = "windows")]

#[cfg(any(windows, test))]
mod adb;
#[cfg(any(windows, test))]
mod device;
#[cfg(any(windows, test))]
mod init_service;
#[cfg(any(windows, test))]
mod production;
#[cfg(any(windows, test))]
mod recovery;
#[cfg(any(windows, test))]
mod releases;
#[cfg(any(windows, test))]
mod setup;
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

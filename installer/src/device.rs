use crate::adb::{Adb, Device, Result, Target, require_success};
use sha2::{Digest, Sha256};
use std::{
    ffi::OsStr,
    fs::{self, File},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::atomic::{AtomicBool, Ordering},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

pub const PACKAGE: &str = "org.openvoyah.controls";
const ACTIVITY: &str = "org.openvoyah.controls/.MainActivity";
const SETTINGS: &str = "com.qinggan.providers.settings";
const DB: &str = "/data/data/com.qinggan.providers.settings/databases/qinggan_settings.db";
const BACKUP: &str =
    "/data/data/com.qinggan.providers.settings/databases/.open-voyah-controls-backup";
const KEYBOARDS: &[&str] = &["com.qinggan.app.qgime", "com.qinggan.app.qgime.second"];
const RUNTIME: &str = include_str!("../../android/dist/FRIDA_RUNTIME.json");

pub fn apk_files(folder: &Path) -> Result<Vec<PathBuf>> {
    let mut files = Vec::new();
    for entry in fs::read_dir(folder).map_err(message)? {
        let entry = entry.map_err(message)?;
        let path = entry.path();
        if entry.file_type().map_err(message)?.is_file()
            && path
                .extension()
                .is_some_and(|ext| ext.eq_ignore_ascii_case("apk"))
        {
            files.push(path);
        }
    }
    files.sort();
    if files.is_empty() {
        return Err("The folder contains no standalone APKs.".into());
    }
    Ok(files)
}

pub fn install_apks(
    target: &Target<'_>,
    files: &[PathBuf],
    main_app: bool,
    progress: impl Fn(String),
) -> Result<String> {
    for (index, path) in files.iter().enumerate() {
        progress(format!(
            "Installing {} of {} • {}",
            index + 1,
            files.len(),
            filename(path)
        ));
        if main_app {
            target.adb.verify_apk(path, PACKAGE, target.stop)?;
        }
        target.install(path).map_err(|e| {
            format!(
                "Installed {index} of {}. {} failed. {e}",
                files.len(),
                filename(path)
            )
        })?;
    }
    Ok(format!(
        "Installed {} APK(s). Existing app data was retained.",
        files.len()
    ))
}

pub fn launch(target: &Target<'_>) -> Result<String> {
    let output = target.text(["shell", "am", "start", "-n", ACTIVITY], 20)?;
    if output.contains("Error") || !output.contains("Starting:") {
        return Err(format!(
            "Android could not open Open Voyah Controls. {output}"
        ));
    }
    Ok("Open Voyah Controls opened on the selected device.".into())
}

pub fn uninstall(target: &Target<'_>) -> Result<String> {
    require_success(&target.text(["uninstall", "-k", PACKAGE], 30)?)?;
    Ok("Open Voyah Controls removed. App data was kept.".into())
}

pub fn info(target: &Target<'_>) -> Result<String> {
    let product = target.property("ro.product.name")?;
    let fingerprint = target.property("ro.build.fingerprint")?;
    let android = target.property("ro.build.version.release")?;
    let packages = target.text(["shell", "pm", "list", "packages", PACKAGE], 15)?;
    let installed = packages
        .lines()
        .any(|line| line == "package:org.openvoyah.controls");
    Ok(format!(
        "Product: {product}\r\nAndroid: {android}\r\nBuild: {fingerprint}\r\nControls installed: {}",
        if installed { "Yes" } else { "No" }
    ))
}

pub fn install_runtime(target: &Target<'_>, path: &Path) -> Result<String> {
    #[derive(serde::Deserialize)]
    struct Runtime {
        version: String,
        file: String,
        binary_sha256: String,
    }
    let runtime: Runtime = serde_json::from_str(RUNTIME).map_err(message)?;
    if digest(path, target.stop)? != runtime.binary_sha256 {
        return Err("The injector does not match the Android project's pinned SHA-256.".into());
    }
    let abi = target.property("ro.product.cpu.abi")?;
    if abi != "arm64-v8a" {
        return Err(format!(
            "The injector needs Android ARM64. Device ABI is {abi}."
        ));
    }
    let remote = format!("/data/local/tmp/{}", runtime.file);
    let incoming = format!("{remote}.incoming");
    target.text(
        [OsStr::new("push"), path.as_os_str(), OsStr::new(&incoming)],
        120,
    )?;
    let script = format!(
        "set -e; test \"$(sha256sum '{incoming}' | cut -d ' ' -f 1)\" = '{}' && chmod 755 '{incoming}' && '{incoming}' --version && mv -f '{incoming}' '{remote}'",
        runtime.binary_sha256,
    );
    let reported = target.text(["shell", &script], 30)?;
    if !reported.lines().any(|line| line.trim() == runtime.version) {
        return Err(format!(
            "The device reported an unexpected injector version. {reported}"
        ));
    }
    Ok(format!(
        "Frida injector {} installed. The app requires a prepared local root transport.",
        runtime.version
    ))
}

pub fn collect_logs(target: &Target<'_>, root: &Path, progress: impl Fn(String)) -> Result<String> {
    let dir = new_folder(root, "diagnostics")?;
    let mut summary = String::new();
    let jobs: &[(&str, &[&str])] = &[
        ("logcat.txt", &["logcat", "-b", "all", "-d", "-t", "3000"]),
        ("crash.txt", &["logcat", "-b", "crash", "-d", "-t", "300"]),
        ("properties.txt", &["shell", "getprop"]),
        ("storage.txt", &["shell", "df", "-h"]),
        ("mounts.txt", &["shell", "cat", "/proc/mounts"]),
        ("power.txt", &["shell", "dumpsys", "power"]),
        ("window.txt", &["shell", "dumpsys", "window"]),
        ("alarm.txt", &["shell", "dumpsys", "alarm"]),
        ("kernel.txt", &["shell", "dmesg"]),
        ("processes.txt", &["shell", "ps", "-A"]),
        ("tombstones.txt", &["shell", "ls", "-l", "/data/tombstones"]),
        ("anr.txt", &["shell", "ls", "-l", "/data/anr"]),
        ("dropbox.txt", &["shell", "dumpsys", "dropbox"]),
        (
            "crash-files.tar",
            &[
                "exec-out",
                "tar",
                "-cf",
                "-",
                "/data/anr",
                "/data/tombstones",
            ],
        ),
    ];
    let mut saved = 0;
    for (index, (name, args)) in jobs.iter().enumerate() {
        if target.stop.load(Ordering::Relaxed) {
            break;
        }
        progress(format!(
            "Collecting {} of {} • {name}",
            index + 1,
            jobs.len()
        ));
        match target.capture(args, 15, 8 * 1024 * 1024) {
            Ok(bytes) => {
                fs::write(dir.join(name), bytes).map_err(message)?;
                saved += 1;
                summary.push_str(&format!("{name}: saved\n"));
            }
            Err(error) => summary.push_str(&format!("{name}: {error}\n")),
        }
    }
    fs::write(dir.join("summary.txt"), summary).map_err(message)?;
    let archive = archive_logs(&dir).map_err(|error| {
        format!(
            "Reports are available in {}. The ZIP could not be created. {error}",
            dir.display()
        )
    })?;
    Ok(format!(
        "Saved {saved} of {} reports. ZIP: {}. See summary.txt for skipped reports.",
        jobs.len(),
        archive.display()
    ))
}

fn archive_logs(folder: &Path) -> Result<PathBuf> {
    let destination = folder.with_extension("zip");
    let staged = destination.with_extension("part");
    let file = File::options()
        .write(true)
        .create_new(true)
        .open(&staged)
        .map_err(message)?;
    let result = (|| {
        let mut archive = zip::ZipWriter::new(file);
        for entry in fs::read_dir(folder).map_err(message)? {
            let entry = entry.map_err(message)?;
            archive
                .start_file(
                    entry.file_name().to_string_lossy(),
                    zip::write::SimpleFileOptions::default(),
                )
                .map_err(message)?;
            std::io::copy(
                &mut File::open(entry.path()).map_err(message)?,
                &mut archive,
            )
            .map_err(message)?;
        }
        archive
            .finish()
            .map_err(message)?
            .sync_all()
            .map_err(message)?;
        fs::rename(&staged, &destination).map_err(message)
    })();
    if result.is_err() {
        let _ = fs::remove_file(staged);
    }
    result.map(|()| destination)
}

#[derive(Clone, Copy)]
pub enum OemAction {
    ResetSettings,
    RestoreSettings,
    DisableVerity,
    EnableVerity,
    InstallKeyboard,
    RestoreKeyboard,
    Reboot,
}

fn verify_firmware(target: &Target<'_>, fingerprint: &str) -> Result<()> {
    let product = target.property("ro.product.name")?;
    if !matches!(product.as_str(), "f0506h" | "f0505h") {
        return Err(format!(
            "This maintenance workflow is limited to the observed Free and Dreamer products. Detected {product}."
        ));
    }
    if fingerprint.is_empty() || target.property("ro.build.fingerprint")? != fingerprint {
        return Err("Enter the exact build fingerprint shown in Device details and verify that it is the expected firmware.".into());
    }
    Ok(())
}

fn root_device(adb: &Adb, device: &Device, stop: &AtomicBool, fingerprint: &str) -> Result<Device> {
    let target = adb.target(device, stop)?;
    if target.text(["shell", "id", "-u"], 10)? == "0" {
        return Ok(device.clone());
    }
    let identity = target.property("ro.serialno")?;
    let output = target.text(["root"], 15)?;
    if output.contains("cannot run as root") || output.contains("not allowed") {
        return Err("This firmware does not allow ADB root.".into());
    }
    let deadline = Instant::now() + Duration::from_secs(30);
    while Instant::now() < deadline && !stop.load(Ordering::Relaxed) {
        thread::sleep(Duration::from_millis(750));
        if device.serial.contains(':') {
            let _ = adb.text(["connect", &device.serial], 3, stop);
        }
        let candidate = adb
            .devices(stop)?
            .into_iter()
            .find(|d| d.serial == device.serial && d.ready());
        if let Some(candidate) = candidate {
            let target = adb.target(&candidate, stop)?;
            if target.text(["shell", "id", "-u"], 4).as_deref() == Ok("0") {
                verify_firmware(&target, fingerprint)?;
                if !identity.is_empty() && target.property("ro.serialno")? != identity {
                    return Err("A different head unit answered after reconnecting.".into());
                }
                return Ok(candidate);
            }
        }
    }
    Err("The selected unit did not reconnect with ADB root. Refresh before retrying.".into())
}

pub fn maintenance(
    adb: &Adb,
    device: &Device,
    stop: &AtomicBool,
    action: OemAction,
    fingerprint: &str,
    input: &str,
    storage: &Path,
    progress: impl Fn(String),
) -> Result<String> {
    let target = adb.target(device, stop)?;
    verify_firmware(&target, fingerprint)?;
    match action {
        OemAction::InstallKeyboard => return install_keyboard(&target, Path::new(input)),
        OemAction::RestoreKeyboard => return restore_keyboard(&target, input),
        OemAction::Reboot => {
            target.text(["reboot"], 20)?;
            return Ok("Head unit restart requested. Refresh after Android has booted.".into());
        }
        _ => {}
    }
    progress("Checking ADB root and reconnecting to the same head unit…".into());
    let rooted = root_device(adb, device, stop, fingerprint)?;
    let target = adb.target(&rooted, stop)?;
    match action {
        OemAction::DisableVerity | OemAction::EnableVerity => {
            let disable = matches!(action, OemAction::DisableVerity);
            let command = if disable {
                "disable-verity"
            } else {
                "enable-verity"
            };
            let output = target.text([command], 30)?;
            let reported = output.to_ascii_lowercase();
            let expected = if disable { "disabled" } else { "enabled" };
            if !reported.contains(expected)
                || reported.contains("failed")
                || reported.contains("cannot")
            {
                return Err(format!(
                    "Android did not confirm the verity change. {output}"
                ));
            }
            Ok(format!(
                "{output}\r\nRestart the head unit separately to apply the change."
            ))
        }
        OemAction::ResetSettings => reset_settings(&target, storage, fingerprint, progress),
        OemAction::RestoreSettings => restore_settings(&target),
        _ => unreachable!(),
    }
}

fn shell(target: &Target<'_>, script: &str) -> Result<String> {
    target.text(["shell", script], 30)
}

fn reset_settings(
    target: &Target<'_>,
    storage: &Path,
    fingerprint: &str,
    progress: impl Fn(String),
) -> Result<String> {
    progress("Backing up the vehicle settings database to this PC…".into());
    shell(
        target,
        &format!("set -e; test ! -e '{BACKUP}'; test -s '{DB}'; am force-stop {SETTINGS}"),
    )?;
    let folder = new_folder(storage, "settings-backup")?;
    let mut checks = String::new();
    let mut members = Vec::new();
    for suffix in ["", "-wal", "-shm", "-journal"] {
        let remote = format!("{DB}{suffix}");
        let hash = shell(
            target,
            &format!(
                "if [ -f '{remote}' ]; then sha256sum '{remote}' | cut -d ' ' -f 1; else echo absent; fi"
            ),
        )?;
        if hash == "absent" && !suffix.is_empty() {
            checks.push_str(&format!("test ! -e '{remote}'; "));
            continue;
        }
        if hash.len() != 64 || !hash.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(
                "The unit could not hash the settings database. Settings were not reset.".into(),
            );
        }
        let local = folder.join(format!("qinggan_settings.db{suffix}"));
        target.text(
            [OsStr::new("pull"), OsStr::new(&remote), local.as_os_str()],
            120,
        )?;
        if digest(&local, target.stop)? != hash {
            return Err("Settings changed during backup. Settings were not reset.".into());
        }
        File::options()
            .write(true)
            .open(&local)
            .and_then(|file| file.sync_all())
            .map_err(message)?;
        checks.push_str(&format!(
            "test \"$(sha256sum '{remote}' | cut -d ' ' -f 1)\" = '{hash}'; "
        ));
        members.push(suffix);
    }
    let mut record = File::create(folder.join("firmware.txt")).map_err(message)?;
    record
        .write_all(fingerprint.as_bytes())
        .and_then(|_| record.sync_all())
        .map_err(message)?;
    let moves = members
        .iter()
        .map(|suffix| format!("mv '{DB}{suffix}' '{BACKUP}/db{suffix}'; "))
        .collect::<String>();
    let rollback = members.iter().map(|suffix| {
        format!("if [ -e '{BACKUP}/db{suffix}' ]; then mv '{BACKUP}/db{suffix}' '{DB}{suffix}'; fi; ")
    }).collect::<String>();
    let script = format!(
        "set -e; am force-stop {SETTINGS}; {checks} mkdir -m 700 '{BACKUP}'; \
        rollback() {{ {rollback} rmdir '{BACKUP}'; }}; trap rollback EXIT; trap 'exit 1' HUP INT TERM; \
        {moves} sync; trap - EXIT HUP INT TERM"
    );
    shell(target, &script).map_err(|e| {
        format!(
            "Settings reset stopped. Recovery files are in {}. {e}",
            folder.display()
        )
    })?;
    Ok(format!(
        "Settings database moved aside. Local backup: {}. Restart separately, or use Restore settings backup.",
        folder.display()
    ))
}

fn restore_settings(target: &Target<'_>) -> Result<String> {
    let script = format!(
        "set -e; test -s '{BACKUP}/db'; test ! -e '{BACKUP}/current'; am force-stop {SETTINGS}; \
        mkdir -m 700 '{BACKUP}/current'; \
        rollback() {{ for s in '' -wal -shm -journal; do \
        if [ -e '{BACKUP}/restored'$s ]; then mv '{DB}'$s '{BACKUP}/db'$s; rm '{BACKUP}/restored'$s; fi; \
        if [ -e '{BACKUP}/current/db'$s ]; then mv '{BACKUP}/current/db'$s '{DB}'$s; fi; done; rmdir '{BACKUP}/current'; }}; \
        trap rollback EXIT; trap 'exit 1' HUP INT TERM; \
        for s in '' -wal -shm -journal; do \
        if [ -e '{DB}'$s ]; then mv '{DB}'$s '{BACKUP}/current/db'$s; fi; \
        if [ -e '{BACKUP}/db'$s ]; then mv '{BACKUP}/db'$s '{DB}'$s; touch '{BACKUP}/restored'$s; fi; done; \
        sync; trap - EXIT HUP INT TERM"
    );
    shell(target, &script)?;
    Ok("Original settings restored. The replaced database remains in the device backup directory. Restart separately.".into())
}

fn stock_keyboard(target: &Target<'_>, package: &str) -> Result<()> {
    if !KEYBOARDS.contains(&package) {
        return Err("Select one of the two supported OEM keyboard packages.".into());
    }
    let output = target.text(["shell", "pm", "list", "packages", "-s", package], 15)?;
    if !output
        .lines()
        .any(|line| line == format!("package:{package}"))
    {
        return Err("The selected OEM keyboard is not a system package on this firmware.".into());
    }
    Ok(())
}

fn install_keyboard(target: &Target<'_>, apk: &Path) -> Result<String> {
    for package in KEYBOARDS {
        if target.adb.verify_apk(apk, package, target.stop).is_ok() {
            stock_keyboard(target, package)?;
            target.install(apk)?;
            return Ok(format!(
                "Installed the signed update for {package}. Android retained the stock system package."
            ));
        }
    }
    Err("Choose a compatible OEM-signed keyboard APK for this head unit. Android SDK Build-Tools must be installed.".into())
}

fn restore_keyboard(target: &Target<'_>, package: &str) -> Result<String> {
    stock_keyboard(target, package)?;
    let help = target.text(["shell", "cmd", "package", "help"], 15)?;
    if !help.lines().any(|line| {
        line.trim_start()
            .strip_prefix("uninstall-system-updates ")
            .is_some_and(|args| args.contains("PACKAGE"))
    }) {
        return Err("This firmware does not advertise package-specific stock restoration.".into());
    }
    let path = target.text(["shell", "pm", "path", package], 15)?;
    if !path
        .lines()
        .any(|line| line.starts_with("package:/data/app/"))
    {
        return Err(
            "The selected keyboard does not have an installed data-partition update.".into(),
        );
    }
    target.text(
        [
            "shell",
            "cmd",
            "package",
            "uninstall-system-updates",
            package,
        ],
        45,
    )?;
    let path = target.text(["shell", "pm", "path", package], 15)?;
    if path
        .lines()
        .any(|line| line.starts_with("package:/data/app/"))
        || !path.contains("package:/")
    {
        return Err("The device did not confirm restoration of the system keyboard.".into());
    }
    Ok(format!("Restored the stock keyboard for {package}."))
}

pub fn engineering_code(year: u16, month: u16, day: u16) -> Result<String> {
    let leap = year.is_multiple_of(4) && (!year.is_multiple_of(100) || year.is_multiple_of(400));
    let days = [
        31,
        if leap { 29 } else { 28 },
        31,
        30,
        31,
        30,
        31,
        31,
        30,
        31,
        30,
        31,
    ];
    if !(1000..=9999).contains(&year)
        || !(1..=12).contains(&month)
        || !(1..=days[usize::from(month - 1)]).contains(&day)
    {
        return Err("Enter a valid date from the head unit.".into());
    }
    let month_and_day = month * 100 + day;
    let mut result = String::new();
    for place in [1000, 100, 10, 1] {
        let year_digit = year / place % 10;
        let date_digit = month_and_day / place % 10;
        result.push_str(&(year_digit + date_digit).to_string());
    }
    Ok(result)
}

#[derive(serde::Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SavedCode {
    pub label: String,
    pub code: String,
}

pub fn saved_codes(path: &Path) -> Result<Vec<SavedCode>> {
    let mut input = String::new();
    File::open(path)
        .map_err(message)?
        .take(8193)
        .read_to_string(&mut input)
        .map_err(message)?;
    if input.len() > 8192 {
        return Err("The saved codes file must be smaller than 8 KB.".into());
    }
    parse_saved_codes(&input)
}

pub fn parse_saved_codes(input: &str) -> Result<Vec<SavedCode>> {
    let entries: Vec<SavedCode> = serde_json::from_str(input)
        .map_err(|_| "Expected a JSON list of label and code entries.")?;
    if entries.len() > 4
        || entries.iter().any(|entry| {
            entry.label.trim().is_empty()
                || entry.label.chars().count() > 60
                || entry.label.chars().any(char::is_control)
                || entry.code.is_empty()
                || entry.code.len() > 48
                || !entry
                    .code
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"-_*#".contains(&b))
        })
    {
        return Err("Use up to four labeled codes with letters, digits, or -_*#.".into());
    }
    Ok(entries)
}

pub fn digest(path: &Path, stop: &AtomicBool) -> Result<String> {
    let mut source = File::open(path).map_err(message)?;
    let mut hasher = Sha256::new();
    let mut buffer = [0u8; 64 * 1024];
    loop {
        if stop.load(Ordering::Relaxed) {
            return Err("Canceled.".into());
        }
        let count = source.read(&mut buffer).map_err(message)?;
        if count == 0 {
            return Ok(format!("{:x}", hasher.finalize()));
        }
        hasher.update(&buffer[..count]);
    }
}

pub fn new_folder(root: &Path, label: &str) -> Result<PathBuf> {
    fs::create_dir_all(root).map_err(message)?;
    let time = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(message)?
        .as_nanos();
    let folder = root.join(format!("{label}-{time}"));
    fs::create_dir(&folder).map_err(message)?;
    Ok(folder)
}

fn filename(path: &Path) -> String {
    path.file_name()
        .unwrap_or(path.as_os_str())
        .to_string_lossy()
        .into_owned()
}

pub fn message(e: impl std::fmt::Display) -> String {
    e.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn engineering_digits_use_the_head_unit_date_without_carry() {
        assert_eq!(engineering_code(2026, 11, 9).unwrap(), "31215");
        assert_eq!(engineering_code(2026, 11, 10).unwrap(), "3136");
        assert_eq!(engineering_code(2026, 11, 11).unwrap(), "3137");
        assert!(engineering_code(2026, 2, 29).is_err());
        assert!(engineering_code(2028, 2, 29).is_ok());
    }
}

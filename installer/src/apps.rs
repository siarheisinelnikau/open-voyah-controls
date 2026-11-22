//! Other apps on the head unit: install or update standalone APKs, list the installed third-party apps, open,
//! grant permissions, export and uninstall them. None of this touches Open Voyah Controls: its package, runtime,
//! deployment files and startup entry are refused or left alone, so its boot activation stays.

use crate::{
    adb::{Result, Target, require_success},
    device::{PACKAGE, message, new_folder},
    production::{Identity, ScratchFile},
};
use std::{
    ffi::OsStr,
    fs,
    path::{Path, PathBuf},
};

/// OEM and Controls packages this page never installs over, uninstalls or exports.
const PROTECTED: &[&str] = &[
    PACKAGE,
    "android",
    "com.qinggan.app.launcher",
    "com.qinggan.app.setting",
    "com.qinggan.app.vehicle",
    "com.qinggan.bluetoothphone",
    "com.qinggan.app.hiboard",
    "com.qinggan.cluster",
    "com.qinggan.canbus.service",
    "com.qinggan.app.login",
];

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct App {
    pub package: String,
    pub version: String,
}

impl App {
    pub fn label(&self) -> String {
        format!("{}  •  {}", self.package, self.version)
    }
}

pub fn valid_package(name: &str) -> bool {
    name.len() <= 255
        && name.split('.').count() >= 2
        && name.split('.').all(|part| {
            part.as_bytes()
                .first()
                .is_some_and(|c| c.is_ascii_alphabetic())
                && part.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'_')
        })
}

fn allowed(package: &str) -> Result<()> {
    if !valid_package(package) {
        return Err("Select an app from the list.".into());
    }
    if PROTECTED.contains(&package) {
        return Err(if package == PACKAGE {
            "Open Voyah Controls is managed on the Installation page, so its startup activation stays consistent."
                .into()
        } else {
            "OEM system apps are not changed from this page.".into()
        });
    }
    Ok(())
}

const LIST: &str = r#"set -e
pm list packages -3 --user 0 | sed -n 's/^package://p' | sort | while read -r name; do
  version=$(dumpsys package "$name" | sed -n 's/^ *versionName=//p' | head -n 1)
  printf '%s\t%s\n' "$name" "${version:-unknown}"
done
"#;

pub fn parse_list(text: &str) -> Vec<App> {
    text.lines()
        .filter_map(|line| {
            let (package, version) = line.split_once('\t')?;
            let package = package.trim();
            if !valid_package(package) || package == PACKAGE {
                return None;
            }
            let version: String = version
                .trim()
                .chars()
                .filter(|c| c.is_ascii_alphanumeric() || ".+-_ ".contains(*c))
                .take(64)
                .collect();
            Some(App {
                package: package.into(),
                version: if version.is_empty() {
                    "unknown".into()
                } else {
                    version
                },
            })
        })
        .collect()
}

/// Third-party apps for Android user 0, Controls excluded.
pub fn list(target: &Target<'_>) -> Result<Vec<App>> {
    Ok(parse_list(&target.script(LIST, &[], 120)?))
}

fn file_name(path: &Path) -> String {
    path.file_name()
        .map(|name| name.to_string_lossy().into_owned())
        .unwrap_or_default()
}

fn explain_install(output: &str) -> String {
    if output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
        "The installed copy is signed by a different certificate. Uninstall it first (that removes its data), then install again.".into()
    } else if output.contains("INSTALL_FAILED_VERSION_DOWNGRADE") {
        "A newer version is installed. Uninstall it first to go back to an older one.".into()
    } else if output.contains("INSTALL_FAILED_OLDER_SDK") {
        "This APK needs a newer Android version than the head unit has.".into()
    } else if output.contains("INSTALL_FAILED_NO_MATCHING_ABIS") {
        "This APK has no native code for the head unit's ARM64 processor.".into()
    } else if output.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE") {
        "The head unit has not enough free storage.".into()
    } else {
        output.trim().to_owned()
    }
}

/// Whether the running Controls setup is blocked; read only with ADB root, otherwise `None`.
fn controls_block(target: &Target<'_>) -> Option<bool> {
    let output = target
        .script(
            "if [ \"$(id -u)\" = 0 ] && [ -d /data/adb/open-voyah-controls ]; then if [ -e /data/adb/open-voyah-controls/recovery.blocked ]; then echo blocked; else echo clear; fi; else echo unknown; fi\n",
            &[],
            10,
        )
        .ok()?;
    match output.as_str() {
        "blocked" => Some(true),
        "clear" => Some(false),
        _ => None,
    }
}

/// Installs or updates each standalone APK. Controls, OEM packages and split APKs are refused before the first
/// install, so a mistaken pick cannot reach the device.
pub fn install(
    target: &Target<'_>,
    files: &[PathBuf],
    storage: &Path,
    progress: impl Fn(String),
) -> Result<String> {
    if files.is_empty() {
        return Err("Choose or drop one or more APK files.".into());
    }
    let identity = Identity::read(target)?;
    let mut checked = Vec::new();
    for path in files {
        let copy = ScratchFile::copy_from(storage, path, target)?;
        let info = target.adb.apk_info(&copy.path, target.stop)?;
        if info.split {
            return Err(format!(
                "{} is a split APK; choose standalone APK files.",
                file_name(path)
            ));
        }
        if info.package == PACKAGE {
            return Err(format!(
                "{} is Open Voyah Controls. Install it on the Installation page: that keeps its startup activation in step with the app.",
                file_name(path)
            ));
        }
        allowed(&info.package).map_err(|error| format!("{}: {error}", file_name(path)))?;
        checked.push((path, copy, info));
    }
    let before = controls_block(target);
    let mut done = Vec::new();
    for (index, (path, copy, info)) in checked.iter().enumerate() {
        progress(format!(
            "Installing {} of {} • {} ({})",
            index + 1,
            checked.len(),
            file_name(path),
            info.package
        ));
        let _locked = copy.lock()?;
        identity.recheck(target)?;
        let output = target.text(
            [
                OsStr::new("install"),
                OsStr::new("-r"),
                OsStr::new("--user"),
                OsStr::new("0"),
                copy.path.as_os_str(),
            ],
            300,
        )?;
        require_success(&output).map_err(|_| {
            format!(
                "Installed {} of {}. {} failed: {}",
                done.len(),
                checked.len(),
                file_name(path),
                explain_install(&output)
            )
        })?;
        done.push(format!(
            "{} {}",
            info.package,
            info.version_name.as_deref().unwrap_or("")
        ));
    }
    let controls = match (before, controls_block(target)) {
        (Some(a), Some(b)) if a == b => " Open Voyah Controls activation checked: unchanged.",
        (Some(_), Some(_)) => {
            " Open Voyah Controls activation state changed during the install; check Device status."
        }
        _ => " Open Voyah Controls was not touched.",
    };
    Ok(format!(
        "Installed {} app(s): {}.{controls}",
        done.len(),
        done.join(", ")
    ))
}

pub fn uninstall(target: &Target<'_>, package: &str) -> Result<String> {
    allowed(package)?;
    let output = target.text(["shell", "pm", "uninstall", "--user", "0", package], 60)?;
    require_success(&output)
        .map_err(|_| format!("Android did not remove {package}. {}", output.trim()))?;
    Ok(format!("{package} was removed with its data."))
}

pub fn open(target: &Target<'_>, package: &str) -> Result<String> {
    allowed(package)?;
    let component = target
        .script(
            "cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \"$1\" 2>/dev/null | tail -n 1\n",
            &[package],
            20,
        )
        .unwrap_or_default();
    let output = if component.starts_with(&format!("{package}/")) && !component.contains(' ') {
        target.text(
            ["shell", "am", "start", "--user", "0", "-n", &component],
            20,
        )?
    } else {
        target.text(
            [
                "shell",
                "monkey",
                "-p",
                package,
                "-c",
                "android.intent.category.LAUNCHER",
                "1",
            ],
            30,
        )?
    };
    if output.contains("Error")
        || output.contains("No activities found")
        || output.contains("aborted")
    {
        return Err(format!(
            "{package} has no screen Android can open. {}",
            output.trim()
        ));
    }
    Ok(format!("{package} opened on the head unit."))
}

const GRANT: &str = r#"set -e
pkg=$1
granted=
dumpsys package "$pkg" | sed -n '/requested permissions:/,/install permissions:/p' | sed -n 's/^ *\([A-Za-z0-9_.]*\.permission\.[A-Za-z0-9_]*\).*/\1/p' | sort -u > /data/local/tmp/.ovc-perms.$$ || true
while read -r perm; do
  if pm grant --user 0 "$pkg" "$perm" 2>/dev/null; then granted="${granted:+$granted }${perm##*.}"; fi
done < /data/local/tmp/.ovc-perms.$$
rm -f /data/local/tmp/.ovc-perms.$$
printf 'OVC_GRANTED=%s\n' "$granted"
"#;

/// Grants every runtime permission the app requests. Android ignores install-time and signature permissions.
pub fn grant(target: &Target<'_>, package: &str) -> Result<String> {
    allowed(package)?;
    let output = target.script(GRANT, &[package], 90)?;
    let granted = output
        .lines()
        .find_map(|line| line.strip_prefix("OVC_GRANTED="))
        .ok_or("The head unit did not report the permission result.")?;
    Ok(if granted.is_empty() {
        format!("{package} requests no runtime permissions that can be granted.")
    } else {
        format!("Granted to {package}: {}.", granted.replace(' ', ", "))
    })
}

/// Copies the installed APK (and any split parts) of `package` into a new folder under `destination`.
pub fn export(target: &Target<'_>, package: &str, destination: &Path) -> Result<String> {
    allowed(package)?;
    let paths = target.text(["shell", "pm", "path", "--user", "0", package], 20)?;
    let remotes: Vec<_> = paths
        .lines()
        .filter_map(|line| line.trim().strip_prefix("package:"))
        .filter(|path| {
            path.starts_with('/')
                && path.ends_with(".apk")
                && !path.split('/').any(|part| part == "..")
        })
        .map(str::to_owned)
        .collect();
    if remotes.is_empty() {
        return Err(format!("Android reported no APK for {package}."));
    }
    let folder = new_folder(destination, package)?;
    for remote in &remotes {
        let name = remote.rsplit('/').next().unwrap_or("base.apk");
        let local = folder.join(name);
        target.text(
            [OsStr::new("pull"), OsStr::new(remote), local.as_os_str()],
            300,
        )?;
        if fs::metadata(&local).map_err(message)?.len() == 0 {
            return Err(format!("The exported {name} is empty."));
        }
    }
    Ok(format!(
        "Exported {} file(s) of {package} to {}.",
        remotes.len(),
        folder.display()
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_list_skips_controls_and_malformed_rows() {
        let apps = parse_list(
            "org.openvoyah.youtube\t0.2.0\norg.openvoyah.controls\t0.21.0\nbad name\t1\ncom.example.app\t\nru.example.maps\t5.1 (beta)\x07\n",
        );
        assert_eq!(
            apps,
            [
                App {
                    package: "org.openvoyah.youtube".into(),
                    version: "0.2.0".into()
                },
                App {
                    package: "com.example.app".into(),
                    version: "unknown".into()
                },
                App {
                    package: "ru.example.maps".into(),
                    version: "5.1 beta".into()
                },
            ]
        );
    }

    #[test]
    fn controls_and_oem_apps_are_protected() {
        assert!(allowed("org.openvoyah.youtube").is_ok());
        for package in [
            PACKAGE,
            "com.qinggan.app.launcher",
            "android",
            "x",
            "a.b;id",
            "1a.b",
        ] {
            assert!(allowed(package).is_err(), "{package}");
        }
    }

    #[test]
    fn install_errors_say_what_to_do() {
        assert!(
            explain_install("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: x]")
                .contains("Uninstall it first")
        );
        assert!(
            explain_install("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]").contains("newer version")
        );
    }
}

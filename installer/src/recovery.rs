use crate::{
    adb::{Result, Target, require_success},
    device::{PACKAGE, digest, message, new_folder},
    production::{self, Identity, ScratchFile, field, is_sha256, safe_id},
};
use base64::{Engine, engine::general_purpose::STANDARD};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    ffi::OsStr,
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::Path,
};

const BOOT_TOOL: &str = include_str!("../../android/tools/configure-boot.sh");
const RUNTIME: &str = include_str!("../../android/dist/FRIDA_RUNTIME.json");
const MAX_BOOT: usize = 262_144;
const MAX_SETTINGS_EXPORT: usize = 2_097_152;

fn boot(target: &Target<'_>, identity: &Identity, mode: &str, sha: Option<&str>) -> Result<String> {
    production::require_root(target)?;
    let args: Vec<_> = [mode, &identity.fingerprint, &identity.serial]
        .into_iter()
        .chain(sha)
        .collect();
    target.script(BOOT_TOOL, &args, 120)
}

pub fn device_status(target: &Target<'_>) -> Result<String> {
    let identity = Identity::read(target)?;
    let installed = if production::apk_path(target, PACKAGE)?.is_some() {
        "Installed"
    } else {
        "Absent"
    };
    let review = if production::has_review(&identity) {
        "Profile listed (OEM APK verification runs during activation)"
    } else {
        "No exact reviewed firmware profile"
    };
    let hook_status = match boot(target, &identity, "status", None) {
        Ok(status) => {
            let marker = field(&status, "OVC_BOOT_ENTRY")?;
            let blocked = field(&status, "OVC_RECOVERY_BLOCKED")?;
            let legacy = field(&status, "OVC_LEGACY_STATE")?;
            let ready = field(&status, "OVC_READY_CHANNELS").unwrap_or("Unverified");
            let inactive = field(&status, "OVC_ROLLBACK_INERT")? == "yes";
            format!(
                "Ready channels: {}\r\nInactive setup: {}\r\nOptional boot entry: {marker}\r\nRecovery block: {blocked}\r\nLegacy setup: {legacy}",
                if ready.is_empty() { "None reported" } else { ready },
                if inactive { "Verified" } else { "Active or recovery incomplete" },
            )
        }
        Err(_) => "Boot and Frida state: Unverified. Check the authorized root session and retry Device status.".into(),
    };
    Ok(format!(
        "Controls APK (Android user 0): {installed}\r\n{hook_status}\r\nProduction review: {review}\r\nProduct: {}\r\nAndroid SDK: {} • {}\r\nBuild: {}\r\nHardware serial: {}",
        identity.product, identity.sdk, identity.abi, identity.fingerprint, identity.serial,
    ))
}

pub fn require_inactive(
    target: &Target<'_>,
    identity: &Identity,
    require_block: bool,
) -> Result<()> {
    let status = boot(target, identity, "status", None).map_err(|error| {
        format!("An inactive setup must be verified before changing the Controls package or runtime. Use Device status or Roll back Controls. {error}")
    })?;
    let legacy = field(&status, "OVC_LEGACY_STATE")?;
    let rolled_back = field(&status, "OVC_RECOVERY_BLOCKED")? == "yes";
    if field(&status, "OVC_ROLLBACK_INERT")? != "yes"
        || !(legacy == "absent" || (legacy == "inert" && rolled_back))
        || (require_block && !rolled_back)
    {
        return Err("Controls has active or unverified device changes. Use Roll back Controls before changing the package or runtime.".into());
    }
    Ok(())
}

pub fn uninstall(target: &Target<'_>) -> Result<String> {
    let identity = Identity::read(target)?;
    require_inactive(target, &identity, true)?;
    identity.recheck(target)?;
    require_success(&target.text(["uninstall", "-k", PACKAGE], 30)?)?;
    Ok("Controls was removed after checking device state. Android retained the app data.".into())
}

pub fn remove_for_migration(
    target: &Target<'_>,
    fingerprint: &str,
    export: &Path,
    storage: &Path,
) -> Result<String> {
    let identity = Identity::confirmed(target, fingerprint)?;
    require_inactive(target, &identity, true)?;
    let users = target.text(["shell", "pm", "list", "users"], 15)?;
    let owner_users: Vec<_> = users
        .lines()
        .filter_map(|line| {
            line.split_once("UserInfo{")?
                .1
                .split_once(':')
                .map(|(id, _)| id)
        })
        .collect();
    if owner_users != ["0"] {
        return Err("Signer migration requires a verified single Android owner profile. Preserve other users' data through system administration.".into());
    }
    let data = read_limited(export, MAX_SETTINGS_EXPORT)?;
    let settings: serde_json::Value = serde_json::from_slice(&data)
        .map_err(|_| "Select a complete Controls settings export in JSON format.")?;
    if settings["format"].as_str() != Some("org.openvoyah.controls.settings")
        || settings["schema"].as_u64() != Some(1)
        || settings["buttons"]["schema"].as_u64() != Some(1)
        || !settings["buttons"]["bindings"].is_array()
        || !settings["voice"].is_object()
        || !settings["localization"].is_object()
        || !matches!(settings["language"].as_str(), Some("en" | "ru"))
        || !settings["display"]["density"].is_i64()
        || !settings["display"]["clusterMedia"].is_boolean()
    {
        return Err(
            "Select a complete Controls settings export from the selected head unit.".into(),
        );
    }
    let folder = new_folder(storage, "settings-migration")?;
    let preserved = folder.join("controls-settings.json");
    write_new(&preserved, &data)?;
    if read_limited(&preserved, MAX_SETTINGS_EXPORT)? != data {
        return Err("The PC copy of app settings could not be verified.".into());
    }
    let manifest = serde_json::json!({
        "fingerprint": &identity.fingerprint,
        "serial": &identity.serial,
        "sha256": format!("{:x}", Sha256::digest(&data)),
    });
    write_new(
        &folder.join("migration.json"),
        &serde_json::to_vec_pretty(&manifest).map_err(message)?,
    )?;
    identity.recheck(target)?;
    require_inactive(target, &identity, true)?;
    target
        .text(["uninstall", PACKAGE], 30)
        .and_then(|result| require_success(&result))
        .map_err(|error| {
            format!(
                "Full removal was unconfirmed. The settings copy is in {}. {error}",
                folder.display()
            )
        })?;
    Ok(format!(
        "The old Controls APK and its data were removed. Install the signed release and import the settings copy from {}.",
        preserved.display()
    ))
}

#[derive(Deserialize, Serialize, Eq, PartialEq)]
#[serde(deny_unknown_fields)]
struct BootBackup {
    fingerprint: String,
    serial: String,
    context: String,
    owner: String,
    mode: String,
    sha256: String,
}

impl BootBackup {
    fn reported(output: &str) -> Result<Self> {
        Ok(Self {
            fingerprint: field(output, "OVC_BACKUP_FINGERPRINT")?.into(),
            serial: field(output, "OVC_BACKUP_SERIAL")?.into(),
            context: field(output, "OVC_BACKUP_CONTEXT")?.into(),
            owner: field(output, "OVC_BACKUP_OWNER")?.into(),
            mode: field(output, "OVC_BACKUP_MODE")?.into(),
            sha256: field(output, "OVC_BACKUP_SHA256")?.to_ascii_lowercase(),
        })
    }

    fn validate(&self, identity: &Identity, bytes: &[u8]) -> Result<()> {
        let owner_valid = self
            .owner
            .split_once(':')
            .is_some_and(|(uid, gid)| uid == "0" && gid.parse::<u32>().is_ok());
        let context_valid = self.context.len() <= 160
            && self
                .context
                .strip_prefix("u:object_r:")
                .is_some_and(|label| {
                    !label.is_empty()
                        && label
                            .bytes()
                            .all(|c| c.is_ascii_alphanumeric() || b"_.:,".contains(&c))
                });
        if self.fingerprint != identity.fingerprint
            || self.serial != identity.serial
            || !context_valid
            || !owner_valid
            || !(3..=4).contains(&self.mode.len())
            || !self.mode.bytes().all(|c| (b'0'..=b'7').contains(&c))
            || !is_sha256(&self.sha256)
            || bytes.is_empty()
            || bytes.len() > MAX_BOOT
            || format!("{:x}", Sha256::digest(bytes)) != self.sha256
        {
            return Err(
                "The PC boot backup does not match this hardware, build, or verified original."
                    .into(),
            );
        }
        Ok(())
    }
}

fn write_new(path: &Path, bytes: &[u8]) -> Result<()> {
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)
        .map_err(message)?;
    file.write_all(bytes).map_err(message)?;
    file.sync_all().map_err(message)
}

pub fn export_boot_backup(
    target: &Target<'_>,
    fingerprint: &str,
    storage: &Path,
) -> Result<String> {
    let identity = Identity::confirmed(target, fingerprint)?;
    let output = boot(target, &identity, "backup", None)?;
    let remote = field(&output, "OVC_BACKUP_DIR")?;
    if !matches!(
        remote,
        "/data/adb/open-voyah-controls-boot" | "/data/local/tmp/open-voyah-controls-boot"
    ) {
        return Err("The head unit reported an unknown boot backup location.".into());
    }
    let backup = BootBackup::reported(&output)?;
    let path = format!("{remote}/original");
    let bytes = target.capture(&["exec-out", "cat", &path], 20, MAX_BOOT)?;
    backup.validate(&identity, &bytes)?;
    identity.recheck(target)?;
    let folder = new_folder(storage, "boot-backup")?;
    let original = folder.join("original");
    write_new(&original, &bytes)?;
    backup.validate(&identity, &fs::read(original).map_err(message)?)?;
    write_new(
        &folder.join("boot-backup.json"),
        &serde_json::to_vec_pretty(&backup).map_err(message)?,
    )?;
    Ok(format!(
        "Verified original boot script and recovery metadata saved to {}.",
        folder.display()
    ))
}

fn read_limited(path: &Path, limit: usize) -> Result<Vec<u8>> {
    let mut bytes = Vec::new();
    File::open(path)
        .map_err(message)?
        .take(limit as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(message)?;
    if bytes.len() > limit {
        return Err("A PC recovery file exceeds the expected size.".into());
    }
    Ok(bytes)
}

pub fn install_boot(
    target: &Target<'_>,
    fingerprint: &str,
    backup_folder: &Path,
    storage: &Path,
) -> Result<String> {
    let identity = Identity::confirmed(target, fingerprint)?;
    let (backup, _) = load_pc_backup(backup_folder, &identity)?;
    production::verify_activation(target, &identity, storage)?;
    if backup != BootBackup::reported(&boot(target, &identity, "backup", None)?)? {
        return Err("The head unit's verified original or its attributes differ from the selected PC backup.".into());
    }
    let output = boot(target, &identity, "install", Some(&backup.sha256))?;
    identity.recheck(target)?;
    if field(&boot(target, &identity, "status", None)?, "OVC_BOOT_ENTRY")? != "present" {
        return Err("The head unit did not confirm the optional boot entry. Check Device status before retrying.".into());
    }
    Ok(output)
}

fn load_pc_backup(backup_folder: &Path, identity: &Identity) -> Result<(BootBackup, Vec<u8>)> {
    let backup: BootBackup = serde_json::from_slice(&read_limited(
        &backup_folder.join("boot-backup.json"),
        4096,
    )?)
    .map_err(message)?;
    let bytes = read_limited(&backup_folder.join("original"), MAX_BOOT)?;
    backup.validate(identity, &bytes)?;
    Ok((backup, bytes))
}

pub fn rollback(target: &Target<'_>, fingerprint: &str) -> Result<String> {
    let identity = Identity::confirmed(target, fingerprint)?;
    let result = boot(target, &identity, "rollback", None).map_err(recovery_error)?;
    confirm_rollback(target, &identity, result)
}

pub fn restore_exported(
    target: &Target<'_>,
    fingerprint: &str,
    backup_folder: &Path,
) -> Result<String> {
    let identity = Identity::confirmed(target, fingerprint)?;
    let (backup, original) = load_pc_backup(backup_folder, &identity)?;
    production::require_root(target)?;
    let encoded = STANDARD.encode(original);
    let mut script = String::from("ovc_external_original() {\nbase64 -d <<'OVC_ORIGINAL_BYTES'\n");
    for line in encoded.as_bytes().chunks(76) {
        script.push_str(std::str::from_utf8(line).expect("base64 is ASCII"));
        script.push('\n');
    }
    script.push_str("OVC_ORIGINAL_BYTES\n}\n");
    script.push_str(BOOT_TOOL);
    let result = target
        .script(
            &script,
            &[
                "restore-exported",
                &identity.fingerprint,
                &identity.serial,
                &backup.sha256,
                &backup.context,
                &backup.owner,
                &backup.mode,
            ],
            120,
        )
        .map_err(recovery_error)?;
    confirm_rollback(target, &identity, result)
}

fn recovery_error(error: String) -> String {
    if error.contains("OVC_ROLLBACK_VISUALS=reboot-required") {
        format!(
            "The unit requires a separate restart to finish recovery. Check Device status afterward before removing the APK. {error}"
        )
    } else {
        error
    }
}

fn confirm_rollback(target: &Target<'_>, identity: &Identity, result: String) -> Result<String> {
    identity.recheck(target)?;
    let status = boot(target, identity, "status", None)?;
    if field(&status, "OVC_ROLLBACK_INERT")? != "yes" {
        return Err(format!(
            "Rollback could not confirm a fully inactive setup. {result}"
        ));
    }
    Ok(format!(
        "Controls rollback verified. Removing the APK is a separate step.\r\n{result}"
    ))
}

pub fn rearm(target: &Target<'_>, fingerprint: &str, storage: &Path) -> Result<String> {
    let identity = Identity::confirmed(target, fingerprint)?;
    production::verify_activation(target, &identity, storage)?;
    boot(target, &identity, "rearm", None)
}

const RUNTIME_GUARD: &str = r#"set -eu
export PATH=/system/bin:/system/xbin
[ "$(id -u)" = 0 ]
[ "$(getenforce)" = Enforcing ]
[ "$(getprop ro.build.fingerprint)" = "$1" ]
[ "$(getprop ro.serialno)" = "$2" ]
[ "$(getprop ro.product.cpu.abi)" = arm64-v8a ]
[ -d /data/adb ] && [ ! -L /data/adb ] && [ "$(stat -c '%u:%g:%a' /data/adb)" = 0:0:700 ]
base=/data/adb/open-voyah-controls
"#;

pub fn install_runtime(
    target: &Target<'_>,
    source: &Path,
    storage: &Path,
    progress: impl Fn(String),
) -> Result<String> {
    #[derive(Deserialize)]
    struct Runtime {
        version: String,
        file: String,
        binary_sha256: String,
    }
    let runtime: Runtime = serde_json::from_str(RUNTIME).map_err(message)?;
    if !safe_id(&runtime.file, 100)
        || runtime.file.contains('/')
        || !safe_id(&runtime.version, 20)
        || !is_sha256(&runtime.binary_sha256)
    {
        return Err("The pinned injector manifest is invalid.".into());
    }
    let staged = ScratchFile::copy_from(storage, source, target)?;
    let _locked = staged.lock()?;
    if digest(&staged.path, target.stop)? != runtime.binary_sha256 {
        return Err("The injector does not match the project's pinned SHA-256.".into());
    }
    let identity = Identity::read(target)?;
    progress("Checking the reviewed firmware and installed Controls release…".into());
    production::verify_activation(target, &identity, storage)?;
    require_inactive(target, &identity, false)?;
    let prepare = format!(
        "{RUNTIME_GUARD}{}",
        r#"umask 077
if [ ! -e "$base" ]; then mkdir -m 700 "$base"; fi
[ -d "$base" ] && [ ! -L "$base" ] && [ "$(stat -c '%u:%g:%a' "$base")" = 0:0:700 ]
[ ! -f "$base/enabled" ]
[ ! -e "$base/$3" ] || { [ -f "$base/recovery.blocked" ] && [ ! -L "$base/recovery.blocked" ]; }
now=$(date +%s)
case "$now" in ''|*[!0-9]*) exit 2 ;; esac
for stale in "$base"/.injector.????????; do
  [ -f "$stale" ] && [ ! -L "$stale" ] && [ "$(stat -c '%u:%g:%h' "$stale")" = 0:0:1 ] || continue
  modified=$(stat -c %Y "$stale")
  case "$modified" in ''|*[!0-9]*) continue ;; esac
  if [ "$((now - modified))" -gt 600 ]; then rm -f -- "$stale"; fi
done
mktemp "$base/.injector.XXXXXXXX"
"#
    );
    let incoming = target.script(
        &prepare,
        &[&identity.fingerprint, &identity.serial, &runtime.file],
        20,
    )?;
    if !incoming
        .strip_prefix("/data/adb/open-voyah-controls/.injector.")
        .is_some_and(|name| name.len() == 8 && name.bytes().all(|c| c.is_ascii_alphanumeric()))
    {
        return Err("The head unit returned an invalid injector staging location.".into());
    }
    target.text(
        [
            OsStr::new("push"),
            staged.path.as_os_str(),
            OsStr::new(&incoming),
        ],
        120,
    )?;
    let finalize = format!(
        "{RUNTIME_GUARD}{}",
        r#"[ -d "$base" ] && [ ! -L "$base" ] && [ "$(stat -c '%u:%g:%a' "$base")" = 0:0:700 ]
case "$3" in "$base"/.injector.*) ;; *) exit 2 ;; esac
[ -f "$3" ] && [ ! -L "$3" ] && [ "$(stat -c '%u:%g:%h' "$3")" = 0:0:1 ]
trap 'rm -f -- "$3"' EXIT
[ ! -f "$base/enabled" ]
[ ! -e "$base/$6" ] || { [ -f "$base/recovery.blocked" ] && [ ! -L "$base/recovery.blocked" ]; }
[ "$(sha256sum "$3" | awk '{print $1}')" = "$4" ]
chmod 500 "$3"
[ "$("$3" --version)" = "$5" ]
[ ! -L "$base/$6" ] && { [ ! -e "$base/$6" ] || [ -f "$base/$6" ]; }
mv -f -- "$3" "$base/$6"
sync
[ "$(stat -c '%u:%g:%a' "$base/$6")" = 0:0:500 ]
[ "$(sha256sum "$base/$6" | awk '{print $1}')" = "$4" ]
printf 'OVC_RUNTIME_STAGED\n'
"#
    );
    let result = target.script(
        &finalize,
        &[
            &identity.fingerprint,
            &identity.serial,
            &incoming,
            &runtime.binary_sha256,
            &runtime.version,
            &runtime.file,
        ],
        35,
    )?;
    if result != "OVC_RUNTIME_STAGED" {
        return Err("The head unit did not confirm the verified injector.".into());
    }
    Ok(format!(
        "Verified injector {} staged. Open Controls and connect Frida as a separate step.",
        runtime.version
    ))
}

use crate::{
    adb::{ApkInfo, Result, Target},
    device::{PACKAGE, digest, message, new_folder},
    recovery,
};
use semver::Version;
use std::{
    collections::{HashMap, HashSet},
    ffi::OsStr,
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::atomic::Ordering,
};

const CATALOG: &str =
    include_str!("../../android/app/src/main/assets/production/verified-firmware.tsv");
const OPERATIONS: &str =
    include_str!("../../android/app/src/main/assets/production/verified-operations.tsv");
const MAX_APK: u64 = 512 * 1024 * 1024;
const OEM_PACKAGES: &[&str] = &[
    "android",
    "com.qinggan.app.launcher",
    "com.qinggan.app.setting",
    "com.qinggan.app.vehicle",
    "com.qinggan.bluetoothphone",
    "com.qinggan.cluster",
];

#[derive(Clone)]
pub struct ReleaseApk {
    pub version: Version,
    pub sha256: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Identity {
    pub fingerprint: String,
    pub serial: String,
    pub product: String,
    pub abi: String,
    pub sdk: u32,
}

impl Identity {
    pub fn read(target: &Target<'_>) -> Result<Self> {
        let fields = target.script(
            "set -e\nfor prop in ro.build.fingerprint ro.serialno ro.product.name ro.product.cpu.abi ro.build.version.sdk; do\n  printf '%s=%s\\n' \"$prop\" \"$(getprop \"$prop\")\"\ndone\n",
            &[],
            15,
        )?;
        let identity = Self {
            fingerprint: field(&fields, "ro.build.fingerprint")?.into(),
            serial: field(&fields, "ro.serialno")?.into(),
            product: field(&fields, "ro.product.name")?.into(),
            abi: field(&fields, "ro.product.cpu.abi")?.into(),
            sdk: field(&fields, "ro.build.version.sdk")?
                .parse()
                .map_err(message)?,
        };
        if !safe_id(&identity.fingerprint, 512)
            || !safe_id(&identity.serial, 128)
            || identity.serial.bytes().any(|c| b"/+".contains(&c))
            || identity.serial.eq_ignore_ascii_case("unknown")
            || !safe_id(&identity.product, 128)
            || !safe_id(&identity.abi, 32)
            || identity.sdk == 0
        {
            return Err(
                "The selected unit did not report a verifiable hardware and firmware identity."
                    .into(),
            );
        }
        Ok(identity)
    }

    pub fn confirmed(target: &Target<'_>, fingerprint: &str) -> Result<Self> {
        let identity = Self::read(target)?;
        if identity.fingerprint != fingerprint {
            return Err(
                "Confirm the selected head unit's exact build fingerprint before continuing."
                    .into(),
            );
        }
        Ok(identity)
    }

    pub fn recheck(&self, target: &Target<'_>) -> Result<()> {
        if &Self::read(target)? != self {
            return Err("Head-unit identity changed during the operation. Inspect the device before retrying.".into());
        }
        Ok(())
    }
}

pub fn safe_id(value: &str, max: usize) -> bool {
    !value.is_empty()
        && value.len() <= max
        && value.as_bytes()[0].is_ascii_alphanumeric()
        && value
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || b"._:/+-".contains(&c))
}

pub fn is_sha256(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|c| c.is_ascii_hexdigit())
}

pub fn field<'a>(text: &'a str, key: &str) -> Result<&'a str> {
    let mut values = text.lines().filter_map(|line| {
        let (name, value) = line.split_once('=')?;
        (name == key).then_some(value)
    });
    match (values.next(), values.next()) {
        (Some(value), None) => Ok(value),
        _ => Err(format!("The head unit did not report one {key} value.")),
    }
}

fn release_signer() -> Result<&'static str> {
    #[cfg(not(test))]
    let pin = option_env!("OPEN_VOYAH_RELEASE_CERT_SHA256");
    #[cfg(test)]
    let pin = Some("1111111111111111111111111111111111111111111111111111111111111111");
    pin.filter(|value| is_sha256(value))
        .ok_or_else(|| "This installer build has no release certificate pin. Use a verified production installer.".into())
}

fn release_metadata(apk: &ApkInfo, sdk: u32) -> Result<(u64, Version)> {
    if apk.package != PACKAGE {
        return Err(format!(
            "Expected APK package {PACKAGE}; found {}.",
            apk.package
        ));
    }
    if apk.debuggable || apk.split {
        return Err("Choose a standalone, non-debuggable production APK.".into());
    }
    if !apk
        .min_sdk
        .is_some_and(|minimum| (1..=sdk).contains(&minimum))
    {
        return Err("This APK does not declare a compatible minimum Android version.".into());
    }
    let code = apk
        .version_code
        .filter(|version| *version > 0)
        .ok_or("The APK has no valid version code.")?;
    let version = apk
        .version_name
        .as_deref()
        .and_then(|name| Version::parse(name).ok())
        .filter(|version| version.pre.is_empty() && version.build.is_empty())
        .ok_or("The APK has no stable release version.")?;
    Ok((code, version))
}

pub struct ScratchFile {
    directory: PathBuf,
    pub path: PathBuf,
}

impl ScratchFile {
    fn new(storage: &Path) -> Result<Self> {
        let directory = new_folder(storage, "artifact-check")?;
        let path = directory.join("artifact.apk");
        Ok(Self { directory, path })
    }

    pub fn copy_from(storage: &Path, source: &Path, target: &Target<'_>) -> Result<Self> {
        let staged = Self::new(storage)?;
        let mut source = File::open(source).map_err(message)?;
        if !source.metadata().map_err(message)?.is_file() {
            return Err("Choose a regular artifact file.".into());
        }
        let mut copy = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&staged.path)
            .map_err(message)?;
        let mut buffer = [0; 64 * 1024];
        let mut size = 0;
        loop {
            if target.stop.load(Ordering::Relaxed) {
                return Err("Canceled before changing the head unit.".into());
            }
            let read = source.read(&mut buffer).map_err(message)?;
            if read == 0 {
                break;
            }
            size += read as u64;
            if size > MAX_APK {
                return Err("The selected artifact exceeds the 512 MiB limit.".into());
            }
            copy.write_all(&buffer[..read]).map_err(message)?;
        }
        copy.sync_all().map_err(message)?;
        if size == 0 {
            return Err("The selected artifact is empty.".into());
        }
        Ok(staged)
    }

    pub fn lock(&self) -> Result<File> {
        let mut options = OpenOptions::new();
        options.read(true);
        #[cfg(windows)]
        {
            use std::os::windows::fs::OpenOptionsExt;
            options.share_mode(1); // FILE_SHARE_READ
        }
        options.open(&self.path).map_err(message)
    }
}

impl Drop for ScratchFile {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.path);
        let _ = fs::remove_dir(&self.directory);
    }
}

pub fn apk_path(target: &Target<'_>, package: &str) -> Result<Option<String>> {
    if package != PACKAGE && !OEM_PACKAGES.contains(&package) {
        return Err("Unknown package in the reviewed firmware catalog.".into());
    }
    let output = target.script(
        "set -e\npackages=$(pm list packages --user 0 \"$1\")\nif printf '%s\\n' \"$packages\" | grep -Fxq \"package:$1\"; then pm path --user 0 \"$1\"; else printf 'OVC_PACKAGE_ABSENT\\n'; fi\n",
        &[package],
        25,
    )?;
    if output == "OVC_PACKAGE_ABSENT" {
        return Ok(None);
    }
    let path = output.strip_prefix("package:").filter(|p| {
        p.ends_with(".apk")
            && p.starts_with('/')
            && p.len() <= 1024
            && !p.split('/').any(|part| part == "." || part == "..")
            && p.bytes()
                .all(|c| c.is_ascii_alphanumeric() || b"/_-.+~=".contains(&c))
    });
    path.map(|p| Some(p.into())).ok_or_else(|| {
        format!("Could not verify a single installed APK for {package}. Split packages require a reviewed workflow.")
    })
}

fn pull_apk(target: &Target<'_>, remote: &str, storage: &Path) -> Result<ScratchFile> {
    let staged = ScratchFile::new(storage)?;
    target.text(
        [
            OsStr::new("pull"),
            OsStr::new(remote),
            staged.path.as_os_str(),
        ],
        180,
    )?;
    let size = staged.path.metadata().map_err(message)?.len();
    if !(1..=MAX_APK).contains(&size) {
        return Err("The installed APK exceeds the verification limit or is empty.".into());
    }
    Ok(staged)
}

fn verify_installed_controls(
    target: &Target<'_>,
    sdk: u32,
    remote: &str,
    storage: &Path,
) -> Result<(u64, Version, String)> {
    let pin = release_signer()?;
    let current = pull_apk(target, remote, storage)?;
    let _locked = current.lock()?;
    let metadata = target.adb.apk_info(&current.path, target.stop)?;
    let (code, version) = release_metadata(&metadata, sdk)?;
    if !target
        .adb
        .apk_signer(&current.path, sdk, target.stop)?
        .eq_ignore_ascii_case(pin)
    {
        return Err("The installed Controls APK has a different signer. Export its settings and use the rollback workflow before replacing it.".into());
    }
    Ok((code, version, digest(&current.path, target.stop)?))
}

pub fn install_controls(
    target: &Target<'_>,
    source: &Path,
    expected: Option<&ReleaseApk>,
    storage: &Path,
    progress: impl Fn(String),
) -> Result<String> {
    let identity = Identity::read(target)?;
    require_owner_user(target)?;
    let pin = release_signer()?;
    progress("Checking the selected APK and release certificate…".into());
    let staged = ScratchFile::copy_from(storage, source, target)?;
    let _locked = staged.lock()?;
    let metadata = target.adb.apk_info(&staged.path, target.stop)?;
    let (code, version) = release_metadata(&metadata, identity.sdk)?;
    let sha = digest(&staged.path, target.stop)?;
    if expected.is_some_and(|release| {
        !release.sha256.eq_ignore_ascii_case(&sha) || release.version != version
    }) {
        return Err("The selected APK bytes or version differ from the official release.".into());
    }
    if !target
        .adb
        .apk_signer(&staged.path, identity.sdk, target.stop)?
        .eq_ignore_ascii_case(pin)
    {
        return Err(
            "The APK signer does not match the pinned Controls release certificate.".into(),
        );
    }
    let current = apk_path(target, PACKAGE)?;
    if let Some(remote) = current.as_deref() {
        let (installed_code, installed_version, installed_sha) =
            verify_installed_controls(target, identity.sdk, remote, storage).map_err(|error| {
                format!("The existing APK could not be verified for an in-place update. Export settings, roll back Controls, then use Remove old app for signer migration. {error}")
            })?;
        if installed_sha == sha {
            identity.recheck(target)?;
            return Ok(format!(
                "The verified Controls {version} APK is already installed. Check Device status for Frida and boot readiness."
            ));
        }
        if code < installed_code || version < installed_version {
            return Err(
                "A newer Controls release is already installed. Its app data was left in place."
                    .into(),
            );
        }
    }
    recovery::require_inactive(target, &identity, current.is_some())?;
    identity.recheck(target)?;
    progress(format!("Installing verified Controls {version}…"));
    target.install_owner(&staged.path).map_err(|error| {
        if error.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
            format!("Android retained a different app signer. Export settings and use the explicit signer migration in Maintenance after rollback. {error}")
        } else {
            error
        }
    })?;
    let verify = (|| {
        identity.recheck(target)?;
        let remote = apk_path(target, PACKAGE)?.ok_or("The installed package path is missing.")?;
        let installed = pull_apk(target, &remote, storage)?;
        if digest(&installed.path, target.stop)? != sha {
            return Err("The installed APK differs from the verified input.".into());
        }
        Ok(())
    })();
    verify.map_err(|error: String| {
        format!("Android reported an installation. Device verification was incomplete. {error}")
    })?;
    Ok(format!(
        "Controls {version} installed and verified. Check Device status separately for Frida and boot readiness."
    ))
}

struct FirmwareRow<'a> {
    package: &'a str,
    signer: &'a str,
    sha256: &'a str,
}

type ReviewKey<'a> = (&'a str, &'a str, &'a str, &'a str);

fn review_key<'a>(fields: &[&'a str]) -> Result<ReviewKey<'a>> {
    let [fingerprint, sdk, abi, product, ..] = fields else {
        return Err("A reviewed device identity is incomplete.".into());
    };
    if !safe_id(fingerprint, 256)
        || !matches!(*sdk, "28" | "30")
        || *abi != "arm64-v8a"
        || !safe_id(product, 64)
        || !product
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || b"_.-".contains(&c))
    {
        return Err("A reviewed device identity has invalid fields.".into());
    }
    Ok((fingerprint, sdk, abi, product))
}

fn table_rows(table: &str) -> impl Iterator<Item = &str> {
    table
        .lines()
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
}

fn firmware_rows<'a>(
    catalog: &'a str,
    operations: &'a str,
    identity: &Identity,
) -> Result<Vec<FirmwareRow<'a>>> {
    if catalog.len() > 65536 || operations.len() > 65536 {
        return Err("Reviewed firmware metadata is too large.".into());
    }
    let mut profiles: HashMap<ReviewKey<'a>, Vec<FirmwareRow<'a>>> = HashMap::new();
    for line in table_rows(catalog) {
        let fields: Vec<_> = line.split('\t').collect();
        let [_, _, _, _, package, signer, sha256] = fields.as_slice() else {
            return Err("The reviewed firmware catalog has an invalid row.".into());
        };
        if !OEM_PACKAGES.contains(package)
            || !is_sha256(signer)
            || !is_sha256(sha256)
            || signer
                .bytes()
                .chain(sha256.bytes())
                .any(|c| c.is_ascii_uppercase())
        {
            return Err("The reviewed firmware catalog has invalid trust fields.".into());
        }
        let rows = profiles.entry(review_key(&fields)?).or_default();
        if rows.iter().any(|row| row.package == *package) {
            return Err("A reviewed firmware profile repeats an OEM package.".into());
        }
        rows.push(FirmwareRow {
            package,
            signer,
            sha256,
        });
    }
    if profiles.values().any(|rows| {
        !rows
            .iter()
            .any(|row| row.package == "com.qinggan.app.launcher")
    }) {
        return Err("A reviewed firmware profile has no launcher entry.".into());
    }
    let mut reviewed_operations = HashSet::new();
    for line in table_rows(operations) {
        let fields: Vec<_> = line.split('\t').collect();
        let [_, _, _, _, name] = fields.as_slice() else {
            return Err("The reviewed operations catalog has an invalid row.".into());
        };
        let key = review_key(&fields)?;
        if !matches!(
            *name,
            "window-service" | "oem-profiles" | "tbox-reboot" | "cluster-write"
        ) || !profiles.contains_key(&key)
            || !reviewed_operations.insert((key, *name))
        {
            return Err("A reviewed operation has an invalid or repeated device profile.".into());
        }
    }
    let sdk = identity.sdk.to_string();
    let selected = (
        identity.fingerprint.as_str(),
        sdk.as_str(),
        identity.abi.as_str(),
        identity.product.as_str(),
    );
    profiles.into_iter().find_map(|(key, rows)| (key == selected).then_some(rows)).ok_or_else(|| {
        "This exact firmware has no reviewed launcher and package profile. Production activation is unavailable.".into()
    })
}

pub fn has_review(identity: &Identity) -> bool {
    firmware_rows(CATALOG, OPERATIONS, identity).is_ok()
}

pub fn check_reviewed(target: &Target<'_>, identity: &Identity, storage: &Path) -> Result<()> {
    let rows = firmware_rows(CATALOG, OPERATIONS, identity)?;
    for row in rows {
        let remote = apk_path(target, row.package)?
            .ok_or_else(|| format!("Reviewed OEM package {} is absent.", row.package))?;
        let staged = pull_apk(target, &remote, storage)?;
        let _locked = staged.lock()?;
        if !digest(&staged.path, target.stop)?.eq_ignore_ascii_case(row.sha256)
            || target.adb.apk_info(&staged.path, target.stop)?.package != row.package
            || !target
                .adb
                .apk_signer(&staged.path, identity.sdk, target.stop)?
                .eq_ignore_ascii_case(row.signer)
        {
            return Err(format!(
                "OEM package {} differs from the reviewed firmware profile.",
                row.package
            ));
        }
    }
    identity.recheck(target)
}

pub fn require_root(target: &Target<'_>) -> Result<()> {
    if target.text(["shell", "id", "-u"], 10)? != "0" {
        return Err("An already-authorized root ADB session is required. Prepare the head unit before continuing.".into());
    }
    Ok(())
}

fn require_owner_user(target: &Target<'_>) -> Result<()> {
    if target.text(["shell", "am", "get-current-user"], 10)? != "0" {
        return Err("Switch the head unit to Android owner user 0 before installing or activating Controls.".into());
    }
    Ok(())
}

pub fn verify_activation(target: &Target<'_>, identity: &Identity, storage: &Path) -> Result<()> {
    require_owner_user(target)?;
    require_root(target)?;
    if target.text(["shell", "getenforce"], 10)? != "Enforcing" {
        return Err("Production activation requires SELinux Enforcing.".into());
    }
    check_reviewed(target, identity, storage)?;
    let remote = apk_path(target, PACKAGE)?.ok_or("Install the verified Controls APK first.")?;
    verify_installed_controls(target, identity.sdk, &remote, storage)?;
    identity.recheck(target)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn firmware_review_requires_the_complete_exact_identity_and_unique_packages() {
        let identity = Identity {
            fingerprint: "example/product/device:11/TEST/1:user/release-keys".into(),
            serial: "fixture".into(),
            product: "f0506h".into(),
            abi: "arm64-v8a".into(),
            sdk: 30,
        };
        let row = format!(
            "{}\t30\tarm64-v8a\tf0506h\tcom.qinggan.app.launcher\t{}\t{}\n",
            identity.fingerprint,
            "1".repeat(64),
            "2".repeat(64)
        );
        assert_eq!(firmware_rows(&row, "", &identity).unwrap().len(), 1);
        assert!(firmware_rows(&row.repeat(2), "", &identity).is_err());
        assert!(firmware_rows(&row.replace("f0506h", "other"), "", &identity).is_err());
        assert!(firmware_rows("", "", &identity).is_err());
    }

    #[test]
    fn script_response_must_be_unambiguous() {
        assert_eq!(
            field("OVC_ROLLBACK_INERT=yes", "OVC_ROLLBACK_INERT").unwrap(),
            "yes"
        );
        assert!(
            field(
                "OVC_ROLLBACK_INERT=yes\nOVC_ROLLBACK_INERT=no",
                "OVC_ROLLBACK_INERT"
            )
            .is_err()
        );
        assert!(field("diagnostic only", "OVC_ROLLBACK_INERT").is_err());
    }
}

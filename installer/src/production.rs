use crate::{
    adb::{ApkInfo, Result, Target},
    device::{PACKAGE, digest, message, new_folder},
    recovery,
};
use semver::Version;
use std::{
    collections::HashMap,
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
    "com.qinggan.app.hiboard",
    "com.qinggan.cluster",
    "com.qinggan.canbus.service",
    "com.qinggan.app.login",
];

pub const YOUTUBE_PACKAGE: &str = "org.openvoyah.youtube";

/// An app the installer verifies against its own pinned release certificate.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Product {
    Controls,
    /// The YouTube web wrapper. It has its own release key and never touches the Controls runtime or boot entry.
    YouTube,
}

impl Product {
    pub fn package(self) -> &'static str {
        match self {
            Product::Controls => PACKAGE,
            Product::YouTube => YOUTUBE_PACKAGE,
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            Product::Controls => "Controls",
            Product::YouTube => "YouTube",
        }
    }

    fn signer(self) -> Result<&'static str> {
        #[cfg(not(test))]
        let pin = match self {
            Product::Controls => option_env!("OPEN_VOYAH_RELEASE_CERT_SHA256"),
            Product::YouTube => option_env!("OPEN_VOYAH_YOUTUBE_CERT_SHA256"),
        };
        #[cfg(test)]
        let pin = Some(match self {
            Product::Controls => "1111111111111111111111111111111111111111111111111111111111111111",
            Product::YouTube => "3333333333333333333333333333333333333333333333333333333333333333",
        });
        pin.filter(|value| is_sha256(value)).ok_or_else(|| {
            format!(
                "This installer build has no {} release certificate pin. Use a verified production installer.",
                self.name()
            )
        })
    }
}

/// How an APK's signing certificate was accepted.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Build {
    /// The certificate pinned into this installer build for the official releases.
    Release,
    /// A certificate the owner trusted on this PC for their own builds. Debuggable local-vehicle builds are
    /// accepted only in this mode.
    Owner,
}

/// The signing certificates and local review tables this PC accepts besides the pinned release certificate.
///
/// Owner certificates come from two places, both under the installer's storage folder: certificates the user
/// confirmed in the installer (`trusted-signers.txt`) and `local-vehicle/signer.sha256`, the same file a local-vehicle
/// Android build reads. A local-vehicle folder may also hold `verified-firmware.tsv` and `verified-operations.tsv`
/// for the owner's exact head unit; they count as a reviewed profile next to the catalog compiled into this build.
#[derive(Clone, Debug, Default)]
pub struct Trust {
    owner: Vec<String>,
    catalog: String,
    operations: String,
}

pub const TRUSTED_SIGNERS: &str = "trusted-signers.txt";
pub const LOCAL_VEHICLE: &str = "local-vehicle";

impl Trust {
    /// Only the pinned release certificates.
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn release() -> Self {
        Self::default()
    }

    pub fn load(store: &Path) -> Self {
        let mut owner = Vec::new();
        let mut add = |text: &str| {
            for line in text.lines() {
                let value = line.trim().to_ascii_lowercase();
                if is_sha256(&value) && !owner.contains(&value) {
                    owner.push(value);
                }
            }
        };
        let read = |path: PathBuf| {
            File::open(path)
                .ok()
                .and_then(|file| {
                    let mut text = String::new();
                    file.take(65536).read_to_string(&mut text).ok()?;
                    Some(text)
                })
                .unwrap_or_default()
        };
        add(&read(store.join(TRUSTED_SIGNERS)));
        let local = store.join(LOCAL_VEHICLE);
        add(&read(local.join("signer.sha256")));
        Self {
            owner,
            catalog: read(local.join("verified-firmware.tsv")),
            operations: read(local.join("verified-operations.tsv")),
        }
    }

    /// Records a certificate the user confirmed after seeing its SHA-256.
    pub fn remember(store: &Path, signer: &str) -> Result<()> {
        let signer = signer.to_ascii_lowercase();
        if !is_sha256(&signer) {
            return Err("The certificate fingerprint is not a SHA-256 value.".into());
        }
        if Self::load(store).owner.contains(&signer) {
            return Ok(());
        }
        fs::create_dir_all(store).map_err(message)?;
        let mut file = OpenOptions::new()
            .create(true)
            .append(true)
            .open(store.join(TRUSTED_SIGNERS))
            .map_err(message)?;
        writeln!(file, "{signer}").map_err(message)?;
        file.sync_all().map_err(message)
    }

    /// Release when the certificate is the product's pin, Owner when the user trusted it, otherwise an error.
    pub fn classify(&self, product: Product, signer: &str) -> Result<Build> {
        if product
            .signer()
            .is_ok_and(|pin| pin.eq_ignore_ascii_case(signer))
        {
            return Ok(Build::Release);
        }
        if self
            .owner
            .iter()
            .any(|value| value.eq_ignore_ascii_case(signer))
        {
            return Ok(Build::Owner);
        }
        Err(format!(
            "The {} APK is signed by certificate {signer}, which is neither the pinned release certificate nor one you trusted on this PC.",
            product.name()
        ))
    }

    fn tables(&self) -> [(&str, &str); 2] {
        [(CATALOG, OPERATIONS), (&self.catalog, &self.operations)]
    }
}

/// What the PC learned about an APK before anything reaches the head unit.
#[derive(Clone, Debug)]
pub struct ApkFacts {
    pub package: String,
    pub version: String,
    pub code: u64,
    pub debuggable: bool,
    pub signer: String,
}

/// Reads an APK's identity and signer on the PC without trusting it yet.
pub fn inspect_apk(target: &Target<'_>, source: &Path, storage: &Path) -> Result<ApkFacts> {
    let identity = Identity::read(target)?;
    let staged = ScratchFile::copy_from(storage, source, target)?;
    let _locked = staged.lock()?;
    let info = target.adb.apk_info(&staged.path, target.stop)?;
    if info.split {
        return Err("Choose a standalone APK; split packages are not supported.".into());
    }
    let signer = target
        .adb
        .apk_signer(&staged.path, identity.sdk, target.stop)?;
    Ok(ApkFacts {
        version: info
            .version_name
            .clone()
            .unwrap_or_else(|| "unknown".into()),
        code: info.version_code.unwrap_or(0),
        debuggable: info.debuggable,
        package: info.package,
        signer,
    })
}

#[derive(Clone)]
pub struct ReleaseApk {
    pub version: Version,
    pub sha256: String,
}

/// A local APK and, when it came from Releases, the official version and digest it must match.
#[derive(Clone, Copy)]
pub struct ApkChoice<'a> {
    pub path: &'a Path,
    pub expected: Option<&'a ReleaseApk>,
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
            "set -e\nfor prop in ro.build.fingerprint ro.serialno ro.product.name ro.build.product_3rdparty ro.product.cpu.abi ro.build.version.sdk; do\n  printf '%s=%s\\n' \"$prop\" \"$(getprop \"$prop\")\"\ndone\n",
            &[],
            15,
        )?;
        // Some OEM frameworks report ro.build.product_3rdparty as Build.PRODUCT; the app, the supervisor and the
        // review tables use it the same way.
        let product = field(&fields, "ro.build.product_3rdparty")
            .ok()
            .filter(|value| !value.is_empty())
            .map_or_else(|| field(&fields, "ro.product.name"), Ok)?;
        let identity = Self {
            fingerprint: field(&fields, "ro.build.fingerprint")?.into(),
            serial: field(&fields, "ro.serialno")?.into(),
            product: product.into(),
            abi: field(&fields, "ro.product.cpu.abi")?.into(),
            sdk: field(&fields, "ro.build.version.sdk")?
                .parse()
                .map_err(message)?,
        };
        if !safe_fingerprint(&identity.fingerprint, 512)
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

/// Build fingerprints may also contain commas, for example `user/release-keys,test-keys`.
pub fn safe_fingerprint(value: &str, max: usize) -> bool {
    !value.is_empty()
        && value.len() <= max
        && value.as_bytes()[0].is_ascii_alphanumeric()
        && value
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || b"._:/+-,".contains(&c))
}

/// Enforcing, or Permissive only when the firmware's own kernel command line booted it that way. The same rule as
/// `configure-boot.sh`, `access-start.sh` and the app.
pub const SELINUX_READY: &str = "{ [ \"$(getenforce)\" = Enforcing ] || { [ \"$(getenforce)\" = Permissive ] && tr ' ' '\\n' < /proc/cmdline | grep -qx androidboot.selinux=permissive; }; }";

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

/// Owner builds may be debuggable and carry suffixed version names such as `0.20.1-polish`; their order is the
/// version code alone, so their parsed version is only for messages.
fn release_metadata(
    apk: &ApkInfo,
    sdk: u32,
    product: Product,
    build: Build,
) -> Result<(u64, Version)> {
    if apk.package != product.package() {
        return Err(format!(
            "Expected APK package {}; found {}.",
            product.package(),
            apk.package
        ));
    }
    if apk.split {
        return Err("Choose a standalone APK; split packages are not supported.".into());
    }
    if build == Build::Owner {
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
        let leading: String = apk
            .version_name
            .as_deref()
            .unwrap_or_default()
            .chars()
            .take_while(|c| c.is_ascii_digit() || *c == '.')
            .collect();
        let version = Version::parse(&leading).unwrap_or(Version::new(0, 0, 0));
        return Ok((code, version));
    }
    if apk.debuggable {
        return Err("Choose a standalone, non-debuggable production APK, or trust your own certificate for an owner build.".into());
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
    if package != PACKAGE && package != YOUTUBE_PACKAGE && !OEM_PACKAGES.contains(&package) {
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

/// An installed or candidate APK after its checks: version code, version, digest, signer and how it was trusted.
pub struct Checked {
    pub code: u64,
    pub version: Version,
    pub sha256: String,
    pub signer: String,
    pub build: Build,
}

/// Checks an installed copy of `product`: package, version, an accepted certificate and its bytes' digest.
fn verify_installed(
    target: &Target<'_>,
    product: Product,
    sdk: u32,
    remote: &str,
    storage: &Path,
    trust: &Trust,
) -> Result<Checked> {
    let current = pull_apk(target, remote, storage)?;
    let _locked = current.lock()?;
    let metadata = target.adb.apk_info(&current.path, target.stop)?;
    let signer = target.adb.apk_signer(&current.path, sdk, target.stop)?;
    let build = trust.classify(product, &signer).map_err(|_| match product {
        Product::Controls => "The installed Controls APK has a different signer. Export its settings and use the rollback workflow before replacing it.".to_string(),
        Product::YouTube => "The installed YouTube app has a different signer.".to_string(),
    })?;
    let (code, version) = release_metadata(&metadata, sdk, product, build)?;
    Ok(Checked {
        code,
        version,
        sha256: digest(&current.path, target.stop)?,
        signer,
        build,
    })
}

/// The checks every APK passes on the PC before it reaches the head unit: package, standalone, the official release
/// digest when it came from Releases, and an accepted certificate. A release build must also be non-debuggable with a
/// stable version; an owner build only needs a certificate the user trusted.
fn check_staged(
    target: &Target<'_>,
    staged: &ScratchFile,
    expected: Option<&ReleaseApk>,
    sdk: u32,
    product: Product,
    trust: &Trust,
) -> Result<Checked> {
    let metadata = target.adb.apk_info(&staged.path, target.stop)?;
    if metadata.package != product.package() {
        return Err(format!(
            "Expected APK package {}; found {}.",
            product.package(),
            metadata.package
        ));
    }
    let signer = target.adb.apk_signer(&staged.path, sdk, target.stop)?;
    let build = match trust.classify(product, &signer) {
        Ok(build) => build,
        Err(_) if product.signer().is_err() => return Err(product.signer().unwrap_err()),
        Err(_) => {
            return Err(format!(
                "The APK signer does not match the pinned {} release certificate.",
                product.name()
            ));
        }
    };
    let (code, version) = release_metadata(&metadata, sdk, product, build)?;
    let sha = digest(&staged.path, target.stop)?;
    if expected.is_some_and(|release| {
        !release.sha256.eq_ignore_ascii_case(&sha) || release.version != version
    }) {
        return Err("The selected APK bytes or version differ from the official release.".into());
    }
    Ok(Checked {
        code,
        version,
        sha256: sha,
        signer,
        build,
    })
}

/// True when `candidate` replaces `installed` with an older build. Owner builds compare version codes only.
fn is_downgrade(candidate: &Checked, installed: &Checked) -> bool {
    candidate.code < installed.code
        || (candidate.build == Build::Release
            && installed.build == Build::Release
            && candidate.version < installed.version)
}

/// Android reported an installation; the APK it now holds must be the verified input byte for byte.
fn verify_install(
    target: &Target<'_>,
    identity: &Identity,
    product: Product,
    sha: &str,
    storage: &Path,
) -> Result<()> {
    let verify = (|| {
        identity.recheck(target)?;
        let remote =
            apk_path(target, product.package())?.ok_or("The installed package path is missing.")?;
        let installed = pull_apk(target, &remote, storage)?;
        if digest(&installed.path, target.stop)? != sha {
            return Err("The installed APK differs from the verified input.".into());
        }
        Ok(())
    })();
    verify.map_err(|error: String| {
        format!("Android reported an installation. Device verification was incomplete. {error}")
    })
}

pub fn install_controls(
    target: &Target<'_>,
    source: &Path,
    expected: Option<&ReleaseApk>,
    storage: &Path,
    trust: &Trust,
    progress: impl Fn(String),
) -> Result<String> {
    install_product(
        target,
        source,
        expected,
        storage,
        trust,
        Product::Controls,
        progress,
    )
}

/// Installs or updates the YouTube web wrapper with the same checks as Controls. For Android it is an ordinary
/// app: no root, no rollback check and no change to the Controls APK, its runtime or its boot entry.
pub fn install_youtube(
    target: &Target<'_>,
    source: &Path,
    expected: Option<&ReleaseApk>,
    storage: &Path,
    trust: &Trust,
    progress: impl Fn(String),
) -> Result<String> {
    install_product(
        target,
        source,
        expected,
        storage,
        trust,
        Product::YouTube,
        progress,
    )
}

fn install_product(
    target: &Target<'_>,
    source: &Path,
    expected: Option<&ReleaseApk>,
    storage: &Path,
    trust: &Trust,
    product: Product,
    progress: impl Fn(String),
) -> Result<String> {
    let identity = Identity::read(target)?;
    require_owner_user(target)?;
    let name = product.name();
    progress(format!(
        "Checking the selected {name} APK and its certificate…"
    ));
    let staged = ScratchFile::copy_from(storage, source, target)?;
    let _locked = staged.lock()?;
    let candidate = check_staged(target, &staged, expected, identity.sdk, product, trust)?;
    let version = &candidate.version;
    let label = match candidate.build {
        Build::Release => "verified",
        Build::Owner => "owner-build",
    };
    let current = apk_path(target, product.package())?;
    if let Some(remote) = current.as_deref() {
        let installed = verify_installed(target, product, identity.sdk, remote, storage, trust).map_err(|error| match product {
            Product::Controls => format!("The existing APK could not be verified for an in-place update. Export settings, roll back Controls, then use Remove old app for signer migration. {error}"),
            Product::YouTube => format!("The installed YouTube app is signed by a certificate this PC does not accept, so it cannot be updated in place. Uninstall it on the head unit first; that also removes its sign-in and watch history. {error}"),
        })?;
        if installed.sha256 == candidate.sha256 {
            identity.recheck(target)?;
            return Ok(format!(
                "The {label} {name} {version} APK is already installed."
            ));
        }
        if !installed.signer.eq_ignore_ascii_case(&candidate.signer) {
            return Err(format!(
                "The installed {name} app is signed by certificate {}, the selected APK by {}. Android cannot update an app across certificates; nothing was changed.",
                installed.signer, candidate.signer
            ));
        }
        if is_downgrade(&candidate, &installed) {
            return Err(format!(
                "A newer {name} release is already installed. Its app data was left in place."
            ));
        }
    }
    // An active deployment sealed with the signer of Controls keeps running across a same-signer update that is not
    // older (checked above and by the supervisor), and the new app redeploys whatever agents changed. Anything else
    // needs the inactive state a rollback leaves.
    let live = product == Product::Controls
        && current.is_some()
        && recovery::accepts_live_update(target, &identity, candidate.code);
    if product == Product::Controls && !live {
        recovery::require_inactive(target, &identity, current.is_some())?;
    }
    identity.recheck(target)?;
    progress(format!("Installing {label} {name} {version}…"));
    target.install_owner(&staged.path).map_err(|error| {
        if error.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
            match product {
                Product::Controls => format!("Android retained a different app signer. Export settings and use the explicit signer migration in Maintenance after rollback. {error}"),
                Product::YouTube => format!("Android retained a YouTube app with a different signer. Uninstall it on the head unit first; that also removes its sign-in and watch history. {error}"),
            }
        } else {
            error
        }
    })?;
    verify_install(target, &identity, product, &candidate.sha256, storage)?;
    Ok(match product {
        Product::Controls if live => format!(
            "Controls {version} installed and verified ({label}) over the running agents, without a restart. The app redeploys changed agents within a minute; Device status shows when they are ready."
        ),
        Product::Controls => format!(
            "Controls {version} installed and verified ({label}). Check Device status separately for Frida and boot readiness."
        ),
        Product::YouTube => format!(
            "YouTube {version} installed and verified ({label}). Controls, its runtime and boot activation were not changed."
        ),
    })
}

pub fn check_candidate(
    target: &Target<'_>,
    source: &Path,
    expected: Option<&ReleaseApk>,
    storage: &Path,
    product: Product,
    trust: &Trust,
) -> Result<Checked> {
    let identity = Identity::read(target)?;
    let staged = ScratchFile::copy_from(storage, source, target)?;
    let _locked = staged.lock()?;
    check_staged(target, &staged, expected, identity.sdk, product, trust).map_err(|error| {
        format!(
            "The selected {} APK does not match the verified release and certificate. {error}",
            product.name()
        )
    })
}

/// The Controls copy Android holds now, checked against the certificates this PC accepts, or `None` when absent.
pub fn installed_controls(
    target: &Target<'_>,
    identity: &Identity,
    storage: &Path,
    trust: &Trust,
) -> Result<Option<Checked>> {
    match apk_path(target, PACKAGE)? {
        None => Ok(None),
        Some(remote) => verify_installed(
            target,
            Product::Controls,
            identity.sdk,
            &remote,
            storage,
            trust,
        )
        .map(Some),
    }
}

/// The version name Android reports for an installed `package`, or `None` when it is absent for user 0.
pub fn installed_version(target: &Target<'_>, package: &str) -> Result<Option<String>> {
    let output = target.script(
        "set -e\nif pm list packages --user 0 \"$1\" | grep -Fxq \"package:$1\"; then dumpsys package \"$1\" | sed -n 's/^ *versionName=//p' | head -n 1; else printf 'OVC_PACKAGE_ABSENT\\n'; fi\n",
        &[package],
        25,
    )?;
    if output == "OVC_PACKAGE_ABSENT" {
        return Ok(None);
    }
    let version = output.trim();
    if version.is_empty()
        || version.len() > 64
        || !version
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || b".+-_".contains(&c))
    {
        return Ok(Some("unknown version".into()));
    }
    Ok(Some(version.into()))
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
    if !safe_fingerprint(fingerprint, 256)
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
    // Operation rows are metadata: they mark an operation as verified on that firmware for the app's
    // Settings -> Allowed actions and never gate activation. Only their shape and identity columns are checked.
    for line in table_rows(operations) {
        let fields: Vec<_> = line.split('\t').collect();
        if fields.len() != 5 || fields[4].is_empty() {
            return Err("The reviewed operations catalog has an invalid row.".into());
        }
        review_key(&fields)?;
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

/// The reviewed profile for this exact unit: the catalog compiled into this build first, then the owner's local
/// review tables, if any.
fn reviewed_rows<'a>(identity: &Identity, trust: &'a Trust) -> Result<Vec<FirmwareRow<'a>>> {
    let mut first = None;
    for (catalog, operations) in trust.tables() {
        if catalog.is_empty() {
            continue;
        }
        match firmware_rows(catalog, operations, identity) {
            Ok(rows) => return Ok(rows),
            Err(error) => {
                first.get_or_insert(error);
            }
        }
    }
    Err(first.unwrap_or_else(|| "This exact firmware has no reviewed launcher and package profile. Production activation is unavailable.".into()))
}

pub fn has_review(identity: &Identity, trust: &Trust) -> bool {
    reviewed_rows(identity, trust).is_ok()
}

pub fn check_reviewed(
    target: &Target<'_>,
    identity: &Identity,
    storage: &Path,
    trust: &Trust,
) -> Result<()> {
    let rows = reviewed_rows(identity, trust)?;
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

pub fn verify_activation(
    target: &Target<'_>,
    identity: &Identity,
    storage: &Path,
    trust: &Trust,
) -> Result<()> {
    verify_preparation(target, identity, storage, trust)?;
    check_reviewed(target, identity, storage, trust)?;
    identity.recheck(target)
}

/// Provisioning may stage an inert runtime; OEM operations still need verify_activation.
pub fn verify_preparation(
    target: &Target<'_>,
    identity: &Identity,
    storage: &Path,
    trust: &Trust,
) -> Result<()> {
    check_setup_device(target, identity)?;
    require_root(target)?;
    let remote = apk_path(target, PACKAGE)?.ok_or("Install the verified Controls APK first.")?;
    verify_installed(
        target,
        Product::Controls,
        identity.sdk,
        &remote,
        storage,
        trust,
    )?;
    identity.recheck(target)
}

/// Read-only prerequisites are checked before requesting root or changing startup.
pub fn check_setup_device(target: &Target<'_>, identity: &Identity) -> Result<()> {
    require_owner_user(target)?;
    if !matches!(identity.sdk, 28 | 30) || identity.abi != "arm64-v8a" {
        return Err("Device setup supports Android 9 or 11 on ARM64.".into());
    }
    let selinux = target.script(
        &format!(
            "if {SELINUX_READY}; then echo OVC_SELINUX_READY; else echo OVC_SELINUX_OFF; fi
"
        ),
        &[],
        10,
    )?;
    if selinux != "OVC_SELINUX_READY" {
        return Err("Setup requires SELinux Enforcing, or Permissive only when the firmware's own kernel command line boots it that way.".into());
    }
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
        let operations = format!(
            "{}\t30\tarm64-v8a\tf0506h\tpassenger-window-dpi\n",
            identity.fingerprint
        );
        assert!(firmware_rows(&row, &operations, &identity).is_ok());
        let swipe = operations.replace("passenger-window-dpi", "window-display-move");
        assert!(firmware_rows(&row, &swipe, &identity).is_ok());
        // Operation rows only mark operations as verified: CAN rows without a service pin, both CAN
        // protocols, repeats and names this version does not know are all accepted.
        let can = operations.replace("passenger-window-dpi", "can-steering-terminal");
        let edges = can.replace("can-steering-terminal", "can-steering-edges");
        assert_eq!(
            firmware_rows(&row, &format!("{can}{edges}"), &identity)
                .unwrap()
                .len(),
            1
        );
        assert!(firmware_rows(&row, &operations.repeat(2), &identity).is_ok());
        assert!(
            firmware_rows(
                &row,
                &operations.replace("passenger-window-dpi", "media-restart"),
                &identity
            )
            .is_ok()
        );
        assert!(
            firmware_rows(
                &row,
                &operations.replace("	passenger-window-dpi", ""),
                &identity
            )
            .is_err()
        );
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

    #[test]
    fn owner_trust_comes_from_the_user_and_the_local_vehicle_profile() {
        let store = crate::device::new_folder(
            &Path::new(env!("CARGO_MANIFEST_DIR")).join("target"),
            "trust-store",
        )
        .unwrap();
        assert!(
            Trust::load(&store)
                .classify(Product::Controls, &"5".repeat(64))
                .is_err()
        );
        assert_eq!(
            Trust::release()
                .classify(Product::Controls, &"1".repeat(64))
                .unwrap(),
            Build::Release
        );
        fs::create_dir_all(store.join(LOCAL_VEHICLE)).unwrap();
        fs::write(
            store.join(LOCAL_VEHICLE).join("signer.sha256"),
            format!("{}\r\n", "A".repeat(64)),
        )
        .unwrap();
        Trust::remember(&store, &"5".repeat(64)).unwrap();
        assert!(Trust::remember(&store, "not-a-digest").is_err());
        let trust = Trust::load(&store);
        for signer in ["a".repeat(64), "5".repeat(64)] {
            assert_eq!(
                trust.classify(Product::Controls, &signer).unwrap(),
                Build::Owner
            );
        }
        // The pin still wins: a release-signed APK is a release even when the owner trusts other certificates.
        assert_eq!(
            trust.classify(Product::Controls, &"1".repeat(64)).unwrap(),
            Build::Release
        );
    }

    #[test]
    fn owner_builds_may_be_debuggable_with_suffixed_versions_but_releases_may_not() {
        let apk = ApkInfo {
            package: PACKAGE.into(),
            version_code: Some(30),
            version_name: Some("0.21.0-local".into()),
            min_sdk: Some(28),
            debuggable: true,
            split: false,
        };
        let (code, version) = release_metadata(&apk, 30, Product::Controls, Build::Owner).unwrap();
        assert_eq!((code, version), (30, Version::new(0, 21, 0)));
        assert!(release_metadata(&apk, 30, Product::Controls, Build::Release).is_err());
        let split = ApkInfo { split: true, ..apk };
        assert!(release_metadata(&split, 30, Product::Controls, Build::Owner).is_err());
        let owner = |code| Checked {
            code,
            version: Version::new(0, 0, 0),
            sha256: String::new(),
            signer: String::new(),
            build: Build::Owner,
        };
        assert!(is_downgrade(&owner(29), &owner(30)));
        assert!(
            !is_downgrade(&owner(30), &owner(30)),
            "a rebuild with the same code is an update"
        );
    }

    #[test]
    fn fingerprints_may_hold_commas_and_local_review_tables_count() {
        let identity = Identity {
            fingerprint:
                "qti/msmnile_gvmq/msmnile_gvmq:11/RQ3A.211001.001/97:user/jenkins-97,test-keys"
                    .into(),
            serial: "fixture".into(),
            product: "f6802h".into(),
            abi: "arm64-v8a".into(),
            sdk: 30,
        };
        assert!(safe_fingerprint(&identity.fingerprint, 512));
        assert!(!safe_id(&identity.fingerprint, 512));
        assert!(!safe_fingerprint("a;b", 512));
        let row = format!(
            "{}\t30\tarm64-v8a\tf6802h\tcom.qinggan.app.launcher\t{}\t{}\n",
            identity.fingerprint,
            "1".repeat(64),
            "2".repeat(64)
        );
        assert!(!has_review(&identity, &Trust::release()));
        let trust = Trust {
            catalog: format!("# local review\n{row}"),
            ..Trust::default()
        };
        assert!(has_review(&identity, &trust));
        assert_eq!(reviewed_rows(&identity, &trust).unwrap().len(), 1);
    }
}

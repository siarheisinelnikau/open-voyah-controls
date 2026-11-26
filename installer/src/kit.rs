use crate::{
    adb::Result,
    device::{message, new_folder},
    production::{Identity, ReleaseApk, safe_fingerprint, safe_id},
};
use ring::signature::{ECDSA_P256_SHA256_ASN1, UnparsedPublicKey};
use semver::Version;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::{
    collections::HashSet,
    fs::{self, File},
    io::{Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
    sync::atomic::{AtomicBool, Ordering},
};
use zip::{CompressionMethod, ZipArchive};

pub const MAX_KIT_BYTES: u64 = 641 * 1024 * 1024;
const MAX_APK_BYTES: u64 = 512 * 1024 * 1024;
pub const MAX_RUNTIME_BYTES: u64 = 128 * 1024 * 1024;
const MANIFEST: &str = "manifest.json";
const SIGNATURE: &str = "manifest.sig";
const RUNTIME: &str = "frida-inject";

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct Manifest {
    schema: u32,
    version: String,
    version_code: u32,
    compatibility: Vec<Firmware>,
    files: Vec<Payload>,
}

#[derive(Deserialize, Eq, Hash, PartialEq)]
#[serde(deny_unknown_fields)]
struct Firmware {
    fingerprint: String,
    sdk: u32,
    abi: String,
    product: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Payload {
    role: Role,
    name: String,
    size: u64,
    sha256: String,
}

#[derive(Clone, Copy, Deserialize, Eq, Hash, PartialEq)]
#[serde(rename_all = "kebab-case")]
enum Role {
    ControlsApk,
    FridaRuntime,
}

impl Manifest {
    fn version(&self) -> Result<Version> {
        let version = Version::parse(&self.version).map_err(message)?;
        if self.schema != 1
            || self.version.len() > 64
            || !version.pre.is_empty()
            || !version.build.is_empty()
            || !(1..=i32::MAX as u32).contains(&self.version_code)
        {
            return Err("The update kit has an unsupported schema or version.".into());
        }
        Ok(version)
    }

    fn validate(&self) -> Result<Version> {
        let version = self.version()?;
        let mut identities = HashSet::new();
        if !(1..=64).contains(&self.compatibility.len())
            || !self.compatibility.iter().all(|firmware| {
                matches!(firmware.sdk, 28 | 30)
                    && firmware.abi == "arm64-v8a"
                    && safe_fingerprint(&firmware.fingerprint, 512)
                    && safe_id(&firmware.product, 128)
                    && identities.insert(firmware)
            })
        {
            return Err("The update kit has an invalid or ambiguous compatibility list.".into());
        }
        let apk_name = format!("OpenVoyahControls-v{version}.apk");
        let mut roles = HashSet::new();
        if self.files.len() != 2
            || !self.files.iter().all(|file| {
                let (name, max) = match file.role {
                    Role::ControlsApk => (apk_name.as_str(), MAX_APK_BYTES),
                    Role::FridaRuntime => (RUNTIME, MAX_RUNTIME_BYTES),
                };
                file.name == name
                    && (1..=max).contains(&file.size)
                    && lower_hex(&file.sha256, 64)
                    && roles.insert(file.role)
            })
        {
            return Err(
                "The update kit must name exactly one Controls APK and one Frida injector.".into(),
            );
        }
        Ok(version)
    }
}

pub struct StagedKit {
    directory: PathBuf,
    pub apk: PathBuf,
    pub runtime: PathBuf,
    pub release: ReleaseApk,
    pub runtime_sha256: String,
    pub runtime_size: u64,
    compatibility: Vec<Firmware>,
}

impl StagedKit {
    pub fn check_compatibility(&self, identity: &Identity) -> Result<()> {
        if self.compatibility.iter().any(|firmware| {
            firmware.fingerprint == identity.fingerprint
                && firmware.product == identity.product
                && firmware.abi == identity.abi
                && firmware.sdk == identity.sdk
        }) {
            Ok(())
        } else {
            Err("This signed update kit does not include the exact firmware of the selected head unit.".into())
        }
    }
}

impl Drop for StagedKit {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.apk);
        let _ = fs::remove_file(&self.runtime);
        let _ = fs::remove_dir(&self.directory);
    }
}

fn lower_hex(value: &str, length: usize) -> bool {
    value.len() == length
        && value
            .bytes()
            .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
}

fn pinned_key() -> Option<Vec<u8>> {
    let pin = option_env!("OPEN_VOYAH_KIT_PUBLIC_KEY_HEX")?;
    if !pin.starts_with("04") || !lower_hex(pin, 130) {
        return None;
    }
    (0..130)
        .step_by(2)
        .map(|index| u8::from_str_radix(&pin[index..index + 2], 16).ok())
        .collect()
}

pub fn supported() -> bool {
    pinned_key().is_some()
}

pub fn release_version(name: &str) -> Option<Version> {
    let label = name
        .strip_prefix("OpenVoyahControlsKit-v")?
        .strip_suffix(".zip")?;
    let version = Version::parse(label).ok()?;
    (label.len() <= 64 && version.pre.is_empty() && version.build.is_empty()).then_some(version)
}

pub fn stage(
    source: &Path,
    root: &Path,
    stop: &AtomicBool,
    release_digest: Option<&str>,
    progress: impl Fn(String),
) -> Result<StagedKit> {
    let key = pinned_key().ok_or("This installer build has no update-kit signing key. Use a production installer with the published pin.")?;
    stage_with_key(source, root, stop, release_digest, progress, &key)
}

fn stage_with_key(
    source: &Path,
    root: &Path,
    stop: &AtomicBool,
    release_digest: Option<&str>,
    progress: impl Fn(String),
    key: &[u8],
) -> Result<StagedKit> {
    check_canceled(stop)?;
    progress("Checking the update kit signature…".into());
    let claimed_version = source
        .file_name()
        .and_then(|name| name.to_str())
        .and_then(release_version);
    let mut options = File::options();
    options.read(true);
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        options.share_mode(1); // FILE_SHARE_READ
    }
    let mut source = options.open(source).map_err(message)?;
    let metadata = source.metadata().map_err(message)?;
    if !metadata.is_file() || !(22..=MAX_KIT_BYTES).contains(&metadata.len()) {
        return Err("The update kit file is empty or exceeds 641 MiB.".into());
    }
    if let Some(digest) = release_digest {
        let mut buffer = [0u8; 64 * 1024];
        let mut hasher = Sha256::new();
        loop {
            check_canceled(stop)?;
            let count = source.read(&mut buffer).map_err(message)?;
            if count == 0 {
                break;
            }
            hasher.update(&buffer[..count]);
        }
        if !format!("{:x}", hasher.finalize()).eq_ignore_ascii_case(digest) {
            return Err("The update kit differs from the official release SHA-256.".into());
        }
    }
    // zip::ZipArchive indexes by name. Check the actual record count as well as the map size
    // so a central directory with duplicate names cannot silently collapse to four entries.
    source.seek(SeekFrom::End(-22)).map_err(message)?;
    let mut footer = [0u8; 22];
    source.read_exact(&mut footer).map_err(message)?;
    let directory_size = u32::from_le_bytes(footer[12..16].try_into().unwrap()) as u64;
    let directory_start = u32::from_le_bytes(footer[16..20].try_into().unwrap()) as u64;
    if footer[..12] != [0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0, 4, 0, 4, 0]
        || footer[20..] != [0, 0]
        || directory_start + directory_size + 22 != metadata.len()
    {
        return Err("The update kit must be a complete four-member ZIP without a comment.".into());
    }
    let mut archive = ZipArchive::new(source).map_err(message)?;
    if archive.len() != 4 || archive.offset() != 0 || archive.zip64_comment().is_some() {
        return Err("The update kit has an invalid ZIP directory.".into());
    }
    let manifest = read_metadata(&mut archive, MANIFEST, 1, 64 * 1024)?;
    let signature = read_metadata(&mut archive, SIGNATURE, 8, 72)?;
    UnparsedPublicKey::new(&ECDSA_P256_SHA256_ASN1, key)
        .verify(&manifest, &signature)
        .map_err(|_| "The update kit signature does not match the pinned signing key.")?;
    let manifest: Manifest = serde_json::from_slice(&manifest).map_err(message)?;
    let version = manifest.validate()?;
    if claimed_version.is_some_and(|claimed| claimed != version) {
        return Err("The kit version in the file name differs from the signed version.".into());
    }
    let apk = manifest
        .files
        .iter()
        .find(|file| file.role == Role::ControlsApk)
        .unwrap();
    let runtime = manifest
        .files
        .iter()
        .find(|file| file.role == Role::FridaRuntime)
        .unwrap();
    let directory = new_folder(root, "kit-artifact")?;
    let staged = StagedKit {
        apk: directory.join(&apk.name),
        runtime: directory.join(&runtime.name),
        directory,
        release: ReleaseApk {
            version,
            sha256: apk.sha256.clone(),
            version_code: Some(manifest.version_code.into()),
        },
        runtime_sha256: runtime.sha256.clone(),
        runtime_size: runtime.size,
        compatibility: manifest.compatibility,
    };
    for (payload, path) in [(apk, &staged.apk), (runtime, &staged.runtime)] {
        progress(format!("Verifying {}…", payload.name));
        let mut entry = archive.by_name(&payload.name).map_err(message)?;
        check_entry(&entry, &payload.name, payload.size, payload.size)?;
        let mut output = File::options()
            .write(true)
            .create_new(true)
            .open(path)
            .map_err(message)?;
        let mut buffer = [0u8; 64 * 1024];
        let mut hasher = Sha256::new();
        let mut size = 0;
        loop {
            check_canceled(stop)?;
            let count = entry.read(&mut buffer).map_err(message)?;
            if count == 0 {
                break;
            }
            size += count as u64;
            if size > payload.size {
                return Err(format!(
                    "The update kit member {} is oversized.",
                    payload.name
                ));
            }
            hasher.update(&buffer[..count]);
            output.write_all(&buffer[..count]).map_err(message)?;
        }
        if size != payload.size || format!("{:x}", hasher.finalize()) != payload.sha256 {
            return Err(format!(
                "The update kit member {} does not match its signed size and SHA-256.",
                payload.name
            ));
        }
    }
    Ok(staged)
}

fn check_entry(entry: &zip::read::ZipFile<'_, File>, name: &str, min: u64, max: u64) -> Result<()> {
    if entry.name_raw() != name.as_bytes()
        || !entry.is_file()
        || entry.encrypted()
        || entry.compression() != CompressionMethod::Stored
        || entry.size() != entry.compressed_size()
        || !(min..=max).contains(&entry.size())
    {
        return Err(format!(
            "The update kit member {name} has an invalid name, size or compression method."
        ));
    }
    Ok(())
}

fn read_metadata(
    archive: &mut ZipArchive<File>,
    name: &str,
    min: u64,
    max: u64,
) -> Result<Vec<u8>> {
    let entry = archive.by_name(name).map_err(message)?;
    check_entry(&entry, name, min, max)?;
    let mut bytes = Vec::new();
    entry
        .take(max + 1)
        .read_to_end(&mut bytes)
        .map_err(message)?;
    if !(min..=max).contains(&(bytes.len() as u64)) {
        return Err(format!("The update kit member {name} has an invalid size."));
    }
    Ok(bytes)
}

fn check_canceled(stop: &AtomicBool) -> Result<()> {
    if stop.load(Ordering::Relaxed) {
        Err("Update kit check canceled before changing the head unit.".into())
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ring::{
        rand::SystemRandom,
        signature::{ECDSA_P256_SHA256_ASN1_SIGNING, EcdsaKeyPair, KeyPair},
    };
    use serde_json::{Value, json};
    use zip::{ZipWriter, write::SimpleFileOptions};

    const APK: &str = "OpenVoyahControls-v0.21.0.apk";
    const FINGERPRINT: &str = "fixture/product/device:11/TEST/1:user/release-keys";
    const RUNTIME_BYTES: &[u8] = b"runtime fixture";
    const APK_BYTES: &[u8] = b"apk fixture";

    fn key() -> EcdsaKeyPair {
        let rng = SystemRandom::new();
        let pkcs8 = EcdsaKeyPair::generate_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, &rng).unwrap();
        EcdsaKeyPair::from_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, pkcs8.as_ref(), &rng).unwrap()
    }

    fn manifest() -> Value {
        json!({
            "schema": 1,
            "version": "0.21.0",
            "versionCode": 21,
            "compatibility": [{
                "fingerprint": FINGERPRINT,
                "sdk": 30,
                "product": "fixture-test",
                "abi": "arm64-v8a"
            }],
            "files": [
                { "role": "controls-apk", "name": APK, "size": APK_BYTES.len(),
                  "sha256": format!("{:x}", Sha256::digest(APK_BYTES)) },
                { "role": "frida-runtime", "name": RUNTIME, "size": RUNTIME_BYTES.len(),
                  "sha256": format!("{:x}", Sha256::digest(RUNTIME_BYTES)) }
            ]
        })
    }

    fn archive(
        folder: &Path,
        key: &EcdsaKeyPair,
        write_manifest: Value,
        runtime: &[u8],
        extra: bool,
    ) -> PathBuf {
        let archive = folder.join("OpenVoyahControlsKit-v0.21.0.zip");
        let mut writer = ZipWriter::new(File::create(&archive).unwrap());
        let manifest = serde_json::to_vec(&write_manifest).unwrap();
        let signature = key.sign(&SystemRandom::new(), &manifest).unwrap();
        let settings = SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
        for (name, bytes) in [
            (MANIFEST, manifest.as_slice()),
            (SIGNATURE, signature.as_ref()),
            (APK, APK_BYTES),
            (RUNTIME, runtime),
        ] {
            writer.start_file(name, settings).unwrap();
            writer.write_all(bytes).unwrap();
        }
        if extra {
            writer.start_file("unlisted", settings).unwrap();
        }
        writer.finish().unwrap();
        archive
    }

    fn fixture() -> PathBuf {
        new_folder(
            &Path::new(env!("CARGO_MANIFEST_DIR")).join("target"),
            "kit-test",
        )
        .unwrap()
    }

    fn verify(path: &Path, folder: &Path, key: &EcdsaKeyPair) -> Result<StagedKit> {
        stage_with_key(
            path,
            folder,
            &AtomicBool::new(false),
            None,
            |_| {},
            key.public_key().as_ref(),
        )
    }

    #[test]
    fn authentic_kit_binds_payloads_version_and_exact_head_unit() {
        let folder = fixture();
        let key = key();
        let zip = archive(&folder, &key, manifest(), RUNTIME_BYTES, false);
        let staged = verify(&zip, &folder, &key).unwrap();
        assert_eq!(staged.release.version, Version::new(0, 21, 0));
        assert_eq!(staged.release.version_code, Some(21));
        assert_eq!(fs::read(&staged.apk).unwrap(), APK_BYTES);
        assert_eq!(fs::read(&staged.runtime).unwrap(), RUNTIME_BYTES);
        let mut identity = Identity {
            fingerprint: FINGERPRINT.into(),
            serial: "fixture".into(),
            product: "fixture-test".into(),
            abi: "arm64-v8a".into(),
            sdk: 30,
        };
        staged.check_compatibility(&identity).unwrap();
        identity.sdk = 28;
        assert!(staged.check_compatibility(&identity).is_err());
        let staged_dir = staged.directory.clone();
        drop(staged);
        assert!(!staged_dir.exists());
    }

    #[test]
    fn unauthorized_signer_and_unlisted_files_stop_before_staging() {
        let folder = fixture();
        let untrusted = key();
        let trusted = key();
        let zip = archive(&folder, &untrusted, manifest(), RUNTIME_BYTES, false);
        assert!(
            matches!(verify(&zip, &folder, &trusted), Err(error) if error.contains("signing key"))
        );
        let zip = archive(&folder, &trusted, manifest(), RUNTIME_BYTES, true);
        assert!(
            matches!(verify(&zip, &folder, &trusted), Err(error) if error.contains("four-member"))
        );
        assert_eq!(fs::read_dir(&folder).unwrap().count(), 1);
    }

    #[test]
    fn verified_archive_hash_and_signed_payload_digests_are_independent_guards() {
        let folder = fixture();
        let key = key();
        let zip = archive(&folder, &key, manifest(), b"runtime altered", false);
        let error = stage_with_key(
            &zip,
            &folder,
            &AtomicBool::new(false),
            Some(&"0".repeat(64)),
            |_| {},
            key.public_key().as_ref(),
        );
        assert!(matches!(error, Err(error) if error.contains("official release SHA-256")));
        assert!(
            matches!(verify(&zip, &folder, &key), Err(error) if error.contains("signed size and SHA-256"))
        );
        assert_eq!(fs::read_dir(&folder).unwrap().count(), 1);
    }

    #[test]
    fn signed_invalid_schema_and_duplicate_device_rows_stop_before_staging() {
        let folder = fixture();
        let key = key();
        let mut duplicate = manifest();
        let targets = duplicate["compatibility"].as_array_mut().unwrap();
        targets.push(targets[0].clone());
        let mut unknown = manifest();
        unknown["unexpected"] = json!(true);
        for invalid in [duplicate, unknown] {
            let zip = archive(&folder, &key, invalid, RUNTIME_BYTES, false);
            assert!(verify(&zip, &folder, &key).is_err());
            assert_eq!(fs::read_dir(&folder).unwrap().count(), 1);
        }
    }
}

use crate::{adb::Result, device::message};
use semver::Version;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::atomic::{AtomicBool, Ordering},
    time::{Duration, Instant},
};

const API: &str =
    "https://api.github.com/repos/siarheisinelnikau/open-voyah-controls/releases?per_page=30";
const DOWNLOAD: &str =
    "https://github.com/siarheisinelnikau/open-voyah-controls/releases/download/";

#[derive(Clone, Debug, Deserialize)]
pub struct Asset {
    pub name: String,
    browser_download_url: String,
    size: u64,
    digest: Option<String>,
}

#[derive(Deserialize)]
struct Release {
    tag_name: String,
    draft: bool,
    prerelease: bool,
    assets: Vec<Asset>,
}

#[derive(Default)]
pub struct Catalog {
    pub apk: Vec<(Version, Asset)>,
    pub installer: Option<(Version, Asset)>,
}

fn agent(seconds: u64) -> ureq::Agent {
    ureq::Agent::config_builder()
        .timeout_global(Some(Duration::from_secs(seconds)))
        .https_only(true)
        .build()
        .into()
}

fn stable_asset(name: &str, prefix: &str, extension: &str) -> Option<Version> {
    let version = Version::parse(name.strip_prefix(prefix)?.strip_suffix(extension)?).ok()?;
    (version.pre.is_empty() && version.build.is_empty()).then_some(version)
}

fn valid_asset(asset: &Asset, tag: &str) -> bool {
    !tag.is_empty()
        && tag
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'_'))
        && asset.browser_download_url == format!("{DOWNLOAD}{tag}/{}", asset.name)
        && asset.size > 0
        && asset.size <= 512 * 1024 * 1024
        && checksum(asset).is_some()
}

pub fn checksum(asset: &Asset) -> Option<&str> {
    let value = asset.digest.as_deref()?.strip_prefix("sha256:")?;
    (value.len() == 64 && value.bytes().all(|b| b.is_ascii_hexdigit())).then_some(value)
}

pub fn apk_version(asset: &Asset) -> Option<Version> {
    stable_asset(&asset.name, "OpenVoyahControls-v", ".apk")
}

pub fn check() -> Result<Catalog> {
    let mut response = agent(15)
        .get(API)
        .header("User-Agent", "OpenVoyahControlsInstaller")
        .header("Accept", "application/vnd.github+json")
        .call()
        .map_err(message)?;
    let releases: Vec<Release> = response
        .body_mut()
        .with_config()
        .limit(4 * 1024 * 1024)
        .read_json()
        .map_err(message)?;
    let installed = Version::parse(env!("CARGO_PKG_VERSION")).map_err(message)?;
    let mut catalog = Catalog::default();
    for release in releases.into_iter().filter(|r| !r.draft && !r.prerelease) {
        for asset in release
            .assets
            .into_iter()
            .filter(|a| valid_asset(a, &release.tag_name))
        {
            if let Some(version) = apk_version(&asset) {
                catalog.apk.push((version, asset));
            } else if let Some(version) =
                stable_asset(&asset.name, "OpenVoyahControlsInstaller-v", ".exe")
                && version > installed
                && catalog.installer.as_ref().is_none_or(|(v, _)| version > *v)
            {
                catalog.installer = Some((version, asset));
            }
        }
    }
    catalog.apk.sort_by(|(a, _), (b, _)| b.cmp(a));
    catalog.apk.dedup_by(|(a, _), (b, _)| a == b);
    Ok(catalog)
}

pub fn download(
    asset: &Asset,
    folder: &Path,
    stop: &AtomicBool,
    progress: impl Fn(String),
) -> Result<PathBuf> {
    fs::create_dir_all(folder).map_err(message)?;
    let path = folder.join(&asset.name);
    let expected = checksum(asset).ok_or("The release has no verified SHA-256.")?;
    if path.is_file() && crate::device::digest(&path, stop)?.eq_ignore_ascii_case(expected) {
        return Ok(path);
    }
    let staged = path.with_extension("part");
    let mut output = File::options()
        .write(true)
        .create_new(true)
        .open(&staged)
        .map_err(message)?;
    let result = (|| {
        let mut response = agent(300)
            .get(&asset.browser_download_url)
            .header("User-Agent", "OpenVoyahControlsInstaller")
            .call()
            .map_err(message)?;
        let mut source = response
            .body_mut()
            .with_config()
            .limit(asset.size + 1)
            .reader();
        let mut buffer = [0u8; 64 * 1024];
        let mut hasher = Sha256::new();
        let mut written = 0;
        let mut last = Instant::now() - Duration::from_secs(1);
        loop {
            if stop.load(Ordering::Relaxed) {
                return Err("Download canceled.".into());
            }
            let count = source.read(&mut buffer).map_err(message)?;
            if count == 0 {
                break;
            }
            written += count as u64;
            if written > asset.size {
                return Err("The download is larger than the release asset.".into());
            }
            hasher.update(&buffer[..count]);
            output.write_all(&buffer[..count]).map_err(message)?;
            if last.elapsed() >= Duration::from_millis(300) {
                progress(format!(
                    "Downloading {} • {}%",
                    asset.name,
                    written * 100 / asset.size
                ));
                last = Instant::now();
            }
        }
        if written != asset.size
            || !format!("{:x}", hasher.finalize()).eq_ignore_ascii_case(expected)
        {
            return Err("The downloaded file did not match the release size or SHA-256.".into());
        }
        output.sync_all().map_err(message)
    })();
    drop(output);
    if let Err(error) = result {
        let _ = fs::remove_file(&staged);
        return Err(error);
    }
    if let Err(error) = fs::rename(&staged, &path) {
        let _ = fs::remove_file(&staged);
        return Err(message(error));
    }
    Ok(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn selects_only_exact_stable_asset_names() {
        let parse = |s| stable_asset(s, "OpenVoyahControlsInstaller-v", ".exe");
        assert_eq!(
            parse("OpenVoyahControlsInstaller-v1.12.3.exe"),
            Some(Version::new(1, 12, 3))
        );
        for invalid in [
            "foo.exe",
            "OpenVoyahControlsInstaller-v1.12.3-rc.1.exe",
            "OpenVoyahControlsInstaller-v1.12.3-x86.exe",
            "OpenVoyahControlsInstaller-v1.12.3.apk",
        ] {
            assert!(parse(invalid).is_none());
        }
    }
}

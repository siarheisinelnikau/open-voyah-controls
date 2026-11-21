use crate::{adb::Result, device::message};
use semver::Version;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::atomic::{AtomicBool, AtomicU64, Ordering},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
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

static DOWNLOAD_SEQUENCE: AtomicU64 = AtomicU64::new(0);

struct DownloadStage {
    path: PathBuf,
    file: Option<File>,
    published: bool,
}

impl DownloadStage {
    fn create(folder: &Path) -> Result<Self> {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(message)?
            .as_nanos();
        for _ in 0..64 {
            let sequence = DOWNLOAD_SEQUENCE.fetch_add(1, Ordering::Relaxed);
            let path = folder.join(format!(
                ".ovc-download-{}-{stamp}-{sequence}.part",
                std::process::id()
            ));
            match File::options().write(true).create_new(true).open(&path) {
                Ok(file) => {
                    return Ok(Self {
                        path,
                        file: Some(file),
                        published: false,
                    });
                }
                Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
                Err(error) => return Err(message(error)),
            }
        }
        Err("Could not reserve a unique download staging file.".into())
    }

    fn publish(mut self, destination: &Path) -> Result<()> {
        drop(self.file.take());
        fs::rename(&self.path, destination).map_err(message)?;
        self.published = true;
        Ok(())
    }
}

impl Drop for DownloadStage {
    fn drop(&mut self) {
        drop(self.file.take());
        if !self.published {
            let _ = fs::remove_file(&self.path);
        }
    }
}

pub fn download(
    asset: &Asset,
    folder: &Path,
    stop: &AtomicBool,
    progress: impl Fn(String),
) -> Result<PathBuf> {
    download_from(asset, folder, stop, progress, || {
        let response = agent(300)
            .get(&asset.browser_download_url)
            .header("User-Agent", "OpenVoyahControlsInstaller")
            .call()
            .map_err(message)?;
        Ok(response
            .into_body()
            .into_with_config()
            .limit(asset.size + 1)
            .reader())
    })
}

fn download_from<R: Read>(
    asset: &Asset,
    folder: &Path,
    stop: &AtomicBool,
    progress: impl Fn(String),
    fetch: impl FnOnce() -> Result<R>,
) -> Result<PathBuf> {
    if stop.load(Ordering::Relaxed) {
        return Err("Download canceled.".into());
    }
    fs::create_dir_all(folder).map_err(message)?;
    let path = folder.join(&asset.name);
    let expected = checksum(asset).ok_or("The release has no verified SHA-256.")?;
    if path.is_file() && crate::device::digest(&path, stop)?.eq_ignore_ascii_case(expected) {
        return Ok(path);
    }
    let mut staged = DownloadStage::create(folder)?;
    let result = (|| {
        if stop.load(Ordering::Relaxed) {
            return Err("Download canceled.".into());
        }
        let mut source = fetch()?;
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
            staged
                .file
                .as_mut()
                .expect("owned download staging file")
                .write_all(&buffer[..count])
                .map_err(message)?;
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
        staged
            .file
            .as_ref()
            .expect("owned download staging file")
            .sync_all()
            .map_err(message)
    })();
    result?;
    staged.publish(&path)?;
    Ok(path)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{cell::Cell, io::Cursor, sync::mpsc, thread};

    fn cache() -> PathBuf {
        crate::device::new_folder(
            &Path::new(env!("CARGO_MANIFEST_DIR")).join("target"),
            "download-fixture",
        )
        .unwrap()
    }

    fn fixture_asset(bytes: &[u8]) -> Asset {
        Asset {
            name: "OpenVoyahControlsInstaller-v99.0.0.exe".into(),
            browser_download_url: "file:///no-network-in-download-fixtures".into(),
            size: bytes.len() as u64,
            digest: Some(format!("sha256:{:x}", Sha256::digest(bytes))),
        }
    }

    fn contents(folder: &Path) -> Vec<PathBuf> {
        let mut paths: Vec<_> = fs::read_dir(folder)
            .unwrap()
            .map(|entry| entry.unwrap().path())
            .collect();
        paths.sort();
        paths
    }

    #[test]
    fn abandoned_partial_does_not_block_verified_retry_or_cache_reuse() {
        let folder = cache();
        let bytes = b"verified installer fixture";
        let asset = fixture_asset(bytes);
        let abandoned = folder.join(&asset.name).with_extension("part");
        fs::write(&abandoned, b"unknown interrupted download").unwrap();
        let fetches = Cell::new(0);
        for _ in 0..2 {
            let path = download_from(
                &asset,
                &folder,
                &AtomicBool::new(false),
                |_| {},
                || {
                    fetches.set(fetches.get() + 1);
                    Ok(Cursor::new(bytes))
                },
            )
            .unwrap();
            assert_eq!(fs::read(path).unwrap(), bytes);
        }
        assert_eq!(fetches.get(), 1);
        assert_eq!(
            fs::read(&abandoned).unwrap(),
            b"unknown interrupted download"
        );
        assert_eq!(contents(&folder).len(), 2);
    }

    #[test]
    fn concurrent_attempts_preserve_live_and_unknown_partials() {
        let folder = cache();
        let unknown = folder.join("someone-else.part");
        fs::write(&unknown, b"leave this file alone").unwrap();
        let bytes = b"verified concurrent installer";
        let asset = fixture_asset(bytes);
        let (started_tx, started_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel();
        let first_asset = asset.clone();
        let first_folder = folder.clone();
        let first = thread::spawn(move || {
            download_from(
                &first_asset,
                &first_folder,
                &AtomicBool::new(false),
                |_| {},
                || {
                    started_tx.send(contents(&first_folder)).map_err(message)?;
                    release_rx
                        .recv_timeout(Duration::from_secs(5))
                        .map_err(message)?;
                    Ok(Cursor::new(bytes))
                },
            )
        });
        let first_files = started_rx.recv_timeout(Duration::from_secs(5)).unwrap();
        let first_partial = first_files.iter().find(|path| **path != unknown).unwrap();
        let mut second_files = Vec::new();
        let failed = download_from(
            &asset,
            &folder,
            &AtomicBool::new(false),
            |_| {},
            || {
                second_files = contents(&folder);
                Err::<Cursor<&[u8]>, _>("Fixture response timeout".into())
            },
        );
        let after_failure = contents(&folder);
        let completed = download_from(
            &asset,
            &folder,
            &AtomicBool::new(false),
            |_| {},
            || Ok(Cursor::new(bytes)),
        );
        let first_survived = first_partial.is_file();
        let _ = release_tx.send(());
        let first_completed = first.join().unwrap();

        assert!(matches!(failed, Err(error) if error == "Fixture response timeout"));
        assert_eq!(first_files.len(), 2);
        assert_eq!(second_files.len(), 3);
        assert_eq!(after_failure, first_files);
        assert!(first_survived);
        assert_eq!(fs::read(completed.unwrap()).unwrap(), bytes);
        assert_eq!(fs::read(first_completed.unwrap()).unwrap(), bytes);
        assert_eq!(fs::read(&unknown).unwrap(), b"leave this file alone");
        assert_eq!(contents(&folder).len(), 2);
    }

    #[test]
    fn canceled_download_does_not_open_the_transport() {
        let folder = cache();
        let unknown = folder.join("someone-else.part");
        fs::write(&unknown, b"preserved").unwrap();
        let bytes = b"unused fixture";
        let fetches = Cell::new(0);
        let result = download_from(
            &fixture_asset(bytes),
            &folder,
            &AtomicBool::new(true),
            |_| {},
            || {
                fetches.set(fetches.get() + 1);
                Ok(Cursor::new(bytes))
            },
        );
        assert!(matches!(result, Err(error) if error.contains("canceled")));
        assert_eq!(fetches.get(), 0);
        assert_eq!(contents(&folder), [unknown]);
    }

    #[test]
    fn midstream_cancellation_cleans_only_its_owned_partial() {
        let folder = cache();
        let unknown = folder.join("someone-else.part");
        fs::write(&unknown, b"preserved").unwrap();
        let bytes = vec![0x35; 128 * 1024];
        let stop = AtomicBool::new(false);
        let result = download_from(
            &fixture_asset(&bytes),
            &folder,
            &stop,
            |_| stop.store(true, Ordering::Relaxed),
            || Ok(Cursor::new(&bytes)),
        );
        assert!(matches!(result, Err(error) if error.contains("canceled")));
        assert_eq!(contents(&folder), std::slice::from_ref(&unknown));
        assert_eq!(fs::read(unknown).unwrap(), b"preserved");
    }

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

use std::{
    ffi::{OsStr, OsString},
    io::{self, Read, Write},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::atomic::{AtomicBool, Ordering},
    thread,
    time::{Duration, Instant},
};

pub type Result<T> = std::result::Result<T, String>;

#[derive(Clone, Debug)]
pub struct Device {
    pub serial: String,
    pub state: String,
    pub model: String,
    pub transport: Option<u64>,
}

impl Device {
    pub fn ready(&self) -> bool {
        self.state == "device" && self.transport.is_some()
    }

    pub fn label(&self) -> String {
        let state = match self.state.as_str() {
            "device" if self.ready() => "ADB authorized",
            "device" => "Update Platform-Tools",
            "unauthorized" => "Authorize debugging on the device",
            "offline" => "Offline",
            _ => &self.state,
        };
        if self.model.is_empty() {
            format!("{}  •  {state}", self.serial)
        } else {
            format!("{}  •  {}  •  {state}", self.model, self.serial)
        }
    }
}

pub fn parse_devices(text: &str) -> Vec<Device> {
    text.lines()
        .filter_map(|line| {
            let mut parts = line.split_whitespace();
            let serial = parts.next()?;
            let state = parts.next()?;
            if !matches!(
                state,
                "device" | "offline" | "unauthorized" | "recovery" | "sideload" | "no"
            ) || serial.starts_with('-')
            {
                return None;
            }
            let mut model = String::new();
            let mut transport = None;
            for part in parts {
                if let Some(value) = part.strip_prefix("model:") {
                    model = value.replace('_', " ");
                } else if let Some(value) = part.strip_prefix("transport_id:") {
                    transport = value.parse().ok();
                }
            }
            Some(Device {
                serial: serial.to_owned(),
                state: state.to_owned(),
                model,
                transport,
            })
        })
        .collect()
}

pub fn endpoint(input: &str) -> Result<String> {
    let input = input.trim();
    let (host, port) = input.rsplit_once(':').unwrap_or((input, "5555"));
    let ipv6 = host.strip_prefix('[').and_then(|s| s.strip_suffix(']'));
    let valid_host = if let Some(ipv6) = ipv6 {
        ipv6.parse::<std::net::Ipv6Addr>().is_ok()
    } else {
        !host.is_empty()
            && host.len() <= 253
            && host.split('.').all(|part| {
                !part.is_empty()
                    && !part.starts_with('-')
                    && !part.ends_with('-')
                    && part.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'-')
            })
    };
    let port = port.parse::<u16>().ok().filter(|p| *p > 0);
    match (valid_host, port) {
        (true, Some(port)) => Ok(format!("{host}:{port}")),
        _ => Err("Enter a host and optional port, for example 192.168.1.20:5555.".into()),
    }
}

#[derive(Clone)]
pub struct Adb {
    pub path: PathBuf,
}

pub struct ApkInfo {
    pub package: String,
    pub version_code: Option<u64>,
    pub version_name: Option<String>,
    pub min_sdk: Option<u32>,
    pub debuggable: bool,
    pub split: bool,
}

impl Adb {
    pub fn from_path(path: PathBuf) -> Result<Self> {
        if !path.is_absolute()
            || !path
                .file_name()
                .is_some_and(|n| n.eq_ignore_ascii_case("adb.exe"))
        {
            return Err("Select adb.exe from an Android SDK Platform-Tools directory.".into());
        }
        let path = path
            .canonicalize()
            .map_err(|e| format!("Could not open adb.exe. {e}"))?;
        if !path.is_file() {
            return Err("The selected adb.exe is missing.".into());
        }
        let directory = path
            .parent()
            .ok_or("Android SDK Platform-Tools directory is missing.")?;
        for library in ["AdbWinApi.dll", "AdbWinUsbApi.dll"] {
            if !directory.join(library).is_file() {
                return Err(format!(
                    "Missing {library}. Keep adb.exe and both DLLs together from one Platform-Tools installation."
                ));
            }
        }
        Ok(Self { path })
    }

    pub fn discover() -> Option<Self> {
        let portable = std::env::current_exe()
            .ok()
            .and_then(|exe| exe.parent().map(|directory| directory.join("android-sdk")));
        let roots = portable
            .into_iter()
            .chain(
                ["ANDROID_HOME", "ANDROID_SDK_ROOT"]
                    .into_iter()
                    .filter_map(std::env::var_os)
                    .map(PathBuf::from),
            )
            .chain(std::env::var_os("LOCALAPPDATA").map(|p| PathBuf::from(p).join("Android/Sdk")))
            .chain([PathBuf::from("C:/Android/Sdk")]);
        roots
            .map(|p| p.join("platform-tools/adb.exe"))
            .find_map(|path| Self::from_path(path).ok())
    }

    pub fn devices(&self, stop: &AtomicBool) -> Result<Vec<Device>> {
        Ok(parse_devices(&self.text(["devices", "-l"], 15, stop)?))
    }

    pub fn text<S: AsRef<OsStr>>(
        &self,
        args: impl IntoIterator<Item = S>,
        seconds: u64,
        stop: &AtomicBool,
    ) -> Result<String> {
        let bytes = process(&self.path, args, seconds, stop, 1024 * 1024, None)?;
        Ok(String::from_utf8_lossy(&bytes).trim().to_owned())
    }

    pub fn target<'a>(&'a self, device: &Device, stop: &'a AtomicBool) -> Result<Target<'a>> {
        let transport = device
            .transport
            .filter(|_| device.ready())
            .ok_or("Select a connected and authorized device.")?;
        Ok(Target {
            adb: self,
            stop,
            transport,
            serial: device.serial.clone(),
        })
    }

    pub fn verify_apk(&self, path: &Path, package: &str, stop: &AtomicBool) -> Result<()> {
        let actual = self.apk_info(path, stop)?.package;
        if actual == package {
            Ok(())
        } else {
            Err(format!("Expected APK package {package}; found {actual}."))
        }
    }

    fn sdk(&self) -> Result<&Path> {
        self.path
            .parent()
            .and_then(Path::parent)
            .ok_or_else(|| "Select adb.exe from an Android SDK platform-tools directory.".into())
    }

    fn build_tool(&self, relative: &str) -> Result<PathBuf> {
        std::fs::read_dir(self.sdk()?.join("build-tools"))
            .ok()
            .into_iter()
            .flatten()
            .filter_map(std::result::Result::ok)
            .filter_map(|e| {
                let version = semver::Version::parse(e.file_name().to_str()?).ok()?;
                let file = e.path().join(relative);
                file.is_file().then_some((version, file))
            })
            .max_by(|(a, _), (b, _)| a.cmp(b))
            .map(|(_, file)| file)
            .ok_or_else(|| {
                format!("Install Android SDK Build-Tools with {relative} next to Platform-Tools.")
            })
    }

    pub fn apk_info(&self, path: &Path, stop: &AtomicBool) -> Result<ApkInfo> {
        let aapt = self.build_tool("aapt2.exe")?;
        let output = process(
            &aapt,
            [OsStr::new("dump"), OsStr::new("badging"), path.as_os_str()],
            20,
            stop,
            256 * 1024,
            None,
        )?;
        apk_info(&String::from_utf8_lossy(&output))
    }

    pub fn apk_signer(&self, path: &Path, sdk: u32, stop: &AtomicBool) -> Result<String> {
        let jar = self.build_tool("lib/apksigner.jar")?;
        let java = [self.sdk()?.join("jre/bin/java.exe")]
            .into_iter()
            .chain(std::env::var_os("JAVA_HOME").map(|p| PathBuf::from(p).join("bin/java.exe")))
            .chain(std::env::var_os("PATH").into_iter().flat_map(|p| {
                std::env::split_paths(&p)
                    .map(|dir| dir.join("java.exe"))
                    .collect::<Vec<_>>()
            }))
            .find(|p| p.is_absolute() && p.is_file())
            .ok_or("Install a JDK and set JAVA_HOME so the installer can verify APK signatures.")?;
        let sdk = sdk.to_string();
        let output = process(
            &java,
            [
                OsStr::new("-jar"),
                jar.as_os_str(),
                OsStr::new("verify"),
                OsStr::new("--min-sdk-version"),
                OsStr::new(&sdk),
                OsStr::new("--max-sdk-version"),
                OsStr::new(&sdk),
                OsStr::new("--print-certs"),
                path.as_os_str(),
            ],
            90,
            stop,
            64 * 1024,
            None,
        )?;
        single_signer(&String::from_utf8_lossy(&output))
    }
}

fn apk_info(output: &str) -> Result<ApkInfo> {
    let line = output
        .lines()
        .find_map(|line| line.strip_prefix("package: "))
        .ok_or("APK package identity is missing.")?;
    let field = |key: &str| {
        let marker = format!("{key}='");
        let value = line.strip_prefix(&marker).or_else(|| {
            line.split_once(&format!(" {marker}"))
                .map(|(_, value)| value)
        })?;
        value.split_once('\'').map(|(value, _)| value)
    };
    Ok(ApkInfo {
        package: field("name")
            .filter(|name| !name.is_empty())
            .ok_or("APK package identity is empty.")?
            .into(),
        version_code: field("versionCode").and_then(|value| value.parse().ok()),
        version_name: field("versionName").map(str::to_owned),
        min_sdk: output.lines().find_map(|line| {
            line.strip_prefix("sdkVersion:'")
                .or_else(|| line.strip_prefix("minSdkVersion:'"))?
                .strip_suffix('\'')?
                .parse()
                .ok()
        }),
        debuggable: output
            .lines()
            .any(|line| line.trim_start().starts_with("application-debuggable")),
        split: field("split").is_some_and(|value| !value.is_empty()),
    })
}

fn single_signer(output: &str) -> Result<String> {
    let mut signers = output.lines().filter_map(|line| {
        let (name, digest) = line.split_once(" certificate SHA-256 digest: ")?;
        name.starts_with("Signer #").then_some((name, digest))
    });
    match (signers.next(), signers.next()) {
        (Some(("Signer #1", digest)), None)
            if digest.len() == 64 && digest.bytes().all(|c| c.is_ascii_hexdigit()) =>
        {
            Ok(digest.to_ascii_lowercase())
        }
        _ => Err("APK verification must report one unambiguous signing certificate.".into()),
    }
}

pub struct Target<'a> {
    pub adb: &'a Adb,
    pub stop: &'a AtomicBool,
    pub transport: u64,
    serial: String,
}

impl Target<'_> {
    fn args<S: AsRef<OsStr>>(&self, args: impl IntoIterator<Item = S>) -> Vec<OsString> {
        [
            OsString::from("-t"),
            OsString::from(self.transport.to_string()),
        ]
        .into_iter()
        .chain(args.into_iter().map(|p| p.as_ref().to_owned()))
        .collect()
    }

    pub fn text<S: AsRef<OsStr>>(
        &self,
        args: impl IntoIterator<Item = S>,
        seconds: u64,
    ) -> Result<String> {
        self.check_transport()?;
        self.adb.text(self.args(args), seconds, self.stop)
    }

    pub fn capture(&self, args: &[&str], seconds: u64, limit: usize) -> Result<Vec<u8>> {
        self.check_transport()?;
        process(
            &self.adb.path,
            self.args(args),
            seconds,
            self.stop,
            limit,
            None,
        )
    }

    pub fn script(&self, script: &str, arguments: &[&str], seconds: u64) -> Result<String> {
        if arguments.iter().any(|value| {
            value.is_empty()
                || !value
                    .bytes()
                    .all(|c| c.is_ascii_alphanumeric() || b"_./:+-,".contains(&c))
        }) {
            return Err("Unsafe argument for a head-unit operation.".into());
        }
        self.check_transport()?;
        let args = self.args(
            ["shell", "-T", "sh", "-s", "--"]
                .into_iter()
                .chain(arguments.iter().copied()),
        );
        let output = process(
            &self.adb.path,
            args,
            seconds,
            self.stop,
            1024 * 1024,
            Some(script.as_bytes()),
        )?;
        String::from_utf8(output)
            .map(|value| value.trim().to_owned())
            .map_err(|_| "Invalid UTF-8 in the head-unit response.".into())
    }

    fn check_transport(&self) -> Result<()> {
        let actual = self.adb.text(self.args(["get-serialno"]), 6, self.stop)?;
        if actual == self.serial {
            Ok(())
        } else {
            Err("The selected ADB transport changed. Refresh and confirm the head unit before retrying.".into())
        }
    }

    pub fn install(&self, apk: &Path) -> Result<()> {
        let result = self.text(
            [OsStr::new("install"), OsStr::new("-r"), apk.as_os_str()],
            240,
        )?;
        require_success(&result)
    }

    pub fn install_owner(&self, apk: &Path) -> Result<()> {
        let result = self.text(
            [
                OsStr::new("install"),
                OsStr::new("-r"),
                OsStr::new("--user"),
                OsStr::new("0"),
                apk.as_os_str(),
            ],
            240,
        )?;
        require_success(&result)
    }

    pub fn property(&self, key: &str) -> Result<String> {
        self.text(["shell", "getprop", key], 10)
    }
}

pub fn require_success(output: &str) -> Result<()> {
    if output.lines().any(|line| line.trim() == "Success") {
        Ok(())
    } else {
        Err(format!("Android did not confirm success. {output}"))
    }
}

fn drain(mut pipe: impl Read, limit: usize) -> io::Result<(Vec<u8>, bool)> {
    let mut output = Vec::new();
    let mut buffer = [0; 8192];
    let mut truncated = false;
    loop {
        let count = pipe.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        let accepted = count.min(limit.saturating_sub(output.len()));
        output.extend_from_slice(&buffer[..accepted]);
        truncated |= accepted < count;
    }
    Ok((output, truncated))
}

fn process<S: AsRef<OsStr>>(
    executable: &Path,
    args: impl IntoIterator<Item = S>,
    seconds: u64,
    stop: &AtomicBool,
    limit: usize,
    input: Option<&[u8]>,
) -> Result<Vec<u8>> {
    if stop.load(Ordering::Relaxed) {
        return Err("Canceled before the next command.".into());
    }
    let scripted = input.is_some();
    let mut command = Command::new(executable);
    command
        .args(args)
        .stdin(if input.is_some() {
            Stdio::piped()
        } else {
            Stdio::null()
        })
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000);
        if let Some(directory) = executable.parent() {
            command.current_dir(directory);
        }
    }
    let mut child = command
        .spawn()
        .map_err(|err| format!("Could not start {}. {err}", executable.display()))?;
    let input = input.map(|bytes| {
        let bytes = bytes.to_vec();
        let mut pipe = child.stdin.take().expect("configured command input");
        thread::spawn(move || pipe.write_all(&bytes))
    });
    let stdout = child.stdout.take().unwrap();
    let stderr = child.stderr.take().unwrap();
    let output = thread::spawn(move || drain(stdout, limit));
    let errors = thread::spawn(move || drain(stderr, 32 * 1024));
    let deadline = Instant::now() + Duration::from_secs(seconds);
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break Ok(status),
            Ok(None) if !stop.load(Ordering::Relaxed) && Instant::now() < deadline => {
                thread::sleep(Duration::from_millis(50));
            }
            result => {
                let _ = child.kill();
                let _ = child.wait();
                break Err(match result {
                    Err(error) => format!("Could not wait for the command. {error}"),
                    _ if stop.load(Ordering::Relaxed) => {
                        "Canceled. Check the device before retrying a write.".into()
                    }
                    _ => "The command timed out. Check the device before retrying a write.".into(),
                });
            }
        }
    };
    // The ADB client can spawn a server. Avoid waiting on inherited pipe handles
    // after a timeout or an interrupted command.
    let status = status?;
    while !output.is_finished()
        || !errors.is_finished()
        || input.as_ref().is_some_and(|writer| !writer.is_finished())
    {
        if Instant::now() >= deadline || stop.load(Ordering::Relaxed) {
            return Err("The command's output stream did not close. Check the device before retrying a write.".into());
        }
        thread::sleep(Duration::from_millis(25));
    }
    let (stdout, truncated) = output
        .join()
        .map_err(|_| "Could not read command output.")?
        .map_err(|e| e.to_string())?;
    let (stderr, _) = errors
        .join()
        .map_err(|_| "Could not read command errors.")?
        .map_err(|e| e.to_string())?;
    if !status.success() {
        if scripted && !stdout.is_empty() && !stderr.is_empty() {
            return Err(format!(
                "Command failed ({status}). {}\r\n{}",
                String::from_utf8_lossy(&stdout).trim(),
                String::from_utf8_lossy(&stderr).trim()
            ));
        }
        let detail = if stderr.is_empty() { &stdout } else { &stderr };
        return Err(format!(
            "Command failed ({status}). {}",
            String::from_utf8_lossy(detail).trim()
        ));
    }
    if truncated {
        return Err("Command output exceeded the diagnostic limit.".into());
    }
    if let Some(writer) = input {
        writer
            .join()
            .map_err(|_| "Could not send command input.")?
            .map_err(|error| format!("Could not send command input. {error}"))?;
    }
    Ok(stdout)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn offline_and_unidentified_devices_cannot_be_targets() {
        let list = parse_devices(
            "List of devices attached\r\nA device model:Voyah_Free transport_id:17\r\nB unauthorized transport_id:18\nC device",
        );
        assert_eq!(list.len(), 3);
        assert!(list[0].ready());
        assert!(!list[1].ready() && !list[2].ready());
        assert_eq!(list[0].transport, Some(17));
    }

    #[test]
    fn endpoint_requires_one_valid_host_and_port() {
        assert_eq!(endpoint("192.168.1.10").unwrap(), "192.168.1.10:5555");
        assert_eq!(endpoint("[::1]:5556").unwrap(), "[::1]:5556");
        for value in ["-a:1", "host:0", "host:65536", "bad host:1", "host; id:1"] {
            assert!(endpoint(value).is_err());
        }
    }
}

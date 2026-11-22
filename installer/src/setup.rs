use crate::{
    adb::{Adb, Device, Result, Target},
    device::PACKAGE,
    production::{self, Identity},
};
use base64::{Engine, engine::general_purpose::STANDARD};
use std::{
    path::PathBuf,
    sync::atomic::{AtomicBool, Ordering},
    thread,
    time::{Duration, Instant},
};

const START: &str = include_str!("../../android/tools/access-start.sh");
const LOGIN: &str = include_str!("../../android/tools/access-login.sh");
const BASE: &str = "/data/adb/open-voyah-controls-access";
pub const FIND_TELNET: &str = r#"
busybox=
for candidate in /system/bin/busybox /system/xbin/busybox /vendor/bin/busybox; do
    [ -f "$candidate" ] && [ ! -L "$candidate" ] && [ -x "$candidate" ] || continue
    case "$(stat -c %u:%a "$candidate")" in 0:755|0:555) ;; *) continue;; esac
    help=$("$candidate" telnetd --help 2>&1 || true)
    case "$help" in *'-b '*) ;; *) continue;; esac
    case "$help" in *'-F'*) ;; *) continue;; esac
    case "$help" in *'-K'*) ;; *) continue;; esac
    "$candidate" --list | grep -qx flock || continue
    "$candidate" sh -c 'exec 9</dev/null; "$1" test -e /proc/self/fd/9' _ "$candidate" || continue
    busybox=$candidate; break
done
[ -n "$busybox" ] || { echo 'This firmware needs a root-owned BusyBox with standalone loopback Telnet and file locking support.' >&2; exit 1; }
"#;

fn wait_selected(
    adb: &Adb,
    serial: &str,
    identity: &Identity,
    stop: &AtomicBool,
    root: bool,
    previous_boot: Option<&str>,
    seconds: u64,
) -> Result<Device> {
    let deadline = Instant::now() + Duration::from_secs(seconds);
    while Instant::now() < deadline {
        if stop.load(Ordering::Relaxed) {
            return Err("Setup canceled while waiting for the selected head unit.".into());
        }
        if let Ok(devices) = adb.devices(stop) {
            let matches: Vec<_> = devices
                .into_iter()
                .filter(|d| d.ready() && d.serial == serial)
                .collect();
            if matches.len() == 1 {
                let Ok(target) = adb.target(&matches[0], stop) else {
                    continue;
                };
                // Properties and framework services can be temporarily unavailable during boot.
                let Ok(current) = Identity::read(&target) else {
                    thread::sleep(Duration::from_millis(500));
                    continue;
                };
                if current != *identity {
                    return Err(
                        "The reconnected head unit has a different device identity. Setup stopped."
                            .into(),
                    );
                }
                if root && target.text(["shell", "id", "-u"], 10).as_deref() != Ok("0") {
                    thread::sleep(Duration::from_millis(500));
                    continue;
                }
                if let Some(previous) = previous_boot
                    && (target
                        .text(["shell", "cat", "/proc/sys/kernel/random/boot_id"], 10)
                        .as_deref()
                        == Ok(previous)
                        || target.property("sys.boot_completed").as_deref() != Ok("1"))
                {
                    thread::sleep(Duration::from_millis(500));
                    continue;
                }
                return Ok(matches[0].clone());
            }
            if matches.len() > 1 {
                return Err(
                    "The selected serial has multiple transports. Reconnect one head unit.".into(),
                );
            }
        }
        thread::sleep(Duration::from_millis(500));
    }
    Err("The selected head unit did not reconnect. Setup can be resumed without reinstalling or clearing app data.".into())
}

pub fn root_device(
    adb: &Adb,
    device: &Device,
    stop: &AtomicBool,
    progress: &impl Fn(String),
) -> Result<(Device, Identity)> {
    let target = adb.target(device, stop)?;
    let identity = Identity::read(&target)?;
    if target.text(["shell", "id", "-u"], 10)? == "0" {
        return Ok((device.clone(), identity));
    }
    progress("Requesting the selected head unit's ADB root service…".into());
    let result = target.text(["root"], 20)?;
    if result.contains("cannot run as root") || result.contains("not allowed") {
        return Err("This firmware does not allow ADB root. No startup files were changed.".into());
    }
    let connected = wait_selected(adb, target.serial(), &identity, stop, true, None, 90)?;
    production::require_root(&adb.target(&connected, stop)?)?;
    Ok((connected, identity))
}

/// How long a restarted head unit may take to reconnect and finish booting.
pub const BOOT_WAIT: u64 = 420;

/// Restarts the selected unit, waits until the same hardware and build has booted again, and restores ADB root,
/// which Android drops on every boot.
pub fn restart(
    adb: &Adb,
    device: &Device,
    identity: &Identity,
    stop: &AtomicBool,
    progress: &impl Fn(String),
) -> Result<Device> {
    let target = adb.target(device, stop)?;
    identity.recheck(&target)?;
    let boot = target.text(["shell", "cat", "/proc/sys/kernel/random/boot_id"], 10)?;
    // The transport closes while the command runs; the new boot ID below is the confirmation.
    let _ = target.text(["reboot"], 30);
    let connected = wait_selected(adb, target.serial(), identity, stop, false, Some(&boot), BOOT_WAIT)
        .map_err(|error| format!("The head unit did not come back after the restart. Reconnect it and select Retry. {error}"))?;
    let (connected, _) = root_device(adb, &connected, stop, progress)?;
    Ok(connected)
}

pub fn remount(
    adb: &Adb,
    device: Device,
    identity: &Identity,
    stop: &AtomicBool,
    progress: &impl Fn(String),
) -> Result<Device> {
    let target = adb.target(&device, stop)?;
    if target.script("test -w /system/etc\n", &[], 10).is_ok() {
        return Ok(device);
    }
    progress("Making the existing system startup script writable…".into());
    let result = target.text(["remount"], 30);
    if result.is_ok() && target.script("test -w /system/etc\n", &[], 10).is_ok() {
        return Ok(device);
    }
    progress("Preparing verified-boot settings; the selected head unit will restart once…".into());
    let verity = target.text(["disable-verity"], 30)?;
    if verity.contains("not supported") || verity.contains("failed") || verity.contains("ERROR") {
        return Err("This firmware cannot prepare a writable startup script with ADB. The PC backup was retained.".into());
    }
    identity.recheck(&target)?;
    let boot = target.text(["shell", "cat", "/proc/sys/kernel/random/boot_id"], 10)?;
    target.text(["reboot"], 10)?;
    let connected = wait_selected(
        adb,
        target.serial(),
        identity,
        stop,
        false,
        Some(&boot),
        BOOT_WAIT,
    )?;
    let (connected, _) = root_device(adb, &connected, stop, progress)?;
    let target = adb.target(&connected, stop)?;
    target.text(["remount"], 30)?;
    target.script("test -w /system/etc\n", &[], 10).map_err(|_| "The system partition is still read-only. Resume with the retained PC backup after checking this firmware.".to_string())?;
    identity.recheck(&target)?;
    Ok(connected)
}

pub fn portable_runtime() -> Option<PathBuf> {
    let metadata: serde_json::Value =
        serde_json::from_str(include_str!("../../android/dist/FRIDA_RUNTIME.json")).ok()?;
    let file = metadata.get("file")?.as_str()?;
    let exe = std::env::current_exe().ok()?;
    let folder = exe.parent()?;
    // The portable kit keeps it in support\; a development build finds the one prepare_runtime.py wrote.
    [
        folder.join("support").join(file),
        folder.join("../../../android/dist/runtime").join(file),
    ]
    .into_iter()
    .find(|candidate| candidate.is_file())
}

pub fn install_access(target: &Target<'_>, identity: &Identity) -> Result<()> {
    let script = format!(
        r#"set -eu
umask 077
base={BASE}
[ "$(getprop ro.build.fingerprint)" = "$1" ] && {selinux}
uid=$(pm list packages -U --user 0 {PACKAGE} | sed -n 's/^package:{PACKAGE} uid:\([0-9]*\)$/\1/p')
case "$uid" in ''|*[!0-9]*) exit 1;; esac
[ "$uid" -ge 10000 ] && [ "$uid" -lt 100000 ]
{find_telnet}
if [ -e "$base" ] || [ -L "$base" ]; then
    [ ! -L "$base" ] && [ "$(stat -c %u:%a "$base")" = 0:700 ]
    for file in access-start.sh login.sh checksums key; do [ -f "$base/$file" ] && [ ! -L "$base/$file" ]; done
    [ "$(stat -c %u:%a "$base/access-start.sh")" = 0:700 ]
    [ "$(stat -c %u:%a "$base/checksums")" = 0:600 ]
    (cd "$base" && [ "$(wc -l < checksums)" = 3 ] && sha256sum -c checksums >/dev/null)
    "$busybox" sh "$base/access-start.sh" --stop
fi
stage=$(mktemp -d /data/adb/.ovc-access-setup.XXXXXXXX)
old=
cleanup() {{
    if [ -n "$old" ] && [ ! -e "$base" ]; then mv "$old" "$base"; fi
    if [ -n "$stage" ]; then
        for file in access-start.sh login.sh fingerprint uid busybox key checksums accessibility-added; do rm -f "$stage/$file"; done
        rmdir "$stage" 2>/dev/null || true
    fi
}}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
base64 -d > "$stage/access-start.sh" <<'OVC_START_BYTES'
{start}
OVC_START_BYTES
base64 -d > "$stage/login.sh" <<'OVC_LOGIN_BYTES'
{login}
OVC_LOGIN_BYTES
chmod 700 "$stage/access-start.sh" "$stage/login.sh"
/system/bin/sh -n "$stage/access-start.sh" && /system/bin/sh -n "$stage/login.sh"
printf '%s\n' "$1" > "$stage/fingerprint"
printf '%s\n' "$uid" > "$stage/uid"
printf '%s\n' "$busybox" > "$stage/busybox"
if [ -e "$base/key" ]; then
    [ "$(stat -c %u:%a:%h "$base/key")" = 0:600:1 ]
    cp "$base/key" "$stage/key"
else
    dd if=/dev/urandom bs=32 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n' > "$stage/key"
    printf '\n' >> "$stage/key"
fi
[ "$(wc -c < "$stage/key")" = 65 ] && grep -Eq '^[0-9a-f]{{64}}$' "$stage/key"
files=/data/user/0/{PACKAGE}/files
[ ! -L "$files" ]
if [ ! -d "$files" ]; then mkdir -m 700 "$files"; chown "$uid:$uid" "$files"; fi
[ "$(stat -c %u "$files")" = "$uid" ]
[ ! -L "$files/root-channel.key" ]
token=$(mktemp "$files/.root-channel.XXXXXXXX")
cp "$stage/key" "$token"
chown "$uid:$uid" "$token"
chmod 600 "$token"
restorecon "$files" "$token"
mv -f "$token" "$files/root-channel.key"
chmod 600 "$stage/fingerprint" "$stage/uid" "$stage/busybox" "$stage/key"
cd "$stage"
sha256sum access-start.sh login.sh "$busybox" > checksums
chmod 600 checksums
if [ -f "$base/accessibility-added" ]; then touch "$stage/accessibility-added"; chmod 600 "$stage/accessibility-added"; fi
sync
cd /data/adb
if [ -d "$base" ]; then
    old=$(mktemp -d /data/adb/.ovc-access-retired.XXXXXXXX)
    rmdir "$old"
    mv "$base" "$old"
fi
mv "$stage" "$base"
stage=
sync
pm grant --user 0 {PACKAGE} android.permission.RECORD_AUDIO
services=$(settings get secure enabled_accessibility_services)
[ "$services" != null ] || services=
component={PACKAGE}/{PACKAGE}.ButtonAccessibilityService
case ":$services:" in *":$component:"*|*":{PACKAGE}/.ButtonAccessibilityService:"*) ;;
    *) touch "$base/accessibility-added"; chmod 600 "$base/accessibility-added"; sync;;
esac
# Restart only our binding, including a service Android marked crashed during an app update.
others=
saved_ifs=$IFS
IFS=:
for service in $services; do
    case "$service" in "$component"|{PACKAGE}/.ButtonAccessibilityService|'') ;;
        *) others="${{others:+$others:}}$service";;
    esac
done
IFS=$saved_ifs
settings put secure enabled_accessibility_services "$others"
sleep 1
settings put secure enabled_accessibility_services "${{others:+$others:}}$component"
settings put secure accessibility_enabled 1
/system/bin/sh "$base/access-start.sh"
"#,
        find_telnet = FIND_TELNET,
        selinux = production::SELINUX_READY,
        start = STANDARD.encode(START.replace("\r\n", "\n")),
        login = STANDARD.encode(LOGIN.replace("\r\n", "\n"))
    );
    target.script(&script, &[&identity.fingerprint], 60)?;
    identity.recheck(target)
}

#[derive(Debug, PartialEq)]
pub struct AppReadiness {
    pub mic: bool,
    pub buttons: bool,
    pub reviewed: bool,
}

fn parse_ack(response: &str, nonce: &str) -> Result<AppReadiness> {
    let fields: Vec<_> = response.split('\t').collect();
    if fields.len() != 5
        || fields[0] != nonce
        || !fields[3..].iter().all(|v| matches!(*v, "0" | "1"))
        || !matches!(fields[2], "root" | "voice" | "firmware" | "hooks")
    {
        return Err("Invalid Android setup acknowledgment.".into());
    }
    match fields[1] {
        "ready" if fields[2] == "hooks" && fields[3] == "1" && fields[4] == "1" => {}
        "permissions" if fields[2] == "hooks" => {}
        "unreviewed" if fields[2] == "firmware" => {}
        "failed" => {
            return Err(format!(
                "Android first-run check failed at '{}'. Read the explanation on the head unit; setup is incomplete.",
                fields[2]
            ));
        }
        _ => return Err("Invalid Android readiness state.".into()),
    }
    Ok(AppReadiness {
        mic: fields[3] == "1",
        buttons: fields[4] == "1",
        reviewed: fields[1] != "unreviewed",
    })
}

pub fn probe_app(target: &Target<'_>) -> Result<AppReadiness> {
    let nonce = target
        .text(["shell", "cat", "/proc/sys/kernel/random/uuid"], 10)?
        .replace('-', "");
    if nonce.len() != 32
        || !nonce
            .bytes()
            .all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase())
    {
        return Err("Invalid setup request identity.".into());
    }
    target.text(
        [
            "shell",
            "am",
            "start",
            "--user",
            "0",
            "-n",
            "org.openvoyah.controls/.SetupActivity",
            "--es",
            "request",
            &nonce,
        ],
        15,
    )?;
    let response=target.script(&format!("n=0\nwhile [ \"$n\" -lt 240 ]; do\n if [ -f {BASE}/app-probe ]; then value=$(cat {BASE}/app-probe); case \"$value\" in \"$1\"*) printf '%s\\n' \"$value\"; exit 0;; esac; fi\n sleep 1; n=$((n+1))\ndone\nexit 1\n"), &[&nonce],245)
        .map_err(|_| "The root service started, but Android did not confirm access. Check the app, local channel, and SELinux diagnostics; setup is not ready.".to_string())?;
    parse_ack(&response, &nonce)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn app_acknowledgment_cannot_claim_unverified_readiness() {
        let nonce = "ab".repeat(16);
        assert!(
            parse_ack(&format!("{nonce}\tready\thooks\t1\t1"), &nonce)
                .unwrap()
                .reviewed
        );
        assert!(
            !parse_ack(&format!("{nonce}\tunreviewed\tfirmware\t1\t0"), &nonce)
                .unwrap()
                .reviewed
        );
        for suffix in [
            "ready\troot\t1\t1",
            "ready\thooks\t1\t0",
            "failed\tvoice\t0\t0",
            "unreviewed\thooks\t1\t1",
            "unknown\tfirmware\t1\t1",
        ] {
            assert!(parse_ack(&format!("{nonce}\t{suffix}"), &nonce).is_err());
        }
        assert!(parse_ack(&format!("{nonce}\tready\thooks\t1\t1"), &"cd".repeat(16)).is_err());
    }
}

use std::{fs, io::Read, path::Path};

const ORIGINAL: &[u8] = b"#!/system/bin/sh\necho stock\n";
const BOOT_SHA: &str = "c4da4be759d9d08f1962899015f2d05b13e18c6515ae99b9a5f108691607ef30";

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let command: Vec<&str> = args[1..].iter().map(String::as_str).collect();
    let executable = Path::new(&args[0]);
    let state = executable.parent().unwrap();
    let installed = state.join("installed-apk");
    let active = state.join("active-hooks");
    let blocked = state.join("recovery-blocked");
    let legacy = state.join("legacy-files");
    let foreign = state.join("foreign-transport");
    if executable.file_stem().and_then(|s| s.to_str()) == Some("aapt2") {
        if let ["dump", "badging", apk] = command.as_slice() {
            let bytes = fs::read_to_string(apk).unwrap();
            let package = if bytes == "wrong" { "com.example.wrong" } else { "org.openvoyah.controls" };
            let (code, version) = match bytes.as_str() {
                "newer" => (16, "0.13.0"),
                "update" => (15, "0.12.1"),
                _ => (14, "0.12.0"),
            };
            println!("package: name='{package}' versionCode='{code}' versionName='{version}'\nsdkVersion:'28'");
            return;
        }
    }
    if executable.file_stem().and_then(|s| s.to_str()) == Some("java")
        && command.first() == Some(&"-jar")
    {
        let bytes = fs::read_to_string(command.last().unwrap()).unwrap();
        let cert = if bytes == "wrong-signer" { "2" } else { "1" }.repeat(64);
        println!("Signer #1 certificate SHA-256 digest: {cert}");
        return;
    }
    if let ["-t", "7", "shell", "-T", "sh", "-s", "--", tail @ ..] = command.as_slice() {
        let mut script = String::new();
        std::io::stdin().read_to_string(&mut script).unwrap();
        match tail {
            [] if script.contains("ro.build.version.sdk") => {
                println!("ro.build.fingerprint=example/demo/build\nro.serialno=hardware-a\nro.product.name=f0506h\nro.product.cpu.abi=arm64-v8a\nro.build.version.sdk=30");
                return;
            }
            ["org.openvoyah.controls"] if script.contains("pm path --user 0") => {
                if installed.exists() {
                    println!("package:/data/app/controls/base.apk");
                } else {
                    println!("OVC_PACKAGE_ABSENT");
                }
                return;
            }
            [mode, "example/demo/build", "hardware-a", extra @ ..]
                if script.contains("OVC_ROLLBACK_INERT") =>
            {
                match (*mode, extra) {
                    ("status", []) => {
                        let inert = if active.exists() || legacy.exists() { "no" } else { "yes" };
                        let prior = if legacy.exists() { "active" } else { "absent" };
                        let recovery = if blocked.exists() { "yes" } else { "no" };
                        let (entry, ready) = if active.exists() { ("present", "launcher,window") } else { ("absent", "") };
                        println!("OVC_ROLLBACK_INERT={inert}\nOVC_LEGACY_STATE={prior}\nOVC_RECOVERY_BLOCKED={recovery}\nOVC_BOOT_ENTRY={entry}\nOVC_READY_CHANNELS={ready}");
                    }
                    ("backup", []) => {
                        println!("OVC_BACKUP_DIR=/data/adb/open-voyah-controls-boot\nOVC_BACKUP_SHA256={BOOT_SHA}\nOVC_BACKUP_FINGERPRINT=example/demo/build\nOVC_BACKUP_SERIAL=hardware-a\nOVC_BACKUP_CONTEXT=u:object_r:system_file:s0:c1,c2\nOVC_BACKUP_OWNER=0:0\nOVC_BACKUP_MODE=755");
                    }
                    ("rollback", [])
                    | ("restore-exported", [BOOT_SHA, "u:object_r:system_file:s0:c1,c2", "0:0", "755"]) => {
                        if *mode == "restore-exported" {
                            assert!(script.starts_with("ovc_external_original() {"));
                            assert!(script.contains("IyEvc3lzdGVtL2Jpbi9zaAplY2hvIHN0b2NrCg=="));
                        }
                        let _ = fs::remove_file(&active);
                        let _ = fs::remove_file(&legacy);
                        fs::write(&blocked, b"yes").unwrap();
                        println!("OVC_ROLLBACK_INERT=yes");
                    }
                    _ => panic!("Unexpected mock boot action"),
                }
                return;
            }
            _ => {}
        }
    }
    match command.as_slice() {
        ["devices", "-l"] => {
            println!("List of devices attached\nunit-a device model:Demo transport_id:7\nunit-b unauthorized transport_id:8");
        }
        ["-t", "7", "get-serialno"] => println!("{}", if foreign.exists() { "different-unit" } else { "unit-a" }),
        ["-t", "7", "install", "-r", "--user", "0", path] if path.ends_with(".apk") => {
            assert!(!active.exists() && !legacy.exists());
            fs::copy(path, &installed).unwrap();
            println!("Success");
        }
        ["-t", "7", "pull", "/data/app/controls/base.apk", destination] => {
            fs::copy(&installed, destination).unwrap();
        }
        ["-t", "7", "uninstall", "-k", "org.openvoyah.controls"] => {
            assert!(!active.exists() && !legacy.exists());
            fs::remove_file(&installed).unwrap();
            println!("Success");
        }
        ["-t", "7", "uninstall", "org.openvoyah.controls"] => {
            assert!(blocked.exists() && !active.exists() && !legacy.exists());
            fs::remove_file(&installed).unwrap();
            fs::write(state.join("full-remove"), "confirmed").unwrap();
            println!("Success");
        }
        ["-t", "7", "shell", "pm", "list", "users"] => println!("Users:\n    UserInfo{{0:Owner:13}} running"),
        ["-t", "7", "shell", "am", "get-current-user"] => println!("0"),
        ["-t", "7", "shell", "id", "-u"] => println!("0"),
        ["-t", "7", "exec-out", "cat", "/data/adb/open-voyah-controls-boot/original"] => {
            use std::io::Write;
            std::io::stdout().write_all(ORIGINAL).unwrap();
        }
        ["-t", "7", "shell", "am", "start", "-n", "org.openvoyah.controls/.MainActivity"] => {
            println!("Starting: Intent");
        }
        ["-t", "7", "shell", "getprop", "ro.product.name"] => println!("f0506h"),
        ["-t", "7", "shell", "getprop", "ro.build.fingerprint"] => println!("example/demo/build"),
        _ => {
            eprintln!("Unexpected mock Android command: {command:?}");
            std::process::exit(2);
        }
    }
}

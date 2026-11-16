use std::{
    env, fs,
    io::{Read, Write},
    path::Path,
};

const APK: &[u8] = b"portable evidence fixture APK";

fn main() {
    let args: Vec<String> = env::args().collect();
    let executable = Path::new(&args[0]);
    let command: Vec<_> = args[1..].iter().map(String::as_str).collect();
    let mut log = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(env::var_os("OVC_FIRMWARE_FIXTURE_LOG").unwrap())
        .unwrap();
    writeln!(log, "{}\t{}", executable.display(), command.join("\t")).unwrap();
    let role = executable.file_stem().unwrap().to_str().unwrap();
    match (role, command.as_slice()) {
        ("java", ["-jar", jar, "version"]) if jar.ends_with("apksigner.jar") => {
            if env::var_os("OVC_FIRMWARE_FIXTURE_JAVA_FAIL").is_some() {
                eprintln!("Fixture Java runtime is incomplete.");
                std::process::exit(1);
            }
            println!("fixture APK verifier");
        }
        (
            "java",
            [
                "-jar",
                jar,
                "verify",
                "--min-sdk-version",
                "30",
                "--max-sdk-version",
                "30",
                "--print-certs",
                apk,
            ],
        ) if jar.ends_with("apksigner.jar") && Path::new(apk).is_file() => {
            println!("Signer #1 certificate SHA-256 digest: {}", "1".repeat(64));
        }
        ("aapt2", ["dump", "badging", apk]) => {
            let package = Path::new(apk).file_stem().unwrap().to_str().unwrap();
            println!("package: name='{package}' versionCode='1' versionName='fixture'");
        }
        ("aapt2", ["dump", "xmltree", "--file", "AndroidManifest.xml", apk]) => {
            let package = Path::new(apk).file_stem().unwrap().to_str().unwrap();
            if env::var_os("OVC_FIRMWARE_FIXTURE_MANIFEST_FAIL").is_some() {
                eprintln!("Fixture manifest inspection is unavailable.");
                std::process::exit(1);
            }
            if env::var_os("OVC_FIRMWARE_FIXTURE_MANIFEST_EMPTY").is_some() {
                return;
            }
            println!("N: android=http://schemas.android.com/apk/res/android (line=2)");
            println!("  E: manifest (line=2)\n      E: application (line=3)");
            println!("          E: service (line=4)");
            println!(
                "            A: http://schemas.android.com/apk/res/android:name(0x01010003)=\".ObservedService\" (Raw: \".ObservedService\")"
            );
            println!(
                "              E: intent-filter (line=5)\n                  E: action (line=6)"
            );
            println!(
                "                    A: http://schemas.android.com/apk/res/android:name(0x01010003)=\"PRIVATE_RECORD_MUST_NOT_APPEAR\" (Raw: \"PRIVATE_RECORD_MUST_NOT_APPEAR\")"
            );
            println!("          E: provider (line=7)");
            println!(
                "            A: http://schemas.android.com/apk/res/android:name(0x01010003)=\"ObservedProvider\" (Raw: \"ObservedProvider\")"
            );
            println!(
                "            A: http://schemas.android.com/apk/res/android:authorities(0x01010018)=\"{package}.provider\" (Raw: \"{package}.provider\")"
            );
        }
        ("adb", ["devices", "-l"]) => {
            if env::var_os("OVC_FIRMWARE_FIXTURE_ADB_DELAY").is_some() {
                eprintln!("PRIVATE_RECORD_MUST_NOT_APPEAR");
                std::thread::sleep(std::time::Duration::from_secs(10));
            }
            println!("List of devices attached\nunit-a device transport_id:7");
        }
        ("adb", ["-t", "7", "shell", "getprop", property]) => {
            println!(
                "{}",
                match *property {
                    "ro.build.fingerprint" => "fixture/device:11/TEST/1:user/test-keys",
                    "ro.build.version.sdk" => "30",
                    "ro.product.cpu.abi" => "arm64-v8a",
                    "ro.product.name" => "f0506h",
                    "ro.serialno" => "fixture-hardware",
                    "ro.product.model" => "Fixture head unit",
                    _ => panic!("Unexpected fixture property"),
                }
            );
        }
        ("adb", ["-t", "7", "shell", "getenforce"]) => println!("Enforcing"),
        ("adb", ["-t", "7", "shell", "dumpsys", "package", "providers"]) => {
            let state = env::var("OVC_FIRMWARE_FIXTURE_PROVIDER").unwrap_or_default();
            println!("ContentProvider Authorities:");
            println!(
                "  [private.account.value]:\n    Provider{{private unrelated.package/.PrivateProvider}}"
            );
            if state != "missing" {
                println!("  [com.qinggan.usersettingtracking.datacenterprovider]:");
                println!("    Provider{{fixture com.qinggan.tracking.fixture/.StateProvider}}");
                if state == "ambiguous" {
                    println!("  [com.qinggan.usersettingtracking.datacenterprovider]:");
                    println!("    Provider{{other com.qinggan.other.fixture/.OtherProvider}}");
                }
            }
        }
        ("adb", ["-t", "7", "shell", "-T", "sh", "-s", "--"]) => {
            let mut script = String::new();
            std::io::stdin().read_to_string(&mut script).unwrap();
            assert!(script.contains("/private/configs/token/accountInfo"));
            assert!(!script.contains("cat "));
            let state = env::var("OVC_FIRMWARE_FIXTURE_ACCOUNT")
                .unwrap_or_else(|_| "metadata-observed".into());
            match state.as_str() {
                "metadata-observed" | "empty" | "oversized" => {
                    let size = match state.as_str() {
                        "empty" => 0,
                        "oversized" => 8193,
                        _ => 64,
                    };
                    println!("OVC_ACCOUNT_FILE_STATUS=metadata-observed");
                    println!("OVC_ACCOUNT_FILE_STAT=1:2:{size}:123:123:1000:1000:444:1");
                    println!("OVC_ACCOUNT_FILE_CONTEXT=u:object_r:fixture_account_file:s0");
                }
                "malformed" => println!("OVC_ACCOUNT_FILE_STATUS=PRIVATE_RECORD_MUST_NOT_APPEAR"),
                "duplicate" => println!(
                    "OVC_ACCOUNT_FILE_STATUS=metadata-observed\nOVC_ACCOUNT_FILE_STATUS=PRIVATE_RECORD_MUST_NOT_APPEAR"
                ),
                "error" => {
                    eprintln!("PRIVATE_RECORD_MUST_NOT_APPEAR");
                    std::process::exit(1);
                }
                _ => println!("OVC_ACCOUNT_FILE_STATUS={state}"),
            }
        }
        ("adb", ["-t", "7", "shell", "pm", "path", package]) => {
            println!("package:/system/app/{package}.apk");
        }
        ("adb", ["-t", "7", "shell", "sha256sum", remote]) => {
            println!(
                "{}  {remote}",
                env::var("OVC_FIRMWARE_FIXTURE_APK_SHA256").unwrap()
            );
        }
        ("adb", ["-t", "7", "pull", remote, local]) if remote.starts_with("/system/app/") => {
            fs::write(local, APK).unwrap();
        }
        _ => {
            eprintln!("Unexpected offline firmware fixture command: {role} {command:?}");
            std::process::exit(2);
        }
    }
}

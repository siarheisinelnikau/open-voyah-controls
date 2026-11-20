use std::{
    env, fs,
    io::{Read, Write},
    path::Path,
};

const APK: &[u8] = b"portable evidence fixture APK";
const CONTROLS_APK: &str = "/data/app/~~Z9_eFI-00a==/org.openvoyah.controls-a11_Bc==/base.apk";

fn checked_controls_grants() -> bool {
    fs::read_to_string(env::var_os("OVC_FIRMWARE_FIXTURE_LOG").unwrap())
        .unwrap()
        .contains("\tdumpsys\tpackage\tcheck-permission\t")
}

fn print_seat_memory_config(property_id: &str, seat_case: &str) {
    let name = match property_id {
        "0x15400b80" => "SEAT_MEMORY_SELECT",
        "0x15400b81" => "SEAT_MEMORY_SET",
        _ => unreachable!(),
    };
    if seat_case == "missing" && name == "SEAT_MEMORY_SET" {
        println!("PRIVATE_RECORD_MUST_NOT_APPEAR");
        return;
    }
    if seat_case == "modern" {
        println!("Property:{name}({property_id}), group:SYSTEM(0x10000000), areaType:SEAT(0x5000000), valueType:INT32(0x400000),");
        println!("    access:WRITE(0x2), changeMode:ON_CHANGE(0x1), configArray:[], minSampleRateHz:0.000000, maxSampleRateHz:0.000000");
        println!("        areaId:ROW_1_RIGHT(0x4), access:WRITE(0x2), f min:0.000000, f max:0.000000, i min:0, i max:2, i64 min:0, i64 max:0");
        return;
    }
    println!("Property:{property_id}, Property name:{name}, access:0x2, changeMode:0x1, config:[], fs min:0.000000, fs max:0.000000");
    println!("\tareaId:0x1, f min:0.000000, f max:0.000000, i min:0, i max:3, i64 min:0, i64 max:0");
    if seat_case == "invalid-ranges" {
        if name == "SEAT_MEMORY_SELECT" {
            println!("\tareaId:0x1, f min:0.000000, f max:0.000000, i min:0, i max:3, i64 min:0, i64 max:0");
        } else {
            println!("\tareaId:0x4, f min:0.000000, f max:0.000000, i min:3, i max:2, i64 min:0, i64 max:0");
        }
    }
}

fn print_headlight_config(property_id: &str, light_case: &str) {
    let name = match property_id {
        "0x11400e10" => "HEADLIGHTS_SWITCH",
        "0x11400e00" => "HEADLIGHTS_STATE",
        "0x11400e01" => "HIGH_BEAM_LIGHTS_STATE",
        _ => unreachable!(),
    };
    let selector = name == "HEADLIGHTS_SWITCH";
    let high_beam = name == "HIGH_BEAM_LIGHTS_STATE";
    if light_case == "unavailable" {
        if selector {
            return;
        }
        if !high_beam {
            eprintln!("PRIVATE_RECORD_MUST_NOT_APPEAR");
            std::process::exit(1);
        }
    }
    if matches!(light_case, "modern" | "policy" | "invalid") {
        let (access, mode) = if light_case == "policy" {
            if selector {
                ("READ(0x1)", "STATIC(0x0)")
            } else if high_beam {
                ("READ(0x1)", "CONTINUOUS(0x2)")
            } else {
                ("READ_WRITE(0x3)", "ON_CHANGE(0x1)")
            }
        } else if selector {
            ("READ_WRITE(0x3)", "ON_CHANGE(0x1)")
        } else {
            ("READ(0x1)", "ON_CHANGE(0x1)")
        };
        let (reported_id, area_type, value_type) = match (light_case, name) {
            ("invalid", "HEADLIGHTS_SWITCH") => (property_id, "SEAT(0x5000000)", "INT32(0x400000)"),
            ("invalid", "HEADLIGHTS_STATE") => (property_id, "GLOBAL(0x1000000)", "BOOLEAN(0x200000)"),
            ("invalid", "HIGH_BEAM_LIGHTS_STATE") => ("0x11400e00", "GLOBAL(0x1000000)", "INT32(0x400000)"),
            _ => (property_id, "GLOBAL(0x1000000)", "INT32(0x400000)"),
        };
        println!("Property:{name}({reported_id}), group:SYSTEM(0x10000000), areaType:{area_type}, valueType:{value_type},");
        println!("    access:{access}, changeMode:{mode}, configArray:[], minSampleRateHz:0.000000, maxSampleRateHz:0.000000");
        if !(light_case == "modern" && selector) {
            let area_access = if light_case == "modern" && !high_beam { "NONE(0x0)" } else { access };
            println!("        areaId:GLOBAL(0x0), access:{area_access}, f min:0.000000, f max:0.000000, i min:0, i max:0, i64 min:0, i64 max:0");
        }
        return;
    }
    let access = if selector { 3 } else { 1 };
    println!("Property:{property_id}, Property name:{name}, access:0x{access}, changeMode:0x1, config:[], fs min:0.000000, fs max:0.000000");
}

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
    let event_case = env::var("OVC_FIRMWARE_FIXTURE_EVENTS").unwrap_or_default();
    let automotive_case = env::var("OVC_FIRMWARE_FIXTURE_AUTOMOTIVE").unwrap_or_default();
    let seat_case = env::var("OVC_FIRMWARE_FIXTURE_SEAT_METADATA").unwrap_or_default();
    let light_case = env::var("OVC_FIRMWARE_FIXTURE_HEADLIGHT_CONFIG").unwrap_or_default();
    let sdk = if automotive_case == "legacy" { "28" } else { "30" };
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
                min_sdk,
                "--max-sdk-version",
                max_sdk,
                "--print-certs",
                apk,
            ],
        ) if jar.ends_with("apksigner.jar") && *min_sdk == sdk && *max_sdk == sdk && Path::new(apk).is_file() => {
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
        ("adb", ["-t", "7", "shell", "getprop", "ro.build.product_3rdparty"]) => {
            match automotive_case.as_str() {
                "legacy" => (),
                "malformed" => println!("PRIVATE_RECORD_MUST_NOT_APPEAR/extra"),
                "product-changed" if checked_controls_grants() => println!("f6811"),
                _ => println!("f6808"),
            }
        }
        ("adb", ["-t", "7", "shell", "getprop", property]) => {
            println!(
                "{}",
                match *property {
                    "ro.build.fingerprint" => "fixture/device:11/TEST/1:user/test-keys",
                    "ro.build.version.sdk" => sdk,
                    "ro.product.cpu.abi" => "arm64-v8a",
                    "ro.product.name" => "f0506h",
                    "ro.serialno" => "fixture-hardware",
                    "ro.product.model" => "Fixture head unit",
                    _ => panic!("Unexpected fixture property"),
                }
            );
        }
        ("adb", ["-t", "7", "shell", "getenforce"]) => println!("Enforcing"),
        ("adb", ["-t", "7", "shell", "pm", "list", "features"]) => {
            println!("feature:reqGlEsVersion=0x20000\nfeature:android.hardware.type.automotive_extra");
            match automotive_case.as_str() {
                "legacy" | "library-only" => (),
                "overflow-version" => println!("feature:android.hardware.type.automotive=2147483648"),
                _ => println!("feature:android.hardware.type.automotive=2"),
            }
            if automotive_case == "malformed" { println!("PRIVATE_RECORD_MUST_NOT_APPEAR"); }
        }
        ("adb", ["-t", "7", "shell", "pm", "list", "libraries"]) => {
            if automotive_case == "malformed" { return; }
            println!("library:android.car.support\nlibrary:org.apache.http.legacy");
            if !matches!(automotive_case.as_str(), "legacy" | "feature-only" | "service-only") {
                println!("library:android.car");
            }
        }
        ("adb", ["-t", "7", "shell", "service", "check", "car_service"]) => {
            match automotive_case.as_str() {
                "legacy" | "feature-only" | "library-only" => println!("Service car_service: not found"),
                "malformed" => println!("Service car_service: found\nPRIVATE_RECORD_MUST_NOT_APPEAR"),
                _ => println!("Service car_service: found"),
            }
        }
        ("adb", ["-t", "7", "shell", "cmd", "car_service", "get-property-value", "0x1540010a", "0"]) => {
            match seat_case.as_str() {
                "modern" => println!("HalPropValue{{Property ID: INFO_DRIVER_SEAT(0x1540010a), Area ID: UNKNOWN, ElapsedRealtimeNanos: 5614347400, Status: AVAILABLE(0x0), Value: ROW_1_RIGHT(0x4)}}"),
                "invalid-ranges" => println!("HalPropValue{{Property ID: INFO_DRIVER_SEAT(0x1540010a), Area ID: UNKNOWN, ElapsedRealtimeNanos: 5614347400, Status: AVAILABLE(0x0), Value: ROW_1_LEFT(0x4)}}"),
                "missing" => println!("Permission Denial: PRIVATE_RECORD_MUST_NOT_APPEAR"),
                _ => println!("Property:0x1540010a, status: 0, timestamp: 4859883400, zone: 0x0, floatValues: [], int32Values: [1], int64Values: [], bytes: [], string: "),
            }
        }
        ("adb", ["-t", "7", "shell", "cmd", "car_service", "get-carpropertyconfig", id @ ("0x15400b80" | "0x15400b81")]) => {
            print_seat_memory_config(id, &seat_case);
        }
        ("adb", ["-t", "7", "shell", "cmd", "car_service", "get-carpropertyconfig", id @ ("0x11400e10" | "0x11400e00" | "0x11400e01")]) => {
            print_headlight_config(id, &light_case);
        }
        ("adb", ["-t", "7", "shell", "am", "get-current-user"]) => {
            if automotive_case == "bad-user" { println!("unknown"); }
            else if automotive_case == "user-changed" && checked_controls_grants() { println!("0"); }
            else if event_case == "driver-changed" &&
                fs::read_to_string(env::var_os("OVC_FIRMWARE_FIXTURE_LOG").unwrap()).unwrap().contains("\tshell\tlogcat\t") {
                println!("0");
            }
            else { println!("10"); }
        }
        ("adb", ["-t", "7", "shell", "pm", "path", "--user", "10", "org.openvoyah.controls"]) => {
            if event_case == "app-missing" { return; }
            let calls = fs::read_to_string(env::var_os("OVC_FIRMWARE_FIXTURE_LOG").unwrap()).unwrap();
            let checks = calls.matches("\tshell\tpm\tpath\t--user\t10\torg.openvoyah.controls").count();
            let path = match automotive_case.as_str() {
                "outside-path" => "/data/local/tmp/org.openvoyah.controls/base.apk",
                "dot-path" => "/data/app/~~Z9_eFI-00a==/../separate/base.apk",
                "wrong-tilde" => "/system/~~Z9_eFI-00a==/org.openvoyah.controls/base.apk",
                "path-mismatch" if checks > 2 => "/system/app/alternate-controls.apk",
                "path-changed" if checked_controls_grants() => "/system/app/alternate-controls.apk",
                _ => CONTROLS_APK,
            };
            println!("package:{path}");
        }
        ("adb", ["-t", "7", "shell", "dumpsys", "package", "check-permission", permission, "org.openvoyah.controls", "10"]) => {
            match *permission {
                "android.car.permission.CAR_EXTERIOR_LIGHTS" => println!("0"),
                "android.car.permission.CONTROL_CAR_EXTERIOR_LIGHTS" if automotive_case == "malformed" => println!("0\nPRIVATE_RECORD_MUST_NOT_APPEAR"),
                "android.car.permission.CONTROL_CAR_EXTERIOR_LIGHTS" => println!("-1"),
                "android.car.permission.CONTROL_CAR_SEATS" if automotive_case == "malformed" => {
                    eprintln!("PRIVATE_RECORD_MUST_NOT_APPEAR");
                    std::process::exit(1);
                }
                "android.car.permission.CONTROL_CAR_SEATS" => println!("-1"),
                _ => panic!("Unexpected permission query"),
            }
        }
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
            if *package == "org.openvoyah.controls" {
                if event_case == "app-missing" || automotive_case == "driver-user-only" { return; }
                println!("package:{CONTROLS_APK}");
            } else {
                println!("package:/system/app/{package}.apk");
            }
        }
        ("adb", ["-t", "7", "shell", "pidof", "org.openvoyah.controls"]) => {
            if event_case == "ambiguous" {
                println!("4321 4322");
            } else {
                println!("4321");
            }
        }
        (
            "adb",
            [
                "-t",
                "7",
                "shell",
                "pm",
                "list",
                "packages",
                "-U",
                "--user",
                "10",
                "org.openvoyah.controls",
            ],
        ) => {
            println!("package:org.openvoyah.controls uid:1010123");
            println!("package:org.openvoyah.controls.test uid:1010124");
        }
        ("adb", ["-t", "7", "shell", "ps", "-p", "4321", "-o", "PID,UID,NAME"]) => {
            let uid = if event_case == "other-owner" {
                1010124
            } else {
                1010123
            };
            println!("  PID   UID NAME\n 4321 {uid} org.openvoyah.controls");
        }
        ("adb", ["-t", "7", "shell", "cat", "/proc/4321/stat"]) => {
            let calls =
                fs::read_to_string(env::var_os("OVC_FIRMWARE_FIXTURE_LOG").unwrap()).unwrap();
            let restarted = event_case == "restarted" && calls.contains("\tshell\tlogcat\t");
            let ticks = if restarted { 999 } else { 123 };
            println!("4321 (fixture process) S {}{ticks} 0", "0 ".repeat(18));
        }
        ("adb", ["-t", "7", "shell", "date", "+%s"]) => {
            let calls =
                fs::read_to_string(env::var_os("OVC_FIRMWARE_FIXTURE_LOG").unwrap()).unwrap();
            let end = calls.matches("\tshell\tdate\t+%s").count() > 1;
            let seconds = if !end {
                0
            } else if event_case == "clock-jump" {
                90
            } else {
                2
            };
            println!("{}", 1_700_000_000 + seconds);
        }
        (
            "adb",
            [
                "-t",
                "7",
                "shell",
                "logcat",
                "-b",
                "main",
                "--pid=4321",
                "-d",
                "-T",
                "1700000001.000",
                "-m",
                "257",
                "-v",
                "threadtime",
                "-v",
                "epoch",
                "-s",
                "-e",
                "'^event=(headlight_(power|feedback|activity|reader|source)|driver_memory_(guidance|handoff)|vehicle_(observer|gear|signal|automation_monitor|automation_dispatch|workflow_runtime)|seasonal_climate_automatic|conditional_rule_(attempt|handoff)) '",
                "OpenVoyahControls:I",
            ],
        ) => {
            if event_case == "denied" {
                eprintln!("PRIVATE_RECORD_MUST_NOT_APPEAR");
                std::process::exit(1);
            }
            println!("--------- beginning of main");
            if event_case == "vehicle_workflows" {
                let header = "1700000001.145 4321 4360 I OpenVoyahControls:";
                for event in [
                    "event=vehicle_observer state=model_unreviewed product=f6808",
                    "event=vehicle_observer outcome=read_failed failure=SecurityException",
                    "event=vehicle_automation_monitor outcome=started",
                    "event=vehicle_automation_monitor outcome=stopped",
                    "event=vehicle_workflow_runtime outcome=started background=true",
                    "event=vehicle_workflow_runtime outcome=stopped",
                    "event=conditional_rule_attempt rule=12345678-1234-4234-9234-123456789abc outcome=unconfirmed",
                    "event=conditional_rule_handoff rule=12345678-1234-4234-9234-123456789abc outcome=completed",
                    "event=vehicle_automation_dispatch kind=headlight command=LowBeam outcome=requested",
                    "event=vehicle_automation_dispatch kind=driving_mode command=EcoMode outcome=unconfirmed",
                    "event=seasonal_climate_automatic season=winter outcome=partial completed=2",
                    "event=seasonal_climate_automatic season=summer outcome=recent completed=0",
                    "event=vehicle_signal signal=gear value=drive outcome=observed elapsed_ns=42 session=12345678-1234-4234-9234-123456789abc",
                    "event=vehicle_signal signal=outside_temperature value=-12.5 outcome=observed elapsed_ns=43 session=12345678-1234-4234-9234-123456789abc",
                    "event=vehicle_signal signal=door_driver value=unknown outcome=unavailable elapsed_ns=44 session=12345678-1234-4234-9234-123456789abc",
                    "event=vehicle_signal signal=gear value=open outcome=observed elapsed_ns=45 session=12345678-1234-4234-9234-123456789abc",
                    "event=vehicle_signal signal=gear value=unknown outcome=observed elapsed_ns=45 session=12345678-1234-4234-9234-123456789abc",
                    "event=vehicle_signal signal=driving_mode value=sport outcome=observed elapsed_ns=9223372036854775808 session=12345678-1234-4234-9234-123456789abc",
                    "event=vehicle_signal signal=door_driver value=open outcome=observed elapsed_ns=45 session=PRIVATE_RECORD_MUST_NOT_APPEAR",
                    "event=vehicle_automation_dispatch kind=headlight command=EcoMode outcome=requested",
                    "event=vehicle_automation_dispatch kind=driving_mode command=LowBeam outcome=confirmed",
                    "event=vehicle_automation_dispatch kind=headlight command=LampOff outcome=success",
                    "event=seasonal_climate_automatic season=winter outcome=accepted completed=6",
                    "event=vehicle_observer state=subscribed product=f6808 extra=PRIVATE_RECORD_MUST_NOT_APPEAR",
                    "event=vehicle_observer state=active product=f6808",
                    "event=vehicle_automation_monitor outcome=started private=PRIVATE_RECORD_MUST_NOT_APPEAR",
                ] {
                    println!("{header} {event}");
                }
                println!("1700000001.145 4322 4360 I OpenVoyahControls: event=vehicle_automation_monitor outcome=started");
                return;
            }
            if event_case == "handoff" {
                let header = "1700000001.145 4321 4360 I OpenVoyahControls:";
                let event = "event=driver_memory_handoff";
                for slot in 1..=3 {
                    for action in ["save", "restore"] {
                        for target in ["seats", "vehicle"] {
                            for outcome in ["requested", "unavailable", "failed"] {
                                println!("{header} {event} slot={slot} action={action} target={target} outcome={outcome} elapsed_ms=0");
                            }
                        }
                    }
                }
                let fields = "slot=2 action=save target=seats outcome=requested";
                println!("{header} {event} {fields} elapsed_ms=9223372036854775807");
                for invalid in [
                    "slot=4 action=save target=seats outcome=requested elapsed_ms=1",
                    "slot=1 action=capture target=seats outcome=requested elapsed_ms=1",
                    "slot=1 action=save target=controls outcome=requested elapsed_ms=1",
                    "slot=1 action=save target=seats outcome=confirmed elapsed_ms=1",
                    "slot=1 target=seats action=save outcome=requested elapsed_ms=1",
                    "slot=1 action=save target=seats outcome=requested elapsed_ms=-1",
                    "slot=1 action=save target=seats outcome=requested elapsed_ms=01",
                    "slot=1 action=save target=seats outcome=requested elapsed_ms=9223372036854775808",
                    "slot=1 action=save target=seats outcome=requested elapsed_ms=1 extra=PRIVATE_RECORD_MUST_NOT_APPEAR",
                ] {
                    println!("{header} {event} {invalid}");
                }
                println!("1700000001.145 4322 4360 I OpenVoyahControls: {event} {fields} elapsed_ms=1");
                println!("1700000002.000 4321 4360 I OpenVoyahControls: {event} {fields} elapsed_ms=1");
                return;
            }
            if event_case == "reader" {
                let header = "1700000001.145 4321 4360 I OpenVoyahControls:";
                let event = "event=headlight_reader source=android_automotive";
                for signal in ["selector", "headlights", "high_beam"] {
                    for outcome in [
                        "unsupported_system", "permission_not_granted", "not_connected",
                        "property_not_listed", "incompatible_config", "registration_rejected",
                        "access_denied", "read_failed", "subscribed",
                    ] {
                        println!("{header} {event} signal={signal} outcome={outcome} elapsed_ms=0");
                    }
                }
                let fields = "signal=headlights outcome=subscribed";
                println!("{header} {event} {fields} elapsed_ms=9223372036854775807");
                for rejected in [
                    "signal=position outcome=subscribed elapsed_ms=4",
                    "signal=headlights outcome=on elapsed_ms=4",
                    "signal=HEADLIGHTS outcome=subscribed elapsed_ms=4",
                    "outcome=subscribed signal=headlights elapsed_ms=4",
                    "signal=headlights outcome=subscribed elapsed_ms=-1",
                    "signal=headlights outcome=subscribed elapsed_ms=9223372036854775808",
                    "signal=headlights outcome=subscribed elapsed_ms=4 confirmed_at_ms=5",
                    "signal=headlights outcome=subscribed elapsed_ms=4 extra=PRIVATE_RECORD_MUST_NOT_APPEAR",
                ] {
                    println!("{header} {event} {rejected}");
                }
                println!("{header} event=headlight_reader source=qinggan {fields} elapsed_ms=4");
                println!("1700000000.999 4321 4360 I OpenVoyahControls: {event} {fields} elapsed_ms=4");
                println!("1700000001.145 4322 4360 I OpenVoyahControls: {event} {fields} elapsed_ms=4");
                return;
            }
            if event_case == "activity" {
                let header = "1700000001.145 4321 4360 I OpenVoyahControls:";
                let event = "event=headlight_activity source=android_automotive";
                for lamp in ["headlights", "high_beam"] {
                    for activity in ["off", "on", "daytime_running"] {
                        println!(
                            "{header} {event} lamp={lamp} activity={activity} elapsed_ms=0 confirmed_at_ms=1"
                        );
                    }
                }
                for fields in [
                    "lamp=position activity=on elapsed_ms=1 confirmed_at_ms=2",
                    "lamp=headlights activity=automatic elapsed_ms=1 confirmed_at_ms=2",
                    "activity=on lamp=headlights elapsed_ms=1 confirmed_at_ms=2",
                    "lamp=headlights activity=on elapsed_ms=-1 confirmed_at_ms=2",
                    "lamp=headlights activity=on elapsed_ms=1 confirmed_at_ms=0",
                    "lamp=headlights activity=on elapsed_ms=9223372036854775808 confirmed_at_ms=2",
                    "lamp=headlights activity=on elapsed_ms=1 confirmed_at_ms=9223372036854775808",
                    "lamp=headlights activity=on elapsed_ms=1 confirmed_at_ms=2 extra=PRIVATE_RECORD_MUST_NOT_APPEAR",
                ] {
                    println!("{header} {event} {fields}");
                }
                let fields = "lamp=headlights activity=on elapsed_ms=5 confirmed_at_ms=2";
                println!("{header} event=headlight_activity source=qinggan {fields}");
                println!("1700000000.999 4321 4360 I OpenVoyahControls: {event} {fields}");
                println!("1700000001.145 4322 4360 I OpenVoyahControls: {event} {fields}");
                return;
            }
            if event_case == "full" {
                for _ in 0..257 {
                    println!(
                        "1700000001.145 4321 4360 I OpenVoyahControls: event=headlight_source source=qinggan outcome=waiting_for_change"
                    );
                }
                return;
            }
            for message in [
                "event=headlight_power trigger=screen_off interactive=false elapsed_ms=8100",
                "event=headlight_power trigger=screen_on interactive=true elapsed_ms=8110",
                "event=headlight_source source=qinggan outcome=waiting_for_change",
                "event=headlight_source source=qinggan outcome=unsupported_product",
                "event=headlight_source source=qinggan outcome=service_not_found",
                "event=headlight_source source=qinggan outcome=reader_failed failure=BadParcelableException elapsed_ms=8111",
                "event=headlight_source source=android_automotive outcome=reader_failed failure=LinkageError elapsed_ms=8112",
                "event=headlight_feedback source=qinggan mode=automatic elapsed_ms=8120 confirmed_at_ms=1700000001100",
                "event=headlight_feedback source=qinggan mode=low_beam elapsed_ms=8130 confirmed_at_ms=1700000001120",
                "event=headlight_feedback source=qinggan mode=position elapsed_ms=8200 confirmed_at_ms=1700000001145",
                "event=headlight_feedback source=qinggan mode=off elapsed_ms=8240 confirmed_at_ms=1700000001160",
                "event=headlight_feedback source=android_automotive mode=on elapsed_ms=8300 confirmed_at_ms=1700000001200",
                "event=driver_memory_guidance slot=2 outcome=requested",
                "event=driver_memory_guidance slot=3 outcome=failed type=SecurityException",
                "event=headlight_feedback source=qinggan mode=on elapsed_ms=8200 confirmed_at_ms=1700000001100",
                "event=headlight_feedback source=qinggan mode=off elapsed_ms=1 confirmed_at_ms=2 extra=PRIVATE_RECORD_MUST_NOT_APPEAR",
                "event=headlight_source source=android_automotive outcome=reader_failed failure=LinkageError elapsed_ms=9223372036854775808",
                "event=headlight_source source=qinggan outcome=reader_failed failure=BadParcelableException elapsed_ms=8 detail=PRIVATE_RECORD_MUST_NOT_APPEAR",
                "event=headlight_power trigger=screen_off interactive=true elapsed_ms=8100",
                "event=driver_memory_guidance slot=0 outcome=requested",
                "event=unknown PRIVATE_RECORD_MUST_NOT_APPEAR",
            ] {
                println!("1700000001.145  4321 4360 I OpenVoyahControls: {message}");
            }
            for (time, pid, tag) in [
                ("1700000000.999", 4321, "OpenVoyahControls"),
                ("1700000002.000", 4321, "OpenVoyahControls"),
                ("1700000001.150", 4322, "OpenVoyahControls"),
                ("1700000001.150", 4321, "OtherTag"),
            ] {
                println!(
                    "{time} {pid} 4360 I {tag}: event=driver_memory_guidance slot=1 outcome=requested"
                );
            }
        }
        ("adb", ["-t", "7", "shell", "sha256sum", remote]) => {
            let hash = if automotive_case == "hash-changed" && *remote == CONTROLS_APK && checked_controls_grants() {
                "f".repeat(64)
            } else {
                env::var("OVC_FIRMWARE_FIXTURE_APK_SHA256").unwrap()
            };
            println!("{hash}  {remote}");
        }
        ("adb", ["-t", "7", "pull", remote, local]) if *remote == CONTROLS_APK || remote.starts_with("/system/app/") => {
            fs::write(local, APK).unwrap();
        }
        _ => {
            eprintln!("Unexpected offline firmware fixture command: {role} {command:?}");
            std::process::exit(2);
        }
    }
}

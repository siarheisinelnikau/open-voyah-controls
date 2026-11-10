use std::path::Path;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let command: Vec<&str> = args[1..].iter().map(String::as_str).collect();
    if Path::new(&args[0]).file_stem().and_then(|s| s.to_str()) == Some("aapt2") {
        if let ["dump", "badging", apk] = command.as_slice() {
            let package = if apk.ends_with("wrong.apk") {
                "com.example.wrong"
            } else {
                "org.openvoyah.controls"
            };
            println!("package: name='{package}' versionCode='6'");
            return;
        }
    }
    match command.as_slice() {
        ["devices", "-l"] => {
            println!("List of devices attached\nunit-a device model:Demo transport_id:7\nunit-b unauthorized transport_id:8");
        }
        ["-t", "7", "install", "-r", path] if path.ends_with("valid.apk") => println!("Success"),
        ["-t", "7", "uninstall", "-k", "org.openvoyah.controls"] => println!("Success"),
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

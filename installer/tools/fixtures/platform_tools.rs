use std::{env, fs, io::Write, path::PathBuf, thread, time::Duration};

fn main() {
    let executable = env::current_exe().unwrap();
    let directory = executable.parent().unwrap();
    let arguments: Vec<_> = env::args().skip(1).collect();
    let mut log = fs::OpenOptions::new()
        .append(true)
        .create(true)
        .open(env::var_os("OVC_PLATFORM_TOOLS_FIXTURE_LOG").unwrap())
        .unwrap();
    writeln!(log, "{}\t{}\t{}", std::process::id(), executable.display(), arguments.join("\t")).unwrap();
    drop(log);
    assert_eq!(env::current_dir().unwrap(), directory);
    let command = match arguments.as_slice() {
        [command] if command == "version" || command == "help" => command,
        _ => panic!("Only read-only version/help fixture commands are allowed"),
    };
    if directory.join(format!("hang-{command}")).exists() {
        thread::sleep(Duration::from_secs(30));
    }
    if directory.join(format!("fail-{command}")).exists() {
        eprintln!("Offline fixture could not load the selected tool libraries.");
        std::process::exit(7);
    }
    if directory.join("change-dll").exists() && command == "version" {
        fs::write(directory.join("AdbWinApi.dll"), b"changed while ADB was being verified").unwrap();
    }
    let output = fs::read_to_string(directory.join(format!("{command}.txt"))).unwrap();
    if directory.join("help-on-stderr").exists() && command == "help" {
        eprint!("{output}");
    } else {
        print!("{output}");
    }
    if let Some(path) = env::var_os("OVC_PLATFORM_TOOLS_FIXTURE_PID") {
        fs::write(PathBuf::from(path), std::process::id().to_string()).unwrap();
    }
}

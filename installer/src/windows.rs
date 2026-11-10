use crate::{
    adb::{self, Adb, Device, Result},
    device::{self, OemAction, message},
    production::{self, Identity, ReleaseApk},
    recovery,
    releases::{self, Catalog},
};
use std::{
    cell::{Cell, RefCell},
    collections::HashMap,
    ffi::c_void,
    fs::{self, File},
    io::BufWriter,
    mem::{size_of, zeroed},
    path::{Path, PathBuf},
    process::Command,
    ptr::{null, null_mut},
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicU32, Ordering},
        mpsc::{self, Receiver, Sender},
    },
    thread,
};
use windows_sys::Win32::{
    Foundation::*,
    Graphics::Gdi::*,
    Storage::{
        FileSystem::ReplaceFileW,
        Xps::{PW_CLIENTONLY, PrintWindow},
    },
    System::{
        Com::*,
        DataExchange::*,
        LibraryLoader::GetModuleHandleW,
        Memory::*,
        Ole::CF_UNICODETEXT,
        SystemInformation::GetSystemTimeAsFileTime,
        Threading::*,
        Time::{FileTimeToSystemTime, SystemTimeToFileTime},
    },
    UI::{
        Controls::{
            DRAWITEMSTRUCT, Dialogs::*, EM_SETCUEBANNER, InitCommonControls, ODS_DISABLED,
            ODS_FOCUS, ODS_SELECTED,
        },
        HiDpi::{AdjustWindowRectExForDpi, GetDpiForSystem},
        Input::KeyboardAndMouse::EnableWindow,
        Shell::*,
        WindowsAndMessaging::*,
    },
};

const TITLE: &str = "Open Voyah Controls Installer";
const WAKE: u32 = WM_APP + 1;
const SS_NOPREFIX: u32 = 0x80;
const fn rgb(red: u8, green: u8, blue: u8) -> u32 {
    red as u32 | (green as u32) << 8 | (blue as u32) << 16
}
const BG: u32 = rgb(240, 245, 244);
const INK: u32 = rgb(21, 35, 45);
const MUTED: u32 = rgb(77, 101, 112);
const SIDEBAR: u32 = rgb(16, 25, 35);
const WHITE: u32 = rgb(255, 255, 255);
const ACCENT: u32 = rgb(113, 223, 197);
const ACCENT_SOFT: u32 = rgb(225, 248, 240);
const ACCENT_INK: u32 = rgb(8, 95, 76);
const LINE: u32 = rgb(215, 227, 225);
const NAV_BASE: u32 = rgb(26, 42, 54);
const NAV_TEXT: u32 = rgb(208, 224, 230);
const DANGER: u32 = rgb(145, 45, 44);
const PANEL_TEXT: u16 = 901;
const PANEL_MUTED: u16 = 902;
const WORKSPACE_TEXT: u16 = 903;
const WORKSPACE_MUTED: u16 = 904;
const SIDEBAR_TEXT: u16 = 905;
const SIDEBAR_MUTED: u16 = 906;
const ACCENT_TEXT: u16 = 907;
const HERO_TITLE: u16 = 80;
const HERO_HINT: u16 = 81;
const DEVICE_BADGE: u16 = 82;
static ACTIVE_PAGE: AtomicU32 = AtomicU32::new(0);
static LAYOUT_DPI: AtomicU32 = AtomicU32::new(96);
static WINDOW_PAINTS: AtomicU32 = AtomicU32::new(0);
static CONTROL_PAINTS: AtomicU32 = AtomicU32::new(0);
const W: i32 = 1000;
const H: i32 = 674;
const DEVICE: u16 = 20;
const REFRESH: u16 = 21;
const HOST: u16 = 22;
const CONNECT: u16 = 23;
const DISCONNECT: u16 = 24;
const ADB_PATH: u16 = 25;
const APK: u16 = 30;
const BROWSE_APK: u16 = 31;
const INSTALL: u16 = 32;
const LAUNCH: u16 = 33;
const REMOVE: u16 = 34;
const BATCH: u16 = 35;
const RUNTIME: u16 = 36;
const INFO: u16 = 37;
const RECOVERY_PAGE: u16 = 38;
const FINGERPRINT: u16 = 40;
const READ_FINGERPRINT: u16 = 41;
const OEM: u16 = 42;
const RUN_OEM: u16 = 43;
const KEYBOARD: u16 = 44;
const BROWSE_KEYBOARD: u16 = 45;
const DATE: u16 = 46;
const CODE: u16 = 47;
const SAVED_CODES: u16 = 48;
const BEIJING: u16 = 49;
const STARTUP: u16 = 50;
const CHECK: u16 = 51;
const UPDATE: u16 = 52;
const RELEASE: u16 = 53;
const DOWNLOAD: u16 = 54;
const COLLECT: u16 = 55;
const LOGS: u16 = 56;
const STATUS: u16 = 60;
const CANCEL: u16 = 61;
const TABS: [u16; 4] = [70, 71, 72, 73];
const DAILY_COPY: [u16; 3] = [100, 101, 102];
const DAILY_DATE: [u16; 3] = [103, 104, 105];
const LEGACY_COPY: [u16; 4] = [110, 111, 112, 113];
const LEGACY_LABEL: [u16; 4] = [114, 115, 116, 117];
const PAGE_TITLES: [&str; 4] = [
    "Installation",
    "Engineering menu",
    "Maintenance",
    "Releases and logs",
];
const PAGE_HINTS: [&str; 4] = [
    "Connect a head unit and install Open Voyah Controls.",
    "Look up the access code for the date on your head unit.",
    "Service the selected unit and retain a recovery path.",
    "Find verified packages and collect diagnostics locally.",
];

#[derive(Clone, Copy)]
enum ServiceAction {
    Verify,
    BackupBoot,
    InstallBoot,
    Rollback,
    RestoreExported,
    Rearm,
    Migrate,
    Oem(OemAction),
}

const SERVICE_ACTIONS: [(&str, ServiceAction); 12] = [
    ("Verify production firmware", ServiceAction::Verify),
    ("Back up boot script to PC", ServiceAction::BackupBoot),
    ("Install optional boot entry", ServiceAction::InstallBoot),
    ("Roll back Controls", ServiceAction::Rollback),
    (
        "Restore Controls from PC backup",
        ServiceAction::RestoreExported,
    ),
    ("Allow fresh Controls setup", ServiceAction::Rearm),
    (
        "Remove old app for signer migration",
        ServiceAction::Migrate,
    ),
    (
        "Reset vehicle settings with backup",
        ServiceAction::Oem(OemAction::ResetSettings),
    ),
    (
        "Restore vehicle settings backup",
        ServiceAction::Oem(OemAction::RestoreSettings),
    ),
    (
        "Install signed OEM keyboard update",
        ServiceAction::Oem(OemAction::InstallKeyboard),
    ),
    (
        "Restore stock OEM keyboard",
        ServiceAction::Oem(OemAction::RestoreKeyboard),
    ),
    ("Restart head unit", ServiceAction::Oem(OemAction::Reboot)),
];

enum Job {
    Refresh,
    Connection {
        connect: bool,
        address: String,
    },
    Install {
        path: PathBuf,
        expected: Option<ReleaseApk>,
    },
    Batch(PathBuf),
    Launch,
    Remove,
    Runtime(PathBuf),
    Info,
    Fingerprint,
    Logs,
    Service {
        action: ServiceAction,
        fingerprint: String,
        input: String,
        backup: Option<PathBuf>,
    },
    Download(releases::Asset, bool),
}

enum Output {
    Status(String),
    Devices(Vec<Device>, String),
    Fingerprint(String),
    Apk(PathBuf, ReleaseApk),
    Update(PathBuf, String),
}

enum Event {
    Progress(String),
    Done(Result<Output>),
    Catalog(Result<Catalog>, bool),
}

#[derive(Clone)]
struct Events {
    hwnd: usize,
    tx: Sender<Event>,
}

impl Events {
    fn send(&self, event: Event) {
        if self.tx.send(event).is_ok() {
            unsafe { PostMessageW(self.hwnd as HWND, WAKE, 0, 0) };
        }
    }
}

struct State {
    hwnd: HWND,
    instance: HINSTANCE,
    dpi: i32,
    fonts: [HFONT; 6],
    controls: HashMap<u16, HWND>,
    panels: [Vec<HWND>; 4],
    connection: Vec<HWND>,
    daily: [String; 3],
    legacy: Vec<device::SavedCode>,
    devices: Vec<Device>,
    adb: Option<Adb>,
    storage: PathBuf,
    catalog: Catalog,
    apk_release: Option<(PathBuf, ReleaseApk)>,
    events: Events,
    rx: Receiver<Event>,
    cancel: Option<Arc<AtomicBool>>,
    checking: bool,
    closing: bool,
    demo: bool,
    probe: Option<Probe>,
}

struct Probe {
    file: PathBuf,
    phase: u8,
    counts: Vec<(bool, bool, u32, u32)>,
}

struct WindowSlot {
    state: RefCell<State>,
    pending_wake: Cell<bool>,
}

fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain([0]).collect()
}

fn storage() -> PathBuf {
    std::env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .unwrap_or_else(std::env::temp_dir)
        .join("OpenVoyahControlsInstaller")
}

fn run_job(
    job: Job,
    adb: Option<Adb>,
    device: Option<Device>,
    store: &Path,
    stop: &AtomicBool,
    progress: impl Fn(String),
) -> Result<Output> {
    if let Job::Download(asset, update) = job {
        let path = releases::download(&asset, &store.join("downloads"), stop, progress)?;
        return Ok(if update {
            Output::Update(
                path,
                releases::checksum(&asset)
                    .ok_or("Missing release digest.")?
                    .into(),
            )
        } else {
            Output::Apk(
                path,
                ReleaseApk {
                    version: releases::apk_version(&asset)
                        .ok_or("The release has an invalid APK version.")?,
                    sha256: releases::checksum(&asset)
                        .ok_or("Missing release digest.")?
                        .into(),
                },
            )
        });
    }
    let adb = adb
        .ok_or("Install Android SDK Platform-Tools, then select adb.exe under Releases & logs.")?;
    if matches!(job, Job::Refresh) {
        let devices = adb.devices(stop)?;
        let status = if devices.is_empty() {
            "No head unit found. Enable USB debugging, then connect by USB or enter its Wi-Fi address.".into()
        } else {
            format!(
                "Found {} device(s). Select the head unit before starting an operation.",
                devices.len()
            )
        };
        return Ok(Output::Devices(devices, status));
    }
    if let Job::Connection { connect, address } = job {
        let result = adb.text(
            [if connect { "connect" } else { "disconnect" }, &address],
            15,
            stop,
        )?;
        return Ok(Output::Devices(adb.devices(stop)?, result));
    }
    let selected = device.ok_or("Select a connected head unit first.")?;
    let target = adb.target(&selected, stop)?;
    let verification = store.join("verification");
    let result = match job {
        Job::Install { path, expected } => {
            production::install_controls(&target, &path, expected.as_ref(), &verification, progress)
        }
        Job::Batch(folder) => device::install_apks(
            &target,
            &device::apk_files(&folder)?,
            &verification,
            progress,
        ),
        Job::Launch => device::launch(&target),
        Job::Remove => recovery::uninstall(&target),
        Job::Runtime(path) => recovery::install_runtime(&target, &path, &verification, progress),
        Job::Info => recovery::device_status(&target),
        Job::Fingerprint => {
            return target
                .property("ro.build.fingerprint")
                .map(Output::Fingerprint);
        }
        Job::Logs => device::collect_logs(&target, &store.join("logs"), progress),
        Job::Service {
            action,
            fingerprint,
            input,
            backup,
        } => match action {
            ServiceAction::Verify => {
                let identity = Identity::confirmed(&target, &fingerprint)?;
                production::check_reviewed(&target, &identity, &verification)?;
                Ok("The exact build and reviewed OEM packages matched. APK, injector, and boot setup are separate steps.".into())
            }
            ServiceAction::BackupBoot => {
                recovery::export_boot_backup(&target, &fingerprint, &store.join("backups"))
            }
            ServiceAction::InstallBoot => recovery::install_boot(
                &target,
                &fingerprint,
                backup
                    .as_deref()
                    .ok_or("Choose a verified PC boot backup.")?,
                &verification,
            ),
            ServiceAction::Rollback => recovery::rollback(&target, &fingerprint),
            ServiceAction::RestoreExported => recovery::restore_exported(
                &target,
                &fingerprint,
                backup
                    .as_deref()
                    .ok_or("Choose a verified PC boot backup.")?,
            ),
            ServiceAction::Rearm => recovery::rearm(&target, &fingerprint, &verification),
            ServiceAction::Migrate => recovery::remove_for_migration(
                &target,
                &fingerprint,
                backup.as_deref().ok_or("Choose the app settings export.")?,
                &store.join("backups"),
            ),
            ServiceAction::Oem(action) => device::maintenance(
                &target,
                action,
                &fingerprint,
                &input,
                &store.join("backups"),
                progress,
            ),
        },
        _ => unreachable!(),
    };
    result.map(Output::Status)
}

impl State {
    fn scale(&self, px: i32) -> i32 {
        (px * self.dpi + 48) / 96
    }

    fn control(
        &mut self,
        panel: Option<usize>,
        id: u16,
        class: &str,
        text: &str,
        [x, y, width, height]: [i32; 4],
        style: u32,
        font: usize,
    ) -> HWND {
        unsafe {
            let ex = if class == "EDIT" { WS_EX_CLIENTEDGE } else { 0 };
            let handle = CreateWindowExW(
                ex,
                wide(class).as_ptr(),
                wide(text).as_ptr(),
                WS_CHILD | WS_VISIBLE | style,
                self.scale(x),
                self.scale(y),
                self.scale(width),
                self.scale(height),
                self.hwnd,
                id as usize as HMENU,
                self.instance,
                null(),
            );
            assert!(!handle.is_null(), "create {class} control");
            SendMessageW(handle, WM_SETFONT, self.fonts[font] as WPARAM, 0);
            SetWindowSubclass(handle, Some(count_control_paints), 1, 0);
            if id != 0 && id < PANEL_TEXT {
                self.controls.insert(id, handle);
            }
            if let Some(tab) = panel {
                self.panels[tab].push(handle);
            }
            handle
        }
    }

    fn styled_label(
        &mut self,
        tab: Option<usize>,
        id: u16,
        text: &str,
        rect: [i32; 4],
        font: usize,
    ) -> HWND {
        self.control(tab, id, "STATIC", text, rect, SS_NOPREFIX, font)
    }

    fn button(&mut self, tab: Option<usize>, id: u16, text: &str, rect: [i32; 4]) {
        self.control(
            tab,
            id,
            "BUTTON",
            text,
            rect,
            WS_TABSTOP | BS_OWNERDRAW as u32,
            0,
        );
    }

    fn edit(&mut self, tab: Option<usize>, id: u16, rect: [i32; 4], hint: &str) {
        let hwnd = self.control(
            tab,
            id,
            "EDIT",
            "",
            rect,
            WS_TABSTOP | ES_AUTOHSCROLL as u32,
            0,
        );
        unsafe { SendMessageW(hwnd, EM_SETCUEBANNER, 0, wide(hint).as_ptr() as LPARAM) };
    }

    fn combo(&mut self, tab: Option<usize>, id: u16, rect: [i32; 4]) {
        self.control(
            tab,
            id,
            "COMBOBOX",
            "",
            rect,
            WS_TABSTOP | WS_VSCROLL | CBS_DROPDOWNLIST as u32,
            0,
        );
    }

    fn init(&mut self) {
        self.styled_label(None, SIDEBAR_TEXT, "OPEN VOYAH", [22, 89, 176, 29], 1);
        self.styled_label(None, SIDEBAR_TEXT, "CONTROLS", [22, 117, 176, 27], 1);
        self.styled_label(
            None,
            SIDEBAR_MUTED,
            "DEVICE WORKSPACE",
            [23, 151, 174, 21],
            3,
        );
        for (index, label) in PAGE_TITLES.into_iter().enumerate() {
            self.button(
                None,
                TABS[index],
                label,
                [14, 203 + index as i32 * 52, 180, 42],
            );
        }
        self.styled_label(
            None,
            SIDEBAR_MUTED,
            "WINDOWS INSTALLER",
            [23, 603, 174, 20],
            3,
        );
        self.styled_label(
            None,
            SIDEBAR_TEXT,
            &format!("Version {}", env!("CARGO_PKG_VERSION")),
            [23, 626, 174, 22],
            0,
        );

        self.styled_label(
            None,
            WORKSPACE_MUTED,
            "OPEN VOYAH  /  WORKSPACE",
            [236, 23, 655, 19],
            3,
        );
        self.styled_label(None, HERO_TITLE, "", [234, 43, 735, 49], 2);
        self.styled_label(None, HERO_HINT, "", [237, 96, 726, 24], 0);

        self.connection = vec![
            self.styled_label(
                None,
                PANEL_TEXT,
                "Device connection",
                [252, 146, 402, 28],
                1,
            ),
            self.styled_label(
                None,
                DEVICE_BADGE,
                "NO UNIT SELECTED",
                [811, 151, 147, 22],
                3,
            ),
        ];
        self.combo(None, DEVICE, [252, 179, 551, 160]);
        self.button(None, REFRESH, "Refresh", [816, 177, 141, 34]);
        self.edit(
            None,
            HOST,
            [252, 224, 387, 30],
            "Wi-Fi address, e.g. 192.168.1.20:5555",
        );
        self.button(None, CONNECT, "Connect Wi-Fi", [652, 222, 142, 34]);
        self.button(None, DISCONNECT, "Disconnect", [807, 222, 150, 34]);
        for id in [DEVICE, REFRESH, HOST, CONNECT, DISCONNECT] {
            self.connection.push(self.controls[&id]);
        }

        let tab = Some(0);
        self.styled_label(
            tab,
            PANEL_TEXT,
            "Open Voyah Controls",
            [252, 292, 690, 28],
            1,
        );
        self.styled_label(
            tab,
            PANEL_MUTED,
            "Select a signed Controls release. Verified updates retain existing app data.",
            [253, 324, 700, 24],
            0,
        );
        self.edit(
            tab,
            APK,
            [252, 354, 551, 31],
            "Choose an Open Voyah Controls APK",
        );
        self.button(tab, BROWSE_APK, "Browse APK…", [816, 352, 141, 34]);
        self.button(tab, INSTALL, "Install or update", [252, 398, 174, 37]);
        self.button(tab, LAUNCH, "Open on device", [438, 398, 154, 37]);
        self.button(tab, REMOVE, "Remove app", [604, 398, 134, 37]);
        self.styled_label(tab, WORKSPACE_TEXT, "Other tools", [236, 473, 700, 23], 1);
        self.button(tab, BATCH, "Install APK folder…", [236, 502, 183, 36]);
        self.button(tab, RUNTIME, "Install Frida injector…", [431, 502, 198, 36]);
        self.button(tab, INFO, "Device status", [641, 502, 143, 36]);
        self.button(tab, RECOVERY_PAGE, "Boot and recovery", [795, 502, 163, 36]);

        let tab = Some(1);
        self.styled_label(
            tab,
            PANEL_TEXT,
            "Daily access codes",
            [252, 146, 480, 28],
            1,
        );
        self.styled_label(
            tab,
            PANEL_MUTED,
            "Codes change at midnight in Beijing. Use the date shown on the head unit.",
            [252, 179, 688, 23],
            0,
        );
        self.edit(tab, DATE, [252, 211, 156, 31], "YYYY-MM-DD");
        self.button(tab, CODE, "Use this date", [420, 210, 133, 34]);
        self.button(tab, BEIJING, "Use Beijing date", [565, 210, 156, 34]);
        for (index, label) in ["Yesterday", "Today", "Tomorrow"].into_iter().enumerate() {
            let left = [250, 505, 760][index];
            let style = if index == 1 { ACCENT_TEXT } else { PANEL_TEXT };
            self.styled_label(tab, style, label, [left, 286, 200, 24], 1);
            self.styled_label(tab, DAILY_DATE[index], "", [left, 312, 200, 20], 3);
            self.control(
                tab,
                DAILY_COPY[index],
                "BUTTON",
                "",
                [left, 341, 195, 44],
                WS_TABSTOP | BS_OWNERDRAW as u32,
                4,
            );
        }
        self.styled_label(
            tab,
            WORKSPACE_TEXT,
            "Earlier firmware",
            [236, 416, 482, 27],
            1,
        );
        self.styled_label(
            tab,
            WORKSPACE_MUTED,
            "Select a code to copy it. Letters are case-sensitive.",
            [236, 446, 554, 23],
            0,
        );
        self.button(tab, SAVED_CODES, "Load local codes…", [792, 425, 166, 35]);
        for index in 0..LEGACY_COPY.len() {
            let left = 236 + 367 * (index % 2) as i32;
            let top = 473 + 42 * (index / 2) as i32;
            self.styled_label(tab, LEGACY_LABEL[index], "", [left, top + 8, 196, 33], 5);
            self.control(
                tab,
                LEGACY_COPY[index],
                "BUTTON",
                "",
                [left + 198, top + 1, 158, 34],
                WS_TABSTOP | BS_OWNERDRAW as u32,
                5,
            );
        }

        let tab = Some(2);
        self.styled_label(
            tab,
            PANEL_TEXT,
            "Boot, recovery and service",
            [252, 291, 668, 27],
            1,
        );
        self.styled_label(
            tab,
            PANEL_MUTED,
            "Confirm the exact build. Boot and recovery require authorized ADB root.",
            [252, 323, 705, 24],
            0,
        );
        self.edit(
            tab,
            FINGERPRINT,
            [252, 355, 551, 31],
            "Exact build fingerprint",
        );
        self.button(tab, READ_FINGERPRINT, "Read build", [816, 353, 141, 35]);
        self.combo(tab, OEM, [252, 402, 485, 246]);
        self.set_items(
            OEM,
            SERVICE_ACTIONS.iter().map(|(label, _)| *label),
            Some(0),
        );
        self.button(tab, RUN_OEM, "Run operation…", [750, 399, 207, 36]);
        self.edit(
            tab,
            KEYBOARD,
            [252, 453, 551, 31],
            "Signed keyboard APK or exact package name for stock restore",
        );
        self.button(tab, BROWSE_KEYBOARD, "Keyboard APK…", [816, 451, 141, 35]);
        self.styled_label(
            tab,
            PANEL_MUTED,
            "Keep the boot backup on this PC. Roll back Controls before removing the app.",
            [252, 500, 704, 23],
            0,
        );

        let tab = Some(3);
        self.styled_label(tab, PANEL_TEXT, "Official releases", [252, 291, 668, 27], 1);
        self.control(
            tab,
            STARTUP,
            "BUTTON",
            "Check on startup",
            [252, 328, 173, 29],
            WS_TABSTOP | BS_AUTOCHECKBOX as u32,
            0,
        );
        let auto = self.demo
            || !matches!(
                fs::read_to_string(self.storage.join("startup-updates")).as_deref(),
                Ok("off")
            );
        unsafe { SendMessageW(self.controls[&STARTUP], BM_SETCHECK, usize::from(auto), 0) };
        self.button(tab, CHECK, "Check for updates", [431, 324, 163, 35]);
        self.button(tab, UPDATE, "Download and install", [605, 324, 198, 35]);
        self.combo(tab, RELEASE, [252, 376, 485, 140]);
        self.button(tab, DOWNLOAD, "Download APK", [750, 373, 207, 36]);
        self.styled_label(tab, WORKSPACE_TEXT, "Diagnostics", [236, 473, 680, 24], 1);
        self.button(tab, COLLECT, "Collect logs", [236, 502, 139, 36]);
        self.button(tab, LOGS, "Open logs folder", [387, 502, 166, 36]);
        self.button(tab, ADB_PATH, "Select adb.exe…", [565, 502, 156, 36]);

        self.styled_label(None, PANEL_TEXT, "Activity", [252, 577, 620, 23], 1);
        self.control(
            None,
            STATUS,
            "EDIT",
            "",
            [252, 603, 525, 43],
            WS_TABSTOP | WS_VSCROLL | (ES_MULTILINE | ES_AUTOVSCROLL | ES_READONLY) as u32,
            5,
        );
        self.button(None, CANCEL, "Cancel operation", [792, 603, 165, 35]);
        self.set_items(DEVICE, ["Select a head unit"], Some(0));
        self.set_items(RELEASE, ["Check for official APK releases"], Some(0));
        self.load_legacy();
        self.set_beijing_date();
        self.switch(0);
        self.status("Connect a head unit and authorize Android debugging.");
        self.enable_controls();
    }

    fn switch(&self, index: usize) {
        ACTIVE_PAGE.store(index as u32, Ordering::Relaxed);
        self.set(HERO_TITLE, PAGE_TITLES[index]);
        self.set(HERO_HINT, PAGE_HINTS[index]);
        unsafe {
            for (i, (tab, controls)) in TABS.iter().zip(self.panels.iter()).enumerate() {
                InvalidateRect(self.controls[tab], null(), 0);
                for hwnd in controls {
                    ShowWindow(*hwnd, if i == index { SW_SHOW } else { SW_HIDE });
                }
            }
            for hwnd in &self.connection {
                ShowWindow(*hwnd, if index == 1 { SW_HIDE } else { SW_SHOW });
            }
            InvalidateRect(self.hwnd, null(), 0);
        }
    }

    fn set(&self, id: u16, text: &str) {
        unsafe { SetWindowTextW(self.controls[&id], wide(text).as_ptr()) };
    }

    fn text(&self, id: u16) -> String {
        let hwnd = self.controls[&id];
        unsafe {
            let mut text = vec![0; GetWindowTextLengthW(hwnd) as usize + 1];
            let count = GetWindowTextW(hwnd, text.as_mut_ptr(), text.len() as i32);
            String::from_utf16_lossy(&text[..count as usize])
        }
    }

    fn status(&self, text: &str) {
        self.set(STATUS, text);
    }

    fn load_legacy(&mut self) {
        self.legacy = if self.demo {
            [
                "Free 2021 · Motorinvest",
                "Free 2021 / 2023 · China / Motorinvest",
                "Free 2023 · China",
                "Free 2021 · alternate firmware",
            ]
            .into_iter()
            .map(|label| device::SavedCode {
                label: label.into(),
                code: "••••••".into(),
            })
            .collect()
        } else {
            device::parse_saved_codes(include_str!(concat!(env!("OUT_DIR"), "/legacy-codes.json")))
                .unwrap_or_default()
        };
        self.show_legacy();
    }

    fn show_legacy(&self) {
        for (index, id) in LEGACY_COPY.into_iter().enumerate() {
            let entry = self.legacy.get(index);
            let label = entry
                .map(|code| code.label.as_str())
                .unwrap_or(if index == 0 {
                    "Load an early-firmware code list"
                } else {
                    ""
                });
            self.set(LEGACY_LABEL[index], label);
            self.set(id, entry.map(|code| code.code.as_str()).unwrap_or("—"));
            self.enable(id, entry.is_some() && !self.demo);
        }
    }

    fn set_beijing_date(&mut self) {
        let date = if self.demo {
            Date {
                year: 2025,
                month: 1,
                day: 15,
            }
        } else {
            Date::beijing()
        };
        let _ = self.show_codes(date);
    }

    fn show_codes(&mut self, date: Date) -> Result<()> {
        let days = [date.offset(-1)?, date, date.offset(1)?];
        for (index, day) in days.into_iter().enumerate() {
            self.daily[index] = device::engineering_code(day.year, day.month, day.day)?;
            self.set(DAILY_DATE[index], &day.label());
            self.set(DAILY_COPY[index], &self.daily[index]);
        }
        self.set(DATE, &date.label());
        Ok(())
    }

    fn set_items<'a>(
        &self,
        id: u16,
        items: impl IntoIterator<Item = &'a str>,
        selection: Option<usize>,
    ) {
        unsafe {
            let hwnd = self.controls[&id];
            SendMessageW(hwnd, CB_RESETCONTENT, 0, 0);
            for item in items {
                SendMessageW(hwnd, CB_ADDSTRING, 0, wide(item).as_ptr() as LPARAM);
            }
            if let Some(index) = selection {
                SendMessageW(hwnd, CB_SETCURSEL, index, 0);
            }
        }
    }

    fn index(&self, id: u16) -> Option<usize> {
        usize::try_from(unsafe { SendMessageW(self.controls[&id], CB_GETCURSEL, 0, 0) }).ok()
    }

    fn selected(&self) -> Option<&Device> {
        self.index(DEVICE).and_then(|i| self.devices.get(i))
    }

    fn enable(&self, id: u16, enabled: bool) {
        unsafe { EnableWindow(self.controls[&id], i32::from(enabled)) };
    }

    fn enable_controls(&self) {
        let free = self.cancel.is_none();
        let ready = free && self.selected().is_some_and(Device::ready);
        for id in [
            INSTALL,
            LAUNCH,
            REMOVE,
            BATCH,
            RUNTIME,
            INFO,
            READ_FINGERPRINT,
            RUN_OEM,
            COLLECT,
        ] {
            self.enable(id, ready);
        }
        for id in [
            REFRESH,
            CONNECT,
            DISCONNECT,
            ADB_PATH,
            BROWSE_APK,
            BROWSE_KEYBOARD,
            RECOVERY_PAGE,
        ] {
            self.enable(id, free);
        }
        self.enable(CANCEL, !free);
        self.enable(CHECK, !self.checking);
        self.enable(UPDATE, free && self.catalog.installer.is_some());
        self.enable(DOWNLOAD, free && !self.catalog.apk.is_empty());
        self.set(
            DEVICE_BADGE,
            if self.selected().is_some_and(Device::ready) {
                "ADB CONNECTED"
            } else {
                "NO UNIT SELECTED"
            },
        );
    }

    fn start(&mut self, job: Job) {
        if self.demo || self.cancel.is_some() {
            return;
        }
        self.status("Working…");
        let cancel = Arc::new(AtomicBool::new(false));
        self.cancel = Some(Arc::clone(&cancel));
        let events = self.events.clone();
        let store = self.storage.clone();
        let device = self.selected().cloned();
        let adb = self.adb.clone();
        self.enable_controls();
        thread::spawn(move || {
            let result = run_job(job, adb, device, &store, &cancel, |s| {
                events.send(Event::Progress(s))
            });
            events.send(Event::Done(result));
        });
    }

    fn check(&mut self, automatic: bool) {
        if self.demo || self.checking {
            return;
        }
        self.checking = true;
        if !automatic {
            self.status("Checking GitHub for official releases…");
        }
        self.enable_controls();
        let events = self.events.clone();
        thread::spawn(move || events.send(Event::Catalog(releases::check(), automatic)));
    }

    fn process_events(&mut self) {
        while let Ok(event) = self.rx.try_recv() {
            match event {
                Event::Progress(text) => self.status(&text),
                Event::Done(result) => {
                    self.cancel = None;
                    match result {
                        Ok(Output::Status(s)) => self.status(&s),
                        Ok(Output::Devices(devices, text)) => {
                            let selected = self.selected().map(|d| d.serial.clone());
                            self.devices = devices;
                            let labels: Vec<_> = self.devices.iter().map(Device::label).collect();
                            let preferred = selected
                                .and_then(|s| self.devices.iter().position(|d| d.serial == s));
                            if labels.is_empty() {
                                self.set_items(DEVICE, ["No devices found"], Some(0));
                            } else {
                                self.set_items(
                                    DEVICE,
                                    labels.iter().map(String::as_str),
                                    preferred,
                                );
                            }
                            self.status(&text);
                        }
                        Ok(Output::Fingerprint(s)) => {
                            self.set(FINGERPRINT, &s);
                            self.status("Verify the displayed build before running maintenance.");
                        }
                        Ok(Output::Apk(path, release)) => {
                            self.set(APK, &path.to_string_lossy());
                            self.apk_release = Some((path, release));
                            self.switch(0);
                            self.status("Release download matched its SHA-256. Select Install or update to check its APK signer and the selected device.");
                        }
                        Ok(Output::Update(path, digest)) => {
                            match stage_update(&path, &digest, &self.storage) {
                                Ok(()) => {
                                    unsafe { DestroyWindow(self.hwnd) };
                                    return;
                                }
                                Err(e) => self.status(&format!("Update could not be staged. {e}")),
                            }
                        }
                        Err(e) => self.status(&format!("Operation stopped. {e}")),
                    }
                    if self.closing {
                        unsafe { DestroyWindow(self.hwnd) };
                        return;
                    }
                }
                Event::Catalog(result, automatic) => {
                    self.checking = false;
                    match result {
                        Ok(catalog) => {
                            self.catalog = catalog;
                            if self.catalog.apk.is_empty() {
                                self.set_items(
                                    RELEASE,
                                    ["No official APK releases are available"],
                                    Some(0),
                                );
                            } else {
                                self.set_items(
                                    RELEASE,
                                    self.catalog.apk.iter().map(|(_, a)| a.name.as_str()),
                                    Some(0),
                                );
                            }
                            if self.cancel.is_none() {
                                if let Some((version, _)) = &self.catalog.installer {
                                    self.status(&format!("Installer {version} is available. Use Download and install under Releases & logs."));
                                } else if !automatic {
                                    self.status(&format!("Installer {} is current. Found {} verified APK release(s).",
                                        env!("CARGO_PKG_VERSION"), self.catalog.apk.len()));
                                }
                            }
                        }
                        Err(e) if !automatic && self.cancel.is_none() => {
                            self.status(&format!("Could not check releases. {e}"));
                        }
                        Err(_) => {}
                    }
                }
            }
            self.enable_controls();
        }
    }

    fn action(&mut self, id: u16) {
        let result = self.dispatch(id);
        if let Err(e) = result {
            self.status(&e);
        }
    }

    fn dispatch(&mut self, id: u16) -> Result<()> {
        if let Some(tab) = TABS.iter().position(|candidate| *candidate == id) {
            if tab == 1 {
                self.set_beijing_date();
                if self.cancel.is_none() {
                    self.status("Select a code to copy it. This tool works offline.");
                }
            }
            self.switch(tab);
            return Ok(());
        }
        if self.demo && !matches!(id, CODE | BEIJING) {
            return Ok(());
        }
        if let Some(index) = DAILY_COPY.iter().position(|candidate| *candidate == id) {
            copy_to_clipboard(self.hwnd, &self.daily[index])?;
            self.status("Engineering code copied.");
            return Ok(());
        }
        if let Some(index) = LEGACY_COPY.iter().position(|candidate| *candidate == id) {
            let code = self
                .legacy
                .get(index)
                .ok_or("Load a local code list first.")?;
            copy_to_clipboard(self.hwnd, &code.code)?;
            self.status("Engineering code copied.");
            return Ok(());
        }
        match id {
            REFRESH => self.start(Job::Refresh),
            CONNECT | DISCONNECT => {
                let mut address = self.text(HOST);
                if id == DISCONNECT && address.trim().is_empty() {
                    address = self
                        .selected()
                        .map(|d| d.serial.clone())
                        .filter(|s| s.contains(':'))
                        .ok_or("Enter a Wi-Fi address or select a Wi-Fi device.")?;
                }
                self.start(Job::Connection {
                    connect: id == CONNECT,
                    address: adb::endpoint(&address)?,
                });
            }
            BROWSE_APK | BROWSE_KEYBOARD => {
                if let Some(path) = choose_file(self.hwnd, "Android packages\0*.apk\0\0") {
                    if id == BROWSE_APK
                        && self
                            .apk_release
                            .as_ref()
                            .is_some_and(|(downloaded, _)| downloaded != &path)
                    {
                        self.apk_release = None;
                    }
                    self.set(
                        if id == BROWSE_APK { APK } else { KEYBOARD },
                        &path.to_string_lossy(),
                    );
                }
            }
            INSTALL => {
                let path = PathBuf::from(self.text(APK).trim());
                if !path.is_file() {
                    return Err("Choose an APK file first.".into());
                }
                let expected = self
                    .apk_release
                    .as_ref()
                    .filter(|(downloaded, _)| downloaded == &path)
                    .map(|(_, release)| release.clone());
                self.start(Job::Install { path, expected });
            }
            LAUNCH => self.start(Job::Launch),
            REMOVE => {
                if confirm(
                    self.hwnd,
                    "Remove Open Voyah Controls after checking boot and Frida state? Android will keep the app data.",
                ) {
                    self.start(Job::Remove);
                }
            }
            BATCH => {
                if let Some(folder) = choose_folder(self.hwnd) {
                    if confirm(
                        self.hwnd,
                        &format!(
                            "Install all standalone APKs in {} through ADB? Manage their permissions in Android app settings. Existing data is retained for updates.",
                            folder.display()
                        ),
                    ) {
                        self.start(Job::Batch(folder));
                    }
                }
            }
            RUNTIME => {
                if let Some(path) = choose_file(self.hwnd, "All files\0*.*\0\0") {
                    self.start(Job::Runtime(path));
                }
            }
            INFO => self.start(Job::Info),
            RECOVERY_PAGE => self.switch(2),
            READ_FINGERPRINT => self.start(Job::Fingerprint),
            RUN_OEM => {
                let index = self.index(OEM).ok_or("Select a maintenance operation.")?;
                let device = self.selected().ok_or("Select a head unit.")?;
                let (label, action) = *SERVICE_ACTIONS
                    .get(index)
                    .ok_or("Select a service operation.")?;
                let fingerprint = self.text(FINGERPRINT).trim().to_owned();
                if fingerprint.is_empty() {
                    return Err(
                        "Select Read build and confirm the head unit's fingerprint first.".into(),
                    );
                }
                let note = match action {
                    ServiceAction::Verify => "",
                    ServiceAction::BackupBoot => {
                        "Save and verify a copy of the original boot script on this PC."
                    }
                    ServiceAction::InstallBoot => {
                        "Select the verified backup folder for this unit. This changes its boot entry. A restart is separate."
                    }
                    ServiceAction::Rollback => {
                        "Stop Controls hooks and restore the original optional boot entry. The APK and its settings stay on the device."
                    }
                    ServiceAction::RestoreExported => {
                        "Use the verified backup from this exact unit if the device copy is unavailable. A restart may be required."
                    }
                    ServiceAction::Rearm => {
                        "Allow Controls to perform a fresh verified setup. Connect Frida from the app afterward."
                    }
                    ServiceAction::Migrate => {
                        "This removes the old Controls app and its data. Select a complete settings export from this unit. Continue only after a verified rollback."
                    }
                    ServiceAction::Oem(OemAction::ResetSettings) => {
                        "A complete backup is required before the settings database is moved aside."
                    }
                    ServiceAction::Oem(OemAction::RestoreSettings) => {
                        "The current settings will be kept in the device backup directory."
                    }
                    ServiceAction::Oem(OemAction::InstallKeyboard) => {
                        "Android will check the OEM signature before installing the update."
                    }
                    ServiceAction::Oem(OemAction::RestoreKeyboard) => {
                        "Enter com.qinggan.app.qgime or com.qinggan.app.qgime.second in the keyboard field."
                    }
                    ServiceAction::Oem(OemAction::Reboot) => {
                        "Park the vehicle and keep power stable before restarting the head unit."
                    }
                };
                if !note.is_empty()
                    && !confirm(
                        self.hwnd,
                        &format!("{label} on {}?\n\n{note}", device.serial),
                    )
                {
                    return Ok(());
                }
                let backup = if matches!(
                    action,
                    ServiceAction::InstallBoot | ServiceAction::RestoreExported
                ) {
                    let Some(folder) = choose_folder(self.hwnd) else {
                        return Ok(());
                    };
                    Some(folder)
                } else if matches!(action, ServiceAction::Migrate) {
                    let Some(file) = choose_file(self.hwnd, "Controls settings export\0*.json\0\0")
                    else {
                        return Ok(());
                    };
                    Some(file)
                } else {
                    None
                };
                self.start(Job::Service {
                    action,
                    fingerprint,
                    input: self.text(KEYBOARD).trim().into(),
                    backup,
                });
            }
            CODE => {
                let parts = self
                    .text(DATE)
                    .split('-')
                    .map(str::parse::<u16>)
                    .collect::<std::result::Result<Vec<_>, _>>()
                    .map_err(|_| "Enter the date as YYYY-MM-DD.")?;
                let [year, month, day] = parts.as_slice() else {
                    return Err("Enter the date as YYYY-MM-DD.".into());
                };
                self.show_codes(Date {
                    year: *year,
                    month: *month,
                    day: *day,
                })?;
            }
            BEIJING => self.set_beijing_date(),
            SAVED_CODES => {
                if let Some(path) = choose_file(self.hwnd, "Code lists\0*.json\0\0") {
                    self.legacy = device::saved_codes(&path)?;
                    self.show_legacy();
                    self.status("Local engineering codes loaded.");
                }
            }
            STARTUP => {
                let checked =
                    unsafe { SendMessageW(self.controls[&STARTUP], BM_GETCHECK, 0, 0) } != 0;
                let result = fs::create_dir_all(&self.storage).and_then(|_| {
                    fs::write(
                        self.storage.join("startup-updates"),
                        if checked { "on" } else { "off" },
                    )
                });
                if let Err(e) = result {
                    unsafe {
                        SendMessageW(
                            self.controls[&STARTUP],
                            BM_SETCHECK,
                            usize::from(!checked),
                            0,
                        )
                    };
                    return Err(message(e));
                }
                self.status("Startup update preference saved.");
            }
            CHECK => self.check(false),
            DOWNLOAD => {
                let (_, asset) = self
                    .index(RELEASE)
                    .and_then(|i| self.catalog.apk.get(i))
                    .ok_or("Select an APK release.")?;
                self.start(Job::Download(asset.clone(), false));
            }
            UPDATE => {
                let (version, asset) = self
                    .catalog
                    .installer
                    .as_ref()
                    .ok_or("Check for an installer update first.")?;
                if confirm(
                    self.hwnd,
                    &format!("Download installer {version}, replace this executable and restart?"),
                ) {
                    self.start(Job::Download(asset.clone(), true));
                }
            }
            COLLECT => self.start(Job::Logs),
            LOGS => {
                let folder = self.storage.join("logs");
                fs::create_dir_all(&folder).map_err(message)?;
                Command::new("explorer.exe")
                    .arg(folder)
                    .spawn()
                    .map_err(message)?;
            }
            ADB_PATH => {
                if let Some(path) = choose_file(self.hwnd, "ADB executable\0adb.exe\0\0") {
                    let adb = Adb::from_path(path.clone())?;
                    fs::create_dir_all(&self.storage).map_err(message)?;
                    fs::write(
                        self.storage.join("adb-path"),
                        path.to_string_lossy().as_bytes(),
                    )
                    .map_err(message)?;
                    self.adb = Some(adb);
                    self.devices.clear();
                    self.start(Job::Refresh);
                }
            }
            CANCEL => {
                if let Some(cancel) = &self.cancel {
                    cancel.store(true, Ordering::Relaxed);
                    self.status(
                        "Stopping the operation. Check the head unit before retrying a write.",
                    );
                }
            }
            _ => {}
        }
        Ok(())
    }
}

#[derive(Clone, Copy)]
struct Date {
    year: u16,
    month: u16,
    day: u16,
}

impl Date {
    const HOUR: i64 = 36_000_000_000;

    fn beijing() -> Self {
        let mut utc: FILETIME = unsafe { zeroed() };
        unsafe { GetSystemTimeAsFileTime(&mut utc) };
        Self::shift(utc, 8 * Self::HOUR).expect("Windows system clock")
    }

    fn label(self) -> String {
        format!("{:04}-{:02}-{:02}", self.year, self.month, self.day)
    }

    fn offset(self, days: i64) -> Result<Self> {
        let calendar = SYSTEMTIME {
            wYear: self.year,
            wMonth: self.month,
            wDay: self.day,
            ..Default::default()
        };
        let mut ticks: FILETIME = unsafe { zeroed() };
        if unsafe { SystemTimeToFileTime(&calendar, &mut ticks) } == 0 {
            return Err("Enter a valid date from the head unit.".into());
        }
        Self::shift(ticks, days * 24 * Self::HOUR)
    }

    fn shift(ticks: FILETIME, offset: i64) -> Result<Self> {
        let current = (u64::from(ticks.dwHighDateTime) << 32) | u64::from(ticks.dwLowDateTime);
        let shifted = current
            .checked_add_signed(offset)
            .ok_or("Date is outside the supported range.")?;
        let ticks = FILETIME {
            dwLowDateTime: shifted as u32,
            dwHighDateTime: (shifted >> 32) as u32,
        };
        let mut time: SYSTEMTIME = unsafe { zeroed() };
        if unsafe { FileTimeToSystemTime(&ticks, &mut time) } == 0 {
            return Err("Date is outside the supported range.".into());
        }
        Ok(Self {
            year: time.wYear,
            month: time.wMonth,
            day: time.wDay,
        })
    }
}

fn copy_to_clipboard(owner: HWND, value: &str) -> Result<()> {
    let buffer = wide(value);
    unsafe {
        let handle = GlobalAlloc(GMEM_MOVEABLE, buffer.len() * size_of::<u16>());
        if handle.is_null() {
            return Err("Could not allocate clipboard text.".into());
        }
        let memory = GlobalLock(handle).cast::<u16>();
        if memory.is_null() {
            GlobalFree(handle);
            return Err("Could not write clipboard text.".into());
        }
        std::ptr::copy_nonoverlapping(buffer.as_ptr(), memory, buffer.len());
        GlobalUnlock(handle);
        if OpenClipboard(owner) == 0 {
            GlobalFree(handle);
            return Err("The clipboard is busy. Try copying again.".into());
        }
        let success =
            EmptyClipboard() != 0 && !SetClipboardData(u32::from(CF_UNICODETEXT), handle).is_null();
        CloseClipboard();
        if !success {
            GlobalFree(handle);
            return Err("Could not copy the engineering code.".into());
        }
        Ok(())
    }
}

fn choose_file(owner: HWND, filter: &str) -> Option<PathBuf> {
    use std::os::windows::ffi::OsStringExt;
    let mut path = [0u16; 32768];
    let filter = wide(filter);
    let mut data: OPENFILENAMEW = unsafe { zeroed() };
    data.lStructSize = size_of::<OPENFILENAMEW>() as u32;
    data.hwndOwner = owner;
    data.lpstrFilter = filter.as_ptr();
    data.lpstrFile = path.as_mut_ptr();
    data.nMaxFile = path.len() as u32;
    data.Flags = OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST | OFN_NOCHANGEDIR | OFN_DONTADDTORECENT;
    if unsafe { GetOpenFileNameW(&mut data) } == 0 {
        return None;
    }
    let length = path.iter().position(|c| *c == 0)?;
    Some(std::ffi::OsString::from_wide(&path[..length]).into())
}

fn choose_folder(owner: HWND) -> Option<PathBuf> {
    use std::os::windows::ffi::OsStringExt;
    let mut name = [0u16; 260];
    let title = wide("Choose a folder containing standalone APK files");
    let mut data: BROWSEINFOW = unsafe { zeroed() };
    data.hwndOwner = owner;
    data.pszDisplayName = name.as_mut_ptr();
    data.lpszTitle = title.as_ptr();
    data.ulFlags = BIF_RETURNONLYFSDIRS | BIF_NEWDIALOGSTYLE;
    unsafe {
        let item = SHBrowseForFolderW(&data);
        if item.is_null() {
            return None;
        }
        let mut path = [0u16; 260];
        let ok = SHGetPathFromIDListW(item, path.as_mut_ptr());
        CoTaskMemFree(item.cast());
        if ok == 0 {
            return None;
        }
        let length = path.iter().position(|c| *c == 0)?;
        Some(std::ffi::OsString::from_wide(&path[..length]).into())
    }
}

fn confirm(owner: HWND, message: &str) -> bool {
    unsafe {
        MessageBoxW(
            owner,
            wide(message).as_ptr(),
            wide(TITLE).as_ptr(),
            MB_YESNO | MB_ICONQUESTION | MB_DEFBUTTON2,
        ) == IDYES
    }
}

unsafe extern "system" fn count_control_paints(
    hwnd: HWND,
    msg: u32,
    w: WPARAM,
    l: LPARAM,
    _: usize,
    _: usize,
) -> LRESULT {
    if msg == WM_PAINT && unsafe { GetUpdateRect(hwnd, null_mut(), 0) } != 0 {
        CONTROL_PAINTS.fetch_add(1, Ordering::Relaxed);
    }
    unsafe { DefSubclassProc(hwnd, msg, w, l) }
}

unsafe extern "system" fn window_proc(hwnd: HWND, msg: u32, w: WPARAM, l: LPARAM) -> LRESULT {
    unsafe {
        match msg {
            WM_PAINT => {
                if GetUpdateRect(hwnd, null_mut(), 0) != 0 {
                    WINDOW_PAINTS.fetch_add(1, Ordering::Relaxed);
                }
                let mut paint: PAINTSTRUCT = zeroed();
                let dc = BeginPaint(hwnd, &mut paint);
                paint_header(hwnd, dc);
                EndPaint(hwnd, &paint);
                return 0;
            }
            WM_PRINTCLIENT => {
                paint_header(hwnd, w as HDC);
                return 0;
            }
            WM_CTLCOLORSTATIC => {
                let dc = w as HDC;
                let id = GetDlgCtrlID(l as HWND) as u16;
                let (foreground, background) = match id {
                    SIDEBAR_TEXT => (WHITE, SIDEBAR),
                    SIDEBAR_MUTED => (NAV_TEXT, SIDEBAR),
                    HERO_TITLE | WORKSPACE_TEXT => (INK, BG),
                    HERO_HINT | WORKSPACE_MUTED => (MUTED, BG),
                    DEVICE_BADGE => (ACCENT_INK, WHITE),
                    ACCENT_TEXT => (ACCENT_INK, ACCENT_SOFT),
                    PANEL_MUTED => (MUTED, WHITE),
                    _ if id == DAILY_DATE[1] => (ACCENT_INK, ACCENT_SOFT),
                    _ if DAILY_DATE.contains(&id) => (MUTED, WHITE),
                    _ if LEGACY_LABEL.contains(&id) => (MUTED, BG),
                    _ => (INK, WHITE),
                };
                SetTextColor(dc, foreground);
                SetBkColor(dc, background);
                SetDCBrushColor(dc, background);
                return GetStockObject(DC_BRUSH) as LRESULT;
            }
            WM_CTLCOLORBTN => {
                SetTextColor(w as HDC, INK);
                SetBkColor(w as HDC, WHITE);
                SetDCBrushColor(w as HDC, WHITE);
                return GetStockObject(DC_BRUSH) as LRESULT;
            }
            WM_DRAWITEM => {
                if let Some(item) = (l as *const DRAWITEMSTRUCT).as_ref() {
                    paint_button(item);
                    return 1;
                }
            }
            WM_DESTROY => {
                PostQuitMessage(0);
                return 0;
            }
            _ => {}
        }
        let id = w as u16;
        let code = (w >> 16) as u16;
        let button = msg == WM_COMMAND && code == 0;
        let device_change = msg == WM_COMMAND && id == DEVICE && code == CBN_SELCHANGE as u16;
        if button || device_change || matches!(msg, WAKE | WM_CLOSE | WM_TIMER) {
            let slot = GetWindowLongPtrW(hwnd, GWLP_USERDATA) as *const WindowSlot;
            if let Some(slot) = slot.as_ref() {
                let Ok(mut state) = slot.state.try_borrow_mut() else {
                    if msg == WAKE {
                        slot.pending_wake.set(true);
                    }
                    return 0;
                };
                if button {
                    state.action(id);
                } else if device_change {
                    state.enable_controls();
                } else if msg == WAKE {
                    state.process_events();
                } else if msg == WM_TIMER {
                    state.sample();
                } else if state.cancel.is_none() {
                    DestroyWindow(hwnd);
                } else if confirm(
                    hwnd,
                    "Cancel the current operation and close once it has stopped?",
                ) {
                    state.closing = true;
                    state.action(CANCEL);
                }
                if slot.pending_wake.replace(false) {
                    PostMessageW(hwnd, WAKE, 0, 0);
                }
                return 0;
            }
        }
        DefWindowProcW(hwnd, msg, w, l)
    }
}

unsafe fn paint_header(hwnd: HWND, dc: HDC) {
    unsafe {
        let mut rect: RECT = zeroed();
        GetClientRect(hwnd, &mut rect);
        SetDCBrushColor(dc, BG);
        FillRect(dc, &rect, GetStockObject(DC_BRUSH) as HBRUSH);
        let dpi = LAYOUT_DPI.load(Ordering::Relaxed) as i32;
        let scale = |px: i32| (px * dpi + 48) / 96;
        let rect = |[left, top, right, bottom]: [i32; 4]| RECT {
            left: scale(left),
            top: scale(top),
            right: scale(right),
            bottom: scale(bottom),
        };
        SetDCBrushColor(dc, SIDEBAR);
        FillRect(
            dc,
            &rect([0, 0, 208, H]),
            GetStockObject(DC_BRUSH) as HBRUSH,
        );
        rounded(dc, rect([22, 23, 74, 75]), ACCENT, ACCENT, scale(12));
        SetDCBrushColor(dc, SIDEBAR);
        for glyph in [[32, 37, 64, 43], [32, 52, 46, 58], [52, 52, 64, 58]] {
            FillRect(dc, &rect(glyph), GetStockObject(DC_BRUSH) as HBRUSH);
        }
        SetDCBrushColor(dc, NAV_BASE);
        FillRect(
            dc,
            &rect([22, 584, 188, 585]),
            GetStockObject(DC_BRUSH) as HBRUSH,
        );
        let card = |bounds, tint, edge| rounded(dc, rect(bounds), tint, edge, scale(18));
        match ACTIVE_PAGE.load(Ordering::Relaxed) {
            1 => {
                card([234, 131, 975, 258], WHITE, LINE);
                card([234, 272, 464, 398], WHITE, LINE);
                card([489, 272, 719, 398], ACCENT_SOFT, rgb(166, 221, 206));
                card([744, 272, 974, 398], WHITE, LINE);
            }
            page => {
                card([234, 131, 975, 269], WHITE, LINE);
                let bottom = match page {
                    2 => 544,
                    3 => 435,
                    _ => 453,
                };
                card([234, 279, 975, bottom], WHITE, LINE);
            }
        }
        card([234, 562, 975, 659], WHITE, LINE);
    }
}

unsafe fn rounded(dc: HDC, rect: RECT, fill: u32, edge: u32, radius: i32) {
    unsafe {
        let brush = SelectObject(dc, GetStockObject(DC_BRUSH));
        let pen = SelectObject(dc, GetStockObject(DC_PEN));
        SetDCBrushColor(dc, fill);
        SetDCPenColor(dc, edge);
        RoundRect(
            dc,
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            radius,
            radius,
        );
        SelectObject(dc, pen);
        SelectObject(dc, brush);
    }
}

unsafe fn paint_button(item: &DRAWITEMSTRUCT) {
    unsafe {
        let id = item.CtlID as u16;
        let tab = TABS.iter().position(|candidate| *candidate == id);
        let active = tab.is_some_and(|index| index as u32 == ACTIVE_PAGE.load(Ordering::Relaxed));
        let disabled = item.itemState & ODS_DISABLED != 0;
        let pressed = item.itemState & ODS_SELECTED != 0;
        let main = matches!(id, INSTALL | RUN_OEM | UPDATE | CONNECT) || id == DAILY_COPY[1];
        let danger = id == REMOVE || id == CANCEL;
        let on_workspace = matches!(
            id,
            BATCH | RUNTIME | INFO | COLLECT | LOGS | ADB_PATH | SAVED_CODES
        ) || LEGACY_COPY.contains(&id);
        let background = if tab.is_some() {
            SIDEBAR
        } else if on_workspace {
            BG
        } else if id == DAILY_COPY[1] {
            ACCENT_SOFT
        } else {
            WHITE
        };
        let (fill, stroke, foreground) = if disabled {
            (rgb(238, 244, 243), LINE, rgb(112, 132, 136))
        } else if active || main {
            let tint = if pressed { rgb(88, 197, 173) } else { ACCENT };
            (tint, tint, SIDEBAR)
        } else if tab.is_some() {
            let tint = if pressed { NAV_BASE } else { SIDEBAR };
            (tint, tint, NAV_TEXT)
        } else {
            (
                if pressed { ACCENT_SOFT } else { WHITE },
                LINE,
                if danger { DANGER } else { INK },
            )
        };
        let dc = item.hDC;
        SetDCBrushColor(dc, background);
        FillRect(dc, &item.rcItem, GetStockObject(DC_BRUSH) as HBRUSH);
        let dpi = LAYOUT_DPI.load(Ordering::Relaxed) as i32;
        rounded(dc, item.rcItem, fill, stroke, 10 * dpi / 96);
        let hwnd = item.hwndItem;
        let mut title = vec![0; GetWindowTextLengthW(hwnd) as usize + 1];
        let count = GetWindowTextW(hwnd, title.as_mut_ptr(), title.len() as i32);
        let font = SendMessageW(hwnd, WM_GETFONT, 0, 0) as HFONT;
        let previous = SelectObject(dc, font);
        SetBkMode(dc, TRANSPARENT as i32);
        SetTextColor(dc, foreground);
        let mut text = item.rcItem;
        let margin = 12 * dpi / 96;
        text.left += margin;
        text.right -= margin;
        DrawTextW(
            dc,
            title.as_ptr(),
            count,
            &mut text,
            DT_VCENTER
                | DT_SINGLELINE
                | DT_NOPREFIX
                | DT_END_ELLIPSIS
                | if tab.is_some() { DT_LEFT } else { DT_CENTER },
        );
        SelectObject(dc, previous);
        if item.itemState & ODS_FOCUS != 0 {
            let mut focus = item.rcItem;
            InflateRect(&mut focus, -3, -3);
            DrawFocusRect(dc, &focus);
        }
    }
}

impl State {
    fn sample(&mut self) {
        let Some(probe) = &mut self.probe else {
            return;
        };
        probe.counts.push(unsafe {
            (
                IsWindowVisible(self.hwnd) != 0,
                IsIconic(self.hwnd) != 0,
                WINDOW_PAINTS.swap(0, Ordering::Relaxed),
                CONTROL_PAINTS.swap(0, Ordering::Relaxed),
            )
        });
        probe.phase += 1;
        unsafe {
            match probe.phase {
                3 => self.switch(2),
                6 => {
                    ShowWindow(self.hwnd, SW_HIDE);
                }
                9 => {
                    SetWindowPos(
                        self.hwnd,
                        null_mut(),
                        0,
                        0,
                        0,
                        0,
                        SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_SHOWWINDOW,
                    );
                }
                12 => {
                    KillTimer(self.hwnd, 1);
                    let _ = fs::write(
                        &probe.file,
                        format!(
                            "GDI window and native controls, 2 seconds per sample. (Visible, minimized, window paints, control paints)\n{:?}\n",
                            probe.counts
                        ),
                    );
                    DestroyWindow(self.hwnd);
                }
                _ => {}
            }
        }
    }
}

pub fn run() {
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    if args.first().is_some_and(|arg| arg == "--apply-update") {
        apply_update(&args[1..]);
        return;
    }
    let capture = (args.len() >= 2 && args[0] == "--capture").then(|| PathBuf::from(&args[1]));
    let probe = (args.len() >= 2 && args[0] == "--diagnose").then(|| Probe {
        file: PathBuf::from(&args[1]),
        phase: 0,
        counts: Vec::new(),
    });
    let demo = capture.is_some() || probe.is_some();
    unsafe {
        CoInitializeEx(null(), COINIT_APARTMENTTHREADED as u32);
        InitCommonControls();
        let instance = GetModuleHandleW(null());
        let requested_dpi = GetDpiForSystem() as i32;
        let mut work_area: RECT = zeroed();
        SystemParametersInfoW(SPI_GETWORKAREA, 0, (&mut work_area as *mut RECT).cast(), 0);
        let available_width = work_area.right - work_area.left - 24;
        let available_height = work_area.bottom - work_area.top - 56;
        let fitted = requested_dpi
            .min(available_width * 96 / W)
            .min(available_height * 96 / H)
            .max(96);
        let preview_dpi = capture
            .as_ref()
            .and_then(|_| args.get(3))
            .and_then(|a| a.to_str())
            .and_then(|a| a.parse::<i32>().ok())
            .filter(|dpi| (96..=144).contains(dpi));
        let dpi = preview_dpi.unwrap_or(fitted);
        LAYOUT_DPI.store(dpi as u32, Ordering::Relaxed);
        let class = wide("OpenVoyahControlsInstallerWindow");
        let icon = LoadIconW(instance, 1usize as *const u16);
        let window = WNDCLASSW {
            lpfnWndProc: Some(window_proc),
            hInstance: instance,
            hIcon: icon,
            hCursor: LoadCursorW(null_mut(), IDC_ARROW),
            lpszClassName: class.as_ptr(),
            ..zeroed()
        };
        if RegisterClassW(&window) == 0 {
            return;
        }
        let style = WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX | WS_CLIPCHILDREN;
        let mut rect = RECT {
            left: 0,
            top: 0,
            right: W * dpi / 96,
            bottom: H * dpi / 96,
        };
        AdjustWindowRectExForDpi(&mut rect, style, 0, WS_EX_CONTROLPARENT, dpi as u32);
        let hwnd = CreateWindowExW(
            WS_EX_CONTROLPARENT,
            class.as_ptr(),
            wide(TITLE).as_ptr(),
            style,
            CW_USEDEFAULT,
            CW_USEDEFAULT,
            rect.right - rect.left,
            rect.bottom - rect.top,
            null_mut(),
            null_mut(),
            instance,
            null(),
        );
        if hwnd.is_null() {
            return;
        }
        SendMessageW(hwnd, WM_SETICON, ICON_BIG as usize, icon as LPARAM);
        let make_font = |size: i32, weight: i32, family: &str| {
            CreateFontW(
                -size * dpi / 96,
                0,
                0,
                0,
                weight,
                0,
                0,
                0,
                DEFAULT_CHARSET as u32,
                OUT_DEFAULT_PRECIS as u32,
                CLIP_DEFAULT_PRECIS as u32,
                CLEARTYPE_QUALITY as u32,
                DEFAULT_PITCH as u32,
                wide(family).as_ptr(),
            )
        };
        let store = storage();
        let adb = if demo {
            None
        } else {
            fs::read_to_string(store.join("adb-path"))
                .ok()
                .map(PathBuf::from)
                .and_then(|path| Adb::from_path(path).ok())
                .or_else(Adb::discover)
        };
        let (tx, rx) = mpsc::channel();
        let mut state = Box::new(State {
            hwnd,
            instance,
            dpi,
            fonts: [
                make_font(16, 400, "Segoe UI"),
                make_font(20, 650, "Segoe UI"),
                make_font(40, 700, "Segoe UI"),
                make_font(13, 650, "Segoe UI"),
                make_font(27, 600, "Consolas"),
                make_font(14, 400, "Segoe UI"),
            ],
            controls: HashMap::new(),
            panels: Default::default(),
            connection: Vec::new(),
            daily: Default::default(),
            legacy: Vec::new(),
            devices: Vec::new(),
            adb,
            storage: store,
            catalog: Catalog::default(),
            apk_release: None,
            events: Events {
                hwnd: hwnd as usize,
                tx,
            },
            rx,
            cancel: None,
            checking: false,
            closing: false,
            demo,
            probe,
        });
        state.init();
        if let Some(path) = capture {
            let tab = args
                .get(2)
                .and_then(|a| a.to_str())
                .and_then(|a| a.parse::<usize>().ok())
                .unwrap_or(0)
                .min(3);
            state.switch(tab);
            if matches!(tab, 0 | 2) {
                let sample = Device {
                    serial: "DEMO-USB".into(),
                    model: "Sample head unit".into(),
                    state: "device".into(),
                    transport: Some(1),
                };
                state.set_items(DEVICE, [sample.label().as_str()], Some(0));
                state.devices.push(sample);
                if tab == 0 {
                    state.set(APK, "OpenVoyahControls-v0.12.0.apk");
                    state.status("Preview device and signed release selected.");
                } else {
                    state.set(
                        FINGERPRINT,
                        "sample/device/demo:11/preview/1:user/release-keys",
                    );
                    state.set_items(
                        OEM,
                        SERVICE_ACTIONS.iter().map(|(label, _)| *label),
                        Some(3),
                    );
                    state.status("Preview recovery on a sample head unit.");
                }
                state.enable_controls();
            } else if tab == 1 {
                for id in DAILY_COPY {
                    state.set(id, "•••••");
                }
                state.status("Sample date. Access codes are masked in this preview.");
            }
            SetWindowPos(
                hwnd,
                HWND_BOTTOM,
                0,
                0,
                0,
                0,
                SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_SHOWWINDOW,
            );
            RedrawWindow(
                hwnd,
                null(),
                null_mut(),
                RDW_INVALIDATE | RDW_UPDATENOW | RDW_ALLCHILDREN,
            );
            let result = capture_client(hwnd, &path);
            DestroyWindow(hwnd);
            for font in state.fonts {
                DeleteObject(font);
            }
            CoUninitialize();
            if result.is_err() {
                std::process::exit(2);
            }
            return;
        }
        if demo {
            SetWindowPos(
                hwnd,
                null_mut(),
                0,
                0,
                0,
                0,
                SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_SHOWWINDOW,
            );
            SetTimer(hwnd, 1, 2000, None);
        } else {
            ShowWindow(hwnd, SW_SHOWNORMAL);
            if state.adb.is_some() {
                state.start(Job::Refresh);
            }
            if SendMessageW(state.controls[&STARTUP], BM_GETCHECK, 0, 0) != 0 {
                state.check(true);
            }
        }
        let slot = WindowSlot {
            state: RefCell::new(*state),
            pending_wake: Cell::new(false),
        };
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, &slot as *const WindowSlot as isize);
        let mut msg = zeroed();
        while GetMessageW(&mut msg, null_mut(), 0, 0) > 0 {
            if IsDialogMessageW(hwnd, &msg) == 0 {
                TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
        }
        for font in slot.state.into_inner().fonts {
            DeleteObject(font);
        }
        CoUninitialize();
    }
}

fn capture_client(hwnd: HWND, path: &Path) -> Result<()> {
    unsafe {
        let mut rect: RECT = zeroed();
        GetClientRect(hwnd, &mut rect);
        let screen = GetDC(hwnd);
        let memory = CreateCompatibleDC(screen);
        let mut info: BITMAPINFO = zeroed();
        info.bmiHeader.biSize = size_of::<BITMAPINFOHEADER>() as u32;
        info.bmiHeader.biWidth = rect.right;
        info.bmiHeader.biHeight = -rect.bottom;
        info.bmiHeader.biPlanes = 1;
        info.bmiHeader.biBitCount = 32;
        let mut bits: *mut c_void = null_mut();
        let bitmap = CreateDIBSection(memory, &info, DIB_RGB_COLORS, &mut bits, null_mut(), 0);
        if bitmap.is_null() || bits.is_null() {
            DeleteDC(memory);
            ReleaseDC(hwnd, screen);
            return Err("Could not allocate the GDI capture surface.".into());
        }
        let previous = SelectObject(memory, bitmap);
        let captured = PrintWindow(hwnd, memory, PW_CLIENTONLY);
        GdiFlush();
        let pixels =
            std::slice::from_raw_parts(bits.cast::<u8>(), (rect.right * rect.bottom * 4) as usize);
        let rgb: Vec<_> = pixels
            .chunks_exact(4)
            .flat_map(|p| [p[2], p[1], p[0]])
            .collect();
        SelectObject(memory, previous);
        DeleteObject(bitmap);
        DeleteDC(memory);
        ReleaseDC(hwnd, screen);
        if captured == 0 {
            return Err("The native controls could not be rendered to the capture surface.".into());
        }
        let file = File::create(path).map_err(message)?;
        let mut encoder =
            png::Encoder::new(BufWriter::new(file), rect.right as u32, rect.bottom as u32);
        encoder.set_color(png::ColorType::Rgb);
        encoder
            .write_header()
            .map_err(message)?
            .write_image_data(&rgb)
            .map_err(message)
    }
}

fn stage_update(download: &Path, digest: &str, storage: &Path) -> Result<()> {
    use std::io::{Read, Seek, SeekFrom};
    let mut file = File::open(download).map_err(message)?;
    let mut dos = [0; 64];
    file.read_exact(&mut dos).map_err(message)?;
    let pe_offset = u32::from_le_bytes(dos[60..64].try_into().unwrap());
    let mut pe = [0; 94];
    file.seek(SeekFrom::Start(pe_offset.into()))
        .and_then(|_| file.read_exact(&mut pe))
        .map_err(message)?;
    if &dos[..2] != b"MZ"
        || &pe[..6] != b"PE\0\0\x64\x86"
        || pe[24..26] != [0x0b, 0x02]
        || pe[92..94] != [2, 0]
    {
        return Err("The release file is not a 64-bit Windows GUI executable.".into());
    }
    let current = std::env::current_exe().map_err(message)?;
    let helper_dir = device::new_folder(&storage.join("updates"), "apply")?;
    let helper = helper_dir.join("update-helper.exe");
    fs::copy(&current, &helper).map_err(message)?;
    Command::new(helper)
        .arg("--apply-update")
        .arg(std::process::id().to_string())
        .arg(&current)
        .arg(download)
        .arg(digest)
        .spawn()
        .map_err(message)?;
    Ok(())
}

fn apply_update(args: &[std::ffi::OsString]) {
    let result = (|| -> Result<()> {
        let [pid, current, download, digest] = args else {
            return Err("Update handoff was incomplete.".into());
        };
        let pid: u32 = pid
            .to_str()
            .and_then(|s| s.parse().ok())
            .ok_or("Invalid update process.")?;
        let current = PathBuf::from(current);
        let download = PathBuf::from(download);
        let digest = digest
            .to_str()
            .filter(|d| d.len() == 64 && d.bytes().all(|b| b.is_ascii_hexdigit()))
            .ok_or("Invalid update digest.")?;
        let handle = unsafe { OpenProcess(PROCESS_SYNCHRONIZE, 0, pid) };
        if !handle.is_null() {
            let wait = unsafe { WaitForSingleObject(handle, 20_000) };
            unsafe { CloseHandle(handle) };
            if wait != WAIT_OBJECT_0 {
                return Err(
                    "The installer did not close. The existing executable was kept.".into(),
                );
            }
        }
        let new = current.with_extension("update.exe");
        let backup = current.with_extension("previous.exe");
        if new.exists() || backup.exists() {
            return Err("An earlier update file is still beside the installer.".into());
        }
        let mut input = File::open(&download).map_err(message)?;
        let mut staged = File::options()
            .write(true)
            .create_new(true)
            .open(&new)
            .map_err(message)?;
        let copy = std::io::copy(&mut input, &mut staged).and_then(|_| staged.sync_all());
        drop(staged);
        if let Err(e) = copy {
            let _ = fs::remove_file(&new);
            return Err(message(e));
        }
        let staged_digest = device::digest(&new, &AtomicBool::new(false));
        if !staged_digest.is_ok_and(|actual| actual.eq_ignore_ascii_case(digest)) {
            let _ = fs::remove_file(&new);
            return Err("The staged installer did not match the release digest.".into());
        }
        if let Err(e) = replace_file(&current, &new, &backup) {
            if !current.exists() && backup.exists() {
                let _ = fs::rename(&backup, &current);
            }
            let _ = fs::remove_file(&new);
            return Err(message(e));
        }
        if let Err(e) = Command::new(&current).spawn() {
            return match replace_file(&current, &backup, &new) {
                Ok(()) => {
                    let _ = fs::remove_file(&new);
                    Err(format!(
                        "Could not restart. The previous installer was restored. {e}"
                    ))
                }
                Err(error) => Err(format!(
                    "Could not restart or roll back. Backup: {}. {error}",
                    backup.display()
                )),
            };
        }
        let _ = fs::remove_file(&backup);
        Ok(())
    })();
    if let Err(e) = result {
        unsafe {
            MessageBoxW(
                null_mut(),
                wide(&e).as_ptr(),
                wide(TITLE).as_ptr(),
                MB_ICONERROR,
            );
        }
    }
}

fn replace_file(destination: &Path, replacement: &Path, backup: &Path) -> std::io::Result<()> {
    use std::os::windows::ffi::OsStrExt;
    let path = |p: &Path| p.as_os_str().encode_wide().chain([0]).collect::<Vec<u16>>();
    let (destination, replacement, backup) = (path(destination), path(replacement), path(backup));
    let success = unsafe {
        ReplaceFileW(
            destination.as_ptr(),
            replacement.as_ptr(),
            backup.as_ptr(),
            0,
            null_mut(),
            null_mut(),
        )
    };
    if success == 0 {
        Err(std::io::Error::last_os_error())
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn engineering_dates_cross_beijing_midnight_and_calendar_boundaries() {
        let utc = SYSTEMTIME {
            wYear: 2026,
            wMonth: 12,
            wDay: 31,
            wHour: 16,
            ..Default::default()
        };
        let mut ticks: FILETIME = unsafe { zeroed() };
        assert_ne!(unsafe { SystemTimeToFileTime(&utc, &mut ticks) }, 0);
        let beijing = Date::shift(ticks, 8 * Date::HOUR).unwrap();
        assert_eq!(beijing.label(), "2027-01-01");
        assert_eq!(beijing.offset(-1).unwrap().label(), "2026-12-31");
        assert_eq!(beijing.offset(1).unwrap().label(), "2027-01-02");
        assert_eq!(
            Date {
                year: 2028,
                month: 3,
                day: 1
            }
            .offset(-1)
            .unwrap()
            .label(),
            "2028-02-29"
        );
    }

    #[test]
    fn portable_replacement_keeps_a_rollback_copy() {
        let root = device::new_folder(
            &Path::new(env!("CARGO_MANIFEST_DIR")).join("target"),
            "update-test",
        )
        .unwrap();
        let current = root.join("installer.exe");
        let next = root.join("staged.exe");
        let backup = root.join("previous.exe");
        fs::write(&current, b"previous").unwrap();
        fs::write(&next, b"verified").unwrap();
        replace_file(&current, &next, &backup).unwrap();
        assert_eq!(fs::read(&current).unwrap(), b"verified");
        assert_eq!(fs::read(&backup).unwrap(), b"previous");
        replace_file(&current, &backup, &next).unwrap();
        assert_eq!(fs::read(&current).unwrap(), b"previous");
    }

    #[test]
    fn installer_jobs_validate_apks_and_use_only_the_selected_transport() {
        let project = Path::new(env!("CARGO_MANIFEST_DIR"));
        let root = device::new_folder(&project.join("target"), "mock-sdk").unwrap();
        let platform = root.join("platform-tools");
        let build = root.join("build-tools/35.0.0");
        fs::create_dir_all(&platform).unwrap();
        fs::create_dir_all(&build).unwrap();
        let executable = platform.join("adb.exe");
        let compiler = std::env::var_os("RUSTC").unwrap_or_else(|| "rustc".into());
        assert!(
            Command::new(compiler)
                .arg(project.join("tests/fixtures/android_tools.rs"))
                .arg("-o")
                .arg(&executable)
                .status()
                .unwrap()
                .success()
        );
        fs::copy(&executable, build.join("aapt2.exe")).unwrap();
        fs::create_dir_all(build.join("lib")).unwrap();
        fs::write(build.join("lib/apksigner.jar"), b"offline signer fixture").unwrap();
        let java = root.join("jre/bin/java.exe");
        fs::create_dir_all(java.parent().unwrap()).unwrap();
        fs::copy(&executable, java).unwrap();
        for (file, data) in [
            ("valid.apk", "valid"),
            ("update.apk", "update"),
            ("wrong.apk", "wrong"),
            ("bad-signer.apk", "wrong-signer"),
        ] {
            fs::write(root.join(file), data).unwrap();
        }
        let stop = AtomicBool::new(false);
        assert!(
            matches!(Adb::from_path(executable.clone()), Err(e) if e.contains("AdbWinApi.dll"))
        );
        fs::write(platform.join("AdbWinApi.dll"), b"mock adb library").unwrap();
        assert!(
            matches!(Adb::from_path(executable.clone()), Err(e) if e.contains("AdbWinUsbApi.dll"))
        );
        fs::write(platform.join("AdbWinUsbApi.dll"), b"mock adb USB library").unwrap();
        let adb = Adb::from_path(executable).unwrap();
        let devices = adb.devices(&stop).unwrap();
        let perform = |job, index: usize| {
            run_job(
                job,
                Some(adb.clone()),
                Some(devices[index].clone()),
                &root,
                &stop,
                |_| {},
            )
        };
        let install = |name: &str| Job::Install {
            path: root.join(name),
            expected: None,
        };
        let service = |action, fingerprint: &str, backup| Job::Service {
            action,
            fingerprint: fingerprint.into(),
            input: String::new(),
            backup,
        };
        let status = |job| matches!(perform(job, 0), Ok(Output::Status(_)));
        assert!(matches!(
            perform(install("valid.apk"), 0),
            Ok(Output::Status(_))
        ));
        assert!(matches!(perform(Job::Launch, 0), Ok(Output::Status(_))));
        assert!(matches!(
            perform(Job::Info, 0),
            Ok(Output::Status(text)) if text.contains("Installed") && text.contains("None reported")
        ));
        assert!(
            matches!(perform(install("wrong.apk"), 0), Err(e) if e.contains("com.example.wrong"))
        );
        assert!(matches!(perform(install("bad-signer.apk"), 0), Err(e) if e.contains("signer")));
        fs::write(platform.join("installed-apk"), "newer").unwrap();
        assert!(
            matches!(perform(install("valid.apk"), 0), Err(e) if e.contains("newer Controls release"))
        );
        fs::write(platform.join("installed-apk"), "valid").unwrap();
        fs::write(platform.join("legacy-files"), "prior staged worker").unwrap();
        assert!(
            matches!(perform(install("update.apk"), 0), Err(e) if e.contains("Roll back Controls"))
        );
        fs::write(platform.join("active-hooks"), "running").unwrap();
        assert!(matches!(perform(Job::Remove, 0), Err(e) if e.contains("Roll back Controls")));
        assert!(platform.join("installed-apk").is_file());
        assert!(status(service(
            ServiceAction::BackupBoot,
            "example/demo/build",
            None
        )));
        let export = fs::read_dir(root.join("backups"))
            .unwrap()
            .next()
            .unwrap()
            .unwrap()
            .path();
        assert_eq!(
            fs::read(export.join("original")).unwrap(),
            b"#!/system/bin/sh\necho stock\n"
        );
        assert!(status(service(
            ServiceAction::Rollback,
            "example/demo/build",
            None
        )));
        assert!(status(Job::Remove));
        fs::write(platform.join("active-hooks"), "orphaned without APK").unwrap();
        assert!(status(service(
            ServiceAction::RestoreExported,
            "example/demo/build",
            Some(export.clone())
        )));
        fs::write(export.join("original"), "changed").unwrap();
        assert!(matches!(
            perform(service(ServiceAction::RestoreExported, "example/demo/build", Some(export)), 0),
            Err(e) if e.contains("PC boot backup")
        ));
        assert!(matches!(perform(install("valid.apk"), 1), Err(e) if e.contains("authorized")));
        assert!(matches!(
            perform(service(ServiceAction::Oem(OemAction::ResetSettings), "other-build", None), 0),
            Err(e) if e.contains("fingerprint")
        ));
        assert!(status(install("valid.apk")));
        let archive = root.join("settings-export.json");
        fs::write(&archive, r#"{"format":"org.openvoyah.controls.settings","schema":1,"buttons":{"schema":1,"bindings":[]},"voice":{},"localization":{},"language":"en","display":{"density":240,"clusterMedia":false}}"#).unwrap();
        assert!(status(service(
            ServiceAction::Migrate,
            "example/demo/build",
            Some(archive)
        )));
        assert!(platform.join("full-remove").is_file());
        fs::write(
            platform.join("foreign-transport"),
            "changed after selection",
        )
        .unwrap();
        assert!(matches!(perform(Job::Launch, 0), Err(e) if e.contains("transport changed")));
    }
}

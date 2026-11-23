use crate::{
    adb::{self, Adb, Device, Result},
    apps::{self, App},
    device::{self, OemAction, message},
    production::{self, ApkChoice, ApkFacts, Build, Identity, Product, ReleaseApk, Trust},
    recovery,
    releases::{self, Catalog},
    setup,
    turnkey::{self, Lang, STEPS, StepState},
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
/// The ordinal of the application icon that `build.rs` embeds as a resource.
const APP_ICON_RESOURCE: usize = 1;
const SS_NOPREFIX: u32 = 0x80;
const fn rgb(red: u8, green: u8, blue: u8) -> u32 {
    red as u32 | (green as u32) << 8 | (blue as u32) << 16
}
// The same roles as the Android app: its graphite surface for the sidebar, its blue accent for actions and its
// daytime palette for the workspace, so the two halves of the product read as one.
const BG: u32 = rgb(238, 243, 248);
const INK: u32 = rgb(24, 37, 58);
const MUTED: u32 = rgb(63, 83, 107);
const SIDEBAR: u32 = rgb(20, 22, 31);
const WHITE: u32 = rgb(255, 255, 255);
const ACCENT: u32 = rgb(100, 137, 255);
const ACCENT_PRESSED: u32 = rgb(78, 112, 224);
const ACCENT_SOFT: u32 = rgb(216, 231, 255);
const ACCENT_SOFT_EDGE: u32 = rgb(170, 196, 238);
const ACCENT_INK: u32 = rgb(23, 55, 101);
const LINE: u32 = rgb(211, 221, 234);
const NAV_BASE: u32 = rgb(32, 36, 49);
const NAV_TEXT: u32 = rgb(186, 197, 238);
const DISABLED_FILL: u32 = rgb(236, 241, 247);
const DISABLED_INK: u32 = rgb(109, 130, 156);
const DANGER: u32 = rgb(161, 38, 50);
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
const ROLLBACK: u16 = 34;
const INFO: u16 = 37;
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
const LANGUAGE: u16 = 57;
const STATUS: u16 = 60;
const CANCEL: u16 = 61;
const TABS: [u16; 5] = [70, 71, 72, 73, 74];
const PAGE_INSTALL: usize = 0;
const PAGE_APPS: usize = 1;
const PAGE_CODES: usize = 2;
const PAGE_SERVICE: usize = 3;
const PAGE_RELEASES: usize = 4;
const STEP_COLUMNS: [u16; 2] = [120, 121];
const APPS_CHOOSE: u16 = 122;
const APPS_REFRESH: u16 = 123;
const APPS_LIST: u16 = 124;
const APPS_OPEN: u16 = 125;
const APPS_GRANT: u16 = 126;
const APPS_EXPORT: u16 = 127;
const APPS_REMOVE: u16 = 128;
const DAILY_COPY: [u16; 3] = [100, 101, 102];
const DAILY_DATE: [u16; 3] = [103, 104, 105];
const LEGACY_COPY: [u16; 4] = [110, 111, 112, 113];
const LEGACY_LABEL: [u16; 4] = [114, 115, 116, 117];
type Text = (&'static str, &'static str);

const PAGE_TITLES: [Text; 5] = [
    ("Installation", "Установка"),
    ("Other apps", "Приложения"),
    ("Engineering menu", "Инженерное меню"),
    ("Maintenance", "Обслуживание"),
    ("Releases and logs", "Релизы и журналы"),
];
const PAGE_HINTS: [Text; 5] = [
    (
        "Connect the head unit, choose the APK and select Install. Everything else is automatic.",
        "Подключите магнитолу, выберите APK и нажмите «Установить». Остальное установщик сделает сам.",
    ),
    (
        "Install, update and manage other apps without touching Open Voyah Controls.",
        "Установка и управление другими приложениями, не затрагивая Open Voyah Controls.",
    ),
    (
        "Look up the access code for the date on your head unit.",
        "Код доступа для даты, которая стоит на магнитоле.",
    ),
    (
        "Service the selected unit and retain a recovery path.",
        "Обслуживание выбранной магнитолы с сохранением пути восстановления.",
    ),
    (
        "Find verified packages and collect diagnostics locally.",
        "Проверенные пакеты и сбор диагностики на этом ПК.",
    ),
];

#[derive(Clone, Copy)]
enum ServiceAction {
    ApkOnly,
    Runtime,
    RemoveApp,
    Verify,
    BackupBoot,
    InstallBoot,
    Rollback,
    RestoreExported,
    Rearm,
    Migrate,
    Oem(OemAction),
}

const SERVICE_ACTIONS: [(Text, ServiceAction); 15] = [
    (
        ("Verify production firmware", "Проверить прошивку"),
        ServiceAction::Verify,
    ),
    (
        (
            "Install Controls APK only",
            "Установить только APK Controls",
        ),
        ServiceAction::ApkOnly,
    ),
    (
        ("Install Frida injector…", "Установить инжектор Frida…"),
        ServiceAction::Runtime,
    ),
    (
        (
            "Remove Controls app (keep data)",
            "Удалить Controls (данные сохранить)",
        ),
        ServiceAction::RemoveApp,
    ),
    (
        (
            "Back up boot script to PC",
            "Копия скрипта автозапуска на ПК",
        ),
        ServiceAction::BackupBoot,
    ),
    (
        (
            "Install optional boot entry",
            "Установить запись автозапуска",
        ),
        ServiceAction::InstallBoot,
    ),
    (
        ("Roll back Controls", "Откатить Controls"),
        ServiceAction::Rollback,
    ),
    (
        (
            "Restore Controls from PC backup",
            "Восстановить автозапуск из копии на ПК",
        ),
        ServiceAction::RestoreExported,
    ),
    (
        (
            "Allow fresh Controls setup",
            "Разрешить новую настройку Controls",
        ),
        ServiceAction::Rearm,
    ),
    (
        (
            "Remove old app for signer migration",
            "Удалить старое приложение для смены подписи",
        ),
        ServiceAction::Migrate,
    ),
    (
        (
            "Reset vehicle settings with backup",
            "Сбросить настройки машины с копией",
        ),
        ServiceAction::Oem(OemAction::ResetSettings),
    ),
    (
        (
            "Restore vehicle settings backup",
            "Вернуть копию настроек машины",
        ),
        ServiceAction::Oem(OemAction::RestoreSettings),
    ),
    (
        (
            "Install signed OEM keyboard update",
            "Обновить клавиатуру OEM",
        ),
        ServiceAction::Oem(OemAction::InstallKeyboard),
    ),
    (
        ("Restore stock OEM keyboard", "Вернуть штатную клавиатуру"),
        ServiceAction::Oem(OemAction::RestoreKeyboard),
    ),
    (
        ("Restart head unit", "Перезагрузить магнитолу"),
        ServiceAction::Oem(OemAction::Reboot),
    ),
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
    InstallYouTube {
        path: PathBuf,
        expected: Option<ReleaseApk>,
    },
    Turnkey {
        path: PathBuf,
        expected: Option<ReleaseApk>,
        runtime: PathBuf,
        lang: Lang,
    },
    RollBack(Lang),
    Apps(AppsJob),
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

enum AppsJob {
    List,
    Install(Vec<PathBuf>),
    Uninstall(String),
    Open(String),
    Grant(String),
    Export(String, PathBuf),
}

enum Output {
    Status(String),
    Devices(Vec<Device>, String),
    /// The turnkey cycle or its rollback finished: fresh devices, the result text and whether it succeeded.
    Cycle(Vec<Device>, String, bool),
    /// The chosen Controls APK is signed by a certificate this PC does not trust yet.
    Trust(ApkFacts),
    Apps(Vec<App>, String),
    Fingerprint(String),
    Apk(PathBuf, ReleaseApk, Product),
    Update(PathBuf, String),
}

enum Event {
    Progress(String),
    Step(usize, StepState),
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
    panels: [Vec<HWND>; 5],
    lang: Lang,
    texts: Vec<(HWND, Text)>,
    cues: Vec<(HWND, Text)>,
    steps: [StepState; 12],
    failed: bool,
    apps: Vec<App>,
    pending: Option<(PathBuf, Option<ReleaseApk>, PathBuf)>,
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

fn fresh_devices(adb: &Adb, status: String) -> (Vec<Device>, String) {
    // Root and restarts change the selected transport even when a job stops early. This bounded read must still
    // replace the UI's old connection list.
    match adb.devices(&AtomicBool::new(false)) {
        Ok(devices) => (devices, status),
        Err(error) => (
            Vec::new(),
            format!(
                "{status}\r\nThe connection list could not be refreshed. Select Refresh before continuing. {error}"
            ),
        ),
    }
}

fn cycle_completion(adb: &Adb, result: std::result::Result<String, String>) -> Output {
    let ok = result.is_ok();
    let (devices, text) = fresh_devices(adb, result.unwrap_or_else(|error| error));
    Output::Cycle(devices, text, ok)
}

fn preferred_device_index(devices: &[Device], serial: Option<&str>) -> Option<usize> {
    let serial = serial?;
    let mut matches = devices
        .iter()
        .enumerate()
        .filter_map(|(index, device)| (device.serial == serial).then_some(index));
    let index = matches.next()?;
    matches.next().is_none().then_some(index)
}

fn run_job(
    job: Job,
    adb: Option<Adb>,
    device: Option<Device>,
    store: &Path,
    stop: &AtomicBool,
    progress: impl Fn(String),
    steps: &dyn Fn(usize, StepState),
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
            let (version, product) = match releases::apk_version(&asset) {
                Some(version) => (version, Product::Controls),
                None => (
                    releases::youtube_version(&asset)
                        .ok_or("The release has an invalid APK version.")?,
                    Product::YouTube,
                ),
            };
            Output::Apk(
                path,
                ReleaseApk {
                    version,
                    sha256: releases::checksum(&asset)
                        .ok_or("Missing release digest.")?
                        .into(),
                },
                product,
            )
        });
    }
    let adb = adb
        .ok_or("Install Android SDK Platform-Tools, then select adb.exe under Releases & logs.")?;
    let trust = Trust::load(store);
    let verification = store.join("verification");
    if let Job::Turnkey {
        path,
        expected,
        runtime,
        lang,
    } = job
    {
        let selected = device.ok_or("Select the intended head unit before setup.")?;
        // The certificate decision comes first and changes nothing on the head unit.
        let facts = adb
            .target(&selected, stop)
            .and_then(|target| production::inspect_apk(&target, &path, &verification));
        let facts = match facts {
            Ok(facts) => facts,
            Err(error) => return Ok(cycle_completion(&adb, Err(error))),
        };
        if facts.package != device::PACKAGE {
            return Ok(Output::Status(lang.pick(
                &format!("{} is not Open Voyah Controls. Install other apps on the Other apps page.", facts.package),
                &format!("{} — не Open Voyah Controls. Другие приложения устанавливаются на странице «Приложения».", facts.package),
            )));
        }
        if expected.is_none() && trust.classify(Product::Controls, &facts.signer).is_err() {
            return Ok(Output::Trust(facts));
        }
        let say = |text: String| progress(text);
        let mut unit = turnkey::Live {
            adb: &adb,
            device: selected,
            apk: ApkChoice {
                path: &path,
                expected: expected.as_ref(),
            },
            runtime: &runtime,
            store,
            trust: &trust,
            stop,
            say: &say,
            identity: None,
            backup: None,
            build: Build::Release,
        };
        let result = turnkey::run(
            &mut unit,
            &turnkey::Reporter {
                lang,
                step: steps,
                say: &say,
            },
        )
        .map_err(|failure| failure.text(lang));
        return Ok(cycle_completion(&adb, result));
    }
    if let Job::RollBack(lang) = job {
        let selected = device.ok_or("Select the intended head unit first.")?;
        let say = |text: String| progress(text);
        let result = turnkey::roll_back(&adb, &selected, stop, lang, &say);
        return Ok(cycle_completion(&adb, result));
    }
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
    if let Job::Apps(job) = job {
        let status = match job {
            AppsJob::List => {
                let list = apps::list(&target)?;
                let text = format!(
                    "{} app(s) installed besides Open Voyah Controls.",
                    list.len()
                );
                return Ok(Output::Apps(list, text));
            }
            AppsJob::Install(files) => apps::install(&target, &files, &verification, &progress)?,
            AppsJob::Uninstall(package) => apps::uninstall(&target, &package)?,
            AppsJob::Open(package) => apps::open(&target, &package)?,
            AppsJob::Grant(package) => apps::grant(&target, &package)?,
            AppsJob::Export(package, folder) => apps::export(&target, &package, &folder)?,
        };
        // Refresh the list so versions and removals show at once.
        let list = apps::list(&target).unwrap_or_default();
        return Ok(Output::Apps(list, status));
    }
    let result = match job {
        Job::Install { path, expected } => production::install_controls(
            &target,
            &path,
            expected.as_ref(),
            &verification,
            &trust,
            progress,
        ),
        Job::InstallYouTube { path, expected } => production::install_youtube(
            &target,
            &path,
            expected.as_ref(),
            &verification,
            &trust,
            progress,
        ),
        Job::Launch => device::launch(&target),
        Job::Remove => recovery::uninstall(&target),
        Job::Runtime(path) => {
            recovery::install_runtime(&target, &path, &verification, &trust, progress)
        }
        Job::Info => recovery::device_status(&target, &trust),
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
            ServiceAction::ApkOnly | ServiceAction::Runtime | ServiceAction::RemoveApp => {
                unreachable!("dispatched as separate jobs")
            }
            ServiceAction::Verify => {
                let identity = Identity::confirmed(&target, &fingerprint)?;
                production::check_reviewed(&target, &identity, &verification, &trust)?;
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
                &trust,
            ),
            ServiceAction::Rollback => recovery::rollback(&target, &fingerprint),
            ServiceAction::RestoreExported => recovery::restore_exported(
                &target,
                &fingerprint,
                backup
                    .as_deref()
                    .ok_or("Choose a verified PC boot backup.")?,
            ),
            ServiceAction::Rearm => recovery::rearm(&target, &fingerprint, &verification, &trust),
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
                &trust,
                progress,
            ),
        },
        _ => unreachable!(),
    };
    result.map(Output::Status)
}

const LEGACY_LABEL_WIDTH: i32 = 196;

/// Left and top of the row that holds an earlier-firmware name and its code; two columns, 42 px apart.
fn legacy_origin(index: usize) -> [i32; 2] {
    [
        236 + 367 * (index % 2) as i32,
        473 + 42 * (index / 2) as i32,
    ]
}

impl State {
    fn scale(&self, px: i32) -> i32 {
        (px * self.dpi + 48) / 96
    }

    #[allow(clippy::too_many_arguments)]
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

    fn pick(&self, (en, ru): Text) -> &'static str {
        match self.lang {
            Lang::En => en,
            Lang::Ru => ru,
        }
    }

    /// A label in the current language that follows the language switch.
    fn label(
        &mut self,
        tab: Option<usize>,
        id: u16,
        text: Text,
        rect: [i32; 4],
        font: usize,
    ) -> HWND {
        let handle = self.styled_label(tab, id, self.pick(text), rect, font);
        self.texts.push((handle, text));
        handle
    }

    fn button(&mut self, tab: Option<usize>, id: u16, text: Text, rect: [i32; 4]) {
        let handle = self.control(
            tab,
            id,
            "BUTTON",
            self.pick(text),
            rect,
            WS_TABSTOP | BS_OWNERDRAW as u32,
            0,
        );
        self.texts.push((handle, text));
    }

    fn edit(&mut self, tab: Option<usize>, id: u16, rect: [i32; 4], hint: Text) {
        let hwnd = self.control(
            tab,
            id,
            "EDIT",
            "",
            rect,
            WS_TABSTOP | ES_AUTOHSCROLL as u32,
            0,
        );
        self.cues.push((hwnd, hint));
        unsafe {
            SendMessageW(
                hwnd,
                EM_SETCUEBANNER,
                0,
                wide(self.pick(hint)).as_ptr() as LPARAM,
            )
        };
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
        self.label(
            None,
            SIDEBAR_MUTED,
            ("DEVICE WORKSPACE", "РАБОЧЕЕ МЕСТО"),
            [23, 151, 174, 21],
            3,
        );
        for (index, label) in PAGE_TITLES.into_iter().enumerate() {
            self.button(
                None,
                TABS[index],
                label,
                [14, 197 + index as i32 * 50, 180, 42],
            );
        }
        self.button(None, LANGUAGE, ("Русский", "English"), [14, 532, 180, 36]);
        self.label(
            None,
            SIDEBAR_MUTED,
            ("WINDOWS INSTALLER", "УСТАНОВЩИК"),
            [23, 603, 174, 20],
            3,
        );
        let version: Text = (
            concat!("Version ", env!("CARGO_PKG_VERSION")),
            concat!("Версия ", env!("CARGO_PKG_VERSION")),
        );
        self.label(None, SIDEBAR_TEXT, version, [23, 626, 174, 22], 0);

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
            self.label(
                None,
                PANEL_TEXT,
                ("Device connection", "Подключение магнитолы"),
                [252, 146, 402, 28],
                1,
            ),
            self.styled_label(
                None,
                DEVICE_BADGE,
                "NO UNIT SELECTED",
                [771, 151, 187, 22],
                3,
            ),
        ];
        self.combo(None, DEVICE, [252, 179, 551, 160]);
        self.button(None, REFRESH, ("Refresh", "Обновить"), [816, 177, 141, 34]);
        self.edit(
            None,
            HOST,
            [252, 224, 387, 30],
            (
                "Wi-Fi address, e.g. 192.168.1.20:5555",
                "Адрес Wi-Fi, например 192.168.1.20:5555",
            ),
        );
        self.button(
            None,
            CONNECT,
            ("Connect Wi-Fi", "Подключить"),
            [652, 222, 142, 34],
        );
        self.button(
            None,
            DISCONNECT,
            ("Disconnect", "Отключить"),
            [807, 222, 150, 34],
        );
        for id in [DEVICE, REFRESH, HOST, CONNECT, DISCONNECT] {
            self.connection.push(self.controls[&id]);
        }

        let tab = Some(PAGE_INSTALL);
        self.label(
            tab,
            PANEL_TEXT,
            ("Open Voyah Controls", "Open Voyah Controls"),
            [252, 288, 690, 28],
            1,
        );
        self.label(
            tab,
            PANEL_MUTED,
            (
                "Choose the APK. Install runs the whole cycle, restarts the head unit by itself and checks the result.",
                "Выберите APK — установщик сам пройдёт весь цикл с перезагрузками и проверкой.",
            ),
            [253, 318, 704, 24],
            0,
        );
        self.edit(
            tab,
            APK,
            [252, 347, 551, 31],
            (
                "Choose or drop an Open Voyah Controls APK",
                "Выберите или перетащите APK Open Voyah Controls",
            ),
        );
        self.button(
            tab,
            BROWSE_APK,
            ("Browse APK…", "Выбрать APK…"),
            [816, 345, 141, 34],
        );
        self.button(tab, INSTALL, ("Install", "Установить"), [252, 388, 204, 36]);
        self.button(
            tab,
            ROLLBACK,
            ("Roll back", "Откатить"),
            [468, 388, 154, 36],
        );
        self.button(
            tab,
            INFO,
            ("Device status", "Состояние"),
            [634, 388, 160, 36],
        );
        self.button(
            tab,
            LAUNCH,
            ("Open on device", "Открыть"),
            [806, 388, 151, 36],
        );
        for (index, id) in STEP_COLUMNS.into_iter().enumerate() {
            self.styled_label(tab, id, "", [252 + 356 * index as i32, 432, 350, 118], 5);
        }

        let tab = Some(PAGE_APPS);
        self.label(
            tab,
            PANEL_TEXT,
            ("Other apps", "Другие приложения"),
            [252, 292, 400, 28],
            1,
        );
        self.label(
            tab,
            PANEL_MUTED,
            (
                "Drop APK files on this window or choose them. Open Voyah Controls is never touched.",
                "Перетащите APK в это окно или выберите их. Open Voyah Controls не затрагивается.",
            ),
            [253, 324, 704, 24],
            0,
        );
        self.button(
            tab,
            APPS_CHOOSE,
            ("Install APKs…", "Установить APK…"),
            [252, 354, 204, 35],
        );
        self.button(
            tab,
            APPS_REFRESH,
            ("Refresh list", "Обновить список"),
            [468, 354, 180, 35],
        );
        self.control(
            tab,
            APPS_LIST,
            "LISTBOX",
            "",
            [252, 400, 551, 148],
            WS_TABSTOP | WS_VSCROLL | WS_BORDER | (LBS_NOTIFY | LBS_NOINTEGRALHEIGHT) as u32,
            5,
        );
        self.button(tab, APPS_OPEN, ("Open", "Открыть"), [816, 400, 141, 32]);
        self.button(
            tab,
            APPS_GRANT,
            ("Permissions", "Разрешения"),
            [816, 438, 141, 32],
        );
        self.button(
            tab,
            APPS_EXPORT,
            ("Export APK…", "Сохранить APK…"),
            [816, 476, 141, 32],
        );
        self.button(
            tab,
            APPS_REMOVE,
            ("Uninstall", "Удалить"),
            [816, 514, 141, 32],
        );

        let tab = Some(PAGE_CODES);
        self.label(
            tab,
            PANEL_TEXT,
            ("Daily access codes", "Коды доступа на день"),
            [252, 146, 480, 28],
            1,
        );
        self.label(
            tab,
            PANEL_MUTED,
            (
                "Codes change at midnight in Beijing. Use the date shown on the head unit.",
                "Коды меняются в полночь по Пекину. Используйте дату, которая стоит на магнитоле.",
            ),
            [252, 179, 688, 23],
            0,
        );
        self.edit(tab, DATE, [252, 211, 156, 31], ("YYYY-MM-DD", "ГГГГ-ММ-ДД"));
        self.button(
            tab,
            CODE,
            ("Use this date", "Эта дата"),
            [420, 210, 133, 34],
        );
        self.button(
            tab,
            BEIJING,
            ("Use Beijing date", "Дата в Пекине"),
            [565, 210, 156, 34],
        );
        for (index, label) in [
            ("Yesterday", "Вчера"),
            ("Today", "Сегодня"),
            ("Tomorrow", "Завтра"),
        ]
        .into_iter()
        .enumerate()
        {
            let left = [250, 505, 760][index];
            let style = if index == 1 { ACCENT_TEXT } else { PANEL_TEXT };
            self.label(tab, style, label, [left, 284, 200, 27], 1);
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
        self.label(
            tab,
            WORKSPACE_TEXT,
            ("Earlier firmware", "Ранние прошивки"),
            [236, 416, 482, 27],
            1,
        );
        self.label(
            tab,
            WORKSPACE_MUTED,
            (
                "Select a code to copy it. Letters are case-sensitive.",
                "Нажмите на код, чтобы скопировать. Регистр букв важен.",
            ),
            [236, 446, 554, 23],
            0,
        );
        self.button(
            tab,
            SAVED_CODES,
            ("Load local codes…", "Загрузить коды…"),
            [792, 425, 166, 35],
        );
        for index in 0..LEGACY_COPY.len() {
            let [left, top] = legacy_origin(index);
            self.styled_label(
                tab,
                LEGACY_LABEL[index],
                "",
                [left, top + 8, LEGACY_LABEL_WIDTH, 20],
                5,
            );
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

        let tab = Some(PAGE_SERVICE);
        self.label(
            tab,
            PANEL_TEXT,
            (
                "Boot, recovery and service",
                "Автозапуск, восстановление и сервис",
            ),
            [252, 291, 668, 27],
            1,
        );
        self.label(
            tab,
            PANEL_MUTED,
            (
                "Confirm the exact build. Boot and recovery require authorized ADB root.",
                "Подтвердите точную сборку. Автозапуск и восстановление требуют ADB root.",
            ),
            [252, 323, 705, 24],
            0,
        );
        self.edit(
            tab,
            FINGERPRINT,
            [252, 355, 551, 31],
            ("Exact build fingerprint", "Точный fingerprint сборки"),
        );
        self.button(
            tab,
            READ_FINGERPRINT,
            ("Read build", "Прочитать"),
            [816, 353, 141, 35],
        );
        self.combo(tab, OEM, [252, 402, 485, 300]);
        self.button(
            tab,
            RUN_OEM,
            ("Run operation…", "Выполнить…"),
            [750, 399, 207, 36],
        );
        self.edit(
            tab,
            KEYBOARD,
            [252, 453, 551, 31],
            (
                "Signed keyboard APK or exact package name for stock restore",
                "APK клавиатуры или точное имя пакета для возврата штатной",
            ),
        );
        self.button(
            tab,
            BROWSE_KEYBOARD,
            ("Keyboard APK…", "APK клавиатуры…"),
            [816, 451, 141, 35],
        );
        self.label(
            tab,
            PANEL_MUTED,
            (
                "Keep the boot backup on this PC. Roll back Controls before removing the app.",
                "Храните копию автозапуска на этом ПК. Перед удалением приложения откатите Controls.",
            ),
            [252, 500, 704, 23],
            0,
        );

        let tab = Some(PAGE_RELEASES);
        self.label(
            tab,
            PANEL_TEXT,
            ("Official releases", "Официальные релизы"),
            [252, 291, 668, 27],
            1,
        );
        let startup = self.control(
            tab,
            STARTUP,
            "BUTTON",
            self.pick(("Check on startup", "Проверять при запуске")),
            [252, 328, 173, 29],
            WS_TABSTOP | BS_AUTOCHECKBOX as u32,
            0,
        );
        self.texts
            .push((startup, ("Check on startup", "Проверять при запуске")));
        let auto = self.demo
            || !matches!(
                fs::read_to_string(self.storage.join("startup-updates")).as_deref(),
                Ok("off")
            );
        unsafe { SendMessageW(self.controls[&STARTUP], BM_SETCHECK, usize::from(auto), 0) };
        self.button(
            tab,
            CHECK,
            ("Check for updates", "Проверить"),
            [431, 324, 163, 35],
        );
        self.button(
            tab,
            UPDATE,
            ("Download and install", "Скачать и обновить"),
            [605, 324, 198, 35],
        );
        self.combo(tab, RELEASE, [252, 376, 485, 140]);
        self.button(
            tab,
            DOWNLOAD,
            ("Download APK", "Скачать APK"),
            [750, 373, 207, 36],
        );
        self.label(
            tab,
            WORKSPACE_TEXT,
            ("Diagnostics", "Диагностика"),
            [236, 471, 680, 27],
            1,
        );
        self.button(
            tab,
            COLLECT,
            ("Collect logs", "Собрать журналы"),
            [236, 502, 160, 36],
        );
        self.button(
            tab,
            LOGS,
            ("Open logs folder", "Папка журналов"),
            [408, 502, 166, 36],
        );
        self.button(
            tab,
            ADB_PATH,
            ("Select adb.exe…", "Выбрать adb.exe…"),
            [586, 502, 166, 36],
        );

        // Tall enough for the descender of the heading font; the status box starts right below it.
        self.label(
            None,
            PANEL_TEXT,
            ("Activity", "Ход работы"),
            [252, 572, 620, 27],
            1,
        );
        self.control(
            None,
            STATUS,
            "EDIT",
            "",
            [252, 603, 525, 43],
            WS_TABSTOP | WS_VSCROLL | (ES_MULTILINE | ES_AUTOVSCROLL | ES_READONLY) as u32,
            5,
        );
        self.button(
            None,
            CANCEL,
            ("Cancel operation", "Прервать"),
            [792, 603, 165, 35],
        );
        self.relabel_lists();
        self.load_legacy();
        self.set_beijing_date();
        self.render_steps();
        self.switch(PAGE_INSTALL);
        self.status(self.pick((
            "Connect a head unit and authorize Android debugging.",
            "Подключите магнитолу и разрешите отладку Android.",
        )));
        self.enable_controls();
    }

    /// Lists and placeholders whose items depend on the language.
    fn relabel_lists(&self) {
        let selection = self.index(OEM).unwrap_or(0);
        self.set_items(
            OEM,
            SERVICE_ACTIONS.iter().map(|(label, _)| self.pick(*label)),
            Some(selection),
        );
        if self.devices.is_empty() {
            self.set_items(
                DEVICE,
                [self.pick(("Select a head unit", "Выберите магнитолу"))],
                Some(0),
            );
        }
        if self.catalog.apk.is_empty() && self.catalog.youtube.is_empty() {
            self.set_items(
                RELEASE,
                [self.pick((
                    "Check for official APK releases",
                    "Проверьте официальные релизы APK",
                ))],
                Some(0),
            );
        }
        if self.apps.is_empty() {
            self.set_list([self.pick((
                "Select Refresh list to see the installed apps.",
                "Нажмите «Обновить список», чтобы увидеть установленные приложения.",
            ))]);
        }
    }

    fn set_language(&mut self, lang: Lang) {
        self.lang = lang;
        let _ = fs::create_dir_all(&self.storage).and_then(|_| {
            fs::write(
                self.storage.join("language"),
                if lang == Lang::Ru { "ru" } else { "en" },
            )
        });
        for (hwnd, text) in &self.texts {
            unsafe { SetWindowTextW(*hwnd, wide(self.pick(*text)).as_ptr()) };
        }
        for (hwnd, text) in &self.cues {
            unsafe {
                SendMessageW(
                    *hwnd,
                    EM_SETCUEBANNER,
                    0,
                    wide(self.pick(*text)).as_ptr() as LPARAM,
                )
            };
        }
        if self.failed {
            self.set(INSTALL, self.pick(("Retry", "Повторить")));
        }
        self.relabel_lists();
        self.render_steps();
        self.show_legacy();
        self.switch(ACTIVE_PAGE.load(Ordering::Relaxed) as usize);
        self.enable_controls();
    }

    fn render_steps(&self) {
        for (column, id) in STEP_COLUMNS.into_iter().enumerate() {
            let text: Vec<String> = (column * 6..column * 6 + 6)
                .map(|index| {
                    let mark = match self.steps[index] {
                        StepState::Pending => "○",
                        StepState::Running => "▶",
                        StepState::Done => "✓",
                        StepState::Skipped => "–",
                        StepState::Failed => "✕",
                    };
                    format!("{mark}  {}. {}", index + 1, self.pick(STEPS[index]))
                })
                .collect();
            self.set(id, &text.join("\r\n"));
        }
    }

    fn set_list<'a>(&self, items: impl IntoIterator<Item = &'a str>) {
        unsafe {
            let hwnd = self.controls[&APPS_LIST];
            SendMessageW(hwnd, LB_RESETCONTENT, 0, 0);
            for item in items {
                SendMessageW(hwnd, LB_ADDSTRING, 0, wide(item).as_ptr() as LPARAM);
            }
        }
    }

    fn selected_app(&self) -> Option<&App> {
        let index = unsafe { SendMessageW(self.controls[&APPS_LIST], LB_GETCURSEL, 0, 0) };
        usize::try_from(index).ok().and_then(|i| self.apps.get(i))
    }

    fn switch(&self, index: usize) {
        ACTIVE_PAGE.store(index as u32, Ordering::Relaxed);
        self.set(HERO_TITLE, self.pick(PAGE_TITLES[index]));
        self.set(HERO_HINT, self.pick(PAGE_HINTS[index]));
        unsafe {
            for (i, (tab, controls)) in TABS.iter().zip(self.panels.iter()).enumerate() {
                InvalidateRect(self.controls[tab], null(), 0);
                for hwnd in controls {
                    ShowWindow(*hwnd, if i == index { SW_SHOW } else { SW_HIDE });
                }
            }
            for hwnd in &self.connection {
                ShowWindow(
                    *hwnd,
                    if index == PAGE_CODES {
                        SW_HIDE
                    } else {
                        SW_SHOW
                    },
                );
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
                    self.pick((
                        "Load an early-firmware code list",
                        "Загрузите список кодов ранних прошивок",
                    ))
                } else {
                    ""
                });
            self.set(LEGACY_LABEL[index], label);
            self.place_legacy_label(index, label);
            self.set(id, entry.map(|code| code.code.as_str()).unwrap_or("—"));
            self.enable(id, entry.is_some() && !self.demo);
        }
    }

    /// A firmware name takes one line or two. A fixed rectangle either cuts the second line or leaves a single
    /// line above the middle of its code, so each name is measured and centered on its row.
    fn place_legacy_label(&self, index: usize, text: &str) {
        let label = self.controls[&LEGACY_LABEL[index]];
        let [left, top] = legacy_origin(index);
        let width = self.scale(LEGACY_LABEL_WIDTH);
        // The code button occupies 34 px starting one pixel below the row origin; two lines need the whole pitch.
        let (band_top, band, limit) = (self.scale(top + 1), self.scale(34), self.scale(40));
        unsafe {
            let dc = GetDC(label);
            let previous = SelectObject(dc, self.fonts[5] as _);
            let mut bounds = RECT {
                left: 0,
                top: 0,
                right: width,
                bottom: 0,
            };
            DrawTextW(
                dc,
                wide(text).as_ptr(),
                -1,
                &mut bounds,
                DT_CALCRECT | DT_WORDBREAK | DT_NOPREFIX,
            );
            SelectObject(dc, previous);
            ReleaseDC(label, dc);
            let height = bounds.bottom.clamp(self.scale(18), limit);
            SetWindowPos(
                label,
                null_mut(),
                self.scale(left),
                band_top + (band - height) / 2,
                width,
                height,
                SWP_NOZORDER | SWP_NOACTIVATE,
            );
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

    /// The Releases list: Controls APKs first, then the YouTube app's, each newest first.
    fn release_apks(&self) -> impl Iterator<Item = &releases::Asset> {
        self.catalog
            .apk
            .iter()
            .chain(&self.catalog.youtube)
            .map(|(_, asset)| asset)
    }

    fn enable(&self, id: u16, enabled: bool) {
        unsafe { EnableWindow(self.controls[&id], i32::from(enabled)) };
    }

    fn enable_controls(&self) {
        let free = self.cancel.is_none();
        let ready = free && self.selected().is_some_and(Device::ready);
        for id in [
            INSTALL,
            ROLLBACK,
            LAUNCH,
            INFO,
            READ_FINGERPRINT,
            RUN_OEM,
            COLLECT,
            APPS_CHOOSE,
            APPS_REFRESH,
        ] {
            self.enable(id, ready);
        }
        let app = ready && self.selected_app().is_some();
        for id in [APPS_OPEN, APPS_GRANT, APPS_EXPORT, APPS_REMOVE] {
            self.enable(id, app);
        }
        for id in [
            DEVICE,
            REFRESH,
            CONNECT,
            DISCONNECT,
            ADB_PATH,
            BROWSE_APK,
            BROWSE_KEYBOARD,
            LANGUAGE,
        ] {
            self.enable(id, free);
        }
        self.enable(CANCEL, !free);
        self.enable(CHECK, !self.checking);
        self.enable(UPDATE, free && self.catalog.installer.is_some());
        self.enable(
            DOWNLOAD,
            free && !(self.catalog.apk.is_empty() && self.catalog.youtube.is_empty()),
        );
        self.set(
            DEVICE_BADGE,
            if self.selected().is_some_and(Device::ready) {
                self.pick(("ADB CONNECTED", "ADB ПОДКЛЮЧЁН"))
            } else {
                self.pick(("NO UNIT SELECTED", "НЕТ МАГНИТОЛЫ"))
            },
        );
    }

    fn start(&mut self, job: Job) {
        if self.demo || self.cancel.is_some() {
            return;
        }
        self.status(self.pick(("Working…", "Выполняется…")));
        let cancel = Arc::new(AtomicBool::new(false));
        self.cancel = Some(Arc::clone(&cancel));
        let events = self.events.clone();
        let store = self.storage.clone();
        let device = self.selected().cloned();
        let adb = self.adb.clone();
        self.enable_controls();
        thread::spawn(move || {
            let steps = |index, state| events.send(Event::Step(index, state));
            let result = run_job(
                job,
                adb,
                device,
                &store,
                &cancel,
                |s| events.send(Event::Progress(s)),
                &steps,
            );
            events.send(Event::Done(result));
        });
    }

    fn check(&mut self, automatic: bool) {
        if self.demo || self.checking {
            return;
        }
        self.checking = true;
        if !automatic {
            self.status(self.pick((
                "Checking GitHub for official releases…",
                "Проверяю официальные релизы на GitHub…",
            )));
        }
        self.enable_controls();
        let events = self.events.clone();
        thread::spawn(move || events.send(Event::Catalog(releases::check(), automatic)));
    }

    fn show_devices(&mut self, devices: Vec<Device>) {
        let selected = self.selected().map(|d| d.serial.clone());
        let preferred = preferred_device_index(&devices, selected.as_deref());
        self.devices = devices;
        let labels: Vec<_> = self.devices.iter().map(Device::label).collect();
        if labels.is_empty() {
            self.set_items(
                DEVICE,
                [self.pick(("No devices found", "Устройства не найдены"))],
                Some(0),
            );
        } else {
            self.set_items(DEVICE, labels.iter().map(String::as_str), preferred);
        }
    }

    fn set_failed(&mut self, failed: bool) {
        self.failed = failed;
        self.set(
            INSTALL,
            self.pick(if failed {
                ("Retry", "Повторить")
            } else {
                ("Install", "Установить")
            }),
        );
    }

    fn process_events(&mut self) {
        while let Ok(event) = self.rx.try_recv() {
            match event {
                Event::Progress(text) => self.status(&text),
                Event::Step(index, state) => {
                    if let Some(slot) = self.steps.get_mut(index) {
                        *slot = state;
                        self.render_steps();
                    }
                }
                Event::Done(result) => {
                    let result = dispatch_completion(result, &mut self.cancel, |path, digest| {
                        stage_update(path, digest, &self.storage)
                    });
                    match result {
                        Ok(Output::Status(s)) => self.status(&s),
                        Ok(Output::Devices(devices, text)) => {
                            self.show_devices(devices);
                            self.status(&text);
                        }
                        Ok(Output::Cycle(devices, text, ok)) => {
                            self.show_devices(devices);
                            self.set_failed(!ok);
                            self.status(&text);
                        }
                        Ok(Output::Trust(facts)) => {
                            self.status(self.pick((
                                "The APK is not signed with the release certificate. Confirm or decline your own certificate.",
                                "APK подписан не релизным сертификатом. Подтвердите или отклоните свой сертификат.",
                            )));
                            self.ask_trust(facts);
                        }
                        Ok(Output::Apps(list, text)) => {
                            self.apps = list;
                            let labels: Vec<_> = self.apps.iter().map(App::label).collect();
                            if labels.is_empty() {
                                self.set_list([self.pick((
                                    "No other apps are installed.",
                                    "Других приложений не установлено.",
                                ))]);
                            } else {
                                self.set_list(labels.iter().map(String::as_str));
                            }
                            self.status(&text);
                        }
                        Ok(Output::Fingerprint(s)) => {
                            self.set(FINGERPRINT, &s);
                            self.status(self.pick((
                                "Verify the displayed build before running maintenance.",
                                "Сверьте показанную сборку перед обслуживанием.",
                            )));
                        }
                        Ok(Output::Apk(path, release, product)) => match product {
                            Product::Controls => {
                                self.apk_release = Some((path.clone(), release));
                                self.set(APK, &path.to_string_lossy());
                                self.switch(PAGE_INSTALL);
                                self.status(self.pick((
                                    "The release download matched its SHA-256. Select Install.",
                                    "Скачанный релиз совпал по SHA-256. Нажмите «Установить».",
                                )));
                            }
                            Product::YouTube => {
                                self.switch(PAGE_APPS);
                                self.status(self.pick((
                                    "The YouTube release matched its SHA-256 and its own certificate pin is checked during installation.",
                                    "Релиз YouTube совпал по SHA-256; его сертификат проверяется при установке.",
                                )));
                                self.enable_controls();
                                if self.selected().is_some_and(Device::ready)
                                    && confirm(
                                        self.hwnd,
                                        self.pick((
                                            "Install the downloaded YouTube app on the selected head unit now? Open Voyah Controls is not touched.",
                                            "Установить скачанное приложение YouTube на выбранную магнитолу? Open Voyah Controls не затрагивается.",
                                        )),
                                    )
                                {
                                    self.start(Job::InstallYouTube {
                                        path,
                                        expected: Some(release),
                                    });
                                }
                            }
                        },
                        Ok(Output::Update(_, _)) => {
                            unsafe { DestroyWindow(self.hwnd) };
                            return;
                        }
                        Err(e) => self.status(&format!(
                            "{} {e}",
                            self.pick(("Operation stopped.", "Операция остановлена."))
                        )),
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
                            if self.catalog.apk.is_empty() && self.catalog.youtube.is_empty() {
                                self.set_items(
                                    RELEASE,
                                    [self.pick((
                                        "No official APK releases are available",
                                        "Официальных релизов APK пока нет",
                                    ))],
                                    Some(0),
                                );
                            } else {
                                let names: Vec<String> =
                                    self.release_apks().map(|a| a.name.clone()).collect();
                                self.set_items(RELEASE, names.iter().map(String::as_str), Some(0));
                            }
                            if self.cancel.is_none() {
                                if let Some((version, _)) = &self.catalog.installer {
                                    self.status(&format!("Installer {version} is available. Use Download and install under Releases & logs."));
                                } else if !automatic {
                                    self.status(&format!("Installer {} is current. Found {} verified Controls and {} YouTube APK release(s).",
                                        env!("CARGO_PKG_VERSION"), self.catalog.apk.len(), self.catalog.youtube.len()));
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

    /// Shows the APK's certificate and asks whether to trust it as the owner's own. A Yes is stored on this PC and the
    /// installation starts again with the same inputs.
    fn ask_trust(&mut self, facts: ApkFacts) {
        let Some((path, expected, runtime)) = self.pending.clone() else {
            return;
        };
        let grouped: Vec<String> = facts
            .signer
            .to_ascii_uppercase()
            .as_bytes()
            .chunks(4)
            .map(|chunk| String::from_utf8_lossy(chunk).into_owned())
            .collect();
        let certificate = format!("{}\n{}", grouped[..8].join(" "), grouped[8..].join(" "));
        let kind = if facts.debuggable {
            self.pick(("debuggable build", "отладочная сборка"))
        } else {
            self.pick(("non-debuggable build", "неотладочная сборка"))
        };
        let question = match self.lang {
            Lang::En => format!(
                "The selected APK is not signed with the official release certificate.\n\nPackage: {}\nVersion: {} (code {}, {kind})\nCertificate SHA-256:\n{certificate}\n\nTrust this certificate as your own on this PC? Use Yes only for a build you made yourself, for example a local-vehicle build. It is then installed and labelled as an owner build.",
                facts.package, facts.version, facts.code
            ),
            Lang::Ru => format!(
                "Выбранный APK подписан не официальным релизным сертификатом.\n\nПакет: {}\nВерсия: {} (код {}, {kind})\nSHA-256 сертификата:\n{certificate}\n\nДоверять этому сертификату как своему на этом ПК? Отвечайте «Да» только для сборки, которую вы сделали сами, например local-vehicle. Она будет установлена и помечена как сборка владельца.",
                facts.package, facts.version, facts.code
            ),
        };
        if !confirm(self.hwnd, &question) {
            self.status(self.pick((
                "Installation canceled: the certificate was not trusted. Nothing was changed.",
                "Установка отменена: сертификату не доверили. Ничего не изменено.",
            )));
            return;
        }
        if let Err(error) = Trust::remember(&self.storage, &facts.signer) {
            self.status(&error);
            return;
        }
        self.start(Job::Turnkey {
            path,
            expected,
            runtime,
            lang: self.lang,
        });
    }

    /// Files dropped on the window: one APK on the Installation page fills the Controls field; anything else goes to
    /// Other apps.
    fn dropped(&mut self, files: Vec<PathBuf>) -> Result<()> {
        let files: Vec<_> = files
            .into_iter()
            .filter(|path| {
                path.is_file()
                    && path
                        .extension()
                        .is_some_and(|ext| ext.eq_ignore_ascii_case("apk"))
            })
            .collect();
        if files.is_empty() {
            return Err(self
                .pick(("Drop .apk files.", "Перетащите файлы .apk."))
                .into());
        }
        if files.len() == 1 && ACTIVE_PAGE.load(Ordering::Relaxed) as usize == PAGE_INSTALL {
            self.apk_release = None;
            self.set(APK, &files[0].to_string_lossy());
            self.status(self.pick((
                "APK selected. Select Install.",
                "APK выбран. Нажмите «Установить».",
            )));
            return Ok(());
        }
        self.switch(PAGE_APPS);
        self.install_apps(files)
    }

    fn install_apps(&mut self, files: Vec<PathBuf>) -> Result<()> {
        let device = self
            .selected()
            .filter(|device| device.ready())
            .ok_or(self.pick((
                "Select a connected head unit first.",
                "Сначала выберите подключённую магнитолу.",
            )))?
            .serial
            .clone();
        let names: Vec<_> = files
            .iter()
            .take(12)
            .map(|path| {
                path.file_name()
                    .map(|n| n.to_string_lossy().into_owned())
                    .unwrap_or_default()
            })
            .collect();
        let more = if files.len() > 12 { "\n…" } else { "" };
        let question = match self.lang {
            Lang::En => format!(
                "Install or update {} app(s) on {device}?\n\n{}{more}\n\nUpdates keep app data. Open Voyah Controls and its startup activation are not touched.",
                files.len(),
                names.join("\n")
            ),
            Lang::Ru => format!(
                "Установить или обновить приложения ({}) на {device}?\n\n{}{more}\n\nПри обновлении данные сохраняются. Open Voyah Controls и его автозапуск не затрагиваются.",
                files.len(),
                names.join("\n")
            ),
        };
        if confirm(self.hwnd, &question) {
            self.start(Job::Apps(AppsJob::Install(files)));
        }
        Ok(())
    }

    fn action(&mut self, id: u16) {
        let result = self.dispatch(id);
        if let Err(e) = result {
            self.status(&e);
        }
    }

    fn dispatch(&mut self, id: u16) -> Result<()> {
        if let Some(tab) = TABS.iter().position(|candidate| *candidate == id) {
            if tab == PAGE_CODES {
                self.set_beijing_date();
                if self.cancel.is_none() {
                    self.status(self.pick((
                        "Select a code to copy it. This tool works offline.",
                        "Нажмите на код, чтобы скопировать. Работает без сети.",
                    )));
                }
            }
            self.switch(tab);
            if tab == PAGE_APPS
                && self.apps.is_empty()
                && self.cancel.is_none()
                && self.selected().is_some_and(Device::ready)
            {
                self.start(Job::Apps(AppsJob::List));
            }
            return Ok(());
        }
        if self.demo && !matches!(id, CODE | BEIJING | LANGUAGE) {
            return Ok(());
        }
        if let Some(index) = DAILY_COPY.iter().position(|candidate| *candidate == id) {
            copy_to_clipboard(self.hwnd, &self.daily[index])?;
            self.status(self.pick(("Engineering code copied.", "Инженерный код скопирован.")));
            return Ok(());
        }
        if let Some(index) = LEGACY_COPY.iter().position(|candidate| *candidate == id) {
            let code = self
                .legacy
                .get(index)
                .ok_or("Load a local code list first.")?;
            copy_to_clipboard(self.hwnd, &code.code)?;
            self.status(self.pick(("Engineering code copied.", "Инженерный код скопирован.")));
            return Ok(());
        }
        let app = || -> Result<String> {
            self.selected_app()
                .map(|app| app.package.clone())
                .ok_or_else(|| {
                    self.pick((
                        "Select an app in the list.",
                        "Выберите приложение в списке.",
                    ))
                    .into()
                })
        };
        match id {
            LANGUAGE => {
                let next = if self.lang == Lang::Ru {
                    Lang::En
                } else {
                    Lang::Ru
                };
                self.set_language(next);
            }
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
                    return Err(self
                        .pick((
                            "Choose the Open Voyah Controls APK first.",
                            "Сначала выберите APK Open Voyah Controls.",
                        ))
                        .into());
                }
                let expected = self
                    .apk_release
                    .as_ref()
                    .filter(|(downloaded, _)| downloaded == &path)
                    .map(|(_, release)| release.clone());
                let Some(runtime) = setup::portable_runtime().or_else(|| {
                    choose_file(
                        self.hwnd,
                        "Verified ARM64 Frida injector\0open-voyah-controls-frida-*\0All files\0*.*\0\0",
                    )
                }) else {
                    return Err(self.pick((
                        "The Frida injector is missing. Keep the support folder next to the installer.",
                        "Не найден инжектор Frida. Держите папку support рядом с установщиком.",
                    ))
                    .into());
                };
                let serial = self
                    .selected()
                    .map(|d| d.serial.clone())
                    .unwrap_or_default();
                let question = match self.lang {
                    Lang::En => format!(
                        "Install Open Voyah Controls on {serial}?\n\nThe installer requests ADB root, saves the original startup script on this PC, installs the app, the Frida injector, the private root channel and the startup entry, runs the first-run check and confirms that everything starts by itself.\n\nThe head unit restarts automatically twice when an earlier installation is updated, once on a fresh unit, and a first setup may need one more restart to prepare its system partition. Keep the vehicle parked with stable power and the cable connected until the installer says Done."
                    ),
                    Lang::Ru => format!(
                        "Установить Open Voyah Controls на {serial}?\n\nУстановщик запросит ADB root, сохранит исходный скрипт автозапуска на этом ПК, установит приложение, инжектор Frida, закрытый root-канал и запись автозапуска, запустит первичную проверку и убедится, что всё запускается само.\n\nМагнитола перезагрузится автоматически: дважды при обновлении прежней установки, один раз на чистой магнитоле; при первой настройке может понадобиться ещё одна перезагрузка для подготовки системного раздела. Машина должна стоять с устойчивым питанием, кабель не отключайте, пока установщик не напишет «Готово»."
                    ),
                };
                if confirm(self.hwnd, &question) {
                    self.pending = Some((path.clone(), expected.clone(), runtime.clone()));
                    self.steps = [StepState::Pending; 12];
                    self.render_steps();
                    self.start(Job::Turnkey {
                        path,
                        expected,
                        runtime,
                        lang: self.lang,
                    });
                }
            }
            ROLLBACK => {
                let serial = self
                    .selected()
                    .map(|d| d.serial.clone())
                    .unwrap_or_default();
                let question = match self.lang {
                    Lang::En => format!(
                        "Roll back Open Voyah Controls on {serial}?\n\nIt stops Controls and restores the original startup script. The head unit may restart once by itself. The app and its settings stay; Install sets it up again."
                    ),
                    Lang::Ru => format!(
                        "Откатить Open Voyah Controls на {serial}?\n\nControls будет остановлен, исходный скрипт автозапуска восстановлен. Магнитола может сама перезагрузиться один раз. Приложение и его настройки останутся; «Установить» настроит его снова."
                    ),
                };
                if confirm(self.hwnd, &question) {
                    self.start(Job::RollBack(self.lang));
                }
            }
            LAUNCH => self.start(Job::Launch),
            INFO => self.start(Job::Info),
            APPS_CHOOSE => {
                let files = choose_files(self.hwnd, "Android packages\0*.apk\0\0");
                if !files.is_empty() {
                    self.install_apps(files)?;
                }
            }
            APPS_REFRESH => self.start(Job::Apps(AppsJob::List)),
            APPS_OPEN => self.start(Job::Apps(AppsJob::Open(app()?))),
            APPS_GRANT => {
                let package = app()?;
                let question = match self.lang {
                    Lang::En => format!(
                        "Grant every runtime permission {package} requests (microphone, location, storage and so on)?"
                    ),
                    Lang::Ru => format!(
                        "Выдать {package} все запрашиваемые разрешения (микрофон, местоположение, хранилище и т. д.)?"
                    ),
                };
                if confirm(self.hwnd, &question) {
                    self.start(Job::Apps(AppsJob::Grant(package)));
                }
            }
            APPS_EXPORT => {
                let package = app()?;
                if let Some(folder) = choose_folder(self.hwnd) {
                    self.start(Job::Apps(AppsJob::Export(package, folder)));
                }
            }
            APPS_REMOVE => {
                let package = app()?;
                let question = match self.lang {
                    Lang::En => {
                        format!("Uninstall {package} from the head unit? Its data is removed too.")
                    }
                    Lang::Ru => {
                        format!("Удалить {package} с магнитолы? Его данные тоже будут удалены.")
                    }
                };
                if confirm(self.hwnd, &question) {
                    self.start(Job::Apps(AppsJob::Uninstall(package)));
                }
            }
            READ_FINGERPRINT => self.start(Job::Fingerprint),
            RUN_OEM => {
                let index = self.index(OEM).ok_or("Select a maintenance operation.")?;
                let device = self.selected().ok_or("Select a head unit.")?;
                let (label, action) = *SERVICE_ACTIONS
                    .get(index)
                    .ok_or("Select a service operation.")?;
                let label = self.pick(label);
                match action {
                    ServiceAction::ApkOnly => {
                        let path = PathBuf::from(self.text(APK).trim());
                        if !path.is_file() {
                            return Err(
                                "Choose the Controls APK on the Installation page first.".into()
                            );
                        }
                        let expected = self
                            .apk_release
                            .as_ref()
                            .filter(|(downloaded, _)| downloaded == &path)
                            .map(|(_, release)| release.clone());
                        if confirm(
                            self.hwnd,
                            &format!(
                                "{label} on {}?\n\nOnly the APK is installed, without the root channel or startup entry. A running Controls sealed with its signer is updated in place; any other installed Controls must be rolled back first.",
                                device.serial
                            ),
                        ) {
                            self.start(Job::Install { path, expected });
                        }
                        return Ok(());
                    }
                    ServiceAction::Runtime => {
                        if let Some(path) = choose_file(self.hwnd, "All files\0*.*\0\0") {
                            self.start(Job::Runtime(path));
                        }
                        return Ok(());
                    }
                    ServiceAction::RemoveApp => {
                        if confirm(
                            self.hwnd,
                            "Remove Open Voyah Controls after checking boot and Frida state? Android will keep the app data.",
                        ) {
                            self.start(Job::Remove);
                        }
                        return Ok(());
                    }
                    _ => {}
                }
                let fingerprint = self.text(FINGERPRINT).trim().to_owned();
                if fingerprint.is_empty() {
                    return Err(
                        "Select Read build and confirm the head unit's fingerprint first.".into(),
                    );
                }
                let note = match action {
                    ServiceAction::ApkOnly | ServiceAction::Runtime | ServiceAction::RemoveApp => {
                        ""
                    }
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
                let asset = self
                    .index(RELEASE)
                    .and_then(|i| self.release_apks().nth(i))
                    .ok_or("Select an APK release.")?
                    .clone();
                self.start(Job::Download(asset, false));
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
                    self.status(self.pick((
                        "Stopping the operation. Check the head unit before retrying a write.",
                        "Останавливаю операцию. Проверьте магнитолу, прежде чем повторять запись.",
                    )));
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

/// Several files from one folder. The dialog returns the folder and then each name, or one full path.
fn choose_files(owner: HWND, filter: &str) -> Vec<PathBuf> {
    use std::os::windows::ffi::OsStringExt;
    let mut buffer = vec![0u16; 65536];
    let filter = wide(filter);
    let mut data: OPENFILENAMEW = unsafe { zeroed() };
    data.lStructSize = size_of::<OPENFILENAMEW>() as u32;
    data.hwndOwner = owner;
    data.lpstrFilter = filter.as_ptr();
    data.lpstrFile = buffer.as_mut_ptr();
    data.nMaxFile = buffer.len() as u32;
    data.Flags = OFN_FILEMUSTEXIST
        | OFN_PATHMUSTEXIST
        | OFN_NOCHANGEDIR
        | OFN_DONTADDTORECENT
        | OFN_ALLOWMULTISELECT
        | OFN_EXPLORER;
    if unsafe { GetOpenFileNameW(&mut data) } == 0 {
        return Vec::new();
    }
    let parts: Vec<PathBuf> = buffer
        .split(|c| *c == 0)
        .take_while(|part| !part.is_empty())
        .map(|part| std::ffi::OsString::from_wide(part).into())
        .collect();
    match parts.as_slice() {
        [] => Vec::new(),
        [single] => vec![single.clone()],
        [folder, names @ ..] => names.iter().map(|name| folder.join(name)).collect(),
    }
}

fn dropped_files(drop: HDROP) -> Vec<PathBuf> {
    use std::os::windows::ffi::OsStringExt;
    let mut files = Vec::new();
    unsafe {
        let count = DragQueryFileW(drop, u32::MAX, null_mut(), 0);
        for index in 0..count.min(64) {
            let length = DragQueryFileW(drop, index, null_mut(), 0) as usize;
            let mut name = vec![0u16; length + 1];
            let copied = DragQueryFileW(drop, index, name.as_mut_ptr(), name.len() as u32) as usize;
            files.push(std::ffi::OsString::from_wide(&name[..copied]).into());
        }
        DragFinish(drop);
    }
    files
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
        let button = msg == WM_COMMAND && code == 0 && id != APPS_LIST;
        let device_change = msg == WM_COMMAND
            && ((id == DEVICE && code == CBN_SELCHANGE as u16)
                || (id == APPS_LIST && code == LBN_SELCHANGE as u16));
        if button || device_change || matches!(msg, WAKE | WM_CLOSE | WM_TIMER | WM_DROPFILES) {
            let slot = GetWindowLongPtrW(hwnd, GWLP_USERDATA) as *const WindowSlot;
            if let Some(slot) = slot.as_ref() {
                let Ok(mut state) = slot.state.try_borrow_mut() else {
                    if msg == WAKE {
                        slot.pending_wake.set(true);
                    }
                    return 0;
                };
                if msg == WM_DROPFILES {
                    let files = dropped_files(w as HDROP);
                    if state.cancel.is_none()
                        && !state.demo
                        && let Err(error) = state.dropped(files)
                    {
                        state.status(&error);
                    }
                } else if button {
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
        match ACTIVE_PAGE.load(Ordering::Relaxed) as usize {
            PAGE_CODES => {
                card([234, 131, 975, 258], WHITE, LINE);
                card([234, 272, 464, 398], WHITE, LINE);
                card([489, 272, 719, 398], ACCENT_SOFT, ACCENT_SOFT_EDGE);
                card([744, 272, 974, 398], WHITE, LINE);
            }
            page => {
                card([234, 131, 975, 269], WHITE, LINE);
                let bottom = match page {
                    PAGE_INSTALL | PAGE_APPS => 554,
                    PAGE_SERVICE => 544,
                    _ => 435,
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
        let main =
            matches!(id, INSTALL | RUN_OEM | UPDATE | CONNECT | APPS_CHOOSE) || id == DAILY_COPY[1];
        let danger = id == APPS_REMOVE || id == CANCEL;
        let on_workspace =
            matches!(id, COLLECT | LOGS | ADB_PATH | SAVED_CODES) || LEGACY_COPY.contains(&id);
        let background = if tab.is_some() || id == LANGUAGE {
            SIDEBAR
        } else if on_workspace {
            BG
        } else if id == DAILY_COPY[1] {
            ACCENT_SOFT
        } else {
            WHITE
        };
        let (fill, stroke, foreground) = if disabled {
            (DISABLED_FILL, LINE, DISABLED_INK)
        } else if active || main {
            let tint = if pressed { ACCENT_PRESSED } else { ACCENT };
            (tint, tint, SIDEBAR)
        } else if tab.is_some() {
            let tint = if pressed { NAV_BASE } else { SIDEBAR };
            (tint, tint, NAV_TEXT)
        } else if id == LANGUAGE {
            (if pressed { SIDEBAR } else { NAV_BASE }, NAV_BASE, NAV_TEXT)
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
                3 => self.switch(PAGE_SERVICE),
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
        // MAKEINTRESOURCEW(1): Win32 passes a resource ordinal in the pointer argument; it is never dereferenced.
        #[allow(clippy::manual_dangling_ptr)]
        let icon = LoadIconW(instance, APP_ICON_RESOURCE as *const u16);
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
        // The frame is drawn at the system DPI even when the layout was fitted to a smaller one. Sizing it for the
        // layout DPI would take the difference out of the client area and cut the bottom edge of the page.
        AdjustWindowRectExForDpi(
            &mut rect,
            style,
            0,
            WS_EX_CONTROLPARENT,
            requested_dpi as u32,
        );
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
        let store_for_language = store.clone();
        let capture_language = capture
            .as_ref()
            .and_then(|_| args.get(4))
            .and_then(|a| a.to_str())
            .map(|a| if a == "ru" { Lang::Ru } else { Lang::En })
            .or(capture.as_ref().map(|_| Lang::En));
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
            lang: initial_language(&store_for_language, capture_language),
            texts: Vec::new(),
            cues: Vec::new(),
            steps: [StepState::Pending; 12],
            failed: false,
            apps: Vec::new(),
            pending: None,
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
                .min(PAGE_RELEASES);
            state.switch(tab);
            if matches!(tab, PAGE_INSTALL | PAGE_APPS | PAGE_SERVICE) {
                let sample = Device {
                    serial: "DEMO-USB".into(),
                    model: "Sample head unit".into(),
                    state: "device".into(),
                    transport: Some(1),
                };
                state.set_items(DEVICE, [sample.label().as_str()], Some(0));
                state.devices.push(sample);
                if tab == PAGE_INSTALL {
                    state.set(APK, "OpenVoyahControls-v0.21.0.apk");
                    for index in 0..5 {
                        state.steps[index] = StepState::Done;
                    }
                    state.steps[4] = StepState::Skipped;
                    state.steps[5] = StepState::Running;
                    state.render_steps();
                    state.status(state.pick((
                        "Installing Controls 0.21.0…",
                        "Устанавливаю Controls 0.21.0…",
                    )));
                } else if tab == PAGE_APPS {
                    state.apps = vec![
                        App {
                            package: "org.openvoyah.youtube".into(),
                            version: "0.2.0".into(),
                        },
                        App {
                            package: "com.example.navigator".into(),
                            version: "24.6.1".into(),
                        },
                        App {
                            package: "com.example.radio".into(),
                            version: "3.4".into(),
                        },
                    ];
                    let labels: Vec<_> = state.apps.iter().map(App::label).collect();
                    state.set_list(labels.iter().map(String::as_str));
                    SendMessageW(state.controls[&APPS_LIST], LB_SETCURSEL, 0, 0);
                    state.status(state.pick((
                        "Installed 1 app(s): org.openvoyah.youtube 0.2.0. Open Voyah Controls activation checked: unchanged.",
                        "Установлено приложений: 1 (org.openvoyah.youtube 0.2.0). Активация Open Voyah Controls проверена: не изменилась.",
                    )));
                } else {
                    state.set(
                        FINGERPRINT,
                        "sample/device/demo:11/preview/1:user/release-keys",
                    );
                    SendMessageW(state.controls[&OEM], CB_SETCURSEL, 6, 0);
                    state.status("Preview recovery on a sample head unit.");
                }
                state.enable_controls();
            } else if tab == PAGE_CODES {
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
            DragAcceptFiles(hwnd, 1);
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

/// The saved choice, or Russian when Windows itself is in Russian.
fn initial_language(store: &Path, forced: Option<Lang>) -> Lang {
    if let Some(lang) = forced {
        return lang;
    }
    match fs::read_to_string(store.join("language"))
        .as_deref()
        .map(str::trim)
    {
        Ok("ru") => Lang::Ru,
        Ok("en") => Lang::En,
        _ if unsafe { windows_sys::Win32::Globalization::GetUserDefaultUILanguage() } & 0x3ff
            == 0x19 =>
        {
            Lang::Ru
        }
        _ => Lang::En,
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

fn dispatch_completion(
    result: Result<Output>,
    cancel: &mut Option<Arc<AtomicBool>>,
    install: impl FnOnce(&Path, &str) -> Result<()>,
) -> Result<Output> {
    let result = match result {
        Ok(Output::Update(path, digest)) => {
            // A queued download has not committed installation. Honor cancellation
            // at dispatch; a started handoff is not retroactively canceled.
            if cancel
                .as_ref()
                .is_some_and(|flag| flag.load(Ordering::Relaxed))
            {
                Ok(Output::Status(
                    "Update canceled before installation. The downloaded file was kept for a later attempt.".into(),
                ))
            } else {
                install(&path, &digest)
                    .map(|()| Output::Update(path, digest))
                    .map_err(|error| format!("Update could not be staged. {error}"))
            }
        }
        result => result,
    };
    // Keep the cancellation state until the handoff decision has been made.
    *cancel = None;
    result
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
    fn queued_update_completion_honors_cancel_before_dispatch() {
        let cancel = Arc::new(AtomicBool::new(false));
        let mut cancellation = Some(Arc::clone(&cancel));
        let (tx, rx) = mpsc::channel();
        assert!(
            tx.send(Event::Done(Ok(Output::Update(
                PathBuf::from("verified-download.exe"),
                "ab".repeat(32),
            ))))
            .is_ok()
        );
        // The download finished, but the UI has not handed installation off yet.
        cancel.store(true, Ordering::Relaxed);
        let Event::Done(result) = rx.try_recv().unwrap() else {
            panic!("Expected the queued update completion");
        };
        let installs = Cell::new(0);
        let completed = dispatch_completion(result, &mut cancellation, |_, _| {
            installs.set(installs.get() + 1);
            Ok(())
        })
        .unwrap();
        assert_eq!(installs.get(), 0);
        assert!(
            matches!(completed, Output::Status(status) if status.starts_with("Update canceled before installation."))
        );
        assert!(cancellation.is_none());
    }

    #[test]
    fn queued_update_completion_hands_off_before_later_cancel() {
        let cancel = Arc::new(AtomicBool::new(false));
        let mut cancellation = Some(Arc::clone(&cancel));
        let (tx, rx) = mpsc::channel();
        assert!(
            tx.send(Event::Done(Ok(Output::Update(
                PathBuf::from("verified-download.exe"),
                "ab".repeat(32),
            ))))
            .is_ok()
        );
        let Event::Done(result) = rx.try_recv().unwrap() else {
            panic!("Expected the queued update completion");
        };
        let installs = Cell::new(0);
        let completed = dispatch_completion(result, &mut cancellation, |path, digest| {
            assert_eq!(path, Path::new("verified-download.exe"));
            assert_eq!(digest, "ab".repeat(32));
            installs.set(installs.get() + 1);
            Ok(())
        })
        .unwrap();
        // Cancellation after a completed handoff cannot undo the started helper.
        cancel.store(true, Ordering::Relaxed);
        assert_eq!(installs.get(), 1);
        assert!(matches!(completed, Output::Update(_, _)));
        assert!(cancellation.is_none());
    }

    fn transport_fixture() -> (PathBuf, PathBuf, Adb) {
        let project = Path::new(env!("CARGO_MANIFEST_DIR"));
        let root = device::new_folder(&project.join("target"), "transport-fixture").unwrap();
        let platform = root.join("platform-tools");
        fs::create_dir_all(&platform).unwrap();
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
        for library in ["AdbWinApi.dll", "AdbWinUsbApi.dll"] {
            fs::write(platform.join(library), b"mock adb library").unwrap();
        }
        let adb = Adb::from_path(executable).unwrap();
        (root, platform, adb)
    }

    #[test]
    fn setup_completion_rebinds_the_device_after_an_adb_root_restart() {
        let (root, platform, adb) = transport_fixture();
        let stop = AtomicBool::new(false);
        let selected = adb.devices(&stop).unwrap().remove(0);
        assert_eq!(selected.transport, Some(7));
        fs::write(platform.join("no-root"), b"yes").unwrap();
        fs::write(platform.join("change-transport-after-root"), b"yes").unwrap();
        let (connected, _) = setup::root_device(&adb, &selected, &stop, &|_| {}).unwrap();
        assert_eq!(connected.transport, Some(9));
        assert!(
            run_job(
                Job::Info,
                Some(adb.clone()),
                Some(selected.clone()),
                &root,
                &stop,
                |_| {},
                &|_, _| {},
            )
            .is_err()
        );
        let completed = run_job(
            Job::Turnkey {
                path: root.join("unused.apk"),
                expected: None,
                runtime: root.join("missing-runtime"),
                lang: Lang::En,
            },
            Some(adb.clone()),
            Some(selected.clone()),
            &root,
            &stop,
            |_| {},
            &|_, _| {},
        );
        let Ok(Output::Cycle(devices, _, false)) = completed else {
            panic!(
                "Setup completion must replace the UI's stale device transports, including on failure"
            );
        };
        let selected = devices
            .get(preferred_device_index(&devices, Some(&selected.serial)).unwrap())
            .unwrap()
            .clone();
        assert_eq!(selected.transport, Some(9));
        for job in [
            Job::Info,
            Job::Launch,
            Job::Service {
                action: ServiceAction::Rollback,
                fingerprint: "example/demo/build".into(),
                input: String::new(),
                backup: None,
            },
        ] {
            assert!(
                run_job(
                    job,
                    Some(adb.clone()),
                    Some(selected.clone()),
                    &root,
                    &stop,
                    |_| {},
                    &|_, _| {},
                )
                .is_ok()
            );
        }
    }

    #[test]
    fn setup_completion_refreshes_success_and_cancellation_without_losing_the_result() {
        let (root, platform, adb) = transport_fixture();
        let stop = AtomicBool::new(false);
        let selected = adb.devices(&stop).unwrap().remove(0);
        fs::write(platform.join("transport"), "9").unwrap();
        let Output::Cycle(devices, status, true) =
            cycle_completion(&adb, Ok("Setup verified by Android.".into()))
        else {
            panic!("Successful setup must refresh its connection");
        };
        assert_eq!(status, "Setup verified by Android.");
        assert_eq!(devices[0].transport, Some(9));

        fs::write(root.join("runtime"), b"fixture runtime").unwrap();
        stop.store(true, Ordering::Relaxed);
        let result = run_job(
            Job::Turnkey {
                path: root.join("unused.apk"),
                expected: None,
                runtime: root.join("runtime"),
                lang: Lang::En,
            },
            Some(adb.clone()),
            Some(selected),
            &root,
            &stop,
            |_| {},
            &|_, _| {},
        );
        let Ok(Output::Cycle(devices, status, false)) = result else {
            panic!("Canceled setup must still discard the old transport");
        };
        assert!(status.contains("Canceled"));
        assert_eq!(devices[0].transport, Some(9));
        assert!(stop.load(Ordering::Relaxed));

        fs::write(platform.join("devices-unavailable"), b"yes").unwrap();
        let Output::Cycle(devices, status, false) =
            cycle_completion(&adb, Err("Canceled after requesting root.".into()))
        else {
            panic!("A failed refresh must clear the old connection list");
        };
        assert!(devices.is_empty());
        assert!(status.contains("Canceled after requesting root."));
        assert!(status.contains("Select Refresh before continuing"));
    }

    #[test]
    fn refreshed_selection_requires_one_matching_device() {
        let mut devices =
            adb::parse_devices("unit-b device transport_id:8\nunit-a device transport_id:9");
        assert_eq!(preferred_device_index(&devices, Some("unit-a")), Some(1));
        assert_eq!(preferred_device_index(&devices, Some("missing")), None);
        assert_eq!(preferred_device_index(&devices, None), None);
        devices.extend(adb::parse_devices("unit-a device transport_id:10"));
        assert_eq!(preferred_device_index(&devices, Some("unit-a")), None);
    }

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

    /// A portable SDK layout whose adb, aapt2 and java are the offline fixture; the ADB DLLs are left to the caller.
    fn mock_sdk() -> (PathBuf, PathBuf, PathBuf) {
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
        (root, platform, executable)
    }

    #[test]
    fn youtube_install_uses_its_own_pin_and_leaves_controls_setup_alone() {
        let (root, platform, executable) = mock_sdk();
        for library in ["AdbWinApi.dll", "AdbWinUsbApi.dll"] {
            fs::write(platform.join(library), b"mock adb library").unwrap();
        }
        for name in [
            "youtube",
            "youtube-update",
            "youtube-newer",
            "youtube-debug",
            "youtube-controls-key",
            "valid",
        ] {
            fs::write(root.join(format!("{name}.apk")), name).unwrap();
        }
        let adb = Adb::from_path(executable).unwrap();
        let stop = AtomicBool::new(false);
        let device = adb.devices(&stop).unwrap().remove(0);
        let perform = |job| {
            run_job(
                job,
                Some(adb.clone()),
                Some(device.clone()),
                &root,
                &stop,
                |_| {},
                &|_, _| {},
            )
        };
        let youtube = |name: &str, expected: Option<ReleaseApk>| Job::InstallYouTube {
            path: root.join(format!("{name}.apk")),
            expected,
        };
        let status = |job| match perform(job) {
            Ok(Output::Status(text)) => Ok(text),
            Ok(_) => Err("unexpected output".to_string()),
            Err(error) => Err(error),
        };

        assert!(
            status(Job::Info)
                .unwrap()
                .contains("YouTube app (Android user 0): Absent")
        );
        // An active Controls deployment stays as it is: the wrapper needs no rollback and no root.
        fs::write(platform.join("active-hooks"), "running").unwrap();
        assert!(
            status(youtube("youtube", None))
                .unwrap()
                .contains("YouTube 0.1.0 installed and verified")
        );
        assert_eq!(
            fs::read_to_string(platform.join("installed-youtube")).unwrap(),
            "youtube"
        );
        assert!(platform.join("active-hooks").is_file());
        assert!(!platform.join("installed-apk").exists());
        assert!(
            status(Job::Info)
                .unwrap()
                .contains("YouTube app (Android user 0): Installed, version 0.1.0")
        );
        assert!(
            status(youtube("youtube", None))
                .unwrap()
                .contains("already installed")
        );

        for (name, error) in [
            ("youtube-controls-key", "pinned YouTube release certificate"),
            ("valid", "Expected APK package org.openvoyah.youtube"),
            ("youtube-debug", "non-debuggable"),
        ] {
            assert!(
                matches!(status(youtube(name, None)), Err(e) if e.contains(error)),
                "{name}"
            );
        }
        let official = ReleaseApk {
            version: semver::Version::new(0, 3, 0),
            sha256: "0".repeat(64),
        };
        assert!(matches!(
            status(youtube("youtube-newer", Some(official))),
            Err(e) if e.contains("official release")
        ));

        assert!(
            status(youtube("youtube-update", None))
                .unwrap()
                .contains("YouTube 0.2.0 installed")
        );
        assert!(
            matches!(status(youtube("youtube", None)), Err(e) if e.contains("newer YouTube release"))
        );
        assert_eq!(
            fs::read_to_string(platform.join("installed-youtube")).unwrap(),
            "youtube-update"
        );

        // A debug build on the unit has another signer; Android would refuse the update, so the user is told first.
        fs::write(platform.join("installed-youtube"), "youtube-debug").unwrap();
        assert!(matches!(
            status(youtube("youtube-newer", None)),
            Err(e) if e.contains("Uninstall it on the head unit first")
        ));
        assert_eq!(
            fs::read_to_string(platform.join("installed-youtube")).unwrap(),
            "youtube-debug"
        );
        assert!(platform.join("active-hooks").is_file());
    }

    #[test]
    fn installer_jobs_validate_apks_and_use_only_the_selected_transport() {
        let (root, platform, executable) = mock_sdk();
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
                &|_, _| {},
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
        fs::write(platform.join("no-root"), b"yes").unwrap();
        assert!(setup::root_device(&adb, &devices[0], &stop, &|_| {}).is_ok());
        fs::write(platform.join("no-root"), b"yes").unwrap();
        fs::write(platform.join("root-denied"), b"yes").unwrap();
        assert!(
            setup::root_device(&adb, &devices[0], &stop, &|_| {})
                .unwrap_err()
                .contains("does not allow ADB root")
        );
        fs::remove_file(platform.join("root-denied")).unwrap();
        fs::write(platform.join("swap-after-root"), b"yes").unwrap();
        assert!(
            setup::root_device(&adb, &devices[0], &stop, &|_| {})
                .unwrap_err()
                .contains("different device identity")
        );
        fs::remove_file(platform.join("changed-identity")).unwrap();
        let target = adb.target(&devices[0], &stop).unwrap();
        assert!(!setup::probe_app(&target).unwrap().reviewed);
        fs::write(platform.join("setup-result"), b"ready\thooks\t1\t1").unwrap();
        assert!(setup::probe_app(&target).unwrap().reviewed);
        fs::write(platform.join("setup-result"), b"failed\tvoice\t0\t0").unwrap();
        assert!(
            setup::probe_app(&target)
                .unwrap_err()
                .contains("failed at 'voice'")
        );
        fs::write(
            platform.join("foreign-transport"),
            "changed after selection",
        )
        .unwrap();
        assert!(matches!(perform(Job::Launch, 0), Err(e) if e.contains("transport changed")));
    }

    #[test]
    fn an_unknown_owner_certificate_is_shown_before_anything_changes_and_trusted_builds_install() {
        let (root, platform, executable) = mock_sdk();
        for library in ["AdbWinApi.dll", "AdbWinUsbApi.dll"] {
            fs::write(platform.join(library), b"mock adb library").unwrap();
        }
        fs::write(root.join("owner.apk"), "owner-debug").unwrap();
        let adb = Adb::from_path(executable).unwrap();
        let stop = AtomicBool::new(false);
        let device = adb.devices(&stop).unwrap().remove(0);
        let perform = |job| {
            run_job(
                job,
                Some(adb.clone()),
                Some(device.clone()),
                &root,
                &stop,
                |_| {},
                &|_, _| {},
            )
        };
        let turnkey = || Job::Turnkey {
            path: root.join("owner.apk"),
            expected: None,
            runtime: root.join("missing-runtime"),
            lang: Lang::Ru,
        };
        let Ok(Output::Trust(facts)) = perform(turnkey()) else {
            panic!("An untrusted certificate must be shown to the user first");
        };
        assert_eq!(facts.signer, "4".repeat(64));
        assert!(facts.debuggable);
        assert_eq!(facts.version, "0.21.0-local");
        assert!(
            !platform.join("installed-apk").exists(),
            "nothing reaches the head unit"
        );
        let install = || Job::Install {
            path: root.join("owner.apk"),
            expected: None,
        };
        assert!(matches!(perform(install()), Err(e) if e.contains("signer")));

        // Trusted, the debuggable build installs and is labelled; the turnkey cycle goes past the certificate step.
        Trust::remember(&root, &"4".repeat(64)).unwrap();
        Trust::remember(&root, &"4".repeat(64)).unwrap();
        assert_eq!(
            fs::read_to_string(root.join(production::TRUSTED_SIGNERS))
                .unwrap()
                .lines()
                .count(),
            1
        );
        assert!(matches!(perform(install()), Ok(Output::Status(s)) if s.contains("owner-build")));
        assert_eq!(
            fs::read_to_string(platform.join("installed-apk")).unwrap(),
            "owner-debug"
        );
        let Ok(Output::Cycle(_, text, false)) = perform(turnkey()) else {
            panic!("The trusted build runs the cycle");
        };
        assert!(text.contains("Остановлено на шаге 1"), "{text}");
        assert!(text.contains("Frida"), "{text}");
    }

    #[test]
    fn other_apps_install_list_and_remove_without_touching_controls() {
        let (root, platform, executable) = mock_sdk();
        for library in ["AdbWinApi.dll", "AdbWinUsbApi.dll"] {
            fs::write(platform.join(library), b"mock adb library").unwrap();
        }
        fs::write(root.join("maps.apk"), "app-maps").unwrap();
        fs::write(root.join("radio.apk"), "app-radio").unwrap();
        fs::write(root.join("controls.apk"), "valid").unwrap();
        fs::write(platform.join("active-hooks"), "running").unwrap();
        fs::write(platform.join("installed-apk"), "valid").unwrap();
        let adb = Adb::from_path(executable).unwrap();
        let stop = AtomicBool::new(false);
        let device = adb.devices(&stop).unwrap().remove(0);
        let perform = |job| {
            run_job(
                job,
                Some(adb.clone()),
                Some(device.clone()),
                &root,
                &stop,
                |_| {},
                &|_, _| {},
            )
        };
        let Ok(Output::Apps(list, text)) = perform(Job::Apps(AppsJob::Install(vec![
            root.join("maps.apk"),
            root.join("radio.apk"),
        ]))) else {
            panic!("Installing other apps refreshes the list");
        };
        assert!(text.starts_with("Installed 2 app(s)"), "{text}");
        assert!(text.contains("activation checked: unchanged"), "{text}");
        assert_eq!(
            list.iter()
                .map(|app| app.package.as_str())
                .collect::<Vec<_>>(),
            ["com.example.maps", "com.example.radio"]
        );
        assert!(platform.join("active-hooks").is_file());
        assert_eq!(
            fs::read_to_string(platform.join("installed-apk")).unwrap(),
            "valid"
        );

        // Controls in the batch stops it before the first install.
        fs::remove_dir_all(platform.join("installed-apps")).unwrap();
        assert!(matches!(
            perform(Job::Apps(AppsJob::Install(vec![root.join("maps.apk"), root.join("controls.apk")]))),
            Err(e) if e.contains("Installation page")
        ));
        assert!(!platform.join("installed-apps").exists());

        perform(Job::Apps(AppsJob::Install(vec![root.join("maps.apk")]))).unwrap();
        let Ok(Output::Apps(list, text)) =
            perform(Job::Apps(AppsJob::Uninstall("com.example.maps".into())))
        else {
            panic!("Uninstall refreshes the list");
        };
        assert!(list.is_empty() && text.contains("removed"));
        assert!(matches!(
            perform(Job::Apps(AppsJob::Uninstall("org.openvoyah.controls".into()))),
            Err(e) if e.contains("Installation page")
        ));
        assert!(platform.join("installed-apk").is_file());
    }
}

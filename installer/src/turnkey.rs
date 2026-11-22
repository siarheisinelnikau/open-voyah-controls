//! The one-button installation: the whole Controls cycle from a chosen APK to agents that start by themselves after a
//! restart. It replaces the manual sequence install → rollback → restart → `adb root` → rearm, access, boot entry,
//! first-run check → restart, and stops in a safe state with Retry and Roll back on any failure.

use crate::{
    adb::{Adb, Device, Result},
    device, init_service,
    production::{self, ApkChoice, Build, Identity, Product, Trust},
    recovery::{self, BootStatus},
    setup,
};
use std::{
    path::{Path, PathBuf},
    sync::atomic::{AtomicBool, Ordering},
    thread,
    time::{Duration, Instant},
};

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum Lang {
    #[default]
    En,
    Ru,
}

impl Lang {
    pub fn pick(self, en: &str, ru: &str) -> String {
        match self {
            Lang::En => en.to_owned(),
            Lang::Ru => ru.to_owned(),
        }
    }
}

/// The steps the Installation page lists, in English and Russian.
pub const STEPS: [(&str, &str); 12] = [
    (
        "Check the APK and the head unit",
        "Проверка APK и магнитолы",
    ),
    (
        "ADB root and startup checks",
        "ADB root и проверка автозапуска",
    ),
    (
        "Back up the startup script to this PC",
        "Копия скрипта автозапуска на ПК",
    ),
    ("Writable system partition", "Запись в системный раздел"),
    (
        "Pause the previous installation",
        "Остановка прежней установки",
    ),
    ("Install the app", "Установка приложения"),
    (
        "Frida injector and activation",
        "Инжектор Frida и активация",
    ),
    ("Root channel and permissions", "Root-канал и разрешения"),
    ("Startup entry", "Запись автозапуска"),
    (
        "First-run check in the app",
        "Первичная проверка в приложении",
    ),
    ("Restart the head unit", "Перезагрузка магнитолы"),
    ("Agents started by themselves", "Агенты запустились сами"),
];

const CHECK: usize = 0;
const ROOT: usize = 1;
const BACKUP: usize = 2;
const REMOUNT: usize = 3;
const PAUSE: usize = 4;
const INSTALL: usize = 5;
const RUNTIME: usize = 6;
const ACCESS: usize = 7;
const ENTRY: usize = 8;
const PROBE: usize = 9;
const RESTART: usize = 10;
const VERIFY: usize = 11;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum StepState {
    Pending,
    Running,
    Done,
    Skipped,
    Failed,
}

/// Where the cycle reports its progress: one call per step change and free text for the Activity box.
pub struct Reporter<'a> {
    pub lang: Lang,
    pub step: &'a dyn Fn(usize, StepState),
    pub say: &'a dyn Fn(String),
}

impl Reporter<'_> {
    fn say(&self, en: &str, ru: &str) {
        (self.say)(self.lang.pick(en, ru));
    }
}

/// What the PC checks found before the first change.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Plan {
    /// Android already holds this exact APK.
    pub same: bool,
    /// Controls is installed (any version).
    pub update: bool,
    /// The exact firmware has a reviewed profile, so the agents may start.
    pub reviewed: bool,
    pub build: Build,
    pub version: String,
}

pub enum Rollback {
    Done,
    NeedsRestart,
}

pub enum Probe {
    Ready { mic: bool, buttons: bool },
    Unreviewed,
}

/// The device operations the cycle needs. The real unit implements them over ADB; tests use a scripted fake.
pub trait Unit {
    fn checks(&mut self) -> Result<Plan>;
    fn root(&mut self) -> Result<()>;
    fn preflight(&mut self) -> Result<()>;
    fn backup(&mut self) -> Result<PathBuf>;
    fn remount(&mut self) -> Result<()>;
    fn status(&mut self) -> Result<BootStatus>;
    fn rollback(&mut self) -> Result<Rollback>;
    /// Restart, wait until the same unit has booted, and restore ADB root.
    fn restart(&mut self) -> Result<()>;
    fn install(&mut self) -> Result<String>;
    fn runtime(&mut self, reviewed: bool) -> Result<()>;
    fn access(&mut self) -> Result<()>;
    fn entry(&mut self) -> Result<()>;
    fn probe(&mut self) -> Result<Probe>;
    fn wait(&mut self, seconds: u64);
}

/// A failed cycle: the step, what happened, and whether the unit is untouched.
#[derive(Debug)]
pub struct Failure {
    pub step: usize,
    pub message: String,
}

impl Failure {
    pub fn text(&self, lang: Lang) -> String {
        let (en, ru) = STEPS[self.step];
        let state = if self.step <= REMOUNT {
            lang.pick(
                "Nothing that Controls relies on was changed on the head unit.",
                "На магнитоле ничего, от чего зависит Controls, не изменено.",
            )
        } else if self.step <= PROBE {
            lang.pick(
                "The installation stopped in a safe state. Select Retry to continue from where it stopped, or Roll back to restore the original startup script.",
                "Установка остановлена в безопасном состоянии. Нажмите «Повторить», чтобы продолжить с этого места, или «Откатить», чтобы вернуть исходный автозапуск.",
            )
        } else {
            lang.pick(
                "Controls is installed, but its agents did not confirm a start after the restart. Select Retry to check again, or Roll back.",
                "Controls установлен, но после перезагрузки агенты не подтвердили запуск. Нажмите «Повторить», чтобы проверить ещё раз, или «Откатить».",
            )
        };
        format!(
            "{} {} — {}. {}\r\n{}",
            lang.pick("Stopped at step", "Остановлено на шаге"),
            self.step + 1,
            lang.pick(en, ru),
            state,
            self.message
        )
    }
}

fn ready_enough(status: &BootStatus, reviewed: bool) -> bool {
    status.entry == "present"
        && !status.blocked
        && status.access == "active"
        && (!reviewed || status.has("launcher"))
}

/// Polls the boot status until the agents report ready or `seconds` pass. Returns the last status.
fn await_ready(unit: &mut impl Unit, seconds: u64, reviewed: bool) -> Result<BootStatus> {
    let rounds = seconds.div_ceil(5).max(1);
    let mut last = unit.status()?;
    for _ in 0..rounds {
        if ready_enough(&last, reviewed) {
            break;
        }
        unit.wait(5);
        last = unit.status()?;
    }
    Ok(last)
}

/// Runs the whole cycle. Every step is idempotent, so Retry simply runs it again.
pub fn run(unit: &mut impl Unit, report: &Reporter<'_>) -> std::result::Result<String, Failure> {
    let mut current = CHECK;
    let mut restarts = 0;
    let step = |index: usize, state: StepState, current: &mut usize| {
        if state == StepState::Running {
            *current = index;
        }
        (report.step)(index, state);
    };
    let fail = |step: usize, message: String| {
        (report.step)(step, StepState::Failed);
        Failure { step, message }
    };
    for index in 0..STEPS.len() {
        (report.step)(index, StepState::Pending);
    }

    step(CHECK, StepState::Running, &mut current);
    report.say(
        "Checking the APK, its certificate and the head unit on this PC…",
        "Проверяю APK, его сертификат и магнитолу на этом ПК…",
    );
    let plan = unit.checks().map_err(|e| fail(current, e))?;
    step(CHECK, StepState::Done, &mut current);

    step(ROOT, StepState::Running, &mut current);
    report.say(
        "Requesting ADB root and checking the startup service…",
        "Запрашиваю ADB root и проверяю службу автозапуска…",
    );
    unit.root().map_err(|e| fail(current, e))?;
    let status = unit.status().map_err(|e| fail(current, e))?;
    if plan.same && ready_enough(&status, plan.reviewed) {
        step(ROOT, StepState::Done, &mut current);
        for index in BACKUP..VERIFY {
            step(index, StepState::Skipped, &mut current);
        }
        step(VERIFY, StepState::Done, &mut current);
        return Ok(report.lang.pick(
            &format!(
                "This Controls {} is already installed and running; nothing was changed. Ready: {}.",
                plan.version,
                status.ready.join(", ")
            ),
            &format!(
                "Эта версия Controls {} уже установлена и работает; ничего не менялось. Готовы: {}.",
                plan.version,
                status.ready.join(", ")
            ),
        ));
    }
    unit.preflight().map_err(|e| fail(current, e))?;
    step(ROOT, StepState::Done, &mut current);

    step(BACKUP, StepState::Running, &mut current);
    report.say(
        "Saving the original startup script on this PC…",
        "Сохраняю исходный скрипт автозапуска на этом ПК…",
    );
    let backup = unit.backup().map_err(|e| fail(current, e))?;
    step(BACKUP, StepState::Done, &mut current);

    step(REMOUNT, StepState::Running, &mut current);
    report.say(
        "Making the system partition writable (a first setup may restart the head unit once)…",
        "Открываю системный раздел для записи (при первой установке магнитола может перезагрузиться один раз)…",
    );
    unit.remount().map_err(|e| fail(current, e))?;
    step(REMOUNT, StepState::Done, &mut current);

    step(PAUSE, StepState::Running, &mut current);
    let mut status = unit.status().map_err(|e| fail(current, e))?;
    if status.inert {
        step(PAUSE, StepState::Skipped, &mut current);
    } else {
        for attempt in 0..2 {
            report.say(
                "Pausing the previous installation and restoring the original startup script…",
                "Останавливаю прежнюю установку и возвращаю исходный автозапуск…",
            );
            let outcome = unit.rollback().map_err(|e| fail(current, e))?;
            status = unit.status().map_err(|e| fail(current, e))?;
            if matches!(outcome, Rollback::NeedsRestart) || !status.inert {
                report.say(
                    "The previous installation is paused. Restarting the head unit to finish (restart 1 of 2)…",
                    "Прежняя установка остановлена. Перезагружаю магнитолу, чтобы завершить (перезагрузка 1 из 2)…",
                );
                unit.restart().map_err(|e| fail(current, e))?;
                restarts += 1;
                unit.remount().map_err(|e| fail(current, e))?;
                status = unit.status().map_err(|e| fail(current, e))?;
            }
            if status.inert {
                break;
            }
            if attempt == 1 {
                return Err(fail(
                    current,
                    "The previous installation did not reach an inactive state after a restart. Check Device status."
                        .into(),
                ));
            }
        }
        step(PAUSE, StepState::Done, &mut current);
    }

    step(INSTALL, StepState::Running, &mut current);
    report.say(
        &format!("Installing Controls {}…", plan.version),
        &format!("Устанавливаю Controls {}…", plan.version),
    );
    unit.install().map_err(|e| fail(current, e))?;
    step(INSTALL, StepState::Done, &mut current);

    step(RUNTIME, StepState::Running, &mut current);
    report.say(
        "Staging the verified Frida injector and allowing activation…",
        "Размещаю проверенный инжектор Frida и разрешаю активацию…",
    );
    unit.runtime(plan.reviewed).map_err(|e| fail(current, e))?;
    step(RUNTIME, StepState::Done, &mut current);

    step(ACCESS, StepState::Running, &mut current);
    report.say(
        "Installing the private root channel, microphone permission and button service…",
        "Устанавливаю закрытый root-канал, разрешение на микрофон и службу кнопок…",
    );
    unit.access().map_err(|e| fail(current, e))?;
    step(ACCESS, StepState::Done, &mut current);

    step(ENTRY, StepState::Running, &mut current);
    report.say("Adding the startup entry…", "Добавляю запись автозапуска…");
    unit.entry().map_err(|e| fail(current, e))?;
    step(ENTRY, StepState::Done, &mut current);

    step(PROBE, StepState::Running, &mut current);
    // The app deploys its agents during the first-run check. Do not repeat the check when they already report ready.
    let status = unit.status().map_err(|e| fail(current, e))?;
    let mut note = String::new();
    if plan.reviewed && status.has("launcher") {
        step(PROBE, StepState::Skipped, &mut current);
    } else {
        report.say(
            "Running the first-run check in the app on the head unit (up to four minutes)…",
            "Запускаю первичную проверку в приложении на магнитоле (до четырёх минут)…",
        );
        match unit.probe() {
            Ok(Probe::Ready { mic, buttons }) => {
                if !mic || !buttons {
                    note = report.lang.pick(
                        " Allow the microphone on first use and check the button service in Accessibility settings.",
                        " Разрешите микрофон при первом использовании и проверьте службу кнопок в настройках специальных возможностей.",
                    );
                }
            }
            Ok(Probe::Unreviewed) => {
                note = report.lang.pick(
                    " This exact firmware has no reviewed profile: app and voice features work, vehicle handlers stay off.",
                    " Для этой прошивки нет проверенного профиля: приложение и голос работают, функции машины выключены.",
                );
            }
            Err(error) => {
                // The check can answer before the agents are up; they often report ready a minute later.
                report.say(
                    "The app answered before its agents were up. Waiting for them to report ready…",
                    "Приложение ответило раньше, чем поднялись агенты. Жду их готовности…",
                );
                let status = await_ready(unit, 180, plan.reviewed).map_err(|e| fail(current, e))?;
                if !ready_enough(&status, plan.reviewed) {
                    return Err(fail(current, error));
                }
            }
        }
        step(PROBE, StepState::Done, &mut current);
    }

    step(RESTART, StepState::Running, &mut current);
    report.say(
        &format!(
            "Restarting the head unit to confirm that everything starts by itself (restart {} of {})…",
            restarts + 1,
            restarts + 1
        ),
        &format!(
            "Перезагружаю магнитолу, чтобы убедиться, что всё запускается само (перезагрузка {} из {})…",
            restarts + 1,
            restarts + 1
        ),
    );
    unit.restart().map_err(|e| fail(current, e))?;
    step(RESTART, StepState::Done, &mut current);

    step(VERIFY, StepState::Running, &mut current);
    report.say(
        "Waiting for the agents to start by themselves (up to five minutes)…",
        "Жду, пока агенты запустятся сами (до пяти минут)…",
    );
    let status = await_ready(unit, 300, plan.reviewed).map_err(|e| fail(current, e))?;
    if !ready_enough(&status, plan.reviewed) {
        return Err(fail(
            current,
            format!(
                "After the restart: startup entry {}, root channel {}, recovery block {}, ready agents: {}.",
                status.entry,
                status.access,
                if status.blocked { "yes" } else { "no" },
                if status.ready.is_empty() {
                    "none".into()
                } else {
                    status.ready.join(", ")
                }
            ),
        ));
    }
    step(VERIFY, StepState::Done, &mut current);
    let build = match plan.build {
        Build::Release => report.lang.pick("release", "релиз"),
        Build::Owner => report.lang.pick("owner build", "сборка владельца"),
    };
    let ready = if status.ready.is_empty() {
        report.lang.pick("root channel only", "только root-канал")
    } else {
        status.ready.join(", ")
    };
    Ok(report.lang.pick(
        &format!(
            "Done. Controls {} ({build}) is installed, the head unit restarted and the agents started by themselves: {ready}. Startup backup: {}.{note}",
            plan.version,
            backup.display()
        ),
        &format!(
            "Готово. Controls {} ({build}) установлен, магнитола перезагружена, агенты запустились сами: {ready}. Копия автозапуска: {}.{note}",
            plan.version,
            backup.display()
        ),
    ))
}

/// The real head unit over ADB.
pub struct Live<'a> {
    pub adb: &'a Adb,
    pub device: Device,
    pub apk: ApkChoice<'a>,
    pub runtime: &'a Path,
    pub store: &'a Path,
    pub trust: &'a Trust,
    pub stop: &'a AtomicBool,
    pub say: &'a dyn Fn(String),
    pub identity: Option<Identity>,
    pub backup: Option<PathBuf>,
    pub build: Build,
}

impl Live<'_> {
    fn verification(&self) -> PathBuf {
        self.store.join("verification")
    }

    fn identity(&self) -> Result<&Identity> {
        self.identity
            .as_ref()
            .ok_or_else(|| "The head unit identity was not read.".to_string())
    }

    fn target(&self) -> Result<crate::adb::Target<'_>> {
        self.adb.target(&self.device, self.stop)
    }
}

impl Unit for Live<'_> {
    fn checks(&mut self) -> Result<Plan> {
        let metadata: serde_json::Value =
            serde_json::from_str(include_str!("../../android/dist/FRIDA_RUNTIME.json"))
                .map_err(device::message)?;
        if !self.runtime.is_file()
            || Some(device::digest(self.runtime, self.stop)?.as_str())
                != metadata.get("binary_sha256").and_then(|v| v.as_str())
        {
            return Err(
                "The Frida injector next to the installer does not match the pinned digest. Use the complete portable kit."
                    .into(),
            );
        }
        let verification = self.verification();
        let target = self.target()?;
        let identity = Identity::read(&target)?;
        production::check_setup_device(&target, &identity)?;
        let candidate = production::check_candidate(
            &target,
            self.apk.path,
            self.apk.expected,
            &verification,
            Product::Controls,
            self.trust,
        )?;
        let installed = production::installed_controls(&target, &identity, &verification, self.trust)
            .map_err(|error| format!("The Controls app on the head unit is signed by a certificate this PC does not accept, so Android cannot update it in place. Nothing was changed. Use Remove old app for signer migration under Maintenance. {error}"))?;
        if let Some(installed) = &installed
            && !installed.signer.eq_ignore_ascii_case(&candidate.signer)
        {
            return Err(format!(
                "The Controls app on the head unit is signed by certificate {}, the selected APK by {}. Android cannot update an app across certificates; nothing was changed. Use Remove old app for signer migration under Maintenance.",
                installed.signer, candidate.signer
            ));
        }
        self.build = candidate.build;
        let reviewed = production::has_review(&identity, self.trust);
        let plan = Plan {
            same: installed
                .as_ref()
                .is_some_and(|installed| installed.sha256 == candidate.sha256),
            update: installed.is_some(),
            reviewed,
            build: candidate.build,
            version: candidate.version.to_string(),
        };
        self.identity = Some(identity);
        Ok(plan)
    }

    fn root(&mut self) -> Result<()> {
        let (device, identity) = setup::root_device(self.adb, &self.device, self.stop, &self.say)?;
        if self
            .identity
            .as_ref()
            .is_some_and(|known| *known != identity)
        {
            return Err("The head unit identity changed after ADB root. Setup stopped.".into());
        }
        self.device = device;
        Ok(())
    }

    fn preflight(&mut self) -> Result<()> {
        let identity = self.identity()?.clone();
        let target = self.target()?;
        target.script(setup::FIND_TELNET, &[], 15)?;
        target.script("set -e\numask 077\nif [ ! -e /data/adb ]; then mkdir -m 700 /data/adb; fi\ntest ! -L /data/adb && test \"$(stat -c %u:%a /data/adb)\" = 0:700\n", &[], 10)?;
        match init_service::preflight(&target, identity.sdk) {
            Ok(service) => {
                (self.say)(format!(
                    "Root init service {} at {} runs the startup script.",
                    service.name, service.source
                ));
                Ok(())
            }
            // An owner's unit that was already set up from this PC (it holds a verified boot backup of this exact unit
            // and build) or still runs our startup entry has proven the service by booting it.
            Err(error)
                if self.build == Build::Owner
                    && (recovery::latest_backup(self.store, &identity).is_some()
                        || recovery::boot_status(&target, &identity)
                            .is_ok_and(|status| status.entry == "present")) =>
            {
                (self.say)(format!(
                    "The init configuration has constructs the preflight does not read; this unit already started the Controls entry before, so setup continues. {error}"
                ));
                Ok(())
            }
            Err(error) => Err(format!(
                "The logcat root-service preflight did not pass. {error} No startup files were changed."
            )),
        }
    }

    fn backup(&mut self) -> Result<PathBuf> {
        let identity = self.identity()?.clone();
        let folder = recovery::export_boot_backup_folder(
            &self.target()?,
            &identity.fingerprint,
            &self.store.join("backups"),
        )?;
        self.backup = Some(folder.clone());
        Ok(folder)
    }

    fn remount(&mut self) -> Result<()> {
        let identity = self.identity()?.clone();
        self.device = setup::remount(
            self.adb,
            self.device.clone(),
            &identity,
            self.stop,
            &self.say,
        )?;
        Ok(())
    }

    fn status(&mut self) -> Result<BootStatus> {
        let identity = self.identity()?.clone();
        recovery::boot_status(&self.target()?, &identity)
    }

    fn rollback(&mut self) -> Result<Rollback> {
        let identity = self.identity()?.clone();
        match recovery::rollback(&self.target()?, &identity.fingerprint) {
            Ok(_) => Ok(Rollback::Done),
            Err(error) if recovery::rollback_needs_restart(&error) => Ok(Rollback::NeedsRestart),
            Err(error) => Err(error),
        }
    }

    fn restart(&mut self) -> Result<()> {
        let identity = self.identity()?.clone();
        self.device = setup::restart(self.adb, &self.device, &identity, self.stop, &self.say)?;
        Ok(())
    }

    fn install(&mut self) -> Result<String> {
        let verification = self.verification();
        production::install_controls(
            &self.target()?,
            self.apk.path,
            self.apk.expected,
            &verification,
            self.trust,
            self.say,
        )
    }

    fn runtime(&mut self, reviewed: bool) -> Result<()> {
        let identity = self.identity()?.clone();
        let verification = self.verification();
        let target = self.target()?;
        production::verify_preparation(&target, &identity, &verification, self.trust)?;
        recovery::stage_setup_runtime(&target, self.runtime, &verification, self.trust, self.say)?;
        // A rollback leaves a block; only a reviewed firmware may clear it after the APK and runtime checks.
        if reviewed {
            recovery::rearm(&target, &identity.fingerprint, &verification, self.trust)?;
        }
        Ok(())
    }

    fn access(&mut self) -> Result<()> {
        let identity = self.identity()?.clone();
        setup::install_access(&self.target()?, &identity)
    }

    fn entry(&mut self) -> Result<()> {
        let identity = self.identity()?.clone();
        let backup = self
            .backup
            .clone()
            .ok_or("The PC boot backup is missing.")?;
        recovery::install_access_boot(
            &self.target()?,
            &identity,
            &backup,
            &self.verification(),
            self.trust,
        )?;
        Ok(())
    }

    fn probe(&mut self) -> Result<Probe> {
        let result = setup::probe_app(&self.target()?)?;
        Ok(if result.reviewed {
            Probe::Ready {
                mic: result.mic,
                buttons: result.buttons,
            }
        } else {
            Probe::Unreviewed
        })
    }

    fn wait(&mut self, seconds: u64) {
        let until = Instant::now() + Duration::from_secs(seconds);
        while Instant::now() < until && !self.stop.load(Ordering::Relaxed) {
            thread::sleep(Duration::from_millis(200));
        }
    }
}

/// Roll back from the Installation page: ADB root, a writable system partition, rollback, and the restart a rollback
/// may need, until the unit reports an inactive Controls setup. The APK and its settings stay.
pub fn roll_back(
    adb: &Adb,
    device: &Device,
    stop: &AtomicBool,
    lang: Lang,
    say: &dyn Fn(String),
) -> Result<String> {
    let (device, identity) = setup::root_device(adb, device, stop, &say)?;
    let device = setup::remount(adb, device, &identity, stop, &say)?;
    let target = adb.target(&device, stop)?;
    if recovery::boot_status(&target, &identity)?.inert {
        return Ok(lang.pick(
            "Controls is already inactive on this head unit; the original startup script is in place.",
            "Controls на этой магнитоле уже неактивен; исходный автозапуск на месте.",
        ));
    }
    say(lang.pick(
        "Stopping Controls and restoring the original startup script…",
        "Останавливаю Controls и возвращаю исходный автозапуск…",
    ));
    let restarted = match recovery::rollback(&target, &identity.fingerprint) {
        Ok(_) => false,
        Err(error) if recovery::rollback_needs_restart(&error) => {
            say(lang.pick(
                "Restarting the head unit to finish the rollback…",
                "Перезагружаю магнитолу, чтобы завершить откат…",
            ));
            let device = setup::restart(adb, &device, &identity, stop, &say)?;
            let target = adb.target(&device, stop)?;
            if !recovery::boot_status(&target, &identity)?.inert {
                return Err(lang.pick(
                    "The head unit restarted, but Controls is still not fully inactive. Check Device status.",
                    "Магнитола перезагрузилась, но Controls всё ещё не полностью неактивен. Проверьте состояние устройства.",
                ));
            }
            true
        }
        Err(error) => return Err(error),
    };
    Ok(lang.pick(
        &format!(
            "Rolled back: Controls is inactive and the original startup script is restored{}. The app and its settings stay; Install sets it up again.",
            if restarted { " after a restart" } else { "" }
        ),
        &format!(
            "Откат выполнен: Controls неактивен, исходный автозапуск восстановлен{}. Приложение и его настройки остались; «Установить» настроит его снова.",
            if restarted { " после перезагрузки" } else { "" }
        ),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    /// A head unit that follows the documented behavior: an install over an active deployment is refused, a rollback
    /// of active visuals needs a restart, the agents come up a little after the first-run check and after a restart.
    #[derive(Default)]
    struct Fake {
        log: Vec<String>,
        installed: bool,
        same: bool,
        active: bool,
        pending_visuals: bool,
        root: bool,
        entry: bool,
        access: bool,
        blocked: bool,
        ready: bool,
        reviewed: bool,
        probe_fails: bool,
        agents_after_restart: bool,
        fail_at: Option<&'static str>,
        clock: u64,
        ready_at: Option<u64>,
    }

    impl Fake {
        fn op(&mut self, name: &'static str) -> Result<()> {
            self.log.push(name.into());
            if self.fail_at == Some(name) {
                return Err(format!("{name} failed"));
            }
            Ok(())
        }
    }

    impl Unit for Fake {
        fn checks(&mut self) -> Result<Plan> {
            self.op("checks")?;
            Ok(Plan {
                same: self.same,
                update: self.installed,
                reviewed: self.reviewed,
                build: Build::Owner,
                version: "0.21.0".into(),
            })
        }
        fn root(&mut self) -> Result<()> {
            self.op("root")?;
            self.root = true;
            Ok(())
        }
        fn preflight(&mut self) -> Result<()> {
            assert!(self.root);
            self.op("preflight")
        }
        fn backup(&mut self) -> Result<PathBuf> {
            self.op("backup")?;
            Ok(PathBuf::from("backup"))
        }
        fn remount(&mut self) -> Result<()> {
            assert!(self.root, "remount needs root");
            self.op("remount")
        }
        fn status(&mut self) -> Result<BootStatus> {
            assert!(self.root, "status needs root");
            if self.ready_at.is_some_and(|at| self.clock >= at) {
                self.ready = true;
            }
            Ok(BootStatus {
                entry: if self.entry { "present" } else { "absent" }.into(),
                access: if self.access && (self.entry || self.active) {
                    "active"
                } else {
                    "inactive"
                }
                .into(),
                blocked: self.blocked,
                legacy: "absent".into(),
                inert: !self.active && !self.pending_visuals,
                ready: if self.ready && self.active {
                    vec!["launcher".into(), "window".into()]
                } else {
                    Vec::new()
                },
            })
        }
        fn rollback(&mut self) -> Result<Rollback> {
            self.op("rollback")?;
            let visuals = self.ready;
            self.active = false;
            self.entry = false;
            self.access = false;
            self.ready = false;
            self.blocked = true;
            if visuals {
                self.pending_visuals = true;
                Ok(Rollback::NeedsRestart)
            } else {
                Ok(Rollback::Done)
            }
        }
        fn restart(&mut self) -> Result<()> {
            self.op("restart")?;
            self.root = false;
            self.pending_visuals = false;
            self.ready = false;
            self.ready_at = None;
            // The startup entry starts the access channel and, when activation is allowed, the agents.
            self.active = self.entry;
            if self.entry && !self.blocked && self.agents_after_restart {
                self.ready_at = Some(self.clock + 40);
            }
            self.root = true;
            Ok(())
        }
        fn install(&mut self) -> Result<String> {
            assert!(
                !self.active,
                "install over an active deployment revokes activation"
            );
            self.op("install")?;
            self.installed = true;
            Ok("installed".into())
        }
        fn runtime(&mut self, reviewed: bool) -> Result<()> {
            self.op("runtime")?;
            if reviewed {
                self.blocked = false;
            }
            Ok(())
        }
        fn access(&mut self) -> Result<()> {
            self.op("access")?;
            self.access = true;
            Ok(())
        }
        fn entry(&mut self) -> Result<()> {
            self.op("entry")?;
            self.entry = true;
            self.active = true;
            Ok(())
        }
        fn probe(&mut self) -> Result<Probe> {
            self.op("probe")?;
            if self.probe_fails {
                self.ready_at = Some(self.clock + 60);
                return Err("Android first-run check failed at 'hooks'.".into());
            }
            self.ready = self.reviewed;
            Ok(if self.reviewed {
                Probe::Ready {
                    mic: true,
                    buttons: true,
                }
            } else {
                Probe::Unreviewed
            })
        }
        fn wait(&mut self, seconds: u64) {
            self.clock += seconds;
        }
    }

    fn drive(fake: &mut Fake) -> (std::result::Result<String, Failure>, Vec<StepState>) {
        let states = RefCell::new(vec![StepState::Pending; STEPS.len()]);
        let step = |index: usize, state: StepState| states.borrow_mut()[index] = state;
        let say = |_: String| {};
        let result = run(
            fake,
            &Reporter {
                lang: Lang::Ru,
                step: &step,
                say: &say,
            },
        );
        let states = states.into_inner();
        (result, states)
    }

    #[test]
    fn fresh_unit_installs_and_confirms_after_one_restart() {
        let mut fake = Fake {
            reviewed: true,
            agents_after_restart: true,
            ..Fake::default()
        };
        let (result, states) = drive(&mut fake);
        let text = result.unwrap();
        assert!(text.starts_with("Готово."), "{text}");
        assert!(text.contains("launcher, window"));
        assert_eq!(fake.log.iter().filter(|op| *op == "restart").count(), 1);
        assert!(!fake.log.contains(&"rollback".to_string()));
        assert_eq!(states[PAUSE], StepState::Skipped);
        assert!(
            states
                .iter()
                .all(|s| matches!(s, StepState::Done | StepState::Skipped))
        );
    }

    #[test]
    fn update_over_running_agents_rolls_back_restarts_and_restarts_again() {
        let mut fake = Fake {
            installed: true,
            active: true,
            entry: true,
            access: true,
            ready: true,
            reviewed: true,
            agents_after_restart: true,
            ..Fake::default()
        };
        let (result, _) = drive(&mut fake);
        result.unwrap();
        let order: Vec<_> = fake
            .log
            .iter()
            .map(String::as_str)
            .filter(|op| {
                matches!(
                    *op,
                    "rollback" | "restart" | "install" | "runtime" | "entry" | "probe"
                )
            })
            .collect();
        assert_eq!(
            order,
            [
                "rollback", "restart", "install", "runtime", "entry", "probe", "restart"
            ]
        );
    }

    #[test]
    fn the_same_running_apk_changes_nothing() {
        let mut fake = Fake {
            installed: true,
            same: true,
            active: true,
            entry: true,
            access: true,
            ready: true,
            reviewed: true,
            ..Fake::default()
        };
        let (result, states) = drive(&mut fake);
        assert!(result.unwrap().contains("ничего не менялось"));
        assert_eq!(fake.log, ["checks", "root"]);
        assert_eq!(states[VERIFY], StepState::Done);
    }

    #[test]
    fn an_early_probe_answer_waits_for_the_agents_instead_of_failing() {
        let mut fake = Fake {
            reviewed: true,
            probe_fails: true,
            agents_after_restart: true,
            ..Fake::default()
        };
        let (result, _) = drive(&mut fake);
        result.unwrap();
        assert_eq!(
            fake.log.iter().filter(|op| *op == "probe").count(),
            1,
            "the probe is not repeated"
        );
    }

    #[test]
    fn agents_that_never_come_up_after_the_restart_fail_the_last_step() {
        let mut fake = Fake {
            reviewed: true,
            agents_after_restart: false,
            ..Fake::default()
        };
        let (result, states) = drive(&mut fake);
        let failure = result.unwrap_err();
        assert_eq!(failure.step, VERIFY);
        assert_eq!(states[VERIFY], StepState::Failed);
        assert!(failure.text(Lang::Ru).contains("«Повторить»"));
        assert!(failure.text(Lang::En).contains("Stopped at step 12"));
    }

    #[test]
    fn unreviewed_firmware_needs_only_the_root_channel_after_the_restart() {
        let mut fake = Fake::default();
        let (result, _) = drive(&mut fake);
        let text = result.unwrap();
        assert!(text.contains("только root-канал"), "{text}");
        assert!(text.contains("нет проверенного профиля"));
    }

    #[test]
    fn a_failure_stops_before_later_steps_and_names_a_safe_state() {
        for (op, step) in [
            ("checks", CHECK),
            ("backup", BACKUP),
            ("install", INSTALL),
            ("entry", ENTRY),
        ] {
            let mut fake = Fake {
                reviewed: true,
                fail_at: Some(op),
                ..Fake::default()
            };
            let (result, states) = drive(&mut fake);
            let failure = result.unwrap_err();
            assert_eq!(failure.step, step, "{op}");
            assert_eq!(fake.log.last().map(String::as_str), Some(op));
            assert_eq!(states[step], StepState::Failed);
            assert!(states[step + 1..].iter().all(|s| *s == StepState::Pending));
            let text = failure.text(Lang::En);
            if step <= REMOUNT {
                assert!(text.contains("Nothing that Controls relies on was changed"));
            } else {
                assert!(text.contains("Retry") && text.contains("Roll back"));
            }
        }
    }
}

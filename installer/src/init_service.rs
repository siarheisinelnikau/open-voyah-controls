//! Read-only inspection of a small, bounded subset of Android init configuration.
//! This establishes a root service definition, not cold-boot activation or SELinux permission.

use crate::adb::{Result, Target};
use base64::{Engine, engine::general_purpose::STANDARD};
use std::{
    collections::{BTreeMap, BTreeSet},
    time::{Duration, Instant},
};

const BOOT_SCRIPT: &str = "/system/etc/init.logcat.sh";
const MAX_FILE_BYTES: usize = 128 * 1024;
const MAX_TOTAL_BYTES: usize = 4 * 1024 * 1024;
const MAX_FILES: usize = 512;
const MAX_NODES: usize = 768;
const MAX_DEPTH: usize = 16;
const MAX_PROPERTIES: usize = 64;
const MAX_LINE_BYTES: usize = 16 * 1024;
const STANDARD_DIRS: &[&str] = &[
    "/system/etc/init",
    "/product/etc/init",
    "/odm/etc/init",
    "/vendor/etc/init",
];

// Each command is read-only. Data stays in memory; neither configuration nor properties are executed.
const READ_NODE: &str = r#"set -eu
path=$1
if [ ! -e "$path" ] && [ ! -L "$path" ]; then printf 'OVC_INIT_UNAVAILABLE\n'; exit 0; fi
if [ -L "$path" ] && [ ! -d "$path" ]; then printf 'OVC_INIT_LINK\n'; exit 0; fi
canonical=$(readlink -f "$path")
[ -n "$canonical" ]
before=$(stat -c '%d:%i:%s:%Y:%Z' "$canonical")
if [ -d "$canonical" ]; then
    printf 'OVC_INIT_DIR\n%s\n%s\n' "$canonical" "$before"
elif [ -f "$canonical" ]; then
    mode=$(stat -c %a "$canonical")
    case "$mode" in ''|*[!0-7]*|*[2367][0-7]|*[0-7][2367]) exit 1;; esac
    size=$(stat -c %s "$canonical")
    case "$size" in ''|*[!0-9]*) exit 1;; esac
    [ "$size" -le 131072 ] || exit 1
    data=$(head -c 131073 "$canonical" | base64)
    printf 'OVC_INIT_FILE\n%s\n%s\n%s\n' "$canonical" "$size" "$data"
else
    exit 1
fi
[ "$(stat -c '%d:%i:%s:%Y:%Z' "$canonical")" = "$before" ]
[ "$(readlink -f "$path")" = "$canonical" ]
"#;

const READ_ANCESTOR_LINK: &str = r#"set -eu
path=$1
if [ ! -L "$path" ]; then printf 'OVC_INIT_NOT_LINK\n'; exit 0; fi
before=$(stat -c '%d:%i:%s:%Y:%Z' "$path")
size=$(stat -c %s "$path")
case "$size" in ''|*[!0-9]*) exit 1;; esac
[ "$size" -gt 0 ] && [ "$size" -le 256 ]
value=$(readlink "$path")
[ "${#value}" -eq "$size" ]
data=$(printf '%s' "$value" | base64)
[ -L "$path" ]
[ "$(stat -c '%d:%i:%s:%Y:%Z' "$path")" = "$before" ]
[ "$(readlink "$path")" = "$value" ]
printf 'OVC_INIT_ANCESTOR_LINK\n%s\n%s\n' "$before" "$data"
"#;

#[derive(Debug, PartialEq)]
pub struct RootService {
    pub name: String,
    pub source: String,
    pub default_user: bool,
}

#[derive(Clone, Debug)]
enum Node {
    Missing,
    SymlinkFile,
    File {
        canonical: String,
        text: String,
    },
    Directory {
        canonical: String,
        children: Vec<String>,
    },
}

trait InitReader {
    fn node(&mut self, path: &str) -> Result<Node>;
    fn property(&mut self, key: &str) -> Result<String>;
}

#[derive(Clone, Debug, PartialEq)]
struct AncestorLink {
    identity: String,
    target: String,
}

trait AbsenceReader {
    fn entries(&mut self, path: &str) -> Result<Vec<String>>;
    fn ancestor_link(&mut self, path: &str) -> Result<Option<AncestorLink>>;
}

struct DeviceReader<'a, 'b> {
    target: &'a Target<'b>,
    started: Instant,
}

impl DeviceReader<'_, '_> {
    fn budget(&self) -> Result<()> {
        if self.started.elapsed() > Duration::from_secs(45) {
            return Err("Init-service inspection exceeded its time limit.".into());
        }
        Ok(())
    }

    fn directory_entries(&self, path: &str) -> Result<Vec<String>> {
        self.budget()?;
        // A successful, untruncated listing proves readability. A failed glob cannot do that.
        let bytes = self
            .target
            .capture(&["shell", "-T", "ls", "-a1", path], 5, MAX_FILE_BYTES)?;
        self.budget()?;
        let text = String::from_utf8(bytes).map_err(|_| "Unsupported init directory encoding.")?;
        if text.contains('\0') {
            return Err("Invalid init directory entry.".into());
        }
        Ok(text.lines().map(str::to_owned).collect())
    }
}

impl AbsenceReader for DeviceReader<'_, '_> {
    fn entries(&mut self, path: &str) -> Result<Vec<String>> {
        self.directory_entries(path)
    }

    fn ancestor_link(&mut self, path: &str) -> Result<Option<AncestorLink>> {
        self.budget()?;
        let response = self.target.script(READ_ANCESTOR_LINK, &[path], 5)?;
        self.budget()?;
        if response == "OVC_INIT_NOT_LINK" {
            return Ok(None);
        }
        let lines: Vec<_> = response.lines().collect();
        if response.len() > 1024
            || lines.len() < 3
            || lines[0] != "OVC_INIT_ANCESTOR_LINK"
            || lines[1].is_empty()
            || !lines[1]
                .bytes()
                .all(|byte| byte.is_ascii_digit() || byte == b':')
        {
            return Err("Invalid init ancestor-link response.".into());
        }
        let bytes = STANDARD
            .decode(lines[2..].join(""))
            .map_err(|_| "Invalid init ancestor-link target.")?;
        let target =
            String::from_utf8(bytes).map_err(|_| "Unsupported init ancestor-link encoding.")?;
        valid_path(&target)?;
        Ok(Some(AncestorLink {
            identity: lines[1].into(),
            target,
        }))
    }
}

fn confirm_missing(reader: &mut impl AbsenceReader, path: &str) -> Result<Node> {
    let mut resolved = path.to_owned();
    let mut links = BTreeMap::<String, AncestorLink>::new();
    // `test -e` also fails on inaccessible ancestors. Only a complete successful parent
    // listing proves absence. A broken ancestor link needs the same proof at its exact
    // allowed absolute target, with its identity checked again before accepting absence.
    'resolve: for _ in 0..=MAX_DEPTH {
        let mut current = resolved.as_str();
        for _ in 0..MAX_DEPTH {
            let (parent, name) = current.rsplit_once('/').ok_or("Invalid init path.")?;
            let parent = if parent.is_empty() { "/" } else { parent };
            match reader.entries(parent) {
                Ok(entries) if !entries.iter().any(|entry| entry == name) => {
                    if !links.is_empty() {
                        for (link_path, expected) in &links {
                            if reader.ancestor_link(link_path)?.as_ref() != Some(expected) {
                                return Err(
                                    "An init ancestor link changed during inspection.".into()
                                );
                            }
                        }
                        if reader.entries(parent)? != entries {
                            return Err("An init absence listing changed during inspection.".into());
                        }
                    }
                    return Ok(Node::Missing);
                }
                Ok(_) => {
                    let Some(link) = reader.ancestor_link(current)? else {
                        return Err(format!(
                            "Init configuration is present but unavailable: {path}."
                        ));
                    };
                    if links.len() >= MAX_DEPTH || links.contains_key(current) {
                        return Err(
                            "Cyclic or excessive init ancestor links are unsupported.".into()
                        );
                    }
                    let expanded = format!("{}{}", link.target, &resolved[current.len()..]);
                    valid_path(&expanded)?;
                    links.insert(current.to_owned(), link);
                    resolved = expanded;
                    continue 'resolve;
                }
                Err(_) if parent != "/" => current = parent,
                Err(_) => break,
            }
        }
        break;
    }
    Err(format!(
        "Could not establish whether init configuration exists: {path}."
    ))
}

impl InitReader for DeviceReader<'_, '_> {
    fn node(&mut self, path: &str) -> Result<Node> {
        self.budget()?;
        let response = self.target.script(READ_NODE, &[path], 5).map_err(|_| {
            format!("Could not read a bounded, stable init configuration at {path}.")
        })?;
        self.budget()?;
        if response == "OVC_INIT_UNAVAILABLE" {
            return confirm_missing(self, path);
        }
        let mut node = decode_node(&response)?;
        if let Node::Directory { children, .. } = &mut node {
            for entry in self.directory_entries(path)? {
                if matches!(entry.as_str(), "." | "..") {
                    continue;
                }
                let child = format!("{path}/{entry}");
                valid_path(&child)?;
                children.push(child);
                if children.len() > MAX_FILES {
                    return Err("Too many init directory entries.".into());
                }
            }
            self.budget()?;
            let after = self.target.script(READ_NODE, &[path], 5)?;
            self.budget()?;
            if after != response {
                return Err("An init directory changed during inspection.".into());
            }
        }
        Ok(node)
    }

    fn property(&mut self, key: &str) -> Result<String> {
        self.budget()?;
        let value = self.target.text(["shell", "getprop", key], 5)?;
        self.budget()?;
        if value.len() > 256 {
            return Err("An init property exceeded the inspection limit.".into());
        }
        Ok(value)
    }
}

pub fn preflight(target: &Target<'_>, sdk: u32) -> Result<RootService> {
    inspect(
        &mut DeviceReader {
            target,
            started: Instant::now(),
        },
        sdk,
    )
}

fn decode_node(response: &str) -> Result<Node> {
    let lines: Vec<_> = response.lines().collect();
    match lines.first().copied() {
        Some("OVC_INIT_LINK") if lines.len() == 1 => Ok(Node::SymlinkFile),
        Some("OVC_INIT_DIR") if lines.len() == 3 => {
            valid_path(lines[1])?;
            if lines[2].is_empty()
                || !lines[2]
                    .bytes()
                    .all(|byte| byte.is_ascii_digit() || byte == b':')
            {
                return Err("Invalid init directory identity.".into());
            }
            Ok(Node::Directory {
                canonical: lines[1].into(),
                children: Vec::new(),
            })
        }
        Some("OVC_INIT_FILE") if lines.len() >= 3 => {
            valid_path(lines[1])?;
            let expected = lines[2]
                .parse::<usize>()
                .map_err(|_| "Invalid init file length.")?;
            if expected > MAX_FILE_BYTES || response.len() > MAX_FILE_BYTES * 2 + 512 {
                return Err("Init file exceeded its inspection limit.".into());
            }
            let bytes = STANDARD
                .decode(lines[3..].join(""))
                .map_err(|_| "Invalid init file response.")?;
            if bytes.len() != expected || bytes.contains(&0) {
                return Err("Init file length or contents changed during inspection.".into());
            }
            let text = String::from_utf8(bytes).map_err(|_| "Unsupported init file encoding.")?;
            Ok(Node::File {
                canonical: lines[1].into(),
                text,
            })
        }
        _ => Err("Invalid init-service inspection response.".into()),
    }
}

#[derive(Debug)]
struct Service {
    name: String,
    source: String,
    target: bool,
    users: Vec<Vec<String>>,
    override_service: bool,
    updatable: bool,
}

#[derive(Default)]
struct Parsed {
    services: Vec<Service>,
    imports: Vec<String>,
    default_mount_imports: bool,
}

struct Inspection<'a, R> {
    reader: &'a mut R,
    visited: BTreeSet<String>,
    stack: BTreeSet<String>,
    properties: BTreeMap<String, String>,
    services: Vec<Service>,
    nodes: usize,
    files: usize,
    bytes: usize,
    sdk: u32,
    needs_standard_dirs: bool,
}

#[derive(Clone, Copy)]
enum ReadScope {
    Config,
    PrimaryFile,
    DirectoryEntry,
}

fn inspect(reader: &mut impl InitReader, sdk: u32) -> Result<RootService> {
    root_service(&inspect_services(reader, sdk)?)
}

fn inspect_services(reader: &mut impl InitReader, sdk: u32) -> Result<Vec<Service>> {
    if !matches!(sdk, 28 | 30) {
        return Err("Init-service inspection supports Android 9 and 11.".into());
    }
    if reader.property("sys.boot_completed")? != "1" {
        return Err("Wait for Android to finish starting before checking its init service.".into());
    }
    let mut check = Inspection {
        reader,
        visited: BTreeSet::new(),
        stack: BTreeSet::new(),
        properties: BTreeMap::new(),
        services: Vec::new(),
        nodes: 0,
        files: 0,
        bytes: 0,
        sdk,
        needs_standard_dirs: false,
    };
    let configured = check.property("ro.boot.init_rc")?;
    if configured.is_empty() {
        // AOSP LoadBootScripts: pie-release/init/init.cpp and android11-release/init/init.cpp.
        check.visit_scoped(
            if sdk == 28 {
                "/init.rc"
            } else {
                "/system/etc/init/hw/init.rc"
            },
            0,
            true,
            ReadScope::PrimaryFile,
        )?;
        check.needs_standard_dirs = true;
    } else {
        valid_path(&configured)?;
        check.visit_scoped(&configured, 0, true, ReadScope::PrimaryFile)?;
        // Android 11 mount-time imports additionally depend on the vendor policy API.
        // Do not infer that API from the platform SDK for a custom primary script.
        if sdk == 30 && check.needs_standard_dirs {
            return Err("A custom Android 11 boot script with mount-time init imports needs separate review.".into());
        }
    }
    // Defer this sweep until primary imports finish: mount_all inside an already loaded
    // directory must not create an artificial recursive import of that same directory.
    if check.needs_standard_dirs {
        check.standard_directories(0)?;
    }
    // Import properties must stay identical throughout this bounded observation.
    for (key, expected) in &check.properties {
        if check.reader.property(key)? != *expected {
            return Err("An init import property changed during inspection.".into());
        }
    }
    Ok(check.services)
}

impl<R: InitReader> Inspection<'_, R> {
    fn property(&mut self, key: &str) -> Result<String> {
        if let Some(value) = self.properties.get(key) {
            return Ok(value.clone());
        }
        if self.properties.len() >= MAX_PROPERTIES
            || !key.starts_with("ro.")
            || !key
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || b"_.-".contains(&byte))
        {
            return Err("Unsupported or excessive init import properties.".into());
        }
        let value = self.reader.property(key)?;
        self.properties.insert(key.into(), value.clone());
        Ok(value)
    }

    fn import_path(&mut self, raw: &str) -> Result<String> {
        let mut expanded = String::new();
        let mut remaining = raw;
        while let Some(index) = remaining.find('$') {
            expanded.push_str(&remaining[..index]);
            let property = remaining[index..]
                .strip_prefix("${")
                .ok_or("Unsupported init import expansion.")?;
            let close = property
                .find('}')
                .ok_or("Unterminated init import expansion.")?;
            let value = self.property(&property[..close])?;
            if value.is_empty()
                || !value
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || b"_.-".contains(&byte))
            {
                return Err("An init import property is unavailable or unsafe.".into());
            }
            expanded.push_str(&value);
            remaining = &property[close + 1..];
        }
        expanded.push_str(remaining);
        valid_path(&expanded)?;
        Ok(expanded)
    }

    fn standard_directories(&mut self, depth: usize) -> Result<()> {
        for directory in STANDARD_DIRS {
            self.visit(directory, depth + 1, false)?;
        }
        if self.sdk == 30 {
            self.visit("/system_ext/etc/init", depth + 1, false)?;
        }
        Ok(())
    }

    fn visit(&mut self, path: &str, depth: usize, required: bool) -> Result<()> {
        self.visit_scoped(path, depth, required, ReadScope::Config)
    }

    fn visit_scoped(
        &mut self,
        path: &str,
        depth: usize,
        required: bool,
        scope: ReadScope,
    ) -> Result<()> {
        valid_path(path)?;
        if depth > MAX_DEPTH || self.nodes >= MAX_NODES {
            return Err("Init imports exceeded the bounded inspection scope.".into());
        }
        self.nodes += 1;
        let node = self.reader.node(path)?;
        match (&node, scope) {
            // AOSP ParseConfigDir reads direct regular files, regardless of extension.
            // It does not recurse into subdirectories or follow their symlink entries.
            (Node::Directory { .. } | Node::SymlinkFile, ReadScope::DirectoryEntry) => {
                return Ok(());
            }
            (Node::Directory { .. } | Node::SymlinkFile, ReadScope::PrimaryFile) => {
                return Err("The primary init configuration is not a direct regular file.".into());
            }
            (Node::SymlinkFile, _) => {
                return Err("Init file imports cannot be symbolic links.".into());
            }
            _ => {}
        }
        let canonical = match &node {
            Node::Missing if !required => return Ok(()),
            Node::Missing => {
                return Err(format!(
                    "Referenced init configuration is unavailable: {path}."
                ));
            }
            Node::File { canonical, .. } | Node::Directory { canonical, .. } => canonical.clone(),
            Node::SymlinkFile => unreachable!(),
        };
        valid_path(&canonical)?;
        if self.stack.contains(&canonical) {
            return Err("Cyclic init imports are unsupported.".into());
        }
        if self.visited.contains(&canonical) {
            return Ok(());
        }
        self.stack.insert(canonical.clone());
        match node {
            Node::File { text, .. } => {
                self.files += 1;
                self.bytes += text.len();
                if self.files > MAX_FILES
                    || text.len() > MAX_FILE_BYTES
                    || self.bytes > MAX_TOTAL_BYTES
                {
                    return Err("Init configuration exceeded the text inspection limits.".into());
                }
                let parsed = parse_file(&canonical, &text)?;
                self.services.extend(parsed.services);
                for import in parsed.imports {
                    let imported = self.import_path(&import)?;
                    // AOSP tolerates absent optional imports. Read failures still propagate;
                    // a device reader returns Missing only after positively proving absence.
                    self.visit(&imported, depth + 1, false)?;
                }
                self.needs_standard_dirs |= parsed.default_mount_imports;
            }
            Node::Directory { children, .. } => {
                if children.len() > MAX_FILES {
                    return Err("Too many init directory entries.".into());
                }
                for child in children {
                    if child.rsplit_once('/').map(|value| value.0)
                        != Some(path.trim_end_matches('/'))
                    {
                        return Err("Unexpected init directory entry.".into());
                    }
                    self.visit_scoped(&child, depth + 1, true, ReadScope::DirectoryEntry)?;
                }
            }
            Node::Missing | Node::SymlinkFile => unreachable!(),
        }
        self.stack.remove(&canonical);
        self.visited.insert(canonical);
        Ok(())
    }
}

fn valid_path(path: &str) -> Result<()> {
    let location = path
        .rsplit_once('/')
        .map(|(parent, _)| parent)
        .unwrap_or("");
    let trusted_location = location.is_empty()
        || [
            "/system/",
            "/system_ext/",
            "/product/",
            "/vendor/",
            "/odm/",
            "/oem/",
        ]
        .iter()
        .any(|prefix| path.starts_with(prefix));
    if path.len() > 256
        || !path.starts_with('/')
        || !trusted_location
        || path
            .split('/')
            .skip(1)
            .any(|part| part.is_empty() || part == "." || part == "..")
        || !path
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"_./-@".contains(&byte))
    {
        return Err(
            if path.len() <= 256
                && path
                    .bytes()
                    .all(|byte| byte.is_ascii_graphic() || byte == b' ')
            {
                format!("Init configuration uses an unsupported path: {path:?}.")
            } else {
                "Init configuration uses an unsupported path (unprintable or too long).".into()
            },
        );
    }
    Ok(())
}

fn parse_file(path: &str, text: &str) -> Result<Parsed> {
    let mut parsed = Parsed::default();
    let mut current: Option<Service> = None;
    let mut pending = String::new();
    let mut logical = LogicalLine::default();
    for (line, physical) in text.lines().enumerate() {
        if physical.len() > MAX_LINE_BYTES || pending.len() + physical.len() > MAX_LINE_BYTES {
            return Err(format!(
                "Init line exceeds the inspection limit at {path}:{}.",
                line + 1
            ));
        }
        if logical.scan(physical) {
            let prefix = physical
                .strip_suffix('\\')
                .ok_or("Invalid init continuation.")?;
            if !prefix.ends_with([' ', '\t']) {
                return Err("Unsupported init token continuation.".into());
            }
            pending.push_str(prefix);
            pending.push(' ');
            continue;
        }
        pending.push_str(physical);
        if logical.quoted {
            pending.push('\n');
            if pending.len() > MAX_LINE_BYTES {
                return Err("Quoted init payload exceeds the inspection limit.".into());
            }
            continue;
        }
        logical = LogicalLine::default();
        let first = pending
            .trim_start_matches([' ', '\t', '\r'])
            .split([' ', '\t', '\r'])
            .next()
            .unwrap_or_default();
        if first.is_empty() || first.starts_with('#') {
            pending.clear();
            continue;
        }
        // Do not interpret unrelated action arguments (which may contain arbitrary write
        // payloads or property defaults). Keep section keywords literal so escaping cannot
        // conceal an import, duplicate service or effective user from this inspection.
        if first.contains(['\\', '"']) {
            return Err(format!("Unsupported init keyword at {path}:{}.", line + 1));
        }
        let relevant = matches!(
            first,
            "service" | "import" | "user" | "override" | "updatable" | "mount_all"
        );
        let data_command = matches!(first, "write" | "setprop" | "export" | "setenv");
        if !relevant && (data_command || !pending.contains(BOOT_SCRIPT)) {
            if first == "on" {
                if let Some(service) = current.take() {
                    parsed.services.push(service);
                }
            }
            pending.clear();
            continue;
        }
        let tokens = tokenize(&pending)
            .map_err(|_| format!("Unsupported init syntax at {path}:{}.", line + 1))?;
        pending.clear();
        let Some(keyword) = tokens.first().map(String::as_str) else {
            continue;
        };
        if keyword != "service" && tokens.iter().any(|token| token.contains(BOOT_SCRIPT)) {
            return Err(
                "The logcat script appears outside a supported direct service invocation.".into(),
            );
        }
        match keyword {
            "service" => {
                if let Some(service) = current.take() {
                    parsed.services.push(service);
                }
                if tokens.len() < 3
                    || tokens[1].is_empty()
                    || !tokens[1]
                        .bytes()
                        .all(|byte| byte.is_ascii_alphanumeric() || b"_.-".contains(&byte))
                {
                    return Err("Unsupported init service declaration.".into());
                }
                let command = &tokens[2..];
                let target = command == [BOOT_SCRIPT] || command == ["/system/bin/sh", BOOT_SCRIPT];
                if !target && command.iter().any(|token| token.contains(BOOT_SCRIPT)) {
                    return Err(
                        "The logcat service command needs a separately reviewed invocation.".into(),
                    );
                }
                current = Some(Service {
                    name: tokens[1].clone(),
                    source: format!("{path}:{}", line + 1),
                    target,
                    users: Vec::new(),
                    override_service: false,
                    updatable: false,
                });
            }
            "on" | "import" => {
                if let Some(service) = current.take() {
                    parsed.services.push(service);
                }
                if keyword == "import" {
                    if tokens.len() != 2 {
                        return Err("Unsupported init import declaration.".into());
                    }
                    parsed.imports.push(tokens[1].clone());
                }
            }
            "user" => {
                if let Some(service) = current.as_mut() {
                    service.users.push(tokens[1..].to_vec());
                }
            }
            "override" => {
                if let Some(service) = current.as_mut() {
                    service.override_service = true;
                }
            }
            "updatable" => {
                if let Some(service) = current.as_mut() {
                    service.updatable = true;
                }
            }
            "mount_all" => {
                // Android 9 permits additional init paths after the fstab. Do not silently miss overrides.
                if tokens
                    .iter()
                    .skip(2)
                    .any(|token| token != "--early" && token != "--late")
                {
                    return Err("Custom mount-time init imports need separate review.".into());
                }
                if !tokens.iter().any(|token| token == "--late") {
                    parsed.default_mount_imports = true;
                }
            }
            _ => {}
        }
    }
    if !pending.is_empty() {
        return Err("Unterminated init quote or continuation.".into());
    }
    if let Some(service) = current {
        parsed.services.push(service);
    }
    Ok(parsed)
}

#[derive(Default)]
struct LogicalLine {
    quoted: bool,
    started: bool,
}

impl LogicalLine {
    // Quotes may span physical lines. Escaped quotes and quotes inside comments must not
    // change that state, or payload text can be mistaken for a new init section or option.
    // Returns true only for an active final backslash, never one inside a comment.
    fn scan(&mut self, physical: &str) -> bool {
        let mut escaped = false;
        for character in physical.chars() {
            if escaped {
                escaped = false;
                self.started = true;
                continue;
            }
            match character {
                '#' if !self.quoted && !self.started => return false,
                '\\' => escaped = true,
                '"' => {
                    self.quoted = !self.quoted;
                    self.started = true;
                }
                ' ' | '\t' | '\r' if !self.quoted => self.started = false,
                _ => self.started = true,
            }
        }
        escaped
    }
}

fn tokenize(line: &str) -> Result<Vec<String>> {
    let mut tokens = Vec::new();
    let mut word = String::new();
    let mut quoted = false;
    let mut started = false;
    let mut characters = line.chars();
    while let Some(character) = characters.next() {
        match character {
            '#' if !quoted && !started => break,
            '"' => {
                quoted = !quoted;
                started = true;
            }
            '\\' => match characters.next() {
                Some(next @ ('\\' | '"' | ' ' | '\t')) => {
                    word.push(next);
                    started = true;
                }
                Some('n') => {
                    word.push('\n');
                    started = true;
                }
                Some('r') => {
                    word.push('\r');
                    started = true;
                }
                Some('t') => {
                    word.push('\t');
                    started = true;
                }
                _ => return Err("Unsupported init escape.".into()),
            },
            ' ' | '\t' | '\r' if !quoted => {
                if started {
                    tokens.push(std::mem::take(&mut word));
                    started = false;
                }
            }
            '\n' | '\t' | '\r' if quoted => {
                word.push(character);
                started = true;
            }
            character if character.is_control() => {
                return Err("Unsupported init control character.".into());
            }
            character => {
                word.push(character);
                started = true;
            }
        }
    }
    if quoted {
        return Err("Unterminated init quote.".into());
    }
    if started {
        tokens.push(word);
    }
    if tokens.len() > 256 {
        return Err("Too many init tokens.".into());
    }
    Ok(tokens)
}

fn root_service(services: &[Service]) -> Result<RootService> {
    let matching: Vec<_> = services.iter().filter(|service| service.target).collect();
    if matching.len() != 1 {
        return Err("Expected one direct init service for the logcat startup script.".into());
    }
    let service = matching[0];
    if services
        .iter()
        .filter(|other| other.name == service.name)
        .count()
        != 1
        || service.override_service
        || service.updatable
    {
        return Err(
            "The logcat init service has an ambiguous duplicate or override definition.".into(),
        );
    }
    // Both Android 9 and 11 AOSP init READMEs document omitted `user` as root.
    // https://android.googlesource.com/platform/system/core/+/refs/heads/android11-release/init/README.md
    if service.users.len() > 1
        || service
            .users
            .first()
            .is_some_and(|user| user.len() != 1 || !matches!(user[0].as_str(), "root" | "0"))
    {
        return Err("The logcat init service does not have one unambiguous root user.".into());
    }
    Ok(RootService {
        name: service.name.clone(),
        source: service.source.clone(),
        default_user: service.users.is_empty(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Default)]
    struct AbsenceFixture {
        listings: BTreeMap<String, Vec<String>>,
        links: BTreeMap<String, AncestorLink>,
        listing_calls: BTreeMap<String, usize>,
        link_calls: BTreeMap<String, usize>,
        changed_listing: Option<String>,
        changed_link: Option<String>,
        unreadable_link: Option<String>,
    }

    impl AbsenceFixture {
        fn broken_optional_directory() -> Self {
            let mut fixture = Self::default();
            fixture.listings.insert("/odm".into(), vec!["etc".into()]);
            fixture
                .listings
                .insert("/vendor".into(), vec!["bin".into(), "etc".into()]);
            fixture.links.insert(
                "/odm/etc".into(),
                AncestorLink {
                    identity: "1:2:15:4:5".into(),
                    target: "/vendor/odm/etc".into(),
                },
            );
            fixture
        }
    }

    impl AbsenceReader for AbsenceFixture {
        fn entries(&mut self, path: &str) -> Result<Vec<String>> {
            let calls = self.listing_calls.entry(path.into()).or_default();
            *calls += 1;
            let mut entries = self
                .listings
                .get(path)
                .cloned()
                .ok_or("Directory is unreadable or unavailable.")?;
            if self.changed_listing.as_deref() == Some(path) && *calls > 1 {
                entries.push("odm".into());
            }
            Ok(entries)
        }
        fn ancestor_link(&mut self, path: &str) -> Result<Option<AncestorLink>> {
            if self.unreadable_link.as_deref() == Some(path) {
                return Err("Link is unreadable.".into());
            }
            let calls = self.link_calls.entry(path.into()).or_default();
            *calls += 1;
            let mut link = self.links.get(path).cloned();
            if self.changed_link.as_deref() == Some(path) && *calls > 1 {
                if let Some(link) = link.as_mut() {
                    link.identity = "different".into();
                }
            }
            Ok(link)
        }
    }

    #[test]
    fn a_broken_optional_ancestor_requires_positive_absence_at_its_exact_target() {
        let mut fixture = AbsenceFixture::broken_optional_directory();
        assert!(matches!(
            confirm_missing(&mut fixture, "/odm/etc/init").unwrap(),
            Node::Missing
        ));
        assert!(fixture.listing_calls.contains_key("/vendor/odm/etc"));
        assert_eq!(fixture.listing_calls["/vendor"], 2);
        assert_eq!(fixture.link_calls["/odm/etc"], 2);
    }

    #[test]
    fn present_or_unreadable_ancestors_do_not_become_optional_imports() {
        let mut inaccessible = AbsenceFixture::broken_optional_directory();
        inaccessible.links.clear();
        assert!(confirm_missing(&mut inaccessible, "/odm/etc/init").is_err());
        let mut unreadable_link = AbsenceFixture::broken_optional_directory();
        unreadable_link.unreadable_link = Some("/odm/etc".into());
        assert!(confirm_missing(&mut unreadable_link, "/odm/etc/init").is_err());
        let mut inaccessible_target = AbsenceFixture::broken_optional_directory();
        inaccessible_target
            .listings
            .get_mut("/vendor")
            .unwrap()
            .push("odm".into());
        assert!(confirm_missing(&mut inaccessible_target, "/odm/etc/init").is_err());
    }

    #[test]
    fn changed_link_identity_or_absence_listing_invalidates_the_proof() {
        let mut changed_link = AbsenceFixture::broken_optional_directory();
        changed_link.changed_link = Some("/odm/etc".into());
        assert!(confirm_missing(&mut changed_link, "/odm/etc/init").is_err());
        let mut changed_listing = AbsenceFixture::broken_optional_directory();
        changed_listing.changed_listing = Some("/vendor".into());
        assert!(confirm_missing(&mut changed_listing, "/odm/etc/init").is_err());
    }

    #[test]
    fn ancestor_link_cycles_and_untrusted_or_relative_targets_fail_closed() {
        let mut cycle = AbsenceFixture::broken_optional_directory();
        cycle
            .listings
            .get_mut("/vendor")
            .unwrap()
            .push("odm".into());
        cycle.links.insert(
            "/vendor/odm".into(),
            AncestorLink {
                identity: "1:3:4:5:6".into(),
                target: "/odm".into(),
            },
        );
        assert!(confirm_missing(&mut cycle, "/odm/etc/init").is_err());
        for target in [
            "/data/local/tmp",
            "../vendor/odm/etc",
            "/vendor/../data",
            "/vendor/odm/etc\n",
        ] {
            let mut fixture = AbsenceFixture::broken_optional_directory();
            fixture.links.get_mut("/odm/etc").unwrap().target = target.into();
            assert!(confirm_missing(&mut fixture, "/odm/etc/init").is_err());
        }
    }

    #[test]
    fn ancestor_link_resolution_has_a_fixed_hop_limit() {
        let mut fixture = AbsenceFixture::default();
        fixture.listings.insert(
            "/vendor".into(),
            (0..=MAX_DEPTH)
                .map(|index| format!("link{index}"))
                .collect(),
        );
        for index in 0..=MAX_DEPTH {
            fixture.links.insert(
                format!("/vendor/link{index}"),
                AncestorLink {
                    identity: format!("{index}"),
                    target: format!("/vendor/link{}", index + 1),
                },
            );
        }
        assert!(
            confirm_missing(&mut fixture, "/vendor/link0/init")
                .unwrap_err()
                .contains("excessive")
        );
    }

    #[derive(Default)]
    struct Fixture {
        nodes: BTreeMap<String, Node>,
        properties: BTreeMap<String, String>,
        unreadable: BTreeSet<String>,
    }

    impl Fixture {
        fn file(&mut self, path: &str, text: &str) {
            self.nodes.insert(
                path.into(),
                Node::File {
                    canonical: path.into(),
                    text: text.into(),
                },
            );
        }
        fn base(sdk: u32, text: &str) -> Self {
            let mut fixture = Self::default();
            fixture
                .properties
                .insert("sys.boot_completed".into(), "1".into());
            fixture.file(
                if sdk == 28 {
                    "/init.rc"
                } else {
                    "/system/etc/init/hw/init.rc"
                },
                text,
            );
            fixture
        }
    }

    impl InitReader for Fixture {
        fn node(&mut self, path: &str) -> Result<Node> {
            if self.unreadable.contains(path) {
                return Err("Read failed.".into());
            }
            Ok(self.nodes.get(path).cloned().unwrap_or(Node::Missing))
        }
        fn property(&mut self, key: &str) -> Result<String> {
            Ok(self.properties.get(key).cloned().unwrap_or_default())
        }
    }

    #[test]
    fn direct_root_services_and_documented_default_are_recognized_on_both_sdks() {
        for sdk in [28, 30] {
            for (command, user) in [
                (BOOT_SCRIPT.to_string(), "    user root\n"),
                (format!("/system/bin/sh {BOOT_SCRIPT}"), "    user 0\n"),
                (format!("/system/bin/sh {BOOT_SCRIPT}"), ""),
            ] {
                let text = format!(
                    "service cockpit_logs {command}\n{user}    class main\n    seclabel u:r:example:s0\n"
                );
                let proof = inspect(&mut Fixture::base(sdk, &text), sdk).unwrap();
                assert_eq!(proof.name, "cockpit_logs");
                assert_eq!(proof.default_user, user.is_empty());
            }
        }
    }

    #[test]
    fn comments_data_and_non_root_services_do_not_pass_the_preflight() {
        for text in [
            format!("# service example /system/bin/sh {BOOT_SCRIPT}\n# user root\n"),
            format!("service example /system/bin/other\n    user root\n# {BOOT_SCRIPT}\n"),
            format!("service example /system/bin/sh {BOOT_SCRIPT}\n    user shell\n"),
            format!("service example /system/bin/sh {BOOT_SCRIPT}\n    user 2000\n"),
            format!("on boot\n    exec -- /system/bin/sh {BOOT_SCRIPT}\n"),
        ] {
            assert!(
                inspect(&mut Fixture::base(28, &text), 28).is_err(),
                "{text}"
            );
        }
    }

    #[test]
    fn wrappers_extra_arguments_and_ambiguous_users_are_rejected() {
        for command in [
            format!("/system/bin/sh -c \"{BOOT_SCRIPT}\""),
            format!("/system/bin/sh {BOOT_SCRIPT} extra"),
            format!("/system/bin/env sh {BOOT_SCRIPT}"),
        ] {
            assert!(
                inspect(
                    &mut Fixture::base(28, &format!("service logs {command}\n user root\n")),
                    28
                )
                .is_err()
            );
        }
        for options in [
            "user root\n user shell",
            "user root\n user root",
            "user",
            "user ${ro.user}",
            "override",
            "updatable",
        ] {
            assert!(
                inspect(
                    &mut Fixture::base(28, &format!("service logs {BOOT_SCRIPT}\n {options}\n")),
                    28
                )
                .is_err()
            );
        }
    }

    #[test]
    fn real_read_only_properties_resolve_hardware_and_zygote_imports() {
        let mut fixture = Fixture::base(
            28,
            "import /init.${ro.hardware}.rc\nimport /init.${ro.zygote}.rc\n",
        );
        fixture
            .properties
            .insert("ro.hardware".into(), "example".into());
        fixture
            .properties
            .insert("ro.zygote".into(), "zygote64_32".into());
        fixture.file(
            "/init.example.rc",
            &format!("service cockpit_logs /system/bin/sh {BOOT_SCRIPT}\n user root\n"),
        );
        fixture.file(
            "/init.zygote64_32.rc",
            "service zygote /system/bin/app_process64\n user root\n",
        );
        assert_eq!(inspect(&mut fixture, 28).unwrap().name, "cockpit_logs");
    }

    #[test]
    fn versioned_android_service_filenames_are_inspected_on_both_sdks() {
        for sdk in [28, 30] {
            for path in [
                "/system/etc/init/android.hidl.example@1.0-service.rc",
                "/vendor/etc/init/android.hardware.example@2.1-service.rc",
            ] {
                let mut fixture = Fixture::base(sdk, "");
                let directory = path.rsplit_once('/').unwrap().0;
                fixture.nodes.insert(
                    directory.into(),
                    Node::Directory {
                        canonical: directory.into(),
                        children: vec![path.into()],
                    },
                );
                fixture.file(path, &format!("service logs {BOOT_SCRIPT}\n user root\n"));
                let proof = inspect(&mut fixture, sdk).unwrap();
                assert_eq!(proof.source, format!("{path}:1"));
            }
        }
    }

    #[test]
    fn unsupported_path_errors_identify_only_bounded_printable_values() {
        let path = "/vendor/etc/init/unsupported$name.rc";
        assert!(valid_path(path).unwrap_err().contains(path));
        for path in [
            "/vendor/etc/init/name\nline.rc".into(),
            format!("/vendor/etc/init/{}", "a".repeat(257)),
        ] {
            let error = valid_path(&path).unwrap_err();
            assert!(!error.contains('\n'));
            assert!(error.len() < 256);
        }
    }

    #[test]
    fn unresolved_unsafe_and_recursive_imports_fail_closed() {
        for import in [
            "/init.${ro.missing}.rc",
            "/init.${persist.example}.rc",
            "/init.${ro.hardware:-fallback}.rc",
            "/data/local/tmp/config.rc",
        ] {
            assert!(
                inspect(
                    &mut Fixture::base(
                        28,
                        &format!("import {import}\nservice logs {BOOT_SCRIPT}\n")
                    ),
                    28
                )
                .is_err()
            );
        }
        let mut recursive = Fixture::base(28, "import /init.loop.rc\n");
        recursive.file("/init.loop.rc", "import /init.rc\n");
        assert!(inspect(&mut recursive, 28).is_err());
    }

    #[test]
    fn positively_missing_optional_imports_pass_but_unreadable_imports_do_not() {
        let mut fixture = Fixture::base(
            28,
            &format!(
                "import /init.absent.rc\nimport /init.${{ro.hardware}}.usb.rc\nservice logs {BOOT_SCRIPT}\n user root\n"
            ),
        );
        fixture
            .properties
            .insert("ro.hardware".into(), "example".into());
        assert!(inspect(&mut fixture, 28).is_ok());
        fixture.unreadable.insert("/init.example.usb.rc".into());
        assert!(inspect(&mut fixture, 28).is_err());
    }

    #[test]
    fn unrelated_action_payloads_and_property_defaults_are_not_interpreted() {
        let text = format!(
            r#"on boot
    write /proc/example "line\nnext\x01"
    setprop example.value ${{example.missing:-fallback}}
service logs {BOOT_SCRIPT}
    user root
on property:example.ready=1
    write /proc/example "value\q"
"#
        );
        assert!(inspect(&mut Fixture::base(28, &text), 28).is_ok());
        for declaration in [
            format!("service \"\" {BOOT_SCRIPT}\n user root\n"),
            format!("s\\ervice logs {BOOT_SCRIPT}\n user root\n"),
            format!("service logs {BOOT_SCRIPT}\n u\\ser shell\n"),
        ] {
            assert!(inspect(&mut Fixture::base(28, &declaration), 28).is_err());
        }
    }

    #[test]
    fn multiline_quoted_write_payloads_cannot_define_services_users_or_imports() {
        let payload = format!(
            r###"on fs
    write /dev/event-log-tags "# logger-owned data
service fabricated {BOOT_SCRIPT}
    user root
import /data/local/tmp/ignored.rc
mount_all /vendor/etc/fstab.example /data/local/tmp/ignored.rc
"
    chown logd logd /dev/event-log-tags
"###
        );
        let parsed = parse_file("/init.fixture.rc", &payload).unwrap();
        assert!(parsed.services.is_empty());
        assert!(parsed.imports.is_empty());
        assert!(!parsed.default_mount_imports);
        assert!(
            inspect(&mut Fixture::base(28, &payload), 28)
                .unwrap_err()
                .contains("Expected one direct init service")
        );
        let text = format!("{payload}service logs {BOOT_SCRIPT}\n user root\n");
        assert_eq!(
            inspect(&mut Fixture::base(28, &text), 28).unwrap().name,
            "logs"
        );
    }

    #[test]
    fn escaped_quotes_and_comment_quotes_do_not_expose_payload_lines() {
        let text = format!(
            r###"# ignored quote " and trailing slash \
on fs
    write /dev/example "an escaped quote: \"
service fabricated {BOOT_SCRIPT}
"
service logs {BOOT_SCRIPT}
    user root
"###
        );
        let parsed = parse_file("/init.fixture.rc", &text).unwrap();
        assert_eq!(parsed.services.len(), 1);
        assert_eq!(parsed.services[0].name, "logs");
        assert!(inspect(&mut Fixture::base(28, &text), 28).is_ok());
        assert_eq!(
            tokenize("write /dev/example \"first\nsecond\"").unwrap()[2],
            "first\nsecond"
        );
    }

    #[test]
    fn a_quoted_service_option_cannot_change_the_effective_user_or_section() {
        let text = format!(
            r###"service logs {BOOT_SCRIPT}
    user shell
    setenv LOG_PAYLOAD "data
user root
on boot
service fabricated {BOOT_SCRIPT}
"
"###
        );
        let parsed = parse_file("/init.fixture.rc", &text).unwrap();
        assert_eq!(parsed.services.len(), 1);
        assert_eq!(parsed.services[0].users, vec![vec!["shell"]]);
        assert!(
            inspect(&mut Fixture::base(28, &text), 28)
                .unwrap_err()
                .contains("root user")
        );
    }

    #[test]
    fn unterminated_and_oversized_multiline_quotes_fail_closed() {
        for payload in [
            "on fs\n write /dev/example \"open\nservice fake /system/bin/other\n".into(),
            format!(
                "on fs\n write /dev/example \"{}\n{}\n\"\n",
                "a".repeat(MAX_LINE_BYTES / 2),
                "b".repeat(MAX_LINE_BYTES / 2)
            ),
        ] {
            assert!(parse_file("/init.fixture.rc", &payload).is_err());
        }
    }

    #[test]
    fn mount_time_default_imports_do_not_create_artificial_directory_cycles() {
        let mut fixture = Fixture::base(28, "on fs\n    mount_all /vendor/etc/fstab.example\n");
        fixture.nodes.insert(
            "/vendor/etc/init".into(),
            Node::Directory {
                canonical: "/vendor/etc/init".into(),
                children: vec!["/vendor/etc/init/logs.rc".into()],
            },
        );
        fixture.file("/vendor/etc/init/logs.rc", &format!(
            "service logs {BOOT_SCRIPT}\n user root\non fs\n mount_all /vendor/etc/fstab.example --early\n"
        ));
        assert!(inspect(&mut fixture, 28).is_ok());
    }

    #[test]
    fn duplicates_and_overrides_in_other_loaded_files_are_not_ignored() {
        let mut fixture = Fixture::base(
            28,
            &format!(
                "service cockpit_logs {BOOT_SCRIPT}\n user root\nimport /vendor/etc/init/other.rc\n"
            ),
        );
        fixture.file(
            "/vendor/etc/init/other.rc",
            "service cockpit_logs /system/bin/other\n user shell\n override\n",
        );
        assert!(inspect(&mut fixture, 28).is_err());
        fixture.file(
            "/vendor/etc/init/other.rc",
            &format!("service other_logs {BOOT_SCRIPT}\n user root\n"),
        );
        assert!(inspect(&mut fixture, 28).is_err());
    }

    #[test]
    fn auto_loaded_directories_and_literal_directory_imports_are_bounded() {
        let mut fixture = Fixture::base(30, "on boot\n    setprop example.ready 1\n");
        fixture.nodes.insert(
            "/vendor/etc/init".into(),
            Node::Directory {
                canonical: "/vendor/etc/init".into(),
                children: vec!["/vendor/etc/init/logs.rc".into()],
            },
        );
        fixture.file(
            "/vendor/etc/init/logs.rc",
            &format!("service cockpit_logs {BOOT_SCRIPT}\n user root\n"),
        );
        assert!(inspect(&mut fixture, 30).is_ok());
        fixture.nodes.insert(
            "/vendor/etc/init".into(),
            Node::Directory {
                canonical: "/vendor/etc/init".into(),
                children: vec!["/other/location.rc".into()],
            },
        );
        assert!(inspect(&mut fixture, 30).is_err());
    }

    #[test]
    fn directory_scans_skip_subdirectories_and_symlinks_but_read_non_rc_regular_files() {
        let mut fixture = Fixture::base(28, &format!("service logs {BOOT_SCRIPT}\n user root\n"));
        fixture.nodes.insert(
            "/vendor/etc/init".into(),
            Node::Directory {
                canonical: "/vendor/etc/init".into(),
                children: vec![
                    "/vendor/etc/init/nested.rc".into(),
                    "/vendor/etc/init/link.rc".into(),
                    "/vendor/etc/init/extra".into(),
                ],
            },
        );
        fixture.nodes.insert(
            "/vendor/etc/init/nested.rc".into(),
            Node::Directory {
                canonical: "/vendor/etc/init/nested.rc".into(),
                children: vec!["/vendor/etc/init/nested.rc/ignored.rc".into()],
            },
        );
        fixture.file(
            "/vendor/etc/init/nested.rc/ignored.rc",
            &format!("service nested {BOOT_SCRIPT}\n user shell\n"),
        );
        fixture
            .nodes
            .insert("/vendor/etc/init/link.rc".into(), Node::SymlinkFile);
        fixture.file(
            "/vendor/etc/init/extra",
            "# A regular file without a suffix is still parsed.\n",
        );
        assert!(inspect(&mut fixture, 28).is_ok());
        fixture.file(
            "/vendor/etc/init/extra",
            "service logs /system/bin/other\n user shell\n override\n",
        );
        assert!(inspect(&mut fixture, 28).is_err());
    }

    #[test]
    fn a_directory_named_rc_cannot_supply_a_root_service() {
        let mut fixture = Fixture::base(28, "");
        fixture.nodes.insert(
            "/vendor/etc/init".into(),
            Node::Directory {
                canonical: "/vendor/etc/init".into(),
                children: vec!["/vendor/etc/init/nested.rc".into()],
            },
        );
        fixture.nodes.insert(
            "/vendor/etc/init/nested.rc".into(),
            Node::Directory {
                canonical: "/vendor/etc/init/nested.rc".into(),
                children: vec!["/vendor/etc/init/nested.rc/ignored.rc".into()],
            },
        );
        fixture.file(
            "/vendor/etc/init/nested.rc/ignored.rc",
            &format!("service logs {BOOT_SCRIPT}\n user root\n"),
        );
        assert!(inspect(&mut fixture, 28).is_err());
    }

    #[test]
    fn primary_files_and_explicit_file_imports_cannot_be_symlinks_or_directories() {
        let mut fixture = Fixture::base(28, "import /init.link.rc\n");
        fixture
            .nodes
            .insert("/init.link.rc".into(), Node::SymlinkFile);
        assert!(inspect(&mut fixture, 28).is_err());
        fixture.nodes.insert(
            "/init.rc".into(),
            Node::Directory {
                canonical: "/init.rc".into(),
                children: vec!["/init.rc/logs.rc".into()],
            },
        );
        fixture.file(
            "/init.rc/logs.rc",
            &format!("service logs {BOOT_SCRIPT}\n user root\n"),
        );
        assert!(inspect(&mut fixture, 28).is_err());
    }

    #[test]
    fn custom_primary_mount_imports_respect_pie_early_and_late_and_android_11_ambiguity() {
        for (sdk, option, succeeds) in [
            (28, "--early", true),
            (28, "--late", false),
            (30, "--early", false),
        ] {
            let mut fixture = Fixture::base(sdk, "");
            fixture
                .properties
                .insert("ro.boot.init_rc".into(), "/init.custom.rc".into());
            fixture.file(
                "/init.custom.rc",
                &format!("on fs\n mount_all /vendor/etc/fstab.example {option}\n"),
            );
            fixture.nodes.insert(
                "/vendor/etc/init".into(),
                Node::Directory {
                    canonical: "/vendor/etc/init".into(),
                    children: vec!["/vendor/etc/init/logs.rc".into()],
                },
            );
            fixture.file(
                "/vendor/etc/init/logs.rc",
                &format!("service logs {BOOT_SCRIPT}\n user root\n"),
            );
            assert_eq!(
                inspect(&mut fixture, sdk).is_ok(),
                succeeds,
                "SDK {sdk} {option}"
            );
        }
    }

    #[test]
    fn an_explicit_boot_script_does_not_invent_auto_loaded_services() {
        let mut fixture = Fixture::base(28, "");
        fixture
            .properties
            .insert("ro.boot.init_rc".into(), "/init.custom.rc".into());
        fixture.file("/init.custom.rc", "on boot\n    setprop example.ready 1\n");
        fixture.nodes.insert(
            "/vendor/etc/init".into(),
            Node::Directory {
                canonical: "/vendor/etc/init".into(),
                children: vec!["/vendor/etc/init/logs.rc".into()],
            },
        );
        fixture.file(
            "/vendor/etc/init/logs.rc",
            &format!("service logs {BOOT_SCRIPT}\n"),
        );
        assert!(inspect(&mut fixture, 28).is_err());
    }

    #[test]
    fn quotes_and_safe_continuations_preserve_a_literal_service_invocation() {
        let text = format!(
            "service logs /system/bin/sh \\\n    \"{BOOT_SCRIPT}\"\n    user \"root\" # explanation\n"
        );
        assert!(inspect(&mut Fixture::base(28, &text), 28).is_ok());
        assert!(
            inspect(
                &mut Fixture::base(28, "service logs /system/bin/sh \\\n"),
                28
            )
            .is_err()
        );
    }

    #[test]
    fn malformed_or_oversized_snapshots_and_text_are_rejected() {
        for response in [
            "",
            "OVC_INIT_MISSING",
            "OVC_INIT_UNAVAILABLE",
            "OVC_INIT_FILE\n/init.rc\n3\nYQ==",
            "OVC_INIT_FILE\n/init.rc\n131073\n",
            "OVC_INIT_DIR\n/data/local/tmp",
        ] {
            assert!(decode_node(response).is_err());
        }
        let text = format!(
            "service logs {BOOT_SCRIPT}\n{}",
            "#".repeat(MAX_LINE_BYTES + 1)
        );
        assert!(inspect(&mut Fixture::base(28, &text), 28).is_err());
        let mut starting = Fixture::base(28, &format!("service logs {BOOT_SCRIPT}\n"));
        starting
            .properties
            .insert("sys.boot_completed".into(), "0".into());
        assert!(inspect(&mut starting, 28).is_err());
    }

    #[test]
    #[ignore = "Requires explicit OVC_INIT_ADB and OVC_INIT_SERIAL for an already-running owned emulator; read-only."]
    fn owned_emulator_init_snapshot_is_read_only() {
        use crate::adb::Adb;
        use std::{path::PathBuf, sync::atomic::AtomicBool};

        let adb = Adb::from_path(PathBuf::from(
            std::env::var_os("OVC_INIT_ADB")
                .expect("Set OVC_INIT_ADB to the complete SDK adb.exe path."),
        ))
        .unwrap();
        let serial = std::env::var("OVC_INIT_SERIAL")
            .expect("Set OVC_INIT_SERIAL to the owned emulator serial.");
        let stop = AtomicBool::new(false);
        let devices: Vec<_> = adb
            .devices(&stop)
            .unwrap()
            .into_iter()
            .filter(|device| device.serial == serial && device.ready())
            .collect();
        assert_eq!(
            devices.len(),
            1,
            "Select exactly one authorized, already-running emulator."
        );
        let target = adb.target(&devices[0], &stop).unwrap();
        assert_eq!(
            target.property("ro.kernel.qemu").unwrap(),
            "1",
            "This check is limited to an owned emulator."
        );
        assert_eq!(target.property("ro.debuggable").unwrap(), "1");
        let sdk = target
            .property("ro.build.version.sdk")
            .unwrap()
            .parse()
            .unwrap();
        let mut reader = DeviceReader {
            target: &target,
            started: Instant::now(),
        };
        let mut services = inspect_services(&mut reader, sdk).unwrap();
        let observed = services.len();
        let fixture = !services.iter().any(|service| service.target);
        if fixture {
            // Stock images have no target service. Add only an in-memory authored definition;
            // the actual primary file, imports and directory contents have already been read.
            services.extend(
                parse_file(
                    "/init.ovc-read-only-fixture.rc",
                    &format!("service ovc_read_only_fixture {BOOT_SCRIPT}\n user root\n"),
                )
                .unwrap()
                .services,
            );
        }
        let proof = root_service(&services).unwrap();
        eprintln!(
            "Read-only init snapshot: Android SDK {sdk}, {observed} observed service definitions, in-memory fixture={fixture}, root service={}; elapsed {:?}.",
            proof.name,
            reader.started.elapsed()
        );
    }
}

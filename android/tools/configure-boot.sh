#!/system/bin/sh
# Explicit boot configuration and recovery over an already authorized root shell.
set -eu
export PATH=/system/bin:/system/xbin
umask 077
mode=${1:-}
fingerprint=${2:-}
serial=${3:-}
exported_sha=${4:-}
boot=/system/etc/init.logcat.sh
private=/data/adb/open-voyah-controls
legacy=/data/local/tmp/open-voyah-controls
private_state=/data/adb/open-voyah-controls-boot
legacy_state=/data/local/tmp/open-voyah-controls-boot
runtime=open-voyah-controls-frida-17.18.0
legacy_injector=/data/local/tmp/$runtime
runtime_sha=d8ce6fe18db97594d1c7a43be4f8fb405fc76f6a01470df34504bca6461328d0
app=org.openvoyah.controls
channels='launcher locale-setting locale-vehicle locale-bluetooth window navigation'
keys="$app.panel $app.voice $app.localization $app.clusterMedia $app.windowProfiles $app.windowRequest"
marker_v1='# OPEN_VOYAH_CONTROLS_BOOT_V1'
marker_v2='# OPEN_VOYAH_CONTROLS_BOOT_V2'

fail() { printf '%s\n' "$*" >&2; exit 1; }
digest() { sha256sum "$1" | awk '{print $1}'; }
is_sha() { [ "${#1}" = 64 ] && case "$1" in *[!0-9a-f]*) false;; *) true;; esac; }
numeric() { case "$1" in ''|*[!0-9]*) return 1;; esac; }
valid_boot_id() {
    case "$1" in *[!a-f0-9-]*) return 1;; ????????-????-????-????-????????????) return 0;; *) return 1;; esac
}
exists() { [ -e "$1" ] || [ -L "$1" ]; }
private_dir() { [ -d "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %u:%a "$1")" = 0:700 ]; }
safe_file() { [ -f "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %u "$1")" = 0 ]; }
context_of() { ls -Zd "$1" | awk '{print $1}'; }
attrs_of() { stat -c '%u:%g:%a' "$1"; }
start_of() {
    line=$(cat "/proc/$1/stat" 2>/dev/null) || return 1
    rest=${line##*) }
    printf '%s\n' "$rest" | awk 'NF>=20 && $1!="Z" && $1!="X" {print $20}'
}

case "$mode" in
    status|backup|restore|rollback|rearm) [ "$#" = 3 ] || fail 'Provide the exact build fingerprint and device serial.' ;;
    install) [ "$#" = 4 ] || fail 'Provide the exported backup digest, build fingerprint and device serial.' ;;
    restore-exported)
        [ "$#" = 7 ] || fail 'Provide same-device backup metadata and the exported original.'
        exported_context=$5; exported_owner=$6; exported_mode=$7
        case "$exported_context" in u:object_r:*) ;; *) fail 'Invalid exported security context.';; esac
        case "$exported_owner" in 0:*) numeric "${exported_owner#*:}" || fail 'Invalid exported owner.';; *) fail 'Invalid exported owner.';; esac
        case "$exported_mode" in [0-7][0-7][0-7]|[0-7][0-7][0-7][0-7]) ;; *) fail 'Invalid exported mode.';; esac
        is_sha "$exported_sha" && command -v ovc_external_original >/dev/null || fail 'The verified exported bytes are required.' ;;
    *) fail 'Choose status, backup, install, restore, rollback, rearm, or restore-exported.' ;;
esac
[ "$(id -u)" = 0 ] || fail 'An authorized root shell is required.'
case "$fingerprint" in ''|*[!A-Za-z0-9_.:/+-]*) fail 'Invalid expected fingerprint.';; esac
case "$serial" in ''|unknown|*[!A-Za-z0-9_.:-]*) fail 'Invalid expected device serial.';; esac
[ "$(getprop ro.build.fingerprint)" = "$fingerprint" ] || fail 'Build fingerprint does not match.'
[ "$(getprop ro.serialno)" = "$serial" ] || fail 'Device serial does not match.'
sdk=$(getprop ro.build.version.sdk)
numeric "$sdk" || fail 'Android SDK could not be determined.'
boot_id=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null || true)
valid_boot_id "$boot_id" || fail 'Kernel boot identity could not be verified.'
if [ "$mode" = install ]; then
    case "$sdk" in 28|30) ;; *) fail 'Boot installation supports Android 9 and 11.';; esac
    [ "$(getprop ro.product.cpu.abi)" = arm64-v8a ] || fail 'The prepared runtime requires Android ARM64.'
    [ "$(getenforce)" = Enforcing ] || fail 'Boot installation requires SELinux Enforcing.'
    is_sha "$exported_sha" || fail 'Export and verify the original on the PC before installing the entry.'
fi

# Prefer an existing verified-generation backup. A legacy backup is only used for recovery.
state=$private_state
if exists "$legacy_state/original.sha256" && ! exists "$private_state/original.sha256"; then state=$legacy_state; fi
journal=$private_state

boot_entry() {
    if ! exists "$boot"; then
        if load_backup; then echo unknown; else echo absent; fi
    elif ! safe_file "$boot"; then echo unknown
    elif grep -Fxq "$marker_v1" "$boot" || grep -Fxq "$marker_v2" "$boot"; then echo present
    else echo absent
    fi
}
load_backup() {
    private_dir "$state" && safe_file "$state/original" &&
        safe_file "$state/original.sha256" && safe_file "$state/fingerprint" && safe_file "$state/context" || return 1
    [ "$(cat "$state/fingerprint")" = "$fingerprint" ] || return 1
    if [ "$state" = "$private_state" ] || exists "$state/serialno"; then
        safe_file "$state/serialno" && [ "$(cat "$state/serialno")" = "$serial" ] || return 1
    fi
    original=$(cat "$state/original.sha256") || return 1
    is_sha "$original" && [ "$(digest "$state/original")" = "$original" ] || return 1
    context=$(cat "$state/context") || return 1
    case "$context" in u:object_r:*) ;; *) return 1;; esac
    attrs=$(attrs_of "$state/original") || return 1
    if [ "$state" = "$private_state" ]; then
        safe_file "$state/attributes" && [ "$(cat "$state/attributes")" = "$attrs" ] || return 1
    fi
    installed=
    if exists "$state/installed.sha256"; then
        safe_file "$state/installed.sha256" || return 1
        installed=$(cat "$state/installed.sha256") && is_sha "$installed" || return 1
    fi
}
backup_result() {
    if ! exists "$state"; then echo missing
    elif load_backup; then echo verified
    elif private_dir "$state" && ! exists "$state/original.sha256"; then echo missing
    else echo invalid
    fi
}
worker_dir() {
    if [ "$2" = launcher ]; then printf '%s\n' "$1"; else printf '%s/%s\n' "$1" "$2"; fi
}
scopes_safe() {
    if exists "$private" && { ! private_dir /data/adb || ! private_dir "$private"; }; then return 1; fi
    if exists "$legacy" && ! private_dir "$legacy"; then return 1; fi
    for scope in "$private" "$legacy"; do
        for item in $channels; do
            dir=$(worker_dir "$scope" "$item")
            if exists "$dir" && ! private_dir "$dir"; then
                # Old optional directories can be root-owned with mode 755 under the private base.
                [ "$scope" = "$legacy" ] && [ -d "$dir" ] && [ ! -L "$dir" ] &&
                    [ "$(stat -c %u:%a "$dir")" = 0:755 ] || return 1
            fi
        done
    done
}
classify() {
    p=$1
    numeric "$p" && [ "$p" -gt 1 ] || return 1
    args=$(tr '\000' ' ' < "/proc/$p/cmdline" 2>/dev/null) || return 1
    case "$args" in
        '/system/bin/sh /data/adb/open-voyah-controls/supervise.sh '*|\
        '/system/bin/sh /data/local/tmp/open-voyah-controls/supervise.sh '*|\
        "/data/adb/open-voyah-controls/$runtime -p "*|"/data/local/tmp/$runtime -p "*) ;;
        *) return 1;;
    esac
    # A matching command line without a verifiable identity is not safe to signal.
    if [ "$(stat -c %u "/proc/$p" 2>/dev/null || true)" != 0 ] ||
        ! tick=$(start_of "$p") || ! numeric "$tick" ||
        ! binary=$(readlink "/proc/$p/exe" 2>/dev/null); then
        if [ -d "/proc/$p" ]; then printf 'unverified - - %s -\n' "$p"; fi
        return 0
    fi
    for scope in "$private" "$legacy"; do
        inject="$scope/$runtime"
        if [ "$scope" = "$legacy" ]; then inject="/data/local/tmp/$runtime"; fi
        for item in $channels; do
            dir=$(worker_dir "$scope" "$item")
            suffix=
            case "$item" in launcher) ;; locale-*) suffix=" ${item#locale-}";; *) suffix=" $item";; esac
            if [ "$args" = "/system/bin/sh $scope/supervise.sh$suffix " ]; then
                case "$binary" in /system/bin/mksh|/system/bin/sh) printf 'worker %s %s %s %s\n' "$scope" "$item" "$p" "$tick"; return 0;; esac
            fi
            if [ "$binary" = "$inject" ] || [ "$binary" = "$inject (deleted)" ]; then
                case "$args" in
                    "$inject -p "*" -s $dir/launcher-buttons.js ")
                        target=${args#"$inject -p "}; target=${target%% *}
                        if numeric "$target" && [ "$target" -gt 1 ] &&
                            [ "$args" = "$inject -p $target -s $dir/launcher-buttons.js " ]; then
                            printf 'injector %s %s %s %s\n' "$scope" "$item" "$p" "$tick"
                            return 0
                        fi ;;
                esac
            fi
        done
    done
    printf 'unverified - - %s -\n' "$p"
}
owned_processes() {
    candidates=$(grep -aF -l -e "$private" -e "$legacy" -e "/data/local/tmp/$runtime" /proc/[0-9]*/cmdline 2>/dev/null || true)
    for proc in $candidates; do
        entry=${proc%/cmdline}
        classify "${entry##*/}" || true
    done
    # Preserve a degraded status if a recorded current-boot process is hidden by policy.
    for scope in "$private" "$legacy"; do
        for item in $channels; do
            dir=$(worker_dir "$scope" "$item")
            safe_file "$dir/worker.boot" && [ "$(cat "$dir/worker.boot")" = "$boot_id" ] || continue
            for pidfile in "$dir/worker.pid" "$dir/child.pid"; do
                safe_file "$pidfile" || continue
                recorded=$(cat "$pidfile" 2>/dev/null || true)
                if numeric "$recorded" && [ -d "/proc/$recorded" ] &&
                    ! tr '\000' ' ' < "/proc/$recorded/cmdline" >/dev/null 2>&1; then
                    printf 'unverified - - %s -\n' "$recorded"
                fi
            done
        done
    done
}
# Scope identity probes to this project and never emit the values of Android settings.
setting_names() {
    current=$(settings list global 2>/dev/null) || return 1
    [ -n "$current" ] || return 1
    for key in $keys; do
        if printf '%s\n' "$current" | grep -q "^$(printf '%s' "$key" | sed 's/\./\\./g')="; then printf '%s\n' "$key"; fi
    done
}
active_markers() {
    for scope in "$private" "$legacy"; do
        for item in $channels; do
            dir=$(worker_dir "$scope" "$item")
            if exists "$dir/enabled"; then printf '%s\n' "$dir/enabled"; fi
        done
    done
}
pending_visuals() {
    for file in "$private_state/visual.pending" "$legacy_state/visual.pending" \
        "$private/recovery.visual-pending" "$legacy/recovery.visual-pending"; do
        if exists "$file"; then
            private_dir "${file%/*}" && safe_file "$file" || return 0
            old=$(cat "$file" 2>/dev/null) || return 0
            valid_boot_id "$old" || return 0
            [ "$old" != "$boot_id" ] || return 0
        fi
    done
    return 1
}
ready_channels() {
    list=$1
    only_scope=${2:-}
    ready=
    now=$(date +%s)
    for item in $channels; do
        for scope in "$private" "$legacy"; do
            if [ -n "$only_scope" ] && [ "$scope" != "$only_scope" ]; then continue; fi
            dir=$(worker_dir "$scope" "$item")
            if safe_file "$dir/ready" &&
                printf '%s\n' "$list" | grep -Fq "worker $scope $item " &&
                printf '%s\n' "$list" | grep -Fq "injector $scope $item "; then
                if [ "$item" = launcher ] && ! grep -q '^OVC_READY NavigationBar click+hold ' "$dir/ready"; then
                    continue
                fi
                updated=$(stat -c %Y "$dir/ready" 2>/dev/null || true)
                if numeric "$now" && numeric "$updated" && [ "$now" -ge "$updated" ] &&
                    [ $((now-updated)) -le 35 ]; then
                    ready="${ready:+$ready,}$item"
                    break
                fi
            fi
        done
    done
    printf '%s\n' "$ready"
}
legacy_state_value() {
    if safe_file "$boot" && grep -Fxq "$marker_v1" "$boot"; then echo active; return; fi
    if exists "$legacy_injector"; then
        if [ -f "$legacy_injector" ] && [ ! -L "$legacy_injector" ]; then echo active; else echo unknown; fi
        return
    fi
    if ! exists "$legacy"; then echo absent; return; fi
    if ! private_dir "$legacy"; then echo unknown; return; fi
    for file in "$legacy/boot-start.sh" "$legacy/supervise.sh" "$legacy/localization.js"; do
        if exists "$file"; then
            if safe_file "$file"; then echo active; else echo unknown; fi
            return
        fi
    done
    for item in $channels; do
        dir=$(worker_dir "$legacy" "$item")
        if exists "$dir/enabled" || exists "$dir/launcher-buttons.js"; then echo active; return; fi
    done
    echo inert
}
report_status() {
    entry=$(boot_entry)
    printf 'OVC_BOOT_ENTRY=%s\n' "$entry"
    if safe_file "$boot"; then printf 'OVC_BOOT_SHA256=%s\n' "$(digest "$boot")"; fi
    printf 'OVC_BOOT_BACKUP=%s\n' "$(backup_result)"
    inert=no
    if ! scopes_safe; then
        printf '%s\n' OVC_RUNTIME=unknown OVC_LEGACY_STATE=unknown OVC_RECOVERY_BLOCKED=unknown OVC_ROLLBACK_INERT=no
        return
    fi
    blocked=no
    if exists "$private/recovery.blocked" || exists "$legacy/recovery.blocked"; then blocked=yes; fi
    printf 'OVC_RECOVERY_BLOCKED=%s\n' "$blocked"
    legacy_status=$(legacy_state_value)
    printf 'OVC_LEGACY_STATE=%s\n' "$legacy_status"
    processes=$(owned_processes)
    markers=$(active_markers)
    settings_ok=yes
    names=$(setting_names) || settings_ok=no
    printf 'OVC_READY_CHANNELS=%s\n' "$(ready_channels "$processes")"
    if [ "$settings_ok" = no ] || printf '%s\n' "$processes" | grep -q '^unverified '; then echo 'OVC_RUNTIME=unknown'
    elif [ -n "$processes$markers$names" ]; then echo 'OVC_RUNTIME=active'
    else
        echo 'OVC_RUNTIME=inactive'
        if [ "$entry" = absent ] && ! pending_visuals; then
            case "$legacy_status" in absent|inert) inert=yes;; esac
        fi
    fi
    printf 'OVC_ROLLBACK_INERT=%s\n' "$inert"
}

if [ "$mode" = status ]; then report_status; exit 0; fi

ensure_dir() {
    if ! exists "$1"; then mkdir "$1" || return 1; fi
    private_dir "$1"
}
private_dir /data/adb && ensure_dir "$journal" || fail 'Root-owned private recovery state is unavailable.'
lock="$journal/recovery.owner"
self_start=$(start_of "$$")
numeric "$self_start" || fail 'Recovery process identity is unavailable.'
owner="$boot_id:$$:$self_start"
gate=
stage=
import_dir=
saved_state=$state
claim=$(mktemp "$journal/recovery.claim.XXXXXX") || fail 'Could not stage recovery lock.'
printf '%s\n' "$owner" > "$claim"
read_owner() { safe_file "$1" && cat "$1"; }
cleanup() {
    if [ -n "$stage" ]; then rm -f "$stage"; fi
    if [ -n "$import_dir" ]; then
        for file in original original.sha256 fingerprint serialno context attributes installed.sha256 candidate; do
            rm -f "$import_dir/$file"
        done
        rmdir "$import_dir" 2>/dev/null || true
    fi
    if [ "$(read_owner "$lock" 2>/dev/null || true)" = "$owner" ]; then rm -f "$lock"; fi
    rm -f "$claim"
    if [ -n "$gate" ]; then rmdir "$gate" 2>/dev/null || true; fi
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
[ ! -d "$lock" ] && [ ! -L "$lock" ] || fail 'Recovery lock has an unexpected type.'
if ! ln "$claim" "$lock" 2>/dev/null; then
    prior=$(read_owner "$lock" 2>/dev/null) || fail 'Recovery lock has an unexpected type.'
    case "$prior" in ''|*[!a-f0-9:-]*) fail 'Recovery lock identity is invalid.';; esac
    prior_boot=${prior%%:*}; prior_pid=${prior#*:}; prior_pid=${prior_pid%%:*}; prior_start=${prior##*:}
    numeric "$prior_pid" && numeric "$prior_start" || fail 'Recovery lock identity is invalid.'
    if [ "$prior_boot" = "$boot_id" ] && [ "$(start_of "$prior_pid" || true)" = "$prior_start" ]; then
        fail 'Another recovery operation is active.'
    fi
    gate_name=$(printf '%s' "$prior" | tr ':' '-')
    reclaim="$journal/recovery-reclaim-$gate_name-$boot_id"
    mkdir "$reclaim" 2>/dev/null || fail 'Another process is recovering the stale lock.'
    gate=$reclaim
    [ "$(read_owner "$lock")" = "$prior" ] || fail 'Recovery lock changed. Retry the operation.'
    rm -f "$lock"
    ln "$claim" "$lock" 2>/dev/null || fail 'Another recovery operation started.'
    rmdir "$gate"
    gate=
fi

save_text() {
    path=$(mktemp "$1.XXXXXX") || return 1
    printf '%s\n' "$2" > "$path" && mv -f "$path" "$1"
}
export_backup() {
    safe_file "$boot" || fail 'The OEM boot file is missing, not root-owned, or linked.'
    current=$(digest "$boot")
    if ! exists "$state/original.sha256"; then
        [ "$state" = "$private_state" ] && private_dir /data/adb &&
            ensure_dir "$state" || fail 'A root-owned private backup directory is required.'
        [ "$(boot_entry)" = absent ] || fail 'The boot entry exists without a verifiable original.'
        read -r shebang < "$boot"
        [ "$shebang" = '#!/system/bin/sh' ] || fail 'The OEM boot file does not have the expected shell header.'
        [ "$(wc -c < "$boot")" -le 262144 ] || fail 'The boot file is too large to export.'
        context=$(context_of "$boot")
        case "$context" in u:object_r:*) ;; *) fail 'The OEM security label could not be validated.';; esac
        attrs=$(attrs_of "$boot")
        stage=$(mktemp "$state/original.XXXXXX")
        cp -p "$boot" "$stage"
        [ "$(digest "$stage")" = "$current" ] && [ "$(attrs_of "$stage")" = "$attrs" ] &&
            [ "$(digest "$boot")" = "$current" ] || fail 'The OEM boot file changed while backing up.'
        save_text "$state/fingerprint" "$fingerprint"
        save_text "$state/serialno" "$serial"
        save_text "$state/context" "$context"
        save_text "$state/attributes" "$attrs"
        mv -f "$stage" "$state/original"
        stage=
        sync
        save_text "$state/original.sha256" "$current"
        sync
    fi
    load_backup || fail 'The saved boot backup does not match this device.'
    [ "$current" = "$original" ] || [ "$current" = "$installed" ] ||
        fail 'The current boot file does not match the saved generation.'
    printf 'OVC_BACKUP_DIR=%s\nOVC_BACKUP_SHA256=%s\nOVC_BACKUP_FINGERPRINT=%s\nOVC_BACKUP_SERIAL=%s\nOVC_BACKUP_CONTEXT=%s\nOVC_BACKUP_OWNER=%s\nOVC_BACKUP_MODE=%s\n' \
        "$state" "$original" "$fingerprint" "$serial" "$context" "${attrs%:*}" "${attrs##*:}"
}
reviewed_boot_worker() {
    private_dir /data/adb && private_dir "$private" && [ "$(getenforce)" = Enforcing ] || return 1
    for file in boot-start.sh trust.tsv; do safe_file "$private/$file" || return 1; done
    [ "$(stat -c %a "$private/trust.tsv")" = 600 ] &&
        [ "$(stat -c %a "$private/boot-start.sh")" = 700 ] || return 1
    expected=$(awk -F '\t' '$1=="artifact" && $2=="boot-start.sh" { n++; hash=$3 } END { if(n!=1) exit 1; print hash }' "$private/trust.tsv") || return 1
    is_sha "$expected" && [ "$(digest "$private/boot-start.sh")" = "$expected" ] &&
        /system/bin/sh "$private/boot-start.sh" --verify
}
render_boot_entry() {
    # Keep V2 rendering stable: verified PC restores reproduce these exact bytes.
    head -n 1 "$state/original"
    printf '%s\n' "$marker_v2"
    cat <<'OVC_BOOT_ENTRY'
(
    PATH=/system/bin:/system/xbin
    export PATH
    b=/data/adb/open-voyah-controls
    t="$b/trust.tsv"
    w="$b/boot-start.sh"
    waits=0
    while { [ ! -e "$t" ] || [ ! -e "$w" ]; } && [ "$waits" -lt 40 ]; do
        sleep 2
        waits=$((waits+1))
    done
    if [ "$(id -u)" = 0 ] && [ "$(getenforce)" = Enforcing ] &&
        [ -d /data/adb ] && [ ! -L /data/adb ] && [ "$(stat -c %u:%a /data/adb)" = 0:700 ] &&
        [ -d "$b" ] && [ ! -L "$b" ] && [ "$(stat -c %u:%a "$b")" = 0:700 ] &&
        [ -f "$t" ] && [ ! -L "$t" ] && [ "$(stat -c %u:%a "$t")" = 0:600 ] &&
        [ -f "$w" ] && [ ! -L "$w" ] && [ "$(stat -c %u:%a "$w")" = 0:700 ] &&
        [ ! -e "$b/recovery.blocked" ] && [ ! -L "$b/recovery.blocked" ]; then
        expected=$(awk -F '\t' '$1=="artifact" && $2=="boot-start.sh" { n++; hash=$3 } END { if(n!=1) exit 1; print hash }' "$t")
        [ "${#expected}" = 64 ] && [ "$expected" = "$(sha256sum "$w" | awk '{print $1}')" ] &&
            /system/bin/sh "$w"
    fi
) </dev/null >/dev/null 2>&1 &
OVC_BOOT_ENTRY
    tail -n +2 "$state/original"
}
replace_boot() {
    direction=$1
    entry=$(boot_entry)
    if [ "$direction" = restore ] && [ "$entry" = absent ]; then
        echo 'OVC_BOOT_RESTORE=entry-absent'
        return 0
    fi
    [ "$entry" != unknown ] && safe_file "$boot" || return 1
    load_backup || return 1
    current=$(digest "$boot")
    [ "$(context_of "$boot")" = "$context" ] && [ "$(attrs_of "$boot")" = "$attrs" ] || return 1
    if [ "$direction" = install ]; then
        [ "$original" = "$exported_sha" ] || return 1
        reviewed_boot_worker && ! exists "$private/recovery.blocked" || return 1
        case ",$(ready_channels "$(owned_processes)" "$private")," in *,launcher,*) ;; *) return 1;; esac
        if [ "$entry" = present ] && [ "$current" = "$installed" ] && grep -Fxq "$marker_v2" "$boot"; then
            echo 'OVC_BOOT_INSTALL=already-installed'
            return 0
        fi
        [ "$state" = "$private_state" ] && [ "$entry" = absent ] && [ "$current" = "$original" ] || return 1
    else
        [ "$entry" = present ] && [ -n "$installed" ] && [ "$current" = "$installed" ] || return 1
    fi
    stage=$(mktemp /system/etc/init.logcat.ovc.XXXXXX) || return 1
    cp -p "$state/original" "$stage" || return 1
    if [ "$direction" = install ]; then render_boot_entry > "$stage" || return 1; fi
    /system/bin/sh -n "$stage" && chcon "$context" "$stage" || return 1
    [ "$(attrs_of "$stage")" = "$attrs" ] && [ "$(context_of "$stage")" = "$context" ] || return 1
    expected=$(digest "$stage")
    is_sha "$expected" || return 1
    if [ "$direction" = install ]; then save_text "$state/installed.sha256" "$expected" || return 1
    else [ "$expected" = "$original" ] || return 1
    fi
    sync
    [ "$(digest "$boot")" = "$current" ] && [ "$(attrs_of "$boot")" = "$attrs" ] &&
        [ "$(context_of "$boot")" = "$context" ] && mv -f "$stage" "$boot" || return 1
    stage=
    sync
    [ "$(digest "$boot")" = "$expected" ] && [ "$(attrs_of "$boot")" = "$attrs" ] &&
        [ "$(context_of "$boot")" = "$context" ] || return 1
    if [ "$direction" = install ]; then echo 'OVC_BOOT_INSTALL=installed'
    else echo 'OVC_BOOT_RESTORE=restored'
    fi
}
prepare_exported_restore() {
    private_dir /data/adb && [ "$journal" = "$private_state" ] && safe_file "$boot" || return 1
    [ "$(context_of "$boot")" = "$exported_context" ] &&
        [ "$(attrs_of "$boot")" = "$exported_owner:$exported_mode" ] || return 1
    import_dir=$(mktemp -d "$journal/exported.XXXXXX") || return 1
    state=$import_dir
    ovc_external_original > "$state/original" || return 1
    [ "$(wc -c < "$state/original")" -le 262144 ] && [ "$(digest "$state/original")" = "$exported_sha" ] ||
        return 1
    read -r shebang < "$state/original" || return 1
    [ "$shebang" = '#!/system/bin/sh' ] && ! grep -Fxq "$marker_v1" "$state/original" &&
        ! grep -Fxq "$marker_v2" "$state/original" || return 1
    chown "$exported_owner" "$state/original" && chmod "$exported_mode" "$state/original" || return 1
    [ "$(attrs_of "$state/original")" = "$exported_owner:$exported_mode" ] || return 1
    printf '%s\n' "$exported_sha" > "$state/original.sha256"
    printf '%s\n' "$fingerprint" > "$state/fingerprint"
    printf '%s\n' "$serial" > "$state/serialno"
    printf '%s\n' "$exported_context" > "$state/context"
    printf '%s\n' "$exported_owner:$exported_mode" > "$state/attributes"
    # Treat the temporary root-private directory as a fully checked current-generation backup.
    current=$(digest "$boot")
    if [ "$current" = "$exported_sha" ]; then return 0; fi
    grep -Fxq "$marker_v2" "$boot" && render_boot_entry > "$state/candidate" &&
        [ "$(digest "$state/candidate")" = "$current" ] || return 1
    printf '%s\n' "$current" > "$state/installed.sha256"
}
token() {
    value=$(tr -d '-' < /proc/sys/kernel/random/uuid 2>/dev/null) || return 1
    [ "${#value}" = 32 ] && case "$value" in *[!a-f0-9]*) false;; *) printf '%s\n' "$value";; esac
}
release_panel() {
    scope=$1
    safe_file "$scope/hook.log" || return 1
    request=$(token) || return 1
    settings put global "$app.panel" "{\"schema\":1,\"enabled\":false,\"steeringEnabled\":false,\"restoreIcons\":\"$request\"}" || return 1
    for delay in 1 2 3 4 5 6; do
        if grep -Fq "OVC_PANEL_ICONS_RESTORED $request" "$scope/hook.log"; then return 0; fi
        sleep 1
    done
    return 1
}
release_windows() {
    scope=$1
    safe_file "$scope/window/hook.log" || return 1
    request=$(token) || return 1
    user=$(am get-current-user 2>/dev/null) && numeric "$user" || return 1
    now=$(date +%s) && numeric "$now" || return 1
    expires=$((now*1000+8000))
    settings put global "$app.windowRequest" "{\"schema\":1,\"id\":\"$request\",\"operation\":\"release\",\"user\":$user,\"component\":\"\",\"expires\":$expires}" || return 1
    for delay in 1 2 3 4 5 6; do
        if grep -F "OVC_WINDOW_RESULT $request " "$scope/window/hook.log" | grep -Fq '"status":"applied"'; then return 0; fi
        sleep 1
    done
    return 1
}
stop_owned() {
    # Every signal is re-checked against uid, executable, exact arguments, and kernel start time.
    list=$(owned_processes)
    if [ -n "$list" ]; then
        printf '%s\n' "$list" | while IFS=' ' read -r role scope item p tick; do
            case "$role" in worker|injector) ;; *) continue;; esac
            current=$(classify "$p" || true)
            if [ "$current" = "$role $scope $item $p $tick" ]; then kill -TERM "$p" 2>/dev/null || true; fi
        done
    fi
    for delay in 1 2 3 4 5; do
        [ -z "$(owned_processes)" ] && return 0
        sleep 1
    done
    [ -z "$(owned_processes)" ]
}
enter_legacy() {
    [ ! -L "$legacy" ] && cd -P "$legacy" &&
        [ "$(pwd -P)" = "$legacy" ] && private_dir .
}
enter_legacy_channel() {
    [ ! -L "$1" ] && cd -P "$1" && [ "$(pwd -P)" = "$legacy/$1" ] &&
        case "$(stat -c %u:%a .)" in 0:700|0:755) true;; *) false;; esac
}
clear_legacy_markers() (
    if ! exists "$legacy"; then return 0; fi
    enter_legacy || return 1
    if exists ./enabled; then rm -f ./enabled || return 1; fi
    for item in locale-setting locale-vehicle locale-bluetooth window navigation; do
        if exists "./$item"; then
            ( enter_legacy_channel "$item" && rm -f ./enabled ) || return 1
        fi
    done
)
retire_legacy() {
    if exists "$legacy"; then
        (
            enter_legacy || return 1
            for file in ./boot-start.sh ./supervise.sh ./localization.js ./launcher-buttons.js; do
                if exists "$file"; then safe_file "$file" && rm -f "$file" || return 1; fi
            done
            for item in locale-setting locale-vehicle locale-bluetooth window navigation; do
                if exists "./$item"; then
                    (
                        enter_legacy_channel "$item" || return 1
                        if exists ./launcher-buttons.js; then
                            safe_file ./launcher-buttons.js && rm -f ./launcher-buttons.js || return 1
                        fi
                    ) || return 1
                fi
            done
        ) || return 1
    fi
    if exists "$legacy_injector"; then
        [ -f "$legacy_injector" ] && [ ! -L "$legacy_injector" ] &&
            [ "$(digest "$legacy_injector")" = "$runtime_sha" ] && rm -f "$legacy_injector" || return 1
    fi
}
rollback() {
    scopes_safe || fail 'An unexpected runtime directory prevents safe rollback.'
    if private_dir /data/adb; then
        ensure_dir "$private" || fail 'Could not secure the runtime block.'
        save_text "$private/recovery.blocked" "$boot_id"
    fi
    sync
    errors=0
    am force-stop --user all "$app" >/dev/null 2>&1 || errors=1
    # Remove a pending command before letting a live agent perform its one-time restoration.
    settings delete global "$app.voice" >/dev/null 2>&1 || errors=1
    processes=$(owned_processes)
    signals=$(active_markers)
    names=$(setting_names) || errors=1
    visuals=not-active
    already_pending=no
    if pending_visuals; then already_pending=yes; fi
    legacy_touched=no
    if safe_file "$legacy/worker.boot" && [ "$(cat "$legacy/worker.boot")" = "$boot_id" ]; then legacy_touched=yes; fi
    if printf '%s\n' "$processes" | grep -Fq " $legacy "; then legacy_touched=yes; fi
    if [ "$already_pending" = yes ] || [ "$legacy_touched" = yes ] || [ -n "$processes$signals$names" ]; then
        save_text "$journal/visual.pending" "$boot_id"
        sync
        visuals=acknowledged
        [ "$already_pending$legacy_touched" = nono ] || visuals=reboot-required
        any_visual_agent=no
        for scope in "$private" "$legacy"; do
            if printf '%s\n' "$processes" | grep -Fq "injector $scope launcher "; then
                any_visual_agent=yes
                release_panel "$scope" || visuals=reboot-required
            elif exists "$scope/enabled" || printf '%s\n' "$names" | grep -Fxq "$app.panel"; then
                if private_dir "$scope"; then visuals=reboot-required; fi
            fi
            if printf '%s\n' "$processes" | grep -Fq "injector $scope window "; then
                any_visual_agent=yes
                release_windows "$scope" || visuals=reboot-required
            elif exists "$scope/window/enabled"; then visuals=reboot-required
            fi
            for item in locale-setting locale-vehicle locale-bluetooth navigation; do
                if printf '%s\n' "$processes" | grep -Fq "injector $scope $item "; then visuals=reboot-required; fi
            done
        done
        if [ "$any_visual_agent" = no ]; then visuals=reboot-required; fi
    fi
    for item in $channels; do
        dir=$(worker_dir "$private" "$item")
        if exists "$dir/enabled"; then rm -f "$dir/enabled" || errors=1; fi
    done
    clear_legacy_markers || errors=1
    sync
    if stop_owned; then retire_legacy || errors=1; else errors=1; fi
    for key in $keys; do settings delete global "$key" >/dev/null 2>&1 || errors=1; done
    replace_boot restore || { errors=1; echo 'OVC_BOOT_RESTORE=unconfirmed'; }
    state=$saved_state
    if [ "$visuals" = acknowledged ] && [ "$errors" = 0 ]; then
        rm -f "$journal/visual.pending"
    fi
    sync
    printf 'OVC_ROLLBACK_VISUALS=%s\n' "$visuals"
    report_status
    [ "$errors" = 0 ] && [ "$inert" = yes ] || fail 'Recovery is incomplete. Review boot, runtime and visual state before removing the APK.'
}

case "$mode" in
    backup) export_backup ;;
    install) replace_boot install || fail 'Installation refused. Verify the exported original, firmware, and staged runtime.' ;;
    restore) replace_boot restore || fail 'Restoration refused. The original or installed boot file could not be verified.' ;;
    rollback) rollback ;;
    restore-exported)
        prepare_exported_restore || fail 'The exported original does not reproduce this exact V2 boot file. No boot changes were made.'
        rollback
        ;;
    rearm)
        report_status
        [ "$inert" = yes ] && private_dir /data/adb && ensure_dir "$private" ||
            fail 'Rearm requires an inert runtime and root-private deployment directory.'
        if exists "$private/recovery.blocked"; then rm -f "$private/recovery.blocked"; fi
        if safe_file "$private/recovery.visual-pending"; then rm -f "$private/recovery.visual-pending"; fi
        for item in $channels; do
            dir=$(worker_dir "$private" "$item")
            if private_dir "$dir"; then rm -f "$dir/failure.boot" "$dir/failure.count"; fi
        done
        # Legacy activation remains retired. Only a new reviewed activation can start a worker.
        sync
        echo 'OVC_REARM=allowed'
        ;;
esac

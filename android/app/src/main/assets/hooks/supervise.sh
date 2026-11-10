#!/system/bin/sh
# One bounded supervisor for each independently enabled target.
export PATH=/system/bin:/system/xbin
umask 077
root=/data/adb/open-voyah-controls
runtime=open-voyah-controls-frida-17.18.0
runtime_sha=d8ce6fe18db97594d1c7a43be4f8fb405fc76f6a01470df34504bca6461328d0
injector="$root/$runtime"
fixture=0
if [ "${OVC_EMULATOR_FIXTURE:-}" = 1 ]; then
    [ "$(getprop ro.kernel.qemu)" = 1 ] && [ "$(getprop ro.debuggable)" = 1 ] || exit 1
    root=/data/local/tmp/open-voyah-controls
    injector="/data/local/tmp/$runtime"
    fixture=1
fi
channel=${1:-}
package=com.qinggan.app.launcher
pattern='^OVC_(READY|VOICE_READY) '
relative=launcher-buttons.js
case "$channel" in
    '') ;;
    setting) package=com.qinggan.app.setting; relative=locale-setting/launcher-buttons.js ;;
    vehicle) package=com.qinggan.app.vehicle; relative=locale-vehicle/launcher-buttons.js ;;
    bluetooth) package=com.qinggan.bluetoothphone; relative=locale-bluetooth/launcher-buttons.js ;;
    window) package=android; relative=window/launcher-buttons.js; pattern='^OVC_WINDOW_READY protocol=3 ' ;;
    navigation) package=com.qinggan.cluster; relative=navigation/launcher-buttons.js; pattern='^OVC_NAVIGATION_READY' ;;
    --verify-boot) ;;
    *) exit 2 ;;
esac
case "$channel" in setting|vehicle|bluetooth) pattern='^OVC_LOCALIZATION_READY';; esac
target=$package
if [ "$channel" = window ]; then target=system_server; fi
script="$root/$relative"
dir=${script%/*}
receipt="$root/trust.tsv"
app=org.openvoyah.controls

digest() { sha256sum "$1" | awk '{print $1}'; }
private_dir() { [ -d "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %u:%a "$1")" = 0:700 ]; }
private_file() { [ -f "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %u:%a "$1")" = "0:$2" ]; }
root_file() {
    [ -f "$1" ] && [ ! -L "$1" ] || return 1
    case "$(stat -c %u:%a "$1")" in 0:400|0:500|0:600|0:700|0:644|0:755) return 0;; esac
    return 1
}
is_number() { case "$1" in ''|*[!0-9]*) return 1;; esac; }
pid_for() { pidof "$1" 2>/dev/null | awk '{ for (i=1; i<=NF; i++) { count++; pid=$i } } END { if(count==1 && pid~/^[1-9][0-9]*$/) print pid }'; }
start_of() {
    line=$(cat "/proc/$1/stat" 2>/dev/null) || return 1
    rest=${line##*) }
    printf '%s\n' "$rest" | awk 'NF>=20 && $1!="Z" && $1!="X" {print $20}'
}
file_identity() { stat -c '%d:%i:%s:%Y:%Z:%u:%a' "$1" 2>/dev/null; }
package_path() {
    value=$(pm path --user 0 "$1" 2>/dev/null) || return 1
    case "$value" in
        package:/*.apk) printf '%s\n' "${value#package:}" ;;
        *) return 1 ;;
    esac
}
record() {
    awk -F '\t' -v type="$1" -v name="$2" -v col="$3" '
        $1==type && $2==name { n++; value=$col }
        END { if(n!=1 || value=="") exit 1; print value }
    ' "$receipt"
}
reviewed_receipt() {
    private_dir /data/adb && private_file "$receipt" 600 &&
        [ "$(getenforce)" = Enforcing ] || return 1
    [ "$(wc -c < "$receipt")" -le 16384 ] || return 1
    awk -F '\t' -v fp="$(getprop ro.build.fingerprint)" -v sdk="$(getprop ro.build.version.sdk)" \
        -v abi="$(getprop ro.product.cpu.abi)" -v product="$(getprop ro.product.name)" '
        function sha(v) { return length(v)==64 && v ~ /^[a-f0-9]+$/ }
        $1=="device" {
            if (NF!=5 || $2!=fp || $3!=sdk || $4!=abi || $5!=product ||
                fp=="" || product=="" || (sdk!="28" && sdk!="30") || abi!="arm64-v8a") exit 1
            devices++; next
        }
        $1=="package" {
            if (NF!=4 || $2 !~ /^[A-Za-z][A-Za-z0-9_.]*$/ || ++packages[$2]!=1 ||
                $3 !~ /^\/[A-Za-z0-9_.~+=\/-]+\.apk$/ || $3 ~ /\/\.\.?\// || !sha($4)) exit 1
            next
        }
        $1=="artifact" {
            if (NF!=3 || ++artifacts[$2]!=1 || !sha($3) ||
                $2 !~ /^(boot-start\.sh|supervise\.sh|open-voyah-controls-frida-17\.18\.0|launcher-buttons\.js|(locale-setting|locale-vehicle|locale-bluetooth|window|navigation)\/launcher-buttons\.js)$/) exit 1
            next
        }
        { exit 1 }
        END {
            if (devices!=1 || !packages["org.openvoyah.controls"] || !packages["com.qinggan.app.launcher"] ||
                !artifacts["boot-start.sh"] || !artifacts["supervise.sh"] ||
                !artifacts["open-voyah-controls-frida-17.18.0"] || !artifacts["launcher-buttons.js"]) exit 1
        }
    ' "$receipt"
}
artifact_ok() {
    expected=$(record artifact "$1" 3) || return 1
    root_file "$root/$1" && [ "$(digest "$root/$1")" = "$expected" ]
}
reviewed_package() {
    declared=$(record package "$1" 3) && expected=$(record package "$1" 4) || return 1
    installed=$(package_path "$1") || return 1
    [ "$declared" = "$installed" ] && [ -f "$installed" ] && [ ! -L "$installed" ] &&
        [ "$(digest "$installed")" = "$expected" ] || return 1
    printf '%s\n' "$installed"
}
legacy_inert() {
    old=/data/local/tmp/open-voyah-controls
    for file in "/data/local/tmp/$runtime" "$old/boot-start.sh" "$old/supervise.sh" \
        "$old/enabled" "$old/launcher-buttons.js" "$old/localization.js"; do
        if [ -e "$file" ] || [ -L "$file" ]; then return 1; fi
    done
    for old_channel in locale-setting locale-vehicle locale-bluetooth window navigation; do
        for file in "$old/$old_channel/enabled" "$old/$old_channel/launcher-buttons.js"; do
            if [ -e "$file" ] || [ -L "$file" ]; then return 1; fi
        done
    done
    candidates=$(grep -aF -l -e "$old/supervise.sh" -e "/data/local/tmp/$runtime" /proc/[0-9]*/cmdline 2>/dev/null || true)
    for proc in $candidates; do
        args=$(tr '\000' ' ' < "$proc" 2>/dev/null) || continue
        case "$args" in "/system/bin/sh $old/supervise.sh "*|"/data/local/tmp/$runtime -p "*) return 1;; esac
    done
}
check_runtime() {
    [ "$(id -u)" = 0 ] && private_dir "$root" && private_dir "$dir" || return 1
    if [ "$fixture" = 1 ]; then
        app_path=$(package_path "$app") || return 1
        package_file=
        [ -f "$app_path" ] && [ -x "$injector" ] && [ -f "$script" ] || return 1
    else
        [ "$(am get-current-user 2>/dev/null)" = 0 ] && legacy_inert && reviewed_receipt &&
            private_file "$injector" 500 && [ "$(record artifact "$runtime" 3)" = "$runtime_sha" ] &&
            artifact_ok "$runtime" &&
            private_file "$root/boot-start.sh" 700 && artifact_ok boot-start.sh &&
            private_file "$root/supervise.sh" 700 && artifact_ok supervise.sh && artifact_ok "$relative" ||
            return 1
        app_path=$(reviewed_package "$app") && package_file=$(reviewed_package "$package") || return 1
    fi
    app_identity=$(file_identity "$app_path") && injector_identity=$(file_identity "$injector") &&
        script_identity=$(file_identity "$script") || return 1
    package_identity=
    if [ -n "$package_file" ]; then package_identity=$(file_identity "$package_file") || return 1; fi
    receipt_identity=
    if [ "$fixture" = 0 ]; then receipt_identity=$(file_identity "$receipt") || return 1; fi
}
runtime_unchanged() {
    [ "$app_identity" = "$(file_identity "$app_path")" ] &&
        [ "$injector_identity" = "$(file_identity "$injector")" ] &&
        [ "$script_identity" = "$(file_identity "$script")" ] &&
        { [ -z "$package_file" ] || [ "$package_identity" = "$(file_identity "$package_file")" ]; }
}
enabled() {
    [ -f "$root/enabled" ] && [ ! -L "$root/enabled" ] &&
        [ -f "$dir/enabled" ] && [ ! -L "$dir/enabled" ]
}
may_start() {
    enabled && [ ! -e "$root/recovery.blocked" ] && [ ! -L "$root/recovery.blocked" ]
}
same_child() {
    is_number "$1" && [ -n "$2" ] && [ "$(start_of "$1")" = "$2" ] &&
        [ "$(stat -c %u "/proc/$1" 2>/dev/null)" = 0 ] || return 1
    executable=$(readlink "/proc/$1/exe" 2>/dev/null) || return 1
    [ "$executable" = "$injector" ] || [ "$executable" = "$injector (deleted)" ] || return 1
    args=$(tr '\000' ' ' < "/proc/$1/cmdline" 2>/dev/null) || return 1
    printf '%s\n' "$args" | awk -v inject="$injector" -v script="$script" '
        NF==5 && $1==inject && $2=="-p" && $3~/^[1-9][0-9]*$/ && $4=="-s" && $5==script { found=1 }
        END { exit !found }
    '
}
stop_child() {
    child_gone "$1" "$2" && return 0
    same_child "$1" "$2" || return 1
    kill -TERM "$1" 2>/dev/null || { child_gone "$1" "$2"; return; }
    for delay in 1 2 3; do
        sleep 1
        if child_gone "$1" "$2"; then return 0; fi
    done
    same_child "$1" "$2" && kill -KILL "$1" 2>/dev/null || { child_gone "$1" "$2"; return; }
    sleep 1
    child_gone "$1" "$2"
}
child_gone() {
    if [ -z "$1" ]; then return 0; fi
    is_number "$1" || return 1
    if [ ! -d "/proc/$1" ]; then return 0; fi
    info=$(cat "/proc/$1/stat" 2>/dev/null) || return 1
    tail=${info##*) }
    printf '%s\n' "$tail" | awk -v start="$2" '
        NF>=20 && ($1=="Z" || $1=="X" || (start!="" && $20!=start && $20~/^[0-9]+$/)) { gone=1 }
        END { exit !gone }
    '
}
unknown_injector() {
    matches=$(grep -aF -l "$script" /proc/[0-9]*/cmdline 2>/dev/null || true)
    for proc in $matches; do
        args=$(tr '\000' ' ' < "$proc" 2>/dev/null) || continue
        case "$args" in "$injector -p "*" -s $script ") return 0;; esac
    done
    return 1
}
log() {
    if [ -f "$dir/worker.log" ] && [ "$(wc -c < "$dir/worker.log")" -gt 131072 ]; then
        : > "$dir/worker.log"
    fi
    printf '%s\n' "$*" >> "$dir/worker.log"
}

if [ "$channel" = --verify-boot ]; then
    if check_runtime; then echo 'OVC_BOOT_VERIFIED'; exit 0; fi
    echo 'OVC_BOOT_ERROR Deployment is missing, changed, or unapproved.' >&2
    exit 1
fi
[ "$(id -u)" = 0 ] && private_dir "$root" && private_dir "$dir" && may_start || exit 1

boot_id=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
case "$boot_id" in ''|*[!a-f0-9-]*) exit 1;; esac
self_start=$(start_of "$$")
is_number "$self_start" || exit 1
owner="$boot_id:$$:$self_start"
lock="$dir/worker.owner"
recover=
child=
child_start=
claim=$(mktemp "$dir/worker.claim.XXXXXX") || exit 1
printf '%s\n' "$owner" > "$claim"
read_owner() { private_file "$1" 600 && cat "$1"; }
finish() {
    if [ "$(read_owner "$lock" 2>/dev/null)" = "$owner" ]; then
        rm -f "$dir/ready"
        if stop_child "$child" "$child_start"; then
            rm -f "$dir/worker.pid" "$dir/target.pid" "$dir/system.pid" "$lock"
        else
            log 'OVC_WORKER_ERROR Injector did not stop. Preserving ownership evidence.'
        fi
    fi
    rm -f "$claim"
    if [ -n "$recover" ]; then rmdir "$recover" 2>/dev/null; fi
}
trap finish EXIT
trap 'exit 0' HUP INT TERM

[ ! -d "$lock" ] && [ ! -L "$lock" ] || exit 1
if ! ln "$claim" "$lock" 2>/dev/null; then
    prior=$(read_owner "$lock" 2>/dev/null) || exit 1
    case "$prior" in ''|*[!a-f0-9:-]*) exit 1;; esac
    prior_boot=${prior%%:*}; prior_pid=${prior#*:}; prior_pid=${prior_pid%%:*}; prior_start=${prior##*:}
    is_number "$prior_pid" && is_number "$prior_start" || exit 1
    if [ "$prior_boot" = "$boot_id" ] && [ "$(start_of "$prior_pid")" = "$prior_start" ]; then exit 0; fi
    # Recoverers of the same stale owner serialize; nobody removes a fresh owner's lock.
    gate_name=$(printf '%s' "$prior" | tr ':' '-')
    gate="$dir/worker-recover-$gate_name-$boot_id"
    mkdir "$gate" 2>/dev/null || exit 1
    recover=$gate
    [ "$(read_owner "$lock" 2>/dev/null)" = "$prior" ] || exit 0
    if [ "$prior_boot" = "$boot_id" ]; then
        orphan=$(cat "$dir/child.pid" 2>/dev/null)
        orphan_start=$(cat "$dir/child.start" 2>/dev/null)
        stop_child "$orphan" "$orphan_start" || exit 1
    fi
    rm -f "$lock" || exit 1
    ln "$claim" "$lock" 2>/dev/null || exit 0
    rmdir "$recover" || exit 1
    recover=
    log 'OVC_WORKER_NOTICE Recovered a stale worker lock.'
fi
if unknown_injector; then
    log 'OVC_WORKER_ERROR An existing injector requires recovery before attaching.'
    exit 1
fi
printf '%s\n' "$boot_id" > "$dir/worker.boot"
printf '%s\n' "$$" > "$dir/worker.pid"

attempts=0
max_attempts=5
if [ "$channel" = window ]; then max_attempts=3; fi
if [ "$(cat "$dir/failure.boot" 2>/dev/null)" = "$boot_id" ]; then
    value=$(cat "$dir/failure.count" 2>/dev/null)
    case "$value" in 0|[1-9]|[1-9][0-9]) attempts=$value;; esac
fi
save_attempts() {
    printf '%s\n' "$boot_id" > "$dir/failure.boot"
    printf '%s\n' "$attempts" > "$dir/failure.count"
}
backoff=3
while may_start; do
    if [ "$attempts" -ge "$max_attempts" ]; then
        log 'OVC_WORKER_ERROR Retry limit reached for this boot. Review diagnostics before reconnecting.'
        break
    fi
    stable=0; waited=0; system_pid=
    while may_start && [ "$stable" -lt 3 ] && [ "$waited" -lt 120 ]; do
        current=$(pid_for system_server)
        if [ "$(getprop sys.boot_completed)" != 1 ] || ! is_number "$current" || [ "$current" -le 1 ]; then
            system_pid=; stable=0
        elif [ "$current" = "$system_pid" ]; then
            stable=$((stable+1))
        else
            system_pid=$current; stable=1
        fi
        if [ "$stable" -lt 3 ]; then sleep 2; waited=$((waited+2)); fi
    done
    may_start || break
    if [ "$stable" -lt 3 ]; then log 'OVC_WORKER_ERROR Android boot did not settle.'; break; fi
    target_pid=$(pid_for "$target")
    waited=0
    while may_start && { ! is_number "$target_pid" || [ "$target_pid" -le 1 ]; } && [ "$waited" -lt 60 ]; do
        sleep 2; waited=$((waited+2)); target_pid=$(pid_for "$target")
    done
    may_start || break
    if ! is_number "$target_pid" || [ "$target_pid" -le 1 ]; then log 'OVC_WORKER_ERROR Reviewed target is unavailable.'; break; fi
    system_start=$(start_of "$system_pid")
    target_start=$(start_of "$target_pid")
    if ! is_number "$system_start" || ! is_number "$target_start"; then log 'OVC_WORKER_ERROR Target identity is unavailable.'; break; fi
    if ! check_runtime; then log 'OVC_WORKER_ERROR Deployment is missing, changed, or unapproved.'; break; fi
    may_start && [ "$(pid_for system_server)" = "$system_pid" ] && [ "$(pid_for "$target")" = "$target_pid" ] &&
        [ "$(start_of "$system_pid")" = "$system_start" ] && [ "$(start_of "$target_pid")" = "$target_start" ] || break
    printf '%s\n' "$target_pid" > "$dir/target.pid"
    printf '%s\n' "$system_pid" > "$dir/system.pid"
    rm -f "$dir/ready"
    : > "$dir/hook.log"
    "$injector" -p "$target_pid" -s "$script" >> "$dir/hook.log" 2>&1 &
    child=$!
    child_start=$(start_of "$child")
    printf '%s\n' "$child" > "$dir/child.pid"
    printf '%s\n' "$child_start" > "$dir/child.start"
    elapsed=0; healthy=0; last_heartbeat=; silent_for=0; markers=
    while [ -n "$child_start" ] && [ "$(start_of "$child")" = "$child_start" ] && enabled; do
        sleep 2
        elapsed=$((elapsed+2))
        if ! runtime_unchanged || [ "$(pid_for system_server)" != "$system_pid" ] || [ "$(pid_for "$target")" != "$target_pid" ] ||
            [ "$(start_of "$system_pid")" != "$system_start" ] || [ "$(start_of "$target_pid")" != "$target_start" ]; then
            log 'OVC_WORKER_NOTICE Android, package, or target changed.'
            break
        fi
        if [ "$fixture" = 0 ] && [ "$receipt_identity" != "$(file_identity "$receipt")" ]; then
            # An optional channel update rewrites the receipt. Revalidate it before continuing.
            if ! check_runtime; then log 'OVC_WORKER_ERROR Updated deployment is unapproved.'; break; fi
        fi
        if [ $((elapsed%10)) -eq 0 ]; then
            if { [ "$fixture" = 0 ] && [ "$(am get-current-user 2>/dev/null)" != 0 ]; } ||
                [ "$(package_path "$app")" != "$app_path" ] ||
                { [ -n "$package_file" ] && [ "$(package_path "$package")" != "$package_file" ]; }; then
                log 'OVC_WORKER_NOTICE Installed package changed.'
                break
            fi
        fi
        if grep -qE "$pattern" "$dir/hook.log"; then
            markers=$(grep -E '^OVC_(READY|[A-Z_]+_READY)' "$dir/hook.log" | tail -n 16)
        fi
        heartbeat=$(grep '^OVC_HEARTBEAT [0-9][0-9]*$' "$dir/hook.log" | tail -n 1 | awk '{print $2}')
        silent_for=$((silent_for+2))
        if [ -n "$markers" ] && [ -n "$heartbeat" ] && [ "$heartbeat" != "$last_heartbeat" ]; then
            last_heartbeat=$heartbeat; silent_for=0; healthy=1
            printf '%s\n' "$markers" > "$dir/ready.new" && mv -f "$dir/ready.new" "$dir/ready"
        fi
        if { [ "$elapsed" -ge 25 ] && [ "$healthy" -eq 0 ]; } ||
            { [ "$healthy" -eq 1 ] && [ "$silent_for" -ge 30 ]; }; then
            log 'OVC_WORKER_ERROR Agent did not confirm current readiness.'
            break
        fi
        if [ "$(wc -c < "$dir/hook.log")" -gt 262144 ]; then : > "$dir/hook.log"; fi
        if [ "$elapsed" -ge 60 ] && [ "$healthy" -eq 1 ] && [ "$attempts" -ne 0 ]; then
            attempts=0; backoff=3; save_attempts
        fi
    done
    rm -f "$dir/target.pid" "$dir/system.pid" "$dir/ready"
    if ! stop_child "$child" "$child_start"; then log 'OVC_WORKER_ERROR Injector did not stop.'; break; fi
    # Reap a known exited child only. Never wait indefinitely for an unknown PID.
    if child_gone "$child" "$child_start"; then wait "$child" 2>/dev/null; fi
    child=; child_start=
    rm -f "$dir/child.pid" "$dir/child.start"
    may_start || break
    attempts=$((attempts+1)); save_attempts
    waited=0
    while may_start && [ "$waited" -lt "$backoff" ]; do sleep 1; waited=$((waited+1)); done
    backoff=$((backoff*2))
    if [ "$backoff" -gt 30 ]; then backoff=30; fi
done

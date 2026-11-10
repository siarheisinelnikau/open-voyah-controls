#!/system/bin/sh
# One worker per enabled process. Wait for Android before attaching.
base=/data/local/tmp/open-voyah-controls
injector=/data/local/tmp/open-voyah-controls-frida-17.18.0
script="$base/launcher-buttons.js"
package=com.qinggan.app.launcher
ready='OVC_READY|OVC_VOICE_READY'
case "$1" in
    setting) package=com.qinggan.app.setting ;;
    vehicle) package=com.qinggan.app.vehicle ;;
    bluetooth) package=com.qinggan.bluetoothphone ;;
    window) package=system_server ;;
    navigation) package=com.qinggan.cluster ;;
    '') ;;
    *) exit 2 ;;
esac
case "$1" in
    window) base="$base/window"; ready=OVC_WINDOW_READY ;;
    navigation) base="$base/navigation"; ready=OVC_NAVIGATION_READY ;;
    ?*) base="$base/locale-$1"; ready=OVC_LOCALIZATION_READY ;;
esac
script="$base/launcher-buttons.js"
child=""
target_pid=""
system_pid=""
pid_for() { pidof "$1" | awk '{print $1}'; }
is_pid() { case "$1" in ''|0|*[!0-9]*) return 1 ;; *) return 0 ;; esac; }
stop_child() {
    if ! is_pid "$child"; then return; fi
    args=$(tr '\000' ' ' < "/proc/$child/cmdline" 2>/dev/null)
    case "$args" in *"$script"*) kill "$child" 2>/dev/null ;; esac
}
finish() {
    stop_child
    rm -f "$base/worker.pid" "$base/target.pid" "$base/system.pid" "$base/ready"
    rmdir "$lock" 2>/dev/null
}
# A new kernel boot uses a different lock, even when both startup paths run at once.
boot_id=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
saved_boot=$(cat "$base/worker.boot" 2>/dev/null)
lock=$base/worker.lock
if [ -n "$boot_id" ]; then lock=$lock-$boot_id; fi
mkdir "$lock" 2>/dev/null || exit 0
if [ -n "$boot_id" ] && [ -n "$saved_boot" ] && [ "$saved_boot" != "$boot_id" ]; then
    rmdir "$base/worker.lock-$saved_boot" 2>/dev/null
fi
trap 'exit 0' INT TERM
trap finish EXIT
printf '%s\n' "$boot_id" > "$base/worker.boot"
echo $$ > "$base/worker.pid"
backoff=3
while [ -f "$base/enabled" ]; do
    stable=0
    system_pid=""
    while [ -f "$base/enabled" ] && [ "$stable" -lt 3 ]; do
        current=$(pid_for system_server)
        if [ "$(getprop sys.boot_completed)" != 1 ] || ! is_pid "$current"; then
            system_pid=""
            stable=0
        elif [ "$current" = "$system_pid" ]; then
            stable=$((stable+1))
        else
            system_pid="$current"
            stable=1
        fi
        if [ "$stable" -lt 3 ]; then sleep 2; fi
    done
    [ -f "$base/enabled" ] || break
    target_pid=$(pid_for "$package")
    if ! is_pid "$target_pid"; then sleep 3; continue; fi
    printf '%s\n' "$target_pid" > "$base/target.pid"
    printf '%s\n' "$system_pid" > "$base/system.pid"
    rm -f "$base/ready"
    : > "$base/hook.log"
    "$injector" -p "$target_pid" -s "$script" >> "$base/hook.log" 2>&1 &
    child=$!
    echo "$child" > "$base/child.pid"
    elapsed=0
    healthy=0
    last_heartbeat=""
    silent_for=0
    while kill -0 "$child" 2>/dev/null && [ -f "$base/enabled" ]; do
        sleep 2
        elapsed=$((elapsed+2))
        if [ "$(pid_for system_server)" != "$system_pid" ] || [ "$(pid_for "$package")" != "$target_pid" ]; then
            echo 'Android or target process changed; reattaching'
            backoff=1
            stop_child
            break
        fi
        if grep -qE "$ready" "$base/hook.log"; then
            healthy=1
            grep -E 'OVC_(READY|[A-Z_]+_READY)' "$base/hook.log" | tail -n 16 > "$base/ready.new"
            mv "$base/ready.new" "$base/ready"
        fi
        if [ "$elapsed" -ge 20 ] && [ "$healthy" -eq 0 ]; then
            echo "Injector has not become ready; retry in ${backoff}s"
            stop_child
            break
        fi
        heartbeat=$(grep OVC_HEARTBEAT "$base/hook.log" | tail -n 1 | awk '{print $2}')
        silent_for=$((silent_for+2))
        case "$heartbeat" in ''|*[!0-9]*) ;; *)
            if [ "$heartbeat" != "$last_heartbeat" ]; then last_heartbeat="$heartbeat"; silent_for=0; fi
        ;; esac
        if [ "$healthy" -eq 1 ] && [ "$silent_for" -ge 30 ]; then
            echo "Injector heartbeat lost; retry in ${backoff}s"
            stop_child
            break
        fi
        # Bound disk use without moving the open output file.
        bytes=$(wc -c < "$base/hook.log")
        if [ "$bytes" -gt 262144 ]; then : > "$base/hook.log"; fi
        if [ "$elapsed" -ge 60 ]; then backoff=3; fi
    done
    if [ ! -f "$base/enabled" ]; then stop_child; fi
    wait "$child" 2>/dev/null
    child=""
    rm -f "$base/target.pid" "$base/system.pid" "$base/ready"
    waited=0
    while [ "$waited" -lt "$backoff" ] && [ -f "$base/enabled" ]; do
        sleep 1
        waited=$((waited+1))
    done
    backoff=$((backoff*2))
    if [ "$backoff" -gt 300 ]; then backoff=300; fi
done

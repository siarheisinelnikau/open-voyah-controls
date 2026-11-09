#!/system/bin/sh
# A separate, opt-in launcher hook worker. Does not change OEM boot files.
base=/data/local/tmp/open-voyah-controls
injector=/data/local/tmp/open-voyah-controls-frida-17.18.0
script="$base/launcher-buttons.js"
package=com.qinggan.app.launcher
ready='OVC_READY|OVC_VOICE_READY'
case "$1" in
    setting) package=com.qinggan.app.setting ;;
    vehicle) package=com.qinggan.app.vehicle ;;
    bluetooth) package=com.qinggan.bluetoothphone ;;
    '') ;;
    *) exit 2 ;;
esac
if [ -n "$1" ]; then base="$base/locale-$1"; script="$base/launcher-buttons.js"; ready=OVC_LOCALIZATION_READY; fi
child=""
finish() {
    if [ -n "$child" ]; then
        args=$(tr '\000' ' ' < "/proc/$child/cmdline" 2>/dev/null)
        case "$args" in *"$script"*) kill "$child" 2>/dev/null ;; esac
    fi
    rmdir "$base/worker.lock" 2>/dev/null
}
# Only one worker; stale lock from a prior boot is handled by the launcher command.
mkdir "$base/worker.lock" 2>/dev/null || exit 0
trap 'exit 0' INT TERM
trap finish EXIT
echo $$ > "$base/worker.pid"
backoff=3
while [ -f "$base/enabled" ]; do
    pid=$(pidof "$package" | awk '{print $1}')
    case "$pid" in ''|*[!0-9]*) sleep 3; continue ;; esac
    "$injector" -p "$pid" -s "$script" > "$base/hook.log" 2>&1 &
    child=$!
    echo "$child" > "$base/child.pid"
    elapsed=0
    healthy=0
    last_heartbeat=""
    silent_for=0
    while kill -0 "$child" 2>/dev/null && [ -f "$base/enabled" ]; do
        sleep 2
        elapsed=$((elapsed+2))
        if grep -qE "$ready" "$base/hook.log"; then healthy=1; fi
        if [ "$elapsed" -ge 20 ] && [ "$healthy" -eq 0 ]; then
            echo "Injector has not become ready; retry in ${backoff}s"
            kill "$child" 2>/dev/null
            break
        fi
        heartbeat=$(grep OVC_HEARTBEAT "$base/hook.log" | tail -n 1 | awk '{print $2}')
        silent_for=$((silent_for+2))
        case "$heartbeat" in ''|*[!0-9]*) ;; *)
            if [ "$heartbeat" != "$last_heartbeat" ]; then last_heartbeat="$heartbeat"; silent_for=0; fi
        ;; esac
        if [ "$healthy" -eq 1 ] && [ "$silent_for" -ge 30 ]; then
            echo "Injector heartbeat lost; retry in ${backoff}s"
            kill "$child" 2>/dev/null
            break
        fi
        # Bound disk use without moving the open output file.
        bytes=$(wc -c < "$base/hook.log")
        if [ "$bytes" -gt 262144 ]; then : > "$base/hook.log"; fi
        if [ "$elapsed" -ge 60 ]; then backoff=3; fi
    done
    if [ ! -f "$base/enabled" ]; then kill "$child" 2>/dev/null; fi
    wait "$child" 2>/dev/null
    child=""
    waited=0
    while [ "$waited" -lt "$backoff" ] && [ -f "$base/enabled" ]; do
        sleep 1
        waited=$((waited+1))
    done
    backoff=$((backoff*2))
    if [ "$backoff" -gt 300 ]; then backoff=300; fi
done

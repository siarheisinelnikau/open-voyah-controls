#!/system/bin/sh
# Root-owned bootstrap called from the OEM logcat init script or the selected ADB root shell.
set -eu
export PATH=/system/bin:/system/xbin
unset ENV BASH_ENV LD_PRELOAD LD_LIBRARY_PATH
umask 077
base=/data/adb/open-voyah-controls-access
private() { [ -d "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %u:%a "$1")" = 0:700 ]; }
file() { [ -f "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %u:%a:%h "$1")" = "0:$2:1" ]; }
start_of() {
    text=$(cat "/proc/$1/stat" 2>/dev/null) || return 1
    printf '%s\n' "${text##*) }" | awk 'NF>=20 && $1!="Z" && $1!="X" {print $20}'
}
port_available() {
    pa_hex() {
        [ "${#1}" = "$2" ] || return 1
        case "$1" in ''|*[!0-9A-Fa-f]*) return 1;; esac
    }
    pa_pair() {
        case "$1" in *:*) ;; *) return 1;; esac
        pa_left=${1%%:*}; pa_right=${1#*:}
        pa_hex "$pa_left" "$2" && pa_hex "$pa_right" "$3"
    }
    pa_address() {
        case "$1" in *:*) ;; *) return 1;; esac
        pa_address_host=${1%%:*}
        case "${#pa_address_host}" in 8|32) ;; *) return 1;; esac
        pa_pair "$1" "${#pa_address_host}" 4
    }
    pa_ifs=$(printf ' \t')
    for pa_file in /proc/net/tcp /proc/net/tcp6; do
        [ -r "$pa_file" ] || return 1
        pa_text=$(head -c 262145 "$pa_file" 2>/dev/null) || return 1
        [ "${#pa_text}" -le 262144 ] || return 1
        pa_header=0
        while IFS="$pa_ifs" read -r pa_slot pa_local pa_remote pa_state pa_queue pa_timer pa_retrans pa_uid pa_timeout pa_inode pa_rest; do
            if [ "$pa_header" = 0 ]; then
                [ "$pa_slot" = sl ] && [ "$pa_local" = local_address ] && [ "$pa_state" = st ] || return 1
                case "$pa_remote" in rem_address|remote_address) ;; *) return 1;; esac
                pa_header=1
                continue
            fi
            case "$pa_slot" in *:) pa_number=${pa_slot%:};; *) return 1;; esac
            case "$pa_number" in ''|*[!0-9]*) return 1;; esac
            pa_address "$pa_local" && pa_address "$pa_remote" && pa_hex "$pa_state" 2 &&
                pa_pair "$pa_queue" 8 8 && pa_pair "$pa_timer" 2 8 && pa_hex "$pa_retrans" 8 || return 1
            for pa_number in "$pa_uid" "$pa_timeout" "$pa_inode"; do
                case "$pa_number" in ''|*[!0-9]*) return 1;; esac
            done
            case "$pa_local" in *:0913) case "$pa_state" in 0A|0a) return 1;; esac;; esac
        done <<OVC_TCP_SNAPSHOT
$pa_text
OVC_TCP_SNAPSHOT
        [ "$pa_header" = 1 ] || return 1
    done
}

[ "$(id -u)" = 0 ] && private /data/adb && private "$base" || exit 1
cd "$base"
running() {
    file "$base/process" 600 || return 1
    read -r pid start boot < "$base/process"
    case "$pid:$start" in *[!0-9:]*|:*) return 1;; esac
    [ "$pid" -gt 1 ] && [ -n "$start" ] && [ "$start" = "$(start_of "$pid")" ] &&
        [ "$boot" = "$(cat /proc/sys/kernel/random/boot_id)" ] || return 1
    [ "$(stat -c %u "/proc/$pid")" = 0 ] || return 1
    args=$(tr '\000' ' ' < "/proc/$pid/cmdline")
    case "$args" in "$busybox telnetd -F -K -p 2323 -b 127.0.0.1 -l $base/login.sh -f /dev/null ") return 0;; *) return 1;; esac
}
file "$base/busybox" 600 || exit 1
busybox=$(cat "$base/busybox")
case "$busybox" in /system/bin/busybox|/system/xbin/busybox|/vendor/bin/busybox) ;; *) exit 1;; esac
if [ "${1:-}" = --status ]; then
    if running; then echo active; else echo inactive; fi
    exit 0
fi
# Android 9 mksh closes inherited descriptors even with an explicit redirection.
# Use the verified BusyBox shell so the shared flock descriptor survives exec.
case "${1:-}" in
    --access-shell) shift;;
    *) exec "$busybox" sh "$base/access-start.sh" --access-shell "$@";;
esac
# Kernel locks release on process death and reboot; a failed start leaves no stale owner.
lock="$base/start.lock"
if [ ! -e "$lock" ]; then (set -C; : > "$lock") 2>/dev/null || true; fi
file "$lock" 600 || exit 1
exec 9<> "$lock"
"$busybox" flock -n 9 || exit 1
if [ "${1:-}" = --stop ]; then
    if running; then
        kill -TERM "$pid"
        count=0
        while [ "$(start_of "$pid" || true)" = "$start" ] && [ "$count" -lt 5 ]; do sleep 1; count=$((count+1)); done
        [ "$(start_of "$pid" || true)" != "$start" ] || exit 1
    fi
    rm -f "$base/process"
    exit 0
fi
[ "$(getenforce)" = Enforcing ] && [ ! -e "$base/disabled" ] && [ ! -L "$base/disabled" ] || exit 1
file "$base/fingerprint" 600 && [ "$(getprop ro.build.fingerprint)" = "$(cat "$base/fingerprint")" ] || exit 1
file "$base/access-start.sh" 700 && file "$base/login.sh" 700 && file "$base/checksums" 600 || exit 1
[ "$(wc -l < "$base/checksums")" = 3 ] && sha256sum -c "$base/checksums" >/dev/null || exit 1
file "$base/key" 600 && file "$base/uid" 600 || exit 1
bound() { awk '$2=="0100007F:0913" && $4=="0A" && $8==0 {print $10}' /proc/net/tcp; }
owns_listener() {
    running || return 1
    inode=$(bound)
    case "$inode" in ''|*[!0-9]*) return 1;; esac
    for fd in /proc/"$pid"/fd/*; do [ "$(readlink "$fd" 2>/dev/null)" != "socket:[$inode]" ] || return 0; done
    return 1
}
if [ "${1:-}" = --verify ]; then owns_listener && echo 'OVC_ACCESS_READY=1'; exit $?; fi
[ "$#" = 0 ] || exit 2
if owns_listener; then echo 'OVC_ACCESS_READY=1'; exit 0; fi
# Refuse a foreign listener rather than connecting to or killing it.
port_available || exit 1
nohup "$busybox" telnetd -F -K -p 2323 -b 127.0.0.1 -l "$base/login.sh" -f /dev/null >/dev/null 2>&1 </dev/null 9>&- &
pid=$!
start=$(start_of "$pid")
[ -n "$start" ] || exit 1
printf '%s %s %s\n' "$pid" "$start" "$(cat /proc/sys/kernel/random/boot_id)" > "$base/process"
chmod 600 "$base/process"
count=0
while [ "$count" -lt 6 ]; do
    if owns_listener; then echo 'OVC_ACCESS_READY=1'; exit 0; fi
    [ -n "$(start_of "$pid")" ] || exit 1
    sleep 1
    count=$((count+1))
done
exit 1

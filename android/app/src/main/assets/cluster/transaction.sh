#!/bin/sh
# The caller supplies fixed, validated OVC_ROOT, OVC_FW, OVC_ACTION and CRC values.
# OVC_ROOT is empty in production; tests use an isolated temporary filesystem.
set -eu
V="$OVC_ROOT/var/ovc-cluster"
B="$V/backup"
files='gauge light point point_normal.kzb'
target() {
    case "$1" in
        gauge|light|point) printf '%s/usr/bin/%s' "$OVC_ROOT" "$1" ;;
        point_normal.kzb) printf '%s/usr/share/theme/0/point_normal.kzb' "$OVC_ROOT" ;;
        *) exit 30 ;;
    esac
}
checksum() {
    result=$(cksum "$1") || return 1
    set -- $result
    [ "$#" -ge 2 ] || return 1
    case "$1:$2" in *[!0-9:]*) return 1 ;; esac
    printf '%s %s' "$1" "$2"
}
state() { printf '%s\n' "$1" > "$V/state.new" && mv "$V/state.new" "$V/state" && sync; }
check_backup() {
    [ -f "$B/ready" ] && [ "$(cat "$B/firmware")" = "$OVC_FW" ] || return 1
    for f in $files; do
        [ ! -L "$B/$f" ] && [ -s "$B/$f" ] || return 1
        [ "$(checksum "$B/$f")" = "$(cat "$B/$f.crc")" ] || return 1
    done
}
preflight() {
    for tool in id cksum cmp cp mv chmod sync mount mkdir rmdir cat rm; do command -v "$tool" >/dev/null || exit 31; done
    [ "$(id -u)" = 0 ] || exit 31
    [ ! -L "$V" ] && [ ! -L "$B" ] && [ ! -L "$V/stage" ] || exit 32
    for f in $files; do
        t=$(target "$f")
        [ -f "$t" ] && [ -s "$t" ] && [ ! -L "$t" ] || exit 33
    done
}
preflight
if [ "$OVC_ACTION" = inspect ]; then
    printf 'Firmware: %s\n' "$OVC_FW"
    for f in $files; do printf '%s: %s\n' "$f" "$(checksum "$(target "$f")")"; done
    if [ -f "$V/state" ]; then cat "$V/state"; else echo UNPREPARED; fi
    if check_backup; then echo BACKUP_VALID; else echo BACKUP_ABSENT_OR_INVALID; fi
    if [ -d "$V/lock" ]; then echo LOCK_PRESENT; fi
    exit 0
fi
umask 077
mkdir -p "$V"
# A dead owner's lock can be reclaimed. A live or malformed owner always blocks writes.
if ! mkdir "$V/lock" 2>/dev/null; then
    p=$(cat "$V/lock/pid" 2>/dev/null) || exit 34
    case "$p" in ''|*[!0-9]*) exit 34 ;; esac
    if kill -0 "$p" 2>/dev/null; then exit 34; fi
    rm "$V/lock/pid" && rmdir "$V/lock" && mkdir "$V/lock" || exit 34
fi
printf '%s\n' "$$" > "$V/lock/pid"
release() { rm -f "$V/lock/pid"; rmdir "$V/lock"; }
trap release EXIT
trap 'exit 35' HUP INT TERM
case "$OVC_ACTION" in
    prepare)
        if [ -e "$B" ]; then check_backup || exit 36; echo BACKUP_VALID; exit 0; fi
        temp="$V/backup.$$"
        mkdir "$temp"
        for f in $files; do
            t=$(target "$f")
            cp -p "$t" "$temp/$f"
            cmp "$t" "$temp/$f"
            checksum "$temp/$f" > "$temp/$f.crc"
            printf 'Backed up %s\n' "$f"
        done
        printf '%s\n' "$OVC_FW" > "$temp/firmware"
        printf '1\n' > "$temp/ready"
        sync
        mv "$temp" "$B"
        state PREPARED
        echo BACKUP_VALID
        exit 0 ;;
    apply|restore) check_backup || exit 36 ;;
    *) exit 37 ;;
esac
if [ "$OVC_ACTION" = apply ]; then
    case "$(cat "$V/state")" in PREPARED|RESTORED) ;; *) echo 'Restore before applying again'; exit 38 ;; esac
    for f in $files; do
        cmp "$(target "$f")" "$B/$f" || exit 39
        case "$f" in
            gauge) expected="$OVC_GAUGE" ;;
            light) expected="$OVC_LIGHT" ;;
            point) expected="$OVC_POINT" ;;
            point_normal.kzb) expected="$OVC_THEME" ;;
        esac
        [ ! -L "$V/stage/$f" ] && [ "$(checksum "$V/stage/$f")" = "$expected" ] || exit 40
    done
    source="$V/stage"
else
    source="$B"
fi
# Never remove the active executable before its replacement is ready.
mount -u -o rw "${OVC_ROOT:-/}"
for f in $files; do
    t=$(target "$f")
    [ ! -L "$t.ovc-new" ] || exit 41
    if [ "$OVC_ACTION" = restore ]; then
        cp -p "$source/$f" "$t.ovc-new"
    else
        cp -p "$t" "$t.ovc-new"
        cat "$source/$f" > "$t.ovc-new"
        case "$f" in gauge|light|point) chmod 755 "$t.ovc-new" ;; esac
    fi
    cmp "$source/$f" "$t.ovc-new"
done
sync
rollback() {
    trap - EXIT HUP INT TERM
    ok=1
    for f in $files; do
        t=$(target "$f")
        if cp -p "$B/$f" "$t.ovc-new" && cmp "$B/$f" "$t.ovc-new" && mv -f "$t.ovc-new" "$t"; then :; else ok=0; fi
    done
    if [ "$ok" = 1 ]; then state RESTORED || true; else state RECOVERY_REQUIRED || true; fi
    release || true
    exit 42
}
state "${OVC_ACTION}_IN_PROGRESS"
trap rollback EXIT HUP INT TERM
for f in $files; do
    t=$(target "$f")
    mv -f "$t.ovc-new" "$t"
    cmp "$source/$f" "$t"
    sync
    printf 'Installed %s\n' "$f"
done
if [ "$OVC_ACTION" = apply ]; then state APPLIED; else state RESTORED; fi
trap release EXIT
trap 'exit 35' HUP INT TERM
echo 'Files verified. No reboot or process restart was requested.'

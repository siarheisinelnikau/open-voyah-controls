#!/system/bin/sh
# Places, enables, disables, rolls back and removes this project's text overlays (tools/build_text_overlays.py) over an
# already authorized root shell. The package manager reads /vendor/overlay only while the head unit starts, so
# "install", "rollback" and "remove" take effect after one restart; "enable" and "disable" work at once on overlays it
# has scanned. "install" places the whole set in one pass and keeps what it replaced in $previous for "rollback".
set -eu
# OVC_ROOT and OVC_TEST_PATH are unset on a head unit; tests set them to run against an isolated filesystem.
root=${OVC_ROOT:-}
export PATH=${OVC_TEST_PATH:+$OVC_TEST_PATH:}/system/bin:/system/xbin
mode=${1:-}
fingerprint=${2:-}
serial=${3:-}
[ $# -ge 3 ] && shift 3
dir=$root/vendor/overlay
state=$root/data/adb/open-voyah-controls-overlays
record=$state/installed
previous=$state/previous
prefix=org.openvoyah.controls.overlay.
label=u:object_r:vendor_overlay_file:s0

fail() { printf '%s\n' "$*" >&2; exit 1; }
digest() { sha256sum "$1" | awk '{print $1}'; }
ours() {
    case "$1" in "$prefix"*.apk) ;; *) return 1;; esac
    case "${1#"$prefix"}" in ''|.*|*..*|*[!a-z0-9.]*) return 1;; esac
}
# One field of an overlay's entry in "dumpsys overlay" (empty while the package manager does not know it).
field() {
    dumpsys overlay | awk -v head="$1:0 {" -v name="$2" '
        $0 == head { inside = 1; next }
        inside && /^}/ { exit }
        inside && index($1, name ".") == 1 { sub(/^[^:]*: */, ""); print; exit }'
}
recorded() { [ -f "$record" ] && awk '{print $1}' "$record" || true; }
writable() { [ -d "$dir" ] && [ ! -L "$dir" ] && [ -w "$dir" ] || fail 'Remount /vendor first.'; }

[ "$(id -u)" = 0 ] || fail 'Run "adb root" first.'
[ "$(getprop ro.build.fingerprint)" = "$fingerprint" ] || fail 'This head unit runs a different firmware build.'
[ "$(getprop ro.serialno)" = "$serial" ] || fail 'This is a different head unit.'
prepare_state() {
    if [ ! -d "$state" ]; then mkdir -m 700 "$state"; fi
    [ ! -L "$state" ] && [ "$(stat -c %u:%a "$state")" = 0:700 ] || fail "Unexpected $state."
}

# Arguments come in groups of five: staged file, its SHA-256, file name, target package, target APK SHA-256.
verify() {
    [ $# -gt 0 ] && [ $(($# % 5)) -eq 0 ] || fail 'Pass the overlays in groups of five.'
    while [ $# -ge 5 ]; do
        stage=$1 sha=$2 file=$3 target=$4 target_sha=$5
        shift 5
        ours "$file" || fail "Unexpected overlay name $file."
        case "$stage" in "$root"/data/local/tmp/ovc-overlay.[0-9a-f]*.apk) ;; *) fail "Unexpected staging path $stage.";; esac
        [ -f "$stage" ] && [ ! -L "$stage" ] && [ "$(digest "$stage")" = "$sha" ] || fail "$file did not arrive intact."
        # The overlay maps the resource names of one exact APK; refuse it for any other build of the target app.
        apk=$(pm path --user 0 "$target" 2>/dev/null | sed -n 's/^package://p' | head -n 1)
        [ -n "$apk" ] && [ "$(digest "$apk")" = "$target_sha" ] || fail "$target here is not the APK $file was built from."
    done
}
# Copies one file into /vendor/overlay the way the package manager expects a preinstalled overlay.
put() {
    temporary="$dir/.$2.tmp"
    cp "$1" "$temporary"
    chown 0:0 "$temporary"
    chmod 644 "$temporary"
    chcon "$label" "$temporary"
    mv -f "$temporary" "$dir/$2"
    [ "$(digest "$dir/$2")" = "$3" ] || fail "$2 was not written intact."
}
# Disables and deletes every overlay of this project in /vendor/overlay; other files there are never touched.
clear_ours() {
    for path in "$dir/$prefix"*.apk; do
        [ -f "$path" ] || continue
        file=${path##*/}
        ours "$file" || continue
        package=${file%.apk}
        if [ -n "$(field "$package" mState)" ]; then cmd overlay disable --user 0 "$package" >/dev/null 2>&1 || true; fi
        rm -f "$path"
        printf 'OVC_OVERLAY_REMOVED %s\n' "$file"
    done
}
# Keeps this project's overlays that are in place now, and their record, for "rollback".
snapshot() {
    rm -rf "$previous"
    mkdir -m 700 "$previous"
    for path in "$dir/$prefix"*.apk; do
        [ -f "$path" ] || continue
        ours "${path##*/}" || continue
        cp "$path" "$previous/${path##*/}"
    done
    if [ -f "$record" ]; then cp "$record" "$previous/installed"; fi
    touch "$previous/complete"
}
place() {
    umask 022
    : > "$record.new"
    chmod 600 "$record.new"
    while [ $# -ge 5 ]; do
        stage=$1 sha=$2 file=$3
        shift 5
        put "$stage" "$file" "$sha"
        rm -f "$stage"
        printf '%s %s\n' "$file" "$sha" >> "$record.new"
        printf 'OVC_OVERLAY_INSTALLED %s\n' "$file"
    done
    mv -f "$record.new" "$record"
    sync
}

case "$mode" in
status)
    printf 'OVC_OVERLAY_DIR_WRITABLE=%s\n' "$([ -w "$dir" ] && echo yes || echo no)"
    for path in "$dir"/*; do
        [ -f "$path" ] || continue
        printf 'OVC_VENDOR_OVERLAY_FILE %s sha256=%s owner=%s\n' "${path##*/}" "$(digest "$path")" \
            "$(stat -c '%u:%g:%a' "$path")"
    done
    for path in "$dir/$prefix"*.apk; do
        [ -f "$path" ] || continue
        package=${path##*/}
        package=${package%.apk}
        printf 'OVC_OVERLAY %s state=%s enabled=%s\n' "$package" "$(field "$package" mState)" \
            "$(field "$package" mIsEnabled)"
    done
    if [ -f "$previous/complete" ]; then echo 'OVC_OVERLAY_ROLLBACK_AVAILABLE'; fi
    ;;
install)
    writable
    verify "$@"
    prepare_state
    snapshot
    # The new set replaces this project's overlays as a whole.
    clear_ours
    place "$@"
    echo 'OVC_OVERLAYS_NEED_RESTART'
    ;;
enable|disable)
    failed=0
    [ -n "$(recorded)" ] || fail 'No text overlays are installed on this head unit.'
    for file in $(recorded); do
        ours "$file" || continue
        package=${file%.apk}
        if [ -z "$(field "$package" mState)" ]; then
            printf 'OVC_OVERLAY_UNKNOWN %s\n' "$package"
            failed=1
            continue
        fi
        cmd overlay "$mode" --user 0 "$package" || true
        printf 'OVC_OVERLAY %s state=%s enabled=%s\n' "$package" "$(field "$package" mState)" \
            "$(field "$package" mIsEnabled)"
        expected=$([ "$mode" = enable ] && echo true || echo false)
        [ "$(field "$package" mIsEnabled)" = "$expected" ] || failed=1
    done
    [ "$failed" = 0 ] || fail 'Some overlays are not known to the package manager or did not change; restart after install.'
    ;;
rollback)
    # Back to what was in place before the last install: its overlays, or none after a first install.
    writable
    [ -f "$previous/complete" ] || fail 'There is no install to roll back.'
    clear_ours
    umask 022
    for path in "$previous/$prefix"*.apk; do
        [ -f "$path" ] || continue
        file=${path##*/}
        ours "$file" || continue
        put "$path" "$file" "$(digest "$path")"
        printf 'OVC_OVERLAY_RESTORED %s\n' "$file"
    done
    if [ -f "$previous/installed" ]; then cp "$previous/installed" "$record"; chmod 600 "$record"; else rm -f "$record"; fi
    rm -rf "$previous"
    sync
    echo 'OVC_OVERLAYS_NEED_RESTART'
    ;;
remove)
    writable
    clear_ours
    rm -f "$record"
    rm -rf "$previous"
    sync
    echo 'OVC_OVERLAYS_NEED_RESTART'
    ;;
*)
    fail 'Mode: status, install, enable, disable, rollback or remove.'
    ;;
esac

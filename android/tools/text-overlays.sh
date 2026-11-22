#!/system/bin/sh
# Places, enables, disables and removes this project's text overlays (tools/build_text_overlays.py) over an already
# authorized root shell. The package manager reads /vendor/overlay only while the head unit starts, so "install" and
# "remove" take effect after a restart; "enable" and "disable" work at once on overlays it has scanned.
set -eu
export PATH=/system/bin:/system/xbin
mode=${1:-}
fingerprint=${2:-}
serial=${3:-}
[ $# -ge 3 ] && shift 3
dir=/vendor/overlay
state=/data/adb/open-voyah-controls-overlays
record=$state/installed
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
        case "$stage" in /data/local/tmp/ovc-overlay.[0-9a-f]*.apk) ;; *) fail "Unexpected staging path $stage.";; esac
        [ -f "$stage" ] && [ ! -L "$stage" ] && [ "$(digest "$stage")" = "$sha" ] || fail "$file did not arrive intact."
        # The overlay maps the resource names of one exact APK; refuse it for any other build of the target app.
        apk=$(pm path --user 0 "$target" 2>/dev/null | sed -n 's/^package://p' | head -n 1)
        [ -n "$apk" ] && [ "$(digest "$apk")" = "$target_sha" ] || fail "$target here is not the APK $file was built from."
    done
}
place() {
    umask 022
    while [ $# -ge 5 ]; do
        stage=$1 sha=$2 file=$3
        shift 5
        temporary="$dir/.$file.tmp"
        cp "$stage" "$temporary"
        chown 0:0 "$temporary"
        chmod 644 "$temporary"
        chcon "$label" "$temporary"
        mv -f "$temporary" "$dir/$file"
        [ "$(digest "$dir/$file")" = "$sha" ] || fail "$file was not written intact."
        rm -f "$stage"
        { grep -v "^$file " "$record" 2>/dev/null || true; printf '%s %s\n' "$file" "$sha"; } > "$record.new"
        chmod 600 "$record.new"
        mv -f "$record.new" "$record"
        printf 'OVC_OVERLAY_INSTALLED %s\n' "$file"
    done
    sync
}

case "$mode" in
status)
    printf 'OVC_OVERLAY_DIR_WRITABLE=%s\n' "$([ -w "$dir" ] && echo yes || echo no)"
    for path in "$dir/$prefix"*.apk; do
        [ -f "$path" ] || continue
        file=${path##*/}
        package=${file%.apk}
        printf 'OVC_OVERLAY %s sha256=%s state=%s enabled=%s\n' "$file" "$(digest "$path")" \
            "$(field "$package" mState)" "$(field "$package" mIsEnabled)"
    done
    ;;
install)
    [ -d "$dir" ] && [ ! -L "$dir" ] && [ -w "$dir" ] || fail 'Remount /vendor first.'
    verify "$@"
    prepare_state
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
remove)
    [ -d "$dir" ] && [ ! -L "$dir" ] && [ -w "$dir" ] || fail 'Remount /vendor first.'
    for path in "$dir/$prefix"*.apk; do
        [ -f "$path" ] || continue
        file=${path##*/}
        ours "$file" || continue
        package=${file%.apk}
        if [ -n "$(field "$package" mState)" ]; then cmd overlay disable --user 0 "$package" || true; fi
        rm -f "$path"
        printf 'OVC_OVERLAY_REMOVED %s\n' "$file"
    done
    rm -f "$record"
    sync
    echo 'OVC_OVERLAYS_NEED_RESTART'
    ;;
*)
    fail 'Mode: status, install, enable, disable or remove.'
    ;;
esac

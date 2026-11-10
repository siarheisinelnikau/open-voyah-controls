#!/system/bin/sh
set -eu
mode=$1
fingerprint=$2
boot=/system/etc/init.logcat.sh
state=/data/local/tmp/open-voyah-controls-boot
worker=/data/local/tmp/open-voyah-controls/boot-start.sh
marker='# OPEN_VOYAH_CONTROLS_BOOT_V1'

fail() { echo "$*" >&2; exit 1; }
digest() { sha256sum "$1" | awk '{print $1}'; }
[ "$(id -u)" = 0 ] || fail 'A prepared root ADB shell is required.'
[ -n "$fingerprint" ] && [ "$(getprop ro.build.fingerprint)" = "$fingerprint" ] || fail 'Build fingerprint does not match.'
case "$(getprop ro.build.version.sdk)" in 28|30) ;; *) fail 'This boot tool supports Android 9 and 11.' ;; esac
[ -f "$boot" ] && [ ! -L "$boot" ] || fail 'The stock boot script is missing or is a symbolic link.'

if [ "$mode" = status ]; then
    printf 'Boot script SHA-256: %s\n' "$(digest "$boot")"
    if grep -Fxq "$marker" "$boot"; then echo 'Controls boot entry present'; else echo 'Controls boot entry absent'; fi
    exit 0
fi
case "$mode" in install|restore) ;; *) fail 'Choose status, install, or restore.' ;; esac
umask 077
[ ! -L "$state" ] || fail 'The backup path is a symbolic link.'
mkdir -p "$state"
[ "$(stat -c %u "$state")" = 0 ] || fail 'The backup directory must be owned by root.'
chmod 700 "$state"
mkdir "$state/lock" || fail 'Another boot configuration is active.'
stage=
cleanup() {
    if [ -n "$stage" ]; then rm -f "$stage"; fi
    rmdir "$state/lock"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

current=$(digest "$boot")
if [ -f "$state/original.sha256" ]; then
    [ "$(cat "$state/fingerprint")" = "$fingerprint" ] || fail 'The saved backup belongs to a different firmware.'
    original=$(cat "$state/original.sha256")
    [ -n "$original" ] && [ "$(digest "$state/original")" = "$original" ] || fail 'The saved boot backup failed validation.'
else
    [ "$mode" = install ] || fail 'A complete boot backup is unavailable.'
    grep -Fxq "$marker" "$boot" && fail 'The boot entry exists without a verifiable backup.'
    read -r shebang < "$boot"
    [ "$shebang" = '#!/system/bin/sh' ] || fail 'The OEM boot script does not have the expected shell header.'
    cp -p "$boot" "$state/original.new"
    [ "$(digest "$state/original.new")" = "$current" ] || fail 'Could not verify the boot backup.'
    ls -Zd "$boot" | awk '{print $1}' > "$state/context"
    test -s "$state/context"
    printf '%s\n' "$fingerprint" > "$state/fingerprint"
    printf '%s\n' "$current" > "$state/original.sha256.new"
    mv "$state/original.new" "$state/original"
    sync
    mv "$state/original.sha256.new" "$state/original.sha256"
    sync
    original=$current
fi
installed=$(cat "$state/installed.sha256" 2>/dev/null || true)

if [ "$mode" = restore ]; then
    if [ "$current" = "$original" ]; then echo 'Original boot script is active.'; exit 0; fi
    [ "$current" = "$installed" ] || fail 'The boot script changed after installation. Use the saved backup for manual recovery.'
else
    [ -x "$worker" ] || fail 'Connect Frida in Controls to stage its boot worker first.'
    if [ "$current" = "$installed" ]; then echo 'Controls boot entry is already installed.'; exit 0; fi
    [ "$current" = "$original" ] || fail 'The boot script changed since the backup.'
fi

stage=$(mktemp /system/etc/init.logcat.ovc.XXXXXX) || fail 'The system partition must already be writable.'
cp -p "$state/original" "$stage"
if [ "$mode" = install ]; then
    {
        head -n 1 "$state/original"
        printf '%s\n' "$marker"
        printf '%s\n' '( w=/data/local/tmp/open-voyah-controls/boot-start.sh; i=0; while [ ! -x "$w" ] && [ "$i" -lt 40 ]; do sleep 2; i=$((i+1)); done; if [ -x "$w" ]; then /system/bin/sh "$w"; fi ) </dev/null >/dev/null 2>&1 &'
        tail -n +2 "$state/original"
    } > "$stage"
fi
/system/bin/sh -n "$stage" || fail 'The staged boot script failed its syntax check.'
context=$(cat "$state/context")
case "$context" in
    u:object_r:*) chcon "$context" "$stage" ;;
    '?') [ "$(getenforce)" = Disabled ] || fail 'The OEM security label could not be read.' ;;
    *) fail 'The OEM security label could not be validated.' ;;
esac
expected=$(digest "$stage")
if [ "$mode" = install ]; then
    printf '%s\n' "$expected" > "$state/installed.sha256.new"
    mv "$state/installed.sha256.new" "$state/installed.sha256"
else
    [ "$expected" = "$original" ] || fail 'The staged restore failed validation.'
fi
sync
[ "$(digest "$boot")" = "$current" ] || fail 'The boot script changed while preparing the update.'
mv -f "$stage" "$boot"
stage=
sync
[ "$(digest "$boot")" = "$expected" ] || fail 'Boot script verification failed after the replacement.'
echo 'Boot script verified. The change will take effect on the next Android boot.'

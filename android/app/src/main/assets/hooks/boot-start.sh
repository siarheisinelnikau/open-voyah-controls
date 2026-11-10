#!/system/bin/sh
# Start only reviewed workers from the root-owned deployment directory.
set -eu
export PATH=/system/bin:/system/xbin
base=/data/adb/open-voyah-controls
fixture=0
if [ "${OVC_EMULATOR_FIXTURE:-}" = 1 ]; then
    [ "$(getprop ro.kernel.qemu)" = 1 ] && [ "$(getprop ro.debuggable)" = 1 ] || exit 1
    base=/data/local/tmp/open-voyah-controls
    fixture=1
fi

fail() { printf 'OVC_BOOT_ERROR %s\n' "$*" >&2; exit 1; }
private_dir() { [ -d "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %u:%a "$1")" = 0:700 ]; }
digest() { sha256sum "$1" | awk '{print $1}'; }
check_script() {
    file="$base/$1"
    [ -f "$file" ] && [ ! -L "$file" ] && [ "$(stat -c %u:%a "$file")" = 0:700 ] || return 1
    if [ "$fixture" = 0 ]; then
        expected=$(awk -F '\t' -v name="$1" '$1=="artifact" && $2==name { n++; hash=$3 } END { if(n!=1) exit 1; print hash }' "$receipt") ||
            return 1
        [ "${#expected}" = 64 ] && [ "$expected" = "$(digest "$file")" ]
    fi
}

[ "$(id -u)" = 0 ] && private_dir "$base" || fail 'Root-owned private runtime is unavailable.'
if [ "$fixture" = 0 ]; then
    private_dir /data/adb || fail 'Root private directory failed validation.'
    [ "$(getenforce)" = Enforcing ] || fail 'SELinux must be Enforcing.'
    receipt="$base/trust.tsv"
    [ -f "$receipt" ] && [ ! -L "$receipt" ] && [ "$(stat -c %u:%a "$receipt")" = 0:600 ] ||
        fail 'A reviewed deployment receipt is required.'
fi
check_script boot-start.sh && check_script supervise.sh || fail 'Boot worker digest or ownership did not match.'
case "${1:-}" in
    --verify) exec /system/bin/sh "$base/supervise.sh" --verify-boot ;;
    '') ;;
    *) fail 'Choose boot startup or --verify.' ;;
esac
[ ! -e "$base/recovery.blocked" ] && [ ! -L "$base/recovery.blocked" ] || fail 'Recovery block is active.'
[ -f "$base/enabled" ] && [ ! -L "$base/enabled" ] || exit 0

for channel in launcher setting vehicle bluetooth window navigation; do
    case "$channel" in
        launcher) dir=$base; set -- ;;
        window|navigation) dir=$base/$channel; set -- "$channel" ;;
        *) dir=$base/locale-$channel; set -- "$channel" ;;
    esac
    [ -d "$dir" ] && [ ! -L "$dir" ] || continue
    [ -f "$dir/enabled" ] && [ ! -L "$dir/enabled" ] || continue
    nohup /system/bin/sh "$base/supervise.sh" "$@" >/dev/null 2>&1 </dev/null &
done

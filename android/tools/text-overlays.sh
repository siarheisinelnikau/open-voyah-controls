#!/system/bin/sh
# Installs, switches, rolls back and removes this project's text overlays (tools/build_text_overlays.py) over an
# already authorized root shell. Controls and tools/text-overlays.ps1 run the same script.
#
#   status                 what is in /vendor/overlay and what the overlay manager says about it (read-only)
#   install                the set staged in $stage (manifest + APKs, written there by the caller as root)
#   enable|disable [pkg…]  switches overlays the package manager knows, at once
#   rollback               back to the set in place before the last install
#   remove                 deletes this project's overlays
#
# The package manager reads /vendor/overlay only while the head unit starts, so install, rollback and remove take
# effect after one restart. Writes remount /vendor read-write only when needed and read-only again afterwards. A set
# replaces another in two phases: every new file is first written, labelled and checked under a temporary name; only
# then are the old files removed and the new ones renamed. A failure in the first phase changes nothing.
#
# Markers the callers read: OVC_OVERLAY_PREVIOUS_RESTORED / OVC_OVERLAY_RESTORE_FAILED (after a failed install),
# OVC_OVERLAY_PREVIOUS_KEPT (the rollback copy was not replaced), OVC_OVERLAY_VENDOR_STILL_WRITABLE (the read-only
# remount failed), OVC_OVERLAY_INTERRUPTED (an earlier change stopped in the middle).
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
stage=$state/stage
record=$state/installed
previous=$state/previous
boot_file=$root/proc/sys/kernel/random/boot_id
prefix=org.openvoyah.controls.overlay.
label=u:object_r:vendor_overlay_file:s0
remounted=0
dump=

fail() { printf '%s\n' "$*" >&2; exit 1; }
digest() { sha256sum "$1" | awk '{print $1}'; }
ours() {
    case "$1" in "$prefix"*.apk) ;; *) return 1;; esac
    case "${1#"$prefix"}" in ''|.*|*..*|*[!a-z0-9.]*) return 1;; esac
}
# Test hook: OVC_TEST_FAIL="<phase>:<count> ..." makes a phase fail after <count> files (only with OVC_ROOT).
injected() {
    [ -n "$root" ] || return 1
    case " ${OVC_TEST_FAIL:-} " in *" $1:$2 "*) return 0;; esac
    return 1
}
# "dumpsys overlay" is read once per step: on the head unit each call takes a noticeable part of a second.
load_states() { dump=$(dumpsys overlay 2>/dev/null || true); }
# One field of an overlay's entry (empty while the package manager does not know it).
field() {
    printf '%s\n' "$dump" | awk -v head="$1:0 {" -v name="$2" '
        $0 == head { inside = 1; next }
        inside && /^}/ { exit }
        inside && index($1, name ".") == 1 { sub(/^[^:]*: */, ""); print; exit }'
}
recorded() { [ -f "$record" ] && awk '{print $1}' "$record" || true; }
boot_id() { cat "$boot_file" 2>/dev/null || echo unknown; }
# True while a job Controls started in this boot still runs the script (job.pid holds "<pid> <boot id>").
job_alive() {
    [ -f "$state/job.pid" ] && [ ! -f "$state/job.status" ] || return 1
    read -r job_pid job_boot < "$state/job.pid" || return 1
    case "$job_pid" in ''|*[!0-9]*) return 1;; esac
    [ "$job_boot" = "$(boot_id)" ] && kill -0 "$job_pid" 2>/dev/null \
        && cat "$root/proc/$job_pid/cmdline" 2>/dev/null | tr '\0' ' ' | grep -q text-overlays.sh
}
finish() {
    rm -f "$dir"/."$prefix"*.tmp 2>/dev/null || true
    sync
    # Only a remount this script made is undone; a /vendor someone else left writable stays as it was. The read-only
    # remount fails while a deleted overlay is still mapped by a running app; the next start mounts it read-only.
    if [ "$remounted" = 1 ]; then
        if [ -n "$root" ] && [ -n "${OVC_TEST_RO_FAILS:-}" ]; then echo 'OVC_OVERLAY_VENDOR_STILL_WRITABLE'
        else mount -o remount,ro /vendor 2>/dev/null || echo 'OVC_OVERLAY_VENDOR_STILL_WRITABLE'; fi
    fi
}
# /vendor is read-only after a start; on a head unit without dynamic partitions (the 97C) a remount is enough.
writable() {
    [ -d "$dir" ] && [ ! -L "$dir" ] || fail 'There is no /vendor/overlay folder.'
    # Tests cannot make a folder read-only on every filesystem; OVC_TEST_REMOUNT takes the remount path anyway.
    if { [ ! -w "$dir" ] && [ -z "$root" ]; } || { [ -n "$root" ] && [ -n "${OVC_TEST_REMOUNT:-}" ]; }; then
        mount -o remount,rw /vendor 2>/dev/null && remounted=1 || true
    fi
    trap finish EXIT
    [ -w "$dir" ] || fail '/vendor could not be made writable.'
    rm -f "$dir"/."$prefix"*.tmp 2>/dev/null || true
    if [ -f "$record.new" ]; then echo 'OVC_OVERLAY_INTERRUPTED'; rm -f "$record.new"; fi
}
prepare_state() {
    if [ ! -d "$state" ]; then mkdir -m 700 "$state"; fi
    [ ! -L "$state" ] && [ "$(stat -c %u:%a "$state")" = 0:700 ] || fail "Unexpected $state."
}

[ "$(id -u)" = 0 ] || fail 'Run "adb root" first.'
[ "$(getprop ro.build.fingerprint)" = "$fingerprint" ] || fail 'This head unit runs a different firmware build.'
# Controls passes "-": it runs on the unit it changes. The PC names the unit it means.
[ "$serial" = - ] || [ "$(getprop ro.serialno)" = "$serial" ] || fail 'This is a different head unit.'

# Writes one file under its temporary name, owned, labelled and checked the way the package manager expects a
# preinstalled overlay. A failed copy leaves nothing behind.
prepare_one() {
    temporary="$dir/.$2.tmp"
    rm -f "$temporary"
    if cp "$1" "$temporary" && chown 0:0 "$temporary" && chmod 644 "$temporary" && chcon "$label" "$temporary" \
        && [ "$(digest "$temporary")" = "$3" ] && sync; then
        return 0
    fi
    rm -f "$temporary"
    return 1
}
# Disables every overlay of this project and deletes them, except the names listed in $1 ("file sha" rows), which
# the caller replaces in place; other files in the folder are never touched.
clear_ours() {
    load_states
    for path in "$dir/$prefix"*.apk; do
        [ -f "$path" ] || continue
        file=${path##*/}
        ours "$file" || continue
        package=${file%.apk}
        if [ -n "$(field "$package" mState)" ]; then cmd overlay disable --user 0 "$package" >/dev/null 2>&1 || true; fi
        if [ -n "${1:-}" ] && grep -q "^$file " "$1"; then continue; fi
        rm -f "$path" || return 1
        printf 'OVC_OVERLAY_REMOVED %s\n' "$file"
    done
}
# Replaces this project's overlays with the files listed in $2 ("file sha" rows) from folder $1, in two phases.
# Returns 0 when done, 2 when the first phase failed (nothing changed), 1 when the second phase failed.
swap() {
    written=0
    while read -r file sha; do
        if injected "$3-write" "$written" || ! ours "$file" || ! prepare_one "$1/$file" "$file" "$sha"; then
            rm -f "$dir"/."$prefix"*.tmp
            return 2
        fi
        written=$((written + 1))
    done < "$2"
    # A name the new set keeps is replaced by an atomic rename, so it is never missing, even on a power loss.
    clear_ours "$2" || return 1
    moved=0
    while read -r file sha; do
        injected "$3-move" "$moved" && return 1
        mv -f "$dir/.$file.tmp" "$dir/$file" || return 1
        printf 'OVC_OVERLAY_PLACED %s\n' "$file"
        moved=$((moved + 1))
    done < "$2"
    sync
}
# True when the record names exactly the overlays in place, with their SHA-256: only such a set is worth keeping.
consistent() {
    if [ ! -f "$record" ]; then
        for path in "$dir/$prefix"*.apk; do [ -f "$path" ] && return 1; done
        return 0
    fi
    count=0
    while read -r file sha; do
        ours "$file" && [ -f "$dir/$file" ] && [ "$(digest "$dir/$file")" = "$sha" ] || return 1
        count=$((count + 1))
    done < "$record"
    present=0
    for path in "$dir/$prefix"*.apk; do [ -f "$path" ] && present=$((present + 1)); done
    [ "$present" = "$count" ]
}
# Keeps the set in place for "rollback", unless it is a broken one; then the last good copy stays.
snapshot() {
    if ! consistent; then echo 'OVC_OVERLAY_PREVIOUS_KEPT'; return 0; fi
    rm -rf "$previous.new"
    mkdir -m 700 "$previous.new"
    for path in "$dir/$prefix"*.apk; do
        [ -f "$path" ] || continue
        cp "$path" "$previous.new/${path##*/}"
    done
    if [ -f "$record" ]; then cp "$record" "$previous.new/installed"; fi
    touch "$previous.new/complete"
    rm -rf "$previous"
    mv "$previous.new" "$previous"
}
# Puts the kept set back in place. Used by "rollback" and after a failed install; 0, 1 or 2 as for swap.
restore() {
    if [ -f "$previous/installed" ]; then
        swap "$previous" "$previous/installed" restore || return $?
        cp "$previous/installed" "$record" && chmod 600 "$record"
    else
        clear_ours || return 1
        rm -f "$record"
    fi
}
# Reads the staged manifest: "pin <fingerprint> <build date>" and "overlay <sha> <file> <target> <target sha>" rows.
# Writes the rows whose factory app on this unit is the APK the overlay was built from to $1; reports the others.
accepted() {
    [ -f "$stage/manifest" ] && [ ! -L "$stage/manifest" ] || fail 'Nothing is staged.'
    pins=$(awk '$1 == "pin" { print $2 " " $3; exit }' "$stage/manifest")
    [ -n "$pins" ] || fail 'The staged overlays name no firmware build.'
    pin_fingerprint=${pins% *}
    pin_date=${pins##* }
    # An unpinned set ("-") could meet another build of the factory apps; it is never installed.
    [ "$pin_fingerprint" != - ] && [ "$(getprop ro.build.fingerprint)" = "$pin_fingerprint" ] \
        || fail 'These overlays were built for another firmware build.'
    [ "$pin_date" != - ] && [ "$(getprop ro.build.date.utc)" = "$pin_date" ] \
        || fail 'These overlays were built for another firmware build.'
    awk '$1 == "overlay" { print $2, $3, $4, $5 }' "$stage/manifest" > "$1.rows"
    : > "$1"
    # Read from a file, not a pipe: a "fail" inside must end the script, in every shell.
    while read -r sha file target target_sha; do
        ours "$file" || fail "Unexpected overlay name $file."
        case "$sha$target_sha" in *[!0-9a-f]*) fail "Unexpected checksum for $file.";; esac
        [ -f "$stage/$file" ] && [ ! -L "$stage/$file" ] && [ "$(digest "$stage/$file")" = "$sha" ] \
            || fail "$file did not arrive intact."
        apk=$(pm path --user 0 "$target" 2>/dev/null | sed -n 's/^package://p' | head -n 1)
        if [ -z "$apk" ]; then
            printf 'OVC_OVERLAY_SKIPPED %s missing\n' "$file"
        elif [ "$(digest "$apk")" != "$target_sha" ]; then
            # The overlay maps the resource names of one exact APK; it is not placed over another build of the app.
            printf 'OVC_OVERLAY_SKIPPED %s other-build\n' "$file"
        else
            printf '%s %s\n' "$file" "$sha" >> "$1"
        fi
    done < "$1.rows"
    rm -f "$1.rows"
}

case "$mode" in
status)
    load_states
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
    if [ -f "$record" ]; then awk '{print "OVC_OVERLAY_RECORD " $1 " " $2}' "$record"; fi
    if [ -f "$state/installed_boot" ] && [ "$(cat "$state/installed_boot")" = "$(boot_id)" ]; then
        echo 'OVC_OVERLAY_INSTALLED_THIS_BOOT'
    fi
    if [ -f "$previous/complete" ]; then echo 'OVC_OVERLAY_ROLLBACK_AVAILABLE'; fi
    # record.new belongs to a running job until that job ends; only a left-over one is an interruption.
    if [ -f "$record.new" ] && ! job_alive; then echo 'OVC_OVERLAY_INTERRUPTED'; fi
    ;;
install)
    prepare_state
    [ -d "$stage" ] && [ ! -L "$stage" ] && [ "$(stat -c %u:%a "$stage")" = 0:700 ] || fail 'Nothing is staged.'
    accepted "$state/accepted"
    [ -s "$state/accepted" ] || fail 'No factory app on this head unit matches these overlays.'
    need=0
    while read -r file sha; do need=$((need + $(stat -c %s "$stage/$file"))); done < "$state/accepted"
    free=$(df -k "$dir" 2>/dev/null | awk 'NR == 2 { print $4 }')
    # In KiB: Android's mksh computes in 32 bits, and bytes of a large partition overflow.
    case "$free" in ''|*[!0-9]*) ;; *) [ "$free" -gt $((need / 1024 + 1024)) ] || fail 'There is not enough space in /vendor.';; esac
    writable
    snapshot
    # record.new marks a change in progress; it is gone once the record is replaced.
    cp "$state/accepted" "$record.new"
    result=0
    swap "$stage" "$state/accepted" install || result=$?
    if [ "$result" = 2 ]; then
        rm -f "$record.new"
        fail 'The overlays could not be written; the overlays in place are unchanged.'
    elif [ "$result" != 0 ]; then
        # The new set's temporary files would only take space the restore needs.
        rm -f "$dir"/."$prefix"*.tmp 2>/dev/null || true
        if [ -f "$previous/complete" ] && restore; then
            rm -f "$record.new"
            echo 'OVC_OVERLAY_PREVIOUS_RESTORED'
            fail 'Installing the overlays failed; the previous set is back in place.'
        fi
        echo 'OVC_OVERLAY_RESTORE_FAILED'
        fail 'Installing the overlays failed and the previous set could not be put back. Install again or remove them.'
    fi
    chmod 600 "$record.new"
    mv -f "$record.new" "$record"
    boot_id > "$state/installed_boot"
    rm -rf "$stage" "$state/accepted"
    echo 'OVC_OVERLAYS_NEED_RESTART'
    ;;
enable|disable)
    # Optional arguments name the overlay packages to change; without them every recorded overlay changes.
    [ -n "$(recorded)" ] || fail 'No text overlays are installed on this head unit.'
    for package in "$@"; do ours "$package.apk" || fail "Unexpected overlay $package."; done
    # Right after an install or rollback Android still holds the replaced overlays under the same names.
    if [ "$mode" = enable ] && [ -f "$state/installed_boot" ] && [ "$(cat "$state/installed_boot")" = "$(boot_id)" ]; then
        fail 'Restart the head unit first; Android reads the new overlays only while it starts.'
    fi
    load_states
    changed=
    failed=0
    for file in $(recorded); do
        ours "$file" || continue
        package=${file%.apk}
        if [ $# -gt 0 ]; then case " $* " in *" $package "*) ;; *) continue;; esac; fi
        if [ -z "$(field "$package" mState)" ]; then
            printf 'OVC_OVERLAY_UNKNOWN %s\n' "$package"
            failed=1
            continue
        fi
        cmd overlay "$mode" --user 0 "$package" >/dev/null 2>&1 || true
        changed="$changed $package"
    done
    load_states
    expected=$([ "$mode" = enable ] && echo true || echo false)
    for package in $changed; do
        printf 'OVC_OVERLAY %s state=%s enabled=%s\n' "$package" "$(field "$package" mState)" "$(field "$package" mIsEnabled)"
        [ "$(field "$package" mIsEnabled)" = "$expected" ] || failed=1
    done
    [ "$failed" = 0 ] || fail 'Some overlays are not known to the package manager or did not change; restart after install.'
    ;;
rollback)
    [ -f "$previous/complete" ] || fail 'There is no install to roll back.'
    writable
    result=0
    restore || result=$?
    [ "$result" != 2 ] || fail 'The previous overlays could not be written; the overlays in place are unchanged.'
    [ "$result" = 0 ] || { echo 'OVC_OVERLAY_RESTORE_FAILED'; fail 'The previous overlays could not be put back; install again or remove them.'; }
    rm -rf "$previous"
    # Android still holds the swapped overlays under the same names until the next start.
    boot_id > "$state/installed_boot"
    echo 'OVC_OVERLAYS_NEED_RESTART'
    ;;
remove)
    writable
    clear_ours || fail 'The overlays could not be deleted.'
    rm -rf "$record" "$previous" "$stage" "$state/installed_boot"
    echo 'OVC_OVERLAYS_NEED_RESTART'
    ;;
*)
    fail 'Mode: status, install, enable, disable, rollback or remove.'
    ;;
esac

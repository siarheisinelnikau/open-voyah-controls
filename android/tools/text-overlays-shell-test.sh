#!/system/bin/sh
# Runs the critical paths of text-overlays.sh in the shell it is started with (Android's mksh on an emulator, or any
# POSIX sh) against a fake root under $1, with fake system commands. Prints PASS or the failed case and exits non-zero.
#   adb push tools/text-overlays.sh tools/text-overlays-shell-test.sh /data/local/tmp/ovc-shell-test/
#   adb shell sh /data/local/tmp/ovc-shell-test/text-overlays-shell-test.sh /data/local/tmp/ovc-shell-test/root
set -u
here=$(cd "$(dirname "$0")" && pwd)
base=$1
shell=$(command -v sh)
fp='brand/product/device:11/X/1:user/release-keys,test-keys'
date=1720866928
prefix=org.openvoyah.controls.overlay.ru.
failures=0

check() { if eval "$2"; then :; else echo "FAIL: $1"; failures=$((failures + 1)); fi; }
wrapper() { printf '#!%s\n%s\n' "$shell" "$2" > "$base/bin/$1"; chmod 755 "$base/bin/$1"; }
digest() { sha256sum "$1" | awk '{print $1}'; }

setup() {
    rm -rf "$base"
    mkdir -p "$base/vendor/overlay" "$base/data/adb" "$base/system/app" "$base/fake" "$base/bin" "$base/proc/sys/kernel/random"
    echo oem > "$base/vendor/overlay/framework-res.apk"
    echo boot-1 > "$base/proc/sys/kernel/random/boot_id"
    echo one > "$base/system/app/com.example.one.apk"
    echo two > "$base/system/app/com.example.two.apk"
    wrapper id 'echo 0'
    wrapper getprop "case \$1 in ro.build.fingerprint) echo '$fp';; ro.serialno) echo unit;; ro.build.date.utc) echo $date;; esac"
    wrapper chown 'exit 0'
    wrapper chcon 'exit 0'
    real_stat=$(command -v stat)
    wrapper stat "case \"\$2\" in %u:%a) echo 0:700;; %s) exec $real_stat -c %s \"\$3\";; *) echo 0:0:644;; esac"
    wrapper mount 'echo "$*" >> "$OVC_ROOT/fake/mount"'
    wrapper pm 'f="$OVC_ROOT/system/app/$4.apk"; [ -f "$f" ] && echo "package:$f"'
    wrapper dumpsys '[ -f "$OVC_ROOT/fake/overlays" ] || exit 0
while read -r p e; do echo "$p:0 {"; echo "  mState.................: STATE_DISABLED"; echo "  mIsEnabled.............: $e"; echo "}"; done < "$OVC_ROOT/fake/overlays"'
    wrapper cmd 'exit 0'
}
stage() { # stage <version> <short:target>...
    version=$1; shift
    rm -rf "$base/data/adb/open-voyah-controls-overlays/stage"
    mkdir -p "$base/data/adb/open-voyah-controls-overlays/stage"
    echo "pin $fp $date" > "$base/data/adb/open-voyah-controls-overlays/stage/manifest"
    for item in "$@"; do
        short=${item%%:*}; target=${item#*:}
        file=$base/data/adb/open-voyah-controls-overlays/stage/$prefix$short.apk
        echo "$short $version" > "$file"
        echo "overlay $(digest "$file") $prefix$short.apk $target $(digest "$base/system/app/$target.apk")" \
            >> "$base/data/adb/open-voyah-controls-overlays/stage/manifest"
    done
}
run() { # run <env> <mode> [args]
    environment=$1; shift
    eval "env OVC_ROOT='$base' OVC_TEST_PATH='$base/bin:$PATH' $environment '$shell' '$here/text-overlays.sh' '$1' '$fp' unit" 2>&1
}
ours() { ls "$base/vendor/overlay" | grep "^$prefix" | tr '\n' ' '; }
content() { cat "$base/vendor/overlay/$prefix$1.apk" 2>/dev/null; }

setup
stage v1 one:com.example.one two:com.example.two
out=$(run '' install); status=$?
check 'install succeeds' '[ $status = 0 ] && [ "$(content one)" = "one v1" ] && [ "$(content two)" = "two v1" ]'

stage v2 one:com.example.one two:com.example.two
out=$(run 'OVC_TEST_FAIL=install-write:1' install); status=$?
check 'a write failure changes nothing' '[ $status != 0 ] && [ "$(content one)" = "one v1" ] && ! echo "$out" | grep -q OVC_OVERLAY_REMOVED'
check 'no temporary file is left' '! ls -a "$base/vendor/overlay" | grep -q "\.tmp$"'

stage v2 one:com.example.one two:com.example.two
out=$(run 'OVC_TEST_FAIL=install-move:1' install); status=$?
check 'a swap failure restores the set' '[ $status != 0 ] && echo "$out" | grep -q OVC_OVERLAY_PREVIOUS_RESTORED && [ "$(content two)" = "two v1" ]'

stage v2 one:com.example.one two:com.example.two
out=$(run "OVC_TEST_FAIL='install-move:1 restore-write:0'" install); status=$?
check 'a failed restore says so' '[ $status != 0 ] && echo "$out" | grep -q OVC_OVERLAY_RESTORE_FAILED && ! echo "$out" | grep -q "back in place"'
out=$(run '' rollback); status=$?
check 'rollback recovers after a failed restore' '[ $status = 0 ] && [ "$(content one)" = "one v1" ]'
check 'rollback keeps the restart pending' 'run "" status | grep -q OVC_OVERLAY_INSTALLED_THIS_BOOT'

stage v3 one:com.example.one
echo damaged > "$base/data/adb/open-voyah-controls-overlays/stage/${prefix}one.apk"
out=$(run '' install); status=$?
check 'a fail inside the manifest loop ends the script' '[ $status != 0 ] && echo "$out" | grep -q "did not arrive intact" && [ "$(content one)" = "one v1" ]'

stage v3 one:com.example.one
out=$(run 'OVC_TEST_REMOUNT=1' install); status=$?
check 'the remount is undone on exit' '[ $status = 0 ] && [ "$(tail -n 1 "$base/fake/mount")" = "-o remount,ro /vendor" ]'
out=$(run 'OVC_TEST_REMOUNT=1' rollback-unknown); status=$?
check 'an unknown mode fails' '[ $status != 0 ]'

# A change in progress (record.new) is an interruption only once its job is gone.
state=$base/data/adb/open-voyah-controls-overlays
touch "$state/installed.new"
sh -c 'sleep 5; true' text-overlays.sh &
job=$!
echo "$job boot-1" > "$state/job.pid"
mkdir -p "$base/proc/$job"
cat "/proc/$job/cmdline" > "$base/proc/$job/cmdline" 2>/dev/null || printf 'sh\0text-overlays.sh\0' > "$base/proc/$job/cmdline"
out=$(run '' status)
check 'a running job is not an interruption' '! echo "$out" | grep -q OVC_OVERLAY_INTERRUPTED'
kill "$job" 2>/dev/null; wait "$job" 2>/dev/null
out=$(run '' status)
check 'a left-over change is an interruption' 'echo "$out" | grep -q OVC_OVERLAY_INTERRUPTED'

rm -rf "$base"
if [ "$failures" = 0 ]; then echo PASS; exit 0; fi
exit 1

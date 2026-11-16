#!/system/bin/sh
# Login gate for the private loopback Telnet service. Never prints the installation key.
set -eu
export PATH=/system/bin:/system/xbin
unset ENV BASH_ENV LD_PRELOAD LD_LIBRARY_PATH
umask 077
base=/data/adb/open-voyah-controls-access
file() { [ -f "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %u:%a:%h "$1")" = "0:$2:1" ]; }
[ "$(id -u)" = 0 ] && [ "$(getenforce)" = Enforcing ] || exit 1
[ ! -e "$base/disabled" ] && [ ! -L "$base/disabled" ] || exit 1
file "$base/key" 600 && file "$base/fingerprint" 600 && file "$base/uid" 600 || exit 1
[ "$(getprop ro.build.fingerprint)" = "$(cat "$base/fingerprint")" ] || exit 1
uid=$(cat "$base/uid")
case "$uid" in ''|*[!0-9]*) exit 1;; esac
[ "$uid" -ge 10000 ] && [ "$uid" -lt 100000 ] || exit 1
actual_uid=$(pm list packages -U --user 0 org.openvoyah.controls | sed -n 's/^package:org.openvoyah.controls uid:\([0-9]*\)$/\1/p')
[ "$actual_uid" = "$uid" ] || exit 1
key=$(cat "$base/key")
case "$key" in *[!a-f0-9]*|'') exit 1;; esac
[ "${#key}" = 64 ] || exit 1
stty -echo -iuclc -olcuc -istrip 2>/dev/null || exit 1
printf 'OVC_ACCESS_1\n'
# Android mksh counts the delimiter in bounded reads; Bash fixtures exclude it.
newline='
'
IFS= read -r -n 33 -t 5 client || exit 1
client=${client%"$newline"}
case "$client" in *[!a-f0-9]*|'') exit 1;; esac
[ "${#client}" = 32 ] || exit 1
server=$(tr -d '-' < /proc/sys/kernel/random/uuid)
case "$server" in *[!a-f0-9]*|'') exit 1;; esac
[ "${#server}" = 32 ] || exit 1
proof=$(printf 'OVC_ROOT_SERVER_V1:%s:%s' "$key" "$client" | sha256sum | awk '{print $1}')
printf 'OVC_SERVER %s %s\n' "$server" "$proof"
IFS= read -r -n 65 -t 5 response || exit 1
response=${response%"$newline"}
case "$response" in *[!a-f0-9]*|'') exit 1;; esac
[ "${#response}" = 64 ] || exit 1
expected=$(printf 'OVC_ROOT_CLIENT_V1:%s:%s:%s' "$key" "$client" "$server" | sha256sum | awk '{print $1}')
[ "$response" = "$expected" ] || exit 1
unset key proof response expected client server
# Commands include large heredocs. Canonical TTY input would silently truncate long lines.
stty -echo -icanon min 1 time 0 -ixon -ixoff -iuclc -olcuc -istrip 2>/dev/null || exit 1
printf 'OVC_AUTHENTICATED\n'
# A pipe forces noninteractive mksh on both supported Android generations.
# Older mksh can wait for more input when a script is read directly from a PTY.
exec /system/bin/sh -c 'cat | /system/bin/sh'

#!/system/bin/sh
# One bounded supervisor for each independently enabled target.
export PATH=/system/bin:/system/xbin
umask 077
root=/data/adb/open-voyah-controls
runtime=open-voyah-controls-frida-17.26.2
runtime_sha=b58f44918fc27843484fc62502b3d2c90ea686a85ed484bed062b23482cd1733
injector="$root/$runtime"
fixture=0
if [ "${OVC_EMULATOR_FIXTURE:-}" = 1 ]; then
    [ "$(getprop ro.kernel.qemu)" = 1 ] && [ "$(getprop ro.debuggable)" = 1 ] || exit 1
    root=/data/local/tmp/open-voyah-controls
    injector="/data/local/tmp/$runtime"
    fixture=1
fi
channel=${1:-}
package=com.qinggan.app.launcher
pattern='^OVC_(READY|VOICE_READY|STEERING_READY) '
relative=launcher-buttons.js
case "$channel" in
    '') ;;
    setting) package=com.qinggan.app.setting; relative=locale-setting/launcher-buttons.js ;;
    vehicle) package=com.qinggan.app.vehicle; relative=locale-vehicle/launcher-buttons.js ;;
    bluetooth) package=com.qinggan.bluetoothphone; relative=locale-bluetooth/launcher-buttons.js ;;
    hiboard) package=com.qinggan.app.hiboard; relative=locale-hiboard/launcher-buttons.js ;;
    window) package=android; relative=window/launcher-buttons.js; pattern='^OVC_WINDOW_READY protocol=3 ' ;;
    navigation) package=com.qinggan.cluster; relative=navigation/launcher-buttons.js; pattern='^OVC_NAVIGATION_READY' ;;
    can) package=com.qinggan.canbus.service; relative=can/launcher-buttons.js; pattern='^OVC_CAN_READY protocol=2 terminal edges ' ;;
    apollo) package=com.qinggan.app.vehiclesetting; relative=apollo/launcher-buttons.js; pattern='^OVC_APOLLO_READY' ;;
    --verify-boot|--recover|--visual-session-state|--visual-session-clear-restored|--visual-session-clear-changed) ;;
    *) exit 2 ;;
esac
# Translation targets are ordinary apps: Android starts them on demand and kills them when they are idle.
locale=0
case "$channel" in setting|vehicle|bluetooth|hiboard) pattern='^OVC_LOCALIZATION_READY'; locale=1;; esac
target=$package
if [ "$channel" = window ]; then target=system_server; fi
script="$root/$relative"
dir=${script%/*}
receipt="$root/trust.tsv"
packages_xml=/data/system/packages.xml
app=org.openvoyah.controls
app_key=/data/user/0/$app/files/root-channel.key
access=/data/adb/open-voyah-controls-access

digest() { sha256sum "$1" | awk '{print $1}'; }
private_dir() { [ -d "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %u:%a "$1")" = 0:700 ]; }
private_file() { [ -f "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %u:%a "$1")" = "0:$2" ]; }
root_file() {
    [ -f "$1" ] && [ ! -L "$1" ] || return 1
    case "$(stat -c %u:%a "$1")" in 0:400|0:500|0:600|0:700|0:644|0:755) return 0;; esac
    return 1
}
is_number() { case "$1" in ''|*[!0-9]*) return 1;; esac; }
pid_for() { pidof "$1" 2>/dev/null | awk '{ for (i=1; i<=NF; i++) { count++; pid=$i } } END { if(count==1 && pid~/^[1-9][0-9]*$/) print pid }'; }
start_of() {
    line=$(cat "/proc/$1/stat" 2>/dev/null) || return 1
    rest=${line##*) }
    printf '%s\n' "$rest" | awk 'NF>=20 && $1!="Z" && $1!="X" {print $20}'
}
file_identity() { stat -c '%d:%i:%s:%Y:%Z:%u:%a' "$1" 2>/dev/null; }
package_path() {
    value=$(pm path --user 0 "$1" 2>/dev/null) || return 1
    case "$value" in
        package:/*.apk) printf '%s\n' "${value#package:}" ;;
        *) return 1 ;;
    esac
}
record() {
    awk -F '\t' -v type="$1" -v name="$2" -v col="$3" '
        $1==type && $2==name { n++; value=$col }
        END { if(n!=1 || value=="") exit 1; print value }
    ' "$receipt"
}
reviewed_receipt() {
    private_dir /data/adb && private_file "$receipt" 600 &&
        { [ "$(getenforce)" = Enforcing ] || { [ "$(getenforce)" = Permissive ] && tr ' ' '\n' < /proc/cmdline | grep -qx androidboot.selinux=permissive; }; } || return 1
    rr_bytes=$(wc -c < "$receipt") || return 1
    [ "$rr_bytes" -le 16384 ] &&
        [ "$(LC_ALL=C tr -d '\000' < "$receipt" | wc -c)" = "$rr_bytes" ] || return 1
    rr_fp=$(getprop ro.build.fingerprint); rr_sdk=$(getprop ro.build.version.sdk)
    # Some OEM frameworks report ro.build.product_3rdparty as Build.PRODUCT, which the receipt records.
    rr_abi=$(getprop ro.product.cpu.abi); rr_product=$(getprop ro.build.product_3rdparty)
    [ -n "$rr_product" ] || rr_product=$(getprop ro.product.name)
    [ -n "$rr_fp" ] && [ -n "$rr_product" ] && [ "$rr_abi" = arm64-v8a ] || return 1
    case "$rr_sdk" in 28|30) ;; *) return 1;; esac
    rr_sha() {
        [ "${#1}" = 64 ] || return 1
        case "$1" in *[!a-f0-9]*) return 1;; esac
    }
    rr_stamp() {
        rr_stamp_rest=$1
        set --
        while case "$rr_stamp_rest" in *:*) true;; *) false;; esac; do
            set -- "$@" "${rr_stamp_rest%%:*}"
            [ "$#" -le 4 ] || return 1
            rr_stamp_rest=${rr_stamp_rest#*:}
        done
        set -- "$@" "$rr_stamp_rest"
        [ "$#" = 5 ] || return 1
        for rr_component do case "$rr_component" in ''|*[!0-9]*) return 1;; esac; done
    }
    rr_tab=$(printf '\t')
    rr_device=0; rr_lifetime=0; rr_signer=0; rr_packages='|'; rr_artifacts='|'
    # Split each tab explicitly: shell whitespace splitting would silently drop empty fields.
    while IFS= read -r rr_line || [ -n "$rr_line" ]; do
        set --
        while case "$rr_line" in *"$rr_tab"*) true;; *) false;; esac; do
            set -- "$@" "${rr_line%%"$rr_tab"*}"
            [ "$#" -le 4 ] || return 1
            rr_line=${rr_line#*"$rr_tab"}
        done
        set -- "$@" "$rr_line"
        case "$1" in
            device)
                [ "$#" = 5 ] && [ "$rr_device" = 0 ] && [ "$2" = "$rr_fp" ] &&
                    [ "$3" = "$rr_sdk" ] && [ "$4" = "$rr_abi" ] && [ "$5" = "$rr_product" ] || return 1
                rr_device=1;;
            package)
                [ "$#" = 4 ] || return 1
                case "$2" in [A-Za-z]*) ;; *) return 1;; esac
                case "$2" in *[!A-Za-z0-9_.]*) return 1;; esac
                case "$rr_packages" in *"|$2|"*) return 1;; esac
                case "$3" in /*.apk) ;; *) return 1;; esac
                rr_apk=${3#/}; rr_apk=${rr_apk%.apk}
                [ -n "$rr_apk" ] || return 1
                case "$3" in *[!A-Za-z0-9_.~+=/-]*|*/./*|*/../*) return 1;; esac
                rr_sha "$4" || return 1
                rr_packages="$rr_packages$2|";;
            artifact)
                [ "$#" = 3 ] || return 1
                case "$2" in
                    boot-start.sh|supervise.sh|open-voyah-controls-frida-17.26.2|launcher-buttons.js|\
                    locale-setting/launcher-buttons.js|locale-vehicle/launcher-buttons.js|locale-bluetooth/launcher-buttons.js|\
                    locale-hiboard/launcher-buttons.js|\
                    window/launcher-buttons.js|navigation/launcher-buttons.js|can/launcher-buttons.js|\
                    apollo/launcher-buttons.js) ;;
                    *) return 1;;
                esac
                case "$rr_artifacts" in *"|$2|"*) return 1;; esac
                rr_sha "$3" || return 1
                rr_artifacts="$rr_artifacts$2|";;
            lifetime)
                [ "$#" = 5 ] && [ "$2" = org.openvoyah.controls ] && [ "$rr_lifetime" = 0 ] || return 1
                case "$3" in [1-9][0-9][0-9][0-9][0-9]) ;; *) return 1;; esac
                rr_stamp "$4" && rr_sha "$5" || return 1
                rr_lifetime=1;;
            signer)
                # Controls is pinned by its signing certificate and a version floor, so a same-signer update keeps
                # the activation (see signed_package). Only the app itself; OEM packages keep their exact APK hash.
                [ "$#" = 4 ] && [ "$2" = org.openvoyah.controls ] && [ "$rr_signer" = 0 ] || return 1
                rr_sha "$3" || return 1
                case "$4" in [1-9]|[1-9][0-9]|[1-9][0-9][0-9]|[1-9][0-9][0-9][0-9]|[1-9][0-9][0-9][0-9][0-9]|\
                    [1-9][0-9][0-9][0-9][0-9][0-9]|[1-9][0-9][0-9][0-9][0-9][0-9][0-9]|\
                    [1-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]|[1-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]) ;;
                    *) return 1;;
                esac
                rr_signer=1;;
            *) return 1;;
        esac
    done < "$receipt"
    [ "$rr_device" = 1 ] || return 1
    for rr_required in org.openvoyah.controls com.qinggan.app.launcher; do
        case "$rr_packages" in *"|$rr_required|"*) ;; *) return 1;; esac
    done
    for rr_required in boot-start.sh supervise.sh open-voyah-controls-frida-17.26.2 launcher-buttons.js; do
        case "$rr_artifacts" in *"|$rr_required|"*) ;; *) return 1;; esac
    done
}
user_unlocked() {
    [ "$(getprop sys.user.0.ce_available)" = true ] || return 1
    dumpsys user 2>/dev/null | awk '
        /^[ \t]*UserInfo\{/ { owner=($0 ~ /^[ \t]*UserInfo\{0:/) }
        owner && /^[ \t]*State:/ { if (NF==2 && $2=="RUNNING_UNLOCKED") unlocked++; owner=0 }
        END { exit unlocked!=1 }
    '
}
lifetime_status() {
    # Credential-encrypted files are not evidence of revocation until user 0 is unlocked.
    user_unlocked || return 2
    lifetime_uid=$(record lifetime "$app" 3) && lifetime_stamp=$(record lifetime "$app" 4) &&
        lifetime_hash=$(record lifetime "$app" 5) || return 3
    # A package manager that does not answer (busy at boot, or restarting with system_server) says nothing about the
    # app. An uninstalled app is a successful, empty answer, which still fails the uid check below.
    lifetime_packages=$(pm list packages -U --user 0 "$app" 2>/dev/null) || return 2
    current_uid=$(printf '%s\n' "$lifetime_packages" | sed -n "s/^package:$app uid:\([0-9]*\)$/\1/p")
    [ "$current_uid" = "$lifetime_uid" ] && [ ! -L "$app_key" ] && [ -f "$app_key" ] &&
        [ "$(stat -c '%u:%a:%h' "$app_key")" = "$lifetime_uid:600:1" ] &&
        [ "$(wc -c < "$app_key")" = 65 ] &&
        [ "$(stat -c '%d:%i:%s:%Y:%Z' "$app_key")" = "$lifetime_stamp" ] &&
        [ "$(digest "$app_key")" = "$lifetime_hash" ] &&
        [ "$(stat -c '%d:%i:%s:%Y:%Z' "$app_key")" = "$lifetime_stamp" ] || return 1
    private_dir "$access" && private_file "$access/key" 600 && private_file "$access/uid" 600 &&
        [ "$(stat -c %h "$access/key")" = 1 ] && [ "$(stat -c %h "$access/uid")" = 1 ] &&
        [ "$(cat "$access/uid")" = "$lifetime_uid" ] && [ "$(digest "$access/key")" = "$lifetime_hash" ]
}
artifact_ok() {
    expected=$(record artifact "$1" 3) || return 1
    root_file "$root/$1" && [ "$(digest "$root/$1")" = "$expected" ]
}
# "<codePath> <versionCode> <certificate hex>" of package $1 as Android's package manager recorded it after verifying
# the APK signature. Exactly one current signer; certificates listed under pastSigs (key rotation) are not current.
# A cert element names its key once in the file and refers to it by index elsewhere. 0 found, 1 unsafe or malformed,
# 2 not readable now: the package manager rewrites the file by moving it to packages-backup.xml and writing it anew.
package_signer() {
    if [ -e "${packages_xml%.xml}-backup.xml" ] || [ ! -e "$packages_xml" ]; then return 2; fi
    [ -f "$packages_xml" ] && [ ! -L "$packages_xml" ] || return 1
    case "$(stat -c %u "$packages_xml" 2>/dev/null)" in 0|1000) ;; *) return 1;; esac
    awk -v pkg="$1" '
        function attr(line, name,   at, rest, end) {
            at = index(line, " " name "=\""); if (!at) return ""
            rest = substr(line, at + length(name) + 3); end = index(rest, "\"")
            return end ? substr(rest, 1, end - 1) : ""
        }
        /<cert / { i = attr($0, "index"); k = attr($0, "key"); if (i != "" && k != "" && !(i in keys)) keys[i] = k }
        /^[ \t]*<package / && attr($0, "name") == pkg { inside = 1; seen++; code = attr($0, "codePath"); version = attr($0, "version") }
        inside && /<pastSigs/ { past = 1 }
        inside && !past && /<cert / { certs++; want = attr($0, "index") }
        inside && /<\/pastSigs>/ { past = 0 }
        inside && /<\/package>/ { inside = 0; past = 0 }
        /<\/packages>/ { complete = 1 }
        END {
            if (!complete || !seen) exit 2
            if (seen != 1 || certs != 1 || want == "" || !(want in keys) || code == "" || version == "") exit 1
            print code " " version " " keys[want]
        }
    ' "$packages_xml"
}
# Whether the APK at $2 of package $1 matches the receipt's signer row: the same signing certificate and a version
# no older than the one sealed. 0 yes, 1 no (a foreign signer or a downgrade), 2 not settled yet: right after an
# install the package manager writes its settings file a few seconds later, so the file still describes the APK it
# replaced. The uid and the app's private key are checked separately (lifetime_status), which catches a reinstall.
signed_package() {
    sp_signer=$(record signer "$1" 3) && sp_floor=$(record signer "$1" 4) || return 1
    sp_record=$(package_signer "$1") || return $?
    sp_code=${sp_record%% *}; sp_rest=${sp_record#* }
    sp_version=${sp_rest%% *}; sp_cert=${sp_rest#* }
    [ "$sp_code" = "${2%/*}" ] || return 2
    case "$sp_version" in [1-9]|[1-9][0-9]|[1-9][0-9][0-9]|[1-9][0-9][0-9][0-9]|[1-9][0-9][0-9][0-9][0-9]|\
        [1-9][0-9][0-9][0-9][0-9][0-9]|[1-9][0-9][0-9][0-9][0-9][0-9][0-9]|\
        [1-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]|[1-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]) ;;
        *) return 1;;
    esac
    [ "$sp_version" -ge "$sp_floor" ] || return 1
    case "$sp_cert" in ''|*[!0-9a-f]*) return 1;; esac
    [ "${#sp_cert}" -le 32768 ] && [ $(( ${#sp_cert} % 2 )) = 0 ] || return 1
    sp_digest=$(printf '%s' "$sp_cert" | xxd -r -p | sha256sum | awk '{print $1}') || return 1
    [ "$sp_digest" = "$sp_signer" ]
}
reviewed_package() {
    if record signer "$1" 3 >/dev/null 2>&1; then
        installed=$(package_path "$1") || return 1
        [ -f "$installed" ] && [ ! -L "$installed" ] || return 1
        signed_package "$1" "$installed" || return $?
        printf '%s\n' "$installed"
        return 0
    fi
    declared=$(record package "$1" 3) && expected=$(record package "$1" 4) || return 1
    installed=$(package_path "$1") || return 1
    [ "$declared" = "$installed" ] && [ -f "$installed" ] && [ ! -L "$installed" ] &&
        [ "$(digest "$installed")" = "$expected" ] || return 1
    printf '%s\n' "$installed"
}
# Some toybox grep builds stop at the first NUL of a command line unless -z makes NUL the line end. Toybox tr
# never returns when a process exits between opening and reading its command line, which hung the launcher
# worker at boot; grep and head simply fail on such a file. So command lines are read only by grep or head.
cmdline_matches() {
    if printf 'a\000b\000' | grep -qFz -e b 2>/dev/null; then
        grep -aFlz "$@" /proc/[0-9]*/cmdline 2>/dev/null || true
        return
    fi
    for cm_file in /proc/[0-9]*/cmdline; do
        if cmdline_text "$cm_file" | grep -qF "$@"; then printf '%s\n' "$cm_file"; fi
    done
}
cmdline_text() { head -c 4096 "$1" 2>/dev/null | tr '\000' ' '; }
legacy_inert() {
    old=/data/local/tmp/open-voyah-controls
    for file in "/data/local/tmp/$runtime" /data/local/tmp/open-voyah-controls-frida-17.18.0 "$old/boot-start.sh" "$old/supervise.sh" \
        "$old/enabled" "$old/launcher-buttons.js" "$old/localization.js"; do
        if [ -e "$file" ] || [ -L "$file" ]; then return 1; fi
    done
    for old_channel in locale-setting locale-vehicle locale-bluetooth window navigation can; do
        for file in "$old/$old_channel/enabled" "$old/$old_channel/launcher-buttons.js"; do
            if [ -e "$file" ] || [ -L "$file" ]; then return 1; fi
        done
    done
    candidates=$(cmdline_matches -e "$old/supervise.sh" -e '/data/local/tmp/open-voyah-controls-frida-')
    for proc in $candidates; do
        args=$(cmdline_text "$proc")
        case "$args" in "/system/bin/sh $old/supervise.sh "*|/data/local/tmp/open-voyah-controls-frida-*' -p '*) return 1;; esac
    done
}
check_runtime() {
    [ "$(id -u)" = 0 ] && private_dir "$root" && private_dir "$dir" || return 1
    if [ "$fixture" = 1 ]; then
        app_path=$(package_path "$app") || return 1
        package_file=
        [ -f "$app_path" ] && [ -x "$injector" ] && [ -f "$script" ] || return 1
    else
        [ "$(am get-current-user 2>/dev/null)" = 0 ] && legacy_inert && reviewed_receipt &&
            private_file "$injector" 500 && [ "$(record artifact "$runtime" 3)" = "$runtime_sha" ] &&
            artifact_ok "$runtime" &&
            private_file "$root/boot-start.sh" 700 && artifact_ok boot-start.sh &&
            private_file "$root/supervise.sh" 700 && artifact_ok supervise.sh && artifact_ok "$relative" ||
            return 1
        # 2: the package manager has not yet recorded a fresh install of Controls; wait, it is no evidence of a change.
        app_path=$(reviewed_package "$app") || return $?
        package_file=$(reviewed_package "$package") || return 1
        lifetime_status || return $?
    fi
    app_identity=$(file_identity "$app_path") && injector_identity=$(file_identity "$injector") &&
        script_identity=$(file_identity "$script") || return 1
    package_identity=
    if [ -n "$package_file" ]; then package_identity=$(file_identity "$package_file") || return 1; fi
    receipt_identity=
    if [ "$fixture" = 0 ]; then receipt_identity=$(file_identity "$receipt") || return 1; fi
}
runtime_unchanged() {
    [ "$injector_identity" = "$(file_identity "$injector")" ] &&
        [ "$script_identity" = "$(file_identity "$script")" ] &&
        { [ -z "$package_file" ] || [ "$package_identity" = "$(file_identity "$package_file")" ]; }
}
packages_unchanged() {
    { [ "$fixture" = 1 ] || [ "$(am get-current-user 2>/dev/null)" = 0 ]; } &&
        { [ -z "$package_file" ] || [ "$(package_path "$package")" = "$package_file" ]; }
}
# The APK file every two seconds (a stat), the package manager's path every ten (pm is a slow process to start).
app_unchanged() {
    [ "$app_identity" = "$(file_identity "$app_path")" ] || return 1
    [ $((elapsed%10)) -ne 0 ] || [ "$(package_path "$app")" = "$app_path" ]
}
# An update of Controls itself (adb install -r, the Windows installer, an in-car update) replaces its APK while the
# agents run. With a signer row in the receipt the new APK is accepted when Android recorded the same signing
# certificate and no older version, and the uid and the app's private key are unchanged (the activation lifetime).
# The agents keep running: they are this deployment's sealed files, not part of the APK. The new app compares them
# with its own assets when it starts and redeploys through the root channel if they differ, which reseals the receipt.
# Without a signer row (a receipt sealed by an older app), any change of the APK still revokes. 0 adopted, 1 not.
adopt_app_update() {
    [ "$fixture" = 0 ] && record signer "$app" 3 >/dev/null 2>&1 || return 1
    au_waited=0
    while :; do
        au_code=2
        if au_path=$(package_path "$app") && [ -f "$au_path" ] && [ ! -L "$au_path" ]; then
            signed_package "$app" "$au_path"
            au_code=$?
            if [ "$au_code" = 0 ]; then
                settled lifetime_status
                au_code=$?
                if [ "$au_code" = 0 ]; then
                    au_identity=$(file_identity "$au_path") || return 1
                    [ "$(package_path "$app")" = "$au_path" ] || au_code=2
                fi
            fi
        fi
        case "$au_code" in 0) break;; 2) ;; *) return 1;; esac
        [ "$au_waited" -lt "$update_wait" ] || return 1
        sleep 2
        au_waited=$((au_waited+2))
    done
    app_path=$au_path; app_identity=$au_identity
    log 'OVC_WORKER_NOTICE Controls was updated by the same signer. Activation kept.'
}
# A single failed package-manager, user-state or file query under boot load is not evidence of a change: on boot 211
# one such sample revoked the whole deployment 30 s after boot. A real change persists, so a check that decides a
# revocation runs again until it passes or has failed on every sample, about six seconds apart in total. 0 (healthy)
# and 2 (user storage locked or package manager silent: wait) end the check at once.
settle_samples=3
settle_delay=3
# How long a replaced Controls APK may stay unrecorded by the package manager before it counts as a change.
update_wait=60
settled() {
    st_sample=1
    while :; do
        "$@"
        st_code=$?
        case "$st_code" in 0|2) return "$st_code";; esac
        [ "$st_sample" -lt "$settle_samples" ] || return "$st_code"
        st_sample=$((st_sample+1))
        sleep "$settle_delay"
    done
}
enabled() {
    [ -f "$root/enabled" ] && [ ! -L "$root/enabled" ] &&
        [ -f "$dir/enabled" ] && [ ! -L "$dir/enabled" ]
}
may_start() {
    enabled && [ ! -e "$root/recovery.blocked" ] && [ ! -L "$root/recovery.blocked" ] &&
        [ ! -e "$root/recovery.visual-pending" ] && [ ! -L "$root/recovery.visual-pending" ] &&
        [ ! -e "$root/update.pending" ] && [ ! -L "$root/update.pending" ]
}
recovery_required=0
recovery_recorded=0
# Channel names in recovery.reason; the main launcher channel is "main".
channel_suffix() {
    case "$1" in
        main) echo '';;
        locale-setting|locale-vehicle|locale-bluetooth|locale-hiboard|window|navigation|can|apollo) echo "/$1";;
        *) return 1;;
    esac
}
enabled_channels() {
    ec_list=
    for ec_name in main locale-setting locale-vehicle locale-bluetooth locale-hiboard window navigation can apollo; do
        ec_suffix=$(channel_suffix "$ec_name")
        if [ -f "$root$ec_suffix/enabled" ] && [ ! -L "$root$ec_suffix/enabled" ]; then ec_list="${ec_list:+$ec_list,}$ec_name"; fi
    done
    printf '%s\n' "${ec_list:--}"
}
# recovery.reason says why this boot revoked activation and which channels were enabled, so OVC can re-arm without
# the PC when the deployment still verifies (see --recover). The first worker to revoke names the cause; workers that
# follow in the same boot keep it.
record_recovery_reason() {
    rr_path="$root/recovery.reason"
    [ ! -L "$rr_path" ] || return 1
    if [ -e "$rr_path" ]; then
        private_file "$rr_path" 600 || return 1
        rr_prior=$(cat "$rr_path" 2>/dev/null) || return 1
        [ "${rr_prior%% *}" != "$recovery_boot" ] || return 0
    fi
    rr_stage=$(mktemp "$root/.recovery-reason.XXXXXXXX") || return 1
    if ! { printf '%s %s %s\n' "$recovery_boot" "$1" "$(enabled_channels)" > "$rr_stage" && chmod 600 "$rr_stage" &&
        sync && mv -f "$rr_stage" "$rr_path" && sync; }; then
        rm -f "$rr_stage"
        return 1
    fi
}
revoke_activation() {
    [ "$fixture" = 0 ] || return 0
    recovery_required=1
    private_dir /data/adb && private_dir "$root" || return 1
    recovery_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null) || return 1
    case "$recovery_boot" in ''|*[!a-f0-9-]*) return 1;; esac
    case "${1:-}" in lifetime|deployment|package|runtime|receipt|visual|user) revoke_reason=$1;; *) revoke_reason=unknown;; esac
    # Diagnostic only: a lost reason leaves the markers in force and recovery to the owner or the PC.
    record_recovery_reason "$revoke_reason" || log 'OVC_WORKER_ERROR Recovery reason could not be recorded.'
    for marker in recovery.visual-pending recovery.blocked; do
        [ ! -L "$root/$marker" ] || return 1
        if [ -e "$root/$marker" ]; then private_file "$root/$marker" 600 || return 1; fi
        recovery_stage=$(mktemp "$root/.lifetime-recovery.XXXXXXXX") || return 1
        if ! { printf '%s\n' "$recovery_boot" > "$recovery_stage" && chmod 600 "$recovery_stage" &&
            sync && mv -f "$recovery_stage" "$root/$marker" && sync; }; then
            rm -f "$recovery_stage"
            return 1
        fi
    done
    recovery_recorded=1
    log "OVC_WORKER_NOTICE Activation revoked reason=$revoke_reason."
    # Close every known channel only after durable recovery evidence exists.
    for suffix in '' /locale-setting /locale-vehicle /locale-bluetooth /locale-hiboard /window /navigation /can /apollo; do
        recovery_dir="$root$suffix"
        if [ -e "$recovery_dir" ] || [ -L "$recovery_dir" ]; then
            private_dir "$recovery_dir" && rm -f "$recovery_dir/enabled" || return 1
        fi
    done
}
visual_uuid() {
    case "$1" in ????????-????-????-????-????????????) ;; *) return 1;; esac
    visual_compact=$(printf '%s' "$1" | tr -d '-') || return 1
    [ "${#visual_compact}" = 32 ] || return 1
    case "$visual_compact" in *[!a-f0-9]*) return 1;; esac
}
visual_pid_valid() {
    case "$1" in ''|0*|1|*[!0-9]*) return 1;; esac
    [ "${#1}" -le 10 ] || return 1
    [ "${#1}" = 10 ] || return 0
    visual_pid_value=$1
    visual_pid_limit=2147483647
    while [ -n "$visual_pid_value" ]; do
        visual_pid_digit=${visual_pid_value%"${visual_pid_value#?}"}
        visual_pid_max_digit=${visual_pid_limit%"${visual_pid_limit#?}"}
        [ "$visual_pid_digit" -lt "$visual_pid_max_digit" ] && return 0
        [ "$visual_pid_digit" -gt "$visual_pid_max_digit" ] && return 1
        visual_pid_value=${visual_pid_value#?}
        visual_pid_limit=${visual_pid_limit#?}
    done
    return 0
}
visual_session_state() {
    visual_path="$dir/visual.session"
    if [ ! -e "$visual_path" ] && [ ! -L "$visual_path" ]; then echo absent; return; fi
    if ! private_file "$visual_path" 600 || [ "$(stat -c %h "$visual_path" 2>/dev/null)" != 1 ]; then echo uncertain; return; fi
    visual_size=$(stat -c %s "$visual_path" 2>/dev/null) || { echo uncertain; return; }
    if ! is_number "$visual_size" || [ "$visual_size" -gt 128 ]; then echo uncertain; return; fi
    visual_stamp=$(file_identity "$visual_path") || { echo uncertain; return; }
    visual_record=$(cat "$visual_path" 2>/dev/null) || { echo uncertain; return; }
    visual_bytes=$(wc -c < "$visual_path") || { echo uncertain; return; }
    if [ "$visual_bytes" -ne "$(( ${#visual_record}+1 ))" ] || [ "$visual_stamp" != "$(file_identity "$visual_path")" ]; then
        echo uncertain; return
    fi
    case "$visual_record" in ''|*[!a-f0-9:-]*) echo uncertain; return;; esac
    visual_boot=${visual_record%%:*}
    visual_parts=${visual_record#*:}
    visual_pid=${visual_parts%%:*}
    visual_parts=${visual_parts#*:}
    visual_since=${visual_parts%%:*}
    visual_nonce=${visual_parts#*:}
    if [ "$visual_record" != "$visual_boot:$visual_pid:$visual_since:$visual_nonce" ] || ! visual_uuid "$visual_boot" ||
        ! visual_pid_valid "$visual_pid" || ! is_number "$visual_since" ||
        [ "${#visual_since}" -gt 20 ] || [ "${#visual_nonce}" != 32 ]; then echo uncertain; return; fi
    case "$visual_since" in 0) ;; 0*|'') echo uncertain; return;; esac
    case "$visual_nonce" in *[!a-f0-9]*) echo uncertain; return;; esac
    visual_current_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null) || { echo uncertain; return; }
    if ! visual_uuid "$visual_current_boot"; then echo uncertain; return; fi
    if [ "$visual_current_boot" != "$visual_boot" ]; then echo changed; return; fi
    # A target that is exiting can fail a read of /proc for a moment before it is gone. A process that died or was
    # restarted (the new one has another start time) has its original visuals again, so that is "changed", not
    # "uncertain"; look again briefly before giving up on telling them apart.
    for visual_try in 1 2 3; do
        if child_gone "$visual_pid" "$visual_since"; then echo changed; return; fi
        if [ "$(start_of "$visual_pid")" = "$visual_since" ]; then echo current; return; fi
        [ "$visual_try" = 3 ] || sleep 1
    done
    echo uncertain
}
visual_session_descriptor() {
    visual_desc_path="$dir/visual.session"
    if [ ! -e "$visual_desc_path" ] && [ ! -L "$visual_desc_path" ]; then echo absent; return; fi
    visual_desc_stamp=$(file_identity "$visual_desc_path") || { echo uncertain; return; }
    visual_desc_state=$(visual_session_state)
    case "$visual_desc_state" in
        current|changed)
            visual_desc_hash=$(digest "$visual_desc_path") || { echo uncertain; return; }
            if [ "${#visual_desc_hash}" != 64 ] || [ "$visual_desc_stamp" != "$(file_identity "$visual_desc_path")" ]; then echo uncertain; return; fi
            case "$visual_desc_hash" in *[!a-f0-9]*) echo uncertain; return;; esac
            printf '%s:%s\n' "$visual_desc_state" "$visual_desc_hash";;
        *) echo uncertain;;
    esac
}
clear_visual_session() {
    visual_clear_path="$dir/visual.session"
    [ "${#2}" = 64 ] || return 1
    case "$2" in *[!a-f0-9]*) return 1;; esac
    private_file "$visual_clear_path" 600 || return 1
    visual_clear_stamp=$(file_identity "$visual_clear_path") || return 1
    [ "$(digest "$visual_clear_path")" = "$2" ] || return 1
    visual_clear_state=$(visual_session_state) || return 1
    case "$visual_clear_state:$1" in current:restored|changed:restored|changed:changed) ;; *) return 1;; esac
    [ "$visual_clear_stamp" = "$(file_identity "$visual_clear_path")" ] && [ "$(digest "$visual_clear_path")" = "$2" ] &&
        rm -f "$visual_clear_path" && sync
}
# The visual session of this boot, process and start time, as visual_session_state read it (current only).
visual_session_of() {
    [ "$visual_boot:$visual_pid:$visual_since" = "$(cat /proc/sys/kernel/random/boot_id 2>/dev/null):$1:$2" ]
}
# $1 $2 (optional) the target about to be attached. A session that is current for exactly that process was left by an
# injector of this deployment that ended while the target kept running (the root channel restarted, the injector
# crashed or was killed). Frida unloads a script that is not eternalized when its injector ends, so the hooks are gone,
# and a new agent of the same deployment takes the views of its predecessor back (orphan sweeps); attaching again
# adopts the session instead of revoking. "defer" leaves a current session to the check before the next attachment.
guard_visual_session() {
    case "$channel" in ''|window) ;; *) return 0;; esac
    visual_guard_state=$(visual_session_descriptor)
    case "$visual_guard_state" in
        absent) return 0;;
        changed:*) clear_visual_session changed "${visual_guard_state#*:}" && return 0;;
        current:*)
            if [ "${1:-}" = defer ]; then return 0; fi
            visual_boot=; visual_pid=; visual_since=
            # Run in this shell (not a substitution) so the recorded boot, process and start time stay readable.
            [ "$#" = 2 ] && visual_session_state >/dev/null
            if [ "$#" = 2 ] && visual_session_of "$1" "$2" && [ "$(visual_session_state)" = current ]; then
                log 'OVC_WORKER_NOTICE The agent ended while its target kept running; attaching again to the same process.'
                return 0
            fi;;
    esac
    log 'OVC_WORKER_ERROR Original visual state is uncertain for the current target. Recovery is required.'
    revoke_activation visual || log 'OVC_WORKER_ERROR Durable visual recovery requires PC assistance.'
    return 1
}
record_visual_session() {
    case "$channel" in ''|window) ;; *) return 0;; esac
    visual_write_path="$dir/visual.session"
    [ ! -e "$visual_write_path" ] && [ ! -L "$visual_write_path" ] || return 1
    visual_write_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null) || return 1
    visual_uuid "$visual_write_boot" && visual_pid_valid "$1" && is_number "$2" &&
        [ "$(pid_for "$target")" = "$1" ] && [ "$(start_of "$1")" = "$2" ] || return 1
    visual_write_nonce=$(head -c 16 /dev/urandom | od -An -v -tx1 | tr -d ' \n') || return 1
    [ "${#visual_write_nonce}" = 32 ] || return 1
    case "$visual_write_nonce" in *[!a-f0-9]*) return 1;; esac
    visual_write_stage=$(mktemp "$dir/.visual-session.XXXXXXXX") || return 1
    if ! { printf '%s:%s:%s:%s\n' "$visual_write_boot" "$1" "$2" "$visual_write_nonce" > "$visual_write_stage" && chmod 600 "$visual_write_stage" &&
        sync && mv -f "$visual_write_stage" "$visual_write_path" && sync; }; then
        rm -f "$visual_write_stage"
        return 1
    fi
}
injector_argv() {
    ia_expected=$(printf '%s' "$injector" | od -An -v -tx1 | tr -d ' \n') || return 1
    ia_script=$(printf '%s' "$script" | od -An -v -tx1 | tr -d ' \n') || return 1
    [ -n "$ia_expected" ] && [ -n "$ia_script" ] && [ -r "$1" ] || return 1
    ia_bytes=$(head -c 4097 "$1" 2>/dev/null | od -An -v -tx1) || return 1
    ia_count=0; ia_arg=0; ia_value=
    for ia_byte in $ia_bytes; do
        ia_count=$((ia_count+1))
        [ "$ia_count" -le 4096 ] || return 1
        if [ "$ia_byte" = 00 ]; then
            ia_arg=$((ia_arg+1))
            case "$ia_arg" in
                1) [ "$ia_value" = "$ia_expected" ] || return 1;;
                2) [ "$ia_value" = 2d70 ] || return 1;;
                3) [ -n "$ia_value" ] || return 1;;
                4) [ "$ia_value" = 2d73 ] || return 1;;
                5) [ "$ia_value" = "$ia_script" ] || return 1;;
                *) return 1;;
            esac
            ia_value=
        else
            [ "$ia_arg" -lt 5 ] || return 1
            if [ "$ia_arg" = 2 ]; then
                case "$ia_byte" in 3[0-9]) ;; *) return 1;; esac
                [ -n "$ia_value" ] || [ "$ia_byte" != 30 ] || return 1
            fi
            ia_value=$ia_value$ia_byte
        fi
    done
    [ "$ia_arg" = 5 ] && [ -z "$ia_value" ]
}
same_child() {
    is_number "$1" && [ -n "$2" ] && [ "$(start_of "$1")" = "$2" ] &&
        [ "$(stat -c %u "/proc/$1" 2>/dev/null)" = 0 ] || return 1
    executable=$(readlink "/proc/$1/exe" 2>/dev/null) || return 1
    [ "$executable" = "$injector" ] || [ "$executable" = "$injector (deleted)" ] || return 1
    injector_argv "/proc/$1/cmdline"
}
# This worker's own injector child by PID, start time, owner and executable, when its command line is unreadable.
own_injector() {
    is_number "$1" && [ -n "$2" ] && [ "$(start_of "$1")" = "$2" ] &&
        [ "$(stat -c %u "/proc/$1" 2>/dev/null)" = 0 ] || return 1
    oi_exe=$(readlink "/proc/$1/exe" 2>/dev/null) || return 1
    [ "$oi_exe" = "$injector" ] || [ "$oi_exe" = "$injector (deleted)" ]
}
stop_child() {
    child_gone "$1" "$2" && return 0
    if ! same_child "$1" "$2"; then
        # An injector whose target just ended can be mid-exit, with its command line already gone. Give it a
        # bounded moment to finish instead of treating it as an unknown process.
        for delay in 1 2 3 4 5; do
            sleep 1
            if child_gone "$1" "$2"; then return 0; fi
        done
        # Still there: a child this worker started (same PID, start time and executable) that hangs on its dead
        # target. Ending it is safe; leaving it would stop this channel until the next boot.
        own_injector "$1" "$2" && kill -KILL "$1" 2>/dev/null || return 1
        for delay in 1 2 3; do
            sleep 1
            if child_gone "$1" "$2"; then return 0; fi
        done
        return 1
    fi
    kill -TERM "$1" 2>/dev/null || { child_gone "$1" "$2"; return; }
    for delay in 1 2 3; do
        sleep 1
        if child_gone "$1" "$2"; then return 0; fi
    done
    same_child "$1" "$2" && kill -KILL "$1" 2>/dev/null || { child_gone "$1" "$2"; return; }
    sleep 1
    child_gone "$1" "$2"
}
child_gone() {
    if [ -z "$1" ]; then return 0; fi
    is_number "$1" || return 1
    if [ ! -d "/proc/$1" ]; then return 0; fi
    info=$(cat "/proc/$1/stat" 2>/dev/null) || { [ ! -d "/proc/$1" ]; return; }
    case "$info" in *') '*) ;; *) return 1;; esac
    tail=${info##*) }
    cg_expected=$2
    (
        set -f
        IFS=' '
        set -- $tail
        [ "$#" -ge 20 ] || exit 1
        cg_state=$1
        shift 19
        cg_actual=$1
        case "$cg_state" in [A-Za-z]) ;; *) exit 1;; esac
        case "$cg_actual" in ''|*[!0-9]*) exit 1;; esac
        case "$cg_expected" in *[!0-9]*) exit 1;; esac
        case "$cg_state" in Z|X) exit 0;; esac
        [ -n "$cg_expected" ] && [ "$cg_actual" != "$cg_expected" ]
    )
}
unknown_injector() {
    matches=$(cmdline_matches -e "$script")
    for proc in $matches; do
        args=$(cmdline_text "$proc")
        case "$args" in "$injector -p "*" -s $script ") return 0;; esac
    done
    return 1
}
log() {
    if [ -f "$dir/worker.log" ] && [ "$(wc -c < "$dir/worker.log")" -gt 131072 ]; then
        : > "$dir/worker.log"
    fi
    printf '%s\n' "$*" >> "$dir/worker.log"
}

case "$channel" in
    --visual-session-state|--visual-session-clear-restored|--visual-session-clear-changed)
        case "$channel:$#" in --visual-session-state:2|--visual-session-clear-restored:3|--visual-session-clear-changed:3) ;; *) exit 2;; esac
        case "$2" in
            main) dir=$root;;
            window) dir="$root/window";;
            *) exit 2;;
        esac
        [ "$(id -u)" = 0 ] && private_dir "$root" && private_dir "$dir" || exit 1
        case "$channel" in
            --visual-session-state) visual_session_descriptor;;
            --visual-session-clear-restored) clear_visual_session restored "$3";;
            --visual-session-clear-changed) clear_visual_session changed "$3";;
        esac
        exit $?;;
esac
# Decides a re-arm without the PC from what --recover observed. Prints "<outcome>:<reason>".
#   $1 mode: auto (OVC's boot job, nobody asked) or manual (the owner confirmed Restore in OVC)
#   $2 reason: recorded (this supervisor revoked and said why) or absent (the PC, the app or an older supervisor did)
#   $3 same_boot: 1 when a marker was written in the current boot, so the screen may still show changed visuals
#   $4 visuals: clear, or pending while a visual session or an injector of this project is still live
#   $5 verify: check_runtime's result (0 verified, 2 user storage locked, 3 lifetime must be resealed, else changed)
# Integrity is the verify step: the receipt pins the OEM APKs, the signer of Controls, the Frida runtime, both scripts
# and every agent, so a foreign or older Controls, a runtime or an OEM package change still needs the PC. The reboot is what proves the original visuals are back.
recover_decision() {
    if [ "$1" = auto ] && [ "$2" != recorded ]; then echo pc:unrecorded; return; fi
    if [ "$1" = auto ] && [ "$3" = 1 ]; then echo reboot:same-boot; return; fi
    if [ "$4" != clear ]; then echo reboot:visual; return; fi
    case "$5" in
        0) echo recovered:verified;;
        2) echo waiting:locked;;
        3) echo pc:lifetime;;
        *) echo pc:deployment;;
    esac
}
# Why an attachment ended, for the retry policy. Prints:
#   restart  the target (or Android's framework) restarted after the agent ran healthy for 20 s: attach again on the
#            restart budget, not the retry budget (an OEM app crash, the persistent launcher restarting)
#   exited   a translation app closed by the driver or reclaimed by Android after a healthy run: wait for it, unlimited
#   attempt  anything else, including a target that dies within 20 s, so a crash loop stays bounded
#   $1 target changed (0|1)  $2 system_server unchanged (0|1)  $3 healthy (0|1)  $4 elapsed seconds  $5 locale (0|1)
attach_outcome() {
    if [ "$1" != 1 ] || [ "$3" != 1 ] || [ "$4" -lt 20 ]; then echo attempt; return; fi
    if [ "$5" = 1 ] && [ "$2" = 1 ]; then echo exited; else echo restart; fi
}
recover_report() { printf 'OVC_RECOVER outcome=%s reason=%s\n' "${1%%:*}" "${1#*:}"; }
recover_activation() {
    rc_mode=$1
    rc_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
    visual_uuid "$rc_boot" || { recover_report pc:boot-id; return 1; }
    rc_blocked=0; rc_same=0
    for rc_marker in recovery.blocked recovery.visual-pending; do
        rc_path="$root/$rc_marker"
        if [ -L "$rc_path" ]; then recover_report pc:marker; return 1; fi
        [ -e "$rc_path" ] || continue
        private_file "$rc_path" 600 || { recover_report pc:marker; return 1; }
        rc_blocked=1
        if [ "$(cat "$rc_path" 2>/dev/null)" = "$rc_boot" ]; then rc_same=1; fi
    done
    if [ "$rc_blocked" = 0 ]; then recover_report none:active; return 0; fi
    if [ -e "$root/update.pending" ] || [ -L "$root/update.pending" ]; then recover_report pc:update; return 1; fi
    rc_reason_state=absent; rc_cause=-; rc_channels=-
    rc_reason_path="$root/recovery.reason"
    if [ -L "$rc_reason_path" ]; then recover_report pc:marker; return 1; fi
    if [ -e "$rc_reason_path" ] && private_file "$rc_reason_path" 600; then
        rc_record=$(cat "$rc_reason_path" 2>/dev/null)
        rc_record_boot=${rc_record%% *}
        rc_rest=${rc_record#* }
        rc_cause=${rc_rest%% *}
        rc_channels=${rc_rest#* }
        # The reason counts only for the revocation that wrote the markers: a later PC rollback has its own boot id.
        rc_reason_state=recorded
        for rc_marker in recovery.blocked recovery.visual-pending; do
            if [ -e "$root/$rc_marker" ] && [ "$(cat "$root/$rc_marker" 2>/dev/null)" != "$rc_record_boot" ]; then rc_reason_state=absent; fi
        done
        case "$rc_cause" in lifetime|deployment|package|runtime|receipt|visual|user|unknown) ;; *) rc_reason_state=absent;; esac
        case "$rc_channels" in -|*[!a-z,-]*) rc_channels=-;; esac
        visual_uuid "$rc_record_boot" || rc_reason_state=absent
        [ "$rc_rest" = "$rc_cause $rc_channels" ] || { rc_reason_state=absent; rc_channels=-; }
    fi
    rc_visuals=clear
    for rc_dir in "$root" "$root/window"; do
        [ -d "$rc_dir" ] || continue
        dir=$rc_dir
        # A current session with no injector of this project left (checked below) is adopted by the next attachment.
        case "$(visual_session_state)" in absent|changed|current) ;; *) rc_visuals=pending;; esac
    done
    dir=$root
    for rc_proc in $(cmdline_matches -e "$injector"); do
        rc_pid=${rc_proc#/proc/}; rc_pid=${rc_pid%/cmdline}
        if [ "$(readlink "/proc/$rc_pid/exe" 2>/dev/null)" = "$injector" ]; then rc_visuals=pending; fi
    done
    rc_verify=1
    if [ "$rc_visuals" = clear ]; then
        settled check_runtime
        rc_verify=$?
    fi
    rc_outcome=$(recover_decision "$rc_mode" "$rc_reason_state" "$rc_same" "$rc_visuals" "$rc_verify")
    case "$rc_outcome" in recovered:*) ;; *) recover_report "$rc_outcome"; return 1;; esac
    # Enable the channels that were on when activation was revoked, each only with its receipted agent in place.
    # Without a recorded list (an older supervisor), every channel the receipt seals comes back.
    rc_restored=
    for rc_name in main locale-setting locale-vehicle locale-bluetooth locale-hiboard window navigation can; do
        case ",$rc_channels," in ,-,) ;; *",$rc_name,"*) ;; *) continue;; esac
        rc_suffix=$(channel_suffix "$rc_name")
        if [ "$rc_name" = main ]; then rc_relative=launcher-buttons.js; else rc_relative="${rc_suffix#/}/launcher-buttons.js"; fi
        record artifact "$rc_relative" 3 >/dev/null 2>&1 || continue
        private_dir "$root$rc_suffix" && artifact_ok "$rc_relative" || continue
        rc_restored="${rc_restored:+$rc_restored,}$rc_name"
    done
    case ",$rc_restored," in *,main,*) ;; *) recover_report pc:main-agent; return 1;; esac
    for rc_dir in "$root" "$root/window"; do
        [ -d "$rc_dir" ] || continue
        dir=$rc_dir
        rc_session=$(visual_session_descriptor)
        case "$rc_session" in changed:*) clear_visual_session changed "${rc_session#*:}" || { recover_report pc:visual-session; return 1; };; esac
    done
    dir=$root
    for rc_name in main locale-setting locale-vehicle locale-bluetooth locale-hiboard window navigation can; do
        rc_suffix=$(channel_suffix "$rc_name")
        if private_dir "$root$rc_suffix"; then rm -f "$root$rc_suffix/failure.boot" "$root$rc_suffix/failure.count" \
            "$root$rc_suffix/restart.boot" "$root$rc_suffix/restart.count"; fi
    done
    rc_ifs=$IFS; IFS=,
    for rc_name in $rc_restored; do
        rc_suffix=$(channel_suffix "$rc_name")
        rc_flag="$root$rc_suffix/enabled"
        [ ! -L "$rc_flag" ] && touch "$rc_flag" && chmod 600 "$rc_flag" || { IFS=$rc_ifs; recover_report pc:enable; return 1; }
    done
    IFS=$rc_ifs
    sync
    rm -f "$root/recovery.blocked" "$root/recovery.visual-pending" "$root/recovery.reason" && sync ||
        { recover_report pc:markers; return 1; }
    log "OVC_WORKER_NOTICE Activation re-armed without the PC mode=$rc_mode cause=$rc_cause channels=$rc_restored."
    printf 'OVC_RECOVER outcome=recovered reason=%s channels=%s\n' "$rc_cause" "$rc_restored"
}
if [ "$channel" = --recover ]; then
    [ "$#" = 2 ] || exit 2
    case "$2" in auto|manual) ;; *) exit 2;; esac
    [ "$(id -u)" = 0 ] && private_dir /data/adb && private_dir "$root" && [ "$fixture" = 0 ] || exit 1
    recover_activation "$2"
    exit $?
fi
if [ "$channel" = --verify-boot ]; then
    settled check_runtime
    checked=$?
    if [ "$checked" = 0 ]; then echo 'OVC_BOOT_VERIFIED'; exit 0; fi
    if [ "$checked" = 2 ]; then echo 'OVC_BOOT_WAITING User storage or package settings are not settled yet.' >&2; exit 2; fi
    if [ "$checked" = 3 ]; then echo 'OVC_BOOT_ERROR Deployment lifetime must be resealed by an explicit connection.' >&2; exit 1; fi
    echo 'OVC_BOOT_ERROR Deployment is missing, changed, or unapproved.' >&2
    exit 1
fi
[ "$(id -u)" = 0 ] && private_dir "$root" && private_dir "$dir" && may_start || exit 1

boot_id=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
case "$boot_id" in ''|*[!a-f0-9-]*) exit 1;; esac
self_start=$(start_of "$$")
is_number "$self_start" || exit 1
owner="$boot_id:$$:$self_start"
lock="$dir/worker.owner"
recover=
child=
child_start=
claim=$(mktemp "$dir/worker.claim.XXXXXX") || exit 1
printf '%s\n' "$owner" > "$claim"
read_owner() { private_file "$1" 600 && cat "$1"; }
finish() {
    if [ "$(read_owner "$lock" 2>/dev/null)" = "$owner" ]; then
        rm -f "$dir/ready"
        if [ "$recovery_required" = 1 ] && [ "$recovery_recorded" != 1 ]; then
            log 'OVC_WORKER_ERROR Recovery evidence could not be saved. Preserving ownership evidence.'
        elif stop_child "$child" "$child_start"; then
            rm -f "$dir/worker.pid" "$dir/target.pid" "$dir/system.pid" "$lock"
        else
            log 'OVC_WORKER_ERROR Injector did not stop. Preserving ownership evidence.'
        fi
    fi
    rm -f "$claim"
    if [ -n "$recover" ]; then rmdir "$recover" 2>/dev/null; fi
}
trap finish EXIT
trap 'exit 0' HUP INT TERM

[ ! -d "$lock" ] && [ ! -L "$lock" ] || exit 1
if ! ln "$claim" "$lock" 2>/dev/null; then
    prior=$(read_owner "$lock" 2>/dev/null) || exit 1
    case "$prior" in ''|*[!a-f0-9:-]*) exit 1;; esac
    prior_boot=${prior%%:*}; prior_pid=${prior#*:}; prior_pid=${prior_pid%%:*}; prior_start=${prior##*:}
    is_number "$prior_pid" && is_number "$prior_start" || exit 1
    if [ "$prior_boot" = "$boot_id" ] && [ "$(start_of "$prior_pid")" = "$prior_start" ]; then exit 0; fi
    # Recoverers of the same stale owner serialize; nobody removes a fresh owner's lock.
    gate_name=$(printf '%s' "$prior" | tr ':' '-')
    gate="$dir/worker-recover-$gate_name-$boot_id"
    mkdir "$gate" 2>/dev/null || exit 1
    recover=$gate
    [ "$(read_owner "$lock" 2>/dev/null)" = "$prior" ] || exit 0
    if [ "$prior_boot" = "$boot_id" ]; then
        if [ "$fixture" = 0 ]; then
            waiting=0
            while may_start && ! user_unlocked; do
                if [ "$waiting" = 0 ]; then log 'OVC_WORKER_WAITING User storage is not verified unlocked.'; waiting=1; fi
                sleep 2
            done
            may_start || exit 0
        fi
        orphan=$(cat "$dir/child.pid" 2>/dev/null)
        orphan_start=$(cat "$dir/child.start" 2>/dev/null)
        settled check_runtime
        checked=$?
        if [ "$checked" != 0 ]; then
            if [ "$checked" = 2 ]; then exit 0; fi
            revoke_activation deployment || exit 1
            stop_child "$orphan" "$orphan_start" || exit 1
            exit 1
        fi
        if ! guard_visual_session defer; then
            if [ "$recovery_recorded" = 1 ]; then stop_child "$orphan" "$orphan_start" || exit 1; fi
            exit 1
        fi
        stop_child "$orphan" "$orphan_start" || exit 1
    fi
    rm -f "$lock" || exit 1
    ln "$claim" "$lock" 2>/dev/null || exit 0
    rmdir "$recover" || exit 1
    recover=
    log 'OVC_WORKER_NOTICE Recovered a stale worker lock.'
fi
if unknown_injector; then
    log 'OVC_WORKER_ERROR An existing injector requires recovery before attaching.'
    exit 1
fi
printf '%s\n' "$boot_id" > "$dir/worker.boot"
printf '%s\n' "$$" > "$dir/worker.pid"

attempts=0
max_attempts=5
if [ "$channel" = window ]; then max_attempts=3; fi
if [ "$(cat "$dir/failure.boot" 2>/dev/null)" = "$boot_id" ]; then
    value=$(cat "$dir/failure.count" 2>/dev/null)
    case "$value" in 0|[1-9]|[1-9][0-9]) attempts=$value;; esac
fi
save_attempts() {
    printf '%s\n' "$boot_id" > "$dir/failure.boot"
    printf '%s\n' "$attempts" > "$dir/failure.count"
}
# Restarts of a target that ran healthy (a crash of the OEM app, a framework restart) have their own budget, so a
# few crashes do not end the retries of this boot, while a crash loop the agent might cause still stops.
restarts=0
max_restarts=10
if [ "$(cat "$dir/restart.boot" 2>/dev/null)" = "$boot_id" ]; then
    value=$(cat "$dir/restart.count" 2>/dev/null)
    case "$value" in 0|[1-9]|[1-9][0-9]) restarts=$value;; esac
fi
save_restarts() {
    printf '%s\n' "$boot_id" > "$dir/restart.boot"
    printf '%s\n' "$restarts" > "$dir/restart.count"
}
backoff=3
waiting_unlock=0
while may_start; do
    if [ "$fixture" = 0 ] && ! user_unlocked; then
        if [ "$waiting_unlock" = 0 ]; then log 'OVC_WORKER_WAITING User storage is not verified unlocked.'; waiting_unlock=1; fi
        sleep 2
        continue
    fi
    waiting_unlock=0
    if [ "$attempts" -ge "$max_attempts" ]; then
        log 'OVC_WORKER_ERROR Retry limit reached for this boot. Review diagnostics before reconnecting.'
        break
    fi
    stable=0; waited=0; system_pid=
    while may_start && [ "$stable" -lt 3 ] && [ "$waited" -lt 120 ]; do
        current=$(pid_for system_server)
        if [ "$(getprop sys.boot_completed)" != 1 ] || ! is_number "$current" || [ "$current" -le 1 ]; then
            system_pid=; stable=0
        elif [ "$current" = "$system_pid" ]; then
            stable=$((stable+1))
        else
            system_pid=$current; stable=1
        fi
        if [ "$stable" -lt 3 ]; then sleep 2; waited=$((waited+2)); fi
    done
    may_start || break
    if [ "$stable" -lt 3 ]; then log 'OVC_WORKER_ERROR Android boot did not settle.'; break; fi
    target_pid=$(pid_for "$target")
    waited=0
    # A translation target may not run until the driver opens it, so its worker keeps waiting for it.
    while may_start && { ! is_number "$target_pid" || [ "$target_pid" -le 1 ]; } &&
        { [ "$locale" = 1 ] || [ "$waited" -lt 60 ]; }; do
        sleep 2; waited=$((waited+2)); target_pid=$(pid_for "$target")
    done
    may_start || break
    if ! is_number "$target_pid" || [ "$target_pid" -le 1 ]; then log 'OVC_WORKER_ERROR Reviewed target is unavailable.'; break; fi
    # The Frida agent crashes 32-bit ART processes (SIGSEGV seen in the 32-bit Settings and Bluetooth phone apps of a
    # 97C head unit), so translation is attached to 64-bit app processes only.
    if [ "$locale" = 1 ] && [ "$fixture" = 0 ] && [ "$(readlink "/proc/$target_pid/exe" 2>/dev/null)" != /system/bin/app_process64 ]; then
        log 'OVC_WORKER_ERROR Translation is not supported in a 32-bit app process.'
        break
    fi
    system_start=$(start_of "$system_pid")
    target_start=$(start_of "$target_pid")
    if ! is_number "$system_start" || ! is_number "$target_start"; then log 'OVC_WORKER_ERROR Target identity is unavailable.'; break; fi
    settled check_runtime
    checked=$?
    if [ "$checked" = 2 ]; then continue; fi
    if [ "$checked" = 3 ]; then log 'OVC_WORKER_ERROR Deployment lifetime must be resealed by an explicit connection.'; break; fi
    if [ "$checked" != 0 ]; then
        log 'OVC_WORKER_ERROR Deployment is missing, changed, or unapproved.'
        revoke_activation deployment || log 'OVC_WORKER_ERROR Durable recovery requires PC assistance.'
        break
    fi
    may_start || break
    if [ "$(pid_for system_server)" != "$system_pid" ] || [ "$(pid_for "$target")" != "$target_pid" ] ||
        [ "$(start_of "$system_pid")" != "$system_start" ] || [ "$(start_of "$target_pid")" != "$target_start" ]; then
        # The target restarted while it was being checked (a crash loop right after boot, for example). Look again;
        # the retry budget keeps a loop bounded.
        log 'OVC_WORKER_NOTICE Target restarted before attachment.'
        attempts=$((attempts+1)); save_attempts
        continue
    fi
    guard_visual_session "$target_pid" "$target_start" || break
    if [ -e "$dir/visual.session" ] || [ -L "$dir/visual.session" ]; then
        # Adopted above: the session already names this target.
        :
    else
        record_visual_session "$target_pid" "$target_start" || { log 'OVC_WORKER_ERROR Visual session could not be recorded before attachment.'; break; }
    fi
    printf '%s\n' "$target_pid" > "$dir/target.pid"
    printf '%s\n' "$system_pid" > "$dir/system.pid"
    rm -f "$dir/ready"
    : > "$dir/hook.log"
    "$injector" -p "$target_pid" -s "$script" >> "$dir/hook.log" 2>&1 &
    child=$!
    child_start=$(start_of "$child")
    printf '%s\n' "$child" > "$dir/child.pid"
    printf '%s\n' "$child_start" > "$dir/child.start"
    elapsed=0; healthy=0; last_heartbeat=; silent_for=0; markers=
    while [ -n "$child_start" ] && [ "$(start_of "$child")" = "$child_start" ] && enabled; do
        sleep 2
        elapsed=$((elapsed+2))
        # Bound package-manager and user-state diagnostics between metadata checks.
        if [ "$fixture" = 0 ] && { [ "$elapsed" = 2 ] || [ $((elapsed%10)) -eq 0 ]; }; then
            settled lifetime_status
            lifetime_result=$?
            if [ "$lifetime_result" = 1 ] || [ "$lifetime_result" = 3 ]; then
                log 'OVC_WORKER_NOTICE App activation lifetime changed.'
                revoke_activation lifetime || log 'OVC_WORKER_ERROR Durable recovery requires PC assistance.'
                break
            fi
        fi
        if ! settled runtime_unchanged; then
            log 'OVC_WORKER_NOTICE Deployment package or runtime changed.'
            revoke_activation runtime || log 'OVC_WORKER_ERROR Durable recovery requires PC assistance.'
            break
        fi
        if ! settled app_unchanged && ! adopt_app_update; then
            log 'OVC_WORKER_NOTICE Controls package changed.'
            revoke_activation package || log 'OVC_WORKER_ERROR Durable recovery requires PC assistance.'
            break
        fi
        if [ "$(pid_for system_server)" != "$system_pid" ] || [ "$(pid_for "$target")" != "$target_pid" ] ||
            [ "$(start_of "$system_pid")" != "$system_start" ] || [ "$(start_of "$target_pid")" != "$target_start" ]; then
            log 'OVC_WORKER_NOTICE Android or target process changed.'
            break
        fi
        if [ "$fixture" = 0 ] && [ "$receipt_identity" != "$(file_identity "$receipt")" ]; then
            # An optional channel update rewrites the receipt. Revalidate it before continuing.
            settled check_runtime
            checked=$?
            if [ "$checked" = 2 ]; then continue; fi
            if [ "$checked" != 0 ]; then
                log 'OVC_WORKER_ERROR Updated deployment is unapproved.'
                revoke_activation receipt || log 'OVC_WORKER_ERROR Durable recovery requires PC assistance.'
                break
            fi
        fi
        if [ $((elapsed%10)) -eq 0 ] && ! settled packages_unchanged; then
            log 'OVC_WORKER_NOTICE Installed package changed.'
            revoke_activation package || log 'OVC_WORKER_ERROR Durable recovery requires PC assistance.'
            break
        fi
        if grep -qE "$pattern" "$dir/hook.log"; then
            markers=$(grep -E '^OVC_(READY|[A-Z_]+_READY)' "$dir/hook.log" | tail -n 16)
        fi
        heartbeat=$(grep '^OVC_HEARTBEAT [0-9][0-9]*$' "$dir/hook.log" | tail -n 1 | awk '{print $2}')
        silent_for=$((silent_for+2))
        if [ -n "$markers" ] && [ -n "$heartbeat" ] && [ "$heartbeat" != "$last_heartbeat" ]; then
            last_heartbeat=$heartbeat; silent_for=0; healthy=1
            printf '%s\n' "$markers" > "$dir/ready.new" && mv -f "$dir/ready.new" "$dir/ready"
        fi
        if { [ "$elapsed" -ge 25 ] && [ "$healthy" -eq 0 ]; } ||
            { [ "$healthy" -eq 1 ] && [ "$silent_for" -ge 30 ]; }; then
            log 'OVC_WORKER_ERROR Agent did not confirm current readiness.'
            break
        fi
        if [ "$(wc -c < "$dir/hook.log")" -gt 262144 ]; then : > "$dir/hook.log"; fi
        if [ "$elapsed" -ge 60 ] && [ "$healthy" -eq 1 ] && [ "$attempts" -ne 0 ]; then
            attempts=0; backoff=3; save_attempts
        fi
        if [ "$elapsed" -ge 600 ] && [ "$healthy" -eq 1 ] && [ "$restarts" -ne 0 ]; then
            restarts=0; save_restarts
        fi
    done
    # The injector also ends by itself when its target dies, so look at the target here, not only inside the loop.
    target_changed=0; system_same=1
    if [ "$(pid_for system_server)" != "$system_pid" ] || [ "$(start_of "$system_pid")" != "$system_start" ]; then
        target_changed=1; system_same=0
    elif [ "$(pid_for "$target")" != "$target_pid" ] || [ "$(start_of "$target_pid")" != "$target_start" ]; then
        target_changed=1
    fi
    outcome=$(attach_outcome "$target_changed" "$system_same" "$healthy" "$elapsed" "$locale")
    # Retire the session of a target that restarted. When the injector ended while its target kept running, keep the
    # session: the next attachment adopts it (see guard_visual_session) instead of revoking the activation.
    case "$channel" in ''|window) if enabled && [ "$target_changed" = 1 ]; then guard_visual_session || true; fi;; esac
    rm -f "$dir/target.pid" "$dir/system.pid" "$dir/ready"
    if [ "$recovery_required" = 1 ] && [ "$recovery_recorded" != 1 ]; then break; fi
    if ! stop_child "$child" "$child_start"; then log 'OVC_WORKER_ERROR Injector did not stop.'; break; fi
    # Reap a known exited child only. Never wait indefinitely for an unknown PID.
    if child_gone "$child" "$child_start"; then wait "$child" 2>/dev/null; fi
    child=; child_start=
    rm -f "$dir/child.pid" "$dir/child.start"
    may_start || break
    if [ "$outcome" = exited ]; then
        # Wait for the next start of the app without using the retry budget.
        backoff=3
        continue
    fi
    if [ "$outcome" = restart ]; then
        restarts=$((restarts+1)); save_restarts
        if [ "$restarts" -gt "$max_restarts" ]; then
            log 'OVC_WORKER_ERROR Target restarted too often this boot. Review diagnostics before reconnecting.'
            break
        fi
        log "OVC_WORKER_NOTICE Target restarted; attaching again restart=$restarts."
        # A restarted target has its original visuals and code again (the guard above retired its visual session),
        # so attaching again is the same as the first attachment of this boot, after a short pause.
        backoff=3
        delay=$((restarts*3)); if [ "$delay" -gt 30 ]; then delay=30; fi
        waited=0
        while may_start && [ "$waited" -lt "$delay" ]; do sleep 1; waited=$((waited+1)); done
        continue
    fi
    attempts=$((attempts+1)); save_attempts
    waited=0
    while may_start && [ "$waited" -lt "$backoff" ]; do sleep 1; waited=$((waited+1)); done
    backoff=$((backoff*2))
    if [ "$backoff" -gt 30 ]; then backoff=30; fi
done

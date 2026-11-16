package org.openvoyah.controls.data.frida

import java.io.IOException

/** Recovery of the observed guest account file and four properties, not all OEM account data. */
internal object GuestAccountState {
    @JvmStatic fun prepare(base: String): String = command(base, "prepare")
    @JvmStatic fun switchTo(base: String, record: String, aid: String, uid: String): String =
        command(base, "switch", record, aid, uid)
    @JvmStatic fun restore(base: String): String = command(base, "restore")
    @JvmStatic fun requireReceipt(reply: String, action: String) {
        if (reply.trim() != "OVC_GUEST_STATE_V2 $action verified")
            throw IOException("Guest account recovery state was not verified; retain the original backup")
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
    private fun command(base: String, action: String, record: String = "", aid: String = "", uid: String = ""): String {
        require(base == "/data/local/tmp/open-voyah-controls" || base == ProductionRootAccess.BASE)
        require(action in setOf("prepare", "switch", "restore"))
        val payload = SCRIPT.replace("@BASE@", quote(base)).replace("@ACTION@", quote(action))
            .replace("@RECORD@", quote(record)).replace("@AID@", quote(aid)).replace("@UID@", quote(uid))
        return LOCK.replace("@BASE@", quote(base)).replace("@PAYLOAD@", payload)
    }

    // The broker already provisions this BusyBox path and uses the same sh/flock pairing.
    // Keep the lock inside that shell: Android 9 mksh can close inherited descriptors.
    private val LOCK = """
        set -eu
        umask 077
        base=@BASE@
        [ "${'$'}(id -u)" = 0 ] && [ ! -L "${'$'}base" ] && [ -d "${'$'}base" ] || exit 79
        [ "${'$'}(stat -c '%u:%g:%a' "${'$'}base")" = 0:0:700 ] || exit 79
        pointer=/data/adb/open-voyah-controls-access/busybox
        [ ! -L "${'$'}pointer" ] && [ -f "${'$'}pointer" ] && [ "${'$'}(stat -c '%u:%a:%h' "${'$'}pointer")" = 0:600:1 ] || exit 79
        [ "${'$'}(stat -c '%s' "${'$'}pointer")" -le 128 ] || exit 79
        busybox=${'$'}(cat "${'$'}pointer")
        case "${'$'}busybox" in /system/bin/busybox|/system/xbin/busybox|/vendor/bin/busybox) ;; *) exit 79;; esac
        lock="${'$'}base/profile-state.lock"
        [ ! -L "${'$'}lock" ] || exit 79
        if [ ! -e "${'$'}lock" ]; then (set -C; : > "${'$'}lock") 2>/dev/null || true; fi
        [ -f "${'$'}lock" ] && [ "${'$'}(stat -c '%u:%g:%a:%h' "${'$'}lock")" = 0:0:600:1 ] || exit 79
        command=${'$'}(mktemp "${'$'}base/.profile-command.XXXXXXXX")
        trap 'rm -f "${'$'}command"' EXIT
        trap 'exit 79' HUP INT TERM
        cat > "${'$'}command" <<'OVC_GUEST_COMMAND'
        @PAYLOAD@
        OVC_GUEST_COMMAND
        chmod 600 "${'$'}command"
        "${'$'}busybox" sh -c 'exec 9<> "${'$'}1"; "${'$'}2" flock -n 9 || exit 79; . "${'$'}3"' ovc-profile "${'$'}lock" "${'$'}busybox" "${'$'}command"
    """.trimIndent()

    // Keep shell errors free of account contents. A pending journal survives any ambiguous write.
    private val SCRIPT = """
        set -eu
        umask 077
        base=@BASE@
        action=@ACTION@
        record=@RECORD@
        aid=@AID@
        uid=@UID@
        account=/private/configs/token/accountInfo
        backup="${'$'}base/profile-backup"
        journal="${'$'}base/profile-transaction"
        properties='qinggan.account.aid qinggan.account.uid qinggan.account.phone com.qinggan.user_started'
        members="schema product fingerprint serial-sha256 accountInfo metadata context ${'$'}properties"
        stage=''; new=''; journal_stage=''
        cleanup() {
            [ -z "${'$'}stage" ] || { rm -f "${'$'}stage"/*; rmdir "${'$'}stage"; }
            [ -z "${'$'}new" ] || rm -f "${'$'}new"
            [ -z "${'$'}journal_stage" ] || rm -f "${'$'}journal_stage"
        }
        trap cleanup EXIT
        trap 'exit 79' HUP INT TERM
        regular() { [ ! -L "${'$'}1" ] && [ -f "${'$'}1" ] && [ "${'$'}(stat -c '%h' "${'$'}1")" = 1 ]; }
        protected() { regular "${'$'}1" && [ "${'$'}(stat -c '%u:%g:%a' "${'$'}1")" = 0:0:600 ]; }
        context() { ls -Zd "${'$'}1" | awk 'NF {print ${'$'}1}'; }
        valid_context() {
            if [ "${'$'}1" = '?' ]; then [ "${'$'}(getenforce)" = Disabled ]; else
                printf '%s\n' "${'$'}1" | grep -Eq '^u:object_r:[A-Za-z0-9_]+:s[0-9]+(:[A-Za-z0-9_,.-]+)?${'$'}'
            fi
        }
        unit_identity() {
            serial=${'$'}(getprop ro.serialno) || return 1
            [ -n "${'$'}serial" ] && [ "${'$'}{#serial}" -le 128 ] || return 1
            case "${'$'}serial" in *[!A-Za-z0-9._-]*) return 1;; esac
            printf '%s\n' "${'$'}serial" | sha256sum | awk '{print ${'$'}1}'
        }
        verify() {
            b="${'$'}1"
            [ ! -L "${'$'}b" ] && [ -d "${'$'}b" ] && [ "${'$'}(stat -c '%u:%g:%a' "${'$'}b")" = 0:0:700 ] || return 1
            for member in ${'$'}members manifest ready; do
                protected "${'$'}b/${'$'}member" || return 1
                case "${'$'}member" in
                    schema|metadata) limit=64;; product) limit=128;; fingerprint|context) limit=512;;
                    accountInfo|manifest) limit=8192;; ready|serial-sha256) limit=65;; *) limit=4096;;
                esac
                [ "${'$'}(stat -c '%s' "${'$'}b/${'$'}member")" -le "${'$'}limit" ] || return 1
            done
            [ "${'$'}(cat "${'$'}b/schema")" = OVC_GUEST_KNOWN_STATE_V2 ] || return 1
            [ "${'$'}(wc -c < "${'$'}b/ready")" = 65 ] || return 1
            [ "${'$'}(sha256sum "${'$'}b/manifest" | awk '{print ${'$'}1}')" = "${'$'}(cat "${'$'}b/ready")" ] || return 1
            [ "${'$'}(cd "${'$'}b" && sha256sum ${'$'}members)" = "${'$'}(cat "${'$'}b/manifest")" ] || return 1
            [ -s "${'$'}b/product" ] && [ -s "${'$'}b/fingerprint" ] || return 1
            [ "${'$'}(cat "${'$'}b/product")" = "${'$'}(getprop ro.product.name)" ] || return 1
            [ "${'$'}(cat "${'$'}b/fingerprint")" = "${'$'}(getprop ro.build.fingerprint)" ] || return 1
            [ "${'$'}(wc -c < "${'$'}b/serial-sha256")" = 65 ] && grep -Eq '^[a-f0-9]{64}${'$'}' "${'$'}b/serial-sha256" || return 1
            current_unit=${'$'}(unit_identity) || return 1
            [ "${'$'}(cat "${'$'}b/serial-sha256")" = "${'$'}current_unit" ] || return 1
            [ "${'$'}(wc -c < "${'$'}b/accountInfo")" -ge 1 ] && [ "${'$'}(wc -c < "${'$'}b/accountInfo")" -le 8192 ] || return 1
            grep -Eq '^[0-9]{1,10}:[0-9]{1,10}:[0-7]{3,4}${'$'}' "${'$'}b/metadata" || return 1
            [ "${'$'}(wc -l < "${'$'}b/metadata")" = 1 ] && [ "${'$'}(wc -l < "${'$'}b/context")" = 1 ] || return 1
            valid_context "${'$'}(cat "${'$'}b/context")" || return 1
            for p in ${'$'}properties; do
                [ "${'$'}(wc -l < "${'$'}b/${'$'}p")" = 1 ] && [ "${'$'}(wc -c < "${'$'}b/${'$'}p")" -le 4096 ] || return 1
            done
        }
        can_switch() {
            if [ -e "${'$'}journal" ] || [ -L "${'$'}journal" ]; then
                protected "${'$'}journal" && [ "${'$'}(stat -c '%s' "${'$'}journal")" -le 64 ] || return 1
                [ "${'$'}(wc -l < "${'$'}journal")" = 1 ] && grep -Eq '^OVC_GUEST_STATE_V2 (switch|restore) verified${'$'}' "${'$'}journal" || return 1
            fi
        }
        verify_state() {
            regular "${'$'}account" || return 1
            if [ "${'$'}action" = restore ]; then
                cmp -s "${'$'}backup/accountInfo" "${'$'}account" || return 1
                [ "${'$'}(stat -c '%u:%g:%a' "${'$'}account")" = "${'$'}(cat "${'$'}backup/metadata")" ] || return 1
                for p in ${'$'}properties; do [ "${'$'}(getprop "${'$'}p")" = "${'$'}(cat "${'$'}backup/${'$'}p")" ] || return 1; done
            else
                printf '%s' "${'$'}record" | cmp -s - "${'$'}account" || return 1
                [ "${'$'}(stat -c '%u:%g:%a' "${'$'}account")" = 1000:1000:444 ] || return 1
                [ "${'$'}(getprop qinggan.account.aid)" = "${'$'}aid" ] && [ "${'$'}(getprop qinggan.account.uid)" = "${'$'}uid" ] || return 1
                [ "${'$'}(getprop qinggan.account.phone)" = 0 ] && [ "${'$'}(getprop com.qinggan.user_started)" = closeLoginView ] || return 1
            fi
            [ "${'$'}(context "${'$'}account")" = "${'$'}label" ]
        }
        write_journal() {
            [ ! -L "${'$'}journal" ] || return 1
            if [ -e "${'$'}journal" ]; then protected "${'$'}journal" || return 1; fi
            journal_stage=${'$'}(mktemp "${'$'}base/.profile-transaction.XXXXXXXX") || return 1
            printf 'OVC_GUEST_STATE_V2 %s %s\n' "${'$'}action" "${'$'}1" > "${'$'}journal_stage" || return 1
            chmod 600 "${'$'}journal_stage" && sync && mv -f "${'$'}journal_stage" "${'$'}journal" && sync || return 1
            journal_stage=''
        }
        [ "${'$'}(id -u)" = 0 ] && [ ! -L "${'$'}base" ] && [ -d "${'$'}base" ] || exit 79
        [ "${'$'}(stat -c '%u:%g:%a' "${'$'}base")" = 0:0:700 ] || exit 79
        case "${'$'}(getprop ro.product.name)" in f0506h|f6802h|f6805h|f0505h) ;; *) exit 79;; esac
        [ "${'$'}(readlink -f /private/configs/token)" = /private/configs/token ] && [ ! -L "${'$'}account" ] || exit 79
        if [ "${'$'}action" = prepare ]; then
            can_switch || exit 79
            if [ -e "${'$'}backup" ] || [ -L "${'$'}backup" ]; then
                verify "${'$'}backup" || exit 79
            else
                [ ! -e "${'$'}journal" ] && [ ! -L "${'$'}journal" ] || exit 79
                regular "${'$'}account" || exit 79
                stamp=${'$'}(stat -c '%u:%g:%a:%d:%i:%s:%Y:%Z' "${'$'}account")
                [ "${'$'}(stat -c '%s' "${'$'}account")" -ge 1 ] && [ "${'$'}(stat -c '%s' "${'$'}account")" -le 8192 ] || exit 79
                stage=${'$'}(mktemp -d "${'$'}base/.profile-backup.XXXXXXXX")
                chmod 700 "${'$'}stage"
                printf 'OVC_GUEST_KNOWN_STATE_V2\n' > "${'$'}stage/schema"
                getprop ro.product.name > "${'$'}stage/product"
                getprop ro.build.fingerprint > "${'$'}stage/fingerprint"
                unit_identity > "${'$'}stage/serial-sha256"
                stat -c '%u:%g:%a' "${'$'}account" > "${'$'}stage/metadata"
                context "${'$'}account" > "${'$'}stage/context"
                dd if="${'$'}account" of="${'$'}stage/accountInfo" bs=8193 count=1 2>/dev/null
                for p in ${'$'}properties; do getprop "${'$'}p" > "${'$'}stage/${'$'}p"; done
                regular "${'$'}account" && cmp -s "${'$'}account" "${'$'}stage/accountInfo" || exit 79
                [ "${'$'}(stat -c '%u:%g:%a:%d:%i:%s:%Y:%Z' "${'$'}account")" = "${'$'}stamp" ] || exit 79
                [ "${'$'}(context "${'$'}account")" = "${'$'}(cat "${'$'}stage/context")" ] || exit 79
                for p in ${'$'}properties; do [ "${'$'}(getprop "${'$'}p")" = "${'$'}(cat "${'$'}stage/${'$'}p")" ] || exit 79; done
                (cd "${'$'}stage" && sha256sum ${'$'}members) > "${'$'}stage/manifest"
                sha256sum "${'$'}stage/manifest" | awk '{print ${'$'}1}' > "${'$'}stage/ready"
                chmod 600 "${'$'}stage"/*
                verify "${'$'}stage" || exit 79
                sync
                [ ! -e "${'$'}backup" ] && [ ! -L "${'$'}backup" ] || exit 79
                mv "${'$'}stage" "${'$'}backup"
                stage=''
                sync
            fi
        else
            verify "${'$'}backup" || exit 79
            if [ "${'$'}action" = switch ]; then can_switch || exit 79; fi
            write_journal pending || exit 79
            new=${'$'}(mktemp /private/configs/token/.ovc-account.XXXXXXXX)
            label=${'$'}(cat "${'$'}backup/context")
            if [ "${'$'}action" = restore ]; then
                cat "${'$'}backup/accountInfo" > "${'$'}new"
                IFS=: read -r owner group mode < "${'$'}backup/metadata"
                chown "${'$'}owner:${'$'}group" "${'$'}new"; chmod "${'$'}mode" "${'$'}new"
            else
                printf '%s' "${'$'}record" > "${'$'}new"
                chown system:system "${'$'}new"; chmod 0444 "${'$'}new"
            fi
            if [ "${'$'}label" != '?' ]; then chcon "${'$'}label" "${'$'}new"; fi
            sync
            [ ! -L "${'$'}account" ] || exit 79
            mv -f "${'$'}new" "${'$'}account"
            new=''
            if [ "${'$'}action" = restore ]; then
                for p in ${'$'}properties; do value=${'$'}(cat "${'$'}backup/${'$'}p"); setprop "${'$'}p" "${'$'}value"; done
            else
                setprop qinggan.account.aid "${'$'}aid"; setprop qinggan.account.uid "${'$'}uid"
                setprop qinggan.account.phone 0; setprop com.qinggan.user_started closeLoginView
            fi
            verify_state || exit 79
            am broadcast -f 16777216 -a com.qinggan.account.userinfo.changed >/dev/null
            am broadcast -f 16777216 -a com.qinggan.account.logined >/dev/null
            sync
            verify_state || exit 79
            write_journal verified || exit 79
        fi
        printf 'OVC_GUEST_STATE_V2 %s verified\n' "${'$'}action"
    """.trimIndent()
}

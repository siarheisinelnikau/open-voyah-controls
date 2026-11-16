/** Read-only launch checks before taking ownership of a physical key-down. */
export function createActionPreflight(Java, buildIntent) {
    const Process = Java.use('android.os.Process');
    return (context, action) => {
        try {
            const intent = buildIntent(action);
            if (intent === null) return false;
            const resolved = context.getPackageManager().resolveActivity(intent, 0x00010000);
            if (resolved === null) return false;
            const info = resolved.activityInfo.value;
            const app = info?.applicationInfo.value;
            if (!info || !app || !info.enabled.value || !app.enabled.value || (app.flags.value & 0x40000000) !== 0) return false;
            const uid = Process.myUid();
            if (app.uid.value !== uid && !info.exported.value) return false;
            const permission = info.permission.value;
            return !permission || String(permission).length === 0
                || context.checkPermission(String(permission), Process.myPid(), uid) === 0;
        } catch (_) { return false; }
    };
}

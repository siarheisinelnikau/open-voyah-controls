// Replace exact dictionary matches in Android text rendering.
// Translations are independent data; no vendor payload or license code is loaded.
export function translated(config, packageName, product, source) {
    if (!config || config.schema !== 1 || config.enabled !== true || typeof source !== 'string') return source;
    if (config.products?.length && !config.products.includes(product)) return source;
    const table = config.packages?.[packageName];
    return table && Object.prototype.hasOwnProperty.call(table, source) && typeof table[source] === 'string'
        ? table[source] : source;
}

export function installLocalization(Java) {
    Java.perform(() => {
        const installed = [];
        try {
            const app = Java.use('android.app.ActivityThread').currentApplication();
            const packageName = String(app.getPackageName());
            const product = String(Java.use('android.os.Build').PRODUCT.value);
            const Global = Java.use('android.provider.Settings$Global');
            const resolver = app.getContentResolver();
            const JString = Java.use('java.lang.String');
            let config = null, previous = '';
            const reload = () => {
                try {
                    const raw = String(Global.getString(resolver, 'org.openvoyah.controls.localization') || '');
                    if (raw !== previous) { config = raw ? JSON.parse(raw) : null; previous = raw; }
                } catch (_) { config = null; }
            };
            reload();
            function convert(value) {
                if (value === null) return value;
                const source = String(value), replacement = translated(config, packageName, product, source);
                // Keep spans and OEM object identity when there is no translation.
                return replacement === source ? value : JString.$new(replacement);
            }
            const TextView = Java.use('android.widget.TextView');
            const text = TextView.setText.overload('java.lang.CharSequence', 'android.widget.TextView$BufferType');
            text.implementation = function(value, type) { return text.call(this, convert(value), type); };
            installed.push(text);
            const hint = TextView.setHint.overload('java.lang.CharSequence');
            hint.implementation = function(value) { return hint.call(this, convert(value)); };
            installed.push(hint);
            const typed = Java.use('android.content.res.TypedArray').getText.overload('int');
            typed.implementation = function(index) { return convert(typed.call(this, index)); };
            installed.push(typed);
            // Refresh text already visible when attaching to a running process.
            Java.choose('android.widget.TextView', {
                onMatch(view) {
                    const retained = Java.retain(view);
                    Java.scheduleOnMainThread(() => {
                        try {
                            const old = retained.getText(), next = convert(old);
                            if (next !== old) retained.setText.overload('java.lang.CharSequence').call(retained, next);
                            const oldHint = retained.getHint(), nextHint = convert(oldHint);
                            if (nextHint !== oldHint) hint.call(retained, nextHint);
                        } catch (error) { console.log("OVC_LOCALIZATION_REFRESH_ERROR " + error); }
                        finally { retained.$dispose(); }
                    });
                }, onComplete() { }
            });
            setInterval(() => Java.perform(reload), 1000);
            console.log('OVC_LOCALIZATION_READY ' + packageName);
        } catch (error) {
            for (const method of installed) { try { method.implementation = null; } catch (_) { } }
            console.log('OVC_LOCALIZATION_ERROR ' + error + '; retry in 30s');
            setTimeout(() => installLocalization(Java), 30000);
        }
    });
}

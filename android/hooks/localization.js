// Replace exact dictionary matches in Android text rendering.
// Translations are independent data; no vendor payload or license code is loaded.
import { createReporter } from './report.js';

const KEY = 'org.openvoyah.controls.localization';

function tableFor(config, packageName, product) {
    if (!config || config.schema !== 1 || config.enabled !== true) return null;
    if (config.products?.length && !config.products.includes(product)) return null;
    return config.packages?.[packageName] || null;
}

function lookup(table, source) {
    return table && Object.prototype.hasOwnProperty.call(table, source) && typeof table[source] === 'string'
        ? table[source] : source;
}

export function translated(config, packageName, product, source) {
    return typeof source === 'string' ? lookup(tableFor(config, packageName, product), source) : source;
}

export function installLocalization(Java, report = createReporter('OVC_LOCALIZATION_ERROR')) {
    Java.perform(() => {
        const installed = [];
        try {
            const app = Java.use('android.app.ActivityThread').currentApplication();
            const packageName = String(app.getPackageName());
            const product = String(Java.use('android.os.Build').PRODUCT.value);
            const Global = Java.use('android.provider.Settings$Global');
            const resolver = app.getContentResolver();
            const JString = Java.use('java.lang.String');
            // The dictionary for this package and product, resolved once per changed setting; null when off.
            let table = null, previous = '';
            const reload = () => {
                let raw;
                try { raw = String(Global.getString(resolver, KEY) || ''); }
                catch (error) {
                    // Stay off while the provider is unavailable and read the value again once it is back.
                    table = null;
                    previous = null;
                    report('settings', error);
                    return;
                }
                if (raw === previous) return;
                // Remember the value before parsing, so a rejected document is parsed and reported once.
                previous = raw;
                table = null;
                if (!raw) return;
                try { table = tableFor(JSON.parse(raw), packageName, product); }
                catch (error) { report('config', error, {bytes:raw.length}); }
            };
            reload();
            function convert(value) {
                // Most processes run without a dictionary: skip the JNI toString() for every text then.
                if (table === null || value === null) return value;
                try {
                    const source = String(value), replacement = lookup(table, source);
                    // Keep spans and OEM object identity when there is no translation.
                    return replacement === source ? value : JString.$new(replacement);
                } catch (error) {
                    report('convert', error);
                    return value;
                }
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
                        } catch (error) { report('refresh', error); }
                        finally { retained.$dispose(); }
                    });
                }, onComplete() { }
            });
            setInterval(() => Java.perform(reload), 1000);
            console.log('OVC_LOCALIZATION_READY ' + packageName);
        } catch (error) {
            for (const method of installed) { try { method.implementation = null; } catch (_) { } }
            report('install', error);
            setTimeout(() => installLocalization(Java, report), 30000);
        }
    });
}

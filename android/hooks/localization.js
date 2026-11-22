// Replace exact dictionary matches in Android text rendering.
// Translations are independent data; no vendor payload or license code is loaded.
import { createReporter } from './report.js';
import ru from './localization/ru.json';
import en from './localization/en.json';

const KEY = 'org.openvoyah.controls.localization';
// Built-in dictionaries travel inside the agent; the setting only names one, so it stays small.
const PRESETS = { ru, en };

function applies(config, product) {
    return !!config && config.schema === 1 && config.enabled === true
        && !(config.products?.length && !config.products.includes(product));
}

function add(table, entries) {
    if (!entries || typeof entries !== 'object') return;
    for (const source of Object.keys(entries)) {
        if (typeof entries[source] === 'string') table.set(source, entries[source]);
    }
}

/** One exact-match table for this package: the named preset, then the custom entries over it. Null when off. */
export function tableFor(config, packageName, product, presets = PRESETS) {
    if (!applies(config, product)) return null;
    const table = new Map();
    const preset = typeof config.preset === 'string' && Object.prototype.hasOwnProperty.call(presets, config.preset)
        ? presets[config.preset] : null;
    add(table, preset?.packages?.[packageName]);
    add(table, config.packages?.[packageName]);
    return table.size ? table : null;
}

function lookup(table, source) {
    const value = table?.get(source);
    return value === undefined ? source : value;
}

export function translated(config, packageName, product, source, presets = PRESETS) {
    return typeof source === 'string' ? lookup(tableFor(config, packageName, product, presets), source) : source;
}

export function installLocalization(Java, report = createReporter('OVC_LOCALIZATION_ERROR')) {
    Java.perform(() => {
        let detach = () => { };
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
            function convert(value) {
                // A hook can still run once while it is being removed.
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
            // The hooks exist only while a dictionary applies to this process. With translation off, the launcher's
            // text calls run without passing through Frida at all.
            const installed = [];
            detach = () => {
                while (installed.length) { try { installed.pop().implementation = null; } catch (_) { } }
            };
            const attach = () => {
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
                // getString and getString(id, args) read through getText(int), so a template such as "%1$s connected"
                // is translated before the app formats it, and the formatted text needs no entry of its own.
                const resource = Java.use('android.content.res.Resources').getText.overload('int');
                resource.implementation = function(id) { return convert(resource.call(this, id)); };
                installed.push(resource);
                // Refresh text that is already visible.
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
            };
            // A failed attach after start-up is retried no sooner than 30 s later, like a failed installation.
            let retryAt = 0;
            const follow = () => {
                if (table === null) { detach(); return; }
                if (installed.length || Date.now() < retryAt) return;
                try { attach(); }
                catch (error) {
                    detach();
                    report('install', error);
                    retryAt = Date.now() + 30000;
                }
            };
            reload();
            if (table !== null) attach();
            setInterval(() => Java.perform(() => { reload(); follow(); }), 1000);
            console.log('OVC_LOCALIZATION_READY ' + packageName);
        } catch (error) {
            detach();
            report('install', error);
            setTimeout(() => installLocalization(Java, report), 30000);
        }
    });
}

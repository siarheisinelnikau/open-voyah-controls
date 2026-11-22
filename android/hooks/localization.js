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
                const Resources = Java.use('android.content.res.Resources');
                const resource = Resources.getText.overload('int');
                resource.implementation = function(id) { return convert(resource.call(this, id)); };
                installed.push(resource);
                // Compiled framework code can reach those methods with the call inlined, past the hooks: an app's
                // TextView.setText(int) and a layout's android:text did, on a 97C launcher. So text is also checked
                // where the app hands over a resource id, where it asks for a string, and when a view is shown.
                const plain = TextView.setText.overload('java.lang.CharSequence');
                const fix = view => {
                    // Typed text in an editor is the driver's, never a label.
                    if (!view.onCheckIsTextEditor()) {
                        const old = view.getText(), next = convert(old);
                        if (next !== old) plain.call(view, next);
                    }
                    const oldHint = view.getHint(), nextHint = convert(oldHint);
                    if (nextHint !== oldHint) hint.call(view, nextHint);
                };
                const checked = (method, name) => {
                    method.implementation = function(...args) {
                        const result = method.call(this, ...args);
                        try { fix(this); } catch (error) { report(name, error); }
                        return result;
                    };
                    installed.push(method);
                };
                checked(TextView.setText.overload('int'), 'resource_text');
                checked(TextView.setText.overload('int', 'android.widget.TextView$BufferType'), 'resource_text');
                checked(TextView.setHint.overload('int'), 'resource_text');
                checked(TextView.onAttachedToWindow.overload(), 'attach_text');
                // getString(id, args) formats inside the framework, so a translated template is formatted here.
                const format = Java.use('java.lang.String').format.overload('java.lang.String', '[Ljava.lang.Object;');
                const formatted = (resources, id, args) => {
                    if (table === null) return null;
                    const template = resource.call(resources, id);
                    if (template === null) return null;
                    const source = String(template), replacement = lookup(table, source);
                    return replacement === source ? null : format.call(JString, replacement, args);
                };
                const strings = (owner, resourcesOf) => {
                    const single = owner.getString.overload('int');
                    single.implementation = function(id) { return convert(single.call(this, id)); };
                    installed.push(single);
                    const withArgs = owner.getString.overload('int', '[Ljava.lang.Object;');
                    withArgs.implementation = function(id, args) {
                        try {
                            const value = formatted(resourcesOf(this), id, args);
                            if (value !== null) return value;
                        } catch (error) { report('format', error); }
                        return withArgs.call(this, id, args);
                    };
                    installed.push(withArgs);
                };
                strings(Resources, resources => resources);
                const Context = Java.use('android.content.Context');
                strings(Context, context => context.getResources());
                const contextText = Context.getText.overload('int');
                contextText.implementation = function(id) { return convert(contextText.call(this, id)); };
                installed.push(contextText);
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

import {createActionIntent, createActionLauncher} from './launcher-buttons.js';
import {createActionPreflight} from './action-preflight.js';
import {createCanKeyRouter} from './can-keys.js';
import {createCanEdgeRouter} from './can-edges.js';
import {initializedType} from './java-types.js';
import {createReporter, errorKind} from './report.js';

const failure = code => Object.assign(Error(code), {code});

/** The service-side fan-out avoids adding a second, competing CAN subscriber. */
export function installCanSteering(Java, retries = 10, report = createReporter('OVC_CAN_ERROR')) {
    Java.perform(() => {
        let callback;
        let phase = 'process';
        try {
            const app = Java.use('android.app.ActivityThread').currentApplication();
            if (app === null && retries > 0) {
                setTimeout(() => installCanSteering(Java, retries - 1, report), 2000);
                return;
            }
            const sdk = Java.use('android.os.Build$VERSION').SDK_INT.value;
            if (app === null || String(app.getPackageName()) !== 'com.qinggan.canbus.service'
                || ![28, 30].includes(sdk)) throw failure('unsupported_process');
            phase = 'constants';
            const Codes = initializedType(Java, 'com.qinggan.canbus.CanBoxCommType$KEY_CODE');
            const Status = initializedType(Java, 'com.qinggan.canbus.CanBoxCommType$KEY_STATUS');
            const keyValues = [Codes.IVOKA.value, Codes.SWS_KEY_USER_DEFINED.value, Codes.SWS_KEY_PHOTO.value];
            const status = {down: Status.KEYDOWN.value, up: Status.KEYUP.value,
                short: Status.SHORT_PRESSED.value, long: Status.LONG_PRESSED.value};
            for (const values of [keyValues, Object.values(status)]) {
                if (!values.every(value => Number.isInteger(value) && value >= 0)
                    || new Set(values).size !== values.length) throw failure('ambiguous_constants');
            }
            phase = 'callback';
            const method = Java.use('com.qinggan.canbus.service.BaseCanBusComponent')
                .onCarKeyChanged.overload('int', 'int');
            if (method.returnType.className !== 'void') throw failure('callback_signature');
            phase = 'launcher';
            const Global = Java.use('android.provider.Settings$Global');
            const clock = Java.use('android.os.SystemClock');
            const resolver = app.getContentResolver();
            const buildIntent = createActionIntent(Java);
            const launch = createActionLauncher(Java, buildIntent);
            const canLaunch = createActionPreflight(Java, buildIntent);
            const inputs = {
                keys: new Map(keyValues.map((value, index) => [value, ['voice', 'custom', 'camera'][index]])),
                status, now: () => Number(clock.elapsedRealtime()),
                readConfig: () => String(Global.getString(resolver, 'org.openvoyah.controls.panel') || ''),
                launch: action => launch(app, 0, action)
            };
            const terminal = createCanKeyRouter(inputs);
            const edges = createCanEdgeRouter({...inputs, canLaunch:action => canLaunch(app, action),
                schedule:(run, delay) => setTimeout(() => Java.perform(run), delay), cancel:clearTimeout,
                report:(reason, error) => report('gesture', failure(String(reason).replace(/-/g, '_')),
                    error === undefined ? {} : {cause:errorKind(error)})});
            phase = 'install';
            callback = method;
            method.implementation = function (code, event) {
                try { if (edges(code, event) || terminal(code, event)) return; }
                catch (error) { report('route', error); /* Preserve OEM delivery. */ }
                return method.call(this, code, event);
            };
            console.log('OVC_CAN_READY protocol=2 terminal edges sdk=' + sdk);
        } catch (error) {
            if (callback) callback.implementation = null;
            report(phase, error);
        }
    });
}

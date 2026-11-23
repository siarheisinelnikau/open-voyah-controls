import {observeAccountState} from './account-state.js';

/** Read-only contract and backing-file observation. No switch or restore is invoked. */
export function inspectOverseaProfiles(Java, request) {
    if (typeof request !== 'string' || !/^[a-f0-9]{32}$/.test(request)) return;
    Java.perform(() => {
        let status = 'unavailable';
        let account = 'unavailable';
        try {
            const app = Java.use('android.app.ActivityThread').currentApplication();
            if (app !== null && String(app.getPackageName()) === 'com.qinggan.app.login'
                && String(Java.use('android.os.Build').PRODUCT.value) === 'f6811') {
                status = 'unsupported';
                const Login = Java.use('com.qinggan.app.login.LoginUtil');
                const Context = Java.use('android.content.Context');
                const Modifier = Java.use('java.lang.reflect.Modifier');
                for (const slot of ['A', 'B', 'C']) {
                    const name = 'chooseUser' + slot;
                    const callback = Login[name].overload('android.content.Context');
                    const reflected = Login.class.getDeclaredMethod(name, Java.array('java.lang.Class', [Context.class]));
                    if (callback.returnType.className !== 'boolean' || !Modifier.isStatic(reflected.getModifiers())) {
                        throw Error('Unsupported profile method');
                    }
                }
                status = 'api-present';
                account = observeAccountState(Java);
            }
        } catch (_) { /* Report only capability, never account data or exception contents. */ }
        console.log('OVC_OVERSEA_PROFILE_PROBE ' + request + ' ' + status + ' account=' + account);
    });
}

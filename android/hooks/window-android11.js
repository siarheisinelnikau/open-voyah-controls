export function android11WindowHook(Java, intercept) {
    const starter = Java.use('com.android.server.wm.ActivityStarter');
    const entries = starter.setInitialState.overloads.filter(entry => {
        const types = entry.argumentTypes.map(type => type.className);
        return entry.returnType.className === 'void' && types[0] === 'com.android.server.wm.ActivityRecord'
            && types[1] === 'android.app.ActivityOptions' && types[2] === 'com.android.server.wm.Task';
    });
    if (entries.length !== 1) throw Error('Android 11 window start signature unavailable');
    intercept(entries[0]);
}

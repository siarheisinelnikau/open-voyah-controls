export function android9WindowHook(Java, intercept) {
    const starter = Java.use('com.android.server.am.ActivityStarter');
    const entry = starter.setInitialState.overload(
        'com.android.server.am.ActivityRecord', 'android.app.ActivityOptions', 'com.android.server.am.TaskRecord',
        'boolean', 'int', 'com.android.server.am.ActivityRecord',
        'android.service.voice.IVoiceInteractionSession', 'com.android.internal.app.IVoiceInteractor'
    );
    intercept(entry);
}

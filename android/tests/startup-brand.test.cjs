const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = ['report.js', 'startup-brand.js'].map(name => fs.readFileSync(__dirname + '/../hooks/' + name, 'utf8')
    .replace(/^import .*$/gm, '').replace(/^export /gm, '')).join('\n');

function fixture() {
    const state = {boot: 7, saved: -1, clock: 0, focused: false, enabled: true, savedSuccessfully: true,
        homes: 1, homePackage: 'com.example.launcher', launches: [], logs: []};
    const view = (screen, shown = true) => ({
        isAttachedToWindow: () => true, isShown: () => shown, getWindowVisibility: () => 0,
        getDisplay: () => ({getDisplayId: () => screen})
    });
    const home = {
        isFinishing: () => false, hasWindowFocus: () => state.focused,
        getWindow: () => ({getDecorView: () => view(0)}),
        startActivity: intent => state.launches.push(intent)
    };
    const launcher = {
        getContentResolver: () => ({}), getPackageName: () => 'com.example.launcher',
        getSharedPreferences: () => ({
            getInt: () => state.saved,
            edit: () => ({putInt: (key, value) => ({commit() {
                assert.equal(key, 'last_boot');
                if (state.savedSuccessfully) state.saved = value;
                return state.savedSuccessfully;
            }})})
        }),
        getPackageManager: () => ({resolveActivity: () => ({activityInfo:{value:{
            packageName:{value:state.homePackage}, name:{value:'com.example.launcher.Home'}, targetActivity:{value:null}
        }}})})
    };
    const classes = {
        'android.os.SystemClock': {elapsedRealtime: () => state.clock},
        'android.provider.Settings$Global': {getInt: () => state.boot},
        'android.animation.ValueAnimator': {areAnimatorsEnabled: () => state.enabled},
        'android.content.Intent': {$new: action => ({action, addCategory() {}, addFlags() {},
            setClassName(pkg, name) { this.component = [pkg, name]; }
        })}
    };
    const Java = {use: name => classes[name], choose(name, callbacks) {
        assert.equal(name, 'com.example.launcher.Home');
        for (let i = 0; i < state.homes; i++) if (callbacks.onMatch(home) === 'stop') break;
        callbacks.onComplete();
    }};
    const create = vm.runInNewContext(source + '\ncreateStartupBrand', {console:{log: line => state.logs.push(line)}});
    return {state, view, launcher, create: () => create(Java)};
}

test('startup brand waits for a visible central panel and focused home once per Android boot', () => {
    const {state, view, launcher, create} = fixture();
    const startup = create();
    startup.showIfReady(launcher, view(1));
    startup.showIfReady(launcher, view(0, false));
    startup.showIfReady(launcher, view(0));
    assert.equal(state.saved, -1);
    assert.deepEqual(state.launches, []);
    assert.deepEqual(state.logs, []);

    state.focused = true;
    startup.showIfReady(launcher, view(0));
    startup.showIfReady(launcher, view(0));
    create().showIfReady(launcher, view(0));
    assert.equal(state.saved, 7);
    assert.deepEqual(state.launches.map(x => [x.action, ...x.component]), [[
        'org.openvoyah.controls.action.SHOW_BOOT_BRAND', 'org.openvoyah.controls', 'org.openvoyah.controls.LaunchBrandActivity'
    ]]);

    state.boot++;
    state.enabled = false;
    create().showIfReady(launcher, view(0));
    state.enabled = true;
    state.savedSuccessfully = false;
    create().showIfReady(launcher, view(0));
    assert.equal(state.launches.length, 1);
    state.savedSuccessfully = true;
    const expired = create();
    assert.equal(expired.pending(), true);
    state.clock = 31_000;
    expired.showIfReady(launcher, view(0));
    assert.equal(state.launches.length, 1);
    create().showIfReady(launcher, view(0));
    assert.equal(state.launches.length, 2);
    // Each attachment names its outcome by a code; the text of a failure never reaches the hook log.
    assert.deepEqual(state.logs, [
        'OVC_STARTUP_BRAND outcome=requested',
        'OVC_STARTUP_BRAND outcome=skipped reason=requested_this_boot',
        'OVC_STARTUP_BRAND outcome=skipped reason=animators_disabled',
        'OVC_STARTUP_BRAND outcome=failed kind=marker_not_saved',
        'OVC_STARTUP_BRAND outcome=skipped reason=no_panel_button',
        'OVC_STARTUP_BRAND outcome=requested'
    ]);
});

test('startup brand names why it skipped, including the last unmet condition when its window closes', () => {
    const outcome = (prepare, button) => {
        const {state, view, launcher, create} = fixture();
        prepare(state);
        const startup = create();
        // The panel timer asks every second from the moment the agent is ready; the first call opens the window.
        startup.pending();
        if (button) startup.showIfReady(launcher, button(view));
        state.clock = 30_000;
        assert.equal(startup.pending(), false);
        assert.equal(startup.pending(), false);
        assert.deepEqual(state.launches, []);
        assert.equal(state.saved, -1);
        return state.logs;
    };
    const line = reason => ['OVC_STARTUP_BRAND outcome=skipped reason=' + reason];
    assert.deepEqual(outcome(() => {}), line('no_panel_button'));
    assert.deepEqual(outcome(() => {}, view => view(1)), line('panel_hidden'));
    assert.deepEqual(outcome(() => {}, view => view(0)), line('home_unfocused'));
    assert.deepEqual(outcome(state => { state.homes = 0; }, view => view(0)), line('home_missing'));
    assert.deepEqual(outcome(state => { state.boot = -1; }, view => view(0)), line('boot_count'));
    assert.deepEqual(outcome(state => { state.homePackage = 'com.example.other'; }, view => view(0)), line('other_home'));
});

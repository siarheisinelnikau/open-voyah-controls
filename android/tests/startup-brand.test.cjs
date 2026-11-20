const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync(__dirname + '/../hooks/startup-brand.js', 'utf8').replace(/^export /gm, '');

test('startup brand waits for a visible central panel and focused home once per Android boot', () => {
    let boot = 7, saved = -1, clock = 0, focused = false, enabled = true, savedSuccessfully = true;
    const launches = [];
    const view = (screen, shown = true) => ({
        isAttachedToWindow: () => true, isShown: () => shown, getWindowVisibility: () => 0,
        getDisplay: () => ({getDisplayId: () => screen})
    });
    const home = {
        isFinishing: () => false, hasWindowFocus: () => focused,
        getWindow: () => ({getDecorView: () => view(0)}),
        startActivity: intent => launches.push(intent)
    };
    const launcher = {
        getContentResolver: () => ({}), getPackageName: () => 'com.example.launcher',
        getSharedPreferences: () => ({
            getInt: () => saved,
            edit: () => ({putInt: (key, value) => ({commit() {
                assert.equal(key, 'last_boot');
                if (savedSuccessfully) saved = value;
                return savedSuccessfully;
            }})})
        }),
        getPackageManager: () => ({resolveActivity: () => ({activityInfo:{value:{
            packageName:{value:'com.example.launcher'}, name:{value:'com.example.launcher.Home'}, targetActivity:{value:null}
        }}})})
    };
    const classes = {
        'android.os.SystemClock': {elapsedRealtime: () => clock},
        'android.provider.Settings$Global': {getInt: () => boot},
        'android.animation.ValueAnimator': {areAnimatorsEnabled: () => enabled},
        'android.content.Intent': {$new: action => ({action, addCategory() {}, addFlags() {},
            setClassName(pkg, name) { this.component = [pkg, name]; }
        })}
    };
    const Java = {use: name => classes[name], choose(name, callbacks) {
        assert.equal(name, 'com.example.launcher.Home');
        callbacks.onMatch(home);
        callbacks.onComplete();
    }};
    const create = vm.runInNewContext(source + '\ncreateStartupBrand', {console:{log() {}}});
    const startup = create(Java);
    startup.showIfReady(launcher, view(1));
    startup.showIfReady(launcher, view(0, false));
    startup.showIfReady(launcher, view(0));
    assert.equal(saved, -1);
    assert.deepEqual(launches, []);

    focused = true;
    startup.showIfReady(launcher, view(0));
    startup.showIfReady(launcher, view(0));
    create(Java).showIfReady(launcher, view(0));
    assert.equal(saved, 7);
    assert.deepEqual(launches.map(x => [x.action, ...x.component]), [[
        'org.openvoyah.controls.action.SHOW_BOOT_BRAND', 'org.openvoyah.controls', 'org.openvoyah.controls.LaunchBrandActivity'
    ]]);

    boot++;
    enabled = false;
    create(Java).showIfReady(launcher, view(0));
    enabled = true;
    savedSuccessfully = false;
    create(Java).showIfReady(launcher, view(0));
    assert.equal(launches.length, 1);
    savedSuccessfully = true;
    const expired = create(Java);
    assert.equal(expired.pending(), true);
    clock = 31_000;
    expired.showIfReady(launcher, view(0));
    assert.equal(launches.length, 1);
    create(Java).showIfReady(launcher, view(0));
    assert.equal(launches.length, 2);
});

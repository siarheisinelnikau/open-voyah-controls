const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const launcher = fs.readFileSync(path.join(__dirname, '../hooks/launcher-buttons.js'), 'utf8')
    .replace(/^import .*;$/gm, '').replace(/^export /gm, '');

function buildAction() {
    const Intent = {$new(action) {
        return {action, extras: {},
            addFlags() {},
            setComponent(component) { this.component = component; },
            putExtra: {overload(...types) {
                assert.deepEqual(types, ['java.lang.String', 'java.lang.String']);
                return function (name, value) { this.extras[name] = value; };
            }}
        };
    }};
    const Component = {$new: (packageName, className) => ({packageName, className})};
    return vm.runInNewContext(launcher + '\ncreateActionIntent(Java)', {
        Java: {use: name => ({'android.content.Intent': Intent, 'android.content.ComponentName': Component})[name]}
    });
}

test('short and long routines reach only the protected steering entry', () => {
    const build = buildAction();
    const id = '10000000-0000-4000-8000-000000000001';
    for (const slot of ['voiceShort', 'voiceLong', 'customShort', 'customLong', 'cameraShort', 'cameraLong']) {
        const intent = build({kind: 'RUN_ROUTINE', steeringSlot: slot, component: id});
        assert.equal(intent.action, 'org.openvoyah.controls.action.STEERING_ROUTINE');
        assert.equal(intent.component.className, 'org.openvoyah.controls.SteeringRoutineActivity');
        assert.equal(intent.extras['org.openvoyah.controls.extra.STEERING_SLOT'], slot);
        assert.equal(intent.extras['org.openvoyah.controls.extra.STEERING_ROUTINE_ID'], id);
    }
    assert.equal(build({kind: 'RUN_ROUTINE', steeringSlot: 'navbar01', component: id}), null);
    assert.equal(build({kind: 'RUN_ROUTINE', component: id}), null);
    assert.equal(build({kind: 'RUN_ROUTINE', steeringSlot: 'voiceShort', component: 'org.example/.Main'}), null);
});

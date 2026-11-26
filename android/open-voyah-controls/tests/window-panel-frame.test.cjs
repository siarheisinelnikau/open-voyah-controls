const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const source = fs.readFileSync(path.join(__dirname, '../hooks/window-panel-frame.js'), 'utf8').replace(/^export /gm, '');
const edge = vm.runInNewContext(source + '\nlauncherPanelEdge');
const display = [0, 0, 1920, 720];
const panel = {pkg:'com.qinggan.app.launcher', type:2110, title:'MainScreenNavigationBar', displayId:0,
    alive:true, visible:true, frame:[0,0,145,720]};

test('the verified left panel can take both full and compact measured widths', () => {
    assert.equal(edge([panel], display), 145);
    assert.equal(edge([{...panel, frame:[0,0,64,720]}], display), 64);
    assert.equal(edge([{...panel, frame:[32,0,112,720]}], [32,0,1952,720]), 80);
    assert.equal(edge([panel, {...panel, frame:[0,0,64,720]}], display), 145);
});

test('a shorter lowered menu, hidden rail or wrong display does not reserve a panel width', () => {
    for (const overrides of [{frame:[0,0,145,560]}, {visible:false}, {alive:false}, {displayId:1}, {type:2118},
        {pkg:'org.example.app'}, {title:'StatusBar'}, {frame:[145,0,290,720]}]) {
        assert.equal(edge([{...panel, ...overrides}], display), null);
    }
});

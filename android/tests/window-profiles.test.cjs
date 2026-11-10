const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const js = fs.readFileSync(path.join(__dirname, '../hooks/window-profiles.js'), 'utf8')
    .replace(/^export /gm, '');
const policy = vm.runInNewContext(js + '\n({componentKey, launchProfile, profileFor, readNavigator, readWindowConfig, readWindowRequest, windowBounds})');
const target = 'org.example.navigator/org.example.navigator.MainActivity';
const external = 'org.example.player/org.example.player.MainActivity';
const profile = {component:target, width:80, dpi:180, keepPanel:true, panelPx:0};
const config = (profiles = [profile], extra = {}) => JSON.stringify({schema:1, user:0, enabled:true, apps:profiles, ...extra});
const frame = {bounds:[0,0,1920,720], stable:[0,24,1872,720]};

test('a reserved left edge uses the larger of the launcher width and configured inset', () => {
    assert.deepEqual(Array.from(policy.windowBounds(frame, profile, 145)), [490,24,1872,720]);
    assert.deepEqual(Array.from(policy.windowBounds(frame, {...profile,width:100,panelPx:180}, 145)), [180,24,1872,720]);
    assert.deepEqual(Array.from(policy.windowBounds({...frame,stable:[200,24,1872,720]}, {...profile,width:100},145)), [200,24,1872,720]);
    assert.deepEqual(Array.from(policy.windowBounds(frame, {...profile,width:100},0)), [145,24,1872,720]);
    assert.equal(policy.windowBounds(frame, {...profile,width:50},145,900,500), null);
});

test('new apps get the panel layout from both launchers without a saved profile', () => {
    const nav = policy.readNavigator(JSON.stringify({schema:1, enabled:true, actions:{navbar01:{kind:'LAUNCH_APP',component:target}}}));
    const state = policy.readWindowConfig(config());
    const launch = {key:external, user:0};
    for (const caller of ['org.openvoyah.controls', 'com.qinggan.app.launcher']) {
        const defaults = policy.launchProfile(state, nav, {...launch, caller});
        assert.equal(defaults.dpi, 0);
        assert.deepEqual(Array.from(policy.windowBounds(frame, defaults, 145)), [145,24,1872,720]);
    }
    assert.equal(policy.launchProfile(state, nav, {...launch, fromHome:true}).width, 100);
    assert.equal(policy.launchProfile(state, nav, launch), null);
    assert.equal(policy.launchProfile(state, nav, {...launch, fromHome:true, user:10}), null);
    assert.equal(policy.launchProfile(policy.readWindowConfig(config([], {enabled:false})), nav,
        {...launch, fromHome:true}), null);
});

test('older profiles keep their width and density across aliases and always reserve the panel', () => {
    const old = {...profile, keepPanel:false};
    const state = policy.readWindowConfig(config([old]));
    const alias = target.replace('MainActivity','OtherActivity');
    const selected = policy.profileFor(state, alias, 0);
    assert.equal(selected.dpi, 180);
    assert.equal(selected.width, 80);
    assert.deepEqual(Array.from(policy.windowBounds(frame, selected, 145)), [490,24,1872,720]);
    const defaults = policy.profileFor(policy.readWindowConfig(config([])), alias, 0, true);
    assert.deepEqual(Array.from(policy.windowBounds(frame, defaults, 145)), [145,24,1872,720]);
    const nav = policy.readNavigator(JSON.stringify({schema:1, enabled:true, actions:{navbar01:{kind:'LAUNCH_APP',component:target}}}));
    assert.equal(policy.launchProfile(policy.readWindowConfig(config([])), nav, {key:target,user:0}).width, 100);
});

test('the window protocol validates profiles and bounds request lifetime', () => {
    const now = 20000;
    const request = {schema:1, id:'a'.repeat(32), user:0, operation:'apply', component:target, expires:now+4000};
    assert.equal(policy.readWindowRequest(JSON.stringify(request),now).component,target);
    assert.equal(policy.readWindowRequest(JSON.stringify({...request,expires:now-1}),now),null);
    assert.equal(policy.readWindowRequest(JSON.stringify({...request,expires:now+20000}),now),null);
    assert.equal(policy.readWindowRequest(JSON.stringify({...request,component:'com.android.systemui/.Main'}),now),null);
    assert.equal(policy.readWindowRequest(JSON.stringify({...request,component:target+'; id'}),now),null);
    assert.equal(policy.readWindowConfig(config([profile, profile])),null);
    assert.equal(policy.readWindowConfig(config([{...profile,dpi:1800}])),null);
    assert.equal(policy.readWindowConfig(config([{...profile,width:'80'}])),null);
});

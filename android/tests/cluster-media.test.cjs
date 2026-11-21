const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = ['report.js', 'display-events.js'].map(file => fs.readFileSync(path.join(__dirname, '../hooks', file), 'utf8')
    .replace(/^import .*;$/gm, '').replace(/^export /gm, '')).join('\n');

function harness(options = {}) {
    let raw = options.initialRaw ?? '', time = 1000, poll;
    let connectionChecks = 0, attempts = 0;
    const published = [], logs = [];
    const context = {getContentResolver:() => ({})};
    const cluster = {
        getClusterConnect() { connectionChecks++; time += options.connectionDelay ?? 0; return options.connected !== false; },
        updateMediaSrcInfo(info) {
            attempts++;
            if (options.failures > 0) { options.failures--; throw Error('Temporary service failure'); }
            published.push(info);
        }
    };
    const classes = {
        'android.app.ActivityThread':{currentApplication:() => context},
        'com.qinggan.cluster.InstrumentClusterManager':{getInstance:() => options.noManager ? null : cluster},
        'com.qinggan.cluster.info.MediaSrcInfo':{$new:() => ({
            setMediaType(value) { this.type = value; },
            setMediaState(value) { this.state = value; },
            setSongName(value) { this.title = value; },
            setArtist(value) { this.artist = value; },
            setAlbumName(value) { this.album = value; }
        })},
        'com.qinggan.cluster.info.MediaSrcType':{Other:{value:1}},
        'com.qinggan.cluster.info.MediaType':{SimpleAudio:{value:2}},
        'com.qinggan.cluster.info.MediaState':{PLAY:{value:3}, PAUSE:{value:4}, CLEAN:{value:5}},
        'android.provider.Settings$Global':{getString() {
            if (options.providerFailure) throw Error('java.lang.IllegalStateException: provider for private.package is restarting');
            return raw;
        }}
    };
    vm.runInNewContext(source + '\ninstallClusterMedia(Java);', {
        Java:{perform:run => run(), use(name) { assert.ok(classes[name], name); return classes[name]; }},
        setInterval:run => { poll = run; }, Date:{now:() => time}, console:{log:line => logs.push(line)}
    });
    return {
        options, published, logs, tick:() => poll(), advance:ms => { time += ms; },
        setRaw:value => { raw = value; },
        send(value = {}) {
            raw = JSON.stringify({title:'Example track', artist:'Example artist', album:'Example album',
                playing:true, expires:time + 5000, ...value});
        },
        get attempts() { return attempts; }, get connectionChecks() { return connectionChecks; }
    };
}

test('a current media snapshot publishes once after a transient cluster disconnect', () => {
    for (const options of [{connected:false}, {noManager:true}]) {
        const h = harness(options);
        h.send(); h.tick();
        assert.equal(h.published.length, 0);
        h.advance(1000);
        options.connected = true; options.noManager = false;
        h.tick(); h.tick();
        assert.equal(h.published.length, 1);
        assert.equal(h.published[0].title, 'Example track');
        assert.equal(h.attempts, 1);
    }
});

test('a one-shot media publication exception does not consume its still-valid snapshot', () => {
    const h = harness({failures:1});
    h.send(); h.tick();
    assert.equal(h.attempts, 1);
    assert.equal(h.published.length, 0);
    h.advance(1000); h.tick(); h.tick();
    assert.equal(h.attempts, 2);
    assert.equal(h.published.length, 1);
    assert.equal(h.logs.filter(line => line.startsWith('OVC_CLUSTER_MEDIA_ERROR ')).length, 1);
});

test('disconnected and failing media requests stop at their original expiry', () => {
    for (const options of [{connected:false}, {failures:10}]) {
        const h = harness(options);
        h.send(); h.tick();
        const attempts = h.attempts, checks = h.connectionChecks;
        h.advance(5000);
        options.connected = true; options.failures = 0;
        h.tick(); h.advance(1000); h.tick();
        assert.equal(h.published.length, 0);
        assert.equal(h.attempts, attempts);
        assert.equal(h.connectionChecks, checks);
    }
});

test('a slow connection check cannot publish media after the snapshot expires', () => {
    const h = harness({connectionDelay:5000});
    h.send(); h.tick(); h.tick();
    assert.equal(h.connectionChecks, 1);
    assert.equal(h.attempts, 0);
    assert.equal(h.published.length, 0);
});

test('new media replaces a pending snapshot instead of queueing an old title', () => {
    const h = harness({connected:false});
    h.send({title:'Old title'}); h.tick();
    h.advance(1000); h.send({title:'Current title'});
    h.options.connected = true; h.tick(); h.tick();
    assert.deepEqual(h.published.map(info => info.title), ['Current title']);
});

test('malformed, oversized and invalid media snapshots never contact the cluster', () => {
    const valid = {title:'Track', artist:'Artist', album:'Album', playing:true, expires:6000};
    const invalid = ['{', 'null', '[]', 'x'.repeat(4097),
        ...[{title:2}, {artist:null}, {album:'x'.repeat(241)}, {playing:'true'}, {expires:1000}, {expires:7001}]
            .map(value => JSON.stringify({...valid, ...value}))];
    for (const raw of invalid) {
        const h = harness();
        h.setRaw(raw); h.tick(); h.tick();
        assert.equal(h.connectionChecks, 0);
        assert.equal(h.published.length, 0);
        assert.ok(h.logs.filter(line => line.startsWith('OVC_CLUSTER_MEDIA_ERROR ')).length <= 1);
        // A rejected future timestamp is not revived when it enters the allowed range.
        h.advance(2000); h.tick();
        assert.equal(h.connectionChecks, 0);
    }
});

test('attachment validates existing media by expiry and dedupes only published snapshots', () => {
    for (const expires of [999, 6000]) {
        const h = harness({initialRaw:JSON.stringify({title:'Existing title', artist:'Artist', album:'Album', playing:false, expires})});
        h.tick(); h.tick();
        assert.equal(h.published.length, expires > 1000 ? 1 : 0);
        if (h.published.length) assert.equal(h.published[0].state, 4);
    }
});

test('an unavailable settings provider is reported once and the poll recovers', () => {
    const h = harness({providerFailure:true});
    for (let second = 0; second < 5; second++) assert.doesNotThrow(() => h.tick());
    assert.deepEqual(h.logs.filter(line => line.startsWith('OVC_CLUSTER_MEDIA_ERROR')),
        ['OVC_CLUSTER_MEDIA_ERROR stage=settings kind=IllegalStateException']);
    h.options.providerFailure = false;
    h.send(); h.tick();
    assert.equal(h.published.length, 1);
    assert.equal(h.logs.some(line => line.includes('private')), false);
});

test('parse and publication failures name their stage and class without quoting the error', () => {
    const malformed = harness();
    malformed.setRaw('{"title":"private'); malformed.tick(); malformed.tick();
    assert.deepEqual(malformed.logs.filter(line => line.startsWith('OVC_CLUSTER_MEDIA_ERROR')),
        ['OVC_CLUSTER_MEDIA_ERROR stage=parse kind=SyntaxError']);
    const failing = harness({failures:3});
    failing.send(); failing.tick(); failing.tick(); failing.tick();
    assert.equal(failing.attempts, 3);
    assert.deepEqual(failing.logs.filter(line => line.startsWith('OVC_CLUSTER_MEDIA_ERROR')),
        ['OVC_CLUSTER_MEDIA_ERROR stage=publish kind=Error']);
    assert.equal([...malformed.logs, ...failing.logs].some(line => line.includes('private') || line.includes('Temporary')), false);
});

test('media clearing retains its ownership rule and dedupes a successful clear', () => {
    const h = harness();
    h.send({title:'', playing:false}); h.tick();
    assert.equal(h.connectionChecks, 0);
    h.send(); h.tick();
    h.advance(1000); h.send({title:'', artist:'', album:'', playing:false}); h.tick(); h.tick();
    assert.deepEqual(h.published.map(info => info.state), [3, 5]);
});

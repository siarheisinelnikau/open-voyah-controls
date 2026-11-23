const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const load = () => vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../hooks/report.js'), 'utf8')
    .replace(/^export /gm, '') + '\n({createReporter, errorKind})');

test('a failure is named by its code, Java class or error type and never by its message', () => {
    const { errorKind } = load();
    assert.equal(errorKind(Object.assign(Error('private'), {code:'no_space'})), 'no_space');
    assert.equal(errorKind(Object.assign(Error('private'), {code:'Not A Code'})), 'Error');
    assert.equal(errorKind(Error('java.lang.SecurityException: Permission Denial: starting Intent { cmp=private.app/.Main }')),
        'SecurityException');
    assert.equal(errorKind(Error('android.content.ActivityNotFoundException: Unable to find explicit activity class')),
        'ActivityNotFoundException');
    assert.equal(errorKind(Error('java.lang.NoSuchFieldError: mPrivateField')), 'NoSuchFieldError');
    assert.equal(errorKind(new TypeError('cannot read private')), 'TypeError');
    assert.equal(errorKind('a thrown string with private text'), 'Error');
    assert.equal(errorKind(null), 'Error');
    assert.equal(errorKind({toString() { throw Error('hostile'); }}), 'Error');
});

test('each distinct problem is written once and then only at powers of ten', () => {
    const lines = [];
    const report = load().createReporter('OVC_TEST_ERROR', {write:line=>lines.push(line)});
    for (let attempt = 0; attempt < 1000; attempt++) report('click', new TypeError('private'), {slot:'navbar01'});
    report('click', new RangeError('other'), {slot:'navbar01'});
    report('hold', new TypeError('private'));
    assert.deepEqual(lines, [
        'OVC_TEST_ERROR stage=click kind=TypeError slot=navbar01',
        'OVC_TEST_ERROR stage=click kind=TypeError slot=navbar01 repeated=10',
        'OVC_TEST_ERROR stage=click kind=TypeError slot=navbar01 repeated=100',
        'OVC_TEST_ERROR stage=click kind=TypeError slot=navbar01 repeated=1000',
        'OVC_TEST_ERROR stage=click kind=RangeError slot=navbar01',
        'OVC_TEST_ERROR stage=hold kind=TypeError',
    ]);
});

test('the number of tracked problems is bounded and unsafe values cannot shape a line', () => {
    const lines = [];
    const report = load().createReporter('OVC_TEST_ERROR', {limit:3, write:line=>lines.push(line)});
    for (let index = 0; index < 10; index++) report('stage' + index, Error('x'));
    assert.equal(lines.length, 3);
    report('stage0', Error('x'));
    assert.equal(lines.length, 3);

    const unsafe = [];
    load().createReporter('OVC_TEST_ERROR', {write:line=>unsafe.push(line)})(
        'launch\nOVC_READY forged', Error('x'), {'bad key':'value with spaces', component:'org.example/.Main'});
    assert.deepEqual(unsafe, ['OVC_TEST_ERROR stage=invalid kind=Error invalid=invalid component=org.example/.Main']);
});

test('a nested exception class keeps its name in the line', () => {
    const lines = [];
    load().createReporter('OVC_TEST_ERROR', {write:line=>lines.push(line)})(
        'icon', Error('android.content.res.Resources$NotFoundException: Resource ID #0x7f080123'));
    assert.deepEqual(lines, ['OVC_TEST_ERROR stage=icon kind=Resources$NotFoundException']);
});

test('reporting never throws into the hooked method', () => {
    const report = load().createReporter('OVC_TEST_ERROR', {write() { throw Error('log unavailable'); }});
    assert.doesNotThrow(() => report('click', Error('x')));
    assert.doesNotThrow(() => report('click', Error('x'), null));
});

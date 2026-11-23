// Runs the update-in-place parts of the boot supervisor (hooks/supervise.sh) in Bash with a package manager settings
// file and stubbed device commands: the signer pin of Controls (package_signer, signed_package, reviewed_package) and
// the live adoption of a same-signer update (adopt_app_update).
const {test} = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {spawnSync} = require('node:child_process');

const supervisor = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/hooks/supervise.sh'), 'utf8');

/** The text of shell function [name] as defined in supervise.sh, up to the closing brace at column 0. */
function shellFunction(name) {
    const lines = supervisor.split('\n');
    const start = lines.findIndex(line => line.startsWith(`${name}() {`));
    assert.notEqual(start, -1, `supervise.sh defines ${name}`);
    if (lines[start].trim().endsWith('}') && lines[start].trim() !== `${name}() {`) return lines[start];
    const end = lines.findIndex((line, index) => index > start && line === '}');
    return lines.slice(start, end + 1).join('\n');
}

const bash = (() => {
    for (const candidate of [process.env.OVC_BASH, 'C:/Program Files/Git/bin/bash.exe', 'C:/Program Files/Git/usr/bin/bash.exe', 'bash']) {
        if (candidate && spawnSync(candidate, ['-s'], {input: 'true'}).status === 0) return candidate;
    }
    return null;
})();
const tools = bash && spawnSync(bash, ['-s'], {input: 'printf 3082 | xxd -r -p | sha256sum'}).status === 0;
const skip = !bash ? 'Bash is not available' : !tools ? 'xxd or sha256sum is not available' : false;

const run = (script, env = {}) => {
    const result = spawnSync(bash, ['-s'], {input: script, encoding: 'utf8', env: {...process.env, ...env}, timeout: 20000});
    return {status: result.status, stdout: result.stdout.trim(), stderr: result.stderr.trim()};
};
const posix = value => value.replace(/\\/g, '/');

const OWNER_CERT = Buffer.from('3082owner-certificate-der-bytes', 'utf8');
const FOREIGN_CERT = Buffer.from('3082foreign-certificate-der-bytes', 'utf8');
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const APP = 'org.openvoyah.controls';

function sandbox() {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ovc-live-update-'));
    const system = path.join(dir, 'system');
    fs.mkdirSync(system);
    const code = path.join(dir, 'app', '~~a==', `${APP}-b==`);
    fs.mkdirSync(code, {recursive: true});
    fs.writeFileSync(path.join(code, 'base.apk'), 'apk v25\n');
    return {dir, xml: path.join(system, 'packages.xml'), code: posix(code), apk: posix(path.join(code, 'base.apk'))};
}

/** A package manager settings file in the Android 11 text format; the cert key is named by another package. */
function packagesXml({code, version = 25, cert = OWNER_CERT, pastCert = null, extraCert = false, complete = true}) {
    const lines = [
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>",
        '<packages>',
        '    <package name="com.example.first" codePath="/data/app/first" version="3" userId="10050">',
        '        <sigs count="1" schemeVersion="2">',
        `            <cert index="7" key="${cert.toString('hex')}" />`,
        '        </sigs>',
        '    </package>',
        `    <package name="${APP}" codePath="${code}" nativeLibraryPath="${code}/lib" version="${version}" userId="10062">`,
        '        <sigs count="1" schemeVersion="2">',
        '            <cert index="7" />',
        ...(extraCert ? [`            <cert index="9" key="${FOREIGN_CERT.toString('hex')}" />`] : []),
        ...(pastCert ? ['            <pastSigs count="1" schemeVersion="3">',
            `                <cert index="8" key="${pastCert.toString('hex')}" flags="2" />`, '            </pastSigs>'] : []),
        '        </sigs>',
        '    </package>',
    ];
    if (complete) lines.push('</packages>');
    return lines.join('\n') + '\n';
}

function signerScript(box, body, {floor = 24, signer = sha(OWNER_CERT)} = {}) {
    const receipt = posix(path.join(box.dir, 'trust.tsv'));
    fs.writeFileSync(receipt, `signer\t${APP}\t${signer}\t${floor}\n`);
    return [
        'set -u',
        `packages_xml='${posix(box.xml)}'; receipt='${receipt}'`,
        // A file of the host user stands in for one owned by system.
        'stat() { if [ "$2" = %u ]; then echo 1000; else command stat "$@"; fi; }',
        `package_path() { [ "$1" = ${APP} ] && printf '%s\\n' '${box.apk}'; }`,
        'digest() { sha256sum "$1" | awk \'{print $1}\'; }',
        ['record', 'package_signer', 'signed_package', 'reviewed_package'].map(shellFunction).join('\n'),
        body,
    ].join('\n');
}

const signed = (box, options) => run(signerScript(box, `signed_package ${APP} '${box.apk}'; echo "status=$?"`, options)).stdout;

test('the signer pin accepts the same certificate at or above the sealed version', {skip}, () => {
    const box = sandbox();
    fs.writeFileSync(box.xml, packagesXml({code: box.code}));
    assert.equal(signed(box), 'status=0');
    assert.equal(signed(box, {floor: 25}), 'status=0');
    const result = run(signerScript(box, `reviewed_package ${APP}; echo "status=$?"`));
    assert.equal(result.stdout, `${box.apk}\nstatus=0`, result.stderr);
});

test('a foreign signer, a downgrade or a rotated-away certificate is refused', {skip}, () => {
    const box = sandbox();
    fs.writeFileSync(box.xml, packagesXml({code: box.code, cert: FOREIGN_CERT}));
    assert.equal(signed(box), 'status=1', 'foreign signer');
    fs.writeFileSync(box.xml, packagesXml({code: box.code}));
    assert.equal(signed(box, {floor: 26}), 'status=1', 'older than the sealed version');
    fs.writeFileSync(box.xml, packagesXml({code: box.code, cert: FOREIGN_CERT, pastCert: OWNER_CERT}));
    assert.equal(signed(box), 'status=1', 'the pinned certificate only in the signing history');
    fs.writeFileSync(box.xml, packagesXml({code: box.code, extraCert: true}));
    assert.equal(signed(box), 'status=1', 'more than one current signer');
    fs.writeFileSync(box.xml, packagesXml({code: box.code}).replace('version="25"', 'version="25x"'));
    assert.equal(signed(box), 'status=1', 'malformed version');
});

test('package settings that do not describe the installed APK yet are a wait, not a change', {skip}, () => {
    const box = sandbox();
    fs.writeFileSync(box.xml, packagesXml({code: '/data/app/~~old==/org.openvoyah.controls-old=='}));
    assert.equal(signed(box), 'status=2', 'written before this install');
    fs.writeFileSync(box.xml, packagesXml({code: box.code, complete: false}));
    assert.equal(signed(box), 'status=2', 'being written');
    fs.writeFileSync(box.xml, packagesXml({code: box.code}));
    const backup = path.join(path.dirname(box.xml), 'packages-backup.xml');
    fs.writeFileSync(backup, 'previous');
    assert.equal(signed(box), 'status=2', 'moved aside for a rewrite');
    fs.rmSync(backup);
    fs.rmSync(box.xml);
    assert.equal(signed(box), 'status=2', 'absent for a moment');
    const result = run(signerScript(box, `installed=$(reviewed_package ${APP}); echo "status=$?"`));
    assert.equal(result.stdout, 'status=2', 'reviewed_package passes the wait on to check_runtime');
});

test('without a signer row the app keeps its exact APK pin', {skip}, () => {
    const box = sandbox();
    fs.writeFileSync(box.xml, packagesXml({code: box.code}));
    const script = signerScript(box, `
        printf 'package\\t${APP}\\t%s\\t%s\\n' '${box.apk}' "$(digest '${box.apk}')" > "$receipt"
        reviewed_package ${APP} >/dev/null; echo "same=$?"
        printf 'apk v26\\n' > '${box.apk}'
        reviewed_package ${APP} >/dev/null; echo "changed=$?"`);
    assert.equal(run(script).stdout, 'same=0\nchanged=1');
});

/** adopt_app_update with the signer check and the lifetime result scripted per call. */
function adoptScript(box, {signed: signedCodes, lifetime = '0', fixture = 0, signerRow = true}) {
    const receipt = posix(path.join(box.dir, 'trust.tsv'));
    fs.writeFileSync(receipt, signerRow ? `signer\t${APP}\t${sha(OWNER_CERT)}\t24\n` : '');
    return [
        'set -u',
        `receipt='${receipt}'; app=${APP}; fixture=${fixture}; update_wait=6; settle_samples=1; settle_delay=0`,
        `app_path='/data/app/~~old==/${APP}-old==/base.apk'; app_identity=old`,
        `package_path() { printf '%s\\n' '${box.apk}'; }`,
        'file_identity() { echo "new:$1"; }',
        `codes='${signedCodes.join(' ')}'`,
        'signed_package() { set -- $codes; code=$1; shift; [ "$#" = 0 ] || codes="$*"; echo "signed $code" >> "$OVC_CALLS"; return "$code"; }',
        `lifetime_status() { return ${lifetime}; }`,
        'sleep() { echo sleep >> "$OVC_CALLS"; }',
        'log() { printf "%s\\n" "$*" >> "$OVC_CALLS"; }',
        ['record', 'settled', 'adopt_app_update'].map(shellFunction).join('\n'),
        'adopt_app_update; echo "status=$? path=$app_path identity=$app_identity"',
    ].join('\n');
}

function adopt(box, options) {
    const calls = path.join(box.dir, `calls-${crypto.randomUUID()}`);
    fs.writeFileSync(calls, '');
    const result = run(adoptScript(box, options), {OVC_CALLS: posix(calls)});
    return {stdout: result.stdout, calls: fs.readFileSync(calls, 'utf8').trim().split('\n').filter(Boolean), stderr: result.stderr};
}

test('a same-signer update is adopted once the package manager records it, keeping the activation', {skip}, () => {
    const box = sandbox();
    const result = adopt(box, {signed: [2, 2, 0]});
    assert.equal(result.stdout, `status=0 path=${box.apk} identity=new:${box.apk}`, result.stderr);
    assert.deepEqual(result.calls, ['signed 2', 'sleep', 'signed 2', 'sleep', 'signed 0',
        'OVC_WORKER_NOTICE Controls was updated by the same signer. Activation kept.']);
});

test('a foreign or older APK, a changed key, or no record within the wait is not adopted', {skip}, () => {
    const box = sandbox();
    assert.match(adopt(box, {signed: [1]}).stdout, /^status=1 path=\/data\/app\/~~old==/);
    assert.match(adopt(box, {signed: [0], lifetime: '1'}).stdout, /^status=1 /);
    assert.match(adopt(box, {signed: [0], lifetime: '3'}).stdout, /^status=1 /);
    const waited = adopt(box, {signed: [2]});
    assert.match(waited.stdout, /^status=1 /);
    assert.equal(waited.calls.filter(call => call === 'sleep').length, 3, 'bounded by update_wait');
});

test('a receipt without the signer row, or the emulator fixture, never adopts an update', {skip}, () => {
    const box = sandbox();
    for (const options of [{signed: [0], signerRow: false}, {signed: [0], fixture: 1}]) {
        const result = adopt(box, options);
        assert.match(result.stdout, /^status=1 /, JSON.stringify(options));
        assert.deepEqual(result.calls, [], 'nothing was checked');
    }
});

test('the receipt schema admits one signer row for Controls and the loop adopts before it revokes', () => {
    const body = shellFunction('reviewed_receipt');
    assert.match(body, /\[ "\$#" = 4 \] && \[ "\$2" = org\.openvoyah\.controls \] && \[ "\$rr_signer" = 0 \] \|\| return 1/);
    const loop = supervisor.slice(supervisor.indexOf('while [ -n "$child_start" ]'));
    assert.ok(loop.indexOf('! settled app_unchanged && ! adopt_app_update') < loop.indexOf('revoke_activation package'));
});

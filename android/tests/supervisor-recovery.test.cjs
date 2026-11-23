// Runs the crash-recovery parts of the boot supervisor (hooks/supervise.sh) in Bash with stubbed device commands:
// settled checks, the restart policy, recovery reasons and the no-PC re-arm (--recover).
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {spawnSync} = require('node:child_process');

const supervisor = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/hooks/supervise.sh'), 'utf8');
const bootStart = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/hooks/boot-start.sh'), 'utf8');

/** The text of shell function [name] as defined in supervise.sh (one-line or up to the closing brace at column 0). */
function shellFunction(name) {
    const lines = supervisor.split('\n');
    const start = lines.findIndex(line => line.startsWith(`${name}() {`) || line.startsWith(`${name}() (`));
    assert.notEqual(start, -1, `supervise.sh defines ${name}`);
    const first = lines[start];
    if (/[})]\s*$/.test(first) && first.trim() !== `${name}() {`) return first;
    const close = first.endsWith('(') ? ')' : '}';
    const end = lines.findIndex((line, index) => index > start && line === close);
    return lines.slice(start, end + 1).join('\n');
}

const bash = (() => {
    for (const candidate of [process.env.OVC_BASH, 'C:/Program Files/Git/bin/bash.exe', 'C:/Program Files/Git/usr/bin/bash.exe', 'bash']) {
        if (candidate && spawnSync(candidate, ['-s'], {input: 'true'}).status === 0) return candidate;
    }
    return null;
})();

function run(script, env = {}) {
    // Through stdin: Windows argument quoting would mangle a script passed with -c.
    const result = spawnSync(bash, ['-s'], {input: script, encoding: 'utf8', env: {...process.env, ...env}, timeout: 20000});
    return {status: result.status, stdout: result.stdout.trim(), stderr: result.stderr.trim()};
}

function sandbox() {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ovc-supervisor-'));
    const root = path.join(dir, 'runtime').replace(/\\/g, '/');
    fs.mkdirSync(root);
    const boot = path.join(dir, 'boot_id').replace(/\\/g, '/');
    fs.writeFileSync(boot, '11111111-2222-3333-4444-555555555555\n');
    return {dir, root, boot};
}
const CURRENT = '11111111-2222-3333-4444-555555555555';
const EARLIER = '99999999-8888-7777-6666-555555555555';

/** Functions under test plus stubs for the device-only checks they call. */
function recoveryScript({root, boot}, body, stubs = '') {
    const names = ['is_number', 'visual_uuid', 'channel_suffix', 'enabled_channels', 'record_recovery_reason', 'revoke_activation',
        'settled', 'recover_decision', 'attach_outcome', 'recover_report', 'recover_activation'];
    const functions = names.map(shellFunction).join('\n').replaceAll('/proc/sys/kernel/random/boot_id', '"$OVC_BOOT_FILE"');
    return [
        'set -u',
        `root='${root}'; fixture=0; injector=/data/adb/open-voyah-controls/open-voyah-controls-frida-17.26.2`,
        'recovery_required=0; recovery_recorded=0; settle_samples=3; settle_delay=0',
        functions,
        // Ownership and SELinux checks need a device; here a regular file or directory stands in for a root-owned one.
        'private_dir() { [ -d "$1" ] && [ ! -L "$1" ]; }',
        'private_file() { [ -f "$1" ] && [ ! -L "$1" ]; }',
        'log() { printf "%s\\n" "$*" >> "$root/worker.log"; }',
        'cmdline_matches() { :; }',
        'visual_session_state() { cat "$dir/visual.state" 2>/dev/null || echo absent; }',
        'visual_session_descriptor() { echo "$(visual_session_state):$(printf %064d 0)"; }',
        'clear_visual_session() { echo "$1" > "$dir/visual.cleared"; }',
        'check_runtime() { return "${OVC_VERIFY:-0}"; }',
        'record() { [ "$1" = artifact ] && [ -f "$root/$2" ] && echo sha; }',
        'artifact_ok() { [ -f "$root/$1" ]; }',
        stubs,
        body,
    ].join('\n');
}

function stageDeployment(root, channels = ['main', 'locale-hiboard', 'window']) {
    for (const name of channels) {
        const dir = name === 'main' ? root : path.join(root, name);
        fs.mkdirSync(dir, {recursive: true});
        fs.writeFileSync(path.join(dir, 'launcher-buttons.js'), 'agent\n');
        fs.writeFileSync(path.join(dir, 'enabled'), '');
        fs.writeFileSync(path.join(dir, 'failure.count'), '5\n');
    }
}
const exists = (...parts) => fs.existsSync(path.join(...parts));

const skip = bash ? false : 'Bash is not available';

test('the supervisor and boot entry scripts parse in POSIX sh', {skip}, () => {
    for (const [name, text] of [['supervise.sh', supervisor], ['boot-start.sh', bootStart]]) {
        const result = spawnSync(bash, ['--posix', '-n'], {input: text, encoding: 'utf8'});
        assert.equal(result.status, 0, `${name}: ${result.stderr}`);
    }
});

test('a check that fails once and then passes is healthy; only a persistent failure counts', {skip}, () => {
    const box = sandbox();
    const script = recoveryScript(box, `
        calls=0
        flaky() { calls=$((calls+1)); [ "$calls" -ge 2 ]; }
        broken() { calls=$((calls+1)); return 1; }
        locked() { calls=$((calls+1)); return 2; }
        calls=0; settled flaky; echo "flaky=$? calls=$calls"
        calls=0; settled broken; echo "broken=$? calls=$calls"
        calls=0; settled locked; echo "locked=$? calls=$calls"
        lifetime() { calls=$((calls+1)); return 3; }
        calls=0; settled lifetime; echo "lifetime=$? calls=$calls"`);
    assert.equal(run(script, {OVC_BOOT_FILE: box.boot}).stdout,
        'flaky=0 calls=2\nbroken=1 calls=3\nlocked=2 calls=1\nlifetime=3 calls=3');
});

test('a silent package manager is unknown (wait), while an empty answer still revokes', {skip}, () => {
    const box = sandbox();
    const key = path.join(box.dir, 'key').replace(/\\/g, '/');
    fs.writeFileSync(key, 'k'.repeat(64) + '\n');
    const lifetime = shellFunction('lifetime_status');
    const script = `set -u
        app=org.openvoyah.controls; app_key='${key}'; access='${box.root}'
        user_unlocked() { return 0; }
        record() { case "$3" in 3) echo 10062;; 4) echo stamp;; 5) echo hash;; esac; }
        ${lifetime}
        pm() { return 1; }; lifetime_status; echo "silent=$?"
        pm() { return 0; }; lifetime_status; echo "uninstalled=$?"`;
    assert.equal(run(script).stdout, 'silent=2\nuninstalled=1');
});

test('the attach outcome separates target restarts and closed translation apps from failures', {skip}, () => {
    const box = sandbox();
    const cases = [
        // changed system_same healthy elapsed locale -> outcome
        ['1 1 1 40 0', 'restart'],   // the launcher crashed after a healthy minute
        ['1 0 1 40 0', 'restart'],   // Android's framework restarted
        ['1 1 1 40 1', 'exited'],    // a translation app was closed
        ['1 0 1 40 1', 'restart'],
        ['1 1 1 10 0', 'attempt'],   // died within 20 s: may be the agent, stays on the retry budget
        ['1 1 0 40 0', 'attempt'],   // never confirmed readiness
        ['0 1 1 40 0', 'attempt'],   // the injector ended but the target still runs
    ];
    const script = recoveryScript(box, cases.map(([args]) => `attach_outcome ${args}`).join('\n'));
    assert.deepEqual(run(script, {OVC_BOOT_FILE: box.boot}).stdout.split('\n'), cases.map(([, outcome]) => outcome));
});

test('the re-arm decision needs a recorded reason and a reboot for the boot job, and always a verified deployment', {skip}, () => {
    const box = sandbox();
    const cases = [
        ['auto recorded 0 clear 0', 'recovered:verified'],
        ['auto absent 0 clear 0', 'pc:unrecorded'],      // PC rollback, app or older supervisor: not on its own
        ['auto recorded 1 clear 0', 'reboot:same-boot'], // the boot job never undoes a revocation of its own boot
        ['auto recorded 0 pending 0', 'reboot:visual'],
        ['auto recorded 0 clear 1', 'pc:deployment'],    // a changed APK, runtime or agent
        ['auto recorded 0 clear 3', 'pc:lifetime'],      // reinstalled app or new key
        ['auto recorded 0 clear 2', 'waiting:locked'],
        ['manual absent 1 clear 0', 'recovered:verified'],
        ['manual recorded 1 pending 0', 'reboot:visual'],
        ['manual absent 0 clear 1', 'pc:deployment'],
    ];
    const script = recoveryScript(box, cases.map(([args]) => `recover_decision ${args}`).join('\n'));
    assert.deepEqual(run(script, {OVC_BOOT_FILE: box.boot}).stdout.split('\n'), cases.map(([, outcome]) => outcome));
});

test('a revocation records its cause and the enabled channels once per boot', {skip}, () => {
    const box = sandbox();
    stageDeployment(box.root);
    const script = recoveryScript(box, `
        revoke_activation lifetime; echo "first=$?"
        revoke_activation visual; echo "second=$?"`, `private_dir() { [ -d "$1" ]; }`);
    // revoke_activation also checks /data/adb, which the stub accepts only if it exists; point it at the sandbox.
    const result = run(script.replaceAll('/data/adb', box.dir.replace(/\\/g, '/')), {OVC_BOOT_FILE: box.boot});
    assert.equal(result.stdout, 'first=0\nsecond=0', result.stderr);
    assert.equal(fs.readFileSync(path.join(box.root, 'recovery.reason'), 'utf8'), `${CURRENT} lifetime main,locale-hiboard,window\n`);
    for (const marker of ['recovery.blocked', 'recovery.visual-pending']) {
        assert.equal(fs.readFileSync(path.join(box.root, marker), 'utf8'), `${CURRENT}\n`);
    }
    assert.equal(exists(box.root, 'enabled'), false);
    assert.equal(exists(box.root, 'window', 'enabled'), false);
    assert.match(fs.readFileSync(path.join(box.root, 'worker.log'), 'utf8'), /Activation revoked reason=lifetime\./);
});

function revoked(box, {boot = EARLIER, reason = `${EARLIER} lifetime main,window`, channels = ['main', 'locale-hiboard', 'window']} = {}) {
    stageDeployment(box.root, channels);
    for (const name of channels) fs.rmSync(path.join(name === 'main' ? box.root : path.join(box.root, name), 'enabled'));
    fs.writeFileSync(path.join(box.root, 'recovery.blocked'), `${boot}\n`);
    fs.writeFileSync(path.join(box.root, 'recovery.visual-pending'), `${boot}\n`);
    if (reason) fs.writeFileSync(path.join(box.root, 'recovery.reason'), `${reason}\n`);
}

test('after a reboot the boot job re-arms a verified deployment with the channels that were on', {skip}, () => {
    const box = sandbox();
    revoked(box);
    fs.writeFileSync(path.join(box.root, 'window', 'visual.state'), 'changed\n');
    const result = run(recoveryScript(box, 'recover_activation auto; echo "status=$?"'), {OVC_BOOT_FILE: box.boot});
    assert.equal(result.stdout, 'OVC_RECOVER outcome=recovered reason=lifetime channels=main,window\nstatus=0', result.stderr);
    for (const marker of ['recovery.blocked', 'recovery.visual-pending', 'recovery.reason']) assert.equal(exists(box.root, marker), false);
    assert.equal(exists(box.root, 'enabled'), true);
    assert.equal(exists(box.root, 'window', 'enabled'), true);
    assert.equal(exists(box.root, 'locale-hiboard', 'enabled'), false, 'a channel that was off stays off');
    assert.equal(exists(box.root, 'failure.count'), false, 'the retry budget starts again');
    assert.equal(fs.readFileSync(path.join(box.root, 'window', 'visual.cleared'), 'utf8'), 'changed\n');
});

test('the boot job leaves a changed deployment, an unrecorded block and its own boot to the owner or the PC', {skip}, () => {
    const scenarios = [
        [{}, {OVC_VERIFY: '1'}, 'OVC_RECOVER outcome=pc reason=deployment'],
        [{reason: null}, {}, 'OVC_RECOVER outcome=pc reason=unrecorded'],
        [{reason: `${CURRENT} lifetime main`}, {}, 'OVC_RECOVER outcome=pc reason=unrecorded'], // reason of another revocation
        [{boot: CURRENT, reason: `${CURRENT} package main`}, {}, 'OVC_RECOVER outcome=reboot reason=same-boot'],
        [{reason: `${EARLIER} lifetime main;rm`}, {}, 'OVC_RECOVER outcome=pc reason=unrecorded'],
    ];
    for (const [setup, env, expected] of scenarios) {
        const box = sandbox();
        revoked(box, setup);
        const result = run(recoveryScript(box, 'recover_activation auto; echo "status=$?"'), {OVC_BOOT_FILE: box.boot, ...env});
        assert.equal(result.stdout, `${expected}\nstatus=1`, JSON.stringify(setup));
        assert.equal(exists(box.root, 'recovery.blocked'), true);
        assert.equal(exists(box.root, 'enabled'), false);
    }
});

test('Restore in OVC works in the same boot unless a visual state cannot be read', {skip}, () => {
    const box = sandbox();
    revoked(box, {boot: CURRENT, reason: null});
    fs.writeFileSync(path.join(box.root, 'visual.state'), 'uncertain\n');
    let result = run(recoveryScript(box, 'recover_activation manual; echo "status=$?"'), {OVC_BOOT_FILE: box.boot});
    assert.equal(result.stdout, 'OVC_RECOVER outcome=reboot reason=visual\nstatus=1');
    assert.equal(exists(box.root, 'recovery.blocked'), true);

    // A session still current for a running target with no injector of this project left: the next attachment adopts
    // it (the agent of the same deployment takes its predecessor's views back), so no reboot is needed.
    fs.writeFileSync(path.join(box.root, 'visual.state'), 'current\n');
    result = run(recoveryScript(box, 'recover_activation manual; echo "status=$?"'), {OVC_BOOT_FILE: box.boot});
    assert.equal(result.stdout, 'OVC_RECOVER outcome=recovered reason=- channels=main,locale-hiboard,window\nstatus=0', result.stderr);
    assert.equal(exists(box.root, 'visual.cleared'), false, 'a current session is kept for adoption, not cleared');

    // The launcher restarted (its new process has the original visuals): every sealed channel comes back as well.
    revoked(box, {boot: CURRENT, reason: null});
    fs.writeFileSync(path.join(box.root, 'visual.state'), 'changed\n');
    result = run(recoveryScript(box, 'recover_activation manual; echo "status=$?"'), {OVC_BOOT_FILE: box.boot});
    assert.equal(result.stdout, 'OVC_RECOVER outcome=recovered reason=- channels=main,locale-hiboard,window\nstatus=0', result.stderr);
    assert.equal(exists(box.root, 'recovery.visual-pending'), false);
});

test('nothing to restore is reported as such and changes nothing', {skip}, () => {
    const box = sandbox();
    stageDeployment(box.root);
    const result = run(recoveryScript(box, 'recover_activation manual; echo "status=$?"'), {OVC_BOOT_FILE: box.boot});
    assert.equal(result.stdout, 'OVC_RECOVER outcome=none reason=active\nstatus=0');
    assert.equal(exists(box.root, 'failure.count'), true);
});

test('the boot entry passes only auto or manual on to the supervisor', () => {
    assert.match(bootStart, /--recover\)\n\s+\[ "\$fixture" = 0 \] && \[ "\$#" = 2 \]/);
    assert.match(bootStart, /case "\$2" in auto\|manual\) exec \/system\/bin\/sh "\$base\/supervise\.sh" --recover "\$2" ;; esac/);
    // The digest check of both scripts against the receipt runs before any mode.
    assert.ok(bootStart.indexOf('check_script boot-start.sh && check_script supervise.sh') < bootStart.indexOf('--recover)'));
});

const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {spawnSync}=require('node:child_process');

const script=path.join(__dirname,'../tools/voice-approvals.mjs');
const shipped=require('../app/src/main/assets/voice/verified-commands.json');
const unit={product:'f6802h',fingerprint:'fixture/test/unit:11/review/97:user/release-keys,test-keys',sdk:30,abi:'arm64-v8a',
    profile:'FREE_REST',commands:['GetFromMemoryCellOne','GetFromMemoryCellTwo','GetFromMemoryCellThree']};

function run(local) {
    const dir=fs.mkdtempSync(path.join(os.tmpdir(),'ovc-voice-approvals-'));
    const output=path.join(dir,'out','voice','verified-commands.json');
    const env={...process.env,OVC_LOCAL_VOICE_APPROVALS:''};
    if (local!==undefined) {
        env.OVC_LOCAL_VOICE_APPROVALS=path.join(dir,'verified-commands.json');
        fs.writeFileSync(env.OVC_LOCAL_VOICE_APPROVALS,typeof local==='string'?local:JSON.stringify(local));
    }
    const result=spawnSync(process.execPath,[script,output],{env,encoding:'utf8'});
    const written=fs.existsSync(output)?JSON.parse(fs.readFileSync(output,'utf8')):null;
    fs.rmSync(dir,{recursive:true,force:true});
    return {status:result.status,stderr:result.stderr,written};
}

test('an ordinary build compiles the agent with exactly the shipped catalog', () => {
    const result=run();
    assert.equal(result.status,0,result.stderr);
    assert.deepEqual(result.written,{schema:1,headUnits:shipped.headUnits});
});

test('a local-vehicle file adds the owner rows after the shipped ones', () => {
    const result=run({schema:1,headUnits:[unit]});
    assert.equal(result.status,0,result.stderr);
    assert.deepEqual(result.written.headUnits,[...shipped.headUnits,unit]);
});

test('a malformed or ambiguous local file stops the build instead of being dropped', () => {
    for (const [local,reason] of [
        ['{not json','JSON'],
        [{schema:2,headUnits:[unit]},'schema'],
        [{schema:1,headUnits:[{...unit,abi:'x86_64'}]},'abi'],
        [{schema:1,headUnits:[{...unit,fingerprint:'bad fingerprint'}]},'fingerprint'],
        [{schema:1,headUnits:[{...unit,commands:[]}]},'command'],
        [{schema:1,headUnits:[{...unit,commands:['GetFromMemoryCellOne','GetFromMemoryCellOne']}]},'command'],
        [{schema:1,headUnits:[{...unit,commands:['Get-Memory']}]},'command'],
        [{schema:1,headUnits:[unit,{...unit,commands:['LampOff']}]},'more than once'],
    ]) {
        const result=run(local);
        assert.equal(result.status,1,`accepted ${JSON.stringify(local)}`);
        assert.equal(result.written,null);
        assert.match(result.stderr,new RegExp(reason,'i'));
    }
});

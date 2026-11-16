const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../hooks/oversea-profiles.js'), 'utf8').replace(/^import .*;$/gm, '').replace(/^export /gm, '');
const nonce = '1'.repeat(32);

function probe(options={}) {
    const logs=[], checked=[], observations=[];
    let calls=0;
    const Login={class:{getDeclaredMethod(name) { checked.push(name); return {getModifiers:()=>options.instance ? 1 : 9}; }}};
    for (const slot of ['A','B','C']) {
        const method=()=>{calls++;throw Error('Account mutation');};
        method.overload=()=>{
            if(options.missing===slot) throw Error('Private account data must not be logged');
            return {returnType:{className:options.wrongType===slot?'void':'boolean'}};
        };
        Login['chooseUser'+slot]=method;
    }
    const Java={perform:fn=>fn(),array:(_,values)=>values,use(name) {
        const types={
            'android.app.ActivityThread':{currentApplication:()=>options.absent?null:{getPackageName:()=>options.package||'com.qinggan.app.login'}},
            'android.os.Build':{PRODUCT:{value:options.product||'f6811'}},
            'android.content.Context':{class:{}},
            'java.lang.reflect.Modifier':{isStatic:bits=>(bits&8)!==0},
            'com.qinggan.app.login.LoginUtil':Login
        };
        if(!types[name]) throw Error(name);
        return types[name];
    }};
    vm.runInNewContext(source+'\ninspectOverseaProfiles(Java, request);',{Java,request:options.nonce??nonce,console:{log:s=>logs.push(s)},
        observeAccountState:()=>{observations.push(true);return options.account||'identified';}});
    return {logs,checked,calls,observations};
}

test('overseas A/B/C methods are inspected without choosing an account',()=>{
    const p=probe();
    assert.deepEqual(p.checked,['chooseUserA','chooseUserB','chooseUserC']);
    assert.equal(p.calls,0);
    assert.deepEqual(p.logs,['OVC_OVERSEA_PROFILE_PROBE '+nonce+' api-present account=identified']);
    assert.equal(p.observations.length,1);
});
test('one missing or non-boolean profile method prevents a complete capability result',()=>{
    for(const option of [{missing:'C'},{wrongType:'B'},{instance:true}]) {
        const p=probe(option); assert.equal(p.calls,0);
        assert.deepEqual(p.logs,['OVC_OVERSEA_PROFILE_PROBE '+nonce+' unsupported account=unavailable']);
        assert.deepEqual(p.observations,[]);
    }
});
test('profiles are never inspected in another model or another process',()=>{
    for(const option of [{product:'f6805h'},{package:'com.qinggan.app.launcher'},{absent:true}]) {
        const p=probe(option); assert.equal(p.calls,0); assert.deepEqual(p.checked,[]);
        assert.deepEqual(p.logs,['OVC_OVERSEA_PROFILE_PROBE '+nonce+' unavailable account=unavailable']);
        assert.deepEqual(p.observations,[]);
    }
});
test('invalid probe tokens produce no result and do not inspect account methods',()=>{
    for(const request of ['','1'.repeat(31),'A'.repeat(32),'1'.repeat(32)+';']) {
        const p=probe({nonce:request}); assert.deepEqual(p.logs,[]);assert.deepEqual(p.checked,[]);
    }
});
test('a missing or changing account record is not reported as an observed guest',()=>{
    for(const account of ['unavailable','unstable','guest']) {
        const p=probe({account});assert.equal(p.calls,0);
        assert.deepEqual(p.logs,['OVC_OVERSEA_PROFILE_PROBE '+nonce+' api-present account='+account]);
    }
});

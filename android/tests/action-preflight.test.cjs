const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../hooks/action-preflight.js'),'utf8').replace(/^export /gm,'');
function probe(change=()=>{}) {
    const app={enabled:{value:true},uid:{value:20000},flags:{value:0}};
    const info={enabled:{value:true},applicationInfo:{value:app},exported:{value:true},permission:{value:null}};
    const state={info,app,resolved:true,intent:true,permission:0};change(state);
    let result;
    vm.runInNewContext(source+'\nresult=createActionPreflight(Java, buildIntent)(context, {});',{
        Java:{use:()=>({myUid:()=>1000,myPid:()=>12})},buildIntent:()=>state.intent?{}:null,
        context:{getPackageManager:()=>({resolveActivity:()=>state.resolved?{activityInfo:{value:info}}:null}),checkPermission:()=>state.permission},
        set result(value){result=value;}
    });return result;
}
test('launch preflight rejects missing, disabled, suspended and private activities',()=>{
    assert.equal(probe(),true);
    for(const change of [s=>s.resolved=false,s=>s.intent=false,s=>s.info.enabled.value=false,s=>s.app.enabled.value=false,
        s=>s.app.flags.value=0x40000000,s=>s.info.exported.value=false]) assert.equal(probe(change),false);
    assert.equal(probe(s=>{s.info.exported.value=false;s.app.uid.value=1000;}),true);
});
test('launch preflight checks the permission of the actual injecting process',()=>{
    assert.equal(probe(s=>{s.info.permission.value='example.permission';s.permission=-1;}),false);
    assert.equal(probe(s=>{s.info.permission.value='example.permission';s.permission=0;}),true);
});

const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const source=fs.readFileSync(require('node:path').join(__dirname,'../hooks/voice-engine.js'),'utf8')
    .replace("import plans from './voice-plans.json';",'').replaceAll('export function','function').replace('export async function','async function');
const env={plans:require('../hooks/voice-plans.json')};
vm.createContext(env);vm.runInContext(source,env);
test('all fixed plans contain named SDK properties and bounded values',()=>{
    for(const id of Object.keys(env.plans)) {
        const plan=env.planFor(id); assert.ok(plan.length>0 && plan.length<=12);
        for(const op of plan) { assert.match(op.property,/^[A-Za-z][A-Za-z0-9_]+$/);assert.ok(Number.isInteger(op.value)); }
    }
});
test('default seat level is deterministic and does not mutate shared plan',()=>{
    const a=env.planFor('EnableHeatDriverLevelDefault');assert.equal(a[0].value,2);a[0].value=99;
    assert.equal(env.planFor('EnableHeatDriverLevelDefault')[0].value,2);
});
test('relative temperature is an adjustment, never negative absolute temperature',()=>{
    const p=env.planFor('DecreaseTemperature')[0];assert.equal(p.relative,true);assert.equal(p.value,-20);
});
test('unknown model blocks model dependent commands',()=>{
    assert.throws(()=>env.planFor('SportMode'),/profile/);assert.throws(()=>env.planFor('AdaptiveLightOn'),/profile/);
});
test('Dreamer powertrain mapping differs from Free',()=>{
    assert.equal(env.planFor('HybridMode',{profile:'FREE'})[0].value,3);
    const dream=env.planFor('HybridMode',{profile:'DREAMER'});assert.equal(dream[0].property,'FORCE_EV_MODE_SET');assert.equal(dream[1].value,1);
});
test('SOC grammar cannot invent an unbounded target',()=>{
    const p=env.planFor('SaveModeWithSOC',{profile:'FREE',phrase:'сохранение восемьдесят'});
    assert.equal(p.at(-1).value,11);assert.equal(p.at(-1).phase,'post');
    assert.throws(()=>env.planFor('SaveModeWithSOC',{profile:'FREE',phrase:'сохранение сто'}));
});
test('roof percent plan includes explicit stop and feedback property',()=>{
    const p=env.planFor('OpenSunshadeWithPercent',{phrase:'открой шторку на пятьдесят процентов'})[0];
    assert.equal(p.target,50);assert.equal(p.property,'ROLL_OPEN_PERCENT');assert.equal(p.stop,7);
    assert.equal(env.percentFrom('открой люк на неизвестно'),null);
});
test('preflight fails before any writes',async()=>{
    let written=0;
    await assert.rejects(env.executePlan({prepare:o=>{if(o.value===2)throw Error('missing property');return o;},write:()=>written++},[{value:1},{value:2}],async()=>{}));
    assert.equal(written,0);
});
test('partial failure stops subsequent operations and reports progress',async()=>{
    let written=0;
    await assert.rejects(env.executePlan({prepare:o=>o,write:()=>{if(++written===2)throw Error('binder died');}},[{},{},{}],async()=>{}),/Completed 1\/3/);
    assert.equal(written,2);
});
test('asynchronous operation is awaited before next write',async()=>{
    const events=[];
    await env.executePlan({prepare:o=>o,write:async p=>{await Promise.resolve();events.push(p.n);}},[{n:1},{n:2,phase:'post'}],async ms=>events.push(ms));
    assert.deepEqual(events,[1,1000,2]);
});

test('English roof and SOC phrases use the same bounded actions',()=>{
    assert.equal(env.percentFrom('open sunroof fifty percent'),50);
    assert.equal(env.planFor('SaveModeWithSOC',{profile:'FREE',phrase:'save seventy five'}).at(-1).value,10);
    assert.throws(()=>env.planFor('SaveModeWithSOC',{profile:'FREE',phrase:'save ninety'}));
});
test('configured default levels and double window pulses match explicit commands',()=>{
    const id='EnableHeatDriverLevelDefault';
    assert.equal(env.planFor(id,{levels:{[id]:3}})[0].value,3);
    assert.throws(()=>env.planFor(id,{levels:{[id]:0}}));
    assert.deepEqual(Array.from(env.planFor('SmokeCommand',{doubleSmoke:true}),x=>x.phase),['main','post']);
    assert.equal(env.planFor('VentilationCommand',{doubleVentilation:true}).length,2);
    const all=env.planFor('EnableAllHeating',{levels:{[id]:1}});
    const firstPost=all.findIndex(x=>x.phase==='post');assert.ok(all.slice(firstPost).every(x=>x.phase==='post'));
});

test('firmware branches remain specific to each feature',()=>{
    assert.equal(env.planFor('AdaptiveLightOff',{profile:'DREAMER_REST'})[0].property,'IHBCFunction');
    assert.equal(env.planFor('AdaptiveLightOff',{profile:'DREAMER_REST'})[0].value,2);
    assert.equal(env.planFor('AdaptiveLightOff',{profile:'DREAMER'})[0].property,'ADB_SWITCH');
    assert.equal(env.planFor('WiperMaintain',{profile:'DREAMER_REST'})[0].toggle,false);
    assert.equal(env.planFor('WiperMaintain',{profile:'DREAMER'})[0].toggle,true);
});

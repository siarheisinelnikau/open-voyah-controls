const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const engine=fs.readFileSync(path.join(__dirname,'../hooks/voice-engine.js'),'utf8').replace("import plans from './voice-plans.json';",'').replaceAll('export function','function').replace('export async function','async function');
const hook=fs.readFileSync(path.join(__dirname,'../hooks/voice-commands.js'),'utf8').replace(/^import .*;$/gm,'').replace('export function','function');
const bus=fs.readFileSync(path.join(__dirname,'../hooks/qinggan-bus.js'),'utf8').replace('export class','class');
function harness(options={}) {
    let raw='',poll,now=100000; const logs=[],writes=[],retained=[],bundles=[],queries=[],events=[];
    const target={argumentTypes:[{className:'VehicleState'},{className:'int'}],call(m,p,v){writes.push([p.name,v]);return options.reject?-1:0;}};
    const floatTarget={...target,argumentTypes:[{className:'VehicleState'},{className:'float'}]};
    const manager={setVehicleState:{overloads:[target]},setAirConditionState:{overloads:[target]},
        setAirConditionStateEx:{overloads:[floatTarget]}, getAirConditionState(){return options.power ?? 1;},
        getAirCondition(){return {getAirLeftTemperature(){return options.temperature ?? 24;}};},
        getVehicleState(){return options.roofPosition ?? 30;}, getGearStatus(){return {name(){return options.gear ?? 'Drive';}};}};
    manager.setVehicleAndAirConditionBundleState=(climate,bundle)=>{bundles.push({...bundle.values});return 0;};
    const resolver={query:{overload(){return {call(_r,uri,projection,where,args){
        queries.push({uri,projection,where,args});
        if(options.noProvider)return null;
        return {moveToFirst:()=>true,getString:()=>args[0].startsWith('drive_mode_recycleState')?'4':'2',close(){}};
    }};}}};
    const classes={
        'android.provider.Settings$Global':{getString(){return raw;}},
        'android.app.ActivityThread':{currentApplication(){return {getContentResolver(){return resolver;}};}},
        'com.qinggan.launcher.base.manager.CanBusManagerWrapper':{getCanBusManager(){return options.noManager?null:manager;}},
        'com.qinggan.launcher.base.config.AppConfig':{isH97:()=>options.h97 === true},
        'com.qinggan.os.SystemProperties':{get(){return '10000001';}},
        'android.net.Uri':{parse:x=>x},
        'android.os.Bundle':{$new(){return {values:{},putInt(k,v){this.values[k]=v;},putLong(k,v){this.values[k]=v;},putBoolean(k,v){this.values[k]=v;}};}},
        'com.qinggan.bus.QGBus':{$new(){return {publish(event){events.push(event);}};}},
        'com.qinggan.bus.QGBusEvent':{$new(){return {setSticky(v){this.sticky=v;},setEventType(v){this.type=v;},setData(v){this.data=v.values;}};}},
        'android.os.SystemClock':{uptimeMillis:()=>now},
        VehicleState:{valueOf(name){if(options.missing===name)throw Error('missing');return {name};}}
    };
    const Java={array:(type,values)=>values,perform:fn=>fn(),performNow:fn=>fn(),use:name=>{if(!classes[name])throw Error('missing '+name);return classes[name];},
        retain:o=>{const r=Object.assign({},o,{$dispose(){r.disposed=true;}});retained.push(r);return r;}};
    vm.runInNewContext(engine+'\n'+bus+'\n'+hook+'\ninstallVoice(Java);',{plans:require('../hooks/voice-plans.json'),Java,console:{log:s=>logs.push(s)},
        setInterval:fn=>poll=fn,setTimeout:(fn,ms)=>{now+=ms;queueMicrotask(fn);},Date:{now:()=>now}});
    return {logs,writes,retained,bundles,queries,events,send(command,extra={}){raw=JSON.stringify({schema:1,id:'a'.repeat(32),command,expires:now+12000,confirmed:true,...extra});poll();},poll:()=>poll()};
}
const settle=()=>new Promise(resolve=>setImmediate(resolve));
test('voice works without NavigationBar and reports a single accepted operation',async()=>{
    const h=harness();h.send('EnableHeatDriverLevelOne');await settle();
    assert.deepEqual(h.writes,[['FRONT_SEAT_HEATING_COMMAND_LEFT',1]]);
    assert.ok(h.logs.some(s=>s.includes('"status":"sent"')));
    h.poll();await settle();assert.equal(h.writes.length,1);assert.ok(h.retained.every(r=>r.disposed));
});
test('expired and unconfirmed requests never write',async()=>{
    const h=harness();h.send('OpenTrunkCommand',{expires:99999});await settle();assert.equal(h.writes.length,0);
    h.send('OpenTrunkCommand',{confirmed:false});await settle();assert.equal(h.writes.length,0);
});
test('missing CAN manager reports error without killing command poller',async()=>{
    const h=harness({noManager:true});h.send('EnableHeatDriverLevelOne');await settle();assert.equal(h.writes.length,0);assert.ok(h.logs.some(s=>s.includes('"status":"error"')));
});
test('missing property in later phase prevents earlier writes and releases handles',async()=>{
    const h=harness({missing:'REAR_SEAT_HEATING_COMMAND_LEFT'});h.send('EnableHeatRearLeftLevelOne');await settle();
    assert.equal(h.writes.length,0);assert.ok(h.retained.every(r=>r.disposed));
});
test('current temperature is read and clamped, invalid telemetry blocks writes',async()=>{
    const h=harness({temperature:17});h.send('FastDecreaseTemperature');await settle();assert.equal(h.writes[0][1],160);
    const bad=harness({temperature:-1});bad.send('IncreaseTemperature');await settle();assert.equal(bad.writes.length,0);
});
test('stationary-only actions reject Drive',async()=>{
    const h=harness();h.send('CarWashMode');await settle();assert.equal(h.writes.length,0);
});
test('roof timeout sends STOP and reports error instead of leaving movement active',async()=>{
    const h=harness({roofPosition:30});h.send('OpenSunroofWithPercent',{options:{phrase:'люк пятьдесят'}});await settle();
    assert.deepEqual(h.writes,[['SunroofControl',1],['SunroofControl',3]]);
    assert.ok(h.logs.some(s=>s.includes('"status":"error"')));assert.ok(h.retained.every(r=>r.disposed));
});

test('guest profile synchronization uses the original sticky QGBus event',async()=>{
    const h=harness();h.send('ProfileSyncPreflight');await settle();assert.equal(h.events.length,0);
    assert.ok(h.logs.some(s=>s.includes('OVC_PROFILE_SYNC_READY')));
    h.send('ProfileSync');await settle();assert.equal(h.events.length,1);
    assert.equal(h.events[0].type,'com.qinggan.update.vehicle_data');
    assert.equal(h.events[0].sticky,true);assert.equal(h.events[0].data.update_vehicle_data,true);
    assert.equal(h.writes.length,0);
});
test('individual mode reads Qinggan provider and sends Free bundles in original order',async()=>{
    const h=harness();h.send('IndividualMode',{options:{profile:'FREE'}});await settle();
    assert.equal(h.queries[0].uri,'content://qinggan.settings/global');
    assert.equal(h.queries[0].where,'name=?');assert.equal(h.queries[0].args[0],'drive_mode_steeringWheelAssist10000001');
    assert.deepEqual(h.bundles,[{DRIVING_MODE_SET:5,HUM_ENERGY_PTREGEN_LEVL:4},{EPS_MODE_SET:2,PROP_MODE_SET:2}]);
});
test('missing OEM provider prevents partial individual mode writes',async()=>{
    const h=harness({noProvider:true});h.send('IndividualMode',{options:{profile:'FREE'}});await settle();
    assert.equal(h.bundles.length,0);assert.ok(h.retained.every(r=>r.disposed));
    assert.ok(h.logs.some(s=>s.includes('"status":"error"')));
});

test('absolute climate uses the H97 front climate branch and rejects unsafe targets', async () => {
    const valid = harness({h97:true});
    valid.send('SetPassengerTemperature', {options:{climateValue:22}});
    await settle();
    assert.deepEqual(valid.writes, [['AC_RIGHT_TEMP',22]]);
    const fan = harness({h97:true});
    fan.send('SetFanSpeed', {options:{climateValue:8}});
    await settle();
    assert.deepEqual(fan.writes, [['AC_BLOWER',8]]);
    for (const options of [{h97:false}, {h97:true, power:0}]) {
        const blocked = harness(options);
        blocked.send('SetDriverTemperature', {options:{climateValue:23}});
        await settle();
        assert.equal(blocked.writes.length,0);
        assert.ok(blocked.logs.some(line => line.includes('"status":"error"')));
    }
    const invalid = harness({h97:true});
    invalid.send('SetDriverTemperature', {options:{climateValue:31}});
    await settle();
    assert.equal(invalid.writes.length,0);
});

test('relative H97 temperature uses the observed half-degree end stops', async () => {
    const high = harness({h97:true, temperature:30});
    high.send('IncreaseTemperature');
    await settle();
    assert.deepEqual(high.writes, [['AC_LEFT_TEMP',305]]);
    const low = harness({h97:true, temperature:15.5});
    low.send('FastDecreaseTemperature');
    await settle();
    assert.deepEqual(low.writes, [['AC_LEFT_TEMP',155]]);
});

test('button voice can recall a seat and turn off lights only in Parking', async () => {
    for (const [id, property, value] of [
        ['GetFromMemoryCellOne', 'DMS_GET_MEMORY_REQ', 1],
        ['LampOff', 'OUT_LAMP_OFF', 1]
    ]) {
        const drive = harness();
        drive.send(id, {schema:2});
        await settle();
        assert.equal(drive.writes.length, 0);
        assert.ok(drive.logs.some(line => line.includes('requires Parking gear')));
        const park = harness({gear:'Parking'});
        park.send(id, {schema:2});
        await settle();
        assert.deepEqual(park.writes, [[property, value]]);
        park.poll();
        await settle();
        assert.equal(park.writes.length, 1);
    }
});

test('button voice can enable massage without a Park restriction', async () => {
    const h = harness();
    h.send('EnableMassageDriverLevelDefault', {schema:2, options:{levels:{EnableMassageDriverLevelDefault:2}}});
    await settle();
    assert.deepEqual(h.writes, [['FRONT_SEAT_MASS_INTEN_LEFT', 2]]);
});

test('Park preflight performs no writes and checks actual gear', async () => {
    const drive = harness();
    drive.send('ParkingPreflight', {schema:2});
    await settle();
    assert.ok(drive.logs.some(line => line.includes('requires Parking gear')));
    const park = harness({gear:'Parking'});
    park.send('ParkingPreflight', {schema:2});
    await settle();
    assert.ok(park.logs.some(line => line.includes('OVC_PARKING_READY')));
    assert.equal(park.writes.length, 0);
});

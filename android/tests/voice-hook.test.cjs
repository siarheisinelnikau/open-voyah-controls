const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const source=name=>fs.readFileSync(path.join(__dirname,'../hooks',name),'utf8')
    .replace(/^import .*;$/gm,'').replace(/^export /gm,'');
const modules=['voice-safety.js','voice-engine.js','qinggan-bus.js','voice-commands.js'].map(source).join('\n');
const identity={product:'f0506h', fingerprint:'fixture/test/unit:11/review/1:user/test-keys', sdk:30, abi:'arm64-v8a'};
const commands=['EnableHeatDriverLevelOne','EnableHeatRearLeftLevelOne','OpenTrunkCommand',
    'FastDecreaseTemperature','IncreaseTemperature','CarWashMode','OpenSunroofWithPercent','DriverA','RebootTbox',
    'IndividualMode','SetPassengerTemperature','SetFanSpeed','SetDriverTemperature','GetFromMemoryCellOne','LampOff',
    'EnableMassageDriverLevelDefault','Sentry','PowerHold','BackLightModeAuto'];
const releasedCatalog=require('../app/src/main/assets/voice/verified-commands.json');
function harness(options={}) {
    let raw=options.initialRaw ?? '',poll,now=100000,sequence=0,roofReads=0,canRequests=0;
    const logs=[],writes=[],retained=[],bundles=[],queries=[],events=[];
    const device={...identity,...options.device};
    const catalog=options.catalog ?? {schema:1,headUnits:[{...identity,profile:'FREE',commands}]};
    const target={argumentTypes:[{className:'VehicleState'},{className:'int'}],call(m,p,v){writes.push([p.name,v]);return options.reject?-1:0;}};
    const floatTarget={...target,argumentTypes:[{className:'VehicleState'},{className:'float'}]};
    const manager={setVehicleState:{overloads:[target]},setAirConditionState:{overloads:[target]},
        setAirConditionStateEx:{overloads:[floatTarget]}, getAirConditionState(){return options.power ?? 1;},
        getAirCondition(){return {getAirLeftTemperature(){return options.temperature ?? 24;}};},
        getVehicleState(){
            if (Array.isArray(options.roofPosition)) return options.roofPosition[Math.min(roofReads++,options.roofPosition.length-1)];
            return options.roofPosition ?? 30;
        },
        getGearStatus(){return {name(){return (writes.length && options.gearAfterWrite) || options.gear || 'Parking';}};},
        getVehicleSpeed(){now+=options.stateDelay ?? 0;return Object.hasOwn(options,'speed') ? options.speed : 0;}};
    manager.setVehicleAndAirConditionBundleState=(climate,bundle)=>{
        bundles.push({...bundle.values});
        return bundles.length === options.rejectBundle ? -1 : 0;
    };
    const resolver={query:{overload(){return {call(_r,uri,projection,where,args){
        queries.push({uri,projection,where,args});
        if(options.noProvider)return null;
        return {moveToFirst:()=>true,getString:()=>args[0].startsWith('drive_mode_recycleState')?'4':'2',close(){}};
    }};}}};
    const classes={
        'android.provider.Settings$Global':{getString(){return raw;}},
        'android.app.ActivityThread':{currentApplication(){return {
            getContentResolver(){return resolver;},
            getPackageName(){return options.packageName ?? 'com.qinggan.app.launcher';},
        };}},
        'android.os.Build':{PRODUCT:{value:device.product},FINGERPRINT:{value:device.fingerprint},SUPPORTED_ABIS:{value:[device.abi]}},
        'android.os.Build$VERSION':{SDK_INT:{value:device.sdk}},
        'com.qinggan.launcher.base.manager.CanBusManagerWrapper':{getCanBusManager(){canRequests++;return options.noManager?null:manager;}},
        'com.qinggan.launcher.base.config.AppConfig':{isH97:()=>options.h97 === true},
        'com.qinggan.os.SystemProperties':{get(){return '10000001';}},
        'android.net.Uri':{parse:x=>x},
        'android.os.Bundle':{$new(){return {values:{},putInt(k,v){this.values[k]=v;},putLong(k,v){this.values[k]=v;},putBoolean(k,v){this.values[k]=v;}};}},
        'com.qinggan.bus.QGBus':{$new(){return {publish(event){events.push(event);}};}},
        'com.qinggan.bus.QGBusEvent':{$new(){return {setSticky(v){this.sticky=v;},setEventType(v){this.type=v;},setData(v){this.data=v.values;}};}},
        'android.os.SystemClock':{uptimeMillis:()=>now,elapsedRealtime:()=>now},
        VehicleState:{valueOf(name){if(options.missing===name)throw Error('missing');return {name};}}
    };
    const Java={array:(type,values)=>values,perform:fn=>fn(),performNow:fn=>fn(),use:name=>{if(!classes[name])throw Error('missing '+name);return classes[name];},
        retain:o=>{const r=Object.assign({},o,{$dispose(){r.disposed=true;}});retained.push(r);return r;}};
    vm.runInNewContext(modules+'\ninstallVoice(Java);',{catalog,plans:require('../hooks/voice-plans.json'),Java,console:{log:s=>logs.push(s)},
        setInterval:fn=>poll=fn,setTimeout:(fn,ms)=>{now+=ms;queueMicrotask(fn);},Date:{now:()=>now+(options.wallOffset ?? 0)}});
    return {logs,writes,retained,bundles,queries,events,get canRequests(){return canRequests;},
        send(command,extra={}){raw=JSON.stringify({schema:3,id:(++sequence).toString(16).padStart(32,'0'),command,
            expires:now+12000,confirmed:true,...extra});poll();},poll:()=>poll()};
}
const settle=()=>new Promise(resolve=>setImmediate(resolve));
test('voice works without NavigationBar and reports a single accepted operation',async()=>{
    const h=harness();h.send('EnableHeatDriverLevelOne');await settle();
    assert.deepEqual(h.writes,[['FRONT_SEAT_HEATING_COMMAND_LEFT',1]]);
    assert.ok(h.logs.some(s=>s.includes('"status":"accepted"')));
    h.poll();await settle();assert.equal(h.writes.length,1);assert.ok(h.retained.every(r=>r.disposed));
});
test('expired and unconfirmed requests never write',async()=>{
    const h=harness();h.send('OpenTrunkCommand',{expires:99999});await settle();assert.equal(h.writes.length,0);
    h.send('OpenTrunkCommand',{confirmed:false});await settle();assert.equal(h.writes.length,0);
    h.send('OpenTrunkCommand',{schema:2});await settle();assert.equal(h.writes.length,0);
});
test('missing CAN manager reports error without killing command poller',async()=>{
    const h=harness({noManager:true});h.send('EnableHeatDriverLevelOne');await settle();assert.equal(h.writes.length,0);assert.ok(h.logs.some(s=>s.includes('"status":"blocked"')));
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
    const h=harness({gear:'Drive'});h.send('CarWashMode');await settle();assert.equal(h.writes.length,0);
});
test('roof timeout sends STOP and reports error instead of leaving movement active',async()=>{
    const h=harness({roofPosition:30});h.send('OpenSunroofWithPercent',{options:{phrase:'люк пятьдесят'}});await settle();
    assert.deepEqual(h.writes,[['SunroofControl',1],['SunroofControl',3]]);
    assert.ok(h.logs.some(s=>s.includes('"status":"partial"')));assert.ok(h.retained.every(r=>r.disposed));
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
    assert.ok(h.logs.some(s=>s.includes('"status":"blocked"')));
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
        assert.ok(blocked.logs.some(line => line.includes('"status":"blocked"')));
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
        const drive = harness({gear:'Drive'});
        drive.send(id);
        await settle();
        assert.equal(drive.writes.length, 0);
        assert.ok(drive.logs.some(line => line.includes('requires Parking gear')));
        const park = harness({gear:'Parking'});
        park.send(id);
        await settle();
        assert.deepEqual(park.writes, [[property, value]]);
        park.poll();
        await settle();
        assert.equal(park.writes.length, 1);
    }
});

test('reviewed massage requests require fresh Park and zero speed', async () => {
    const h = harness();
    h.send('EnableMassageDriverLevelDefault', {options:{levels:{EnableMassageDriverLevelDefault:2}}});
    await settle();
    assert.deepEqual(h.writes, [['FRONT_SEAT_MASS_INTEN_LEFT', 2]]);
});

test('Park preflight performs no writes and checks actual gear', async () => {
    const drive = harness({gear:'Drive'});
    drive.send('ParkingPreflight');
    await settle();
    assert.ok(drive.logs.some(line => line.includes('requires Parking gear')));
    const park = harness({gear:'Parking'});
    park.send('ParkingPreflight');
    await settle();
    assert.ok(park.logs.some(line => line.includes('OVC_PARKING_READY')));
    assert.equal(park.writes.length, 0);
});

test('the production catalog denies all vehicle routes before contacting OEM services', async () => {
    const h = harness({catalog:releasedCatalog});
    for (const command of ['EnableHeatDriverLevelOne','Sentry','PowerHold','BackLightModeAuto',
        'ParkingPreflight','ProfileSyncPreflight','ProfileSync']) h.send(command);
    await settle();
    assert.equal(h.canRequests,0);
    assert.equal(h.events.length,0);
    assert.equal(h.writes.length,0);
    assert.equal(h.logs.filter(line=>line.includes('not approved for this head unit')).length,7);
});

test('changed fingerprint or missing per-command evidence blocks the hook', async () => {
    const updated = harness({device:{fingerprint:'fixture/test/unit:11/changed/1:user/test-keys'}});
    updated.send('EnableHeatDriverLevelOne');
    assert.equal(updated.canRequests,0);
    const narrow = harness({catalog:{schema:1,headUnits:[{...identity,profile:'FREE',commands:['DriverA']}]}});
    narrow.send('EnableHeatDriverLevelOne');
    narrow.send('ParkingPreflight');
    assert.equal(narrow.canRequests,0);
    assert.equal(narrow.logs.filter(line=>line.includes('not approved for this head unit')).length,2);
    const foreign = harness({packageName:'fixture.other.app'});
    foreign.send('EnableHeatDriverLevelOne');
    assert.equal(foreign.canRequests,0);
});

test('motion and unavailable or slow telemetry fail closed before each write', async () => {
    for (const options of [{speed:1},{speed:-1},{speed:null},{gear:'Unknown'},{stateDelay:501}]) {
        const h = harness(options);
        h.send('EnableHeatDriverLevelOne');
        await settle();
        assert.equal(h.writes.length,0);
        assert.ok(h.logs.some(line=>line.includes('"status":"blocked"')));
    }
    const shifted = harness({gearAfterWrite:'Drive'});
    shifted.send('CarWashMode');
    await settle();
    assert.equal(shifted.writes.length,1);
    assert.ok(shifted.logs.some(line=>line.includes('"status":"partial"')));
});

test('accepted write, observed target and ambiguous failure produce different receipts', async () => {
    const verified = harness({roofPosition:[30,30,50,50]});
    verified.send('OpenSunroofWithPercent',{options:{phrase:'люк пятьдесят'}});
    await settle();
    assert.ok(verified.logs.some(line=>line.includes('"status":"verified"')));
    const overshot = harness({roofPosition:[30,30,60,60]});
    overshot.send('OpenSunroofWithPercent',{options:{phrase:'люк пятьдесят'}});
    await settle();
    assert.ok(overshot.logs.some(line=>line.includes('"status":"accepted"')));
    assert.ok(overshot.logs.every(line=>!line.includes('"status":"verified"')));
    const unknown = harness({reject:true});
    unknown.send('EnableHeatDriverLevelOne');
    await settle();
    assert.ok(unknown.logs.some(line=>line.includes('"status":"unknown"')));
    const partial = harness({rejectBundle:2});
    partial.send('IndividualMode');
    await settle();
    assert.ok(partial.logs.some(line=>line.includes('"status":"partial"')));
});

test('the same request id cannot be replayed by changing its options', async () => {
    const h = harness();
    const id = 'a'.repeat(32);
    h.send('EnableHeatDriverLevelOne',{id});
    await settle();
    h.send('EnableHeatDriverLevelOne',{id,options:{profile:'DREAMER'}});
    await settle();
    assert.equal(h.writes.length,1);
    const reattached = harness({initialRaw:JSON.stringify({id})});
    reattached.send('EnableHeatDriverLevelOne',{id});
    await settle();
    assert.equal(reattached.writes.length,0);
});

test('an overlapping request is rejected instead of queued or replayed', async () => {
    const h = harness({roofPosition:[30,30,50,50]});
    h.send('OpenSunroofWithPercent',{options:{phrase:'люк пятьдесят'}});
    h.send('EnableHeatDriverLevelOne');
    await settle();
    h.poll();
    assert.ok(h.logs.some(line=>line.includes('A vehicle command is in progress')));
    assert.deepEqual(h.writes,[['SunroofControl',1],['SunroofControl',3]]);
});

test('voice request validity uses elapsed time independently of wall clock changes', async () => {
    const h = harness({wallOffset:86400000});
    h.send('EnableHeatDriverLevelOne');
    await settle();
    assert.equal(h.writes.length,1);
    assert.ok(h.logs.some(line=>line.includes('"status":"accepted"')));
});

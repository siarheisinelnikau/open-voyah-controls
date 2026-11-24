const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const source=name=>fs.readFileSync(path.join(__dirname,'../hooks',name),'utf8')
    .replace(/^import .*;$/gm,'').replace(/^export /gm,'');
const modules=['report.js','voice-safety.js','voice-engine.js','qinggan-bus.js','voice-commands.js'].map(source).join('\n');
const identity={product:'f0506h', fingerprint:'fixture/test/unit:11/review/1:user/test-keys', sdk:30, abi:'arm64-v8a'};
const commands=['EnableHeatDriverLevelOne','EnableHeatRearLeftLevelOne','OpenTrunkCommand',
    'FastDecreaseTemperature','IncreaseTemperature','CarWashMode','OpenSunroofWithPercent','DriverA','RebootTbox',
    'IndividualMode','SetPassengerTemperature','SetFanSpeed','SetDriverTemperature','GetFromMemoryCellOne','LampOff',
    'EnableMassageDriverLevelDefault','Sentry','PowerHold','BackLightModeAuto'];
const releasedCatalog=require('../app/src/main/assets/voice/verified-commands.json');
function harness(options={}) {
    let raw=options.initialRaw ?? '',poll,now=100000,sequence=0,roofReads=0,canRequests=0;
    const logs=[],writes=[],retained=[],bundles=[],queries=[],events=[],backlight=[];
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
        'android.provider.Settings$Global':{getString(){
            if(options.providerFailure)throw Error('java.lang.IllegalStateException: provider for private.package is restarting');
            return raw;
        }},
        'android.app.ActivityThread':{currentApplication(){return {
            getContentResolver(){return resolver;},
            getPackageName(){return options.packageName ?? 'com.qinggan.app.launcher';},
        };}},
        'android.os.Build':{PRODUCT:{value:device.product},FINGERPRINT:{value:device.fingerprint},SUPPORTED_ABIS:{value:[device.abi]}},
        'android.os.Build$VERSION':{SDK_INT:{value:device.sdk}},
        'com.qinggan.launcher.base.manager.CanBusManagerWrapper':{getCanBusManager(){canRequests++;return options.noManager?null:manager;}},
        'com.qinggan.launcher.base.config.AppConfig':{isH97:()=>options.h97 === true},
        'com.qinggan.system.BackLightManager':{getInstance(){return {
            setBackLight:{overload(...types){assert.deepEqual(types,['int','int','int']);return {call(_light,...args){
                backlight.push(args);return options.backlightRejected !== true;}};}},
            getBackLight:{overload(...types){assert.deepEqual(types,['int']);return {call(_light,screen){
                if(options.backlightReadback === 'throw')throw Error('service restarting');
                return options.backlightReadback ?? (screen === 1 ? backlight.at(-1)?.[0] : -1);}};}},
        };}},
        'com.qinggan.launcher.base.manager.PowerManagerWrapper':{getPowerManager(){
            return options.lcdTemp === null ? null : {getLCDTemp(){return options.lcdTemp ?? 0;}};}},
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
    if(options.noPowerWrapper)delete classes['com.qinggan.launcher.base.manager.PowerManagerWrapper'];
    vm.runInNewContext(modules+'\ninstallVoice(Java);',{catalog,plans:require('../hooks/voice-plans.json'),Java,console:{log:s=>logs.push(s)},
        setInterval:fn=>poll=fn,setTimeout:(fn,ms)=>{now+=ms;queueMicrotask(fn);},Date:{now:()=>now+(options.wallOffset ?? 0)}});
    return {logs,writes,retained,bundles,queries,events,backlight,get canRequests(){return canRequests;},
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

test('an unsupported product is refused before contacting OEM services', async () => {
    for (const device of [{product:'x9999'}, {sdk:29}, {abi:'x86_64'}]) {
        const h = harness({device, catalog:releasedCatalog});
        for (const command of ['EnableHeatDriverLevelOne','Sentry','PowerHold','BackLightModeAuto',
            'ParkingPreflight','ProfileSyncPreflight','ProfileSync']) h.send(command);
        await settle();
        assert.equal(h.canRequests,0);
        assert.equal(h.events.length,0);
        assert.equal(h.writes.length,0);
        assert.equal(h.logs.filter(line=>line.includes('not approved for this head unit')).length,7);
    }
});

test('every command runs on a supported product without a catalog row; another package never runs one', async () => {
    // The released catalog is empty: review rows are metadata, not a gate.
    const released = harness({catalog:releasedCatalog});
    released.send('EnableHeatDriverLevelOne');
    await settle();
    assert.ok(released.canRequests > 0);
    assert.ok(!released.logs.some(line=>line.includes('not approved for this head unit')));
    const updated = harness({device:{fingerprint:'fixture/test/unit:11/changed/1:user/test-keys'}});
    updated.send('EnableHeatDriverLevelOne');
    await settle();
    assert.ok(updated.canRequests > 0);
    const narrow = harness({catalog:{schema:1,headUnits:[{...identity,profile:'FREE',commands:['DriverA']}]}});
    narrow.send('ParkingPreflight');
    await settle();
    assert.ok(narrow.logs.some(line=>line.includes('OVC_PARKING_READY')));
    const foreign = harness({packageName:'fixture.other.app'});
    foreign.send('EnableHeatDriverLevelOne');
    await settle();
    assert.equal(foreign.canRequests,0);
});

test('a fingerprint with a comma keeps the Park check for driver-memory recalls', async () => {
    // The supported Android 11 unit reports "...,test-keys"; its own rows must still match exactly.
    const unit = {product:'f6802h', fingerprint:'fixture/test/unit:11/review/97:user/release-keys,test-keys', sdk:30, abi:'arm64-v8a'};
    const catalog = {schema:1, headUnits:[{...unit, profile:'FREE_REST',
        commands:['GetFromMemoryCellOne','GetFromMemoryCellTwo','GetFromMemoryCellThree']}]};
    for (const [command, value] of [['GetFromMemoryCellOne', 1], ['GetFromMemoryCellTwo', 2], ['GetFromMemoryCellThree', 3]]) {
        const park = harness({device:unit, catalog});
        park.send(command);
        await settle();
        assert.deepEqual(park.writes, [['DMS_GET_MEMORY_REQ', value]]);
        const drive = harness({device:unit, catalog, gear:'Drive'});
        drive.send(command);
        await settle();
        assert.equal(drive.writes.length, 0);
        assert.ok(drive.logs.some(line => line.includes('requires Parking gear')));
    }
    // A command outside the row and a changed fingerprint still run; only the "verified" mark differs.
    const changed = harness({device:{...unit, fingerprint:unit.fingerprint.replace(',test-keys', ',dev-keys')}, catalog});
    changed.send('GetFromMemoryCellOne');
    await settle();
    assert.deepEqual(changed.writes, [['DMS_GET_MEMORY_REQ', 1]]);
});

test('motion and unavailable or slow telemetry fail closed before each Park-only write', async () => {
    for (const options of [{speed:1},{speed:-1},{speed:null},{gear:'Unknown'},{stateDelay:501}]) {
        const h = harness(options);
        h.send('OpenTrunkCommand');
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

test('an unavailable settings provider is reported once and does not stop the command poller', async () => {
    const options = {};
    const h = harness(options);
    options.providerFailure = true;
    for (let tick = 0; tick < 8; tick++) assert.doesNotThrow(() => h.poll());
    assert.deepEqual(h.logs.filter(line=>line.startsWith('OVC_VOICE_ERROR')),
        ['OVC_VOICE_ERROR stage=settings kind=IllegalStateException']);
    options.providerFailure = false;
    h.send('EnableHeatDriverLevelOne');
    await settle();
    assert.deepEqual(h.writes,[['FRONT_SEAT_HEATING_COMMAND_LEFT',1]]);
    assert.equal(h.logs.some(line=>line.includes('private')),false);
});

test('comfort, lighting, window and mode commands also run while driving', async () => {
    const drive = harness({gear:'Drive', speed:60});
    drive.send('EnableHeatDriverLevelOne');
    await settle();
    assert.deepEqual(drive.writes, [['FRONT_SEAT_HEATING_COMMAND_LEFT', 1]]);
    assert.ok(drive.logs.some(line => line.includes('"status":"accepted"')));
    const unknownState = harness({gear:'Unknown', speed:null});
    unknownState.send('IncreaseTemperature');
    await settle();
    assert.deepEqual(unknownState.writes, [['AC_LEFT_TEMP', 260]]);
    for (const command of ['OpenTrunkCommand', 'LampOff', 'GetFromMemoryCellOne', 'CarWashMode', 'PowerHold']) {
        const h = harness({gear:'Drive', speed:60});
        h.send(command);
        await settle();
        assert.equal(h.writes.length, 0, command);
        assert.equal(h.events.length, 0, command);
        assert.ok(h.logs.some(line => line.includes('requires Parking gear')), command);
    }
});

test('every command allowed in motion exists in the voice catalog', () => {
    const context = {catalog: {}};
    vm.runInNewContext(source('voice-safety.js') + '\nthis.ids = [...MOTION_COMMANDS];', context);
    const catalog = fs.readFileSync(path.join(__dirname, '../core/src/main/resources/voice-catalog-en.tsv'), 'utf8')
        .split(/\r?\n/).filter(line => line && !line.startsWith('#')).map(line => line.split('\t')[0]);
    const known = new Set([...catalog, 'SetDriverTemperature', 'SetPassengerTemperature', 'SetFanSpeed', 'SetBrightness']);
    assert.equal(JSON.stringify(context.ids.filter(id => !known.has(id))), '[]');
    assert.ok(context.ids.length > 60);
    for (const parked of ['LampOff', 'PositionLamp', 'OpenTrunkCommand', 'CloseTrunkCommand', 'EnableTrunkCommand',
        'DisableTrunkCommand', 'OpenElectricityPort', 'OpenFuelPort', 'GetFromMemoryCellOne', 'GetFromMemoryCellTwo',
        'GetFromMemoryCellThree', 'CarWashMode', 'WiperMaintain', 'PowerHold', 'Sentry', 'Magic', 'RebootTbox',
        'DriverA', 'DriverB', 'DriverC']) assert.equal(context.ids.includes(parked), false, parked);
});

test('the agent product profiles match core Firmware.java', () => {
    const java = fs.readFileSync(path.join(__dirname,'../core/src/main/java/org/openvoyah/controls/core/Firmware.java'),'utf8');
    const expected = Object.fromEntries([...java.matchAll(/products\.put\("([^"]+)", "([^"]+)"\)/g)].map(m => [m[1], m[2]]));
    const context = vm.createContext({});
    vm.runInContext(source('voice-safety.js').replace(/^.*catalog.*from.*$/m,'') + '\nthis.profiles = PRODUCT_PROFILES;', context);
    assert.ok(Object.keys(expected).length >= 10);
    assert.deepEqual({...context.profiles}, expected);
});

test('spoken brightness moves the main screen like the launcher slider and confirms it from the service', async () => {
    const h = harness({gear:'Drive', speed:60});
    h.send('SetBrightness', {options:{brightness:30}});
    await settle();
    assert.deepEqual(h.backlight, [[30, 1, 1]]);
    assert.equal(h.writes.length, 0);
    assert.ok(h.logs.some(line => line.includes('"status":"verified"')));
    const lagging = harness({backlightReadback:55});
    lagging.send('SetBrightness', {options:{brightness:1}});
    await settle();
    assert.deepEqual(lagging.backlight, [[1, 1, 1]]);
    assert.ok(lagging.logs.some(line => line.includes('"status":"accepted"')));
    const unreadable = harness({backlightReadback:'throw'});
    unreadable.send('SetBrightness', {options:{brightness:100}});
    await settle();
    assert.deepEqual(unreadable.backlight, [[100, 1, 1]]);
    assert.ok(unreadable.logs.some(line => line.includes('"status":"accepted"')));
});

test('brightness outside 1-100 or without a level never reaches the service', async () => {
    for (const extra of [{options:{brightness:0}}, {options:{brightness:101}}, {options:{brightness:'30'}},
        {options:{brightness:30.5}}, {options:{}}, {}]) {
        const h = harness();
        h.send('SetBrightness', extra);
        await settle();
        assert.equal(h.backlight.length, 0, JSON.stringify(extra));
        assert.ok(h.logs.some(line => line.includes('"status":"blocked"')), JSON.stringify(extra));
    }
});

test('a hot display keeps its brightness as factory Settings does; no reading lets the request through', async () => {
    for (const lcdTemp of [1, 2, 3]) {
        const hot = harness({lcdTemp});
        hot.send('SetBrightness', {options:{brightness:80}});
        await settle();
        assert.equal(hot.backlight.length, 0);
        assert.ok(hot.logs.some(line => line.includes('"status":"blocked"') && line.includes('The display is too hot')));
    }
    for (const options of [{lcdTemp:-1}, {lcdTemp:0}, {lcdTemp:null}, {noPowerWrapper:true}]) {
        const h = harness(options);
        h.send('SetBrightness', {options:{brightness:80}});
        await settle();
        assert.deepEqual(h.backlight, [[80, 1, 1]], JSON.stringify(options));
    }
});

test('a rejected brightness write is reported as unknown, not as applied', async () => {
    const h = harness({backlightRejected:true});
    h.send('SetBrightness', {options:{brightness:40}});
    await settle();
    assert.deepEqual(h.backlight, [[40, 1, 1]]);
    assert.ok(h.logs.some(line => line.includes('"status":"unknown"') && line.includes('rejected')));
});

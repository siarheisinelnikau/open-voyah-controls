import {planFor, executePlan} from './voice-engine.js';
import {QingganBus} from './qinggan-bus.js';
import {approvedVoiceCommands, requireStationary, VoiceCommandFailure} from './voice-safety.js';

export function installVoice(Java) {
    Java.perform(() => {
        try {
            const Global = Java.use('android.provider.Settings$Global');
            const ActivityThread = Java.use('android.app.ActivityThread');
            const application = ActivityThread.currentApplication();
            const resolver = application.getContentResolver();
            const bus = new QingganBus(Java, application);
            const approval = String(application.getPackageName()) === 'com.qinggan.app.launcher'
                ? approvedVoiceCommands(Java) : null;
            const clock = Java.use('android.os.SystemClock');
            const now = () => Number(clock.elapsedRealtime());
            const KEY = 'org.openvoyah.controls.voice';
            const requestId = /^[a-f0-9]{32}$/;
            // Ignore requests left over from a prior injector lifetime.
            let last = String(Global.getString(resolver, KEY) || '');
            const recentIds = new Map();
            try {
                const previous = JSON.parse(last);
                if (typeof previous?.id === 'string' && requestId.test(previous.id))
                    recentIds.set(previous.id, now() + 15000);
            } catch (_) { /* No valid previous request. */ }
            let busy = false;
            const report = (id, status, message) => console.log('OVC_VOICE_RESULT ' +
                JSON.stringify({id, status, message: String(message || 'Vehicle response unavailable').slice(0, 400)}));
            function canManager() {
                const Wrapper = Java.use('com.qinggan.launcher.base.manager.CanBusManagerWrapper');
                const manager = Wrapper.getCanBusManager();
                if (manager === null) throw Error('CAN service is not connected yet. Try later');
                return manager;
            }
            function reviewed(command) {
                if (approval === null) return false;
                if (command === 'ParkingPreflight') return approval.commands.has('RebootTbox');
                if (command === 'ProfileSyncPreflight' || command === 'ProfileSync') {
                    const driver = ['DriverA', 'DriverB', 'DriverC'].some(id => approval.commands.has(id));
                    return driver || (command === 'ProfileSync' && approval.commands.has(command));
                }
                return approval.commands.has(command);
            }
            function guard(manager, expires) {
                if (now() >= expires) throw new VoiceCommandFailure('blocked', 'Vehicle command expired');
                requireStationary(manager, now);
                if (now() >= expires) throw new VoiceCommandFailure('blocked', 'Vehicle command expired');
            }
            setInterval(() => Java.perform(() => {
                const raw = String(Global.getString(resolver, KEY) || '');
                if (!raw || raw === last) return;
                last = raw;
                let request;
                let attempted = false;
                try {
                    request = JSON.parse(raw);
                    if (!request || typeof request.id !== 'string' || !requestId.test(request.id)) return;
                    const received = now();
                    for (const [id, until] of recentIds) if (until <= received) recentIds.delete(id);
                    if (recentIds.has(request.id)) return;
                    if (request.schema !== 3 || request.confirmed !== true ||
                        typeof request.command !== 'string' || !/^[A-Za-z][A-Za-z0-9]{0,63}$/.test(request.command) ||
                        !Number.isSafeInteger(received) || !Number.isSafeInteger(request.expires) ||
                        request.expires <= received || request.expires > received + 15000) {
                        report(request.id, 'blocked', 'Invalid or expired vehicle request');
                        return;
                    }
                    if (recentIds.size >= 64) {
                        report(request.id, 'blocked', 'Vehicle command rate limit');
                        return;
                    }
                    recentIds.set(request.id, received + 15000);
                    if (busy) {
                        report(request.id, 'blocked', 'A vehicle command is in progress');
                        return;
                    }
                    if (!reviewed(request.command)) {
                        report(request.id, 'blocked', 'Vehicle command is not approved for this head unit');
                        return;
                    }
                    if (request.command==='ParkingPreflight') {
                        guard(canManager(), request.expires);
                        report(request.id, 'verified', 'OVC_PARKING_READY');
                        return;
                    }
                    if (request.command==='ProfileSync' || request.command==='ProfileSyncPreflight') {
                        guard(canManager(), request.expires);
                        const preflight = request.command === 'ProfileSyncPreflight';
                        attempted = !preflight;
                        bus.refreshVehicleData(preflight);
                        report(request.id, preflight ? 'verified' : 'accepted',
                            preflight ? 'OVC_PROFILE_SYNC_READY' : 'OEM vehicle data refresh requested');
                        return;
                    }
                    if (request.command==='PowerHold') {
                        guard(canManager(), request.expires);
                        attempted = true;
                        bus.openPowerHold();
                        report(request.id,'accepted','OEM Power Hold confirmation requested'); return;
                    }
                    if(request.command.startsWith('BackLightMode')) {
                        const mode={BackLightModeNight:0,BackLightModeDay:1,BackLightModeAuto:2}[request.command];
                        if(mode===undefined) throw Error('Unknown brightness mode');
                        const Backlight=Java.use('com.qinggan.system.BackLightManager');
                        const light=Backlight.getInstance(ActivityThread.currentApplication(),null);
                        guard(canManager(), request.expires);
                        attempted = true;
                        if(!light.setDimmer.overload('int','int').call(light,mode,0)) throw Error('Brightness service rejected the command');
                        report(request.id,'accepted','Brightness mode requested'); return;
                    }
                    if(request.command==='Sentry') {
                        const Intent=Java.use('android.content.Intent');
                        const intent=Intent.$new('com.arcsoft.sentryserviceimpl.SentryService');
                        intent.setClassName('com.arcsoft.sentryService','com.arcsoft.sentryserviceimpl.SentryService');
                        const am=Java.use('android.app.ActivityManager').getService();
                        const binder=am.peekService(intent,null,String(ActivityThread.currentApplication().getPackageName()));
                        if(binder===null) throw Error('Sentry service is unavailable to this process');
                        const descriptor='com.arcsoft.sentryaidlinterface.ISentryServiceInterface';
                        if(String(binder.getInterfaceDescriptor())!==descriptor) throw Error('Incompatible sentry interface');
                        const current=Global.getInt.overload('android.content.ContentResolver','java.lang.String','int').call(Global,resolver,'THIS_TIME_SWITCH',2);
                        const Parcel=Java.use('android.os.Parcel'); const data=Parcel.obtain(), reply=Parcel.obtain();
                        try {
                            data.writeInterfaceToken(descriptor); data.writeInt(current===1?2:1);
                            guard(canManager(), request.expires);
                            attempted = true;
                            if(!binder.transact(2,data,reply,0)) throw Error('Sentry service does not support switching');
                            reply.readException(); const code=reply.readInt();
                            report(request.id,'unknown','Sentry response '+code+'. OEM confirmation required');
                        } finally { data.recycle();reply.recycle(); }
                        return;
                    }
                    const operations = planFor(request.command, {...request.options, profile:approval.profile});
                    const manager = Java.retain(canManager());
                    const retained = [manager];
                    const methods = {};
                    function method(bus) {
                        if (methods[bus]) return methods[bus];
                        const name = bus === 'temperature' ? 'setAirConditionStateEx'
                            : bus === 'climate' ? 'setAirConditionState' : 'setVehicleState';
                        const valueType = bus === 'temperature' ? 'float' : 'int';
                        const overloads = manager[name].overloads.filter(m => m.argumentTypes.length === 2 && m.argumentTypes[1].className === valueType);
                        if (overloads.length !== 1) throw Error('Incompatible API: ' + name);
                        return methods[bus] = overloads[0];
                    }
                    function isH97() {
                        try { return Java.use('com.qinggan.launcher.base.config.AppConfig').isH97(); }
                        catch (error) { return false; }
                    }
                    const adapter = {
                        prepare(op) {
                            guard(manager, request.expires);
                            if(op.bus==='individual') {
                                // Read the OEM user's saved settings instead of imposing hardcoded defaults.
                                const Properties=Java.use('com.qinggan.os.SystemProperties');
                                const uid=String(Properties.get('qinggan.account.uid'));
                                if(!uid) throw Error('Current OEM account is unknown');
                                const get=(key,fallback)=>{
                                    const Uri=Java.use('android.net.Uri');
                                    const cursor=resolver.query.overload('android.net.Uri','[Ljava.lang.String;','java.lang.String','[Ljava.lang.String;','java.lang.String').call(
                                        resolver,Uri.parse('content://qinggan.settings/global'),Java.array('java.lang.String',['value']),
                                        'name=?',Java.array('java.lang.String',[key+uid]),null);
                                    if(cursor===null) throw Error('OEM settings provider unavailable');
                                    try { return cursor.moveToFirst()?Number(cursor.getString(0)):fallback; } finally { cursor.close(); }
                                };
                                const eps=get('drive_mode_steeringWheelAssist',2), regen=get('drive_mode_recycleState',4), prop=get('drive_mode_runState',1);
                                if(![eps,regen,prop].every(Number.isInteger) || eps<1 || eps>3 || regen<0 || regen>4 || prop<1 || prop>3) throw Error('Invalid individual mode settings');
                                const Bundle=Java.use('android.os.Bundle');
                                const bundle=Java.retain(Bundle.$new()); retained.push(bundle);
                                bundle.putInt('DRIVING_MODE_SET',5);
                                const second=Java.retain(Bundle.$new()); retained.push(second);
                                if(op.profile==='DREAMER') {
                                    bundle.putInt('EPS_MODE_SET',eps); bundle.putInt('PROP_MODE_SET',prop); bundle.putInt('ISC_MODE_SET',regen);
                                } else {
                                    if(op.profile==='FREE') bundle.putInt('HUM_ENERGY_PTREGEN_LEVL',regen);
                                    second.putInt('EPS_MODE_SET',eps); second.putInt('PROP_MODE_SET',prop);
                                }
                                if(!manager.setVehicleAndAirConditionBundleState) throw Error('CAN bundle API unavailable');
                                return {individual:true,bundle,second:op.profile==='DREAMER'?null:second};
                            }
                            if (op.h97Climate && !isH97())
                                throw Error('This climate command requires the H97 adapter');
                            const target = method(op.bus==='roof'?'vehicle':op.bus);
                            const Type = Java.use(target.argumentTypes[0].className);
                            const property = Java.retain(Type.valueOf(op.property)); retained.push(property);
                            if (op.h97Climate) {
                                const power = Java.retain(Type.valueOf('AC_POWER_SWITCH')); retained.push(power);
                                if (manager.getAirConditionState(power) !== 1) throw Error('Turn on the front climate system first');
                            }
                            if (op.bus==='roof') {
                                const control=Java.retain(Type.valueOf('SunroofControl')); retained.push(control);
                                readRoofPosition(property);
                                return {roof:true,target,property,control,op};
                            }
                            let value = op.value;
                            if(op.toggle) value=manager.getVehicleState(property)===1?0:1;
                            if (op.relative) {
                                const current = Number(manager.getAirCondition().getAirLeftTemperature());
                                const [minimum, maximum] = isH97() ? [155, 305] : [160, 320];
                                const tenths = Math.round(current * 10);
                                if (!Number.isFinite(current) || tenths < minimum || tenths > maximum)
                                    throw Error('No valid current temperature');
                                value = Math.max(minimum, Math.min(maximum, tenths + value));
                            }
                            return {target, property, value};
                        },
                        write(p) {
                            guard(manager, request.expires);
                            if(p.individual) {
                                if(manager.setVehicleAndAirConditionBundleState(null,p.bundle)!==0) throw Error('CAN rejected individual mode');
                                if (p.second) {
                                    try {
                                        guard(manager, request.expires);
                                        if (manager.setVehicleAndAirConditionBundleState(null,p.second)!==0)
                                            throw Error('EPS/PROP request was rejected');
                                    } catch (error) {
                                        throw new VoiceCommandFailure('partial', 'Drive mode was accepted. ' + error.message);
                                    }
                                }
                                return;
                            }
                            if(p.roof) return moveRoof(p);
                            const result = p.target.call(manager,p.property,p.value);
                            if (result !== 0) throw Error('CAN rejected the request: ' + result);
                        }
                    };
                    function readRoofPosition(property) {
                        const value = manager.getVehicleState(property);
                        if (!Number.isInteger(value) || value < 0 || value > 100)
                            throw new VoiceCommandFailure('blocked', 'Roof position unavailable');
                        return value;
                    }
                    function moveRoof(p) {
                        const current = readRoofPosition(p.property);
                        if (Math.abs(current-p.op.target)<=2) return true;
                        const opening=p.op.target>current;
                        const started=now();
                        const write=value=>{ if(p.target.call(manager,p.control,value)!==0) throw Error('CAN rejected roof control'); };
                        return new Promise((resolve,reject)=>{
                            const finish=error=>{
                                try { write(p.op.stop); }
                                catch (_) { reject(new VoiceCommandFailure('unknown', 'Roof STOP response unavailable')); return; }
                                if (error) {
                                    reject(new VoiceCommandFailure('partial', 'Roof STOP accepted. ' + error.message));
                                    return;
                                }
                                try { resolve(Math.abs(readRoofPosition(p.property)-p.op.target)<=2); }
                                catch (_) { resolve(false); }
                            };
                            guard(manager, request.expires);
                            try { write(opening?p.op.open:p.op.close); } catch(error) { finish(error); return; }
                            function tick() { Java.performNow(()=>{
                                try {
                                    guard(manager, request.expires);
                                    const value=readRoofPosition(p.property);
                                    if(Math.abs(value-p.op.target)<=2 || (opening?value>=p.op.target:value<=p.op.target)) { finish();return; }
                                    if(now()-started>8000) throw Error('Roof target not reached before the deadline');
                                    setTimeout(tick,100);
                                } catch(error) { finish(error); }
                            }); }
                            setTimeout(tick,100);
                        });
                    }
                    busy = true;
                    // Every continuation re-enters the VM. No UI thread sleeps.
                    executePlan({prepare:adapter.prepare, write:p => Java.performNow(() => adapter.write(p))}, operations,
                        ms => new Promise(resolve => setTimeout(resolve,ms)))
                        .then(result => report(request.id, result.verified ? 'verified' : 'accepted',
                            result.verified ? 'Vehicle reported the target state' : 'Vehicle request accepted. State unverified'))
                        .catch(error => report(request.id,error.status || 'unknown',error.message))
                        .finally(() => { for(const object of retained) object.$dispose(); busy = false; });
                } catch (error) {
                    if (request && typeof request.id === 'string' && requestId.test(request.id))
                        report(request.id, attempted ? 'unknown' : 'blocked', error.message);
                }
            }),250);
            console.log('OVC_VOICE_READY protocol=3');
        } catch (error) { console.log('OVC_VOICE_ERROR ' + error + '; retry in 30s');setTimeout(()=>installVoice(Java),30000); }
    });
}

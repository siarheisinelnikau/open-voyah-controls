import {planFor, executePlan} from './voice-engine.js';
import {QingganBus} from './qinggan-bus.js';

export function installVoice(Java) {
    Java.perform(() => {
        try {
            const Global = Java.use('android.provider.Settings$Global');
            const ActivityThread = Java.use('android.app.ActivityThread');
            const application = ActivityThread.currentApplication();
            const resolver = application.getContentResolver();
            const bus = new QingganBus(Java, application);
            const KEY = 'org.openvoyah.controls.voice';
            // Ignore requests left over from a prior injector lifetime.
            let last = String(Global.getString(resolver, KEY) || '');
            let busy = false;
            const report = (id, status, message) => console.log('OVC_VOICE_RESULT ' + JSON.stringify({id,status,message}));
            function canManager() {
                const Wrapper = Java.use('com.qinggan.launcher.base.manager.CanBusManagerWrapper');
                const manager = Wrapper.getCanBusManager();
                if (manager === null) throw Error('CAN service is not connected yet. Try later');
                return manager;
            }
            function requireParking(manager) {
                if (String(manager.getGearStatus().name()) !== 'Parking') throw Error('This command requires Parking gear');
            }
            setInterval(() => Java.perform(() => {
                const raw = String(Global.getString(resolver, KEY) || '');
                if (!raw || raw === last || busy) return;
                last = raw;
                let request;
                try {
                    request = JSON.parse(raw);
                    if (![1, 2].includes(request.schema) || !/^[a-f0-9]{32}$/.test(request.id) ||
                        !Number.isFinite(request.expires) || request.expires < Date.now() || request.expires > Date.now()+15000) return;
                    if (request.confirmed !== true) throw Error('Vehicle command was not confirmed');
                    if (request.command==='ParkingPreflight') {
                        requireParking(canManager());
                        report(request.id, 'sent', 'OVC_PARKING_READY');
                        return;
                    }
                    if (request.command==='ProfileSync' || request.command==='ProfileSyncPreflight') {
                        if (request.schema === 2 && request.command === 'ProfileSyncPreflight') requireParking(canManager());
                        bus.refreshVehicleData(request.command === 'ProfileSyncPreflight');
                        report(request.id,'sent',request.command==='ProfileSync'?'OEM vehicle data refresh requested':'OVC_PROFILE_SYNC_READY');
                        return;
                    }
                    if (request.command==='PowerHold') {
                        bus.openPowerHold();
                        report(request.id,'sent','OEM Power Hold confirmation opened'); return;
                    }
                    if(request.command.startsWith('BackLightMode')) {
                        const mode={BackLightModeNight:0,BackLightModeDay:1,BackLightModeAuto:2}[request.command];
                        if(mode===undefined) throw Error('Unknown brightness mode');
                        const Backlight=Java.use('com.qinggan.system.BackLightManager');
                        const light=Backlight.getInstance(ActivityThread.currentApplication(),null);
                        if(!light.setDimmer.overload('int','int').call(light,mode,0)) throw Error('Brightness service rejected the command');
                        report(request.id,'sent','Brightness mode requested'); return;
                    }
                    if(request.command==='Sentry') {
                        if (request.schema === 2) requireParking(canManager());
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
                            if(!binder.transact(2,data,reply,0)) throw Error('Sentry service does not support switching');
                            reply.readException(); const code=reply.readInt();
                            report(request.id,'sent','Sentry response: '+code+'. Check the OEM confirmation dialog');
                        } finally { data.recycle();reply.recycle(); }
                        return;
                    }
                    const operations = planFor(request.command, request.options);
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
                                const current=Number(manager.getVehicleState(property));
                                if(!Number.isInteger(current) || current<0 || current>100) throw Error('No valid roof or shade position');
                                return {roof:true,target,property,control,op,current};
                            }
                            let value = op.value;
                            if (op.parked) requireParking(manager);
                            if(op.toggle) value=manager.getVehicleState(property)===1?0:1;
                            if (op.relative) {
                                const current = Number(manager.getAirCondition().getAirLeftTemperature());
                                const [minimum, maximum] = isH97() ? [155, 305] : [160, 320];
                                const tenths = Math.round(current * 10);
                                if (!Number.isFinite(current) || tenths < minimum || tenths > maximum)
                                    throw Error('No valid current temperature');
                                value = Math.max(minimum, Math.min(maximum, tenths + value));
                            }
                            return {target, property, value, parked: op.parked};
                        },
                        write(p) {
                            if (Date.now() > request.expires) throw Error('Command expired');
                            if (p.parked) requireParking(manager);
                            if(p.individual) {
                                if(manager.setVehicleAndAirConditionBundleState(null,p.bundle)!==0) throw Error('CAN rejected individual mode');
                                if(p.second && manager.setVehicleAndAirConditionBundleState(null,p.second)!==0) throw Error('Drive mode changed but EPS/PROP were rejected');
                                return;
                            }
                            if(p.roof) return moveRoof(p);
                            const result = p.target.call(manager,p.property,p.value);
                            if (result !== 0) throw Error('CAN rejected the request: ' + result);
                        }
                    };
                    function moveRoof(p) {
                        if(Math.abs(p.current-p.op.target)<=2) return Promise.resolve();
                        const opening=p.op.target>p.current;
                        const started=Date.now();
                        const write=value=>{ if(p.target.call(manager,p.control,value)!==0) throw Error('CAN rejected roof control'); };
                        return new Promise((resolve,reject)=>{
                            const finish=error=>{
                                try { write(p.op.stop); } catch(stopError) { reject(Error('STOP was not confirmed: '+stopError)); return; }
                                if(error)reject(error);else resolve();
                            };
                            try { write(opening?p.op.open:p.op.close); } catch(error) { finish(error); return; }
                            function tick() { Java.performNow(()=>{
                                try {
                                    const value=Number(manager.getVehicleState(p.property));
                                    if(!Number.isInteger(value) || value<0 || value>100) throw Error('Roof position unavailable');
                                    if(Math.abs(value-p.op.target)<=2 || (opening?value>=p.op.target:value<=p.op.target)) { finish();return; }
                                    if(Date.now()-started>8000 || Date.now()>request.expires) throw Error('Timeout: target not reached, STOP sent');
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
                        .then(count => report(request.id,'sent','CAN accepted ' + count + ' operations; physical state was not verified'))
                        .catch(error => report(request.id,'error',String(error)))
                        .finally(() => { for(const object of retained) object.$dispose(); busy = false; });
                } catch (error) { if (request && request.id) report(request.id,'error',String(error)); }
            }),250);
            console.log('OVC_VOICE_READY protocol=2');
        } catch (error) { console.log('OVC_VOICE_ERROR ' + error + '; retry in 30s');setTimeout(()=>installVoice(Java),30000); }
    });
}

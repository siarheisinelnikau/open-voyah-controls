import plans from './voice-plans.json';

// Protocol names, not enum ordinals: the installed SDK supplies its own ABI.
export function planFor(id, options = {}) {
    if (['SetDriverTemperature', 'SetPassengerTemperature', 'SetFanSpeed'].includes(id)) {
        const value = options.climateValue;
        const fan = id === 'SetFanSpeed';
        if (!Number.isInteger(value) || value < (fan ? 1 : 16) || value > (fan ? 8 : 30))
            throw Error('Climate value is outside the supported range');
        const property = fan ? 'AC_BLOWER' : id === 'SetDriverTemperature' ? 'AC_LEFT_TEMP' : 'AC_RIGHT_TEMP';
        return [{bus: fan ? 'climate' : 'temperature', property, value, phase:'main', h97Climate:true}];
    }
    if (id.endsWith('LevelDefault')) {
        const level=options.levels?.[id] ?? 2;
        if (![1,2,3].includes(level)) throw Error('Invalid default level');
        const suffix={1:'LevelOne',2:'LevelTwo',3:'LevelTree'}[level];
        const target=plans[id.replace('LevelDefault',suffix)];
        if(!target) throw Error('Unknown default command');
        return target.map(x=>({...x}));
    }
    if(id==='EnableAllHeating') {
        const result=['EnableHeatDriverLevelDefault','EnableHeatPassengerLevelDefault','EnableHeatRearLeftLevelDefault','EnableHeatRearRightLevelDefault','EnableWheelHeat'].flatMap(key=>planFor(key,options));
        return result.sort((a,b)=>(a.phase==='post'?1:0)-(b.phase==='post'?1:0));
    }
    if(id==='VentilationCommand' || id==='SmokeCommand') {
        const property=id==='SmokeCommand'?'DRIVER_WINDOW_CONTROL':'ALL_WINDOW_CONTROL';
        const action={bus:'vehicle',property,value:4,phase:'main'};
        return options[id==='SmokeCommand'?'doubleSmoke':'doubleVentilation']===true ? [action,{...action,phase:'post'}] : [action];
    }
    if (plans[id]) return plans[id].map(x => ({...x}));
    const op = (property, value, phase = 'main') => ({bus:'vehicle',property,value,phase});
    const profile = ({NXP:'FREE',FREE_NORWAY:'FREE',FREE_REST_BY:'FREE_REST',FREE_REST_OVERSEA:'FREE_REST',DREAMER_REST:'DREAMER',DREAMER_REST_MI:'DREAMER',DREAMER_REST_MI_V2:'DREAMER'})[options.profile] || options.profile;
    if (['AdaptiveLightOn','AdaptiveLightOff'].includes(id)) {
        if (!['FREE','FREE_REST','DREAMER'].includes(profile)) throw Error('Select a vehicle profile for adaptive lights');
        const ihbc=['FREE_REST','FREE_REST_BY','FREE_REST_OVERSEA','DREAMER_REST','DREAMER_REST_MI','DREAMER_REST_MI_V2'].includes(options.profile);
        return [op(ihbc ? 'IHBCFunction' : 'ADB_SWITCH', id.endsWith('On') ? 1 : ihbc ? 2 : 0)];
    }
    if (id==='SmokeCommand') return [op('DRIVER_WINDOW_CONTROL',4)];
    if (id==='CarWashMode') return [{...op('CAR_CLEANING_MODE_SWITCH',1),parked:true},op('CAR_CLEANING_MODE_SWITCH',0,'post')];
    if (id==='WiperMaintain') {
        if (!['FREE','FREE_REST','DREAMER'].includes(profile)) throw Error('Select a vehicle profile');
        return [{...op('WIPPER_MAINTAIN_SWITCH',1),parked:true,toggle:options.profile==='DREAMER'}];
    }
    if (id==='OpenSunroofWithPercent' || id==='OpenSunshadeWithPercent') {
        const target = percentFrom(options.phrase);
        if (target===null) throw Error('Percentage not recognized');
        const shade = id==='OpenSunshadeWithPercent';
        return [{bus:'roof',property:shade?'ROLL_OPEN_PERCENT':'SUNROOF_OPEN_PERCENT',target,
            open:shade?5:1,close:shade?6:2,stop:shade?7:3,phase:'main'}];
    }
    if(id==='IndividualMode') {
        if (!['FREE','FREE_REST','DREAMER'].includes(profile)) throw Error('Select a vehicle profile');
        return [{bus:'individual',property:'DRIVING_MODE_SET',value:5,phase:'main',profile}];
    }
    const modes = {EcoMode:1,ComfortMode:2,SportMode:3,OffroadMode:4,SnowMode:6};
    if (modes[id]) {
        if (!['FREE','FREE_REST','DREAMER'].includes(profile)) throw Error('Select a vehicle profile for drive mode');
        const mode = modes[id];
        const result = [op('DRIVING_MODE_SET',mode)];
        if (profile==='DREAMER') {
            const [eps,isc,prop] = {1:[2,1,2],2:[2,2,1],3:[3,3,3],4:[2,4,3],6:[2,3,2]}[mode];
            result.push(op('EPS_MODE_SET',eps),op('ISC_MODE_SET',isc),op('PROP_MODE_SET',prop));
        }
        return result;
    }
    const engine = {ElectroMode:2,ForcedElectroMode:5,FuelMode:3,HybridMode:3,IntellectMode:1,SaveMode:4,SaveModeWithSOC:4};
    if (engine[id]) {
        if (!['FREE','FREE_REST','DREAMER'].includes(profile)) throw Error('Select a vehicle profile for energy mode');
        let value=engine[id];
        if (profile === 'DREAMER') {
            if (id === 'HybridMode') value = 1;
            if (id.startsWith('Save')) value = 3;
            if (id === 'ForcedElectroMode') value = 2;
        }
        const result = profile === 'DREAMER' ? [op('FORCE_EV_MODE_SET', id === 'ForcedElectroMode' ? 1 : 0)] : [];
        result.push(op('IVI_SOC_MODESET',value));
        if(id==='SaveModeWithSOC') {
            const names=['двадцать пять','тридцать','тридцать пять','сорок','сорок пять','пятьдесят','пятьдесят пять','шестьдесят','шестьдесят пять','семьдесят','семьдесят пять','восемьдесят'];
            const english=['twenty five','thirty','thirty five','forty','forty five','fifty','fifty five','sixty','sixty five','seventy','seventy five','eighty'];
            const phrase=String(options.phrase||'');
            const index=phrase.startsWith('save ')?english.indexOf(phrase.slice(5)):names.indexOf(phrase.replace(/^сохранение /,''));
            if(index<0) throw Error('SOC level not recognized');
            result.push(op('SREV_SOC_SET',index,'post'));
        }
        return result;
    }
    throw Error('No built-in handler or assigned app for this command');
}

export function percentFrom(phrase) {
    if (typeof phrase!=='string') return null;
    const words=['десять','двадцать','тридцать','сорок','пятьдесят','шестьдесят','семьдесят','восемьдесят','девяносто','сто'];
    for(let i=0;i<words.length;i++) if(new RegExp('(?:^| )'+words[i]+'(?: процентов)?$').test(phrase)) return (i+1)*10;
    const english=['ten','twenty','thirty','forty','fifty','sixty','seventy','eighty','ninety','hundred'];
    for(let i=0;i<english.length;i++) if(new RegExp('(?:^| )'+english[i]+'(?: percent)?$').test(phrase)) return (i+1)*10;
    return null;
}

/** Preflight the entire plan before any writes. Never replay after partial failure. */
export async function executePlan(adapter, operations, delay) {
    const prepared = operations.map(op => adapter.prepare(op));
    let completed = 0;
    try {
        for (let i=0;i<prepared.length;i++) {
            if (i) await delay(operations[i].phase === 'post' ? 1000 : 150);
            await adapter.write(prepared[i]); completed++;
        }
        return completed;
    } catch (error) { throw Error('Completed ' + completed + '/' + prepared.length + '. ' + error + '. Automatic retry disabled'); }
}

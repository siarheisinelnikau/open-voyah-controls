const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const read=name=>fs.readFileSync(path.join(__dirname,'../hooks',name),'utf8').replace(/^import .*;$/gm,'').replace(/^export /gm,'');
const preset=name=>fs.readFileSync(path.join(__dirname,'../hooks/localization',name),'utf8');
const source='const ru='+preset('ru.json')+';\nconst en='+preset('en.json')+';\n'+read('report.js')+'\n'+read('localization.js');
const env={};vm.createContext(env);vm.runInContext(source,env);
const config={schema:1,enabled:true,products:['f0506h'],packages:{'com.qinggan.app.launcher':{'设置':'Settings'}}};
test('translations are exact and scoped to package and product',()=>{
    assert.equal(env.translated(config,'com.qinggan.app.launcher','f0506h','设置'),'Settings');
    assert.equal(env.translated(config,'com.qinggan.app.vehicle','f0506h','设置'),'设置');
    assert.equal(env.translated(config,'com.qinggan.app.launcher','other','设置'),'设置');
    assert.equal(env.translated(config,'com.qinggan.app.launcher','f0506h','设置!'),'设置!');
});
test('disabled and missing dictionaries preserve original text',()=>{
    assert.equal(env.translated({...config,enabled:false},'com.qinggan.app.launcher','f0506h','设置'),'设置');
    assert.equal(env.translated(null,'com.qinggan.app.launcher','f0506h','设置'),'设置');
    assert.equal(env.translated(config,'com.qinggan.app.launcher','f0506h','toString'),'toString');
    assert.equal(env.translated(config,'com.qinggan.app.launcher','f0506h','__proto__'),'__proto__');
});
test('a named preset applies per package and custom entries override it',()=>{
    const presets={ru:{packages:{'com.qinggan.app.launcher':{Settings:'Настройки',Phone:'Телефон'}}}};
    const named={schema:1,enabled:true,preset:'ru',products:[],packages:{'com.qinggan.app.launcher':{Phone:'Звонки'}}};
    assert.equal(env.translated(named,'com.qinggan.app.launcher','any','Settings',presets),'Настройки');
    assert.equal(env.translated(named,'com.qinggan.app.launcher','any','Phone',presets),'Звонки');
    assert.equal(env.translated(named,'com.qinggan.app.vehicle','any','Settings',presets),'Settings');
    assert.equal(env.translated({...named,preset:'de'},'com.qinggan.app.launcher','any','Settings',presets),'Settings');
    assert.equal(env.translated({...named,preset:'toString'},'com.qinggan.app.launcher','any','Settings',presets),'Settings');
    assert.equal(env.translated({...named,enabled:false},'com.qinggan.app.launcher','any','Settings',presets),'Settings');
});
test('the bundled presets are used when the setting names one',()=>{
    const named={schema:1,enabled:true,preset:'ru',products:[],packages:{}};
    assert.equal(env.translated(named,'com.qinggan.app.launcher','f6802h','设置'),'Настройки');
    assert.equal(env.translated({...named,preset:'en'},'com.qinggan.app.launcher','f6802h','设置'),'Settings');
});
test('partial installation rolls back and retries independently',()=>{
    const text={overload(){return this;},call(){}};const retry=[];
    const classes={
        'android.app.ActivityThread':{currentApplication(){return {getPackageName(){return 'com.qinggan.app.launcher';},getContentResolver(){return {};}};}},
        'android.os.Build':{PRODUCT:{value:'f0506h'}},'android.provider.Settings$Global':{getString(){return JSON.stringify(config);}},
        'java.lang.String':{$new:x=>x},'android.widget.TextView':{setText:text,setHint:{overload(){throw Error('unavailable');}}}
    };
    const Java={perform:fn=>fn(),use:name=>classes[name]};
    const context={Java,console:{log(){}},setTimeout:(fn,ms)=>retry.push(ms)};
    vm.runInNewContext(source+'\ninstallLocalization(Java);',context);
    assert.equal(text.implementation,null);assert.deepEqual(retry,[30000]);
});

function harness(options={}) {
    let raw=options.raw ?? JSON.stringify(config),tick,readFailure=false;
    const logs=[],passed=[],retries=[],refreshed=[],parses={count:0};
    const method=record=>({overload(){return this;},call(_view,value){record.push(value);return 'oem';}});
    const text=method(passed),hint=method(passed),typed={overload(){return this;},call:()=>options.typed},
        resource={overload(){return this;},call:()=>options.resource};
    const classes={
        'android.app.ActivityThread':{currentApplication(){return {
            getPackageName(){return options.packageName ?? 'com.qinggan.app.launcher';},getContentResolver(){return {};}};}},
        'android.os.Build':{PRODUCT:{value:options.product ?? 'f0506h'}},
        'android.provider.Settings$Global':{getString(){
            if(readFailure) throw Error('java.lang.IllegalStateException: provider for private.package unavailable');
            return raw;
        }},
        'java.lang.String':{$new:value=>({translated:value})},
        'android.widget.TextView':{setText:text,setHint:options.noHint?{overload(){
            throw Error('java.lang.NoSuchMethodError: private.Signature');
        }}:hint},
        'android.content.res.TypedArray':{getText:typed},
        'android.content.res.Resources':{getText:resource}
    };
    const Java={perform:fn=>fn(),use:name=>classes[name],retain:view=>view,scheduleOnMainThread:fn=>fn(),
        choose(_name,callbacks){for(const view of options.views ?? []) callbacks.onMatch(view);callbacks.onComplete();}};
    for(const view of options.views ?? []) view.setText={overload:()=>({call:(_view,value)=>refreshed.push(value)})};
    vm.runInNewContext(source+'\ninstallLocalization(Java);',{Java,console:{log:line=>logs.push(line)},
        setInterval:fn=>{tick=fn;},setTimeout:(fn,ms)=>retries.push({fn,ms}),
        JSON:{parse:value=>{parses.count++;return JSON.parse(value);},stringify:JSON.stringify}});
    return {logs,passed,retries,refreshed,parses,tick:()=>tick(),setRaw:value=>{raw=value;},
        failReads:value=>{readFailure=value;},
        setText:value=>text.implementation.call({},value,'NORMAL'),
        setHint:value=>hint.implementation.call({},value),
        getText:()=>typed.implementation.call({},0),
        getResourceText:()=>resource.implementation.call({},0x7f010001)};
}
// A Java CharSequence: String() on it is a JNI toString() call.
function chars(text) {
    const value={calls:0,toString(){value.calls++;return text;}};
    return value;
}

test('without an applicable dictionary text passes through unconverted and unchanged',()=>{
    for(const options of [{raw:JSON.stringify({...config,enabled:false})},{raw:JSON.stringify({...config,schema:2})},
        {product:'other'},{packageName:'com.qinggan.app.vehicle'},{raw:''}]) {
        const value=chars('设置');
        const h=harness({...options,typed:value});
        h.setText(value);h.setHint(value);
        assert.equal(h.getText(),value);
        assert.deepEqual(h.passed,[value,value]);
        assert.ok(h.passed.every(item=>item===value));
        assert.equal(value.calls,0);
        assert.deepEqual(h.logs,['OVC_LOCALIZATION_READY '+(options.packageName ?? 'com.qinggan.app.launcher')]);
    }
});

test('an applicable dictionary translates exact text and keeps every other text object',()=>{
    const h=harness();
    const known=chars('设置'),other=chars('其他');
    h.setText(known);h.setHint(other);h.setText(null);
    assert.deepEqual(h.passed[0],{translated:'Settings'});
    assert.equal(h.passed[1],other);
    assert.equal(h.passed[2],null);
    assert.equal(known.calls,1);
    h.setRaw(JSON.stringify({...config,enabled:false}));h.tick();
    h.setText(known);
    assert.equal(h.passed[3],known);
    assert.equal(known.calls,1);
});

test('a failing conversion keeps the original text and is reported without its message',()=>{
    const h=harness();
    const hostile={toString(){throw Error('java.lang.IllegalStateException: private user text');}};
    for(let attempt=0;attempt<5;attempt++) assert.equal(h.setText(hostile),'oem');
    assert.equal(h.passed.length,5);
    assert.ok(h.passed.every(value=>value===hostile));
    assert.deepEqual(h.logs.filter(line=>line.startsWith('OVC_LOCALIZATION_ERROR')),
        ['OVC_LOCALIZATION_ERROR stage=convert kind=IllegalStateException']);
    assert.equal(h.logs.some(line=>line.includes('private')),false);
});

test('a rejected document is parsed and reported once per value and keeps translation off',()=>{
    const h=harness({raw:'{"schema":1,'});
    for(let second=0;second<5;second++) h.tick();
    assert.equal(h.parses.count,1);
    const value=chars('设置');
    h.setText(value);
    assert.equal(h.passed[0],value);
    assert.equal(value.calls,0);
    h.setRaw('[private');h.tick();h.tick();
    assert.equal(h.parses.count,2);
    assert.deepEqual(h.logs.filter(line=>line.startsWith('OVC_LOCALIZATION_ERROR')),[
        'OVC_LOCALIZATION_ERROR stage=config kind=SyntaxError bytes=12',
        'OVC_LOCALIZATION_ERROR stage=config kind=SyntaxError bytes=8',
    ]);
    h.setRaw(JSON.stringify(config));h.tick();
    h.setText(value);
    assert.deepEqual(h.passed[1],{translated:'Settings'});
    assert.equal(h.logs.some(line=>line.includes('private')),false);
});

test('an unreadable setting turns translation off until the same value can be read again',()=>{
    const h=harness();
    h.failReads(true);h.tick();h.tick();
    const value=chars('设置');
    h.setText(value);
    assert.equal(h.passed[0],value);
    assert.deepEqual(h.logs.filter(line=>line.startsWith('OVC_LOCALIZATION_ERROR')),
        ['OVC_LOCALIZATION_ERROR stage=settings kind=IllegalStateException']);
    h.failReads(false);h.tick();
    h.setText(value);
    assert.deepEqual(h.passed[1],{translated:'Settings'});
});

test('an installation failure names its class, resets hooks and keeps retrying every 30 s',()=>{
    const h=harness({noHint:true});
    assert.deepEqual(h.logs,['OVC_LOCALIZATION_ERROR stage=install kind=NoSuchMethodError']);
    assert.deepEqual(h.retries.map(retry=>retry.ms),[30000]);
    h.retries[0].fn();
    assert.deepEqual(h.retries.map(retry=>retry.ms),[30000,30000]);
    // The same failure on every retry stays a single line until it repeats ten times.
    assert.equal(h.logs.length,1);
});

test('visible text is refreshed on attach and a failing view is reported and released',()=>{
    const disposed=[];
    const working={getText:()=>chars('设置'),getHint:()=>null,$dispose(){disposed.push('working');}};
    const broken={getText(){throw Error('java.lang.IllegalStateException: private view');},$dispose(){disposed.push('broken');}};
    const h=harness({views:[working,broken]});
    assert.deepEqual(h.refreshed,[{translated:'Settings'}]);
    assert.deepEqual(disposed,['working','broken']);
    assert.deepEqual(h.logs.filter(line=>line.startsWith('OVC_LOCALIZATION_ERROR')),
        ['OVC_LOCALIZATION_ERROR stage=refresh kind=IllegalStateException']);
});

test('a resource template is translated before the app formats it',()=>{
    const named=JSON.stringify({schema:1,enabled:true,preset:'ru',products:[],packages:{}});
    const h=harness({raw:named,packageName:'com.qinggan.bluetoothphone',resource:chars('%1$s connected')});
    assert.deepEqual(h.getResourceText(),{translated:'%1$s подключён'});
    const other=chars('Plain resource');
    const k=harness({raw:named,packageName:'com.qinggan.bluetoothphone',resource:other});
    assert.equal(k.getResourceText(),other);
});

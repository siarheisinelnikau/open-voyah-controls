const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const read=name=>fs.readFileSync(path.join(__dirname,'../hooks',name),'utf8').replace(/^import .*;$/gm,'').replace(/^export /gm,'');
const preset=name=>fs.readFileSync(path.join(__dirname,'../hooks/localization',name),'utf8');
const source=['ru','en','kk'].map(name=>'const '+name+'='+preset(name+'.json')+';').join('\n')
    +'\n'+read('report.js')+'\n'+read('localization.js');
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
    assert.equal(env.translated({...named,preset:'kk'},'com.qinggan.app.launcher','f6802h','设置'),'Баптаулар');
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
    const logs=[],passed=[],retries=[],refreshed=[],parses={count:0},originals=[];
    // One Frida method object per signature; call() runs the original OEM method.
    const overloads=original=>{
        const bySig={};
        return {bySig,overload(...sig){
            const key=sig.join(',');
            return bySig[key] ??= {call:(self,...args)=>original(key,self,...args)};
        }};
    };
    const setText=overloads((sig,self,...args)=>{
        // A shown view's text set again (the refresh after attaching) is recorded on its own.
        if(sig==='java.lang.CharSequence' && self?.shown) { refreshed.push(args[0]); return 'oem'; }
        if(sig.startsWith('java.lang.CharSequence')) { passed.push(args[0]); return 'oem'; }
        originals.push('setText('+sig+')');
    });
    const setHint=options.noHint?{overload(){throw Error('java.lang.NoSuchMethodError: private.Signature');}}:
        overloads((sig,_self,...args)=>{
            if(sig==='java.lang.CharSequence') { passed.push(args[0]); return 'oem'; }
            originals.push('setHint('+sig+')');
        });
    const attached=overloads(()=>{ originals.push('onAttachedToWindow'); });
    const typed=overloads(()=>options.typed);
    const resourceText=overloads(()=>options.resource);
    const strings=(name)=>overloads((sig,_self,id,args)=>{
        originals.push(name+'.getString('+sig+')');
        return sig==='int' ? (options.string ?? 'plain') : 'oem formatted';
    });
    const resourceStrings=strings('Resources'),contextStrings=strings('Context');
    const contextText=overloads(()=>options.string ?? 'plain');
    const format=overloads((_sig,_self,template,args)=>({formatted:template,args}));
    const classes={
        'android.app.ActivityThread':{currentApplication(){return {
            getPackageName(){return options.packageName ?? 'com.qinggan.app.launcher';},getContentResolver(){return {};}};}},
        'android.os.Build':{PRODUCT:{value:options.product ?? 'f0506h'}},
        'android.provider.Settings$Global':{getString(){
            if(readFailure) throw Error('java.lang.IllegalStateException: provider for private.package unavailable');
            return raw;
        }},
        'java.lang.String':{$new:value=>({translated:value}),format},
        'android.widget.TextView':{setText,setHint,onAttachedToWindow:attached,class:{isInstance:view=>!view.children}},
        'android.view.ViewGroup':{class:{isInstance:view=>!!view.children}},
        'android.view.View':{},
        // One window whose root holds the given views; a failing window list is reported like a failing view.
        'android.view.WindowManagerGlobal':{getInstance(){
            if(options.brokenWindows) throw Error('java.lang.IllegalStateException: private window');
            const roots=options.views?[{children:options.views,getChildCount:()=>options.views.length,
                getChildAt:index=>options.views[index]}]:[];
            return {mViews:{value:{size:()=>roots.length,get:index=>roots[index]}}};
        }},
        'android.content.res.TypedArray':{getText:typed},
        'android.content.res.Resources':{getText:resourceText,getString:resourceStrings},
        'android.content.Context':{getString:contextStrings,getText:contextText}
    };
    const Java={perform:fn=>fn(),use:name=>classes[name],cast:value=>value,scheduleOnMainThread:fn=>fn()};
    vm.runInNewContext(source+'\ninstallLocalization(Java);',{Java,console:{log:line=>logs.push(line)},
        setInterval:fn=>{tick=fn;},setTimeout:(fn,ms)=>retries.push({fn,ms}),
        JSON:{parse:value=>{parses.count++;return JSON.parse(value);},stringify:JSON.stringify}});
    const main=setText.overload('java.lang.CharSequence','android.widget.TextView$BufferType'),
        hint=options.noHint?{}:setHint.overload('java.lang.CharSequence'),typedText=typed.overload('int'),
        resource=resourceText.overload('int');
    // An OEM call: through the hook while one is installed, straight to the original method otherwise.
    const invoke=(method,self,...args)=>(method.implementation ?? ((...a)=>method.call(self,...a))).call(self,...args);
    return {logs,passed,retries,refreshed,parses,originals,tick:()=>tick(),setRaw:value=>{raw=value;},
        failReads:value=>{readFailure=value;},
        hooked:()=>[main,hint,typedText,resource].map(item=>typeof item.implementation==='function'),
        setText:value=>invoke(main,{},value,'NORMAL'),
        setInner:(view,value)=>invoke(setText.overload('java.lang.CharSequence','android.widget.TextView$BufferType',
            'boolean','int'),view,value,'NORMAL',true,0),
        setHint:value=>invoke(hint,{},value),
        getText:()=>invoke(typedText,{},0),
        getResourceText:()=>invoke(resource,{},0x7f010001),
        setTextId:view=>invoke(setText.overload('int'),view,0x7f010002),
        attach:view=>invoke(attached.overload(),view),
        resourcesString:(...args)=>invoke(resourceStrings.overload(...(args.length>1?['int','[Ljava.lang.Object;']:['int'])),
            {},...args),
        contextString:(...args)=>invoke(contextStrings.overload(...(args.length>1?['int','[Ljava.lang.Object;']:['int'])),
            {getResources:()=>({})},...args)};
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
        // Without a dictionary nothing is hooked, so the OEM text calls do not pass through Frida.
        assert.deepEqual(h.hooked(),[false,false,false,false]);
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
    assert.deepEqual(h.hooked(),[true,true,true,true]);
    h.setRaw(JSON.stringify({...config,enabled:false}));h.tick();
    assert.deepEqual(h.hooked(),[false,false,false,false]);
    h.setText(known);
    assert.equal(h.passed[3],known);
    assert.equal(known.calls,1);
    h.setRaw(JSON.stringify(config));h.tick();
    assert.deepEqual(h.hooked(),[true,true,true,true]);
    h.setText(known);
    assert.deepEqual(h.passed[4],{translated:'Settings'});
});

test('a dictionary that arrives later installs the hooks and refreshes visible text',()=>{
    const working={shown:true,onCheckIsTextEditor:()=>false,getText:()=>chars('设置'),getHint:()=>null};
    const h=harness({raw:'',views:[working]});
    assert.deepEqual(h.hooked(),[false,false,false,false]);
    assert.deepEqual(h.refreshed,[]);
    h.setRaw(JSON.stringify(config));h.tick();
    assert.deepEqual(h.hooked(),[true,true,true,true]);
    assert.deepEqual(h.refreshed,[{translated:'Settings'}]);
    h.tick();
    assert.deepEqual(h.refreshed,[{translated:'Settings'}]);
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

test('visible text in every window is refreshed on attach and a failing view or window is reported',()=>{
    const working={shown:true,onCheckIsTextEditor:()=>false,getText:()=>chars('设置'),getHint:()=>null};
    const broken={shown:true,onCheckIsTextEditor(){throw Error('java.lang.IllegalStateException: private view');}};
    const editor={shown:true,onCheckIsTextEditor:()=>true,getText:()=>chars('设置'),getHint:()=>null};
    const h=harness({views:[broken,working,editor]});
    // Subclasses are found by walking the windows, not by their exact class; typed text in an editor stays.
    assert.deepEqual(h.refreshed,[{translated:'Settings'}]);
    assert.deepEqual(h.logs.filter(line=>line.startsWith('OVC_LOCALIZATION_ERROR')),
        ['OVC_LOCALIZATION_ERROR stage=refresh kind=IllegalStateException']);
    const windows=harness({brokenWindows:true});
    assert.deepEqual(windows.hooked(),[true,true,true,true]);
    assert.deepEqual(windows.logs.filter(line=>line.startsWith('OVC_LOCALIZATION_ERROR')),
        ['OVC_LOCALIZATION_ERROR stage=refresh kind=IllegalStateException']);
});

test('text set past the public setText hooks is translated where every variant ends',()=>{
    const h=harness();
    const label={onCheckIsTextEditor:()=>false};
    h.setInner(label,chars('设置'));
    assert.deepEqual(h.passed,[{translated:'Settings'}]);
    const editor={onCheckIsTextEditor:()=>true};
    const typed=chars('设置');
    h.setInner(editor,typed);
    assert.equal(h.passed[1],typed);
    // Other text keeps its object and asks nothing of the view.
    const other=chars('其他');
    h.setInner({onCheckIsTextEditor(){throw Error('not asked');}},other);
    assert.equal(h.passed[2],other);
    h.setRaw(JSON.stringify({...config,enabled:false}));h.tick();
    const off=chars('设置');
    h.setInner(label,off);
    assert.equal(h.passed[3],off);
});

test('a resource template is translated before the app formats it',()=>{
    const named=JSON.stringify({schema:1,enabled:true,preset:'ru',products:[],packages:{}});
    const h=harness({raw:named,packageName:'com.qinggan.bluetoothphone',resource:chars('%1$s connected')});
    assert.deepEqual(h.getResourceText(),{translated:'%1$s подключён'});
    const other=chars('Plain resource');
    const k=harness({raw:named,packageName:'com.qinggan.bluetoothphone',resource:other});
    assert.equal(k.getResourceText(),other);
});

test('a hook that cannot be installed later is reported, reset and not retried for 30 s',()=>{
    const h=harness({raw:'',noHint:true});
    assert.deepEqual(h.logs,['OVC_LOCALIZATION_READY com.qinggan.app.launcher']);
    h.setRaw(JSON.stringify(config));h.tick();h.tick();h.tick();
    assert.deepEqual(h.hooked(),[false,false,false,false]);
    assert.deepEqual(h.logs.slice(1),['OVC_LOCALIZATION_ERROR stage=install kind=NoSuchMethodError']);
    assert.deepEqual(h.retries,[]);
});

test('a resource id or a shown view gets its label translated after the original call',()=>{
    const h=harness();
    const set=[];
    const label={onCheckIsTextEditor:()=>false,getText:()=>chars('设置'),getHint:()=>null};
    h.setTextId(label);
    assert.deepEqual(h.originals,['setText(int)']);
    assert.deepEqual(h.passed,[{translated:'Settings'}]);
    const editor={onCheckIsTextEditor:()=>true,getText:()=>chars('设置'),getHint:()=>chars('设置')};
    h.attach(editor);
    assert.deepEqual(h.originals,['setText(int)','onAttachedToWindow']);
    // The driver's typed text stays; only the hint is a label.
    assert.deepEqual(h.passed,[{translated:'Settings'},{translated:'Settings'}]);
    const other={onCheckIsTextEditor:()=>false,getText:()=>chars('其他'),getHint:()=>null};
    h.attach(other);
    assert.equal(h.passed.length,2);
});

test('getString translates plain strings and formats a translated template itself',()=>{
    const named=JSON.stringify({schema:1,enabled:true,preset:'ru',products:[],packages:{}});
    const h=harness({raw:named,packageName:'com.qinggan.bluetoothphone',string:'Phone',resource:chars('%1$s connected')});
    assert.deepEqual(h.contextString(1),{translated:'Телефон'});
    const args=['Redmi'];
    assert.deepEqual(h.resourcesString(1,args),{formatted:'%1$s подключён',args});
    assert.deepEqual(h.contextString(1,args),{formatted:'%1$s подключён',args});
    const plain=harness({raw:named,packageName:'com.qinggan.bluetoothphone',resource:chars('Unknown %1$s')});
    assert.equal(plain.resourcesString(1,args),'oem formatted');
    assert.deepEqual(plain.originals,['Resources.getString(int,[Ljava.lang.Object;)']);
});

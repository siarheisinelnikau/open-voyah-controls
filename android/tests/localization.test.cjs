const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../hooks/localization.js'),'utf8').replaceAll('export function','function');
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

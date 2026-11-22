const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path');

// The built-in dictionaries are bundled into the localization agent; the app only names one in the setting.
const presets=Object.fromEntries(['ru','en'].map(name=>[name,
    JSON.parse(fs.readFileSync(path.join(__dirname,'../hooks/localization',name+'.json'),'utf8'))]));
const settings=fs.readFileSync(path.join(__dirname,
    '../app/src/main/java/org/openvoyah/controls/data/settings/LocalizationSettings.java'),'utf8');
const declared=/PACKAGES = [^;]*Arrays\.asList\(([^;]*?)\)\)\);/s.exec(settings);
const PACKAGES=new Set([...declared[1].matchAll(/"([a-z.]+)"/g)].map(match=>match[1]));
const CJK=/[㐀-鿿]/, CYRILLIC=/[Ѐ-ӿ]/;
// Templates such as "%1$s connected" are replaced through Resources.getText before the app formats them, so a
// translation must keep exactly the same specifiers in the same order, or String.format in the OEM app would throw.
const SPEC=/%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]/g;
const MARKUP=/\{\d+\}|<\/?[a-z]+>/;
const specs=text=>text.match(SPEC) ?? [];

test('presets are schema 1 documents for the declared packages only',()=>{
    assert.ok(PACKAGES.has('com.qinggan.app.launcher') && PACKAGES.has('com.qinggan.app.hiboard'));
    for(const [name,preset] of Object.entries(presets)) {
        assert.equal(preset.schema,1,name);
        assert.deepEqual(preset.products,[],name);
        for(const pkg of Object.keys(preset.packages)) assert.ok(PACKAGES.has(pkg),`${name}: ${pkg}`);
    }
});

test('every entry is a real, bounded replacement',()=>{
    for(const [name,preset] of Object.entries(presets)) for(const [pkg,table] of Object.entries(preset.packages)) {
        for(const [source,value] of Object.entries(table)) {
            const where=`${name} ${pkg} ${JSON.stringify(source)}`;
            assert.equal(typeof value,'string',where);
            assert.ok(source.trim().length>0 && source.length<=512 && value.trim().length>0 && value.length<=512,where);
            assert.notEqual(value,source,where);
            assert.ok(!MARKUP.test(source) && !MARKUP.test(value),where);
            assert.deepEqual(specs(value),specs(source),`${where}: same format specifiers`);
            assert.equal(source.trim()===source,value.trim()===value,`${where}: keep the source's outer spaces`);
            assert.equal(source.split('\n').length>1,value.split('\n').length>1,`${where}: keep line breaks`);
            // A Chinese label may become a name that stays Latin in Russian ("蓝牙" → "Bluetooth").
            if(name==='ru' && !CJK.test(source)) assert.ok(CYRILLIC.test(value),`${where}: Russian value`);
            if(name==='en') {
                // The firmware already shows English; the English preset only replaces leftover Chinese.
                assert.ok(CJK.test(source),`${where}: English preset keys are Chinese`);
                assert.ok(!CJK.test(value),where);
            }
        }
    }
});

test('Russian covers every Chinese text the English preset translates',()=>{
    for(const [pkg,table] of Object.entries(presets.en.packages))
        for(const source of Object.keys(table))
            assert.ok(Object.hasOwn(presets.ru.packages[pkg] ?? {},source),`${pkg} ${source}`);
});

test('presets cover the OEM apps of the current firmware and stay within the agent budget',()=>{
    for(const pkg of PACKAGES) assert.ok(Object.keys(presets.ru.packages[pkg] ?? {}).length>=50,pkg);
    for(const name of Object.keys(presets)) {
        const bytes=fs.statSync(path.join(__dirname,'../hooks/localization',name+'.json')).size;
        assert.ok(bytes<=320*1024,`${name}: ${bytes} bytes`);
    }
});

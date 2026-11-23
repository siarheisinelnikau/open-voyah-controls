const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname,'../hooks/account-state.js'),'utf8')
    .replace(/^import .*;$/gm,'').replace(/^export /gm,'');
let classify;
vm.runInNewContext(source+'\nresult=classifyAccountRecord;', {set result(value){classify=value;}});
const sample = 'Example Driver%5550100%0%fixture-account%0';

function observe(options={}) {
    let position=0, pass=0, opened=0, closed=0, statCalls=0, result;
    const raw=options.raw??sample, size=Buffer.byteLength(raw);
    const stat=()=>{
        statCalls++;
        const data={st_dev:1,st_ino:options.replaced&&statCalls>2?2:1,st_size:size,st_mtime:5,st_ctime:5,st_mode:options.regular===false?0:1};
        return Object.fromEntries(Object.entries(data).map(([key,value])=>[key,{value}]));
    };
    const C={O_RDONLY:{value:0},O_NOFOLLOW:{value:1},O_CLOEXEC:{value:2},SEEK_SET:{value:0},S_ISREG:mode=>mode===1};
    const Os={lstat:stat,fstat:stat,open(_path,flags){assert.equal(flags,3);opened++;return {};},close(){closed++;},
        lseek(){position=0;pass++;},read:{overload:()=>({call(_os,_fd,bytes,offset,count){
            if(options.failRead) throw Error('Sensitive data must not escape');
            const text=options.changed&&pass>0?raw.replace('Driver','Person'):raw;
            const buffer=Buffer.from(text+(options.grows?'extra':''));
            const amount=Math.min(count,3,buffer.length-position);
            for(let i=0;i<amount;i++)bytes[offset+i]=buffer[position+i];
            position+=amount;return amount;
        }})}};
    const Java={array:(_,values)=>values,use(name){
        const types={
            'android.system.Os':Os,
            'java.io.File':{$new:()=>({getCanonicalPath:()=>options.symlink?'/unexpected':'/private/configs/token/accountInfo'})},
            'java.lang.String':{$new:(bytes,start,count)=>Buffer.from(bytes.slice(start,start+count)).toString('utf8')}
        };if(!types[name])throw Error('Unsupported class');return types[name];
    }};
    vm.runInNewContext(source+'\nresult=observeAccountState(Java);',{Java,initializedType:()=>C,set result(value){result=value;}});
    return {result,opened,closed};
}

test('account classification exposes only kind, never identity or a guessed A/B/C slot',()=>{
    assert.equal(classify(sample),'identified');assert.equal(classify(sample+'\n'),'identified');
    assert.equal(classify('guest'),'guest');assert.equal(classify('guest\r\n'),'guest');
    for(const value of ['',null,'A','Guest A','a%b%c%d','x%555%2%account%0','x%555%0%%0',
        'x%555%0%account%-1','x%555%0%account%2147483648',sample+'\0',sample+'\nmore','x'.repeat(8193)]) {
        assert.equal(classify(value),'unavailable');
    }
});
test('bounded account reads support short reads, verify a second observation and close the descriptor',()=>{
    assert.deepEqual(observe(),{result:'identified',opened:1,closed:1});
    assert.deepEqual(observe({raw:'guest'}),{result:'guest',opened:1,closed:1});
});
test('oversized files, empty files, non-regular files and symbolic paths are not opened',()=>{
    for(const options of [{raw:'x'.repeat(8193)},{raw:''},{regular:false},{symlink:true}]) {
        assert.deepEqual(observe(options),{result:'unavailable',opened:0,closed:0});
    }
});
test('growth, replacement and changing contents never produce a stable account observation',()=>{
    for(const options of [{grows:true},{replaced:true},{changed:true}]) {
        assert.deepEqual(observe(options),{result:'unstable',opened:1,closed:1});
    }
});
test('read errors close the descriptor and reveal no exception or account contents',()=>{
    assert.deepEqual(observe({failRead:true}),{result:'unavailable',opened:1,closed:1});
});

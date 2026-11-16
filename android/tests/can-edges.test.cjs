const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../hooks/can-edges.js'), 'utf8').replace(/^export /gm, '');

function harness(options = {}) {
    let time = 100, id = 0, route;
    const timers = new Map(), native = [], launched = [], reports = [];
    const config = {schema:1, steeringSource:'can-edges', steeringEnabled:true, steeringHoldMs:650,
        steeringDebounceMs:100, steering:{voiceShort:{kind:'short'},voiceLong:{kind:'long'},cameraShort:{kind:'short'},cameraLong:{kind:'long'}}};
    const raw = () => options.badJson ? '{' : JSON.stringify(config);
    vm.runInNewContext(source + '\nresult=createCanEdgeRouter(inputs)', {
        inputs:{keys:new Map([[16,'voice'],[76,'camera']]),status:{down:1,up:0,short:3,long:4},readConfig:raw,
            canLaunch:action=>options.unavailable !== action.kind,
            launch:action=>{
                launched.push(action.kind);
                if (options.reenter) route(16,0);
                if (options.fail) throw Error('Unavailable');
                return options.accepted !== false;
            }, now:()=>time, schedule:(run,delay)=>{
                if(options.schedulerFailure) throw Error('No timer');
                timers.set(++id,{run,at:time+delay});return id;
            }, cancel:key=>timers.delete(key), report:reason=>reports.push(reason)},
        set result(value) { route=value; }
    });
    const emit=(code,event)=>{if(!route(code,event))native.push([code,event]);};
    const advance=(ms,fire=true)=>{
        time+=ms;
        if(fire) for(const [key,timer] of timers) if(timer.at<=time){timers.delete(key);timer.run();}
    };
    return {config,options,timers,native,launched,reports,emit,advance,setTime:value=>time=value};
}

test('edge tap owns both edges, fires on release once and consumes repeated release',()=>{
    const h=harness(); h.emit(16,1);h.emit(16,1);h.advance(100);h.emit(16,0);h.emit(16,0);
    h.advance(1000);
    assert.deepEqual(h.launched,['short']);assert.deepEqual(h.native,[]);assert.equal(h.timers.size,0);
});
test('edge hold fires at the threshold without waiting for release and never adds a tap',()=>{
    const h=harness();h.emit(16,1);h.advance(649);assert.deepEqual(h.launched,[]);
    h.advance(1);h.emit(16,1);h.emit(16,0);h.advance(5000);
    assert.deepEqual(h.launched,['long']);assert.deepEqual(h.native,[]);
});
test('a delayed timer classifies by the monotonic release time',()=>{
    const h=harness();h.emit(16,1);h.advance(900,false);h.emit(16,0);h.advance(1);
    assert.deepEqual(h.launched,['long']);assert.equal(h.timers.size,0);
});
test('partial assignments and preflight failures preserve the entire native gesture',()=>{
    for(const change of [h=>delete h.config.steering.voiceLong,h=>delete h.config.steering.voiceShort,
        h=>h.options.unavailable='short',h=>h.options.unavailable='long',h=>h.options.schedulerFailure=true]) {
        const h=harness();change(h);h.emit(16,1);h.advance(1000);h.emit(16,0);
        assert.deepEqual(h.native,[[16,1],[16,0]]);assert.deepEqual(h.launched,[]);
    }
});
test('unknown, disabled and invalid edge configurations are passed through',()=>{
    for(const change of [h=>h.config.steeringSource='can-terminal',h=>h.config.steeringEnabled=false,
        h=>h.options.badJson=true,h=>h.config.steeringHoldMs=0,h=>h.config.steeringDebounceMs=501]) {
        const h=harness();change(h);h.emit(16,1);h.emit(16,0);h.emit(99,1);h.emit(99,0);
        assert.equal(h.native.length,4);assert.deepEqual(h.launched,[]);
    }
});
test('a chord that already exists at down leaves both buttons native',()=>{
    const h=harness();h.emit(99,1);h.emit(16,1);h.advance(1000);h.emit(16,0);h.emit(99,0);
    assert.equal(h.native.length,4);assert.deepEqual(h.launched,[]);
});
test('a chord after capture cancels the owned action without replaying its down',()=>{
    const h=harness();h.emit(16,1);h.emit(76,1);h.advance(1000);h.emit(16,0);h.emit(76,0);
    assert.deepEqual(h.native,[[76,1],[76,0]]);assert.deepEqual(h.launched,[]);assert.equal(h.timers.size,0);
});
test('changed settings cancel a captured gesture but do not leak its up to OEM',()=>{
    for(const mode of ['can-terminal','qg']) {
        const h=harness();h.emit(16,1);h.config.steeringSource=mode;h.advance(1000);h.emit(16,0);
        assert.deepEqual(h.native,[]);assert.deepEqual(h.launched,[]);
    }
});
test('late target removal and launch denial are not replayed as physical events',()=>{
    for(const option of [{fail:true},{accepted:false}]) {
        const h=harness(option);h.emit(16,1);h.emit(16,0);h.emit(16,0);
        assert.deepEqual(h.launched,['short']);assert.deepEqual(h.native,[]);
        assert.deepEqual(h.reports,['launch-unavailable']);
    }
    const h=harness();h.emit(16,1);h.options.unavailable='short';h.emit(16,0);
    assert.deepEqual(h.launched,[]);assert.deepEqual(h.native,[]);assert.equal(h.reports.length,1);
});
test('reentrant release cannot cause a second dispatch',()=>{
    const h=harness({reenter:true});h.emit(16,1);h.emit(16,0);assert.deepEqual(h.launched,['short']);
});
test('lost release, stale timers and a backwards clock cancel without replay',()=>{
    for(const mutate of [h=>h.advance(15001,false),h=>h.setTime(1)]) {
        const h=harness();h.emit(16,1);mutate(h);h.emit(16,1);h.emit(16,0);
        assert.deepEqual(h.launched,[]);assert.deepEqual(h.native,[]);assert.equal(h.timers.size,0);
        h.advance(600);h.emit(16,1);h.emit(16,0);assert.deepEqual(h.launched,['short']);
    }
});
test('unexpected terminal events cancel an edge gesture and cannot add native actions',()=>{
    const h=harness();h.emit(16,1);h.emit(16,3);h.advance(800);h.emit(16,0);h.emit(16,4);
    assert.deepEqual(h.launched,[]);assert.deepEqual(h.native,[]);
});
test('bounce within the configured debounce interval is swallowed as a whole press',()=>{
    const h=harness();h.emit(16,1);h.emit(16,0);h.advance(50);h.emit(16,1);h.emit(16,0);
    assert.deepEqual(h.launched,['short']);assert.deepEqual(h.native,[]);
    h.advance(101);h.emit(16,1);h.emit(16,0);assert.deepEqual(h.launched,['short','short']);
});

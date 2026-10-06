const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

function popup(response) {
  const observed = {closed:0, notices:[], messages:[], events:[], disabled:[]};
  const context = {URL, crypto:require('node:crypto').webcrypto, Event:class {constructor(type){this.type=type;}},
    document:{addEventListener(){}, getElementById:()=>({classList:{add(){}}})},
    window:{close:()=>observed.closed++, dispatchEvent:event=>observed.events.push(event.type)},
    chrome:{runtime:{sendMessage:async message=>{
      observed.messages.push(message);
      if(response instanceof Error) throw response;
      return response;
    }}}
  };
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname,'../chromium/popup.js'),'utf8'), context);
  vm.runInContext("pageInfo={url:'https://www.youtube.com/watch?v=abcdefghijk',title:'Example'}",context);
  context.setButtonsDisabled=value=>observed.disabled.push(value);
  context.showNotice=(message,error)=>observed.notices.push({message,error});
  return {context,observed};
}

test('Video and Audio release the browser popup only after delivery to the running app',async()=>{
  for(const action of ['video','audio']) {
    const {context,observed}=popup({ok:true,status:'delivered'});
    await context.sendCapture({kind:'page',title:'Example'},action);
    assert.equal(observed.closed,1);
    assert.equal(observed.messages[0].capture.action,action);
    assert.deepEqual(observed.disabled,[true,false]);
    assert.deepEqual(observed.notices,[]);
  }
});

test('waiting confirmations, queued requests and errors keep the popup available',async()=>{
  for(const response of [{ok:true,status:'awaiting_confirmation'}, {ok:true,status:'queued'},
    {ok:false,status:'rejected',message:'Bridge unavailable'}, {ok:false,status:'delivered',message:'Not delivered'},
    new Error('Disconnected')]) {
    const {context,observed}=popup(response);
    await context.sendCapture({kind:'page'},'video');
    assert.equal(observed.closed,0);
    if(response.ok) assert.deepEqual(observed.events,['grabx-queue-updated']);
    else assert.equal(observed.notices[0].error,true);
  }
});

const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
test('popup exposes recent bridge rejection independently of page scanning', async () => {
 let result={ok:false,message:'Download link/session rejected (HTTP 498)',at:Date.now()};
 const notices=[];
 const context={document:{addEventListener(){}},chrome:{storage:{local:{get:async()=>({lastContextMenuResult:result})}}}};
 vm.createContext(context);
 vm.runInContext(fs.readFileSync(path.join(__dirname,'../chromium/popup.js'),'utf8'),context);
 context.showNotice=(...args)=>notices.push(args);
 await context.showLastBridgeResult();
 assert.deepEqual(notices,[[result.message,true]]);
 result={...result,at:Date.now()-600000}; await context.showLastBridgeResult();
 result={...result,ok:true,at:Date.now()}; await context.showLastBridgeResult();
 assert.equal(notices.length,1);
});
test('extension supplies real icon assets at every declared size', () => {
 const root=path.join(__dirname,'../chromium');
 const manifest=JSON.parse(fs.readFileSync(path.join(root,'manifest.json'),'utf8'));
 for(const size of [16,32,48,128]) {
  const bytes=fs.readFileSync(path.join(root,manifest.icons[size]));
  assert.equal(bytes.toString('ascii',1,4),'PNG');
  assert.equal(bytes.readUInt32BE(16),size); assert.equal(bytes.readUInt32BE(20),size);
  assert.equal(manifest.action.default_icon[size],manifest.icons[size]);
 }
});

test('page shortcut visibility is independent of download interception and persists', async () => {
 let listener; const saved=[];const toggle={checked:true,addEventListener:(_type,fn)=>{listener=fn;}};
 const context={document:{addEventListener(){},getElementById:()=>toggle},chrome:{storage:{local:{
  get:async()=>({showPageButtons:false,interceptBrowserDownloads:true}),set:async value=>saved.push(value)
 }}}};
 vm.createContext(context);
 vm.runInContext(fs.readFileSync(path.join(__dirname,'../chromium/popup.js'),'utf8'),context);
 await context.initializePageButtonsToggle();assert.equal(toggle.checked,false);
 toggle.checked=true;await listener();
 assert.equal(saved[0].showPageButtons,true);assert.equal(Object.keys(saved[0]).length,1);
});

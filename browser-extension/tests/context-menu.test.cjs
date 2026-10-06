const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const context = { URL, TextEncoder, crypto: require('node:crypto').webcrypto, chrome: {
 runtime: {onInstalled:{addListener(){}},onStartup:{addListener(){}},onMessage:{addListener(){}}},
 contextMenus:{onClicked:{addListener(){}}},downloads:{onCreated:{addListener(){}},onChanged:{addListener(){}}}
}};
vm.createContext(context);
vm.runInContext(fs.readFileSync(require('node:path').join(__dirname,'../chromium/service-worker.js'),'utf8'),context);
const capture = url => context.contextMenuCapture({linkUrl:url, pageUrl:'https://example.com/source'}, {});
test('GitHub repository and clone links become archives',()=>{
 for (const url of ['https://github.com/user/repo','https://github.com/user/repo.git?x=1']) {
  const c=capture(url); assert.equal(c.action,'file'); assert.equal(c.mediaUrl,'https://github.com/user/repo/archive/HEAD.zip');
 }
});
test('GitHub blob becomes raw while release assets remain direct',()=>{
 assert.equal(capture('https://github.com/user/repo/blob/main/test.pdf').mediaUrl,'https://github.com/user/repo/raw/main/test.pdf');
 const url='https://github.com/user/repo/releases/download/v1/app.dmg'; assert.equal(capture(url).mediaUrl,url);
});
test('ordinary pages are analyzed and signed file URLs keep tokens',()=>{
 assert.equal(capture('https://example.com/watch/123').action,'ask');
 const url='https://example.com/file.mp4?token=abc'; assert.equal(capture(url).mediaUrl,url);
 assert.equal(capture(url).pageUrl,'https://example.com/source');
});
test('closed app asks before enqueueing and ignores the removed preference',async()=>{
 const stored={}; let captures=0, windows=0;
 context.chrome.runtime.sendNativeMessage=(_host,message,callback)=>{
  if(message.type==='status') callback({ok:true,running:false});
  else {captures++;callback({ok:true,status:'queued'});}
 };
 context.chrome.runtime.getURL=x=>x;
 context.chrome.storage={local:{get:async key=>key===null?stored:{[key]:stored[key]},set:async value=>Object.assign(stored,value)}};
 context.chrome.windows={create:async()=>windows++};
 context.chrome.action={setBadgeText:async()=>{},setTitle:async()=>{}};
 let result=await context.handOffCapture(capture('https://example.com/file.zip'));
 assert.equal(result.status,'awaiting_confirmation'); assert.equal(captures,0); assert.equal(windows,0);
 stored.skipQueueConfirmation=true;
 result=await context.handOffCapture(capture('https://example.com/file.zip'));
 assert.equal(result.status,'awaiting_confirmation'); assert.equal(captures,0); assert.equal(windows,0);
});

test('confirmation uses toolbar popup and survives browsers refusing to open it',async()=>{
 let attempts=0;
 context.chrome.action.openPopup=async()=>{attempts++;throw new Error('No user gesture');};
 context.chrome.storage.local.get=async()=>({skipQueueConfirmation:false});
 const result=await context.handOffCapture(capture('https://example.com/file.zip'));
 assert.equal(attempts,1);
 assert.equal(result.status,'awaiting_confirmation');
 assert.match(result.message,/extension/);
});

vm.runInContext(fs.readFileSync(require('node:path').join(__dirname,'../chromium/queue-state.js'),'utf8'),context);
test('closed-app guidance requires both a current request and a closed app',()=>{
 assert.equal(context.queuePresentation({running:false,items:[]}).text,'');
 assert.match(context.queuePresentation({running:false,items:[{}]}).text,/GrabX is closed/);
 assert.equal(context.queuePresentation({running:true,items:[{}]}).text,'');
 assert.doesNotMatch(context.queuePresentation({running:null,items:[{confirmation:true}]}).text,/closed/);
 assert.doesNotMatch(context.queuePresentation({running:true,items:[{confirmation:true}]}).text,/closed/);
});
test('media appears above queue and removed preference is absent',()=>{
 const html=fs.readFileSync(require('node:path').join(__dirname,'../chromium/popup.html'),'utf8');
 assert.ok(html.indexOf('id="results"') < html.indexOf('id="manageQueue"'));
 assert.ok(!html.includes('dontAsk'));
});

test('opaque download links use HTTP metadata and preserve the referring page', async () => {
 context.inspectFile = async (url, referer) => ({ok:true,file:true,filename:'Movie.mp4',mimeType:'video/mp4'});
 const result = await context.resolveContextMenuCapture({linkUrl:'https://cdn.example.com/d/opaque?sig=abc',pageUrl:'https://example.com/watch'}, {});
 assert.equal(result.capture.action,'file');
 assert.equal(result.capture.mediaUrl,'https://cdn.example.com/d/opaque?sig=abc');
 assert.equal(result.capture.pageUrl,'https://example.com/watch');
 assert.equal(result.capture.suggestedFilename,'Movie.mp4');
});

test('HTML and rejected links explain the problem instead of opening the unsupported video dialog', async () => {
 let notice;
 context.reportDownloadIssue = async message => {notice=message;};
 for (const inspected of [{ok:true,file:false,message:'Open the site download button first'},
   {ok:false,message:'Download link/session rejected (HTTP 498)'}]) {
   context.inspectFile = async () => inspected;
   const result = await context.resolveContextMenuCapture({linkUrl:'https://example.com/download?id=1'}, {});
   assert.equal(result.capture,undefined); assert.equal(result.error.ok,false); assert.equal(notice,inspected.message);
 }
});

test('browser User-Agent is attached from the extension rather than page-provided data', () => {
 context.navigator = {userAgent:'FixtureBrowser/145'};
 const result = capture('https://example.com/file.mp4');
 assert.equal(result.userAgent,'FixtureBrowser/145');
 assert.equal(context.sanitizeCapture({...result,userAgent:'PageInjectedAgent'}).userAgent,'FixtureBrowser/145');
});

test('live handoff checks server acceptance before sending a file to GrabX', async () => {
 const messages=[];
 context.chrome.runtime.sendNativeMessage=(_host,message,callback)=>{
   messages.push(message.type);
   if(message.type==='status') callback({ok:true,running:true,fileInspection:true});
   else callback({ok:true,status:'accepted'});
 };
 context.inspectFile=async()=>({ok:false,file:false,message:'HTTP 498'});
 let result=await context.handOffCapture(capture('https://example.com/file.mp4'));
 assert.equal(result.ok,false); assert.deepEqual(messages,['status']);
 context.inspectFile=async()=>({ok:true,file:true}); messages.length=0;
 context.chrome.storage.local.get=async()=>({recentCaptures:[]});
 result=await context.handOffCapture(capture('https://example.com/file.mp4'));
 assert.equal(result.ok,true); assert.deepEqual(messages,['status','capture']);
 context.inspectFile=async()=>({ok:true,file:false,mimeType:''}); messages.length=0;
 result=await context.handOffCapture(capture('https://example.com/file.mp4'));
 assert.equal(result.ok,true); assert.deepEqual(messages,['status','capture']);
});

test('a bridge without file inspection cannot silently take over browser downloads', async () => {
 context.chrome.runtime.sendNativeMessage=(_host,message,callback)=>callback({ok:true,running:true});
 const result=await context.handOffCapture(capture('https://example.com/file.mp4'));
 assert.equal(result.ok,false); assert.match(result.message,/Update.*native bridge/);
});

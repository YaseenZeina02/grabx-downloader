const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

function fixture(overrides = {}) {
  const events = [], captures = [], session = {}, local = {};
  const item = { id: 42, state: 'in_progress', paused: false, filename: '',
    url: 'https://example.com/start', finalUrl: 'https://cdn.example.com/movie.mp4?token=a%2Fb',
    referrer: 'https://example.com/watch', mime: 'video/mp4', ...overrides };
  const storage = data => ({
    get: async key => key === null ? { ...data } : { [key]: data[key] },
    set: async values => Object.assign(data, values),
    remove: async key => { delete data[key]; }
  });
  const listeners = [], determining = [];
  const event = { addListener() {} };
  const context = { URL, TextEncoder, crypto: require('node:crypto').webcrypto, chrome: {
    runtime: { onInstalled: event, onStartup: event, onMessage: { addListener: fn => listeners.push(fn) } },
    action: {setBadgeText:async()=>{},setBadgeBackgroundColor:async()=>{},setTitle:async()=>{},openPopup:async()=>{}},
    contextMenus: { onClicked: event }, storage: { session: storage(session), local: storage(local) },
    downloads: { onCreated: event, onChanged: event, onDeterminingFilename: {addListener:fn=>determining.push(fn)},
      search: async () => [{ ...item }],
      pause: async () => { events.push('pause'); item.paused = true; },
      resume: async () => { events.push('resume'); item.paused = false; },
      cancel: async () => { events.push('cancel'); item.state = 'interrupted'; },
      erase: async () => { events.push('erase'); }
    }
  }};
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../chromium/service-worker.js'), 'utf8'), context);
  const originalHandOff = context.handOffCapture;
  context.handOffCapture = async capture => { events.push('handoff'); captures.push(capture); return { ok: true }; };
  return { context, item, events, captures, local, session, listeners, determining, originalHandOff };
}

test('waits for Save As, then sends the selected folder and redirected URL exactly once', async () => {
  const f = fixture();
  await f.context.watchBrowserDownload(f.item);
  assert.deepEqual(f.events, []);
  f.item.filename = 'C:\\Users\\Test\\Movies\\My choice.mp4';
  await Promise.all([
    f.context.browserDownloadChanged({ id: 42, filename: { current: f.item.filename } }),
    f.context.browserDownloadChanged({ id: 42, filename: { current: f.item.filename } })
  ]);
  assert.deepEqual(f.events, ['pause', 'handoff', 'cancel', 'erase']);
  assert.equal(f.captures[0].suggestedFolder, 'C:\\Users\\Test\\Movies');
  assert.equal(f.captures[0].suggestedFilename, 'My choice.mp4');
  assert.equal(f.captures[0].mediaUrl, f.item.finalUrl);
  assert.equal(f.captures[0].pageUrl, f.item.referrer);
  assert.equal(f.captures[0].browserDestinationResolved, true);
});

test('cancelling the browser picker never queues a download or opens GrabX', async () => {
  const f = fixture();
  await f.context.watchBrowserDownload(f.item);
  f.item.state = 'interrupted';
  await f.context.browserDownloadChanged({ id: 42, state: { current: 'interrupted' } });
  f.item.filename = '/tmp/movie.mp4';
  await f.context.browserDownloadChanged({ id: 42, filename: { current: f.item.filename } });
  assert.deepEqual(f.events, []);
  assert.deepEqual(f.session, {});
});

test('uses an already resolved browser destination without changing its filename', async () => {
  const f = fixture({ filename: '/Users/test/Movies/download.mp4' });
  await f.context.watchBrowserDownload(f.item);
  assert.equal(f.captures[0].suggestedFilename, 'download.mp4');
  assert.equal(f.captures[0].suggestedFolder, '/Users/test/Movies');
});

test('failed bridge resumes only a transfer paused by GrabX and never retries on changes', async () => {
  const f = fixture({ filename: '/tmp/movie.mp4' });
  f.context.handOffCapture = async () => ({ ok: false });
  await f.context.watchBrowserDownload(f.item);
  await f.context.browserDownloadChanged({ id: 42, filename: { current: f.item.filename } });
  assert.deepEqual(f.events, ['pause', 'resume']);
  for (const overrides of [{ paused: true }, { state: 'complete' }]) {
    const untouched = fixture({ filename: '/tmp/movie.mp4', ...overrides });
    await untouched.context.watchBrowserDownload(untouched.item);
    assert.deepEqual(untouched.events, []);
  }
});

test('a user cancellation during handoff is not resumed on bridge failure', async () => {
  const f = fixture({ filename: '/tmp/movie.mp4' });
  f.context.handOffCapture = async () => { f.item.state = 'interrupted'; throw new Error('Bridge gone'); };
  await f.context.watchBrowserDownload(f.item);
  assert.deepEqual(f.events, ['pause']);
});

test('turning interception off while the picker is open leaves the browser in control', async () => {
  const f = fixture();
  await f.context.watchBrowserDownload(f.item);
  f.local.interceptBrowserDownloads = false;
  f.item.filename = '/tmp/movie.mp4';
  await f.context.browserDownloadChanged({ id: 42, filename: { current: f.item.filename } });
  assert.deepEqual(f.events, []);
});

test('root folders and UNC shares retain an absolute parent directory', () => {
  const { context } = fixture();
  for (const [filename, folder] of [['/movie.mp4', '/'], ['D:\\movie.mp4', 'D:\\'],
    ['\\\\server\\share\\movie.mp4', '\\\\server\\share']]) {
    assert.equal(context.hasBrowserDestination(filename), true);
    assert.equal(context.parentFolder(filename), folder);
  }
  for (const filename of ['', 'movie.mp4', 'D:movie.mp4', '/tmp/']) {
    assert.equal(context.hasBrowserDestination(filename), false);
  }
});

test('popup captures cannot bypass the app chooser by claiming a browser destination', async () => {
  const f = fixture();
  const capture = f.context.fileCapture({ url: f.item.finalUrl, pageUrl: f.item.referrer,
    suggestedFolder: '/tmp', browserDestinationResolved: true });
  await new Promise(resolve => f.listeners[0]({ type: 'GRABX_CAPTURE', capture }, {}, resolve));
  assert.equal(f.captures[0].browserDestinationResolved, false);
});

test('filename-ready event after a service worker restart retains the pending download', async () => {
  const f = fixture();
  await f.context.watchBrowserDownload(f.item);
  const restarted = fixture({ filename: '/tmp/movie.mp4' });
  Object.assign(restarted.session, f.session);
  await restarted.context.browserDownloadChanged({ id: 42, filename: { current: restarted.item.filename } });
  assert.equal(restarted.captures.length, 1);
});

test('filename change racing the initial search is rechecked instead of lost', async () => {
  const f = fixture();
  let finishSearch, searchStarted;
  const started = new Promise(resolve => { searchStarted = resolve; });
  const search = f.context.chrome.downloads.search;
  f.context.chrome.downloads.search = async () => {
    f.context.chrome.downloads.search = search;
    const snapshot = { ...f.item };
    searchStarted();
    await new Promise(resolve => { finishSearch = resolve; });
    return [snapshot];
  };
  const watching = f.context.watchBrowserDownload(f.item);
  await started;
  f.item.filename = '/tmp/movie.mp4';
  const changed = f.context.browserDownloadChanged({ id: 42, filename: { current: f.item.filename } });
  finishSearch();
  await Promise.all([watching, changed]);
  assert.equal(f.captures.length, 1);
});


test('confirmed browser response is delivered without requesting a second preflight', async () => {
 const f=fixture({filename:'/tmp/movie.mp4'});
 const calls=[];
 f.context.handOffCapture=f.originalHandOff;
 f.context.nativeControl=async request=>{calls.push(request.type);return{ok:true,running:true,fileInspection:false};};
 f.context.sendCaptureNative=async()=>{calls.push('deliver');return{ok:true,status:'delivered'};};
 await f.context.watchBrowserDownload(f.item);
 assert.deepEqual(calls,['status','deliver']);
 assert.deepEqual(f.events,['pause','cancel','erase']);
});

test('an in-progress transition rechecks a filename without needing another filename event', async () => {
 const f=fixture(); await f.context.watchBrowserDownload(f.item);
 f.item.filename='/tmp/movie.mp4';
 await f.context.browserDownloadChanged({id:42,state:{current:'in_progress'}});
 assert.equal(f.captures.length,1);
});

test('page detection badges are bounded, tab scoped and reject foreign/frame messages', async () => {
 const f=fixture(); const badges=[];
 f.context.chrome.runtime.id='grabx-test';
 f.context.chrome.action.setBadgeText=async value=>badges.push(value);
 const send=async(count,sender)=>{
  let response;
  for(const listener of f.listeners){
   const pending=new Promise(resolve=>{const keep=listener({type:'GRABX_DETECTED',count},sender,value=>resolve(value));if(!keep)resolve(null);});
   response=await pending;if(response)break;
  }
  return response;
 };
 await send(3,{id:'foreign',tab:{id:7},frameId:0});
 await send(3,{id:'grabx-test',tab:{id:7},frameId:2});
 assert.equal(badges.length,0);
 assert.equal((await send(200,{id:'grabx-test',tab:{id:7},frameId:0})).ok,true);
 assert.equal(badges[0].tabId,7);assert.equal(badges[0].text,'99');
 await send(0,{id:'grabx-test',tab:{id:7},frameId:0});assert.equal(badges[1].text,'');
});

test('the video route delivers even when file-only preflight fails, without weakening file validation', async () => {
 const f=fixture();let inspections=0,deliveries=0;
 f.context.nativeControl=async()=>({ok:true,running:true,fileInspection:true});
 f.context.inspectFile=async()=>{inspections++;return{ok:false,message:'Could not verify this download link'};};
 f.context.sendCaptureNative=async()=>{deliveries++;return{ok:true,status:'delivered'};};
 const capture=f.context.fileCapture({url:'https://cdn.example.com/movie.mp4?token=a%2Fb',pageUrl:'https://example.com/watch',title:'Movie'});
 const fileResult=await f.originalHandOff(capture);
 assert.equal(fileResult.ok,false);assert.equal(inspections,1);assert.equal(deliveries,0);
 const videoResult=await f.originalHandOff({...capture,action:'video',mediaKind:'video'});
 assert.equal(videoResult.ok,true);assert.equal(inspections,1);assert.equal(deliveries,1);
 assert.equal(f.local.lastContextMenuResult.ok,true);
});


async function determineFilename(f) {
  let calls = 0;
  await new Promise(resolve => {
    assert.equal(f.determining[0]({...f.item}, () => { calls++; resolve(); }), true);
  });
  assert.equal(calls, 1, 'Browser filename callback must always be released exactly once');
}

test('script-started redirected download reaches GrabX before filename becomes final', async () => {
  const f = fixture({ filename: 'movie.mp4' });
  await f.context.watchBrowserDownload(f.item);
  await determineFilename(f);
  assert.deepEqual(f.events, ['pause', 'handoff', 'cancel', 'erase']);
  assert.equal(f.captures[0].mediaUrl, f.item.finalUrl);
  assert.equal(f.captures[0].pageUrl, f.item.referrer);
  assert.equal(f.captures[0].browserDestinationResolved, false);
  assert.equal(f.captures[0].suggestedFolder, '');
  assert.equal(f.captures[0].suggestedFilename, 'movie.mp4');
});

test('filename and creation events racing delivery still hand off exactly once', async () => {
  const f = fixture({ filename: '/tmp/movie.mp4' });
  await Promise.all([determineFilename(f), f.context.watchBrowserDownload(f.item),
    f.context.browserDownloadChanged({id:42,filename:{current:f.item.filename}})]);
  assert.equal(f.captures.length, 1);
  assert.deepEqual(f.events, ['pause', 'handoff', 'cancel', 'erase']);
});

test('closed app keeps automatic downloads in browser without creating a confirmation queue', async () => {
  const f = fixture(); f.context.handOffCapture = f.originalHandOff;
  f.context.nativeControl = async () => ({ok:true,running:false});
  await determineFilename(f);
  assert.deepEqual(f.events, ['pause', 'resume']);
  assert.deepEqual(f.local, {});
});

test('app closing during delivery leaves browser download intact without queueing', async () => {
  const f = fixture(); f.context.handOffCapture = f.originalHandOff;
  f.context.nativeControl = async () => ({ok:true,running:true});
  f.context.sendCaptureNative = async () => ({ok:false,status:'confirmation_required'});
  await determineFilename(f);
  assert.deepEqual(f.events, ['pause', 'resume']);
  assert.deepEqual(f.local, {});
});

test('disabled interception and non-HTTP downloads release filename determination untouched', async () => {
  for (const options of [{disabled:true}, {url:'blob:https://example.com/id',finalUrl:''}]) {
    const f = fixture(options);
    if (options.disabled) f.local.interceptBrowserDownloads = false;
    await determineFilename(f);
    assert.deepEqual(f.events, []);
  }
});

test('native failure resumes early paused download and releases the browser filename callback', async () => {
  const f = fixture();
  f.context.handOffCapture = async () => ({ok:false});
  await determineFilename(f);
  assert.deepEqual(f.events, ['pause', 'resume']);
});

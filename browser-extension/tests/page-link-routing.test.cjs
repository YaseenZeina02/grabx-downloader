const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../chromium/page-assistant.js'),'utf8');
const context={URL};vm.createContext(context);
vm.runInContext(source.slice(0,source.indexOf('(() => {')),context);

test('GX routes movie anchors through the same video action as the popup, including signed links',()=>{
 for(const suffix of ['mp4','MP4','webm','mov','mkv']) {
  assert.equal(context.grabxPageLinkKind(`https://cdn.example.com/[movie].${suffix}?token=a%2Fb#download`),'video');
 }
});
test('audio uses audio routing while documents and opaque download links remain files',()=>{
 for(const suffix of ['mp3','m4a','wav','flac']) assert.equal(context.grabxPageLinkKind(`https://cdn.example.com/media.${suffix}`),'audio');
 for(const suffix of ['pdf','zip','7z','exe']) assert.equal(context.grabxPageLinkKind(`https://cdn.example.com/file.${suffix}`),'file');
 assert.equal(context.grabxPageLinkKind('https://cdn.example.com/download?id=movie.mp4'),'file');
});

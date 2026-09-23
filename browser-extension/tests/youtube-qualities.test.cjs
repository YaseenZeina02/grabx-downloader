const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

function read(player, url = 'https://www.youtube.com/watch?v=DodLg1SxmWI') {
  const context = {URL, location: new URL(url), document: {addEventListener(){}, getElementById(){return player;}}};
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../chromium/popup.js'), 'utf8'), context);
  return JSON.parse(JSON.stringify(context.readYouTubeQualities()));
}
test('reports the current player quality levels rather than portrait pixel height', () => {
  assert.deepEqual(read({getVideoData:()=>({video_id:'DodLg1SxmWI'}),
    getAvailableQualityLevels:()=>['hd1080','hd720','large','medium','small','tiny','auto']}),
    {videoId:'DodLg1SxmWI', qualities:[1080,720,480,360,240,144], sizes:[]});
});
test('uses quality labels for shorts, ignores stale metadata and ambiguous highres', () => {
  const player = {getVideoData:()=>({video_id:'DodLg1SxmWI'}), getAvailableQualityLevels:()=>['highres'],
    getPlayerResponse:()=>({videoDetails:{videoId:'DodLg1SxmWI'}, streamingData:{adaptiveFormats:[
      {qualityLabel:'1080p60', width:1080, height:1920}, {qualityLabel:'480p'}, {qualityLabel:'9999p'}]}})};
  assert.deepEqual(read(player, 'https://youtube.com/shorts/DodLg1SxmWI').qualities, [1080,480]);
  player.getPlayerResponse = ()=>({videoDetails:{videoId:'different'}, streamingData:{formats:[{qualityLabel:'2160p'}]}});
  assert.deepEqual(read(player).qualities, []);
});
test('falls back to app probing on navigation mismatch, missing player or unrelated site', () => {
  assert.equal(read(null), null);
  assert.equal(read({getVideoData:()=>({video_id:'old-video'})}), null);
  assert.equal(read({}, 'https://youtube.com.evil.example/watch?v=DodLg1SxmWI'), null);
});

function withFormats(formats, details = {}) {
  return {getVideoData:()=>({video_id:'DodLg1SxmWI'}),
    getPlayerResponse:()=>({videoDetails:{videoId:'DodLg1SxmWI', lengthSeconds:'10', ...details},
      streamingData:{adaptiveFormats:formats}})};
}
test('size combines preferred native video and audio without counting other codecs or dubs', () => {
  const formats = [
    {qualityLabel:'480p', mimeType:'video/mp4; codecs="avc1"', contentLength:'1000000'},
    {qualityLabel:'480p', mimeType:'video/mp4; codecs="av01"', contentLength:'800000'},
    {mimeType:'audio/mp4; codecs="mp4a"', contentLength:'100000', audioTrack:{audioIsDefault:true}},
    {mimeType:'audio/webm; codecs="opus"', contentLength:'150000', audioTrack:{audioIsDefault:true}},
    {mimeType:'audio/mp4; codecs="mp4a"', contentLength:'300000', audioTrack:{audioIsDefault:false}}
  ];
  assert.deepEqual(read(withFormats(formats)).sizes, [{quality:480, bytes:1100000}]);
});
test('missing lengths use bitrate and duration; progressive files include audio only once', () => {
  assert.deepEqual(read(withFormats([
    {qualityLabel:'720p', mimeType:'video/mp4; codecs="avc1"', bitrate:800000},
    {mimeType:'audio/mp4; codecs="mp4a"', averageBitrate:80000}
  ])).sizes, [{quality:720, bytes:1100000}]);
  assert.deepEqual(read(withFormats([
    {qualityLabel:'360p', mimeType:'video/mp4; codecs="avc1, mp4a"', contentLength:'900000'},
    {mimeType:'audio/mp4; codecs="mp4a"', contentLength:'100000'}
  ])).sizes, [{quality:360, bytes:900000}]);
});
test('unknown audio, unknown duration, live and invalid lengths do not fabricate a total', () => {
  const video = {qualityLabel:'1080p', mimeType:'video/mp4; codecs="avc1"', contentLength:'1000000'};
  assert.deepEqual(read(withFormats([video])).sizes, []);
  assert.deepEqual(read(withFormats([video, {mimeType:'audio/mp4; codecs="mp4a"'}])).sizes, []);
  const muxed = {...video, mimeType:'video/mp4; codecs="avc1, mp4a"'};
  assert.deepEqual(read(withFormats([muxed], {isLive:true})).sizes, []);
  assert.deepEqual(read(withFormats([{...muxed, contentLength:'1e20'}])).sizes, []);
  assert.deepEqual(read(withFormats([{...muxed, contentLength:undefined, bitrate:800000}], {lengthSeconds:undefined})).sizes, []);
});

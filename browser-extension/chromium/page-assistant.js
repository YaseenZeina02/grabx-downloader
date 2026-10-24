// Match the popup's video/audio routing for direct media links.
function grabxPageLinkKind(address) {
  try {
    const path = new URL(address).pathname;
    if (/\.(mp4|webm|mov|mkv)$/i.test(path)) return 'video';
    if (/\.(mp3|m4a|wav|flac)$/i.test(path)) return 'audio';
  } catch { /* Unknown links remain ordinary files. */ }
  return 'file';
}

(() => {
  'use strict';
  if (globalThis.__grabxPageAssistant || !/^https?:$/.test(location.protocol)) return;
  globalThis.__grabxPageAssistant = true;
  const FILE = /\.(zip|7z|rar|tar|gz|bz2|xz|exe|msi|dmg|pkg|deb|rpm|iso|pdf|epub|mp4|mkv|webm|mov|mp3|m4a|flac|wav|csv|docx?|xlsx?|pptx?)$/i;
  const http = value => { try { const u = new URL(value, location.href); return /^https?:$/.test(u.protocol) ? u.href : null; } catch { return null; } };
  const host = document.createElement('div');
  host.setAttribute('data-grabx-assistant', '');
  host.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:2147483647;';
  const root = host.attachShadow({mode:'open'});
  root.innerHTML = `<style>
    :host{all:initial}*{box-sizing:border-box}button{font:600 12px/1.3 system-ui,sans-serif;color:#e8effa;background:linear-gradient(135deg,#152d47,#0c1a2c);border:1px solid #53769b;border-radius:11px;padding:8px 11px;display:flex;align-items:center;gap:8px;cursor:pointer;pointer-events:auto;box-shadow:0 3px 14px #0005;transition:background .15s,transform .15s}
    button:hover{background:#254e75;transform:translateY(-1px)}button:focus-visible{outline:2px solid #90bfff;outline-offset:3px}button:disabled{cursor:wait;opacity:.8}.item{position:fixed;max-width:240px}.item[hidden]{display:none}.brand{font-weight:800;letter-spacing:-1px;color:#b9d9ff}.dot{width:6px;height:6px;border-radius:50%;background:#6fe3b7;flex:none}.file{padding:5px 8px;font-size:11px;border-radius:8px}.toast{position:fixed;right:20px;bottom:22px;max-width:360px;padding:13px 17px;background:#10263e;border:1px solid #426b92;border-radius:13px;color:#ecf3ff;font:13px/1.5 system-ui;box-shadow:0 8px 32px #0005;pointer-events:auto}.toast[hidden]{display:none}@media(prefers-reduced-motion:reduce){button{transition:none}}
  </style>`;
  const toast = document.createElement('div'); toast.className='toast'; toast.hidden=true;
  toast.setAttribute('role','status'); toast.setAttribute('aria-live','polite'); root.append(toast);
  let candidates=[], items=[], scheduled, toastTimer, lastPage=location.href, lastCount=-1;
  let intercept=true, showButtons=true; const busy=new WeakSet(), bypass=new WeakSet(), pending=new Set();
  chrome.storage.local.get(['interceptBrowserDownloads','showPageButtons']).then(s => {intercept=s.interceptBrowserDownloads!==false;showButtons=s.showPageButtons!==false;position();}).catch(()=>{});
  chrome.storage.onChanged.addListener((changes,area)=>{if(area!=='local')return;if(changes.interceptBrowserDownloads)intercept=changes.interceptBrowserDownloads.newValue!==false;if(changes.showPageButtons){showButtons=changes.showPageButtons.newValue!==false;position();}});
  function mount(){const parent=document.fullscreenElement||document.documentElement;if(host.parentNode!==parent)parent.append(host);}
  function notify(text){toast.textContent=text;toast.hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>toast.hidden=true,6000);}
  function capture(item){return {protocolVersion:1,type:'capture',requestId:crypto.randomUUID(),pageUrl:location.href,mediaUrl:item.url,title:item.title||document.title,mediaKind:item.kind,action:item.kind==='file'?'file':item.kind==='audio'?'audio':'video',createdAt:Date.now()};}
  async function send(item,button){
    const key=item.kind+':'+item.url;
    if(pending.has(key))return true;
    pending.add(key);
    if(button)button.disabled=true;
    try{
      const result=await chrome.runtime.sendMessage({type:'GRABX_CAPTURE',capture:capture(item)});
      if(!result?.ok){notify(result?.message||'Could not reach GrabX. You can still download in your browser.');return false;}
      notify(result.status==='awaiting_confirmation'?'GrabX is closed · open the extension to confirm.':result.status==='queued'?'Added to the GrabX waiting list.':'Sent to GrabX · choose your download options in the app.');return true;
    }catch{notify('GrabX extension was updated. Refresh this page to reconnect.');return false;}
    finally{pending.delete(key);if(button)button.disabled=false;}
  }
  function fileItem(link){const url=http(link.href);if(!url)return null;const path=new URL(url).pathname;if(!link.hasAttribute('download')&&!FILE.test(path))return null;return{element:link,url,inline:true,kind:grabxPageLinkKind(url),title:link.getAttribute('download')||link.textContent.trim().slice(0,160)||path.split('/').pop()};}
  function scan(){
    mount();lastPage=location.href;
    const found=[];const seen=new Set();
    for(const media of [...document.querySelectorAll('video,audio')].slice(0,50)){
      const raw=media.currentSrc||media.src||media.querySelector('source')?.src;
      const source=raw?http(raw):null;
      const youtube=/(^|\.)youtube\.com$/.test(location.hostname)&&(/\/watch/.test(location.pathname)||location.pathname.startsWith('/shorts/'));
      const url=youtube?location.href:(source||location.href);
      if(seen.has(url))continue;seen.add(url);found.push({element:media,url,kind:media.tagName==='AUDIO'?'audio':'video',title:document.title});
    }
    for(const link of [...document.querySelectorAll('a[href]')].slice(0,1500)){
      const item=fileItem(link);if(!item||seen.has(item.url))continue;seen.add(item.url);found.push(item);if(found.length>=60)break;
    }
    candidates=found;
    for(const {button} of items)button.remove();items=[];
    for(const item of found.slice(0,30)){
      const button=document.createElement('button');button.type='button';button.className='item'+(item.inline?' file':'');
      button.innerHTML='<span class="dot"></span><span class="brand">GX</span><span></span>';
      button.lastElementChild.textContent=item.inline?'Download':'Download with GrabX';
      button.title='Send '+(item.title||item.kind)+' to GrabX';button.setAttribute('aria-label',button.title);
      button.disabled=pending.has(item.kind+':'+item.url);
      button.addEventListener('click',e=>{if(e.isTrusted)void send(item,button);});root.append(button);items.push({item,button});
    }
    if(found.length!==lastCount){lastCount=found.length;chrome.runtime.sendMessage({type:'GRABX_DETECTED',count:found.length}).catch(()=>{});}
    position();
  }
  function position(){for(const {item,button} of items){const r=item.element.getBoundingClientRect();const visible=r.width>=18&&r.height>=12&&r.bottom>0&&r.top<innerHeight&&r.right>0&&r.left<innerWidth&&getComputedStyle(item.element).visibility!=='hidden';button.hidden=!visible||!showButtons;if(!visible)continue;const width=item.inline?102:191;button.style.left=Math.max(4,Math.min(innerWidth-width-4,item.inline?r.right+6:r.right-width-10))+'px';button.style.top=Math.max(4,Math.min(innerHeight-42,r.top+(item.inline?0:10)))+'px';}}
  function schedule(){clearTimeout(scheduled);scheduled=setTimeout(scan,450);}
  document.addEventListener('click',e=>{
    if(!intercept||!e.isTrusted||e.defaultPrevented||e.button!==0||e.ctrlKey||e.metaKey||e.shiftKey||e.altKey)return;
    const link=e.target.closest?.('a[href]');if(!link||bypass.has(link)||busy.has(link))return;
    const item=fileItem(link);if(!item||link.target==='_blank')return;
    e.preventDefault();e.stopImmediatePropagation();busy.add(link);
    void send(item).then(ok=>{if(!ok){bypass.add(link);link.click();bypass.delete(link);}}).finally(()=>busy.delete(link));
  },true);
  new MutationObserver(records=>{if(records.some(r=>!host.contains(r.target)&&r.target!==host))schedule();}).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['href','src','download']});
  addEventListener('scroll',position,{passive:true,capture:true});addEventListener('resize',position);document.addEventListener('fullscreenchange',schedule);
  document.addEventListener('loadedmetadata',schedule,true);document.addEventListener('yt-navigate-finish',schedule);
  setInterval(()=>{if(lastPage!==location.href)schedule();},1500);
  scan();
})();

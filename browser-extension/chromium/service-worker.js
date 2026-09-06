const NATIVE_HOST = "com.grabx.browser_bridge";
const DOWNLOAD_MENU_ID = "grabx-download-link";
const INTERCEPTION_SETTING = "interceptBrowserDownloads";
const PAGE_CACHE_BYTES = 256 * 1024;
function trimPageContexts(entries, now = Date.now()) {
  const fresh = entries.filter(item => now - item.at < 1800000).slice(-20);
  while (fresh.length && new TextEncoder().encode(JSON.stringify(fresh)).length > PAGE_CACHE_BYTES) fresh.shift();
  return fresh;
}

chrome.runtime.onInstalled.addListener(async details => {
  await ensureContextMenu();
  if (details.reason === 'install') {
    await chrome.storage.local.set({ [INTERCEPTION_SETTING]: true });
  }
});

chrome.runtime.onStartup.addListener(ensureContextMenu);

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  if (info.menuItemId !== DOWNLOAD_MENU_ID) return;
  const linkUrl = safeHttpUrl(info.linkUrl);
  if (!linkUrl) return;

  const response = await handOffCapture(contextMenuCapture(info, tab));
  await chrome.storage.local.set({ lastContextMenuResult: {
    ok: Boolean(response?.ok),
    message: response?.message || (response?.ok ? 'Sent to GrabX' : 'Could not send this link to GrabX'),
    at: Date.now()
  } });
  await chrome.action.setBadgeText({ text: response?.status === 'queued' ? 'Q' : response?.status === 'awaiting_confirmation' ? '?' : response?.ok ? '' : '!' });
  if (!response?.ok) {
    await chrome.action.setBadgeBackgroundColor({ color: '#b3261e' });
    await chrome.action.setTitle({ title: 'GrabX could not receive the link. Open the extension for details.' });
  } else {
    await chrome.action.setTitle({ title: 'Download with GrabX' });
  }
});

chrome.downloads.onCreated.addListener(download => {
  void interceptDownload(download);
});

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message?.type === 'GRABX_PAGE_CONTEXT') {
    const url = safeHttpUrl(message.url);
    if (url && url.length <= 4096) chrome.storage.session.get('pageContexts').then(async stored => {
      const contexts = trimPageContexts(stored.pageContexts || []).filter(item => item.url !== url);
      const media = (Array.isArray(message.media) ? message.media : []).slice(0, 20).map(item => ({
        url: String(item.url || '').length <= 4096 ? safeHttpUrl(item.url) : null, title: String(item.title || '').slice(0, 255),
        sizeHint: String(item.sizeHint || '').slice(0, 80), mimeType: String(item.mimeType || '').slice(0, 160)
      })).filter(item => item.url);
      contexts.push({ url, title: String(message.title || '').slice(0, 255), media, at: Date.now() });
      await chrome.storage.session.set({ pageContexts: trimPageContexts(contexts) });
    }).catch(() => { /* Cache failure must not disrupt downloads. */ });
    return false;
  }
  if (!message || message.type !== "GRABX_CAPTURE") return false;
  const capture = sanitizeCapture(message.capture);
  if (!capture) {
    sendResponse({ ok: false, status: "rejected", message: "Invalid media request" });
    return false;
  }

  handOffCapture(capture).then(sendResponse);
  return true;
});

async function ensureContextMenu() {
  try { await chrome.contextMenus.remove(DOWNLOAD_MENU_ID); } catch { /* not created yet */ }
  chrome.contextMenus.create({
    id: DOWNLOAD_MENU_ID,
    title: "Download with GrabX",
    contexts: ["link"],
    targetUrlPatterns: ["http://*/*", "https://*/*"]
  });
}

async function interceptDownload(download) {
  const settings = await chrome.storage.local.get(INTERCEPTION_SETTING);
  if (settings[INTERCEPTION_SETTING] === false) return;

  const url = safeHttpUrl(download.finalUrl) || safeHttpUrl(download.url);
  if (!url) return;

  // Pause first so a failed bridge can safely fall back to the browser.
  try {
    await chrome.downloads.pause(download.id);
    const [current] = await chrome.downloads.search({ id: download.id });
    if (!current || current.state !== 'in_progress' || !current.paused) return;

    const stored = await chrome.storage.session.get('pageContexts');
    const context = (stored.pageContexts || []).find(item => item.url === safeHttpUrl(download.referrer) && Date.now() - item.at < 1800000);
    const exactMedia = context?.media?.find(item => item.url === url);
    const suggested = chooseDownloadName(fileName(current.filename), url, exactMedia?.title || context?.title, download.mime);
    const response = await handOffCapture(fileCapture({
      url,
      pageUrl: safeHttpUrl(download.referrer) || url,
      title: suggested,
      mimeType: download.mime || '',
      suggestedFilename: suggested,
      suggestedFolder: parentFolder(download.filename)
    }));

    if (response?.ok) {
      await ignoreDownloadError(chrome.downloads.cancel(download.id));
      await ignoreDownloadError(chrome.downloads.erase({ id: download.id }));
    } else {
      await ignoreDownloadError(chrome.downloads.resume(download.id));
    }
  } catch {
    await ignoreDownloadError(chrome.downloads.resume(download.id));
  }
}

function nativeControl(message) {
  return new Promise(resolve => chrome.runtime.sendNativeMessage(NATIVE_HOST, message, response => {
    const error = chrome.runtime.lastError;
    resolve(error ? {ok:false, message:error.message} : response || {ok:false, message:'No response from GrabX'});
  }));
}

async function showQueueInExtension() {
  await chrome.action.setBadgeText({text:'?'});
  await chrome.action.setTitle({title:'GrabX is closed — open the extension to confirm or cancel'});
  // Browser support and user-gesture rules vary. Never open a separate window.
  try { await chrome.action.openPopup(); } catch { /* The badge directs the user to the toolbar popup. */ }
}

async function handOffCapture(capture) {
  const state = await nativeControl({type:'status'});
  if (!state.ok) return {ok:false, message:state.message || 'Update the GrabX browser bridge to use download confirmation.'};
  if (!state.running) {
    await chrome.storage.local.set({['pending-' + capture.requestId]: capture});
    await showQueueInExtension();
    return {ok:true, status:'awaiting_confirmation', message:'GrabX is closed. Open the GrabX extension to confirm or cancel.'};
  }
  const result = await sendCaptureNative(capture, false);
  if (result?.status === 'confirmation_required') {
    await chrome.storage.local.set({['pending-' + capture.requestId]:capture});
    await showQueueInExtension();
    return {ok:true,status:'awaiting_confirmation',message:'GrabX closed before receiving the link. Open the GrabX extension to confirm it.'};
  }
  if (result?.status === 'queued') {
    await chrome.action.setBadgeText({text:'Q'});
    await chrome.action.setTitle({title:'Downloads queued — open GrabX or manage the waiting list'});
  }
  return result;
}

chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (sender.id !== chrome.runtime.id || !['GRABX_QUEUE_LIST','GRABX_QUEUE_ACCEPT','GRABX_QUEUE_CANCEL'].includes(message?.type)) return false;
  (async () => {
    const key = 'pending-' + String(message.requestId || '');
    if (message.type === 'GRABX_QUEUE_LIST') {
      const stored = await chrome.storage.local.get(null);
      const pending = Object.entries(stored).filter(([key]) => key.startsWith('pending-'))
        .map(([,item]) => ({requestId:item.requestId, title:item.title, confirmation:true}));
      const [queued, state] = await Promise.all([nativeControl({type:'listQueued'}), nativeControl({type:'status'})]);
      return {ok:queued.ok, message:queued.message, running:state.ok ? state.running : null, items:[...pending,...(queued.items || [])]};
    }
    const stored = await chrome.storage.local.get(key);
    if (message.type === 'GRABX_QUEUE_ACCEPT') {
      if (!stored[key]) return {ok:false,message:'This request is no longer waiting for confirmation'};
      const result = await sendCaptureNative(stored[key], true);
      if (result.ok) {
        await chrome.storage.local.remove(key);
      }
      return result;
    }
    if (stored[key]) { await chrome.storage.local.remove(key); return {ok:true,message:'Download cancelled'}; }
    return nativeControl({type:'cancelQueued', requestId:message.requestId});
  })().then(respond).catch(error => respond({ok:false,message:error.message}));
  return true;
});

function sendCaptureNative(capture, queueApproved = false) {
  return new Promise(resolve => {
    chrome.runtime.sendNativeMessage(NATIVE_HOST, {...capture, queueApproved}, async response => {
      if (chrome.runtime.lastError) {
        const nativeError = chrome.runtime.lastError.message || "Unknown native messaging error";
        resolve({
          ok: false,
          status: "unavailable",
          message: `GrabX bridge error: ${nativeError}`
        });
        return;
      }
      if (response?.ok) {
        try { await rememberCapture(capture); } catch { /* History is optional; always return the bridge result. */ }
      }
      resolve(response || { ok: false, message: "No response from GrabX" });
    });
  });
}

function contextMenuCapture(info, tab) {
  const link = safeHttpUrl(info.linkUrl);
  if (!link) return null;
  const parsed = new URL(link);
  const page = safeHttpUrl(info.frameUrl) || safeHttpUrl(info.pageUrl) || safeHttpUrl(tab?.url) || link;
  if (parsed.hostname === 'github.com') {
    const parts = parsed.pathname.split('/').filter(Boolean);
    if (parts.length === 2) {
      const repo = parts[1].replace(/\.git$/i, '');
      return fileCapture({ url: `https://github.com/${parts[0]}/${repo}/archive/HEAD.zip`,
        pageUrl: link, title: `${repo}.zip`, suggestedFilename: `${repo}.zip` });
    }
    if (parts.length >= 5 && parts[2] === 'blob') {
      parsed.pathname = parsed.pathname.replace('/blob/', '/raw/');
      parsed.search = '';
      return fileCapture({ url: parsed.href, pageUrl: link, title: fileNameFromUrl(link) });
    }
  }
  const direct = /\.(zip|7z|rar|tar|gz|bz2|xz|exe|msi|dmg|pkg|deb|rpm|iso|pdf|epub|mp4|mkv|webm|mov|mp3|m4a|flac|wav|jpg|jpeg|png|gif|csv|docx?|xlsx?|pptx?)$/i.test(parsed.pathname)
    || parsed.hostname === 'raw.githubusercontent.com'
    || /\/(releases\/download|archive)\//.test(parsed.pathname);
  if (direct) return fileCapture({ url: link, pageUrl: page, title: linkTitle(info, link) });
  return sanitizeCapture({ protocolVersion: 1, type: 'capture', requestId: crypto.randomUUID(),
    pageUrl: link, mediaUrl: null, title: linkTitle(info, link), mediaKind: 'page',
    action: 'ask', createdAt: Date.now() });
}

function fileCapture({ url, pageUrl, title, mimeType = '', suggestedFilename = '', suggestedFolder = '' }) {
  return sanitizeCapture({
    protocolVersion: 1,
    type: 'capture',
    requestId: crypto.randomUUID(),
    pageUrl,
    mediaUrl: url,
    title: title || fileNameFromUrl(url),
    mimeType,
    mediaKind: 'file',
    action: 'file',
    suggestedFilename,
    suggestedFolder,
    createdAt: Date.now()
  });
}

function safeHttpUrl(value) {
  try {
    const url = new URL(value);
    return ['http:', 'https:'].includes(url.protocol) ? url.href : null;
  } catch { return null; }
}

function linkTitle(info, url) {
  return String(info.selectionText || '').trim() || fileNameFromUrl(url);
}

function fileName(path) {
  return String(path || '').split(/[\\/]/).filter(Boolean).pop() || '';
}

function parentFolder(path) {
  const value = String(path || '');
  const separator = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
  return separator > 0 ? value.slice(0, separator) : '';
}

function fileNameFromUrl(value) {
  try {
    const parsed = new URL(value);
    const name = decodeURIComponent(parsed.pathname).split('/').filter(Boolean).pop();
    return name || parsed.hostname;
  } catch { return 'Browser download'; }
}

async function ignoreDownloadError(operation) {
  try { await operation; } catch { /* the browser may have already completed the item */ }
}

function sanitizeCapture(value) {
  if (!value || value.type !== "capture" || value.protocolVersion !== 1) return null;
  let pageUrl;
  try {
    pageUrl = new URL(value.pageUrl);
  } catch {
    return null;
  }
  if (!['http:', 'https:'].includes(pageUrl.protocol)) return null;

  let mediaUrl = null;
  if (value.mediaUrl) {
    try {
      const parsed = new URL(value.mediaUrl);
      if (['http:', 'https:'].includes(parsed.protocol)) mediaUrl = parsed.href;
    } catch { /* page URL remains the safe fallback */ }
  }
  const allowedKinds = new Set(['page', 'video', 'audio', 'file']);
  const allowedActions = new Set(['ask', 'video', 'audio', 'file']);
  return {
    protocolVersion: 1,
    type: 'capture',
    requestId: String(value.requestId || crypto.randomUUID()).slice(0, 100),
    pageUrl: pageUrl.href,
    mediaUrl,
    title: String(value.title || '').slice(0, 500),
    mimeType: String(value.mimeType || '').slice(0, 160),
    mediaKind: allowedKinds.has(value.mediaKind) ? value.mediaKind : 'page',
    action: allowedActions.has(value.action) ? value.action : 'ask',
    suggestedFilename: String(value.suggestedFilename || '').slice(0, 255),
    suggestedFolder: String(value.suggestedFolder || '').slice(0, 4096),
    createdAt: Number(value.createdAt) || Date.now()
  };
}

async function rememberCapture(capture) {
  const { recentCaptures = [] } = await chrome.storage.local.get('recentCaptures');
  const safeSummary = {
    title: capture.title,
    pageUrl: capture.pageUrl,
    mediaKind: capture.mediaKind,
    action: capture.action,
    createdAt: capture.createdAt
  };
  await chrome.storage.local.set({
    recentCaptures: [safeSummary, ...recentCaptures].slice(0, 20)
  });
}

function chooseDownloadName(browserName, url, pageTitle, mime) {
  const generic = /^(download|file|video)( \(\d+\))?(\.[a-z0-9]+)?$/i;
  for (const name of [browserName, fileNameFromUrl(url)]) {
    if (name && /\.[a-z0-9]{2,5}$/i.test(name) && !generic.test(name)) return name;
  }
  if (pageTitle && !/^(download|your download link|StreamHG)$/i.test(pageTitle.trim())) {
    const extension = browserName?.match(/\.[a-z0-9]{2,5}$/i)?.[0] || (mime === 'video/mp4' ? '.mp4' : '');
    const clean = pageTitle.trim().replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').slice(0, 230);
    return clean.toLowerCase().endsWith(extension.toLowerCase()) ? clean : clean + extension;
  }
  return browserName || fileNameFromUrl(url);
}

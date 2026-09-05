const NATIVE_HOST = "com.grabx.browser_bridge";
const DOWNLOAD_MENU_ID = "grabx-download-link";
const INTERCEPTION_SETTING = "interceptBrowserDownloads";

chrome.runtime.onInstalled.addListener(async details => {
  await ensureContextMenu();
  if (details.reason === 'install') {
    await chrome.storage.local.set({ [INTERCEPTION_SETTING]: true });
  }
});

chrome.runtime.onStartup.addListener(ensureContextMenu);

chrome.contextMenus.onClicked.addListener((info, tab) => {
  if (info.menuItemId !== DOWNLOAD_MENU_ID) return;
  const linkUrl = safeHttpUrl(info.linkUrl);
  if (!linkUrl) return;

  void handOffCapture(fileCapture({
    url: linkUrl,
    pageUrl: safeHttpUrl(tab?.url) || linkUrl,
    title: linkTitle(info, linkUrl)
  }));
});

chrome.downloads.onCreated.addListener(download => {
  void interceptDownload(download);
});

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
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

    const response = await handOffCapture(fileCapture({
      url,
      pageUrl: safeHttpUrl(download.referrer) || url,
      title: fileName(download.filename) || fileNameFromUrl(url),
      mimeType: download.mime || '',
      suggestedFilename: fileName(download.filename),
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

function handOffCapture(capture) {
  return new Promise(resolve => {
    chrome.runtime.sendNativeMessage(NATIVE_HOST, capture, async response => {
      if (chrome.runtime.lastError) {
        const nativeError = chrome.runtime.lastError.message || "Unknown native messaging error";
        resolve({
          ok: false,
          status: "unavailable",
          message: `GrabX bridge error: ${nativeError}`
        });
        return;
      }
      if (response?.ok) await rememberCapture(capture);
      resolve(response || { ok: false, message: "No response from GrabX" });
    });
  });
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

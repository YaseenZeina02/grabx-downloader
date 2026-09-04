const NATIVE_HOST = "com.grabx.browser_bridge";

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (!message || message.type !== "GRABX_CAPTURE") return false;
  const capture = sanitizeCapture(message.capture);
  if (!capture) {
    sendResponse({ ok: false, status: "rejected", message: "Invalid media request" });
    return false;
  }

  chrome.runtime.sendNativeMessage(NATIVE_HOST, capture, async (response) => {
    if (chrome.runtime.lastError) {
      const nativeError = chrome.runtime.lastError.message || "Unknown native messaging error";
      sendResponse({
        ok: false,
        status: "unavailable",
        message: `GrabX bridge error: ${nativeError}`
      });
      return;
    }
    if (response?.ok) await rememberCapture(capture);
    sendResponse(response || { ok: false, message: "No response from GrabX" });
  });
  return true;
});

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

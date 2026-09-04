let pageInfo = null;
let candidates = [];

document.addEventListener('DOMContentLoaded', scanActivePage);
document.getElementById('sendPage').addEventListener('click', () => {
  if (pageInfo) sendCapture({ kind: 'page', url: null, title: pageInfo.title }, 'ask');
});

async function scanActivePage() {
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab?.id || !/^https?:/.test(tab.url || '')) throw new Error('This page cannot be scanned');
    const [{ result }] = await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      func: detectPageMedia
    });
    pageInfo = result.page;
    candidates = result.candidates;
    render();
  } catch (error) {
    document.getElementById('loading').classList.add('hidden');
    showNotice(error.message || 'Could not scan this page', true);
  }
}

function detectPageMedia() {
  const absolute = value => {
    if (!value || value.startsWith('blob:') || value.startsWith('data:')) return null;
    try {
      const url = new URL(value, location.href);
      return ['http:', 'https:'].includes(url.protocol) ? url.href : null;
    } catch { return null; }
  };
  const items = [];
  const add = (url, kind, title, mimeType = '') => {
    const resolved = absolute(url);
    if (!resolved) return;
    items.push({ url: resolved, kind, title: title || document.title, mimeType });
  };

  document.querySelectorAll('video, audio').forEach(media => {
    const kind = media.tagName.toLowerCase();
    add(media.currentSrc || media.src, kind, media.getAttribute('title') || document.title,
      media.getAttribute('type') || '');
    media.querySelectorAll('source').forEach(source =>
      add(source.src, kind, source.getAttribute('title') || document.title, source.type || ''));
  });
  document.querySelectorAll('meta[property="og:video"], meta[property="og:video:url"], meta[property="og:audio"]')
    .forEach(meta => add(meta.content, meta.property.includes('audio') ? 'audio' : 'video', document.title));
  document.querySelectorAll('a[download], link[rel="enclosure"]').forEach(element => {
    const url = element.href;
    const label = element.getAttribute('download') || element.textContent?.trim() || document.title;
    add(url, 'file', label, element.type || '');
  });
  document.querySelectorAll('a[href]').forEach(anchor => {
    const href = anchor.getAttribute('href') || '';
    if (/\.(mp4|webm|mov|mkv|mp3|m4a|wav|flac|zip|rar|7z|pdf)(?:[?#]|$)/i.test(href)) {
      const extension = href.match(/\.([a-z0-9]+)(?:[?#]|$)/i)?.[1]?.toLowerCase();
      const kind = ['mp4', 'webm', 'mov', 'mkv'].includes(extension) ? 'video'
        : ['mp3', 'm4a', 'wav', 'flac'].includes(extension) ? 'audio' : 'file';
      add(href, kind, anchor.textContent?.trim() || href.split('/').pop());
    }
  });

  const unique = [...new Map(items.map(item => [item.url, item])).values()].slice(0, 50);
  return {
    page: { url: location.href, title: document.title || location.hostname },
    candidates: unique
  };
}

function render() {
  document.getElementById('loading').classList.add('hidden');
  document.getElementById('count').textContent = String(candidates.length);
  if (!candidates.length) {
    document.getElementById('empty').classList.remove('hidden');
    return;
  }
  const results = document.getElementById('results');
  results.classList.remove('hidden');
  candidates.forEach((candidate, index) => results.appendChild(createCard(candidate, index)));
}

function createCard(candidate, index) {
  const card = document.createElement('article');
  card.className = 'media-card';
  const top = document.createElement('div');
  top.className = 'media-top';
  const kind = document.createElement('span');
  kind.className = 'kind';
  kind.textContent = candidate.kind;
  const copy = document.createElement('span');
  copy.className = 'media-copy';
  const title = document.createElement('strong');
  title.className = 'media-title';
  title.textContent = candidate.title || `Media ${index + 1}`;
  title.title = title.textContent;
  const meta = document.createElement('small');
  meta.className = 'media-meta';
  meta.textContent = candidate.mimeType || safeHostname(candidate.url);
  copy.append(title, meta);
  top.append(kind, copy);

  const actions = document.createElement('div');
  actions.className = 'actions';
  if (candidate.kind === 'video' || candidate.kind === 'page') {
    actions.append(actionButton('Video', candidate, 'video', true));
    actions.append(actionButton('Audio', candidate, 'audio'));
  } else {
    actions.append(actionButton(candidate.kind === 'audio' ? 'Download audio' : 'Download file',
      candidate, candidate.kind, true));
  }
  card.append(top, actions);
  return card;
}

function actionButton(label, candidate, action, primary = false) {
  const button = document.createElement('button');
  button.textContent = label;
  if (primary) button.classList.add('primary');
  button.addEventListener('click', () => sendCapture(candidate, action));
  return button;
}

async function sendCapture(candidate, action) {
  setButtonsDisabled(true);
  const capture = {
    protocolVersion: 1,
    type: 'capture',
    requestId: crypto.randomUUID(),
    pageUrl: pageInfo.url,
    mediaUrl: candidate.url || null,
    title: candidate.title || pageInfo.title,
    mimeType: candidate.mimeType || '',
    mediaKind: candidate.kind || 'page',
    action,
    createdAt: Date.now()
  };
  try {
    const response = await chrome.runtime.sendMessage({ type: 'GRABX_CAPTURE', capture });
    showNotice(response?.message || 'Sent to GrabX', !response?.ok);
    if (response?.ok) setTimeout(() => window.close(), 850);
  } catch {
    showNotice('Could not connect to GrabX', true);
  } finally {
    setButtonsDisabled(false);
  }
}

function safeHostname(value) {
  try { return new URL(value).hostname; } catch { return 'Direct media'; }
}

function showNotice(message, error = false) {
  const notice = document.getElementById('notice');
  notice.textContent = message;
  notice.classList.remove('hidden', 'error');
  if (error) notice.classList.add('error');
}

function setButtonsDisabled(disabled) {
  document.querySelectorAll('button').forEach(button => button.disabled = disabled);
}

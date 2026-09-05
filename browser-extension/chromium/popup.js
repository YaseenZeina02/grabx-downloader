let pageInfo = null;
let candidates = [];
const INTERCEPTION_SETTING = 'interceptBrowserDownloads';

document.addEventListener('DOMContentLoaded', async () => {
  await initializeInterceptionToggle();
  await scanActivePage();
});

async function initializeInterceptionToggle() {
  const toggle = document.getElementById('interceptDownloads');
  const settings = await chrome.storage.local.get(INTERCEPTION_SETTING);
  toggle.checked = settings[INTERCEPTION_SETTING] !== false;
  toggle.addEventListener('change', () => {
    chrome.storage.local.set({ [INTERCEPTION_SETTING]: toggle.checked });
  });
}

async function scanActivePage() {
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab?.id || !/^https?:/.test(tab.url || '')) throw new Error('This page cannot be scanned');
    let result = null;
    try {
      const injectionResults = await chrome.scripting.executeScript({
        target: { tabId: tab.id },
        func: detectPageMedia
      });
      result = injectionResults?.[0]?.result || null;
    } catch {
      // Restricted or unusually scripted pages can still be analyzed by GrabX
      // using the trusted tab URL supplied by the browser extension API.
    }
    pageInfo = result?.page || {
      url: tab.url,
      title: tab.title || new URL(tab.url).hostname,
      thumbnailUrl: youtubeThumbnail(tab.url)
    };
    chrome.runtime.sendMessage({ type: 'GRABX_PAGE_CONTEXT', url: pageInfo.url, title: pageInfo.title, media: result?.candidates || [] });
    if (!pageInfo.thumbnailUrl) pageInfo.thumbnailUrl = youtubeThumbnail(pageInfo.url);
    candidates = Array.isArray(result?.candidates) ? result.candidates : [];
    if (!candidates.length && isYouTubePage(pageInfo.url)) {
      candidates = [{
        url: null,
        kind: 'page',
        title: pageInfo.title,
        mimeType: 'Analyze with GrabX',
        thumbnailUrl: pageInfo.thumbnailUrl
      }];
    }
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
  const pageThumbnail = absolute(
    document.querySelector('meta[property="og:image"]')?.content
    || document.querySelector('meta[name="twitter:image"]')?.content
    || document.querySelector('meta[property="twitter:image"]')?.content
  );
  const items = [];
  const visibleInViewport = element => {
    if (!element || element.closest('[hidden], [aria-hidden="true"], [inert]')) return false;
    const style = getComputedStyle(element);
    if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) return false;
    const rect = element.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0
      && rect.bottom > 0 && rect.right > 0
      && rect.top < innerHeight && rect.left < innerWidth;
  };
  const add = (url, kind, title, mimeType = '') => {
    const resolved = absolute(url);
    if (!resolved || resolved.length > 4096 || items.length >= 100) return;
    items.push({ url: resolved, kind, title: title || document.title, mimeType,
      thumbnailUrl: pageThumbnail });
  };

  document.querySelectorAll('video, audio').forEach(media => {
    if (!visibleInViewport(media)) return;
    const kind = media.tagName.toLowerCase();
    add(media.currentSrc || media.src, kind, media.getAttribute('title') || document.title,
      media.getAttribute('type') || '');
    media.querySelectorAll('source').forEach(source =>
      add(source.src, kind, source.getAttribute('title') || document.title, source.type || ''));
  });
  document.querySelectorAll('meta[property="og:video"], meta[property="og:video:url"], meta[property="og:audio"]')
    .forEach(meta => add(meta.content, meta.property.includes('audio') ? 'audio' : 'video', document.title));
  document.querySelectorAll('a[download], link[rel="enclosure"]').forEach(element => {
    if (!visibleInViewport(element)) return;
    const url = element.href;
    const label = element.getAttribute('download') || element.textContent?.trim() || document.title;
    add(url, 'file', label, element.type || '');
  });
  document.querySelectorAll('a[href]').forEach(anchor => {
    if (!visibleInViewport(anchor)) return;
    const href = anchor.getAttribute('href') || '';
    if (/\.(mp4|webm|mov|mkv|mp3|m4a|wav|flac|zip|rar|7z|pdf)(?:[?#]|$)/i.test(href)) {
      const extension = href.match(/\.([a-z0-9]+)(?:[?#]|$)/i)?.[1]?.toLowerCase();
      const kind = ['mp4', 'webm', 'mov', 'mkv'].includes(extension) ? 'video'
        : ['mp3', 'm4a', 'wav', 'flac'].includes(extension) ? 'audio' : 'file';
      add(href, kind, anchor.textContent?.trim() || href.split('/').pop());
    }
  });

  // Only attach structured metadata when its content URL matches the candidate.
  // Never guess a file size from unrelated page text or an advert.
  const metadata = new Map();
  const clean = value => typeof value === 'string' ? value.trim().slice(0, 255) : '';
  const size = value => {
    const text = String(value ?? '').trim();
    return /^(?:\d+(?:\.\d+)?)\s*(?:B|KB|MB|GB|TB|KiB|MiB|GiB|TiB|bytes?)$/i.test(text) ? text : '';
  };
  let visited = 0;
  let parsedCharacters = 0;
  const collect = (node, depth = 0) => {
    if (!node || typeof node !== 'object' || depth > 8 || ++visited > 1000) return;
    if (Array.isArray(node)) { node.slice(0, 100).forEach(value => collect(value, depth + 1)); return; }
    const url = absolute(node.contentUrl);
    if (url) metadata.set(url, {
      name: clean(node.name), sizeHint: size(node.contentSize),
      durationHint: /^P[0-9DT HMs.]+$/i.test(clean(node.duration)) ? clean(node.duration) : '',
      mimeType: /^[a-z0-9.+-]+\/[a-z0-9.+-]+$/i.test(clean(node.encodingFormat)) ? clean(node.encodingFormat) : '',
      source: 'Structured page data'
    });
    Object.values(node).forEach(value => collect(value, depth + 1));
  };
  document.querySelectorAll('script[type="application/ld+json"]').forEach(script => {
    if (visited > 1000 || parsedCharacters + script.textContent.length > 200000) return;
    parsedCharacters += script.textContent.length;
    try { collect(JSON.parse(script.textContent)); } catch { /* malformed site metadata */ }
  });
  const unique = [...new Map(items.map(item => [item.url, item])).values()].slice(0, 50);
  const sizeLinks = [...document.querySelectorAll('a[download], a[data-size], a[data-filesize]')].slice(0, 200);
  for (const item of unique) {
    const data = metadata.get(item.url);
    if (data) {
      item.sizeHint = data.sizeHint;
      item.durationHint = data.durationHint;
      item.metadataSource = data.source;
      if (data.name) item.title = data.name;
      if (!item.mimeType) item.mimeType = data.mimeType;
    }
    for (const anchor of sizeLinks) {
      if (absolute(anchor.href) !== item.url) continue;
      const hint = size(anchor.getAttribute('data-size') || anchor.getAttribute('data-filesize'));
      if (!item.sizeHint && hint) { item.sizeHint = hint; item.metadataSource = 'Download link'; }
    }
  }
  return {
    page: { url: location.href, title: document.title || location.hostname,
      thumbnailUrl: pageThumbnail },
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
  let visual = kind;
  if (candidate.thumbnailUrl) {
    const thumbnail = document.createElement('img');
    thumbnail.className = 'media-thumb';
    thumbnail.src = candidate.thumbnailUrl;
    thumbnail.alt = '';
    thumbnail.referrerPolicy = 'no-referrer';
    thumbnail.addEventListener('error', () => thumbnail.replaceWith(kind), { once: true });
    visual = thumbnail;
  }
  const copy = document.createElement('span');
  copy.className = 'media-copy';
  const title = document.createElement('strong');
  title.className = 'media-title';
  title.textContent = candidate.title || `Media ${index + 1}`;
  title.title = title.textContent;
  const meta = document.createElement('small');
  meta.className = 'media-meta';
  meta.textContent = [candidate.mimeType || safeHostname(candidate.url),
    candidate.sizeHint ? `≈ ${candidate.sizeHint} (page estimate)` : '', candidate.durationHint || ''].filter(Boolean).join(' · ');
  if (candidate.metadataSource) meta.title = candidate.metadataSource;
  copy.append(title, meta);
  top.append(visual, copy);

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

function youtubeThumbnail(value) {
  try {
    const url = new URL(value);
    let videoId = null;
    if (url.hostname === 'youtu.be') videoId = url.pathname.split('/').filter(Boolean)[0];
    else if (url.hostname.endsWith('youtube.com')) videoId = url.searchParams.get('v');
    return /^[A-Za-z0-9_-]{6,20}$/.test(videoId || '')
      ? `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`
      : null;
  } catch {
    return null;
  }
}

function isYouTubePage(value) {
  try {
    const url = new URL(value);
    if (url.hostname === 'youtu.be') return Boolean(url.pathname.split('/').filter(Boolean)[0]);
    if (!url.hostname.endsWith('youtube.com')) return false;
    return url.pathname === '/watch' || url.pathname.startsWith('/shorts/')
      || url.pathname.startsWith('/live/');
  } catch {
    return false;
  }
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

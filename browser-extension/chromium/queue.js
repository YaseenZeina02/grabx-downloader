(() => {
const message = document.getElementById('message');
const panel = document.getElementById('queuePanel');
const toggle = document.getElementById('manageQueue');
let refreshGeneration = 0;
let lastPending = false;
let openingUntil = 0;
let launchError = '';
const openApp = document.getElementById('openGrabX');
openApp.addEventListener('click', async () => {
  openingUntil = Date.now() + 20000;
  launchError = '';
  openApp.disabled = true;
  openApp.textContent = 'Opening…';
  try {
    const result = await chrome.runtime.sendMessage({type:'GRABX_OPEN_APP'});
    if (!result?.ok) {
      openingUntil = 0;
      launchError = result?.message || 'Could not open GrabX. Try opening it manually.';
    }
  } catch (error) { openingUntil = 0; launchError = 'Could not reach GrabX. Try opening it manually.'; }
  await refresh();
});
function expand(value) {
  panel.classList.toggle('hidden', !value);
  toggle.setAttribute('aria-expanded', String(value));
  document.getElementById('queueChevron').textContent = value ? '▴' : '▾';
}
toggle.addEventListener('click', () => {
  expand(toggle.getAttribute('aria-expanded') !== 'true');
  refresh().catch(error => message.textContent = error.message);
});
async function refresh(autoExpand = false) {
  const generation = ++refreshGeneration;
  const response = await chrome.runtime.sendMessage({type:'GRABX_QUEUE_LIST'});
  if (generation !== refreshGeneration) return;
  const presentation = queuePresentation(response);
  const status = document.getElementById('queueStatus');
  status.textContent = presentation.text;
  status.classList.toggle('hidden', !presentation.text);
  if (response.running === true) { openingUntil = 0; launchError = ''; }
  if (openingUntil && Date.now() >= openingUntil) {
    openingUntil = 0;
    launchError = 'GrabX hasn’t opened yet. Try again or open it manually.';
  }
  openApp.classList.toggle('hidden', response.running !== false);
  openApp.disabled = openingUntil > 0;
  openApp.textContent = openingUntil ? 'Opening…' : 'Open GrabX';
  if (response.ok) message.textContent = launchError;
  const list = document.getElementById('items'); list.replaceChildren();
  const pending = (response.items || []).some(item => item.confirmation);
  document.getElementById('queueCount').textContent = String((response.items || []).length);
  if (pending && (autoExpand || !lastPending)) expand(true);
  lastPending = pending;
  if (response.ok) {
    await chrome.action.setBadgeText({text:pending ? '?' : response.items.length ? 'Q' : ''});
  }
  if (!response.ok) message.textContent = response.message || 'Could not read the waiting list. Update the GrabX browser bridge.';
  for (const item of response.items || []) {
    const card = document.createElement('div');
    const title = document.createElement('p'); title.textContent = item.title;
    const state = document.createElement('small'); state.textContent = item.confirmation ? 'Ready to add' : 'Waiting for GrabX';
    card.append(title, state);
    for (const action of item.confirmation ? ['Add to queue', 'Cancel'] : ['Cancel']) {
      const button = document.createElement('button'); button.textContent = action;
      button.addEventListener('click', async () => {
        button.disabled = true;
        try {
          const result = await chrome.runtime.sendMessage({type:action === 'Add to queue' ? 'GRABX_QUEUE_ACCEPT' : 'GRABX_QUEUE_CANCEL',
            requestId:item.requestId});
          await refresh();
          if (!result.ok) message.textContent = result.message || 'Could not update request';
        } catch(error) { message.textContent = error.message; button.disabled = false; }
      });
      card.append(button);
    }
    list.append(card);
  }
  if (!(response.items || []).length && response.ok) list.textContent = 'You’re all caught up. No downloads waiting.';
}
document.getElementById('refresh').addEventListener('click', () => refresh().catch(error => message.textContent = error.message));
refresh(true).catch(error => message.textContent = error.message);
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === 'local' && Object.keys(changes).some(key => key.startsWith('pending-')))
    refresh(true).catch(error => message.textContent = error.message);
});
window.addEventListener('grabx-queue-updated', () => refresh(true).catch(error => message.textContent = error.message));
const timer = setInterval(() => refresh().catch(() => {}), 3000);
window.addEventListener('pagehide', () => clearInterval(timer));
})();

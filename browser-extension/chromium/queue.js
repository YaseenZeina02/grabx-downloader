(() => {
const message = document.getElementById('message');
const panel = document.getElementById('queuePanel');
const toggle = document.getElementById('manageQueue');
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
  const response = await chrome.runtime.sendMessage({type:'GRABX_QUEUE_LIST'});
  const list = document.getElementById('items'); list.replaceChildren();
  const pending = (response.items || []).some(item => item.confirmation);
  document.getElementById('queueCount').textContent = String((response.items || []).length);
  if (autoExpand && pending) expand(true);
  if (response.ok) {
    await chrome.action.setBadgeText({text:pending ? '?' : response.items.length ? 'Q' : ''});
  }
  if (!response.ok) message.textContent = response.message || 'Could not read the waiting list. Update the GrabX browser bridge.';
  for (const item of response.items || []) {
    const card = document.createElement('div');
    const title = document.createElement('p'); title.textContent = item.title;
    const state = document.createElement('small'); state.textContent = item.confirmation ? 'Awaiting your confirmation' : 'Queued for GrabX';
    card.append(title, state);
    for (const action of item.confirmation ? ['Accept', 'Cancel'] : ['Cancel']) {
      const button = document.createElement('button'); button.textContent = action;
      button.addEventListener('click', async () => {
        button.disabled = true;
        try {
          const result = await chrome.runtime.sendMessage({type:action === 'Accept' ? 'GRABX_QUEUE_ACCEPT' : 'GRABX_QUEUE_CANCEL',
            requestId:item.requestId, dontAskAgain:document.getElementById('dontAsk').checked});
          message.textContent = result.message || (result.ok ? 'Done' : 'Could not update request');
          await refresh();
        } catch(error) { message.textContent = error.message; button.disabled = false; }
      });
      card.append(button);
    }
    list.append(card);
  }
  if (!(response.items || []).length && response.ok) list.textContent = 'No downloads waiting.';
}
document.getElementById('refresh').addEventListener('click', () => refresh().catch(error => message.textContent = error.message));
refresh(true).catch(error => message.textContent = error.message);
chrome.storage.local.get('skipQueueConfirmation').then(settings => {
  document.getElementById('dontAsk').checked = Boolean(settings.skipQueueConfirmation);
});
document.getElementById('dontAsk').addEventListener('change', event => {
  chrome.storage.local.set({skipQueueConfirmation:event.target.checked});
});

chrome.storage.onChanged.addListener((changes, area) => {
  if (area === 'local' && Object.keys(changes).some(key => key.startsWith('pending-')))
    refresh(true).catch(error => message.textContent = error.message);
});
})();

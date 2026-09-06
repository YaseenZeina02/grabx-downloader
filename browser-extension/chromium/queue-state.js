/* Derive guidance from current queue and app state, never a saved notification. */
function queuePresentation(response) {
  const items = response.items || [];
  if (!items.length) return {text:''};
  if (response.running === false) return {text:'GrabX is closed. Open it when you’re ready to download.'};
  if (items.some(item => item.confirmation)) return {text:'Add a request to continue, or cancel it.'};
  return {text:''};
}

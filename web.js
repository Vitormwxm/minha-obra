/* Bootstrap da versão web. O cálculo continua em calculator.js, como no APK. */
(() => {
  'use strict';
  const label = document.getElementById('connection-status');
  let offlineReady = false;
  function updateStatus() {
    if (!label) return;
    label.textContent = navigator.onLine === false ? 'Sem conexão' : offlineReady ? 'Pronto offline' : 'No navegador';
  }
  window.addEventListener('online', updateStatus);
  window.addEventListener('offline', updateStatus);
  updateStatus();
  if (!('serviceWorker' in navigator) || !['http:', 'https:'].includes(location.protocol)) return;
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('./sw.js', { scope: './' })
      .then(() => navigator.serviceWorker.ready)
      .then(() => { offlineReady = true; updateStatus(); })
      .catch(() => { offlineReady = false; updateStatus(); });
  });
})();

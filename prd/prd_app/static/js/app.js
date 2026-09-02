/* Shared helpers: toasts, copy buttons, scroll reveals. */
window.PRD = (function () {
  function toast(message, kind) {
    const wrap = document.getElementById('toasts');
    if (!wrap) { return; }
    const el = document.createElement('div');
    el.className = 'toast ' + (kind || '');
    el.textContent = message;
    wrap.appendChild(el);
    setTimeout(() => {
      el.style.transition = 'opacity .3s, transform .3s';
      el.style.opacity = '0';
      el.style.transform = 'translateY(8px)';
      setTimeout(() => el.remove(), 320);
    }, 2600);
  }

  async function copy(text) {
    try {
      await navigator.clipboard.writeText(text);
      toast('Copied to clipboard', 'good');
    } catch (err) {
      toast('Could not copy — select it manually', 'bad');
    }
  }

  function reveals() {
    const items = document.querySelectorAll('.reveal:not(.in)');
    if (!('IntersectionObserver' in window)) {
      items.forEach((el) => el.classList.add('in'));
      return;
    }
    const io = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) { entry.target.classList.add('in'); io.unobserve(entry.target); }
      });
    }, { rootMargin: '0px 0px -6% 0px', threshold: 0.05 });
    items.forEach((el) => io.observe(el));
  }

  function escapeHtml(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, (ch) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
    }[ch]));
  }

  document.addEventListener('click', (ev) => {
    const target = ev.target.closest('[data-copy]');
    if (target) { copy(target.getAttribute('data-copy')); }
  });
  document.addEventListener('DOMContentLoaded', reveals);

  return { toast, copy, reveals, escapeHtml };
})();

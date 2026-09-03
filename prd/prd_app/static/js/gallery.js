/* Gallery: filter, search and lazy thumbnails. */
(function () {
  const grid = document.getElementById('grid');
  const empty = document.getElementById('empty');
  const more = document.getElementById('more');
  const search = document.getElementById('q');
  const presetSelect = document.getElementById('preset');
  const state = { sort: 'new', preset: '', q: '', page: 0 };
  let timer = null;

  function card(item) {
    const el = document.createElement('article');
    el.className = 'gal-card reveal';
    el.innerHTML = `
      <a class="gal-thumb" href="/site/${encodeURIComponent(item.slug)}">
        <div class="gal-thumb-fallback">${PRD.ico('page', 26)}</div>
        <iframe src="/s/${encodeURIComponent(item.slug)}" loading="lazy" tabindex="-1" scrolling="no"
                title="${PRD.escapeHtml(item.title)} preview"></iframe>
      </a>
      <div class="gal-body">
        <h3>${PRD.escapeHtml(item.title)}</h3>
        <p>${PRD.escapeHtml(item.summary || 'No description')}</p>
        <div class="gal-meta"><span>${PRD.ico('eye', 14)}${item.views}</span>
          <span>${PRD.ico('duplicate', 14)}${item.remixes}</span>
          <span style="margin-left:auto" class="mono">/${PRD.escapeHtml(item.slug)}</span></div>
      </div>
      <div class="gal-actions">
        <a class="btn btn-soft btn-s btn-block" href="/s/${encodeURIComponent(item.slug)}">Visit</a>
        <a class="btn btn-ghost btn-s btn-block" href="/editor?template=${encodeURIComponent(item.slug)}">Remix</a>
      </div>`;
    return el;
  }

  async function load(reset) {
    if (reset) { state.page = 0; grid.innerHTML = ''; }
    const params = new URLSearchParams({ sort: state.sort, preset: state.preset, q: state.q, page: state.page });
    let data;
    try {
      const res = await fetch('/api/gallery?' + params.toString());
      data = await res.json();
    } catch (err) {
      PRD.toast('Could not load the gallery', 'bad');
      return;
    }
    (data.items || []).forEach((item) => grid.appendChild(card(item)));
    empty.hidden = !(state.page === 0 && (data.items || []).length === 0);
    more.hidden = !data.has_more;
    PRD.reveals();
  }

  document.querySelectorAll('.filters .chip[data-sort]').forEach((chip) => {
    chip.addEventListener('click', () => {
      document.querySelectorAll('.filters .chip[data-sort]').forEach((c) => c.classList.remove('on'));
      chip.classList.add('on');
      state.sort = chip.getAttribute('data-sort');
      load(true);
    });
  });
  presetSelect.addEventListener('change', () => { state.preset = presetSelect.value; load(true); });
  search.addEventListener('input', () => {
    clearTimeout(timer);
    timer = setTimeout(() => { state.q = search.value.trim(); load(true); }, 280);
  });
  more.addEventListener('click', () => { state.page += 1; load(false); });

  load(true);
})();

/* PRD editor — a no-code builder driven entirely by the block schema.
 *
 * The document lives here in the browser; the server renders it into the
 * preview iframe so what you see is exactly what gets deployed.
 */
(function () {
  'use strict';

  const BOOT = window.PRD_BOOT || {};
  const DRAFT_KEY = 'prd.draft.v2';
  const HISTORY_MAX = 80;

  const state = {
    schema: null,
    blocksByType: {},
    doc: null,
    selected: null,
    history: [],
    hIndex: -1,
    device: 'desktop',
    dirty: false,
    mode: BOOT.editSlug ? 'edit' : 'new',
    slug: BOOT.editSlug || '',
    token: BOOT.editToken || '',
    templateSource: BOOT.template || '',
    preset: BOOT.preset || '',
    previewReady: false,
    dragging: null,
    dropIndex: -1,
    previewScale: 1,
    startedAt: Date.now(),
  };

  const $ = (id) => document.getElementById(id);
  const el = (tag, cls, html) => {
    const node = document.createElement(tag);
    if (cls) { node.className = cls; }
    if (html != null) { node.innerHTML = html; }
    return node;
  };
  const esc = PRD.escapeHtml;
  const uid = () => 'b' + Math.random().toString(16).slice(2, 10);
  const clone = (value) => JSON.parse(JSON.stringify(value));

  // ---------------------------------------------------------------- boot
  async function boot() {
    try {
      state.schema = await (await fetch('/api/schema')).json();
    } catch (err) {
      PRD.toast('Could not load the editor. Refresh the page?', 'bad');
      return;
    }
    state.schema.blocks.forEach((block) => { state.blocksByType[block.type] = block; });

    const doc = await initialDocument();
    setDoc(doc, { silent: true });
    pushHistory();

    buildLibrary();
    buildDesignTab();
    buildPageTab();
    wireChrome();
    renderAll();
    if (state.doc.blocks.length) { select(state.doc.blocks[0].id, false); }
  }

  async function initialDocument() {
    if (state.mode === 'edit') {
      try {
        const url = `/api/sites/${encodeURIComponent(state.slug)}?t=${encodeURIComponent(state.token)}`;
        const data = await (await fetch(url)).json();
        if (data.ok) {
          document.title = `Editing ${data.slug} — PRD`;
          state.isPublic = data.public;
          return data.doc;
        }
        PRD.toast('That manage link is not valid — starting a new site instead.', 'bad');
        state.mode = 'new';
      } catch (err) { state.mode = 'new'; }
    }
    if (BOOT.template) {
      try {
        const data = await (await fetch(`/api/templates/${encodeURIComponent(BOOT.template)}`)).json();
        if (data.ok) { PRD.toast('Remixing — make it yours', 'good'); return data.doc; }
      } catch (err) { /* fall through */ }
    }
    if (!BOOT.preset) {
      const saved = loadDraft();
      if (saved) { PRD.toast('Picked up where you left off', 'good'); return saved.doc; }
    }
    const presetId = BOOT.preset || 'blank';
    const data = await (await fetch(`/api/presets/${encodeURIComponent(presetId)}`)).json();
    return data.doc;
  }

  function loadDraft() {
    try {
      const raw = localStorage.getItem(DRAFT_KEY);
      if (!raw) { return null; }
      const saved = JSON.parse(raw);
      if (!saved || !saved.doc || !Array.isArray(saved.doc.blocks) || !saved.doc.blocks.length) { return null; }
      if (Date.now() - (saved.at || 0) > 1000 * 60 * 60 * 24 * 30) { return null; }
      return saved;
    } catch (err) { return null; }
  }

  function saveDraft() {
    if (state.mode === 'edit') { return; }
    try {
      localStorage.setItem(DRAFT_KEY, JSON.stringify({ at: Date.now(), doc: state.doc }));
      setSaveState('saved locally');
    } catch (err) { setSaveState('not saved'); }
  }

  function setSaveState(text) { $('saveState').textContent = text; }

  // ------------------------------------------------------------- history
  function setDoc(doc, options) {
    state.doc = doc;
    state.doc.blocks.forEach((block) => { if (!block.id) { block.id = uid(); } });
    if (!(options && options.silent)) { renderAll(); }
  }

  function pushHistory() {
    state.history = state.history.slice(0, state.hIndex + 1);
    state.history.push(clone(state.doc));
    if (state.history.length > HISTORY_MAX) { state.history.shift(); }
    state.hIndex = state.history.length - 1;
    updateHistoryButtons();
  }

  function updateHistoryButtons() {
    $('undo').disabled = state.hIndex <= 0;
    $('redo').disabled = state.hIndex >= state.history.length - 1;
  }

  function undo() {
    if (state.hIndex <= 0) { return; }
    state.hIndex -= 1;
    state.doc = clone(state.history[state.hIndex]);
    updateHistoryButtons();
    renderAll();
    saveDraft();
  }

  function redo() {
    if (state.hIndex >= state.history.length - 1) { return; }
    state.hIndex += 1;
    state.doc = clone(state.history[state.hIndex]);
    updateHistoryButtons();
    renderAll();
    saveDraft();
  }

  /** Apply a change, remember it for undo, refresh everything. */
  function mutate(fn, options) {
    fn(state.doc);
    if (!(options && options.noHistory)) { pushHistory(); }
    state.dirty = true;
    setSaveState('saving…');
    saveDraft();
    if (options && options.light) {
      schedulePreview();
      renderLayers();
    } else {
      renderAll();
    }
  }

  // ------------------------------------------------------------- preview
  let previewTimer = null;
  let previewToken = 0;

  function schedulePreview(delay) {
    clearTimeout(previewTimer);
    previewTimer = setTimeout(renderPreview, delay == null ? 400 : delay);
  }

  function frameScroll() {
    try { return $('preview').contentWindow.scrollY || 0; } catch (err) { return 0; }
  }

  async function renderPreview() {
    const token = ++previewToken;
    const busy = $('busy');
    busy.classList.add('on');
    let html;
    try {
      const res = await fetch('/api/preview', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ doc: state.doc, editing: true }),
      });
      const data = await res.json();
      if (!data.ok) { throw new Error(data.error || 'Preview failed'); }
      html = data.html;
    } catch (err) {
      busy.classList.remove('on');
      PRD.toast(err.message || 'Preview failed', 'bad');
      return;
    }
    if (token !== previewToken) { return; }

    const iframe = $('preview');
    pendingScroll = frameScroll();
    state.previewReady = false;
    // The preview announces itself with a `ready` message as soon as its script
    // runs. Waiting for the iframe's load event instead would mean waiting for
    // the web font too, which can be slow or blocked.
    iframe.onload = finishPreview;
    iframe.srcdoc = html;
    pollPreviewReady(0);
  }

  /* Poll the (same-origin) preview document instead of trusting one event:
     `load` waits for the web font, and a blocked font would leave the editor
     looking stuck. */
  function pollPreviewReady(attempt) {
    let ready = false;
    try {
      const doc = $('preview').contentDocument;
      ready = !!(doc && doc.readyState !== 'loading' && doc.body && doc.body.firstChild);
    } catch (err) { ready = false; }
    if (ready) { finishPreview(); return; }
    if (attempt < 60) { setTimeout(() => pollPreviewReady(attempt + 1), 50); }
    else { finishPreview(); }
  }

  let pendingScroll = 0;

  function finishPreview() {
    const busy = $('busy');
    if (!busy.classList.contains('on')) { return; }
    state.previewReady = true;
    fitPreview();
    try { $('preview').contentWindow.scrollTo(0, pendingScroll); } catch (err) { /* ignore */ }
    postToPreview({ type: 'select', id: state.selected });
    busy.classList.remove('on');
  }

  const DESKTOP_WIDTH = 1280;

  /* The centre pane is narrower than a real screen, so desktop preview renders
     at a true desktop width and is scaled down -- otherwise every site would
     show its mobile layout while you build it. */
  function fitPreview() {
    const frame = $('frame');
    const iframe = $('preview');
    if (state.device !== 'desktop') {
      state.previewScale = 1;
      iframe.style.cssText = '';
      return;
    }
    const scale = Math.min(1, frame.clientWidth / DESKTOP_WIDTH);
    state.previewScale = scale;
    iframe.style.width = DESKTOP_WIDTH + 'px';
    iframe.style.height = (frame.clientHeight / scale) + 'px';
    iframe.style.transform = `scale(${scale})`;
    iframe.style.transformOrigin = 'top left';
  }

  function postToPreview(message) {
    const iframe = $('preview');
    if (!iframe.contentWindow) { return; }
    iframe.contentWindow.postMessage(Object.assign({ source: 'prd-editor' }, message), '*');
  }

  window.addEventListener('message', (event) => {
    const data = event.data || {};
    if (data.source !== 'prd-preview') { return; }
    if (data.type === 'select') {
      select(data.id, false);
      openPane('right');
    } else if (data.type === 'dropindex') {
      state.dropIndex = data.index;
    } else if (data.type === 'ready') {
      finishPreview();
    } else if (data.type === 'pointer' && state.dragging) {
      const rect = $('preview').getBoundingClientRect();
      moveGhost(rect.left + data.x, rect.top + data.y);
    }
  });

  // -------------------------------------------------------------- blocks
  function blockDef(type) { return state.blocksByType[type] || { label: type, icon: '📦', fields: [] }; }

  function blockIndex(id) { return state.doc.blocks.findIndex((block) => block.id === id); }

  function defaultsFor(type) {
    const props = {};
    blockDef(type).fields.forEach((field) => { props[field.key] = clone(field.default); });
    return props;
  }

  function addBlock(type, index) {
    const block = { id: uid(), type, props: defaultsFor(type) };
    mutate((doc) => {
      const at = index == null || index < 0 || index > doc.blocks.length ? doc.blocks.length : index;
      doc.blocks.splice(at, 0, block);
    });
    select(block.id, true);
    PRD.toast(`${blockDef(type).label} added`, 'good');
  }

  function removeBlock(id) {
    const index = blockIndex(id);
    if (index < 0) { return; }
    mutate((doc) => { doc.blocks.splice(index, 1); });
    if (state.selected === id) {
      const next = state.doc.blocks[Math.min(index, state.doc.blocks.length - 1)];
      select(next ? next.id : null, false);
    }
  }

  function duplicateBlock(id) {
    const index = blockIndex(id);
    if (index < 0) { return; }
    const copy = clone(state.doc.blocks[index]);
    copy.id = uid();
    mutate((doc) => { doc.blocks.splice(index + 1, 0, copy); });
    select(copy.id, true);
  }

  function moveBlock(from, to) {
    if (from === to || from < 0) { return; }
    mutate((doc) => {
      const [block] = doc.blocks.splice(from, 1);
      doc.blocks.splice(to > from ? to - 1 : to, 0, block);
    });
  }

  function select(id, scroll) {
    state.selected = id;
    renderLayers();
    renderInspector();
    postToPreview({ type: 'select', id, scroll: !!scroll });
  }

  // ------------------------------------------------------------ rendering
  function renderAll() {
    renderLayers();
    renderInspector();
    $('siteTitle').value = state.doc.meta.title || '';
    syncDesignTab();
    syncPageTab();
    schedulePreview(120);
  }

  // ------------------------------------------------------- block library
  function buildLibrary() {
    const host = $('library');
    const search = $('libSearch');

    function draw(query) {
      host.innerHTML = '';
      const needle = (query || '').trim().toLowerCase();
      let shown = 0;
      state.schema.categories.forEach((category) => {
        const items = state.schema.blocks.filter((block) => {
          if (block.category !== category) { return false; }
          if (!needle) { return true; }
          return (block.label + ' ' + block.description + ' ' + (block.keywords || ''))
            .toLowerCase().includes(needle);
        });
        if (!items.length) { return; }
        shown += items.length;
        const section = el('div', 'pane-section');
        section.appendChild(el('h4', null, esc(category)));
        const grid = el('div', 'lib-grid');
        items.forEach((block) => {
          const button = el('button', 'lib-item');
          button.type = 'button';
          button.title = block.description;
          button.dataset.type = block.type;
          button.innerHTML = `<span class="lib-emoji">${esc(block.icon)}</span>
            <span class="lib-name">${esc(block.label)}</span>`;
          button.addEventListener('click', () => {
            if (state.dragging) { return; }
            const index = state.selected ? blockIndex(state.selected) + 1 : state.doc.blocks.length;
            addBlock(block.type, index);
          });
          button.addEventListener('pointerdown', (ev) => startLibraryDrag(ev, block));
          grid.appendChild(button);
        });
        section.appendChild(grid);
        host.appendChild(section);
      });
      if (!shown) { host.appendChild(el('p', 'lib-empty', 'Nothing matches that.')); }
    }

    search.addEventListener('input', () => draw(search.value));
    draw('');
  }

  // ---------------------------------------------------- drag into canvas
  let ghost = null;
  let shield = null;

  function moveGhost(x, y) {
    if (!ghost) { return; }
    ghost.style.left = x + 'px';
    ghost.style.top = y + 'px';
  }

  /* A transparent sheet over everything: the preview iframe would otherwise
     capture the pointer as soon as the drag crosses into it, and this window
     would stop hearing pointermove. */
  function raiseShield() {
    if (shield) { return; }
    shield = el('div');
    shield.style.cssText = 'position:fixed;inset:0;z-index:900;cursor:grabbing';
    document.body.appendChild(shield);
  }

  function dropShield() {
    if (shield) { shield.remove(); shield = null; }
  }

  function startLibraryDrag(event, block) {
    if (event.button !== 0) { return; }
    const origin = { x: event.clientX, y: event.clientY };
    let started = false;

    function onMove(moveEvent) {
      const dx = moveEvent.clientX - origin.x;
      const dy = moveEvent.clientY - origin.y;
      if (!started && Math.hypot(dx, dy) < 6) { return; }
      if (!started) {
        started = true;
        state.dragging = block;
        state.dropIndex = -1;
        raiseShield();
        ghost = el('div', 'drag-ghost', `${esc(block.icon)} ${esc(block.label)}`);
        document.body.appendChild(ghost);
        document.body.style.userSelect = 'none';
      }
      moveGhost(moveEvent.clientX, moveEvent.clientY);
      const rect = $('preview').getBoundingClientRect();
      const inside = moveEvent.clientX >= rect.left && moveEvent.clientX <= rect.right
        && moveEvent.clientY >= rect.top && moveEvent.clientY <= rect.bottom;
      if (inside) {
        postToPreview({ type: 'dragmove', y: (moveEvent.clientY - rect.top) / state.previewScale });
      } else {
        state.dropIndex = -1;
        postToPreview({ type: 'dragend' });
      }
    }

    function onUp(upEvent) {
      document.removeEventListener('pointermove', onMove);
      document.removeEventListener('pointerup', onUp);
      document.body.style.userSelect = '';
      dropShield();
      if (ghost) { ghost.remove(); ghost = null; }
      postToPreview({ type: 'dragend' });
      if (started) {
        const rect = $('preview').getBoundingClientRect();
        const inside = upEvent.clientX >= rect.left && upEvent.clientX <= rect.right
          && upEvent.clientY >= rect.top && upEvent.clientY <= rect.bottom;
        if (inside) { addBlock(block.type, state.dropIndex < 0 ? undefined : state.dropIndex); }
        setTimeout(() => { state.dragging = null; }, 0);
      } else {
        state.dragging = null;
      }
    }

    document.addEventListener('pointermove', onMove);
    document.addEventListener('pointerup', onUp);
  }

  // -------------------------------------------------------------- layers
  function blockSummary(block) {
    const props = block.props || {};
    for (const key of ['title', 'heading', 'brand', 'name', 'text', 'label']) {
      if (typeof props[key] === 'string' && props[key].trim()) { return props[key].trim().slice(0, 42); }
    }
    for (const key of Object.keys(props)) {
      if (Array.isArray(props[key])) { return `${props[key].length} item${props[key].length === 1 ? '' : 's'}`; }
    }
    return blockDef(block.type).description.slice(0, 42);
  }

  function renderLayers() {
    const host = $('layers');
    host.innerHTML = '';
    state.doc.blocks.forEach((block, index) => {
      const def = blockDef(block.type);
      const row = el('div', 'layer' + (block.id === state.selected ? ' on' : ''));
      row.dataset.id = block.id;
      row.dataset.index = String(index);
      row.innerHTML = `<span class="layer-emoji">${esc(def.icon)}</span>
        <span class="layer-name">${esc(def.label)}<span class="layer-sub">${esc(blockSummary(block))}</span></span>
        <span class="layer-tools">
          <button type="button" data-act="up" title="Move up">↑</button>
          <button type="button" data-act="down" title="Move down">↓</button>
          <button type="button" data-act="dup" title="Duplicate">⧉</button>
          <button type="button" data-act="del" title="Delete">✕</button>
        </span>`;
      row.addEventListener('click', (ev) => {
        const action = ev.target.getAttribute && ev.target.getAttribute('data-act');
        if (action === 'up') { moveBlock(index, index - 1); }
        else if (action === 'down') { moveBlock(index, index + 2); }
        else if (action === 'dup') { duplicateBlock(block.id); }
        else if (action === 'del') { removeBlock(block.id); }
        else { select(block.id, true); }
      });
      row.addEventListener('pointerdown', (ev) => {
        if (ev.target.closest('.layer-tools')) { return; }
        startSort(ev, host, row, (from, to) => moveBlock(from, to));
      });
      host.appendChild(row);
    });
    if (!state.doc.blocks.length) {
      host.appendChild(el('p', 'lib-empty', 'No sections yet — add one from the Add blocks tab.'));
    }
  }

  /** Pointer-based reordering shared by the layer list and list fields. */
  function startSort(event, container, item, onDrop) {
    if (event.button !== 0) { return; }
    const origin = { x: event.clientX, y: event.clientY };
    const selector = item.className.split(' ')[0];
    let started = false;
    let line = null;
    let target = -1;

    const siblings = () => Array.from(container.children).filter((node) => node.classList.contains(selector));
    const from = siblings().indexOf(item);

    function place(y) {
      const nodes = siblings();
      target = nodes.length;
      for (let i = 0; i < nodes.length; i += 1) {
        const rect = nodes[i].getBoundingClientRect();
        if (y < rect.top + rect.height / 2) { target = i; break; }
      }
      if (!line) { line = el('div', 'layer-drop'); }
      const reference = siblings()[target];
      container.insertBefore(line, reference || null);
    }

    function onMove(moveEvent) {
      if (!started && Math.hypot(moveEvent.clientX - origin.x, moveEvent.clientY - origin.y) < 6) { return; }
      if (!started) { started = true; item.classList.add('dragging'); document.body.style.userSelect = 'none'; }
      place(moveEvent.clientY);
    }

    function onUp() {
      document.removeEventListener('pointermove', onMove);
      document.removeEventListener('pointerup', onUp);
      document.body.style.userSelect = '';
      item.classList.remove('dragging');
      if (line) { line.remove(); }
      if (started && target >= 0) { onDrop(from, target); }
    }

    document.addEventListener('pointermove', onMove);
    document.addEventListener('pointerup', onUp);
  }

  // ----------------------------------------------------------- inspector
  const EMOJI = ('✨🚀🎮💬🎨🔥⚡🎯🛡️🏆🎁🎧🎵🎬📷🖼️📚📝📌📎🔗🌐💻📱🕹️🧩🧠💡🔧⚙️📊📈💰💳🛒🎪🎟️🎂🍕☕🍺🌙⭐'
    + '🌈☀️🌊🏔️🌲🐾🐱🐶🦊🐼🚗✈️🏠🏢🎓👑💎🔒✅❌❤️💜💙💚🧡🖤🤍😀😎🤝👋👀🙌💪🫶').split('');

  function emojiPicker(anchor, onPick) {
    document.querySelectorAll('.emoji-pop').forEach((pop) => pop.remove());
    const pop = el('div', 'emoji-pop');
    EMOJI.forEach((emoji) => {
      const button = el('button', null, emoji);
      button.type = 'button';
      button.addEventListener('click', () => { onPick(emoji); pop.remove(); });
      pop.appendChild(button);
    });
    document.body.appendChild(pop);
    const rect = anchor.getBoundingClientRect();
    pop.style.top = Math.min(rect.bottom + 6, window.innerHeight - 240) + 'px';
    pop.style.left = Math.max(10, Math.min(rect.left, window.innerWidth - 290)) + 'px';
    setTimeout(() => {
      document.addEventListener('pointerdown', function close(ev) {
        if (!pop.contains(ev.target)) { pop.remove(); document.removeEventListener('pointerdown', close); }
      });
    }, 0);
  }

  function buildField(spec, value, onChange) {
    const wrap = el('div', 'f');
    const id = 'f_' + Math.random().toString(16).slice(2, 8);
    if (spec.type !== 'toggle') {
      const label = el('label', null, esc(spec.label));
      label.setAttribute('for', id);
      wrap.appendChild(label);
    }

    let input;
    switch (spec.type) {
      case 'textarea':
      case 'richtext':
        input = el('textarea');
        input.rows = spec.rows || 4;
        input.value = value || '';
        input.addEventListener('input', () => onChange(input.value, true));
        wrap.appendChild(input);
        if (spec.type === 'richtext') {
          wrap.appendChild(el('div', 'hint', '**bold**, *italic*, [link](https://…) and - bullets all work.'));
        }
        break;

      case 'toggle': {
        const label = el('label', 'switch');
        label.innerHTML = `<input type="checkbox" id="${id}"><span class="switch-track"></span>
          <span style="font-size:.85rem;color:var(--text)">${esc(spec.label)}</span>`;
        input = label.querySelector('input');
        input.checked = !!value;
        input.addEventListener('change', () => onChange(input.checked));
        wrap.appendChild(label);
        break;
      }

      case 'select':
        input = el('select');
        (spec.options || []).forEach((option) => {
          const node = el('option', null, esc(option.label));
          node.value = option.value;
          input.appendChild(node);
        });
        input.value = value == null ? spec.default : value;
        input.addEventListener('change', () => onChange(input.value));
        wrap.appendChild(input);
        break;

      case 'color': {
        const row = el('div', 'f-color');
        const picker = el('input');
        picker.type = 'color';
        picker.value = /^#[0-9a-f]{6}$/i.test(value || '') ? value : (spec.default || '#7c5cff');
        input = el('input');
        input.type = 'text';
        input.value = value || '';
        input.placeholder = spec.default || '#7c5cff';
        picker.addEventListener('input', () => { input.value = picker.value; onChange(picker.value, true); });
        input.addEventListener('input', () => {
          if (/^#[0-9a-f]{3,6}$/i.test(input.value)) { picker.value = input.value; }
          onChange(input.value, true);
        });
        row.append(picker, input);
        wrap.appendChild(row);
        break;
      }

      case 'icon': {
        const row = el('div', 'f-emoji');
        input = el('input');
        input.type = 'text';
        input.value = value || '';
        input.maxLength = 8;
        input.addEventListener('input', () => onChange(input.value, true));
        const pick = el('button', 'btn btn-soft btn-xs', 'Pick emoji');
        pick.type = 'button';
        pick.addEventListener('click', () => emojiPicker(pick, (emoji) => {
          input.value = emoji;
          onChange(emoji);
        }));
        row.append(input, pick);
        wrap.appendChild(row);
        break;
      }

      case 'number': {
        const row = el('div', 'f-range');
        input = el('input');
        input.type = 'range';
        input.min = spec.min == null ? 0 : spec.min;
        input.max = spec.max == null ? 100 : spec.max;
        input.value = value == null ? spec.default : value;
        const out = el('output', null, String(input.value));
        input.addEventListener('input', () => { out.textContent = input.value; onChange(Number(input.value), true); });
        row.append(input, out);
        wrap.appendChild(row);
        break;
      }

      case 'date':
        input = el('input');
        input.type = 'datetime-local';
        input.value = value || '';
        input.addEventListener('input', () => onChange(input.value, true));
        wrap.appendChild(input);
        wrap.appendChild(el('div', 'hint', 'Times are UTC.'));
        break;

      case 'list':
        wrap.appendChild(buildListField(spec, Array.isArray(value) ? value : [], onChange));
        break;

      default: {
        input = el('input');
        input.type = spec.type === 'url' || spec.type === 'image' ? 'url' : 'text';
        input.value = value || '';
        input.placeholder = spec.placeholder || (spec.type === 'image' ? 'https://…/picture.jpg' : '');
        input.addEventListener('input', () => onChange(input.value, true));
        wrap.appendChild(input);
        if (spec.type === 'image') {
          const preview = el('img', 'f-img-preview');
          preview.alt = '';
          preview.hidden = !value;
          if (value) { preview.src = value; }
          preview.addEventListener('error', () => { preview.hidden = true; });
          input.addEventListener('change', () => {
            preview.hidden = !input.value;
            if (input.value) { preview.src = input.value; }
          });
          wrap.appendChild(preview);
        }
        break;
      }
    }
    if (input && input.id !== undefined) { input.id = id; }
    if (spec.help) { wrap.appendChild(el('div', 'hint', esc(spec.help))); }
    return wrap;
  }

  let pendingOpen = null;

  const TITLE_KEYS = ['label', 'title', 'name', 'q', 'quote', 'value', 'date', 'platform', 'text'];

  function itemTitle(spec, item, index) {
    const usable = (key) => {
      const spec2 = (spec.fields || []).find((sub) => sub.key === key);
      if (!spec2 || ['url', 'image', 'icon', 'toggle'].includes(spec2.type)) { return null; }
      const value = item[key];
      return typeof value === 'string' && value.trim() ? value.trim().slice(0, 34) : null;
    };
    for (const key of TITLE_KEYS) {
      const found = usable(key);
      if (found) { return found; }
    }
    for (const sub of spec.fields || []) {
      const found = usable(sub.key);
      if (found) { return found; }
    }
    return `${spec.item_label || 'Item'} ${index + 1}`;
  }

  function buildListField(spec, items, onChange) {
    const host = el('div');
    const list = el('div', 'list-items');
    host.appendChild(list);

    function commit(next, light) { onChange(next, light); }

    items.forEach((item, index) => {
      const card = el('div', 'li');
      const head = el('div', 'li-head');
      head.innerHTML = `<span class="li-grip">⠿</span>
        <span class="li-title">${esc(itemTitle(spec, item, index))}</span>
        <span class="li-tools">
          <button type="button" data-act="dup" title="Duplicate">⧉</button>
          <button type="button" data-act="del" title="Remove">✕</button>
        </span>`;
      head.addEventListener('click', (ev) => {
        const action = ev.target.getAttribute && ev.target.getAttribute('data-act');
        if (action === 'del') {
          const next = items.slice();
          next.splice(index, 1);
          commit(next);
        } else if (action === 'dup') {
          const next = items.slice();
          next.splice(index + 1, 0, clone(item));
          commit(next);
        } else {
          card.classList.toggle('open');
        }
      });
      head.addEventListener('pointerdown', (ev) => {
        if (!ev.target.closest('.li-grip')) { return; }
        startSort(ev, list, card, (from, to) => {
          const next = items.slice();
          const [moved] = next.splice(from, 1);
          next.splice(to > from ? to - 1 : to, 0, moved);
          commit(next);
        });
      });
      card.appendChild(head);

      const body = el('div', 'li-body');
      (spec.fields || []).forEach((sub) => {
        body.appendChild(buildField(sub, item[sub.key], (value, light) => {
          const next = items.slice();
          next[index] = Object.assign({}, next[index], { [sub.key]: value });
          items = next;
          commit(next, light);
          head.querySelector('.li-title').textContent = itemTitle(spec, next[index], index);
        }));
      });
      card.appendChild(body);
      if (pendingOpen && pendingOpen.key === spec.key && pendingOpen.index === index) {
        card.classList.add('open');
        pendingOpen = null;
      }
      list.appendChild(card);
    });

    const max = spec.max || 24;
    const add = el('button', 'btn btn-soft btn-s btn-block', `＋ Add ${esc((spec.item_label || 'item').toLowerCase())}`);
    add.type = 'button';
    add.style.marginTop = '10px';
    add.disabled = items.length >= max;
    add.addEventListener('click', () => {
      const blank = {};
      (spec.fields || []).forEach((sub) => { blank[sub.key] = clone(sub.default); });
      pendingOpen = { key: spec.key, index: items.length };
      commit(items.concat([blank]));
    });
    host.appendChild(add);
    if (items.length >= max) { host.appendChild(el('div', 'hint', `That's the maximum of ${max}.`)); }
    return host;
  }

  function renderInspector() {
    const head = $('inspectorHead');
    const body = $('inspector');
    const scrollTop = body.scrollTop;
    head.innerHTML = '';
    body.innerHTML = '';
    requestAnimationFrame(() => { body.scrollTop = scrollTop; });

    const index = blockIndex(state.selected);
    if (index < 0) {
      body.innerHTML = `<div class="insp-empty"><div>👈</div>
        Select a section on the page — or add one from the left — and its settings show up here.</div>`;
      return;
    }
    const block = state.doc.blocks[index];
    const def = blockDef(block.type);

    const bar = el('div', 'insp-head');
    bar.innerHTML = `<span class="emoji">${esc(def.icon)}</span>
      <span style="flex:1"><h3>${esc(def.label)}</h3><p>${esc(def.description)}</p></span>`;
    const dup = el('button', 'ed-icon', '⧉');
    dup.title = 'Duplicate section';
    dup.addEventListener('click', () => duplicateBlock(block.id));
    const del = el('button', 'ed-icon', '✕');
    del.title = 'Delete section';
    del.addEventListener('click', () => removeBlock(block.id));
    bar.append(dup, del);
    head.appendChild(bar);

    const main = el('div');
    const layout = el('div', 'pane-section');
    layout.appendChild(el('h4', null, 'Layout'));
    let hasLayout = false;

    def.fields.forEach((spec) => {
      const node = buildField(spec, block.props[spec.key], (value, light) => {
        // `light` means "someone is typing": patch quietly and keep focus.
        // Anything else (adding a list item, flipping a select) rebuilds the panel.
        mutate((doc) => {
          const current = doc.blocks[blockIndex(block.id)];
          if (current) { current.props[spec.key] = value; }
        }, { light: !!light, noHistory: !!light });
        if (light) { debounceHistory(); }
      });
      if (spec.group === 'Layout') { hasLayout = true; layout.appendChild(node); } else { main.appendChild(node); }
    });

    body.appendChild(main);
    if (hasLayout) { body.appendChild(layout); }
  }

  let historyTimer = null;
  function debounceHistory() {
    clearTimeout(historyTimer);
    historyTimer = setTimeout(() => { pushHistory(); setSaveState('saved locally'); }, 700);
  }

  // --------------------------------------------------------- design tab
  function setTheme(patch) {
    mutate((doc) => { Object.assign(doc.theme, patch); }, { light: true, noHistory: true });
    debounceHistory();
  }

  function buildDesignTab() {
    const host = $('tabDesign');
    host.innerHTML = '';

    const palettes = el('div', 'pane-section');
    palettes.appendChild(el('h4', null, 'Colour palette'));
    const swatches = el('div', 'swatches');
    state.schema.palettes.forEach((palette) => {
      const item = el('div', 'swatch');
      item.dataset.palette = palette.id;
      item.innerHTML = `<div class="swatch-bar">
          <span style="background:${esc(palette.bg)}"></span>
          <span style="background:${esc(palette.surface)}"></span>
          <span style="background:${esc(palette.accent)}"></span>
          <span style="background:${esc(palette.accent2)}"></span>
        </div><div class="swatch-name">${esc(palette.label)}</div>`;
      item.addEventListener('click', () => {
        setTheme({ palette: palette.id, bg: '', surface: '', text: '', muted: '', accent: '', accent2: '', mode: '' });
        syncDesignTab();
      });
      swatches.appendChild(item);
    });
    palettes.appendChild(swatches);
    host.appendChild(palettes);

    const custom = el('div', 'pane-section');
    custom.appendChild(el('h4', null, 'Your own colours'));
    const active = state.schema.palettes.find((item) => item.id === state.doc.theme.palette) || {};
    [['accent', 'Accent'], ['accent2', 'Second accent'], ['bg', 'Background'],
     ['surface', 'Cards'], ['text', 'Text'], ['muted', 'Muted text']].forEach(([key, label]) => {
      custom.appendChild(buildField(
        { key, type: 'color', label, default: active[key] || '#7c5cff' },
        state.doc.theme[key], (value) => setTheme({ [key]: value })));
    });
    custom.appendChild(el('div', 'hint', 'Leave one empty to keep the palette\'s own colour.'));
    const reset = el('button', 'btn btn-soft btn-s btn-block', 'Reset to the palette');
    reset.type = 'button';
    reset.addEventListener('click', () => {
      setTheme({ bg: '', surface: '', text: '', muted: '', accent: '', accent2: '' });
      syncDesignTab();
    });
    custom.appendChild(reset);
    host.appendChild(custom);

    const type = el('div', 'pane-section');
    type.appendChild(el('h4', null, 'Type & shape'));
    const fontOptions = state.schema.fonts.map((font) => ({ value: font.id, label: font.label }));
    type.appendChild(buildField({ key: 'heading_font', type: 'select', label: 'Heading font',
      options: fontOptions, default: 'inter' }, state.doc.theme.heading_font,
      (value) => setTheme({ heading_font: value })));
    type.appendChild(buildField({ key: 'font', type: 'select', label: 'Body font',
      options: fontOptions, default: 'inter' }, state.doc.theme.font, (value) => setTheme({ font: value })));
    type.appendChild(buildField({ key: 'radius', type: 'number', label: 'Corner rounding',
      min: 0, max: 40, default: 18 }, state.doc.theme.radius, (value) => setTheme({ radius: value })));
    type.appendChild(buildField({ key: 'width', type: 'number', label: 'Content width',
      min: 640, max: 1600, default: 1080 }, state.doc.theme.width, (value) => setTheme({ width: value })));
    type.appendChild(buildField({ key: 'spacing', type: 'select', label: 'Spacing',
      options: state.schema.spacings.map((value) => ({ value, label: value })), default: 'normal' },
      state.doc.theme.spacing, (value) => setTheme({ spacing: value })));
    host.appendChild(type);

    const effects = el('div', 'pane-section');
    effects.appendChild(el('h4', null, 'Background & motion'));
    effects.appendChild(buildField({ key: 'effect', type: 'select', label: 'Page texture',
      options: state.schema.effects.map((value) => ({ value, label: value })), default: 'none' },
      state.doc.theme.effect, (value) => setTheme({ effect: value })));
    effects.appendChild(buildField({ key: 'animations', type: 'toggle', label: 'Animate on scroll',
      default: true }, state.doc.theme.animations, (value) => setTheme({ animations: value })));
    host.appendChild(effects);
  }

  function syncDesignTab() {
    if (!state.schema) { return; }
    buildDesignTab();
    document.querySelectorAll('.swatch').forEach((node) => {
      node.classList.toggle('on', node.dataset.palette === state.doc.theme.palette);
    });
  }

  // ----------------------------------------------------------- page tab
  function buildPageTab() {
    const host = $('tabPage');
    host.innerHTML = '';

    const meta = el('div', 'pane-section');
    meta.appendChild(el('h4', null, 'Page details'));
    meta.appendChild(buildField({ key: 'title', type: 'text', label: 'Site title', default: '' },
      state.doc.meta.title, (value) => setMeta({ title: value })));
    meta.appendChild(buildField({ key: 'description', type: 'textarea', label: 'Description', rows: 3,
      default: '', help: 'Shown in Google results and link previews.' },
      state.doc.meta.description, (value) => setMeta({ description: value })));
    meta.appendChild(buildField({ key: 'favicon', type: 'icon', label: 'Tab icon', default: '🌐' },
      state.doc.meta.favicon, (value) => setMeta({ favicon: value })));
    meta.appendChild(buildField({ key: 'og_image', type: 'image', label: 'Link preview image', default: '' },
      state.doc.meta.og_image, (value) => setMeta({ og_image: value })));
    host.appendChild(meta);

    const file = el('div', 'pane-section');
    file.appendChild(el('h4', null, 'Your design file'));
    const download = el('button', 'btn btn-soft btn-s btn-block', '⬇ Download design (.json)');
    download.type = 'button';
    download.style.marginBottom = '8px';
    download.addEventListener('click', downloadDesign);
    const upload = el('button', 'btn btn-soft btn-s btn-block', '⬆ Load a design file');
    upload.type = 'button';
    upload.style.marginBottom = '8px';
    upload.addEventListener('click', uploadDesign);
    const restart = el('button', 'btn btn-ghost btn-s btn-block', '↺ Start over from a preset');
    restart.type = 'button';
    restart.addEventListener('click', startOver);
    file.append(download, upload, restart);
    host.appendChild(file);

    const help = el('div', 'pane-section');
    help.appendChild(el('h4', null, 'Shortcuts'));
    help.appendChild(el('div', 'hint',
      'Ctrl+Z undo · Ctrl+Shift+Z redo · Ctrl+S publish · Delete removes the selected section.'));
    host.appendChild(help);
  }

  function setMeta(patch) {
    mutate((doc) => { Object.assign(doc.meta, patch); }, { light: true, noHistory: true });
    if (patch.title != null) { $('siteTitle').value = patch.title; }
    debounceHistory();
  }

  function syncPageTab() { if (state.schema) { buildPageTab(); } }

  function downloadDesign() {
    const blob = new Blob([JSON.stringify(state.doc, null, 2)], { type: 'application/json' });
    const link = el('a');
    link.href = URL.createObjectURL(blob);
    link.download = (state.doc.meta.title || 'site').toLowerCase().replace(/[^a-z0-9]+/g, '-') + '.prd.json';
    link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 1000);
  }

  function uploadDesign() {
    const input = el('input');
    input.type = 'file';
    input.accept = 'application/json,.json';
    input.addEventListener('change', () => {
      const file = input.files && input.files[0];
      if (!file) { return; }
      const reader = new FileReader();
      reader.onload = () => {
        try {
          const doc = JSON.parse(String(reader.result));
          if (!doc || !Array.isArray(doc.blocks)) { throw new Error('bad file'); }
          setDoc(doc);
          pushHistory();
          PRD.toast('Design loaded', 'good');
        } catch (err) { PRD.toast('That file is not a PRD design', 'bad'); }
      };
      reader.readAsText(file);
    });
    input.click();
  }

  async function startOver() {
    const choice = prompt('Start over from which preset?\n' +
      BOOT.presets.map((preset) => preset.id).join(', '), 'blank');
    if (!choice) { return; }
    try {
      const data = await (await fetch(`/api/presets/${encodeURIComponent(choice.trim())}`)).json();
      if (!data.ok) { throw new Error(); }
      setDoc(data.doc);
      pushHistory();
      select(state.doc.blocks[0] ? state.doc.blocks[0].id : null, false);
      PRD.toast('Fresh start', 'good');
    } catch (err) { PRD.toast('No preset with that name', 'bad'); }
  }

  // ------------------------------------------------------------- chrome
  function openPane(side) {
    if (window.innerWidth > 900) { return; }
    $('paneLeft').classList.toggle('open', side === 'left');
    $('paneRight').classList.toggle('open', side === 'right');
  }

  function wireChrome() {
    document.querySelectorAll('.pane-left .tab').forEach((tab) => {
      tab.addEventListener('click', () => {
        document.querySelectorAll('.pane-left .tab').forEach((other) => other.classList.remove('on'));
        tab.classList.add('on');
        $('tabLibrary').hidden = tab.dataset.tab !== 'library';
        $('tabLayers').hidden = tab.dataset.tab !== 'layers';
      });
    });
    document.querySelectorAll('.pane-right .tab').forEach((tab) => {
      tab.addEventListener('click', () => {
        document.querySelectorAll('.pane-right .tab').forEach((other) => other.classList.remove('on'));
        tab.classList.add('on');
        $('tabElement').style.display = tab.dataset.tab === 'element' ? 'flex' : 'none';
        $('tabDesign').hidden = tab.dataset.tab !== 'design';
        $('tabPage').hidden = tab.dataset.tab !== 'page';
      });
    });
    document.querySelectorAll('[data-device]').forEach((button) => {
      button.addEventListener('click', () => {
        document.querySelectorAll('[data-device]').forEach((other) => other.classList.remove('on'));
        button.classList.add('on');
        state.device = button.dataset.device;
        $('frame').className = 'frame' + (state.device === 'desktop' ? '' : ' ' + state.device);
        fitPreview();
      });
    });
    document.querySelectorAll('[data-pane]').forEach((button) => {
      button.addEventListener('click', () => {
        const side = button.dataset.pane;
        const pane = side === 'left' ? $('paneLeft') : $('paneRight');
        const isOpen = pane.classList.contains('open');
        $('paneLeft').classList.remove('open');
        $('paneRight').classList.remove('open');
        if (!isOpen) { pane.classList.add('open'); }
      });
    });

    $('undo').addEventListener('click', undo);
    $('redo').addEventListener('click', redo);
    $('siteTitle').addEventListener('input', (ev) => setMeta({ title: ev.target.value }));
    $('publishBtn').addEventListener('click', openPublish);
    $('previewBtn').addEventListener('click', openCleanPreview);
    if (state.mode === 'edit') { $('publishBtn').textContent = 'Save changes →'; }

    setTimeout(() => { const hint = $('hint'); if (hint) { hint.style.opacity = '0'; } }, 5200);

    let resizeTimer = null;
    window.addEventListener('resize', () => {
      clearTimeout(resizeTimer);
      resizeTimer = setTimeout(fitPreview, 120);
    });

    document.addEventListener('keydown', (ev) => {
      const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement.tagName);
      if ((ev.ctrlKey || ev.metaKey) && ev.key.toLowerCase() === 'z') {
        ev.preventDefault();
        if (ev.shiftKey) { redo(); } else { undo(); }
      } else if ((ev.ctrlKey || ev.metaKey) && ev.key.toLowerCase() === 'y') {
        ev.preventDefault(); redo();
      } else if ((ev.ctrlKey || ev.metaKey) && ev.key.toLowerCase() === 's') {
        ev.preventDefault(); openPublish();
      } else if (!typing && (ev.key === 'Delete' || ev.key === 'Backspace') && state.selected) {
        ev.preventDefault(); removeBlock(state.selected);
      } else if (ev.key === 'Escape') {
        document.querySelectorAll('.modal-bd').forEach((node) => node.remove());
      }
    });

    window.addEventListener('beforeunload', (ev) => {
      if (state.dirty && state.mode === 'edit') { ev.preventDefault(); ev.returnValue = ''; }
    });
  }

  async function openCleanPreview() {
    try {
      const res = await fetch('/api/preview', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ doc: state.doc, editing: false }),
      });
      const data = await res.json();
      if (!data.ok) { throw new Error(data.error); }
      const blob = new Blob([data.html], { type: 'text/html' });
      window.open(URL.createObjectURL(blob), '_blank', 'noopener');
    } catch (err) { PRD.toast(err.message || 'Preview failed', 'bad'); }
  }

  // ------------------------------------------------------------ publish
  function modal(html) {
    document.querySelectorAll('.modal-bd').forEach((node) => node.remove());
    const backdrop = el('div', 'modal-bd');
    const box = el('div', 'modal', html);
    backdrop.appendChild(box);
    backdrop.addEventListener('pointerdown', (ev) => { if (ev.target === backdrop) { backdrop.remove(); } });
    document.body.appendChild(backdrop);
    return box;
  }

  function suggestedSlug() {
    return (state.doc.meta.title || 'my-site').toLowerCase()
      .normalize('NFKD').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40) || 'my-site';
  }

  function openPublish() {
    if (!state.doc.blocks.length) { PRD.toast('Add at least one section first', 'bad'); return; }
    return state.mode === 'edit' ? openSaveDialog() : openPublishDialog();
  }

  function openPublishDialog() {
    const box = modal(`
      <h2>Request your site</h2>
      <p class="lead">No account needed. Pick an address, and you'll get a private link to manage it afterwards.</p>
      <div class="field">
        <label for="slugInput">Web address</label>
        <div class="slug-line"><span>/s/</span><input id="slugInput" type="text" spellcheck="false"
          autocomplete="off" maxlength="40" placeholder="my-site"></div>
        <div class="err" id="slugErr"></div>
      </div>
      <label class="check">
        <input type="checkbox" id="publicInput" checked>
        <span><b>Show it in the public gallery</b>
        <span>Other people can find it and use your design as a template. Turn this off to keep the
        link private — the site still works, it just isn't listed.</span></span>
      </label>
      <div class="field">
        <label for="contactInput">Contact (optional)</label>
        <input id="contactInput" type="text" maxlength="120" placeholder="email or Discord tag">
        <div class="hint">Only used if the owner needs to reach you about this site.</div>
      </div>
      <div class="field">
        <label for="noteInput">Note to the owner (optional)</label>
        <textarea id="noteInput" rows="2" maxlength="400" placeholder="Anything they should know?"></textarea>
      </div>
      <input class="honey" id="websiteInput" tabindex="-1" autocomplete="off" aria-hidden="true">
      <div id="usageNote" class="hint"></div>
      <div class="modal-actions">
        <button class="btn btn-ghost" id="cancelBtn">Not yet</button>
        <button class="btn btn-primary" id="sendBtn">Send request</button>
      </div>`);

    const slugInput = box.querySelector('#slugInput');
    const slugErr = box.querySelector('#slugErr');
    const sendBtn = box.querySelector('#sendBtn');
    slugInput.value = suggestedSlug();

    let checkTimer = null;
    async function checkSlug() {
      const value = slugInput.value.trim();
      if (!value) { slugErr.textContent = 'Pick an address.'; sendBtn.disabled = true; return; }
      try {
        const data = await (await fetch('/api/slug?slug=' + encodeURIComponent(value))).json();
        slugInput.value = data.slug || value;
        if (data.ok) {
          slugErr.className = 'slug-ok';
          slugErr.textContent = `✓ ${data.url} is free`;
          sendBtn.disabled = false;
        } else {
          slugErr.className = 'err';
          slugErr.textContent = data.error + (data.suggestion ? ` Try “${data.suggestion}”.` : '');
          sendBtn.disabled = true;
        }
      } catch (err) { slugErr.textContent = ''; sendBtn.disabled = false; }
    }
    slugInput.addEventListener('input', () => { clearTimeout(checkTimer); checkTimer = setTimeout(checkSlug, 320); });
    checkSlug();

    fetch('/api/usage').then((res) => res.json()).then((data) => {
      const usage = (data.usage || {}).publish_day;
      if (usage) {
        box.querySelector('#usageNote').textContent =
          `${Math.max(0, usage.limit - usage.used)} of ${usage.limit} sites left today from this network.`;
      }
    }).catch(() => {});

    box.querySelector('#cancelBtn').addEventListener('click', () => box.closest('.modal-bd').remove());
    sendBtn.addEventListener('click', async () => {
      sendBtn.disabled = true;
      sendBtn.textContent = 'Sending…';
      try {
        const res = await fetch('/api/publish', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            doc: state.doc,
            slug: slugInput.value.trim(),
            public: box.querySelector('#publicInput').checked,
            contact: box.querySelector('#contactInput').value,
            note: box.querySelector('#noteInput').value,
            preset: state.preset || (state.templateSource ? 'custom' : 'custom'),
            template: state.templateSource,
            website: box.querySelector('#websiteInput').value,
            elapsed_ms: Date.now() - state.startedAt,
          }),
        });
        const data = await res.json();
        if (!res.ok || !data.ok) { throw new Error(data.error || 'That did not work.'); }
        state.mode = 'edit';
        state.slug = data.slug;
        state.token = data.manage_token;
        state.dirty = false;
        $('publishBtn').textContent = 'Save changes →';
        try { localStorage.removeItem(DRAFT_KEY); } catch (err) { /* ignore */ }
        showSuccess(data);
      } catch (err) {
        sendBtn.disabled = false;
        sendBtn.textContent = 'Send request';
        PRD.toast(err.message, 'bad');
      }
    });
    slugInput.focus();
    slugInput.select();
  }

  function showSuccess(data) {
    const live = data.live;
    const manageUrl = location.origin + data.manage_url;
    const siteUrl = data.url.startsWith('http') ? data.url : location.origin + data.url;
    const box = modal(`
      <div class="success-mark">${live ? '🎉' : '📨'}</div>
      <h2 class="center">${live ? 'Your site is live' : 'Request sent'}</h2>
      <p class="lead center">${live
        ? 'It is deployed and ready to share.'
        : 'It is in the queue. As soon as the owner approves it, your address starts working.'}</p>
      <div class="field">
        <label>Your address</label>
        <div class="code-line"><span style="flex:1">${esc(siteUrl)}</span>
          <button class="btn btn-xs btn-soft" data-copy="${esc(siteUrl)}">Copy</button></div>
      </div>
      <div class="field">
        <label>Your private manage link — save this</label>
        <div class="code-line"><span style="flex:1">${esc(manageUrl)}</span>
          <button class="btn btn-xs btn-soft" data-copy="${esc(manageUrl)}">Copy</button></div>
        <div class="hint">This link is the only way back in to edit or take down your site. There are no
          accounts, so nobody can recover it for you.</div>
      </div>
      ${data.deploy_error ? `<p class="err">${esc(data.deploy_error)}</p>` : ''}
      <div class="modal-actions">
        <button class="btn btn-ghost" id="keepEditing">Keep editing</button>
        <a class="btn btn-primary" href="${esc(manageUrl)}">Open manage page</a>
      </div>`);
    box.querySelector('#keepEditing').addEventListener('click', () => box.closest('.modal-bd').remove());
  }

  function openSaveDialog() {
    const box = modal(`
      <h2>Save changes</h2>
      <p class="lead">Your site is already yours — this pushes the new version out.</p>
      <label class="check">
        <input type="checkbox" id="publicInput" ${state.isPublic === false ? '' : 'checked'}>
        <span><b>Show it in the public gallery</b><span>Others can find and remix it.</span></span>
      </label>
      <div class="field">
        <label for="noteInput">What changed? (optional)</label>
        <input id="noteInput" type="text" maxlength="400" placeholder="Updated the hero text">
      </div>
      <div class="modal-actions">
        <button class="btn btn-ghost" id="cancelBtn">Cancel</button>
        <button class="btn btn-primary" id="saveBtn">Save &amp; deploy</button>
      </div>`);
    box.querySelector('#cancelBtn').addEventListener('click', () => box.closest('.modal-bd').remove());
    const saveBtn = box.querySelector('#saveBtn');
    saveBtn.addEventListener('click', async () => {
      saveBtn.disabled = true;
      saveBtn.textContent = 'Saving…';
      try {
        const res = await fetch(`/api/sites/${encodeURIComponent(state.slug)}/update`, {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            token: state.token,
            doc: state.doc,
            public: box.querySelector('#publicInput').checked,
            note: box.querySelector('#noteInput').value,
          }),
        });
        const data = await res.json();
        if (!res.ok || !data.ok) { throw new Error(data.error || 'Could not save.'); }
        state.dirty = false;
        state.isPublic = box.querySelector('#publicInput').checked;
        box.closest('.modal-bd').remove();
        PRD.toast(data.queued ? 'Saved — waiting for approval' : 'Saved and deployed', 'good');
        if (data.deploy_error) { PRD.toast(data.deploy_error, 'bad'); }
      } catch (err) {
        saveBtn.disabled = false;
        saveBtn.textContent = 'Save & deploy';
        PRD.toast(err.message, 'bad');
      }
    });
  }

  boot();
})();

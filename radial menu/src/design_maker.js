// ==========================================
// DESIGN MAKER — Visual Theme Builder JS
// ==========================================

const invoke = (...args) => {
  if (window.__TAURI__ && window.__TAURI__.core) {
    return window.__TAURI__.core.invoke(...args);
  }
  return Promise.resolve(null);
};

const getCurrentWindow = () => {
  if (window.__TAURI__) {
    if (window.__TAURI__.webviewWindow) return window.__TAURI__.webviewWindow.getCurrentWebviewWindow();
    if (window.__TAURI__.window) return window.__TAURI__.window.getCurrentWindow();
  }
  return { hide: () => {}, startDragging: () => {} };
};

// ====== PRESET THEMES ======
const PRESETS = [
  {
    name: 'Obsidian Dark',
    colors: { bg: '#0a0a0f', surface: '#1a1a2e', primary: '#6366f1', secondary: '#8b5cf6', accent: '#06b6d4', text: '#e2e8f0', slice: '#1e1e3a', sliceHover: '#2d2d5e', hub: '#111128', subSlice: '#16163a' },
    effects: { blur: 25, opacity: 65, glow: 0.5, border: 1 }
  },
  {
    name: 'Neon Cyber',
    colors: { bg: '#050510', surface: '#0a0a20', primary: '#00ff88', secondary: '#ff006e', accent: '#00e5ff', text: '#e0ffe0', slice: '#0a1a1a', sliceHover: '#0f2a2a', hub: '#060615', subSlice: '#081515' },
    effects: { blur: 30, opacity: 55, glow: 1.5, border: 1 }
  },
  {
    name: 'Rose Gold',
    colors: { bg: '#1a0a0f', surface: '#2e1a20', primary: '#f1636e', secondary: '#cf5c8b', accent: '#d4a06b', text: '#f0e2e2', slice: '#3a1e24', sliceHover: '#5e2d38', hub: '#28111a', subSlice: '#3a1620' },
    effects: { blur: 20, opacity: 70, glow: 0.4, border: 1 }
  },
  {
    name: 'Ocean Deep',
    colors: { bg: '#030815', surface: '#0a1628', primary: '#3b82f6', secondary: '#0ea5e9', accent: '#06d6a0', text: '#cbd5e1', slice: '#0d1e3a', sliceHover: '#142d5e', hub: '#06102a', subSlice: '#0a1835' },
    effects: { blur: 25, opacity: 60, glow: 0.6, border: 1 }
  },
  {
    name: 'Forest Zen',
    colors: { bg: '#060f08', surface: '#12241a', primary: '#22c55e', secondary: '#84cc16', accent: '#a3e635', text: '#dcfce7', slice: '#0f2218', sliceHover: '#1a3a28', hub: '#0a1a10', subSlice: '#0d1e15' },
    effects: { blur: 20, opacity: 70, glow: 0.3, border: 0.5 }
  },
  {
    name: 'Sunset Fire',
    colors: { bg: '#150808', surface: '#2e1410', primary: '#f97316', secondary: '#ef4444', accent: '#fbbf24', text: '#fef3c7', slice: '#3a1a0f', sliceHover: '#5e2a18', hub: '#281008', subSlice: '#3a1610' },
    effects: { blur: 22, opacity: 65, glow: 0.8, border: 1 }
  },
  {
    name: 'Minimal Light',
    colors: { bg: '#f0f0f5', surface: '#ffffff', primary: '#3b82f6', secondary: '#6366f1', accent: '#06b6d4', text: '#1e293b', slice: '#e8e8f0', sliceHover: '#d0d0e0', hub: '#f5f5fa', subSlice: '#e0e0ea' },
    effects: { blur: 15, opacity: 85, glow: 0.1, border: 0.5 }
  },
  {
    name: 'Amethyst Glow',
    colors: { bg: '#0d0515', surface: '#1a0e2e', primary: '#a855f7', secondary: '#c084fc', accent: '#d946ef', text: '#f3e8ff', slice: '#1e0e3a', sliceHover: '#2d1a5e', hub: '#110828', subSlice: '#16093a' },
    effects: { blur: 28, opacity: 60, glow: 1.0, border: 1 }
  },
];

// ====== DEMO ITEMS FOR PREVIEW ======
const DEMO_ITEMS = [
  { label: 'Browser', icon: 'globe' },
  { label: 'Files', icon: 'folder' },
  { label: 'Apps', icon: 'notepad' },
  { label: 'Settings', icon: 'gear' },
  { label: 'Media', icon: 'music' },
  { label: 'Terminal', icon: 'terminal' },
];

const ICON_PATHS = {
  globe: `<circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="1.5" fill="none"/><path d="M2 12h20M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" stroke="currentColor" stroke-width="1.5" fill="none"/>`,
  folder: `<path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" stroke="currentColor" stroke-width="1.5" fill="none" stroke-linejoin="round"/>`,
  notepad: `<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" stroke="currentColor" stroke-width="1.5" fill="none" stroke-linejoin="round"/><path d="M14 2v6h6M16 13H8M16 17H8M10 9H8" stroke="currentColor" stroke-width="1.5" fill="none" stroke-linecap="round"/>`,
  gear: `<circle cx="12" cy="12" r="3" stroke="currentColor" stroke-width="1.5" fill="none"/>`,
  music: `<path d="M9 18V5l12-2v13" stroke="currentColor" stroke-width="1.5" fill="none" stroke-linecap="round" stroke-linejoin="round"/><circle cx="6" cy="18" r="3" stroke="currentColor" stroke-width="1.5" fill="none"/><circle cx="18" cy="16" r="3" stroke="currentColor" stroke-width="1.5" fill="none"/>`,
  terminal: `<path d="m4 17 6-5-6-5M12 19h8" stroke="currentColor" stroke-width="1.5" fill="none" stroke-linecap="round" stroke-linejoin="round"/>`,
};

// ====== STATE ======
let currentColors = { ...PRESETS[0].colors };
let currentEffects = { ...PRESETS[0].effects };
let currentGeometry = { innerRadius: 68, outerRadius: 185, hubSize: 68, gap: 0 };
let hoveredSlice = -1;

// ====== INIT ======
document.addEventListener('DOMContentLoaded', () => {
  // Header
  const header = document.querySelector('.dm-header');
  if (header) {
    header.addEventListener('mousedown', (e) => {
      if (e.button === 0 && !e.target.closest('.dm-header-controls')) {
        getCurrentWindow().startDragging();
      }
    });
  }

  document.getElementById('dm-close-btn')?.addEventListener('click', () => getCurrentWindow().hide());

  // Collapsible sections
  document.querySelectorAll('.dm-section-title').forEach(title => {
    title.addEventListener('click', () => {
      const section = title.getAttribute('data-toggle');
      const body = document.getElementById(`section-${section}`);
      if (body) body.classList.toggle('open');
    });
  });

  // Render presets
  renderPresets();

  // Bind color inputs
  const colorIds = ['bg', 'surface', 'primary', 'secondary', 'accent', 'text', 'slice', 'slice-hover', 'hub', 'sub-slice'];
  const colorKeys = ['bg', 'surface', 'primary', 'secondary', 'accent', 'text', 'slice', 'sliceHover', 'hub', 'subSlice'];
  colorIds.forEach((id, i) => {
    const input = document.getElementById(`color-${id}`);
    if (input) {
      input.addEventListener('input', () => {
        currentColors[colorKeys[i]] = input.value;
        updatePreview();
      });
    }
  });

  // Bind geometry sliders
  const geomSliders = [
    { id: 'geom-inner-radius', key: 'innerRadius', valId: 'val-inner-radius' },
    { id: 'geom-outer-radius', key: 'outerRadius', valId: 'val-outer-radius' },
    { id: 'geom-hub-size', key: 'hubSize', valId: 'val-hub-size' },
    { id: 'geom-gap', key: 'gap', valId: 'val-gap' },
  ];
  geomSliders.forEach(({ id, key, valId }) => {
    const slider = document.getElementById(id);
    const valSpan = document.getElementById(valId);
    if (slider) {
      slider.addEventListener('input', () => {
        currentGeometry[key] = parseFloat(slider.value);
        if (valSpan) valSpan.textContent = slider.value;
        updatePreview();
      });
    }
  });

  // Bind effect sliders
  const effectSliders = [
    { id: 'effect-blur', key: 'blur', valId: 'val-blur' },
    { id: 'effect-opacity', key: 'opacity', valId: 'val-opacity' },
    { id: 'effect-glow', key: 'glow', valId: 'val-glow' },
    { id: 'effect-border', key: 'border', valId: 'val-border' },
  ];
  effectSliders.forEach(({ id, key, valId }) => {
    const slider = document.getElementById(id);
    const valSpan = document.getElementById(valId);
    if (slider) {
      slider.addEventListener('input', () => {
        currentEffects[key] = parseFloat(slider.value);
        if (valSpan) valSpan.textContent = slider.value;
        updatePreview();
      });
    }
  });

  // Save & Apply
  document.getElementById('btn-save-theme')?.addEventListener('click', saveAndApplyTheme);
  document.getElementById('btn-copy-css')?.addEventListener('click', copyCssToClipboard);
  document.getElementById('btn-ai-generate')?.addEventListener('click', handleAiGenerate);

  // Preview hover
  document.getElementById('preview-svg')?.addEventListener('mousemove', handlePreviewHover);
  document.getElementById('preview-svg')?.addEventListener('mouseleave', () => {
    hoveredSlice = -1;
    updatePreview();
  });

  // Load existing settings theme
  loadCurrentTheme();

  // Initial render
  updatePreview();
});

// ====== PRESETS ======
function renderPresets() {
  const grid = document.getElementById('presets-grid');
  if (!grid) return;
  grid.innerHTML = '';

  PRESETS.forEach((preset, index) => {
    const btn = document.createElement('button');
    btn.className = 'dm-preset-btn';
    btn.innerHTML = `
      <div class="dm-preset-swatch">
        <span style="background:${preset.colors.primary}"></span>
        <span style="background:${preset.colors.secondary}"></span>
        <span style="background:${preset.colors.accent}"></span>
      </div>
      ${preset.name}
    `;
    btn.addEventListener('click', () => applyPreset(index));
    grid.appendChild(btn);
  });
}

function applyPreset(index) {
  const preset = PRESETS[index];
  currentColors = { ...preset.colors };
  currentEffects = { ...preset.effects };

  // Update color inputs
  const colorIds = ['bg', 'surface', 'primary', 'secondary', 'accent', 'text', 'slice', 'slice-hover', 'hub', 'sub-slice'];
  const colorKeys = ['bg', 'surface', 'primary', 'secondary', 'accent', 'text', 'slice', 'sliceHover', 'hub', 'subSlice'];
  colorIds.forEach((id, i) => {
    const input = document.getElementById(`color-${id}`);
    if (input) input.value = currentColors[colorKeys[i]];
  });

  // Update effect sliders
  document.getElementById('effect-blur').value = currentEffects.blur;
  document.getElementById('val-blur').textContent = currentEffects.blur;
  document.getElementById('effect-opacity').value = currentEffects.opacity;
  document.getElementById('val-opacity').textContent = currentEffects.opacity;
  document.getElementById('effect-glow').value = currentEffects.glow;
  document.getElementById('val-glow').textContent = currentEffects.glow;
  document.getElementById('effect-border').value = currentEffects.border;
  document.getElementById('val-border').textContent = currentEffects.border;

  // Highlight active preset
  document.querySelectorAll('.dm-preset-btn').forEach((btn, i) => {
    btn.classList.toggle('active', i === index);
  });

  updatePreview();
}

// ====== PREVIEW RENDERING ======
function getSectorPath(x, y, innerR, outerR, startAngle, endAngle, gap) {
  const gapHalf = (gap || 0) / 2;
  const s = startAngle + gapHalf;
  const e = endAngle - gapHalf;
  if (e <= s) return '';
  const sr = (s - 90) * Math.PI / 180;
  const er = (e - 90) * Math.PI / 180;
  const x1i = x + innerR * Math.cos(sr);
  const y1i = y + innerR * Math.sin(sr);
  const x2i = x + innerR * Math.cos(er);
  const y2i = y + innerR * Math.sin(er);
  const x1o = x + outerR * Math.cos(sr);
  const y1o = y + outerR * Math.sin(sr);
  const x2o = x + outerR * Math.cos(er);
  const y2o = y + outerR * Math.sin(er);
  const la = (e - s) > 180 ? 1 : 0;
  return `M ${x1i} ${y1i} L ${x1o} ${y1o} A ${outerR} ${outerR} 0 ${la} 1 ${x2o} ${y2o} L ${x2i} ${y2i} A ${innerR} ${innerR} 0 ${la} 0 ${x1i} ${y1i} Z`;
}

function updatePreview() {
  const slicesGroup = document.getElementById('preview-slices');
  const iconsGroup = document.getElementById('preview-icons');
  const labelsGroup = document.getElementById('preview-labels');
  const hub = document.getElementById('preview-hub');
  const previewBg = document.getElementById('preview-bg');
  const svg = document.getElementById('preview-svg');

  if (!slicesGroup) return;
  slicesGroup.innerHTML = '';
  iconsGroup.innerHTML = '';
  labelsGroup.innerHTML = '';

  const cx = 300, cy = 300;
  const numItems = DEMO_ITEMS.length;
  const angleStep = 360 / numItems;
  const halfStep = angleStep / 2;
  const innerR = currentGeometry.innerRadius;
  const outerR = currentGeometry.outerRadius;
  const iconR = innerR + (outerR - innerR) * 0.38;
  const labelR = innerR + (outerR - innerR) * 0.72;
  const gap = currentGeometry.gap;

  // Background color
  previewBg.style.background = `radial-gradient(circle at center, ${currentColors.surface}80 0%, ${currentColors.bg} 70%)`;

  // Hub
  hub.setAttribute('r', currentGeometry.hubSize);
  hub.style.fill = `${currentColors.hub}e6`;
  hub.style.stroke = `${currentColors.primary}30`;
  hub.style.strokeWidth = currentEffects.border;

  // Hub labels
  const hubLabel = document.getElementById('preview-hub-label');
  const hubSublabel = document.getElementById('preview-hub-sublabel');
  hubLabel.style.fill = currentColors.text;
  hubSublabel.style.fill = `${currentColors.text}66`;

  // Glow
  const glowFilter = document.querySelector('#preview-glow feGaussianBlur');
  if (glowFilter) glowFilter.setAttribute('stdDeviation', currentEffects.glow * 4);
  svg.style.filter = `drop-shadow(0 0 ${currentEffects.glow * 30}px ${currentColors.primary}40)`;

  // Slices
  DEMO_ITEMS.forEach((item, index) => {
    const startAngle = index * angleStep - halfStep;
    const endAngle = (index + 1) * angleStep - halfStep;
    const midAngle = startAngle + halfStep;
    const midRad = (midAngle - 90) * Math.PI / 180;

    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', getSectorPath(cx, cy, innerR, outerR, startAngle, endAngle, gap));
    path.setAttribute('class', 'preview-slice');
    path.dataset.index = index;

    const isHovered = hoveredSlice === index;
    path.style.fill = isHovered
      ? `${currentColors.sliceHover}cc`
      : `${currentColors.slice}b3`;
    path.style.stroke = isHovered
      ? `${currentColors.primary}40`
      : `${currentColors.text}14`;
    path.style.strokeWidth = currentEffects.border;

    if (isHovered) path.classList.add('hovered');
    slicesGroup.appendChild(path);

    // Icon
    const iconX = cx + iconR * Math.cos(midRad);
    const iconY = cy + iconR * Math.sin(midRad);
    const iconSvg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    iconSvg.setAttribute('viewBox', '0 0 24 24');
    iconSvg.setAttribute('width', '22');
    iconSvg.setAttribute('height', '22');
    iconSvg.setAttribute('x', iconX - 11);
    iconSvg.setAttribute('y', iconY - 11);
    iconSvg.setAttribute('class', 'preview-icon');
    iconSvg.style.stroke = isHovered ? currentColors.primary : `${currentColors.text}b3`;
    iconSvg.innerHTML = ICON_PATHS[item.icon] || ICON_PATHS['globe'];
    iconsGroup.appendChild(iconSvg);

    // Label
    const label = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    label.setAttribute('x', cx + labelR * Math.cos(midRad));
    label.setAttribute('y', cy + labelR * Math.sin(midRad) + 2);
    label.setAttribute('class', 'preview-label');
    label.style.fill = isHovered ? currentColors.text : `${currentColors.text}99`;
    label.textContent = item.label;
    labelsGroup.appendChild(label);

    // Sub-slices for hovered
    if (isHovered) {
      const subGroup = document.getElementById('preview-sub-slices');
      subGroup.innerHTML = '';
      const subInner = outerR + 10;
      const subOuter = outerR + 75;
      for (let s = 0; s < 3; s++) {
        const subAngleStep = angleStep / 3;
        const subStart = startAngle + s * subAngleStep;
        const subEnd = startAngle + (s + 1) * subAngleStep;
        const subPath = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        subPath.setAttribute('d', getSectorPath(cx, cy, subInner, subOuter, subStart, subEnd, gap));
        subPath.setAttribute('class', 'preview-sub-slice visible');
        subPath.style.fill = `${currentColors.subSlice}99`;
        subPath.style.stroke = `${currentColors.text}10`;
        subGroup.appendChild(subPath);
      }
    }
  });
}

function handlePreviewHover(e) {
  const svg = document.getElementById('preview-svg');
  const rect = svg.getBoundingClientRect();
  const scaleX = 600 / rect.width;
  const scaleY = 600 / rect.height;
  const mx = (e.clientX - rect.left) * scaleX;
  const my = (e.clientY - rect.top) * scaleY;

  const dx = mx - 300;
  const dy = my - 300;
  const dist = Math.sqrt(dx * dx + dy * dy);

  if (dist < currentGeometry.innerRadius || dist > currentGeometry.outerRadius + 80) {
    if (hoveredSlice !== -1) {
      hoveredSlice = -1;
      document.getElementById('preview-sub-slices').innerHTML = '';
      updatePreview();
    }
    return;
  }

  let angle = Math.atan2(dy, dx) * 180 / Math.PI;
  if (angle < 0) angle += 360;
  const adjustedAngle = (angle + 90) % 360;
  const angleStep = 360 / DEMO_ITEMS.length;
  const halfStep = angleStep / 2;
  const shiftedAngle = (adjustedAngle + halfStep) % 360;
  const newIndex = Math.floor(shiftedAngle / angleStep);

  if (newIndex !== hoveredSlice) {
    hoveredSlice = newIndex;
    updatePreview();
  }
}

// ====== CSS GENERATION ======
function generateCssVars() {
  const hexAlpha = (hex, alphaPercent) => {
    const alphaHex = Math.round((alphaPercent / 100) * 255).toString(16).padStart(2, '0');
    return `${hex}${alphaHex}`;
  };

  return `--bg: ${currentColors.bg};
  --surface: ${currentColors.surface};
  --primary: ${currentColors.primary};
  --secondary: ${currentColors.secondary};
  --accent: ${currentColors.accent};
  --text: ${currentColors.text};
  
  --bg-overlay: ${hexAlpha(currentColors.bg, currentEffects.opacity)};
  --slice-bg: ${hexAlpha(currentColors.slice, 70)};
  --slice-stroke: ${hexAlpha(currentColors.text, 8)};
  --slice-active-bg: ${hexAlpha(currentColors.sliceHover, 80)};
  --slice-active-stroke: ${hexAlpha(currentColors.primary, 80)};
  --slice-active-shadow: ${hexAlpha(currentColors.primary, Math.min(100, currentEffects.glow * 40))};
  --slice-icon-color: ${hexAlpha(currentColors.text, 60)};
  --slice-icon-active: ${currentColors.primary};
  --slice-text-color: ${hexAlpha(currentColors.text, 65)};
  --slice-text-active: ${currentColors.primary};
  --slice-text-shadow: ${hexAlpha(currentColors.primary, Math.min(100, currentEffects.glow * 50))};
  
  --hub-bg: ${hexAlpha(currentColors.hub, 90)};
  --hub-border: ${hexAlpha(currentColors.primary, 30)};
  --hub-shadow: rgba(0, 0, 0, 0.6);
  --hub-shadow-glow: ${hexAlpha(currentColors.primary, Math.min(100, currentEffects.glow * 20))};
  --hub-shadow-active: ${hexAlpha(currentColors.primary, Math.min(100, currentEffects.glow * 40))};
  --hub-border-active: ${currentColors.primary};
  --hub-icon-color: ${currentColors.primary};
  --hub-label-color: ${currentColors.text};
  --hub-label-shadow: transparent;
  --hub-sublabel-color: ${currentColors.secondary};
  
  --orbital-ring-stroke: ${hexAlpha(currentColors.primary, 20)};
  --orbital-ring-2-stroke: ${hexAlpha(currentColors.secondary, 20)};
  
  --sub-dot-bg: ${hexAlpha(currentColors.primary, 30)};
  --sub-dot-active: ${currentColors.primary};
  
  --sub-slice-bg: ${hexAlpha(currentColors.subSlice, 80)};
  --sub-slice-stroke: ${hexAlpha(currentColors.text, 10)};
  --sub-slice-active-bg: ${hexAlpha(currentColors.subSlice, 90)};
  --sub-slice-active-stroke: ${currentColors.primary};
  --sub-slice-active-shadow: ${hexAlpha(currentColors.primary, 30)};
  --sub-slice-text: ${hexAlpha(currentColors.text, 60)};
  --sub-slice-text-active: ${currentColors.text};
  --sub-slice-text-shadow: transparent;
  
  --media-bg: ${hexAlpha(currentColors.hub, 95)};
  --media-border: ${hexAlpha(currentColors.primary, 30)};
  --media-shadow: rgba(0, 0, 0, 0.5);
  --media-text: ${currentColors.text};
  --media-subtext: ${currentColors.secondary};
  --media-btn-color: ${hexAlpha(currentColors.text, 70)};
  --media-btn-hover: ${currentColors.text};
  --media-btn-hover-bg: ${hexAlpha(currentColors.primary, 10)};
  --media-btn-play-bg: ${hexAlpha(currentColors.primary, 20)};
  --media-btn-play-border: ${currentColors.primary};
  --media-progress-bg: ${hexAlpha(currentColors.primary, 20)};
  
  --search-bg: ${hexAlpha(currentColors.hub, 95)};
  --search-border: ${hexAlpha(currentColors.primary, 30)};
  --search-shadow: rgba(0, 0, 0, 0.5);
  --search-text: ${currentColors.text};
  --search-placeholder: ${hexAlpha(currentColors.text, 40)};
  --search-item-bg: ${hexAlpha(currentColors.bg, 40)};
  --search-item-color: ${hexAlpha(currentColors.text, 65)};
  --search-item-hover-bg: ${hexAlpha(currentColors.primary, 20)};
  --search-item-hover-color: ${currentColors.text};
  
  --ripple-stroke: ${currentColors.primary};`;
}

function hexToRgb(hex) {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return `${r}, ${g}, ${b}`;
}

async function saveAndApplyTheme() {
  const nameInput = document.getElementById('theme-name-input');
  let name = nameInput.value.trim().toLowerCase().replace(/\s+/g, '-').replace(/[^a-z0-9-]/g, '');
  if (!name) {
    name = 'custom-' + Date.now().toString(36);
    nameInput.value = name;
  }

  const cssVars = generateCssVars();

  try {
    const current = await invoke('get_settings');
    if (!current) { showToast('Failed to load settings'); return; }

    let customThemes = current.custom_themes || [];
    const existingIndex = customThemes.findIndex(t => t.name === name);
    const themeObj = { name, css_vars: cssVars };
    if (existingIndex >= 0) {
      customThemes[existingIndex] = themeObj;
    } else {
      customThemes.push(themeObj);
    }
    current.custom_themes = customThemes;
    current.theme = name;
    current.accent_color = currentColors.primary;

    await invoke('save_settings', { settings: current });
    showToast(`Theme "${name}" saved & applied!`);
  } catch (e) {
    showToast('Error: ' + e);
  }
}

function copyCssToClipboard() {
  const css = generateCssVars();
  navigator.clipboard.writeText(css);
  showToast('CSS variables copied to clipboard!');
}

// ====== AI THEME GENERATION ======
async function handleAiGenerate() {
  const promptEl = document.getElementById('ai-theme-prompt');
  const btnEl = document.getElementById('btn-ai-generate');
  const prompt = promptEl.value.trim();
  if (!prompt) return;

  btnEl.disabled = true;
  btnEl.textContent = '⏳ Generating...';

  // AI generates color palette from description using a simple heuristic
  // In a real implementation, this would call the AI API
  const generated = generateThemeFromDescription(prompt);
  currentColors = generated.colors;
  currentEffects = generated.effects;

  // Update all inputs
  const colorIds = ['bg', 'surface', 'primary', 'secondary', 'accent', 'text', 'slice', 'slice-hover', 'hub', 'sub-slice'];
  const colorKeys = ['bg', 'surface', 'primary', 'secondary', 'accent', 'text', 'slice', 'sliceHover', 'hub', 'subSlice'];
  colorIds.forEach((id, i) => {
    const input = document.getElementById(`color-${id}`);
    if (input) input.value = currentColors[colorKeys[i]];
  });

  document.getElementById('effect-blur').value = currentEffects.blur;
  document.getElementById('val-blur').textContent = currentEffects.blur;
  document.getElementById('effect-glow').value = currentEffects.glow;
  document.getElementById('val-glow').textContent = currentEffects.glow;

  updatePreview();
  btnEl.disabled = false;
  btnEl.textContent = '✨ Generate Theme';
  showToast('Theme generated from description!');
}

// Simple heuristic theme generator based on keywords
function generateThemeFromDescription(desc) {
  const d = desc.toLowerCase();
  let primary, secondary, accent, bg, surface;

  // Color keyword mapping
  if (d.includes('red') || d.includes('fire') || d.includes('lava')) {
    primary = '#ef4444'; secondary = '#f97316'; accent = '#fbbf24';
  } else if (d.includes('blue') || d.includes('ocean') || d.includes('sky') || d.includes('ice')) {
    primary = '#3b82f6'; secondary = '#0ea5e9'; accent = '#06d6a0';
  } else if (d.includes('green') || d.includes('forest') || d.includes('nature') || d.includes('matrix')) {
    primary = '#22c55e'; secondary = '#10b981'; accent = '#a3e635';
  } else if (d.includes('purple') || d.includes('violet') || d.includes('amethyst') || d.includes('lavender')) {
    primary = '#a855f7'; secondary = '#8b5cf6'; accent = '#d946ef';
  } else if (d.includes('pink') || d.includes('rose') || d.includes('sakura')) {
    primary = '#ec4899'; secondary = '#f472b6'; accent = '#fb923c';
  } else if (d.includes('gold') || d.includes('amber') || d.includes('honey')) {
    primary = '#f59e0b'; secondary = '#d97706'; accent = '#fcd34d';
  } else if (d.includes('cyan') || d.includes('teal') || d.includes('aqua')) {
    primary = '#06b6d4'; secondary = '#14b8a6'; accent = '#22d3ee';
  } else {
    primary = '#6366f1'; secondary = '#8b5cf6'; accent = '#06b6d4';
  }

  // Dark/Light
  if (d.includes('light') || d.includes('white') || d.includes('bright')) {
    bg = '#f0f0f5'; surface = '#ffffff';
    return {
      colors: { bg, surface, primary, secondary, accent, text: '#1e293b', slice: '#e8e8f0', sliceHover: '#d0d0e0', hub: '#f5f5fa', subSlice: '#e0e0ea' },
      effects: { blur: 15, opacity: 85, glow: d.includes('glow') ? 0.6 : 0.1, border: 0.5 }
    };
  }

  // Neon / Cyberpunk
  const isNeon = d.includes('neon') || d.includes('cyber') || d.includes('glow') || d.includes('electric');
  const glowVal = isNeon ? 1.5 : (d.includes('subtle') ? 0.2 : 0.5);

  bg = darken(primary, 0.92);
  surface = darken(primary, 0.85);

  return {
    colors: {
      bg, surface, primary, secondary, accent,
      text: lighten(primary, 0.9),
      slice: darken(primary, 0.82),
      sliceHover: darken(primary, 0.72),
      hub: darken(primary, 0.88),
      subSlice: darken(primary, 0.84),
    },
    effects: { blur: isNeon ? 30 : 22, opacity: 60, glow: glowVal, border: 1 }
  };
}

function darken(hex, amount) {
  const r = Math.round(parseInt(hex.slice(1, 3), 16) * (1 - amount));
  const g = Math.round(parseInt(hex.slice(3, 5), 16) * (1 - amount));
  const b = Math.round(parseInt(hex.slice(5, 7), 16) * (1 - amount));
  return `#${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}`;
}

function lighten(hex, amount) {
  const r = Math.min(255, Math.round(parseInt(hex.slice(1, 3), 16) + (255 - parseInt(hex.slice(1, 3), 16)) * amount));
  const g = Math.min(255, Math.round(parseInt(hex.slice(3, 5), 16) + (255 - parseInt(hex.slice(3, 5), 16)) * amount));
  const b = Math.min(255, Math.round(parseInt(hex.slice(5, 7), 16) + (255 - parseInt(hex.slice(5, 7), 16)) * amount));
  return `#${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}`;
}

// ====== LOAD CURRENT THEME ======
async function loadCurrentTheme() {
  try {
    const settings = await invoke('get_settings');
    if (settings && settings.accent_color) {
      currentColors.primary = settings.accent_color;
    }
  } catch (e) {}
}

// ====== TOAST ======
function showToast(msg) {
  let toast = document.querySelector('.dm-toast');
  if (!toast) {
    toast = document.createElement('div');
    toast.className = 'dm-toast';
    document.body.appendChild(toast);
  }
  toast.textContent = msg;
  toast.classList.add('show');
  setTimeout(() => toast.classList.remove('show'), 2500);
}

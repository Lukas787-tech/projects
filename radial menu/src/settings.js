window.onerror = function(msg, url, lineNo, columnNo, error) {
  alert("JS Error in settings: " + msg + "\nLine: " + lineNo + "\n" + (error ? error.stack : ""));
  return false;
};

window.onunhandledrejection = function(event) {
  alert("Unhandled Rejection in settings: " + event.reason + "\nStack: " + (event.reason && event.reason.stack ? event.reason.stack : ""));
};

const invoke = (...args) => {
  if (window.__TAURI__ && window.__TAURI__.core) {
    return window.__TAURI__.core.invoke(...args);
  }
  console.warn("Tauri core invoke not available", args);
  return Promise.resolve(null);
};

const getCurrentWindow = () => {
  if (window.__TAURI__) {
    if (window.__TAURI__.webviewWindow) {
      return window.__TAURI__.webviewWindow.getCurrentWebviewWindow();
    }
    if (window.__TAURI__.window) {
      return window.__TAURI__.window.getCurrentWindow();
    }
  }
  return {
    hide: () => console.log("Window hide called"),
    startDragging: () => console.log("Window startDragging called")
  };
};

let localSettings = null;

// The available icon options for dropdowns
const iconOptions = [
  { value: 'globe', label: '🌐 Web' },
  { value: 'folder', label: '📂 Folder' },
  { value: 'notepad', label: '📝 Note' },
  { value: 'gear', label: '⚙️ Settings' },
  { value: 'lock', label: '🔒 Lock' },
  { value: 'music', label: '🎵 Music' },
  { value: 'terminal', label: '💻 Shell' },
  { value: 'sparkle', label: '✦ AI Assistant' }
];

function updateSliderLabels() {
  const blurSlider = document.getElementById('blur-slider');
  const blurVal = document.getElementById('blur-val');
  blurVal.textContent = blurSlider.value;

  const scaleSlider = document.getElementById('scale-slider');
  const scaleVal = document.getElementById('scale-val');
  scaleVal.textContent = scaleSlider.value;

  const animSpeedSlider = document.getElementById('anim-speed-slider');
  if (animSpeedSlider) {
    const animSpeedVal = document.getElementById('anim-speed-val');
    animSpeedVal.textContent = animSpeedSlider.value;
  }

  const bgOpacitySlider = document.getElementById('bg-opacity-slider');
  if (bgOpacitySlider) {
    const bgOpacityVal = document.getElementById('bg-opacity-val');
    bgOpacityVal.textContent = bgOpacitySlider.value;
  }
}

// ==========================================
// TAB SWITCHING
// ==========================================
function initTabs() {
  const tabButtons = document.querySelectorAll('.tab-button');
  const tabs = document.querySelectorAll('.settings-tab');
  const indicator = document.querySelector('.tab-indicator');

  tabButtons.forEach((btn, index) => {
    btn.addEventListener('click', () => {
      // Hide all tabs
      tabs.forEach(tab => tab.classList.remove('active'));
      
      // Remove active class from all buttons
      tabButtons.forEach(b => b.classList.remove('active'));
      
      // Show selected tab
      const tabName = btn.dataset.tab;
      const tab = document.querySelector(`.settings-tab[data-tab="${tabName}"]`);
      if (tab) tab.classList.add('active');
      
      // Mark button as active
      btn.classList.add('active');
      
      // Move indicator
      const left = btn.offsetLeft;
      const width = btn.offsetWidth;
      indicator.style.left = left + 'px';
      indicator.style.width = width + 'px';

      // Lazy load Monaco / Scripts / Events / Plugins
      if (tabName === 'editor') {
        initMonaco();
        loadScripts();
      } else if (tabName === 'planner') {
        loadEvents();
      } else if (tabName === 'plugins') {
        loadPlugins();
      }
    });
  });

  // Set initial indicator position
  const activeBtn = document.querySelector('.tab-button.active');
  if (activeBtn) {
    const left = activeBtn.offsetLeft;
    const width = activeBtn.offsetWidth;
    indicator.style.left = left + 'px';
    indicator.style.width = width + 'px';
  }
}



function renderActionsEditor(actions) {
  const listContainer = document.getElementById('actions-list');
  listContainer.innerHTML = '';

  actions.forEach((action, index) => {
    const row = document.createElement('div');
    row.className = 'action-row';
    row.dataset.id = action.id;

    // Slot number
    const slotNum = document.createElement('div');
    slotNum.className = 'slot-num';
    slotNum.textContent = `#${index + 1}`;

    // Label input
    const labelInput = document.createElement('input');
    labelInput.type = 'text';
    labelInput.className = 'form-control';
    labelInput.value = action.label;
    labelInput.placeholder = 'Action Label';
    labelInput.id = `label-${index}`;

    // Icon select
    const iconSelect = document.createElement('select');
    iconSelect.className = 'form-control';
    iconSelect.id = `icon-${index}`;
    iconOptions.forEach(opt => {
      const option = document.createElement('option');
      option.value = opt.value;
      option.textContent = opt.label;
      if (opt.value === action.icon) {
        option.selected = true;
      }
      iconSelect.appendChild(option);
    });

    // Command input
    const cmdInput = document.createElement('input');
    cmdInput.type = 'text';
    cmdInput.className = 'form-control';
    cmdInput.value = action.cmd;
    cmdInput.placeholder = 'Executable/Cmd';
    cmdInput.id = `cmd-${index}`;
    // Lock settings slot executable since it is handled internally
    if (action.id === 'settings') {
      cmdInput.disabled = true;
      cmdInput.value = 'settings (internal)';
    }

    // Args input
    const argsInput = document.createElement('input');
    argsInput.type = 'text';
    argsInput.className = 'form-control';
    argsInput.value = action.args.join(' ');
    argsInput.placeholder = 'Arguments (space separated)';
    argsInput.id = `args-${index}`;
    if (action.id === 'settings') {
      argsInput.disabled = true;
    }

    // Sub-items toggle button
    const toggleBtn = document.createElement('button');
    toggleBtn.className = 'btn-sub-toggle';
    toggleBtn.id = `btn-sub-toggle-${index}`;
    const subCount = action.subItems ? action.subItems.filter(s => s.label.trim() !== '').length : 0;
    toggleBtn.innerHTML = `⚙️ Sub-items (${subCount})`;

    row.appendChild(slotNum);
    row.appendChild(labelInput);
    row.appendChild(iconSelect);
    row.appendChild(cmdInput);
    row.appendChild(argsInput);
    row.appendChild(toggleBtn);

    // Render expandable sub-items editor container
    const subEditor = document.createElement('div');
    subEditor.className = 'sub-items-editor';
    subEditor.id = `sub-editor-${index}`;

    const subHeader = document.createElement('div');
    subHeader.className = 'sub-items-header';
    subHeader.innerHTML = `
      <span class="sub-items-title">Sub-items for ${action.label || 'Slot ' + (index + 1)}</span>
      <span class="sub-items-desc">Hover past this slice to select nested options (max 6)</span>
    `;
    subEditor.appendChild(subHeader);

    // Render exactly 6 sub-item input rows
    for (let subIdx = 0; subIdx < 6; subIdx++) {
      const subItem = (action.subItems && action.subItems[subIdx]) || { label: '', cmd: '', args: [] };

      const subRow = document.createElement('div');
      subRow.className = 'sub-item-row';

      const subNum = document.createElement('div');
      subNum.className = 'sub-item-num';
      subNum.textContent = `${subIdx + 1}`;

      const subLabelInput = document.createElement('input');
      subLabelInput.type = 'text';
      subLabelInput.className = 'form-control';
      subLabelInput.placeholder = 'Label (e.g. YouTube)';
      subLabelInput.value = subItem.label;
      subLabelInput.id = `sub-label-${index}-${subIdx}`;

      const subCmdInput = document.createElement('input');
      subCmdInput.type = 'text';
      subCmdInput.className = 'form-control';
      subCmdInput.placeholder = 'Command (e.g. cmd.exe)';
      subCmdInput.value = subItem.cmd;
      subCmdInput.id = `sub-cmd-${index}-${subIdx}`;

      const subArgsInput = document.createElement('input');
      subArgsInput.type = 'text';
      subArgsInput.className = 'form-control';
      subArgsInput.placeholder = 'Arguments';
      subArgsInput.value = (subItem.args || []).join(' ');
      subArgsInput.id = `sub-args-${index}-${subIdx}`;

      subRow.appendChild(subNum);
      subRow.appendChild(subLabelInput);
      subRow.appendChild(subCmdInput);
      subRow.appendChild(subArgsInput);

      subEditor.appendChild(subRow);
    }

    listContainer.appendChild(row);
    listContainer.appendChild(subEditor);

    // Toggle click handler
    toggleBtn.addEventListener('click', (e) => {
      e.preventDefault();
      const isExpanded = subEditor.classList.contains('expanded');

      // Collapse all other editors
      document.querySelectorAll('.sub-items-editor').forEach(editor => {
        if (editor !== subEditor) editor.classList.remove('expanded');
      });
      document.querySelectorAll('.btn-sub-toggle').forEach(btn => {
        if (btn !== toggleBtn) btn.classList.remove('active');
      });

      if (isExpanded) {
        subEditor.classList.remove('expanded');
        toggleBtn.classList.remove('active');
      } else {
        subEditor.classList.add('expanded');
        toggleBtn.classList.add('active');
      }
    });
  });
}

function getContrastColor(hexColor) {
  if (!hexColor) return '#ffffff';
  let hex = hexColor.replace('#', '');
  if (hex.length === 3) {
    hex = hex[0] + hex[0] + hex[1] + hex[1] + hex[2] + hex[2];
  }
  if (hex.length !== 6) return '#ffffff';
  const r = parseInt(hex.substring(0, 2), 16);
  const g = parseInt(hex.substring(2, 4), 16);
  const b = parseInt(hex.substring(4, 6), 16);
  const yiq = ((r * 299) + (g * 587) + (b * 114)) / 1000;
  return (yiq >= 150) ? '#121214' : '#ffffff';
}

function applyTheme(settings) {
  // Inject Custom Themes first
  injectCustomThemes(settings);

  // Remove all previous theme classes
  document.body.className = '';
  if (settings.theme) {
    document.body.classList.add(`theme-${settings.theme}`);
  }
  
  const root = document.documentElement;
  const accent = settings.accent_color || '#007aff';
  root.style.setProperty('--accent-color', accent);
  root.style.setProperty('--accent-text', getContrastColor(accent));
  root.style.setProperty('--blur-intensity', settings.blur_intensity || '25');
  root.style.setProperty('--bg-opacity', settings.bg_opacity || '0.75');
}

async function loadSettings() {
  try {
    let settings = null;
    let retries = 0;
    while (!settings && retries < 20) {
      settings = await invoke('get_settings');
      if (!settings) {
        await new Promise(resolve => setTimeout(resolve, 50));
        retries++;
      }
    }
    if (!settings) {
      showStatus('Failed to load settings from backend.', 'error');
      return;
    }
    localSettings = settings;
    applyTheme(settings);

    // Set general fields
    document.getElementById('hotkey-input').value = settings.hotkey;
    document.getElementById('assistant-hotkey-input').value = settings.assistant_hotkey || 'Control+Shift+A';      
      if (settings.theme) {
        document.getElementById('theme-select').value = settings.theme;
      }
    
    const blurSlider = document.getElementById('blur-slider');
    blurSlider.value = settings.blur_intensity;
    
    const scaleSlider = document.getElementById('scale-slider');
    scaleSlider.value = settings.scale;

    // Set autostart field (defaulting to true)
    const autostartToggle = document.getElementById('autostart-toggle');
    if (autostartToggle) {
      autostartToggle.checked = settings.autostart !== false;
    }

    // Set appearance fields
    const animSpeedSlider = document.getElementById('anim-speed-slider');
    if (animSpeedSlider) {
      animSpeedSlider.value = settings.animation_speed || 1.0;
    }

    const bgOpacitySlider = document.getElementById('bg-opacity-slider');
    if (bgOpacitySlider) {
      bgOpacitySlider.value = (settings.bg_opacity || 0.65) * 100;
    }

    const accentColor = document.getElementById('accent-color');
    if (accentColor) {
      accentColor.value = settings.accent_color || '#007aff';
    }


    updateSliderLabels();

    // Render actions
    renderActionsEditor(settings.actions);
  } catch (err) {
    showStatus(`Failed to load settings: ${err}`, 'error');
  }
}

function showStatus(message, type) {
  const statusEl = document.getElementById('save-status');
  statusEl.textContent = message;
  statusEl.className = `save-status visible ${type}`;

  setTimeout(() => {
    statusEl.classList.remove('visible');
  }, 4000);
}

async function saveSettings() {
  if (!localSettings) return;

  try {
    const hotkey = document.getElementById('hotkey-input').value || 'Control+Q';
    const assistant_hotkey = document.getElementById('assistant-hotkey-input').value || 'Control+Shift+A';
    const theme = document.getElementById('theme-select').value;
    const blur_intensity = parseInt(document.getElementById('blur-slider').value, 10);
    const scale = parseFloat(document.getElementById('scale-slider').value);

    const animation_speed = parseFloat(document.getElementById('anim-speed-slider')?.value || '1.0');
    const bg_opacity = parseFloat((document.getElementById('bg-opacity-slider')?.value || '65') / 100);
    const accent_color = document.getElementById('accent-color')?.value || '#007aff';

    // Build actions list
    const actions = localSettings.actions.map((act, index) => {
      const label = document.getElementById(`label-${index}`).value;
      const icon = document.getElementById(`icon-${index}`).value;
      
      // Keep cmd unchanged for settings slot, otherwise read input
      let cmd = act.cmd;
      if (act.id !== 'settings') {
        cmd = document.getElementById(`cmd-${index}`).value;
      }

      // Read args
      let args = [];
      if (act.id !== 'settings') {
        const rawArgs = document.getElementById(`args-${index}`).value.trim();
        args = rawArgs === '' ? [] : rawArgs.split(/\s+/);
      }

      // Retrieve sub-items for this slot (6 slots now)
      const subItems = [];
      for (let subIdx = 0; subIdx < 6; subIdx++) {
        const subLabel = document.getElementById(`sub-label-${index}-${subIdx}`).value.trim();
        const subCmd = document.getElementById(`sub-cmd-${index}-${subIdx}`).value.trim();
        const rawSubArgs = document.getElementById(`sub-args-${index}-${subIdx}`).value.trim();

        if (subLabel !== '') {
          subItems.push({
            label: subLabel,
            cmd: subCmd,
            args: rawSubArgs === '' ? [] : rawSubArgs.split(/\s+/)
          });
        }
      }

      return {
        id: act.id,
        label,
        icon,
        cmd,
        args,
        subItems
      };
    });

    const autostart = document.getElementById('autostart-toggle')?.checked !== false;

    const newSettings = {
      hotkey,
      assistant_hotkey,
      blur_intensity,
      theme,
      scale,
      animation_speed,
      bg_opacity,
      accent_color,
      actions,
      autostart,
      gemini_key: localSettings.gemini_key || ''
    };

    await invoke('save_settings', { settings: newSettings });
    localSettings = newSettings;
    applyTheme(newSettings);
    
    // Reload editor to refresh sub-item counts
    renderActionsEditor(newSettings.actions);
    
    showStatus('Settings saved & applied successfully!', 'success');
  } catch (err) {
    showStatus(`Error saving: ${err}`, 'error');
  }
}

window.addEventListener('DOMContentLoaded', () => {
  loadSettings();
  
  function setupHotkeyCapture() {
    const inputs = document.querySelectorAll('.hotkey-capture');
    inputs.forEach(input => {
      input.addEventListener('keydown', (e) => {
        e.preventDefault();
        e.stopPropagation();
        
        // Ignore bare modifiers
        if (['Control', 'Shift', 'Alt', 'Meta', 'AltGraph'].includes(e.key)) {
          return;
        }

        let parts = [];
        if (e.ctrlKey) parts.push('Control');
        if (e.altKey) parts.push('Alt');
        if (e.shiftKey) parts.push('Shift');
        if (e.metaKey) parts.push('Super');
        
        let keyName = e.key;
        if (keyName === ' ') keyName = 'Space';
        else if (keyName.length === 1) keyName = keyName.toUpperCase();
        
        parts.push(keyName);
        input.value = parts.join('+');
      });
    });
  }
  setupHotkeyCapture();
  initTabs();

  // Programmatic window dragging
  const header = document.querySelector('.settings-header');
  if (header) {
    header.addEventListener('mousedown', (e) => {
      if (e.button === 0 && !e.target.closest('.header-controls') && !e.target.closest('button')) {
        getCurrentWindow().startDragging();
      }
    });
  }

  // Settings window close control
  const closeBtn = document.getElementById('settings-close-btn');
  if (closeBtn) {
    closeBtn.addEventListener('click', () => {
      getCurrentWindow().hide();
    });
  }

  // Listen for config-updated event
  if (window.__TAURI__ && window.__TAURI__.event) {
    window.__TAURI__.event.listen('config-updated', (event) => {
      if (event.payload) {
        applyTheme(event.payload);
      }
    });
  }

  // Range slider listeners
  document.getElementById('blur-slider').addEventListener('input', updateSliderLabels);
  document.getElementById('scale-slider').addEventListener('input', updateSliderLabels);
  
  const animSpeedSlider = document.getElementById('anim-speed-slider');
  if (animSpeedSlider) {
    animSpeedSlider.addEventListener('input', updateSliderLabels);
  }

  const bgOpacitySlider = document.getElementById('bg-opacity-slider');
  if (bgOpacitySlider) {
    bgOpacitySlider.addEventListener('input', updateSliderLabels);
  }

  // Save button listener
  document.getElementById('btn-save').addEventListener('click', saveSettings);

  // Design Maker button listener
  const designMakerBtn = document.getElementById('btn-open-design-maker');
  if (designMakerBtn) {
    designMakerBtn.addEventListener('click', () => {
      invoke('show_design_maker').catch(e => console.error("Failed to open design maker", e));
    });
  }

  // Script editor save/delete button listeners
  document.getElementById('btn-new-script').addEventListener('click', () => {
    activeScriptName = '';
    document.getElementById('script-filename').value = '';
    if (editorInstance) {
      editorInstance.setValue('# New Script\n');
    }
    document.querySelectorAll('.script-item').forEach(item => item.classList.remove('active'));
  });
  document.getElementById('btn-save-script').addEventListener('click', saveActiveScript);
  document.getElementById('btn-delete-script').addEventListener('click', deleteActiveScript);

  // Calendar prev/next buttons
  document.getElementById('btn-prev-month').addEventListener('click', () => {
    currentMonth--;
    if (currentMonth < 0) {
      currentMonth = 11;
      currentYear--;
    }
    renderCalendar();
  });
  document.getElementById('btn-next-month').addEventListener('click', () => {
    currentMonth++;
    if (currentMonth > 11) {
      currentMonth = 0;
      currentYear++;
    }
    renderCalendar();
  });

  // Event form submission
  document.getElementById('event-form').addEventListener('submit', handleAddEventSubmit);

  // Listen for background updates
  if (window.__TAURI__) {
    const { listen } = window.__TAURI__.event;
    listen('events-updated', () => {
      loadEvents();
    });
  }
});

// ==========================================
// AUTOMATION SCRIPTS & MONACO EDITOR
// ==========================================
let editorInstance = null;
let scriptsList = [];
let activeScriptName = '';

function getEditorValue() {
  if (editorInstance) {
    return editorInstance.getValue();
  }
  const fallback = document.getElementById('script-fallback-textarea');
  return fallback ? fallback.value : '';
}

function setEditorValue(val) {
  if (editorInstance) {
    editorInstance.setValue(val);
  } else {
    const fallback = document.getElementById('script-fallback-textarea');
    if (fallback) {
      fallback.value = val;
    }
  }
}

function initMonaco() {
  const container = document.getElementById('editor-container');
  if (!container || window.editorInstance) return;

  require.config({ paths: { vs: 'https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.39.0/min/vs' } });
  require(['vs/editor/editor.main'], function () {
    const fallback = document.getElementById('script-fallback-textarea');
    const currentValue = fallback ? fallback.value : '';

    editorInstance = monaco.editor.create(container, {
      value: currentValue || '# Write your automation script here...\n# e.g., cmd.exe /c start notepad.exe\n',
      language: 'powershell',
      theme: 'vs-dark',
      automaticLayout: true,
      minimap: { enabled: false }
    });
    window.editorInstance = editorInstance;

    if (fallback) {
      fallback.style.display = 'none';
    }

    // Load active script if any selected before monaco was ready
    if (activeScriptName) {
      const activeScript = scriptsList.find(s => s.name === activeScriptName);
      if (activeScript) {
        editorInstance.setValue(activeScript.content);
        updateEditorLanguage(activeScriptName);
      }
    }
  });
}

function updateEditorLanguage(filename) {
  if (!editorInstance) return;
  const model = editorInstance.getModel();
  let lang = 'powershell';
  if (filename.endsWith('.bat') || filename.endsWith('.cmd')) {
    lang = 'bat';
  } else if (filename.endsWith('.js')) {
    lang = 'javascript';
  }
  monaco.editor.setModelLanguage(model, lang);
}

async function loadScripts() {
  try {
    const scripts = await invoke('get_scripts');
    scriptsList = scripts;
    renderScriptsList();
    populateScriptDropdown();
  } catch (err) {
    showStatus(`Failed to load scripts: ${err}`, 'error');
  }
}

function renderScriptsList() {
  const listEl = document.getElementById('script-list');
  if (!listEl) return;
  listEl.innerHTML = '';

  if (scriptsList.length === 0) {
    listEl.innerHTML = '<li style="padding: 12px; font-size: 0.58rem; color: var(--text-muted); text-align: center;">No scripts found</li>';
    return;
  }

  scriptsList.forEach(script => {
    const li = document.createElement('li');
    li.className = `script-item ${script.name === activeScriptName ? 'active' : ''}`;
    li.textContent = script.name;
    li.title = script.name;
    li.addEventListener('click', () => {
      selectScript(script.name);
    });
    listEl.appendChild(li);
  });
}

function selectScript(name) {
  activeScriptName = name;
  document.getElementById('script-filename').value = name;
  
  // Highlight in list
  document.querySelectorAll('.script-item').forEach(item => {
    item.classList.toggle('active', item.textContent === name);
  });

  const script = scriptsList.find(s => s.name === name);
  if (script) {
    setEditorValue(script.content);
    updateEditorLanguage(name);
  }
}

function populateScriptDropdown() {
  const selectEl = document.getElementById('event-script-select');
  if (!selectEl) return;
  
  selectEl.innerHTML = '<option value="">No Automation Script</option>';
  
  scriptsList.forEach(script => {
    const opt = document.createElement('option');
    opt.value = script.name;
    opt.textContent = script.name;
    selectEl.appendChild(opt);
  });
}

async function saveActiveScript() {
  const filenameEl = document.getElementById('script-filename');
  let filename = filenameEl.value.trim();
  if (!filename) {
    showStatus('Please enter a script filename!', 'error');
    return;
  }
  
  if (!filename.includes('.')) {
    filename += '.ps1';
    filenameEl.value = filename;
  }

  const content = getEditorValue();
  
  try {
    await invoke('save_script', { name: filename, content });
    activeScriptName = filename;
    showStatus('Script saved successfully!', 'success');
    await loadScripts();
  } catch (err) {
    showStatus(`Failed to save script: ${err}`, 'error');
  }
}

async function deleteActiveScript() {
  const filename = document.getElementById('script-filename').value.trim();
  if (!filename) return;

  if (!confirm(`Are you sure you want to delete script '${filename}'?`)) return;

  try {
    await invoke('delete_script', { name: filename });
    activeScriptName = '';
    document.getElementById('script-filename').value = '';
    if (editorInstance) {
      editorInstance.setValue('');
    }
    showStatus('Script deleted!', 'success');
    await loadScripts();
  } catch (err) {
    showStatus(`Failed to delete script: ${err}`, 'error');
  }
}

// ==========================================
// PROJECT PLANNER & CALENDAR
// ==========================================
let currentYear = new Date().getFullYear();
let currentMonth = new Date().getMonth(); // 0-11
let selectedDateStr = formatDateString(new Date());
let eventsList = [];

const monthNames = [
  "January", "February", "March", "April", "May", "June",
  "July", "August", "September", "October", "November", "December"
];

function formatDateString(date) {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

async function loadEvents() {
  try {
    const events = await invoke('get_events');
    eventsList = events;
    renderCalendar();
    renderEventsList();
    loadScripts();
  } catch (err) {
    showStatus(`Failed to load events: ${err}`, 'error');
  }
}

function renderCalendar() {
  const monthYearEl = document.getElementById('calendar-month-year');
  const daysContainer = document.getElementById('calendar-days');
  if (!monthYearEl || !daysContainer) return;

  monthYearEl.textContent = `${monthNames[currentMonth]} ${currentYear}`;
  daysContainer.innerHTML = '';

  const firstDayIndex = new Date(currentYear, currentMonth, 1).getDay();
  const totalDays = new Date(currentYear, currentMonth + 1, 0).getDate();
  const prevMonthTotalDays = new Date(currentYear, currentMonth, 0).getDate();

  // Render empty padding days from previous month
  for (let i = firstDayIndex - 1; i >= 0; i--) {
    const dayEl = document.createElement('div');
    dayEl.className = 'calendar-day other-month';
    dayEl.textContent = prevMonthTotalDays - i;
    daysContainer.appendChild(dayEl);
  }

  // Render current month days
  const today = new Date();
  const todayStr = formatDateString(today);

  for (let day = 1; day <= totalDays; day++) {
    const dayDate = new Date(currentYear, currentMonth, day);
    const dateStr = formatDateString(dayDate);

    const dayEl = document.createElement('div');
    dayEl.className = 'calendar-day';
    dayEl.textContent = day;

    if (dateStr === todayStr) {
      dayEl.classList.add('today');
    }
    if (dateStr === selectedDateStr) {
      dayEl.classList.add('active');
    }

    // Check if this day has events
    const hasEvents = eventsList.some(e => e.date === dateStr);
    if (hasEvents) {
      const dot = document.createElement('div');
      dot.className = 'day-event-dot';
      dayEl.appendChild(dot);
    }

    dayEl.addEventListener('click', () => {
      selectedDateStr = dateStr;
      
      // Update active highlight
      document.querySelectorAll('.calendar-day').forEach(el => el.classList.remove('active'));
      dayEl.classList.add('active');

      renderEventsList();
    });

    daysContainer.appendChild(dayEl);
  }

  // Render next month padding days (up to 42 total slots)
  const totalGridSlots = 42;
  const currentSlots = firstDayIndex + totalDays;
  const nextMonthPadding = totalGridSlots - currentSlots;
  for (let day = 1; day <= nextMonthPadding; day++) {
    const dayEl = document.createElement('div');
    dayEl.className = 'calendar-day other-month';
    dayEl.textContent = day;
    daysContainer.appendChild(dayEl);
  }
}

function renderEventsList() {
  const headerEl = document.getElementById('selected-date-header');
  const listEl = document.getElementById('events-list');
  if (!headerEl || !listEl) return;

  const parsedDate = new Date(selectedDateStr + 'T00:00:00');
  const friendlyDate = parsedDate.toLocaleDateString([], { month: 'long', day: 'numeric', year: 'numeric' });
  headerEl.textContent = `Tasks & Events for ${friendlyDate}`;

  listEl.innerHTML = '';

  const dayEvents = eventsList.filter(e => e.date === selectedDateStr);
  if (dayEvents.length === 0) {
    listEl.innerHTML = '<div style="font-size: 0.58rem; color: var(--text-muted); text-align: center; padding: 16px;">No tasks or events scheduled for this day.</div>';
    return;
  }

  dayEvents.forEach(event => {
    const item = document.createElement('div');
    item.className = `event-item ${event.completed ? 'completed' : ''}`;

    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.className = 'event-checkbox';
    checkbox.checked = event.completed;
    checkbox.addEventListener('change', () => {
      toggleEventCompleted(event.id, checkbox.checked);
    });

    const details = document.createElement('div');
    details.className = 'event-details';

    const title = document.createElement('span');
    title.className = 'event-item-title';
    title.textContent = event.title;

    const desc = document.createElement('span');
    desc.className = 'event-item-desc';
    desc.textContent = event.description || 'No description';

    const meta = document.createElement('div');
    meta.className = 'event-item-meta';

    const time = document.createElement('span');
    time.className = 'event-item-time';
    time.textContent = `⏰ ${event.time}`;
    meta.appendChild(time);

    if (event.script) {
      const badge = document.createElement('span');
      badge.className = 'event-item-script';
      badge.textContent = `⚙️ ${event.script}`;
      meta.appendChild(badge);
    }

    details.appendChild(title);
    details.appendChild(desc);
    details.appendChild(meta);

    const deleteBtn = document.createElement('button');
    deleteBtn.className = 'event-item-delete';
    deleteBtn.textContent = '✕';
    deleteBtn.title = 'Delete Event';
    deleteBtn.addEventListener('click', () => {
      deleteEvent(event.id);
    });

    item.appendChild(checkbox);
    item.appendChild(details);
    item.appendChild(deleteBtn);

    listEl.appendChild(item);
  });
}

async function toggleEventCompleted(id, completed) {
  eventsList = eventsList.map(e => {
    if (e.id === id) {
      return { ...e, completed };
    }
    return e;
  });
  await saveEventsToBackend();
}

async function deleteEvent(id) {
  eventsList = eventsList.filter(e => e.id !== id);
  await saveEventsToBackend();
}

async function saveEventsToBackend() {
  try {
    await invoke('save_events', { events: eventsList });
    renderCalendar();
    renderEventsList();
  } catch (err) {
    showStatus(`Failed to update events: ${err}`, 'error');
  }
}

async function handleAddEventSubmit(e) {
  e.preventDefault();

  const title = document.getElementById('event-title').value.trim();
  const description = document.getElementById('event-desc').value.trim();
  const time = document.getElementById('event-time').value;
  const scriptSelect = document.getElementById('event-script-select');
  const script = scriptSelect.value || null;

  if (!title) return;

  const newEvent = {
    id: Math.random().toString(36).substring(2, 9),
    title,
    description,
    date: selectedDateStr,
    time,
    script,
    completed: false,
    last_run: null
  };

  eventsList.push(newEvent);
  await saveEventsToBackend();

  document.getElementById('event-title').value = '';
  document.getElementById('event-desc').value = '';
  document.getElementById('event-time').value = '12:00';
  scriptSelect.value = '';
}

// ====== CUSTOM THEMES INJECTION ======
function injectCustomThemes(settings) {
  if (!settings || !settings.custom_themes) return;
  let styleEl = document.getElementById('dynamic-custom-themes');
  if (!styleEl) {
    styleEl = document.createElement('style');
    styleEl.id = 'dynamic-custom-themes';
    document.head.appendChild(styleEl);
  }
  let cssStr = '';
  const themeSelect = document.getElementById('theme-select');
  
  // Clean up old custom themes from dropdown
  if (themeSelect) {
    Array.from(themeSelect.options).forEach(opt => {
      if (opt.dataset.custom) opt.remove();
    });
  }

  for (const theme of settings.custom_themes) {
    if (!theme.name || !theme.css_vars) continue;
    cssStr += `
.radial-container.theme-${theme.name}, 
body.theme-${theme.name} {
  ${theme.css_vars}
}
`;
    // Add to dropdown
    if (themeSelect) {
      const opt = document.createElement('option');
      opt.value = theme.name;
      opt.textContent = `Custom: ${theme.name}`;
      opt.dataset.custom = 'true';
      themeSelect.appendChild(opt);
    }
  }
  styleEl.textContent = cssStr;
}

// ==========================================
// PLUGINS
// ==========================================

async function loadPlugins() {
  try {
    const plugins = await invoke('get_plugins');
    renderPluginsList(plugins || []);
  } catch (err) {
    console.error("Failed to load plugins", err);
  }
}

function renderPluginsList(plugins) {
  const container = document.getElementById('plugins-list');
  if (!container) return;
  container.innerHTML = '';

  // Import Plugin button at the top
  const importSection = document.createElement('div');
  importSection.className = 'plugin-import-section';
  importSection.innerHTML = `
    <button class="btn btn-secondary" id="btn-import-plugin" style="margin-bottom: 12px;">📥 Import Plugin from JSON</button>
    <input type="file" id="plugin-import-file" accept=".json" style="display: none;">
  `;
  container.appendChild(importSection);

  const importBtn = importSection.querySelector('#btn-import-plugin');
  const importFile = importSection.querySelector('#plugin-import-file');
  importBtn.addEventListener('click', () => importFile.click());
  importFile.addEventListener('change', async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    try {
      const text = await file.text();
      const result = await invoke('import_plugin', { json: text });
      showStatus(result, 'success');
      loadPlugins();
    } catch (err) {
      showStatus(`Failed to import: ${err}`, 'error');
    }
    importFile.value = '';
  });

  if (plugins.length === 0) {
    container.innerHTML += '<p class="tab-desc" style="text-align: center; margin-top: 20px;">No plugins installed. Use the AI Assistant to build some!</p>';
    return;
  }

  plugins.forEach(plugin => {
    const card = document.createElement('div');
    card.className = 'plugin-card';

    const info = document.createElement('div');
    info.className = 'plugin-info';

    const titleRow = document.createElement('div');
    titleRow.className = 'plugin-title';
    titleRow.textContent = plugin.name;

    if (plugin.enabled) {
      const badge = document.createElement('span');
      badge.className = 'plugin-badge';
      badge.textContent = 'Active';
      titleRow.appendChild(badge);
    }

    // Version + Author metadata
    if (plugin.version || plugin.author) {
      const vBadge = document.createElement('span');
      vBadge.className = 'plugin-meta-badge';
      vBadge.textContent = [plugin.version ? `v${plugin.version}` : '', plugin.author ? `by ${plugin.author}` : ''].filter(Boolean).join(' · ');
      titleRow.appendChild(vBadge);
    }
    
    const desc = document.createElement('div');
    desc.className = 'plugin-desc';
    desc.textContent = plugin.description || 'No description';

    // Feature indicators
    const features = [];
    if (plugin.menu_actions && plugin.menu_actions.length > 0) features.push(`${plugin.menu_actions.length} actions`);
    if (plugin.hooks && Object.keys(plugin.hooks).length > 0) features.push(`${Object.keys(plugin.hooks).length} hooks`);
    if (plugin.custom_themes && plugin.custom_themes.length > 0) features.push(`${plugin.custom_themes.length} themes`);
    if (plugin.keybindings && plugin.keybindings.length > 0) features.push(`${plugin.keybindings.length} keybinds`);
    if (plugin.settings_schema && plugin.settings_schema.length > 0) features.push('configurable');
    if (plugin.injected_js) features.push('JS');
    if (plugin.injected_css) features.push('CSS');
    if (plugin.layout_overrides) features.push('layout');
    if (plugin.icon_definitions && Object.keys(plugin.icon_definitions).length > 0) features.push(`${Object.keys(plugin.icon_definitions).length} icons`);

    if (features.length > 0) {
      const featureRow = document.createElement('div');
      featureRow.className = 'plugin-features';
      featureRow.innerHTML = features.map(f => `<span class="plugin-feature-tag">${f}</span>`).join('');
      info.appendChild(titleRow);
      info.appendChild(desc);
      info.appendChild(featureRow);
    } else {
      info.appendChild(titleRow);
      info.appendChild(desc);
    }

    const actions = document.createElement('div');
    actions.className = 'plugin-actions';

    // Toggle
    const toggleLabel = document.createElement('label');
    toggleLabel.className = 'toggle-switch';
    toggleLabel.style.transform = 'scale(0.8)';
    
    const toggleInput = document.createElement('input');
    toggleInput.type = 'checkbox';
    toggleInput.className = 'toggle-input';
    toggleInput.checked = plugin.enabled;
    toggleInput.addEventListener('change', async () => {
      try {
        await invoke('toggle_plugin', { id: plugin.id, enabled: toggleInput.checked });
        loadPlugins();
      } catch (e) {
        console.error("Failed to toggle plugin", e);
      }
    });

    const toggleSlider = document.createElement('span');
    toggleSlider.className = 'toggle-slider';

    toggleLabel.appendChild(toggleInput);
    toggleLabel.appendChild(toggleSlider);

    // Export button
    const exportBtn = document.createElement('button');
    exportBtn.className = 'icon-btn';
    exportBtn.innerHTML = '📤';
    exportBtn.title = 'Export Plugin';
    exportBtn.addEventListener('click', async () => {
      try {
        const json = await invoke('export_plugin', { id: plugin.id });
        const blob = new Blob([json], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `${plugin.id}.json`;
        a.click();
        URL.revokeObjectURL(url);
        showStatus('Plugin exported!', 'success');
      } catch (e) {
        showStatus(`Export failed: ${e}`, 'error');
      }
    });

    // Settings button (only if plugin has settings_schema)
    if (plugin.settings_schema && plugin.settings_schema.length > 0) {
      const settingsBtn = document.createElement('button');
      settingsBtn.className = 'icon-btn';
      settingsBtn.innerHTML = '⚙️';
      settingsBtn.title = 'Plugin Settings';
      settingsBtn.addEventListener('click', () => {
        togglePluginSettingsPanel(card, plugin);
      });
      actions.appendChild(settingsBtn);
    }

    // Delete btn
    const deleteBtn = document.createElement('button');
    deleteBtn.className = 'icon-btn';
    deleteBtn.innerHTML = '✕';
    deleteBtn.title = 'Delete Plugin';
    deleteBtn.style.color = '#ff3b30';
    deleteBtn.addEventListener('click', async () => {
      if (confirm(`Are you sure you want to delete ${plugin.name}?`)) {
        try {
          await invoke('delete_plugin', { id: plugin.id });
          loadPlugins();
        } catch (e) {
          console.error("Failed to delete plugin", e);
        }
      }
    });

    actions.appendChild(toggleLabel);
    actions.appendChild(exportBtn);
    actions.appendChild(deleteBtn);

    card.appendChild(info);
    card.appendChild(actions);
    container.appendChild(card);
  });
}

// Plugin-specific settings panel
async function togglePluginSettingsPanel(cardEl, plugin) {
  let panel = cardEl.querySelector('.plugin-settings-panel');
  if (panel) {
    panel.remove();
    return;
  }

  panel = document.createElement('div');
  panel.className = 'plugin-settings-panel';

  // Load current settings
  let currentSettings = {};
  try {
    currentSettings = await invoke('get_plugin_settings', { id: plugin.id });
  } catch (e) {
    console.warn('Failed to load plugin settings', e);
  }

  const formFields = [];

  for (const schema of plugin.settings_schema) {
    const value = currentSettings[schema.key] !== undefined ? currentSettings[schema.key] : schema.default_value;
    const fieldId = `plugin-setting-${plugin.id}-${schema.key}`;

    const group = document.createElement('div');
    group.className = 'form-group';
    group.style.marginBottom = '8px';

    const label = document.createElement('label');
    label.textContent = schema.label;
    label.htmlFor = fieldId;
    group.appendChild(label);

    let input;
    if (schema.type === 'toggle') {
      const toggleWrap = document.createElement('label');
      toggleWrap.className = 'toggle-switch';
      toggleWrap.style.transform = 'scale(0.7)';
      input = document.createElement('input');
      input.type = 'checkbox';
      input.className = 'toggle-input';
      input.id = fieldId;
      input.checked = !!value;
      const slider = document.createElement('span');
      slider.className = 'toggle-slider';
      toggleWrap.appendChild(input);
      toggleWrap.appendChild(slider);
      group.appendChild(toggleWrap);
    } else if (schema.type === 'color') {
      input = document.createElement('input');
      input.type = 'color';
      input.className = 'color-picker';
      input.id = fieldId;
      input.value = value || '#ffffff';
      group.appendChild(input);
    } else if (schema.type === 'select') {
      input = document.createElement('select');
      input.className = 'form-control';
      input.id = fieldId;
      (schema.options || []).forEach(opt => {
        const option = document.createElement('option');
        option.value = opt;
        option.textContent = opt;
        if (opt === value) option.selected = true;
        input.appendChild(option);
      });
      group.appendChild(input);
    } else if (schema.type === 'slider') {
      input = document.createElement('input');
      input.type = 'range';
      input.className = 'range-slider';
      input.id = fieldId;
      input.min = schema.min || 0;
      input.max = schema.max || 100;
      input.step = schema.step || 1;
      input.value = value || schema.min || 0;
      group.appendChild(input);
    } else if (schema.type === 'number') {
      input = document.createElement('input');
      input.type = 'number';
      input.className = 'form-control';
      input.id = fieldId;
      input.value = value || 0;
      if (schema.min != null) input.min = schema.min;
      if (schema.max != null) input.max = schema.max;
      if (schema.step != null) input.step = schema.step;
      group.appendChild(input);
    } else {
      input = document.createElement('input');
      input.type = 'text';
      input.className = 'form-control';
      input.id = fieldId;
      input.value = value || '';
      group.appendChild(input);
    }

    formFields.push({ schema, input, fieldId });
    panel.appendChild(group);
  }

  // Save button
  const saveBtn = document.createElement('button');
  saveBtn.className = 'btn btn-primary';
  saveBtn.textContent = 'Save Plugin Settings';
  saveBtn.style.marginTop = '8px';
  saveBtn.addEventListener('click', async () => {
    const newSettings = {};
    for (const { schema, input } of formFields) {
      if (schema.type === 'toggle') {
        newSettings[schema.key] = input.checked;
      } else if (schema.type === 'number' || schema.type === 'slider') {
        newSettings[schema.key] = parseFloat(input.value);
      } else {
        newSettings[schema.key] = input.value;
      }
    }
    try {
      await invoke('save_plugin_settings', { id: plugin.id, settings: newSettings });
      showStatus('Plugin settings saved!', 'success');
    } catch (e) {
      showStatus(`Failed: ${e}`, 'error');
    }
  });
  panel.appendChild(saveBtn);

  cardEl.appendChild(panel);
}

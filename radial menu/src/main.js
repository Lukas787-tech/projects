window.onerror = function(msg, url, lineNo, columnNo, error) {
  alert("JS Error in main: " + msg + "\nLine: " + lineNo + "\n" + (error ? error.stack : ""));
  return false;
};

window.onunhandledrejection = function(event) {
  alert("Unhandled Rejection in main: " + event.reason + "\nStack: " + (event.reason && event.reason.stack ? event.reason.stack : ""));
};

const invoke = (...args) => {
  if (window.__TAURI__ && window.__TAURI__.core) {
    return window.__TAURI__.core.invoke(...args);
  }
  console.warn("Tauri core invoke not available", args);
  return Promise.resolve(null);
};

const listen = (...args) => {
  if (window.__TAURI__ && window.__TAURI__.event) {
    return window.__TAURI__.event.listen(...args);
  }
  console.warn("Tauri event listen not available", args);
  return Promise.resolve(() => {});
};

const iconPaths = {
  globe: `<circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="1.5" fill="none"/><path d="M2 12h20M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" stroke="currentColor" stroke-width="1.5" fill="none"/>`,
  folder: `<path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" stroke="currentColor" stroke-width="1.5" fill="none" stroke-linejoin="round"/>`,
  notepad: `<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" stroke="currentColor" stroke-width="1.5" fill="none" stroke-linejoin="round"/><path d="M14 2v6h6M16 13H8M16 17H8M10 9H8" stroke="currentColor" stroke-width="1.5" fill="none" stroke-linecap="round"/>`,
  gear: `<circle cx="12" cy="12" r="3" stroke="currentColor" stroke-width="1.5" fill="none"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" stroke="currentColor" stroke-width="1.5" fill="none" stroke-linejoin="round"/>`,
  lock: `<rect x="3" y="11" width="18" height="11" rx="2" ry="2" stroke="currentColor" stroke-width="1.5" fill="none"/><path d="M7 11V7a5 5 0 0 1 10 0v4" stroke="currentColor" stroke-width="1.5" fill="none" stroke-linejoin="round"/>`,
  terminal: `<path d="m4 17 6-5-6-5M12 19h8" stroke="currentColor" stroke-width="1.5" fill="none" stroke-linecap="round" stroke-linejoin="round"/>`,
  music: `<path d="M9 18V5l12-2v13" stroke="currentColor" stroke-width="1.5" fill="none" stroke-linecap="round" stroke-linejoin="round"/><circle cx="6" cy="18" r="3" stroke="currentColor" stroke-width="1.5" fill="none"/><circle cx="18" cy="16" r="3" stroke="currentColor" stroke-width="1.5" fill="none"/>`,
  sparkle: `<path d="M12 2l2.5 7.5L22 12l-7.5 2.5L12 22l-2.5-7.5L2 12l7.5-2.5z" stroke="currentColor" stroke-width="1.5" fill="none" stroke-linejoin="round"/>`
};

let items = [];
let currentSelectedIndex = -1;
let currentSubSelectedIndex = -1;
let appSettings = null;
let menuCenterX = 300;
let menuCenterY = 300;
let hasClickedThisSession = false;
let isMenuHiding = false;
let pollInterval = null;
let clockInterval = null;

// Plugin system state
let pluginHooks = {}; // { hookName: [{ pluginId, code }] }
let layoutConfig = {
  innerRadius: 68,
  outerRadius: 185,
  subInnerRadius: 195,
  subOuterRadius: 270,
  hubRadius: 68,
  iconRadius: 118,
  labelRadius: 154,
  gapDegrees: 0,
};

function getSectorPath(x, y, innerRadius, outerRadius, startAngle, endAngle) {
  const gap = layoutConfig.gapDegrees / 2;
  const adjustedStart = startAngle + gap;
  const adjustedEnd = endAngle - gap;
  if (adjustedEnd <= adjustedStart) return '';
  const startRad = (adjustedStart - 90) * Math.PI / 180;
  const endRad = (adjustedEnd - 90) * Math.PI / 180;

  const x1_inner = x + innerRadius * Math.cos(startRad);
  const y1_inner = y + innerRadius * Math.sin(startRad);
  const x2_inner = x + innerRadius * Math.cos(endRad);
  const y2_inner = y + innerRadius * Math.sin(endRad);

  const x1_outer = x + outerRadius * Math.cos(startRad);
  const y1_outer = y + outerRadius * Math.sin(startRad);
  const x2_outer = x + outerRadius * Math.cos(endRad);
  const y2_outer = y + outerRadius * Math.sin(endRad);

  const largeArc = (adjustedEnd - adjustedStart) > 180 ? 1 : 0;

  return `M ${x1_inner} ${y1_inner} 
          L ${x1_outer} ${y1_outer} 
          A ${outerRadius} ${outerRadius} 0 ${largeArc} 1 ${x2_outer} ${y2_outer} 
          L ${x2_inner} ${y2_inner} 
          A ${innerRadius} ${innerRadius} 0 ${largeArc} 0 ${x1_inner} ${y1_inner} Z`;
}

// ==========================================
// CLOCK — live time display in center hub
// ==========================================
function updateClock() {
  if (currentSelectedIndex >= 0) return; // Don't override active selection
  const hubIcon = document.getElementById('hub-icon');
  const hubLabel = document.getElementById('hub-label');
  const hubSublabel = document.getElementById('hub-sublabel');
  if (!hubLabel || !hubSublabel) return;

  const now = new Date();
  const time = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  const date = now.toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' });

  hubIcon.innerHTML = `<svg viewBox="0 0 24 24" width="28" height="28"><circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="1.5" fill="none"/><polyline points="12 6 12 12 16 14" stroke="currentColor" stroke-width="1.5" fill="none" stroke-linecap="round"/></svg>`;
  hubLabel.textContent = time;
  hubSublabel.textContent = date;
}

function startClock() {
  updateClock();
  if (clockInterval) clearInterval(clockInterval);
  clockInterval = setInterval(updateClock, 1000);
}

function stopClock() {
  if (clockInterval) {
    clearInterval(clockInterval);
    clockInterval = null;
  }
}

function setSelectedIndex(index) {
  if (currentSelectedIndex === index) return;

  // Reset previous active elements
  if (currentSelectedIndex >= 0) {
    const prevPath = document.getElementById(`slice-${currentSelectedIndex}`);
    const prevIcon = document.getElementById(`icon-${currentSelectedIndex}`);
    const prevLabel = document.getElementById(`label-${currentSelectedIndex}`);
    if (prevPath) {
      prevPath.classList.remove('active');
      prevPath.style.transform = '';
    }
    if (prevIcon) prevIcon.style.transform = '';
    if (prevLabel) prevLabel.style.transform = '';

    // Deactivate dots for previous slice
    const prevDots = document.querySelectorAll(`.sub-dot[data-slice="${currentSelectedIndex}"]`);
    prevDots.forEach(d => d.classList.remove('active'));
  }

  currentSelectedIndex = index;
  currentSubSelectedIndex = -1;

  const hub = document.getElementById('center-hub');
  const hubIcon = document.getElementById('hub-icon');
  const hubLabel = document.getElementById('hub-label');
  const hubSublabel = document.getElementById('hub-sublabel');

  // Dynamically render sub-slices for the hovered primary slice
  renderSubSlices(index);

  if (index >= 0) {
    stopClock();
    const item = items[index];
    const path = document.getElementById(`slice-${index}`);
    const icon = document.getElementById(`icon-${index}`);
    const label = document.getElementById(`label-${index}`);

    if (path) {
      path.classList.add('active');
      
      // Calculate radial shift direction
      const angleStep = 360 / items.length;
      const midAngle = index * angleStep;
      const midRad = (midAngle - 90) * Math.PI / 180;
      const shiftDist = 6; // px
      const dx = shiftDist * Math.cos(midRad);
      const dy = shiftDist * Math.sin(midRad);

      path.style.transform = `translate(${dx}px, ${dy}px)`;
      if (icon) icon.style.transform = '';
      if (label) label.style.transform = `translate(${dx}px, ${dy}px)`;
    }

    // Activate dots for current slice
    const dots = document.querySelectorAll(`.sub-dot[data-slice="${index}"]`);
    dots.forEach(d => d.classList.add('active'));

    hub.classList.add('active');
    hubIcon.innerHTML = `<svg viewBox="0 0 24 24" width="28" height="28">${iconPaths[item.icon] || ''}</svg>`;
    
    if (item.id === 'media') {
      hubLabel.textContent = "Loading...";
      hubSublabel.textContent = "Media Controls";
      
      invoke('get_current_media_info').then((media) => {
        if (currentSelectedIndex === index) {
          if (media.title && media.title !== 'None' && media.title !== 'Not Supported') {
            hubLabel.textContent = media.title;
            hubSublabel.textContent = media.artist || 'Unknown Artist';
          } else {
            hubLabel.textContent = "No Music Playing";
            hubSublabel.textContent = "Release to Play/Pause";
          }
        }
      }).catch(() => {
        if (currentSelectedIndex === index) {
          hubLabel.textContent = item.label;
          hubSublabel.textContent = "RELEASE TO RUN";
        }
      });
    } else {
      hubLabel.textContent = item.label;
      hubSublabel.textContent = "RELEASE TO RUN";
    }
  } else {
    hub.classList.remove('active');
    startClock();
  }
}

function setSubSelectedIndex(subIndex) {
  if (currentSubSelectedIndex === subIndex) return;

  // Reset previous active sub-slice
  if (currentSubSelectedIndex >= 0) {
    const prevPath = document.getElementById(`sub-slice-${currentSubSelectedIndex}`);
    const prevLabel = document.getElementById(`sub-label-${currentSubSelectedIndex}`);
    if (prevPath) prevPath.classList.remove('active');
    if (prevLabel) prevLabel.classList.remove('active');
  }

  currentSubSelectedIndex = subIndex;

  const hubSublabel = document.getElementById('hub-sublabel');

  if (subIndex >= 0 && currentSelectedIndex >= 0) {
    const path = document.getElementById(`sub-slice-${subIndex}`);
    const label = document.getElementById(`sub-label-${subIndex}`);
    if (path) path.classList.add('active');
    if (label) label.classList.add('active');

    // Update center hub label to show the sub-action triggered
    const parentItem = items[currentSelectedIndex];
    const subItem = parentItem.subItems[subIndex];
    hubSublabel.textContent = `▸ ${subItem.label.toUpperCase()}`;
  } else {
    // Restore primary label
    if (currentSelectedIndex >= 0) {
      hubSublabel.textContent = "RELEASE TO RUN";
    } else {
      hubSublabel.textContent = "Select Action";
    }
  }
}

function renderSlices() {
  const slicesGroup = document.getElementById('slices-group');
  slicesGroup.innerHTML = '';

  if (!items || items.length === 0) return;

  const angleStep = 360 / items.length;
  const halfStep = angleStep / 2;
  items.forEach((item, index) => {
    const startAngle = index * angleStep - halfStep;
    const endAngle = (index + 1) * angleStep - halfStep;
    const midAngle = startAngle + halfStep;
    const midRad = (midAngle - 90) * Math.PI / 180;

    // Create Path (centered at 300, 300) — uses layout config
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', getSectorPath(300, 300, layoutConfig.innerRadius, layoutConfig.outerRadius, startAngle, endAngle));
    path.setAttribute('class', 'menu-slice');
    path.setAttribute('id', `slice-${index}`);

    // Create Icon group (centered at 300, 300)
    const rIcon = layoutConfig.iconRadius;
    const iconX = 300 + rIcon * Math.cos(midRad);
    const iconY = 300 + rIcon * Math.sin(midRad);
    
    const iconGroup = document.createElementNS('http://www.w3.org/2000/svg', 'g');
    iconGroup.setAttribute('class', 'slice-icon-group');
    iconGroup.setAttribute('id', `icon-${index}`);
    
    const iconSvg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    iconSvg.setAttribute('viewBox', '0 0 24 24');
    iconSvg.setAttribute('width', '24');
    iconSvg.setAttribute('height', '24');
    iconSvg.setAttribute('x', iconX - 12);
    iconSvg.setAttribute('y', iconY - 12);
    iconSvg.innerHTML = iconPaths[item.icon] || iconPaths['globe'];
    
    iconGroup.appendChild(iconSvg);

    // Create Label
    const rText = layoutConfig.labelRadius;
    const label = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    label.setAttribute('x', 300 + rText * Math.cos(midRad));
    label.setAttribute('y', 300 + rText * Math.sin(midRad) + 3);
    label.setAttribute('class', 'slice-text');
    label.setAttribute('id', `label-${index}`);
    label.textContent = item.label;

    slicesGroup.appendChild(path);
    slicesGroup.appendChild(iconGroup);
    slicesGroup.appendChild(label);
  });

  // Render sub-item count dots
  renderSubDots();
}

// ==========================================
// SUB-ITEM DOTS — small circles per slice
// ==========================================
function renderSubDots() {
  const dotsGroup = document.getElementById('dots-group');
  if (!dotsGroup) return;
  dotsGroup.innerHTML = '';

  if (!items || items.length === 0) return;

  const angleStep = 360 / items.length;
  items.forEach((item, index) => {
    const subCount = item.subItems ? item.subItems.length : 0;
    if (subCount === 0) return;

    const startAngle = index * angleStep - (angleStep / 2);
    const midAngle = startAngle + (angleStep / 2);
    const midRad = (midAngle - 90) * Math.PI / 180;

    // Place dots along the outer edge of the primary slice
    const dotRadius = 188;
    const dotSpread = 3.5; // degrees between dots
    const totalSpread = (subCount - 1) * dotSpread;
    const startDotAngle = midAngle - totalSpread / 2;

    for (let i = 0; i < subCount; i++) {
      const dotAngle = startDotAngle + i * dotSpread;
      const dotRad = (dotAngle - 90) * Math.PI / 180;
      const dx = 300 + dotRadius * Math.cos(dotRad);
      const dy = 300 + dotRadius * Math.sin(dotRad);

      const dot = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      dot.setAttribute('cx', dx);
      dot.setAttribute('cy', dy);
      dot.setAttribute('r', '1.5');
      dot.setAttribute('class', 'sub-dot');
      dot.setAttribute('data-slice', index);
      dotsGroup.appendChild(dot);
    }
  });
}

function renderSubSlices(primaryIndex) {
  const subGroup = document.getElementById('sub-slices-group');
  subGroup.innerHTML = '';

  if (primaryIndex < 0) return;
  const item = items[primaryIndex];
  if (!item.subItems || item.subItems.length === 0) return;

  const angleStep = 360 / items.length;
  const parentStart = primaryIndex * angleStep - (angleStep / 2);
  const numSubs = item.subItems.length;
  const subAngleStep = angleStep / numSubs;

  const elementsToAnimate = [];

  item.subItems.forEach((sub, subIndex) => {
    const startAngle = parentStart + subIndex * subAngleStep;
    const endAngle = parentStart + (subIndex + 1) * subAngleStep;
    const midAngle = startAngle + subAngleStep / 2;
    const midRad = (midAngle - 90) * Math.PI / 180;

    // Create sub path — uses layout config
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', getSectorPath(300, 300, layoutConfig.subInnerRadius, layoutConfig.subOuterRadius, startAngle, endAngle));
    path.setAttribute('class', 'sub-slice');
    path.setAttribute('id', `sub-slice-${subIndex}`);

    // Create sub label
    const rText = 232;
    const label = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    label.setAttribute('x', 300 + rText * Math.cos(midRad));
    label.setAttribute('y', 300 + rText * Math.sin(midRad) + 3);
    label.setAttribute('class', 'sub-slice-text');
    label.setAttribute('id', `sub-label-${subIndex}`);
    label.textContent = sub.label;

    subGroup.appendChild(path);
    subGroup.appendChild(label);

    elementsToAnimate.push({ path, label });
  });

  // Staggered activation of 'visible' class for smooth Apple-style extension
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      elementsToAnimate.forEach((pair, index) => {
        setTimeout(() => {
          pair.path.classList.add('visible');
          pair.label.classList.add('visible');
        }, index * 25);
      });
    });
  });
}

// ==========================================
// CLICK RIPPLE — visual feedback on click
// ==========================================
function spawnClickRipple() {
  const svg = document.getElementById('radial-svg');
  if (!svg) return;
  const ripple = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
  ripple.setAttribute('cx', '300');
  ripple.setAttribute('cy', '300');
  ripple.setAttribute('r', '68');
  ripple.setAttribute('class', 'click-ripple');
  svg.appendChild(ripple);
  setTimeout(() => ripple.remove(), 500);
}

// ==========================================
// MEDIA BAR
// ==========================================
let mediaInterval = null;

function escapeHtml(text) {
  if (!text) return '';
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

function formatTime(ms) {
  if (isNaN(ms) || ms < 0) return '0:00';
  const totalSecs = Math.floor(ms / 1000);
  const mins = Math.floor(totalSecs / 60);
  const secs = totalSecs % 60;
  return `${mins}:${secs < 10 ? '0' : ''}${secs}`;
}

function startMediaPolling() {
  updateMediaBar();
  if (mediaInterval) clearInterval(mediaInterval);
  mediaInterval = setInterval(updateMediaBar, 1000);
}

function stopMediaPolling() {
  if (mediaInterval) {
    clearInterval(mediaInterval);
    mediaInterval = null;
  }
}

async function updateMediaBar() {
  const mediaBar = document.getElementById('media-bar');
  if (!mediaBar) return;

  try {
    const media = await invoke('get_current_media_info');
    if (media && media.title && media.title !== 'None' && media.title !== 'Not Supported') {
      const isPlaying = media.status === 'Playing';
      
      const currentTime = formatTime(media.position);
      const totalDuration = formatTime(media.duration);
      const progressPercent = media.duration > 0 ? (media.position / media.duration) * 100 : 0;

      const playPauseIcon = isPlaying 
        ? `<svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><rect x="6" y="4" width="4" height="16" rx="1"></rect><rect x="14" y="4" width="4" height="16" rx="1"></rect></svg>`
        : `<svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor" style="transform: translateX(1px);"><polygon points="5 3 19 12 5 21 5 3"></polygon></svg>`;

      mediaBar.innerHTML = `
        <div class="media-track-info">
          <div class="media-icon">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M9 18V5l12-2v13"></path>
              <circle cx="6" cy="18" r="3"></circle>
              <circle cx="18" cy="16" r="3"></circle>
            </svg>
          </div>
          <div class="media-text-container">
            <div class="media-title">${escapeHtml(media.title)}</div>
            <div class="media-artist">${escapeHtml(media.artist || 'Unknown Artist')}</div>
          </div>
        </div>
        
        <div class="media-controls">
          <button class="media-btn prev-btn" data-action="prev" title="Previous Track">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><polygon points="19 20 9 12 19 4 19 20"></polygon><rect x="5" y="4" width="2" height="16"></rect></svg>
          </button>
          <button class="media-btn play-btn" data-action="playpause" title="${isPlaying ? 'Pause' : 'Play'}">
            ${playPauseIcon}
          </button>
          <button class="media-btn next-btn" data-action="next" title="Next Track">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><polygon points="5 4 15 12 5 20 5 4"></polygon><rect x="17" y="4" width="2" height="16"></rect></svg>
          </button>
        </div>

        <div class="media-progress-bar">
          <span>${currentTime}</span>
          <div class="media-progress-track">
            <div class="media-progress-fill" style="width: ${progressPercent}%;"></div>
          </div>
          <span>${totalDuration}</span>
        </div>
      `;
      mediaBar.classList.add('visible');
      mediaBar.style.visibility = 'visible';
    } else {
      mediaBar.classList.remove('visible');
      mediaBar.style.visibility = 'hidden';
    }
  } catch (err) {
    console.error("Error updating media bar:", err);
    mediaBar.classList.remove('visible');
    mediaBar.style.visibility = 'hidden';
  }
}

async function triggerSelectedAction(shouldClose = true) {
  if (currentSelectedIndex >= 0) {
    // Fire on_action_execute hook
    firePluginHook('on_action_execute', { primaryIndex: currentSelectedIndex, subIndex: currentSubSelectedIndex });

    if (currentSubSelectedIndex >= 0) {
      await invoke('execute_sub_action', { 
        primaryIndex: currentSelectedIndex, 
        subIndex: currentSubSelectedIndex 
      });
    } else {
      const actionId = items[currentSelectedIndex].id;
      await invoke('execute_action', { actionId });
    }
    
    if (!shouldClose) {
      // Refresh the media bar and center hub dynamically if it's the media player
      setTimeout(() => {
        updateMediaBar();
        if (currentSelectedIndex >= 0 && items[currentSelectedIndex].id === 'media') {
          invoke('get_current_media_info').then((media) => {
            const hubLabel = document.getElementById('hub-label');
            const hubSublabel = document.getElementById('hub-sublabel');
            if (hubLabel && hubSublabel && media.title && media.title !== 'None' && media.title !== 'Not Supported') {
              hubLabel.textContent = media.title;
              hubSublabel.textContent = media.artist || 'Unknown Artist';
            }
          });
        }
      }, 500);
    }
  }
  
  if (shouldClose) {
    stopClock();
    await invoke('hide_menu');
  }
}

// ==========================================
// KEY POLLING
// ==========================================
function startPolling() {
  if (pollInterval) return;
  pollInterval = setInterval(async () => {
    if (!appSettings) return;
    try {
      const isHeld = await invoke('is_hotkey_held', { hotkey: appSettings.hotkey });
      if (!isHeld) {
        handleKeyRelease();
      }
    } catch (err) {
      console.error("Error polling hotkey state:", err);
    }
  }, 50);
}

function stopPolling() {
  if (pollInterval) {
    clearInterval(pollInterval);
    pollInterval = null;
  }
}

// ==========================================
// SEARCH FUNCTIONALITY
// ==========================================
let isSearchActive = false;
let searchResults = [];
let searchSelectedIndex = 0;

function fuzzyMatch(haystack, needle) {
  needle = needle.toLowerCase();
  haystack = haystack.toLowerCase();
  let charIndex = 0;
  let score = 0;
  for (let i = 0; i < haystack.length; i++) {
    if (haystack[i] === needle[charIndex]) {
      charIndex++;
      score += Math.pow(0.9, i - charIndex);
    }
  }
  return charIndex === needle.length ? score : 0;
}

function buildSearchIndex() {
  const index = [];
  if (!items) return index;
  
  items.forEach((item, primaryIndex) => {
    // Add primary action
    index.push({
      type: 'primary',
      label: item.label,
      primaryIndex,
      subIndex: -1
    });
    
    // Add sub-items
    if (item.subItems && item.subItems.length > 0) {
      item.subItems.forEach((subItem, subIndex) => {
        if (subItem.label.trim()) {
          index.push({
            type: 'sub',
            label: `${item.label} > ${subItem.label}`,
            primaryIndex,
            subIndex,
            rawLabel: subItem.label
          });
        }
      });
    }
  });
  
  return index;
}

function performSearch(query) {
  if (!query.trim()) {
    renderSearchResults([]);
    isSearchActive = false;
    return;
  }

  const index = buildSearchIndex();
  const results = index
    .map(item => ({
      ...item,
      score: fuzzyMatch(item.label, query)
    }))
    .filter(item => item.score > 0)
    .sort((a, b) => b.score - a.score)
    .slice(0, 8);

  searchResults = results;
  searchSelectedIndex = 0;
  renderSearchResults(results);
  isSearchActive = true;
}

function renderSearchResults(results) {
  const resultsContainer = document.getElementById('search-results');
  if (!resultsContainer) return;
  
  resultsContainer.innerHTML = '';
  
  results.forEach((result, index) => {
    const item = document.createElement('div');
    item.className = 'search-result-item';
    if (index === 0) item.classList.add('top-result');
    item.textContent = result.label;
    item.style.animationDelay = `${index * 20}ms`;
    
    item.addEventListener('click', async () => {
      await executeSearchResult(result);
    });
    
    resultsContainer.appendChild(item);
  });
}

async function executeSearchResult(result) {
  const appContainer = document.getElementById('app');
  appContainer.classList.remove('visible');
  
  // Close search
  closeSearch();
  
  // Execute the action
  if (result.subIndex >= 0) {
    await invoke('execute_sub_action', { 
      primaryIndex: result.primaryIndex, 
      subIndex: result.subIndex 
    });
  } else {
    const action = items[result.primaryIndex];
    await invoke('execute_action', { actionId: action.id });
  }
  
  await invoke('hide_menu');
}

function closeSearch() {
  const searchBar = document.getElementById('search-bar');
  const searchInput = document.getElementById('search-input');
  if (searchBar) searchBar.classList.remove('active');
  if (searchInput) searchInput.value = '';
  renderSearchResults([]);
  isSearchActive = false;
  searchResults = [];
  searchSelectedIndex = 0;
}

function updateSearchHighlight() {
  const items = document.querySelectorAll('.search-result-item');
  items.forEach((item, index) => {
    item.classList.toggle('top-result', index === searchSelectedIndex);
  });
}

function handleSearchKeydown(e) {
  if (!isSearchActive) return;
  
  if (e.key === 'ArrowDown') {
    e.preventDefault();
    searchSelectedIndex = (searchSelectedIndex + 1) % searchResults.length;
    updateSearchHighlight();
  } else if (e.key === 'ArrowUp') {
    e.preventDefault();
    searchSelectedIndex = (searchSelectedIndex - 1 + searchResults.length) % searchResults.length;
    updateSearchHighlight();
  } else if (e.key === 'Enter') {
    e.preventDefault();
    if (searchResults[searchSelectedIndex]) {
      executeSearchResult(searchResults[searchSelectedIndex]);
    }
  } else if (e.key === 'Escape') {
    e.preventDefault();
    closeSearch();
  }
}

function handleKeyRelease() {
  if (isMenuHiding) return;
  isMenuHiding = true;
  stopMediaPolling();
  stopPolling();
  stopClock();

  if (hasClickedThisSession) {
    const appContainer = document.getElementById('app');
    if (appContainer) appContainer.classList.remove('visible');
    invoke('hide_menu');
  } else {
    triggerSelectedAction(true);
  }
}

// ==========================================
// CONFIG & INIT
// ==========================================
function applyConfig(config) {
  appSettings = config;
  items = config.actions;

  const appContainer = document.getElementById('app');
  
  // Inject Custom Themes
  injectCustomThemes(config);

  // Apply theme
  appContainer.className = 'radial-container';
  if (config.theme) {
    appContainer.classList.add(`theme-${config.theme}`);
  }

  // Apply scale
  appContainer.style.setProperty('--menu-scale', config.scale.toString());

  // Render sectors
  renderSlices();
  setSelectedIndex(-1);
}

async function init() {
  // Close other instances of the app using PowerShell
  try {
    const powershellCommand = `$parentPid = (Get-WmiObject Win32_Process -Filter "ProcessId = $PID").ParentProcessId; Get-WmiObject Win32_Process -Filter "Name = 'tauri-radial-menu.exe'" | Where-Object { $_.ProcessId -ne $parentPid } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }`;
    await invoke('run_terminal_command', { cmd: powershellCommand });
  } catch (e) {
    console.warn("Single instance cleanup failed:", e);
  }

  let config = null;
  let retries = 0;
  while (!config && retries < 20) {
    config = await invoke('get_settings');
    if (!config) {
      await new Promise(resolve => setTimeout(resolve, 50));
      retries++;
    }
  }

  if (config) {
    // Re-register hotkeys now that old instances are cleared
    try {
      await invoke('save_settings', { settings: config });
    } catch (e) {
      console.warn("Hotkey re-registration failed:", e);
    }
    await applyConfigWithPlugins(config);
  } else {
    console.error("Failed to load settings configuration after retries.");
  }

  listen('config-updated', async (event) => {
    if (event.payload) {
      await applyConfigWithPlugins(event.payload);
    }
  });

  listen('plugins-updated', async () => {
    if (appSettings) {
      const freshConfig = await invoke('get_settings');
      if (freshConfig) {
        await applyConfigWithPlugins(freshConfig);
      }
    }
  });
}

async function applyConfigWithPlugins(config) {
  try {
    const plugins = await invoke('get_plugins');
    // Reset plugin hooks and layout config
    pluginHooks = {};
    layoutConfig = {
      innerRadius: 68, outerRadius: 185, subInnerRadius: 195, subOuterRadius: 270,
      hubRadius: 68, iconRadius: 118, labelRadius: 154, gapDegrees: 0
    };

    if (plugins && plugins.length > 0) {
      // Clean up previous injected css/js
      const oldStyles = document.querySelectorAll('style[data-plugin-id]');
      oldStyles.forEach(s => s.remove());
      window.__INJECTED_PLUGINS__ = window.__INJECTED_PLUGINS__ || new Set();

      for (const plugin of plugins) {
        if (plugin.enabled) {
          // Inject CSS
          if (plugin.injected_css) {
            const styleEl = document.createElement('style');
            styleEl.dataset.pluginId = plugin.id;
            styleEl.textContent = plugin.injected_css;
            document.head.appendChild(styleEl);
          }
          // Inject JS
          if (plugin.injected_js && !window.__INJECTED_PLUGINS__.has(plugin.id)) {
            try {
              const fn = new Function('invoke', 'listen', plugin.injected_js);
              fn(invoke, listen);
              window.__INJECTED_PLUGINS__.add(plugin.id);
            } catch (e) {
              console.error(`Failed to inject JS for plugin ${plugin.id}:`, e);
            }
          }
          // Merge menu actions
          if (plugin.menu_actions) {
            config.actions = config.actions.concat(plugin.menu_actions);
          }
          // Merge custom themes
          if (plugin.custom_themes) {
            config.custom_themes = (config.custom_themes || []).concat(plugin.custom_themes);
          }
          // Merge custom icons
          if (plugin.icon_definitions) {
            Object.assign(iconPaths, plugin.icon_definitions);
          }
          // Register plugin hooks
          if (plugin.hooks) {
            for (const [hookName, code] of Object.entries(plugin.hooks)) {
              if (!pluginHooks[hookName]) pluginHooks[hookName] = [];
              pluginHooks[hookName].push({ pluginId: plugin.id, code });
            }
          }
          // Apply layout overrides (last plugin wins)
          if (plugin.layout_overrides) {
            const lo = plugin.layout_overrides;
            if (lo.inner_radius != null) layoutConfig.innerRadius = lo.inner_radius;
            if (lo.outer_radius != null) layoutConfig.outerRadius = lo.outer_radius;
            if (lo.sub_inner_radius != null) layoutConfig.subInnerRadius = lo.sub_inner_radius;
            if (lo.sub_outer_radius != null) layoutConfig.subOuterRadius = lo.sub_outer_radius;
            if (lo.hub_radius != null) layoutConfig.hubRadius = lo.hub_radius;
            if (lo.icon_radius != null) layoutConfig.iconRadius = lo.icon_radius;
            if (lo.label_radius != null) layoutConfig.labelRadius = lo.label_radius;
            if (lo.gap_degrees != null) layoutConfig.gapDegrees = lo.gap_degrees;
          }
        } else {
          window.__INJECTED_PLUGINS__.delete(plugin.id);
        }
      }
    }
  } catch (e) {
    console.warn("Failed to load plugins:", e);
  }
  applyConfig(config);
}

// ==========================================
// PLUGIN HOOK SYSTEM
// ==========================================
function firePluginHook(hookName, data) {
  const hooks = pluginHooks[hookName];
  if (!hooks || hooks.length === 0) return;
  for (const hook of hooks) {
    try {
      const fn = new Function('data', 'invoke', 'listen', 'items', 'appSettings', hook.code);
      fn(data, invoke, listen, items, appSettings);
    } catch (e) {
      console.error(`Plugin hook '${hookName}' from '${hook.pluginId}' failed:`, e);
    }
  }
}

window.addEventListener('DOMContentLoaded', () => {
  init();

  const appContainer = document.getElementById('app');

  // Advanced Media Bar Event Delegation
  const mediaBar = document.getElementById('media-bar');
  if (mediaBar) {
    mediaBar.addEventListener('click', async (e) => {
      e.stopPropagation();
      const btn = e.target.closest('.media-btn');
      if (!btn) return;
      const action = btn.dataset.action;
      if (action) {
        try {
          await invoke('control_media', { action });
          setTimeout(updateMediaBar, 150);
        } catch (err) {
          console.error("Failed to control media:", err);
        }
      }
    });
    mediaBar.addEventListener('mousedown', (e) => {
      e.stopPropagation();
    });
  }

  // Listen to coordinates from Rust backend
  listen('show-menu', (event) => {
    isMenuHiding = false;
    hasClickedThisSession = false;
    const { x, y } = event.payload;
    menuCenterX = x;
    menuCenterY = y;
    const menuWrapper = document.getElementById('menu-wrapper');
    if (menuWrapper) {
      menuWrapper.style.left = `${x}px`;
      menuWrapper.style.top = `${y}px`;
    }
    setSelectedIndex(-1);
    setSubSelectedIndex(-1);
    closeSearch();
    startMediaPolling();
    startPolling();
    startClock();
    // Fire plugin hook
    firePluginHook('on_menu_open', { x, y });
  });

  // Search functionality
  const searchBar = document.getElementById('search-bar');
  const searchInput = document.getElementById('search-input');
  if (searchInput) {
    searchInput.addEventListener('input', (e) => {
      performSearch(e.target.value);
    });
    
    searchInput.addEventListener('keydown', handleSearchKeydown);
  }

  // Show search bar when typing alphanumeric while menu is open
  window.addEventListener('keydown', (e) => {
    if (!appContainer.classList.contains('visible')) return;
    if (isSearchActive) return; // Search input already active
    
    // Check if typing alphanumeric (not special keys)
    if (e.key.length === 1 && /[a-z0-9 ]/i.test(e.key) && !e.ctrlKey && !e.altKey && !e.metaKey) {
      e.preventDefault();
      if (searchBar) searchBar.classList.add('active');
      if (searchInput) {
        searchInput.value += e.key;
        searchInput.focus();
        performSearch(searchInput.value);
      }
    } else if (e.key === 'Escape') {
      closeSearch();
    }
  });

  // Pure mathematical coordinate tracking for optimal performance & responsiveness
  window.addEventListener('mousemove', (e) => {
    if (!appContainer.classList.contains('visible')) return;

    const dx = e.clientX - menuCenterX;
    const dy = e.clientY - menuCenterY;
    
    // Factor in the menu scale for correct radius matching
    const scale = appSettings ? appSettings.scale : 1.0;
    const distance = Math.sqrt(dx * dx + dy * dy) / scale;
    
    let angle = Math.atan2(dy, dx) * 180 / Math.PI;
    if (angle < 0) angle += 360;

    // Adjust for the 90-degree counter-clockwise offset in SVG rendering (top-aligned first slice)
    const adjustedAngle = (angle + 90) % 360;

    // Resolve selections based on circular radii rings in design space
    if (distance < layoutConfig.hubRadius) {
      // Deadzone: Center Hub
      setSelectedIndex(-1);
      setSubSelectedIndex(-1);
    } else if (distance >= layoutConfig.hubRadius && distance < layoutConfig.subOuterRadius) {
      // Inside active rings, map adjusted angle to primary index
      const angleStep = 360 / items.length;
      const halfStep = angleStep / 2;
      const shiftedAngle = (adjustedAngle + halfStep) % 360;
      const primaryIndex = Math.floor(shiftedAngle / angleStep);
      setSelectedIndex(primaryIndex);
      
      if (distance >= layoutConfig.subInnerRadius) {
        // Secondary ring (Extended sub-slices)
        const parentItem = items[primaryIndex];
        if (parentItem && parentItem.subItems && parentItem.subItems.length > 0) {
          const parentStart = primaryIndex * angleStep - halfStep;
          let relAngle = (adjustedAngle - parentStart) % 360;
          if (relAngle < 0) relAngle += 360;
          
          if (relAngle <= angleStep) {
            const numSubs = parentItem.subItems.length;
            const subIndex = Math.floor((relAngle / angleStep) * numSubs);
            setSubSelectedIndex(subIndex);
          } else {
            setSubSelectedIndex(-1);
          }
        } else {
          setSubSelectedIndex(-1);
        }
      } else {
        // Primary ring only
        setSubSelectedIndex(-1);
      }
    } else {
      // Out of bounds
      setSelectedIndex(-1);
      setSubSelectedIndex(-1);
    }
  });

  // Trigger scale-in transition
  setTimeout(() => {
    appContainer.classList.add('visible');
  }, 50);

  // Listen to focus and blur window events
  window.addEventListener('focus', () => {
    isMenuHiding = false;
    hasClickedThisSession = false;
    appContainer.classList.add('visible');
    setSelectedIndex(-1);
    setSubSelectedIndex(-1);
    startMediaPolling();
    startPolling();
    startClock();
  });

  window.addEventListener('blur', async () => {
    if (appSettings) {
      try {
        const isHeld = await invoke('is_hotkey_held', { hotkey: appSettings.hotkey });
        if (isHeld) {
          return;
        }
      } catch (err) {
        console.error("is_hotkey_held check failed:", err);
      }
    }
    isMenuHiding = true;
    stopMediaPolling();
    stopPolling();
    stopClock();
    appContainer.classList.remove('visible');
    invoke('hide_menu');
    // Fire plugin hook
    firePluginHook('on_menu_close', {});
  });

  // Handle hotkey release
  window.addEventListener('keyup', async (e) => {
    if (appSettings) {
      const isHeld = await invoke('is_hotkey_held', { hotkey: appSettings.hotkey });
      if (!isHeld) {
        handleKeyRelease();
      }
    }
  });

  // Handle click activation
  window.addEventListener('mousedown', (e) => {
    if (e.target.closest('#media-bar') || e.target.closest('#search-bar')) {
      return;
    }
    if (currentSelectedIndex >= 0) {
      hasClickedThisSession = true;
      spawnClickRipple();
      triggerSelectedAction(false);
    } else {
      isMenuHiding = true;
      stopMediaPolling();
      stopPolling();
      stopClock();
      appContainer.classList.remove('visible');
      invoke('hide_menu');
    }
  });
});
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
  for (const theme of settings.custom_themes) {
    if (!theme.name || !theme.css_vars) continue;
    cssStr += `
.radial-container.theme-${theme.name}, 
body.theme-${theme.name} {
  ${theme.css_vars}
}
`;
  }
  styleEl.textContent = cssStr;
}

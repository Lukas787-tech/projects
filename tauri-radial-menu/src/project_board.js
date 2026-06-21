import { invoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import { getCurrentWindow } from '@tauri-apps/api/window';

let tasks = [];
let draggingTaskId = null;
const appWindow = getCurrentWindow();

// ====== INITIALIZATION ======
document.addEventListener('DOMContentLoaded', async () => {
  // Titlebar controls
  document.getElementById('minimize-btn').addEventListener('click', async () => {
    try { await appWindow.minimize(); } catch(e) { console.error(e); }
  });
  document.getElementById('close-btn').addEventListener('click', async () => {
    try { await appWindow.hide(); } catch(e) { console.error(e); }
  });
  
  const titlebar = document.querySelector('.titlebar');
  if (titlebar) {
    titlebar.addEventListener('mousedown', async (e) => {
      if (e.target.tagName !== 'BUTTON') {
        try { await appWindow.startDragging(); } catch(e) { console.error(e); }
      }
    });
  }

  // Load Settings for Theme
  await loadSettings();
  
  // Load Tasks
  await loadTasks();

  // Setup Event Listeners
  setupEvents();
});

// ====== THEME ======
function getContrastColor(hexColor) {
  if (!hexColor) return '#ffffff';
  let hex = hexColor.replace('#', '');
  if (hex.length === 3) hex = hex[0] + hex[0] + hex[1] + hex[1] + hex[2] + hex[2];
  if (hex.length !== 6) return '#ffffff';
  const r = parseInt(hex.substring(0, 2), 16);
  const g = parseInt(hex.substring(2, 4), 16);
  const b = parseInt(hex.substring(4, 6), 16);
  const yiq = ((r * 299) + (g * 587) + (b * 114)) / 1000;
  return (yiq >= 150) ? '#121214' : '#ffffff';
}

async function loadSettings() {
  try {
    const settings = await invoke('get_settings');
    if (settings) {
      if (settings.theme) {
        document.body.className = `theme-${settings.theme}`;
      }
      const root = document.documentElement;
      if (settings.accent_color) {
        root.style.setProperty('--accent', settings.accent_color);
        root.style.setProperty('--accent-text', getContrastColor(settings.accent_color));
      }
    }
  } catch (e) {
    console.error("Failed to load settings:", e);
  }
}

// ====== DATA LAYER ======
async function getTasksFilePath() {
  // In a real implementation we would use tauri path api.
  // For simplicity, we can use the backend to read/write, or use local storage if isolated,
  // but to let the AI CLI edit it, it MUST be a real file.
  // We'll write a simple Rust command if needed, or use tauri-plugin-fs.
  // Since we might not have fs plugin imported in JS easily without knowing the exact API,
  // let's use a Rust command if it exists, otherwise fallback to a known location or invoke.
  // Wait, `read_file_content` and `write_file_content` exist in our app!
  return await invoke('get_settings').then(s => {
    // We will save tasks.json in the same folder as settings.json, or workspace.
    // For now we can use an absolute path or a rust command.
    return "tasks.json"; // We'll just pass a relative name if the backend supports it, but backend needs absolute.
  }).catch(() => null);
}

// We will add custom commands in Rust to read/write tasks, or just use localStorage if we want a quick UI.
// BUT the prompt promised the AI could edit it. The AI can read/write ANY file.
// Let's store tasks in `AppData/Roaming/com.ralfm.tauri-radial-menu/tasks.json`
async function loadTasks() {
  try {
    const appData = await invoke('get_app_state'); // We can get config path?
    // Actually, let's just create two Rust commands: `get_tasks` and `save_tasks`.
    // Wait, modifying Rust takes compile time. Let's try to see if `read_file_content` works.
    // If not, we will just use localStorage and tell the AI to use `invoke_agent` or JS to update tasks.
    // Wait, the AI is a CLI. The CLI can read files.
    // Let's just create `get_tasks` and `save_tasks` in Rust to be safe and robust!
    
    // For now, let's try reading via a command.
    let res = await invoke('run_terminal_command', { cmd: `Get-Content "$env:APPDATA\\com.ralfm.tauri-radial-menu\\tasks.json" -Raw` });
    if (res && res.trim().length > 0) {
      tasks = JSON.parse(res);
    } else {
      tasks = [];
    }
  } catch (e) {
    console.log("No tasks file found or error:", e);
    tasks = [];
  }
  renderBoard();
}

async function saveTasks() {
  try {
    const jsonStr = JSON.stringify(tasks, null, 2).replace(/"/g, '\\"').replace(/\n/g, '`n');
    await invoke('run_terminal_command', { 
      cmd: `Set-Content -Path "$env:APPDATA\\com.ralfm.tauri-radial-menu\\tasks.json" -Value "${jsonStr}"` 
    });
  } catch (e) {
    console.error("Failed to save tasks:", e);
  }
}

// ====== UI RENDERING ======
function renderBoard() {
  const todoList = document.getElementById('list-todo');
  const inProgressList = document.getElementById('list-in-progress');
  const doneList = document.getElementById('list-done');

  todoList.innerHTML = '';
  inProgressList.innerHTML = '';
  doneList.innerHTML = '';

  let counts = { todo: 0, 'in-progress': 0, done: 0 };

  tasks.forEach(task => {
    const card = document.createElement('div');
    card.className = 'task-card';
    card.draggable = true;
    card.dataset.id = task.id;

    // Check due date
    let dateClass = 'task-date';
    if (task.dueDate && task.status !== 'done') {
      const due = new Date(task.dueDate);
      if (due < new Date()) dateClass += ' overdue';
    }

    card.innerHTML = `
      <button class="task-delete" title="Delete Task">✕</button>
      <div class="task-title">${escapeHtml(task.title)}</div>
      ${task.description ? `<div class="task-desc">${escapeHtml(task.description)}</div>` : ''}
      <div class="task-meta">
        <span class="task-id">#${task.id.substring(0, 4)}</span>
        ${task.dueDate ? `<span class="${dateClass}">${task.dueDate}</span>` : ''}
      </div>
    `;

    card.addEventListener('dragstart', handleDragStart);
    card.addEventListener('dragend', handleDragEnd);
    
    const delBtn = card.querySelector('.task-delete');
    delBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      deleteTask(task.id);
    });

    card.addEventListener('click', () => openEditModal(task));

    if (task.status === 'in-progress') {
      inProgressList.appendChild(card);
      counts['in-progress']++;
    } else if (task.status === 'done') {
      doneList.appendChild(card);
      counts.done++;
    } else {
      todoList.appendChild(card);
      counts.todo++;
    }
  });

  document.getElementById('count-todo').textContent = counts.todo;
  document.getElementById('count-in-progress').textContent = counts['in-progress'];
  document.getElementById('count-done').textContent = counts.done;
  document.getElementById('task-stats').textContent = `${tasks.length} Tasks`;
}

// ====== DRAG & DROP ======
function handleDragStart(e) {
  draggingTaskId = this.dataset.id;
  this.style.opacity = '0.5';
  e.dataTransfer.effectAllowed = 'move';
}

function handleDragEnd(e) {
  this.style.opacity = '1';
  draggingTaskId = null;
}

function setupEvents() {
  const columns = document.querySelectorAll('.board-column');
  columns.forEach(col => {
    col.addEventListener('dragover', e => {
      e.preventDefault();
      col.style.background = 'rgba(255, 255, 255, 0.05)';
    });
    col.addEventListener('dragleave', () => {
      col.style.background = 'rgba(0, 0, 0, 0.15)';
    });
    col.addEventListener('drop', e => {
      e.preventDefault();
      col.style.background = 'rgba(0, 0, 0, 0.15)';
      if (!draggingTaskId) return;
      
      let newStatus = 'todo';
      if (col.id === 'col-in-progress') newStatus = 'in-progress';
      if (col.id === 'col-done') newStatus = 'done';

      const task = tasks.find(t => t.id === draggingTaskId);
      if (task && task.status !== newStatus) {
        task.status = newStatus;
        saveTasks();
        renderBoard();
      }
    });
  });

  document.getElementById('new-task-btn').addEventListener('click', () => {
    openModal();
  });
  
  document.getElementById('refresh-btn').addEventListener('click', async () => {
    await loadTasks();
  });

  document.getElementById('modal-cancel-btn').addEventListener('click', closeModal);
  document.getElementById('modal-save-btn').addEventListener('click', saveModalTask);
}

// ====== MODAL ======
let editingTaskId = null;

function openModal() {
  editingTaskId = null;
  document.getElementById('modal-title').textContent = 'New Task';
  document.getElementById('task-title').value = '';
  document.getElementById('task-desc').value = '';
  document.getElementById('task-status').value = 'todo';
  document.getElementById('task-due-date').value = '';
  document.getElementById('task-modal').classList.remove('hidden');
}

function openEditModal(task) {
  editingTaskId = task.id;
  document.getElementById('modal-title').textContent = 'Edit Task';
  document.getElementById('task-title').value = task.title;
  document.getElementById('task-desc').value = task.description || '';
  document.getElementById('task-status').value = task.status;
  document.getElementById('task-due-date').value = task.dueDate || '';
  document.getElementById('task-modal').classList.remove('hidden');
}

function closeModal() {
  document.getElementById('task-modal').classList.add('hidden');
}

function saveModalTask() {
  const title = document.getElementById('task-title').value.trim();
  if (!title) return;

  const desc = document.getElementById('task-desc').value.trim();
  const status = document.getElementById('task-status').value;
  const dueDate = document.getElementById('task-due-date').value;

  if (editingTaskId) {
    const task = tasks.find(t => t.id === editingTaskId);
    if (task) {
      task.title = title;
      task.description = desc;
      task.status = status;
      task.dueDate = dueDate;
    }
  } else {
    tasks.push({
      id: Date.now().toString(36) + Math.random().toString(36).substr(2),
      title,
      description: desc,
      status,
      dueDate,
      createdAt: new Date().toISOString()
    });
  }

  saveTasks();
  renderBoard();
  closeModal();
}

function deleteTask(id) {
  if (confirm('Delete this task?')) {
    tasks = tasks.filter(t => t.id !== id);
    saveTasks();
    renderBoard();
  }
}

// ====== UTILS ======
function escapeHtml(str) {
  if (!str) return '';
  return str.replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
}

// Listen for settings change to live update theme
listen('config-updated', (event) => {
  loadSettings();
});

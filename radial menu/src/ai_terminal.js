const invoke = (...args) => {
  if (window.__TAURI__ && window.__TAURI__.core) {
    return window.__TAURI__.core.invoke(...args);
  }
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
  return { hide: () => {}, startDragging: () => {}, minimize: () => {} };
};

let geminiKey = '';
let isThinking = false;
let autocompleteTimer = null;
let currentPath = '';

const terminalOutput = document.getElementById('terminal-output');
const terminalBody = document.getElementById('terminal-body');
const terminalInput = document.getElementById('terminal-input');
const promptText = document.getElementById('prompt-text');
const ghostText = document.getElementById('ghost-text');
const closeBtn = document.getElementById('close-btn');
const minBtn = document.getElementById('min-btn');
const titlebar = document.querySelector('.titlebar');

// Initialize
async function init() {
  // Setup close and minimize buttons
  if (closeBtn) {
    closeBtn.addEventListener('click', () => {
      getCurrentWindow().hide();
    });
  }
  const minBtn = document.getElementById('min-btn');
  if (minBtn) {
    minBtn.addEventListener('click', () => {
      getCurrentWindow().minimize();
    });
  }

  // Setup dragging
  if (titlebar) {
    titlebar.addEventListener('mousedown', (e) => {
      if (e.button === 0 && !e.target.closest('.title-controls')) {
        getCurrentWindow().startDragging();
      }
    });
  }

  // Input event listener for autocomplete
  terminalInput.addEventListener('input', handleInput);
  
  // Keydown event listener for executing and autocomplete accept
  terminalInput.addEventListener('keydown', handleKeydown);

  // Focus input when clicking anywhere on terminal body
  document.body.addEventListener('click', (e) => {
    if (!e.target.closest('.title-controls')) {
      terminalInput.focus();
    }
  });

  try {
    const settings = await invoke('get_settings');
    if (settings) {
      geminiKey = settings.gemini_key || '';
      applyTheme(settings);
    }
  } catch (err) {
    console.error("Failed to fetch settings:", err);
    appendLine(`<span class="line-error">Failed to connect to backend: ${err}</span>`);
  }
}

function applyTheme(settings) {
  // Inject Custom Themes first
  injectCustomThemes(settings);

  document.body.className = '';
  if (settings.theme) {
    document.body.classList.add(`theme-${settings.theme}`);
  }
  const root = document.documentElement;
  root.style.setProperty('--blur-intensity', `${settings.blur_intensity || 25}px`);
  root.style.setProperty('--accent-color', settings.accent_color || '#007aff');
}

function appendLine(htmlContent) {
  const line = document.createElement('div');
  line.className = 'terminal-line';
  line.innerHTML = htmlContent;

  const hasAi = line.querySelector('.line-ai') || line.classList.contains('line-ai');
  if (hasAi) {
    // 1. Add click listeners to inline code blocks
    line.querySelectorAll('.terminal-inline-code').forEach(codeEl => {
      codeEl.addEventListener('click', (e) => {
        e.stopPropagation();
        const codeText = codeEl.textContent;
        navigator.clipboard.writeText(codeText);
        
        // Populate into input
        const input = document.getElementById('terminal-input');
        if (input) {
          input.value = codeText;
          input.focus();
        }
        
        // Show a brief toast in terminal
        showTerminalToast("Copied & inserted command!");
      });
    });

    // 2. Add message copy button to the line itself
    const rawText = line.textContent.replace(/^▶\s*/, '').trim();
    if (rawText && rawText !== 'Building command with AI...' && rawText !== 'Analyzing error...') {
      addTerminalLineCopyButton(line, rawText);
    }
  }

  terminalOutput.appendChild(line);
  terminalBody.scrollTop = terminalBody.scrollHeight;
}

// =====================================
// AI Autocomplete
// =====================================
function handleInput() {
  const val = terminalInput.value;
  
  // Match ghost text to input so far
  const currentGhost = ghostText.textContent;
  if (currentGhost && currentGhost.startsWith(val)) {
    // Keep it if it still matches
  } else {
    ghostText.textContent = '';
  }

  if (val.trim() === '') {
    ghostText.textContent = '';
    clearTimeout(autocompleteTimer);
    return;
  }

  clearTimeout(autocompleteTimer);
  autocompleteTimer = setTimeout(() => {
    fetchAutocomplete(val);
  }, 500);
}

async function fetchAutocomplete(currentInput) {
  if (!geminiKey || isThinking) return;

  try {
    const prompt = `You are providing an inline autocomplete suggestion for a Windows command line (PowerShell/CMD). The user has typed: "${currentInput}". What is the most likely rest of the command they want to type? Output ONLY the full completed command string, without markdown formatting, without backticks, and without explanation. If you have no good suggestion, output nothing.`;
    
    const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=${geminiKey}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        contents: [{ parts: [{ text: prompt }] }],
        generationConfig: { temperature: 0.1, maxOutputTokens: 50 }
      })
    });
    
    const data = await response.json();
    if (data.candidates && data.candidates[0].content.parts[0].text) {
      let suggestion = data.candidates[0].content.parts[0].text.trim();
      // Remove any trailing newlines or markdown that the AI ignored instructions on
      suggestion = suggestion.replace(/^`+|`+$/g, '');
      
      // If the suggestion starts with what the user typed, show the rest
      if (suggestion.toLowerCase().startsWith(currentInput.toLowerCase())) {
        // We set the ghost text to the original case of the input + the rest of the suggestion
        const rest = suggestion.substring(currentInput.length);
        ghostText.textContent = currentInput + rest;
      }
    }
  } catch (err) {
    console.error("Autocomplete error:", err);
  }
}

// =====================================
// Input Handling & Execution
// =====================================
async function handleKeydown(e) {
  // Tab for autocomplete
  if (e.key === 'Tab') {
    e.preventDefault();
    if (ghostText.textContent && ghostText.textContent.toLowerCase().startsWith(terminalInput.value.toLowerCase())) {
      terminalInput.value = ghostText.textContent;
      ghostText.textContent = '';
    }
    return;
  }

  if (e.key === 'Enter') {
    e.preventDefault();
    const cmd = terminalInput.value.trim();
    if (!cmd) return;

    terminalInput.value = '';
    ghostText.textContent = '';
    
    const displayPrompt = currentPath ? `PS ${currentPath}&gt;` : `PS C:\\&gt;`;
    appendLine(`<span class="line-prompt">${displayPrompt}</span> ${cmd}`);
    
    if (cmd.toLowerCase() === 'clear' || cmd.toLowerCase() === 'cls') {
      terminalOutput.innerHTML = '';
      return;
    }

    if (cmd.toLowerCase() === 'exit') {
      getCurrentWindow().hide();
      return;
    }

    isThinking = true;
    
    try {
      let finalCmd = cmd;
      if (cmd.includes('\\') && geminiKey) {
        appendLine(`<span class="line-ai">Building command with AI...</span>`);
        const parts = cmd.split('\\');
        const baseCmd = parts[0].trim();
        const instruction = parts.slice(1).join('\\').trim();
        
        const prompt = `The user wants to run a Windows PowerShell command. They have started with: "${baseCmd}". Their instruction for what the command should do is: "${instruction}". Generate the full, complete PowerShell command. Output ONLY the command text. Do NOT wrap it in backticks or markdown.`;
        
        const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=${geminiKey}`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            contents: [{ parts: [{ text: prompt }] }],
            generationConfig: { temperature: 0.1 }
          })
        });
        const data = await response.json();
        if (data.candidates && data.candidates[0].content.parts[0].text) {
          finalCmd = data.candidates[0].content.parts[0].text.trim().replace(/^`+|`+$/g, '');
          if (terminalOutput.lastChild && terminalOutput.lastChild.textContent === 'Building command with AI...') {
            terminalOutput.removeChild(terminalOutput.lastChild);
          }
          appendLine(`<span class="line-ai">▶ ${escapeHtml(finalCmd)}</span>`);
        } else {
          const errMsg = data.error ? data.error.message : 'Unknown response structure';
          throw new Error(`AI API Error: ${errMsg}`);
        }
      }

      const marker = '___PWD_MARKER___';
      let wrappedCmd = finalCmd;
      if (currentPath) {
        wrappedCmd = `Set-Location -LiteralPath '${currentPath}' -ErrorAction SilentlyContinue; ${finalCmd}`;
      }
      wrappedCmd += `; Write-Output '${marker}'; (Get-Location).Path`;

      const id = Math.random().toString(36).substring(2, 9);
      let sawMarker = false;

      const unlistenOut = await window.__TAURI__.event.listen(`terminal-out-${id}`, (event) => {
        const line = event.payload;
        if (line.trim() === marker) {
          sawMarker = true;
        } else if (sawMarker) {
          currentPath = line.trim();
          promptText.textContent = currentPath ? `PS ${currentPath}>` : `PS C:\\>`;
        } else {
          appendLine(escapeHtml(line));
        }
      });
      
      const unlistenErr = await window.__TAURI__.event.listen(`terminal-err-${id}`, (event) => {
        appendLine(`<span class="line-error">${escapeHtml(event.payload)}</span>`);
      });

      await new Promise(async (resolve, reject) => {
        const unlistenExit = await window.__TAURI__.event.listen(`terminal-exit-${id}`, (event) => {
          const code = event.payload;
          unlistenOut(); unlistenErr(); unlistenExit();
          if (code !== 0) {
            reject(new Error(`Exited with code ${code}`));
          } else {
            resolve();
          }
        });
        
        try {
          await invoke('spawn_terminal_command', { id, cmd: wrappedCmd });
        } catch (err) {
          unlistenOut(); unlistenErr(); unlistenExit();
          reject(err);
        }
      });
    } catch (error) {
      // It failed! Display error and ask AI
      appendLine(`<span class="line-error">${escapeHtml(String(error))}</span>`);
      appendLine(`<span class="line-ai">Analyzing error...</span>`);
      
      await askAIToCorrect(cmd, String(error));
    }
    
    isThinking = false;
  }
}

async function askAIToCorrect(failedCmd, errorMsg) {
  if (!geminiKey) {
    appendLine(`<span class="line-ai">Error analysis skipped: No Gemini API Key found in settings.</span>`);
    return;
  }

  try {
    const prompt = `The user tried to run this Windows command:\n\`${failedCmd}\`\n\nAnd received this error:\n\`${errorMsg}\`\n\nBriefly explain why it failed, and provide the correct command they should use instead. Keep it concise.`;
    
    const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=${geminiKey}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        contents: [{ parts: [{ text: prompt }] }],
        generationConfig: { temperature: 0.3 }
      })
    });
    
    const data = await response.json();
    if (data.candidates && data.candidates[0].content.parts[0].text) {
      let aiResponse = data.candidates[0].content.parts[0].text.trim();
      
      // Remove the "Analyzing error..." line
      if (terminalOutput.lastChild && terminalOutput.lastChild.textContent === 'Analyzing error...') {
        terminalOutput.removeChild(terminalOutput.lastChild);
      }
      
      appendLine(`<div class="line-ai">${formatAIResponse(aiResponse)}</div>`);
    }
  } catch (err) {
    if (terminalOutput.lastChild && terminalOutput.lastChild.textContent === 'Analyzing error...') {
      terminalOutput.removeChild(terminalOutput.lastChild);
    }
    appendLine(`<span class="line-ai">AI Analysis failed: ${err.message}</span>`);
  }
}

// Helpers
function escapeHtml(unsafe) {
  return unsafe
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

function formatAIResponse(text) {
  let escaped = escapeHtml(text);
  // Basic markdown formatting
  escaped = escaped.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>');
  escaped = escaped.replace(/`(.*?)`/g, '<code class="terminal-inline-code" title="Click to copy & run">$1</code>');
  escaped = escaped.replace(/\n/g, '<br>');
  return escaped;
}

function addTerminalLineCopyButton(lineEl, text) {
  lineEl.style.position = 'relative';
  
  const btn = document.createElement('button');
  btn.className = 'terminal-copy-btn';
  btn.title = 'Copy text';
  btn.innerHTML = `
    <svg viewBox="0 0 24 24" width="10" height="10" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
      <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
      <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
    </svg>
  `;
  btn.addEventListener('click', (e) => {
    e.stopPropagation();
    navigator.clipboard.writeText(text);
    btn.innerHTML = '✓';
    setTimeout(() => {
      btn.innerHTML = `
        <svg viewBox="0 0 24 24" width="10" height="10" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
          <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
        </svg>
      `;
    }, 1500);
  });
  lineEl.appendChild(btn);
}

function showTerminalToast(message) {
  let toast = document.getElementById('terminal-toast');
  if (!toast) {
    toast = document.createElement('div');
    toast.id = 'terminal-toast';
    toast.className = 'terminal-toast-msg';
    document.body.appendChild(toast);
  }
  toast.textContent = message;
  toast.classList.add('show');
  setTimeout(() => toast.classList.remove('show'), 2000);
}

init();

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

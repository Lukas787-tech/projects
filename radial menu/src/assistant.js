window.onerror = function(msg, url, lineNo, columnNo, error) {
  alert("JS Error: " + msg + "\nLine: " + lineNo + "\n" + (error ? error.stack : ""));
  return false;
};

window.onunhandledrejection = function(event) {
  alert("Unhandled Rejection: " + event.reason + "\nStack: " + (event.reason && event.reason.stack ? event.reason.stack : ""));
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
    minimize: () => console.log("Window minimize called"),
    startDragging: () => console.log("Window startDragging called")
  };
};

let activeSettings = null;
let conversationHistory = [];
let isStreaming = false;
let autoScroll = true;
let savedChats = [];
let activeChatId = null;

// ====== PROVIDER CONFIGURATION ======
const PROVIDERS = {
  gemini_cli: {
    name: 'Gemini CLI (Local)',
    color: '#00C4B3',
    keyPlaceholder: 'Local, no key needed',
    keyLink: '#',
    format: 'gemini_cli',
    models: [
      { id: 'gemini-cli-agent', tag: 'pro', tagLabel: 'Agent' }
    ]
  },
  gemini: {
    name: 'Google Gemini',
    color: '#4285F4',
    keyPlaceholder: 'AIzaSy...',
    keyLink: 'https://aistudio.google.com/',
    format: 'gemini',
    models: [
      { id: 'gemini-3.5-flash', tag: 'smart', tagLabel: 'Smart' },
      { id: 'gemini-3.1-pro-preview', tag: 'pro', tagLabel: 'Pro' },
      { id: 'gemini-3.1-flash-lite', tag: 'fast', tagLabel: 'Fast' },
      { id: 'gemini-2.5-pro', tag: 'pro', tagLabel: 'Pro' },
      { id: 'gemini-2.5-flash', tag: 'cheap', tagLabel: 'Cheap' },
    ]
  },
  openai: {
    name: 'OpenAI',
    color: '#10a37f',
    keyPlaceholder: 'sk-...',
    keyLink: 'https://platform.openai.com/api-keys',
    format: 'openai',
    endpoint: 'https://api.openai.com/v1/chat/completions',
    models: [
      { id: 'gpt-4o', tag: 'smart', tagLabel: 'Smart' },
      { id: 'gpt-4o-mini', tag: 'fast', tagLabel: 'Fast' },
      { id: 'gpt-4-turbo', tag: 'pro', tagLabel: 'Pro' },
      { id: 'o3-mini', tag: 'smart', tagLabel: 'Smart' },
      { id: 'gpt-3.5-turbo', tag: 'cheap', tagLabel: 'Cheap' },
    ]
  },
  anthropic: {
    name: 'Anthropic',
    color: '#d97706',
    keyPlaceholder: 'sk-ant-...',
    keyLink: 'https://console.anthropic.com/',
    format: 'anthropic',
    endpoint: 'https://api.anthropic.com/v1/messages',
    models: [
      { id: 'claude-sonnet-4-20250514', tag: 'smart', tagLabel: 'Smart' },
      { id: 'claude-3-5-sonnet-20241022', tag: 'pro', tagLabel: 'Pro' },
      { id: 'claude-3-5-haiku-20241022', tag: 'fast', tagLabel: 'Fast' },
    ]
  },
  mistral: {
    name: 'Mistral',
    color: '#ff7000',
    keyPlaceholder: 'your-key...',
    keyLink: 'https://console.mistral.ai/',
    format: 'openai',
    endpoint: 'https://api.mistral.ai/v1/chat/completions',
    models: [
      { id: 'mistral-large-latest', tag: 'smart', tagLabel: 'Smart' },
      { id: 'mistral-small-latest', tag: 'fast', tagLabel: 'Fast' },
      { id: 'open-mistral-nemo', tag: 'cheap', tagLabel: 'Cheap' },
    ]
  },
  groq: {
    name: 'Groq',
    color: '#f55036',
    keyPlaceholder: 'gsk_...',
    keyLink: 'https://console.groq.com/',
    format: 'openai',
    endpoint: 'https://api.groq.com/openai/v1/chat/completions',
    models: [
      { id: 'llama-3.3-70b-versatile', tag: 'smart', tagLabel: 'Smart' },
      { id: 'llama-3.1-8b-instant', tag: 'fast', tagLabel: 'Fast' },
      { id: 'mixtral-8x7b-32768', tag: 'cheap', tagLabel: 'Cheap' },
    ]
  },
  openrouter: {
    name: 'OpenRouter',
    color: '#6366f1',
    keyPlaceholder: 'sk-or-...',
    keyLink: 'https://openrouter.ai/keys',
    format: 'openai',
    endpoint: 'https://openrouter.ai/api/v1/chat/completions',
    models: [
      { id: 'auto', tag: 'smart', tagLabel: 'Auto' },
      { id: 'google/gemini-2.5-flash:free', tag: 'fast', tagLabel: 'Free' },
      { id: 'meta-llama/llama-3.3-70b-instruct:free', tag: 'smart', tagLabel: 'Free' },
      { id: 'deepseek/deepseek-chat:free', tag: 'pro', tagLabel: 'Free' }
    ]
  }
};

function getProviderForModel(modelId) {
  for (const [key, provider] of Object.entries(PROVIDERS)) {
    if (provider.models.some(m => m.id === modelId)) {
      return { key, ...provider };
    }
  }
  return { key: 'gemini', ...PROVIDERS.gemini };
}

function getSelectedModel() {
  const stored = localStorage.getItem('ai_assistant_model');
  if (stored) {
    // Validate that the stored model still exists in our list
    for (const provider of Object.values(PROVIDERS)) {
      if (provider.models.some(m => m.id === stored)) {
        return stored;
      }
    }
  }
  return 'gemini-3.5-flash';
}

// ====== API KEY MANAGEMENT ======
function loadApiKeys() {
  try {
    const raw = localStorage.getItem('ai_api_keys');
    return raw ? JSON.parse(raw) : {};
  } catch (e) {
    return {};
  }
}

function saveApiKeys(keys) {
  localStorage.setItem('ai_api_keys', JSON.stringify(keys));
}

function getApiKeyForProvider(providerKey) {
  const keys = loadApiKeys();
  if (keys[providerKey]) return keys[providerKey];
  // Backward compat: fall back to Gemini key from settings
  if (providerKey === 'gemini' && activeSettings?.gemini_key) {
    return activeSettings.gemini_key;
  }
  return null;
}

// ====== TOOLS DECLARATION ======
const TOOLS_DECLARATION = [
  {
    "functionDeclarations": [
      {
        "name": "show_ai_terminal",
        "description": "Show/open the AI Terminal window (also known as the Gemini CLI or console).",
        "parameters": {
          "type": "OBJECT",
          "properties": {}
        }
      },
      {
        "name": "show_settings",
        "description": "Show/open the Settings/Preferences window.",
        "parameters": {
          "type": "OBJECT",
          "properties": {}
        }
      },
      {
        "name": "show_assistant",
        "description": "Show/open the AI Assistant window.",
        "parameters": {
          "type": "OBJECT",
          "properties": {}
        }
      },
      {
        "name": "run_terminal_command",
        "description": "Execute a PowerShell command on Windows. Returns stdout/stderr. Use for running scripts, network commands, installing packages, git, etc.",
        "parameters": {
          "type": "OBJECT",
          "properties": {
            "cmd": { "type": "STRING", "description": "The PowerShell command to execute" }
          },
          "required": ["cmd"]
        }
      },
      {
        "name": "write_file",
        "description": "Write content to a file. Creates parent directories automatically.",
        "parameters": {
          "type": "OBJECT",
          "properties": {
            "path": { "type": "STRING", "description": "Absolute file path" },
            "content": { "type": "STRING", "description": "File content to write" }
          },
          "required": ["path", "content"]
        }
      },
      {
        "name": "read_file",
        "description": "Read the text contents of a file.",
        "parameters": {
          "type": "OBJECT",
          "properties": {
            "path": { "type": "STRING", "description": "Absolute file path" }
          },
          "required": ["path"]
        }
      },
      {
        "name": "edit_file_content",
        "description": "Apply a find/replace edit to an existing file. The 'find' text must exist exactly in the file. Only the first occurrence is replaced.",
        "parameters": {
          "type": "OBJECT",
          "properties": {
            "path": { "type": "STRING", "description": "Absolute file path" },
            "find": { "type": "STRING", "description": "Exact text to find in the file" },
            "replace": { "type": "STRING", "description": "Replacement text" }
          },
          "required": ["path", "find", "replace"]
        }
      },
      {
        "name": "list_directory",
        "description": "List all files and folders in a directory.",
        "parameters": {
          "type": "OBJECT",
          "properties": {
            "path": { "type": "STRING", "description": "Directory path to list" }
          },
          "required": ["path"]
        }
      },
      {
        "name": "create_directory",
        "description": "Create a directory and all parent directories.",
        "parameters": {
          "type": "OBJECT",
          "properties": {
            "path": { "type": "STRING", "description": "Directory path to create" }
          },
          "required": ["path"]
        }
      },
      {
        "name": "search_files",
        "description": "Recursively search for a text pattern in all files within a directory. Returns matching lines with file paths and line numbers. Case-insensitive.",
        "parameters": {
          "type": "OBJECT",
          "properties": {
            "directory": { "type": "STRING", "description": "Root directory to search in" },
            "pattern": { "type": "STRING", "description": "Text pattern to search for" },
            "max_results": { "type": "INTEGER", "description": "Maximum number of results (default 50)" }
          },
          "required": ["directory", "pattern"]
        }
      },
      {
        "name": "save_plugin",
        "description": "Create or update a plugin that extends the Radial Menu with new actions. The plugin is a JSON object with menu_actions that define new radial menu entries. Changes are hot-reloaded instantly.",
        "parameters": {
          "type": "OBJECT",
          "properties": {
            "plugin": {
              "type": "OBJECT",
              "description": "Plugin object",
              "properties": {
                "id": { "type": "STRING", "description": "Unique slug ID (e.g. 'game-launchers')" },
                "name": { "type": "STRING", "description": "Display name" },
                "version": { "type": "STRING" },
                "description": { "type": "STRING" },
                "author": { "type": "STRING" },
                "menu_actions": {
                  "type": "ARRAY",
                  "description": "Array of menu actions to add to the radial menu",
                  "items": {
                    "type": "OBJECT",
                    "properties": {
                      "id": { "type": "STRING" },
                      "label": { "type": "STRING" },
                      "icon": { "type": "STRING", "description": "Icon key: globe, folder, notepad, gear, lock, terminal, music, sparkle" },
                      "cmd": { "type": "STRING" },
                      "args": { "type": "ARRAY", "items": { "type": "STRING" } },
                      "subItems": {
                        "type": "ARRAY",
                        "items": {
                          "type": "OBJECT",
                          "properties": {
                            "label": { "type": "STRING" },
                            "cmd": { "type": "STRING" },
                            "args": { "type": "ARRAY", "items": { "type": "STRING" } }
                          }
                        }
                      }
                    }
                  }
                },
                "custom_themes": {
                  "type": "ARRAY",
                  "description": "Optional array of full custom themes bundled with this plugin",
                  "items": {
                    "type": "OBJECT",
                    "properties": {
                      "name": { "type": "STRING" },
                      "css_vars": { "type": "STRING" }
                    }
                  }
                },
                "injected_js": { "type": "STRING", "description": "Raw JS code to execute in the webview context on load" },
                "injected_css": { "type": "STRING", "description": "Raw CSS code to inject into the document head on load" },
                "on_init_script": { "type": "STRING" }
              },
              "required": ["id", "name"]
            }
          },
          "required": ["plugin"]
        }
      },
      {
        "name": "delete_plugin",
        "description": "Delete a plugin by ID. Removes its menu actions from the radial menu.",
        "parameters": {
          "type": "OBJECT",
          "properties": {
            "id": { "type": "STRING", "description": "Plugin ID to delete" }
          },
          "required": ["id"]
        }
      },
      {
        "name": "get_plugins",
        "description": "List all installed plugins with their full configuration.",
        "parameters": { "type": "OBJECT", "properties": {} }
      },
      {
        "name": "save_custom_module",
        "description": "Create or update a custom desktop overlay widget (HTML/CSS/JS). Use '__SCOPE__' as CSS parent selector. JS has 'container' (DOM element) and 'invoke' (Tauri IPC) available.",
        "parameters": {
          "type": "OBJECT",
          "properties": {
            "id": { "type": "STRING" },
            "name": { "type": "STRING" },
            "description": { "type": "STRING" },
            "html": { "type": "STRING" },
            "css": { "type": "STRING" },
            "js": { "type": "STRING" },
            "poll_interval_ms": { "type": "INTEGER" },
            "enabled": { "type": "BOOLEAN" }
          },
          "required": ["id", "name", "description", "html", "css", "js", "poll_interval_ms", "enabled"]
        }
      },
      {
        "name": "delete_custom_module",
        "description": "Delete a custom overlay module by ID.",
        "parameters": {
          "type": "OBJECT",
          "properties": { "id": { "type": "STRING" } },
          "required": ["id"]
        }
      },
      {
        "name": "update_app_settings",
        "description": "Update app settings (theme, hotkey, accent_color, actions etc). Merges with current settings.",
        "parameters": {
          "type": "OBJECT",
          "properties": {
            "settings": { "type": "OBJECT", "description": "Partial settings to merge" }
          },
          "required": ["settings"]
        }
      },
      {
        "name": "get_app_state",
        "description": "Get full app state: settings, custom modules, and installed plugins. ALWAYS call this first before making changes to understand the current configuration.",
        "parameters": { "type": "OBJECT", "properties": {} }
      },
      {
        "name": "get_active_app",
        "description": "Get the tag or window title of the currently focused foreground window (e.g. 'Minecraft', 'VSCode', 'Notepad', 'Browser', or the window title). Use this to see what application or game the user is currently working on or playing.",
        "parameters": { "type": "OBJECT", "properties": {} }
      },
      {
        "name": "save_theme",
        "description": "Create or update a custom CSS theme for the application. The theme is injected dynamically across all windows.",
        "parameters": {
          "type": "OBJECT",
          "properties": {
            "name": { "type": "STRING", "description": "Unique slug for the theme (e.g. 'matrix', 'neon-purple')" },
            "css_vars": { "type": "STRING", "description": "CSS variable definitions (e.g. '--bg: #000; --text: #0f0; --primary: #00ff00;')" }
          },
          "required": ["name", "css_vars"]
        }
      },
      {
        "name": "delete_theme",
        "description": "Delete a custom CSS theme.",
        "parameters": {
          "type": "OBJECT",
          "properties": {
            "name": { "type": "STRING", "description": "Theme name to delete" }
          },
          "required": ["name"]
        }
      },
      {
        "name": "show_design_maker",
        "description": "Open the visual Design Maker window — a GUI tool for creating custom themes with live preview, color pickers, geometry controls, and preset templates.",
        "parameters": { "type": "OBJECT", "properties": {} }
      },
      {
        "name": "import_plugin",
        "description": "Import a plugin from a JSON string. Use this to install plugins from exported JSON data.",
        "parameters": {
          "type": "OBJECT",
          "properties": {
            "json": { "type": "STRING", "description": "The full plugin JSON string to import" }
          },
          "required": ["json"]
        }
      },
      {
        "name": "export_plugin",
        "description": "Export a plugin as a JSON string by its ID.",
        "parameters": {
          "type": "OBJECT",
          "properties": {
            "id": { "type": "STRING", "description": "Plugin ID to export" }
          },
          "required": ["id"]
        }
      },
      {
        "name": "get_plugin_settings",
        "description": "Get the saved settings for a specific plugin by its ID.",
        "parameters": {
          "type": "OBJECT",
          "properties": {
            "id": { "type": "STRING", "description": "Plugin ID" }
          },
          "required": ["id"]
        }
      },
      {
        "name": "save_plugin_settings",
        "description": "Save settings for a specific plugin.",
        "parameters": {
          "type": "OBJECT",
          "properties": {
            "id": { "type": "STRING", "description": "Plugin ID" },
            "settings": { "type": "OBJECT", "description": "Settings key-value pairs to save" }
          },
          "required": ["id", "settings"]
        }
      },
      {
        "name": "create_plan",
        "description": "Create an implementation plan and display it as an interactive checklist card in the chat. Use this when the user asks you to build something complex — break it into clear steps first. The plan will be shown as an interactive card with checkboxes and execute buttons. Each step should be a concrete, actionable task.",
        "parameters": {
          "type": "OBJECT",
          "properties": {
            "title": { "type": "STRING", "description": "Plan title" },
            "description": { "type": "STRING", "description": "Brief plan overview" },
            "steps": {
              "type": "ARRAY",
              "description": "Array of plan steps",
              "items": {
                "type": "OBJECT",
                "properties": {
                  "id": { "type": "STRING", "description": "Step ID (e.g. 'step-1')" },
                  "title": { "type": "STRING", "description": "Step title" },
                  "description": { "type": "STRING", "description": "What this step does" },
                  "tool": { "type": "STRING", "description": "Which tool to use (e.g. 'save_plugin', 'write_file')" },
                  "tool_args": { "type": "OBJECT", "description": "Arguments to pass to the tool" }
                },
                "required": ["id", "title"]
              }
            }
          },
          "required": ["title", "steps"]
        }
      }
    ]
  }
];

// OpenAI-compatible tools format
function geminiToolsToOpenAI() {
  const fns = TOOLS_DECLARATION[0].functionDeclarations;
  return fns.map(fn => ({
    type: 'function',
    function: {
      name: fn.name,
      description: fn.description,
      parameters: convertGeminiSchemaToJsonSchema(fn.parameters)
    }
  }));
}

function convertGeminiSchemaToJsonSchema(schema) {
  if (!schema) return {};
  const result = {};
  if (schema.type) result.type = schema.type.toLowerCase();
  if (schema.description) result.description = schema.description;
  if (schema.required) result.required = schema.required;
  if (schema.properties) {
    result.properties = {};
    for (const [k, v] of Object.entries(schema.properties)) {
      result.properties[k] = convertGeminiSchemaToJsonSchema(v);
    }
  }
  if (schema.items) result.items = convertGeminiSchemaToJsonSchema(schema.items);
  return result;
}

// Anthropic tools format
function geminiToolsToAnthropic() {
  const fns = TOOLS_DECLARATION[0].functionDeclarations;
  return fns.map(fn => ({
    name: fn.name,
    description: fn.description,
    input_schema: convertGeminiSchemaToJsonSchema(fn.parameters)
  }));
}

// ====== FORMAT CONVERSION: Gemini History → OpenAI Messages ======
function geminiHistoryToOpenAI(history, systemPrompt) {
  const messages = [{ role: 'system', content: systemPrompt }];

  for (let i = 0; i < history.length; i++) {
    const turn = history[i];
    if (turn.role === 'user') {
      const firstPart = turn.parts?.[0];
      if (firstPart?.functionResponse) {
        // Tool results
        for (const part of turn.parts) {
          if (part.functionResponse) {
            messages.push({
              role: 'tool',
              tool_call_id: part.functionResponse._tool_call_id || `call_${part.functionResponse.name}`,
              content: typeof part.functionResponse.response?.output === 'string'
                ? part.functionResponse.response.output
                : JSON.stringify(part.functionResponse.response)
            });
          }
        }
      } else if (firstPart?.text) {
        messages.push({ role: 'user', content: firstPart.text });
      }
    } else if (turn.role === 'model') {
      const textParts = turn.parts?.filter(p => p.text) || [];
      const fcParts = turn.parts?.filter(p => p.functionCall) || [];

      if (fcParts.length > 0) {
        messages.push({
          role: 'assistant',
          content: textParts.map(p => p.text).join('') || null,
          tool_calls: fcParts.map((p, idx) => ({
            id: p.functionCall._id || `call_${p.functionCall.name}`,
            type: 'function',
            function: {
              name: p.functionCall.name,
              arguments: JSON.stringify(p.functionCall.args || {})
            }
          }))
        });
      } else {
        messages.push({
          role: 'assistant',
          content: textParts.map(p => p.text).join('') || ''
        });
      }
    }
  }
  return messages;
}

// ====== FORMAT CONVERSION: Gemini History → Anthropic Messages ======
function geminiHistoryToAnthropic(history) {
  const messages = [];

  for (let i = 0; i < history.length; i++) {
    const turn = history[i];
    if (turn.role === 'user') {
      const firstPart = turn.parts?.[0];
      if (firstPart?.functionResponse) {
        const content = turn.parts
          .filter(p => p.functionResponse)
          .map(p => ({
            type: 'tool_result',
            tool_use_id: p.functionResponse._tool_call_id || `call_${p.functionResponse.name}`,
            content: typeof p.functionResponse.response?.output === 'string'
              ? p.functionResponse.response.output
              : JSON.stringify(p.functionResponse.response)
          }));
        messages.push({ role: 'user', content });
      } else if (firstPart?.text) {
        messages.push({ role: 'user', content: firstPart.text });
      }
    } else if (turn.role === 'model') {
      const content = [];
      for (const part of (turn.parts || [])) {
        if (part.text) {
          content.push({ type: 'text', text: part.text });
        } else if (part.functionCall) {
          content.push({
            type: 'tool_use',
            id: part.functionCall._id || `call_${part.functionCall.name}`,
            name: part.functionCall.name,
            input: part.functionCall.args || {}
          });
        }
      }
      if (content.length > 0) {
        messages.push({ role: 'assistant', content });
      }
    }
  }
  return messages;
}

// ====== INITIALIZATION ======
function init() {
  console.log("AI Assistant initializing...");

  // Load saved chats and initialize history synchronously
  loadSavedChats();
  activeChatId = generateUUID();
  renderSidebar();

  // Dragging
  const header = document.querySelector('.assistant-header');
  if (header) {
    header.addEventListener('mousedown', (e) => {
      if (e.button === 0 && !e.target.closest('.header-controls') && !e.target.closest('.header-left')) {
        getCurrentWindow().startDragging();
      }
    });
  }

  // Buttons
  const closeBtn = document.getElementById('close-btn');
  if (closeBtn) closeBtn.addEventListener('click', () => getCurrentWindow().hide());
  
  const minBtn = document.getElementById('min-btn');
  if (minBtn) minBtn.addEventListener('click', () => getCurrentWindow().hide());

  const toggleSettingsBtn = document.getElementById('toggle-settings');
  if (toggleSettingsBtn) {
    toggleSettingsBtn.addEventListener('click', () => {
      const pane = document.getElementById('settings-pane');
      pane.classList.toggle('open');
      if (pane.classList.contains('open')) {
        renderApiKeysSettings();
      }
    });
  }

  const projectBoardBtn = document.getElementById('project-board-btn');
  if (projectBoardBtn) {
    projectBoardBtn.addEventListener('click', () => {
      window.__TAURI__.core.invoke('plugin:window|show', { label: 'project_board' })
        .then(() => window.__TAURI__.core.invoke('plugin:window|set_focus', { label: 'project_board' }))
        .catch(err => alert("Window plugin error: " + err));
    });
  }

  const saveKeysBtn = document.getElementById('save-keys-btn');
  if (saveKeysBtn) saveKeysBtn.addEventListener('click', saveAllApiKeys);

  const newChatBtn = document.getElementById('new-chat-btn');
  if (newChatBtn) newChatBtn.addEventListener('click', startNewChat);

  const copyChatBtn = document.getElementById('copy-chat-btn');
  if (copyChatBtn) {
    copyChatBtn.addEventListener('click', () => {
      if (!conversationHistory || conversationHistory.length === 0) {
        showToast("No message history to copy!");
        return;
      }
      let formattedText = "";
      conversationHistory.forEach(turn => {
        const roleName = turn.role === 'user' ? 'User' : 'AI';
        const parts = turn.parts || [];
        parts.forEach(part => {
          if (part.text) {
            formattedText += `### ${roleName}\n${part.text}\n\n`;
          } else if (part.functionCall) {
            formattedText += `### AI [Tool Call: ${part.functionCall.name}]\nArguments: \`${JSON.stringify(part.functionCall.args)}\`\n\n`;
          } else if (part.functionResponse) {
            const data = part.functionResponse.response || {};
            const output = data.output || JSON.stringify(data);
            formattedText += `### AI [Tool Response: ${part.functionResponse.name}]\nResponse: \n\`\`\`\n${output}\n\`\`\`\n\n`;
          }
        });
      });
      navigator.clipboard.writeText(formattedText.trim());
      showToast("Conversation copied!");
    });
  }

  const toggleHistoryBtn = document.getElementById('toggle-history-btn');
  if (toggleHistoryBtn) {
    toggleHistoryBtn.addEventListener('click', () => {
      const sidebar = document.getElementById('history-sidebar');
      if (sidebar) sidebar.classList.toggle('open');
    });
  }

  // Input
  const chatInput = document.getElementById('chat-input');
  if (chatInput) {
    chatInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendMessage();
      }
    });

    // Auto-resize textarea
    chatInput.addEventListener('input', () => {
      chatInput.style.height = 'auto';
      chatInput.style.height = Math.min(chatInput.scrollHeight, 120) + 'px';
    });
  }

  const sendBtn = document.getElementById('send-btn');
  if (sendBtn) sendBtn.addEventListener('click', sendMessage);

  // Smart auto-scroll
  const chatMessages = document.getElementById('chat-messages');
  if (chatMessages) {
    chatMessages.addEventListener('scroll', () => {
      const isAtBottom = chatMessages.scrollHeight - chatMessages.scrollTop - chatMessages.clientHeight < 60;
      autoScroll = isAtBottom;
    });
  }

  setupChips();

  // Custom model dropdown
  initModelDropdown();

  // Listen to window focus to dynamically update app detection greeting
  window.addEventListener('focus', () => {
    if (conversationHistory.length === 0) {
      updateWelcomeGreeting();
    }
  });

  // Listen for config-updated events
  if (window.__TAURI__ && window.__TAURI__.event) {
    window.__TAURI__.event.listen('config-updated', (event) => {
      if (event.payload) applyTheme(event.payload);
    });
  }

  // Run async Tauri loading in the background
  loadSettings().catch(err => console.error("Async loadSettings failed", err));
  updateWelcomeGreeting().catch(err => console.error("Async updateWelcomeGreeting failed", err));

  console.log("AI Assistant initialized successfully!");
  showToast("AI Assistant Loaded!");
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}

// ====== CUSTOM MODEL DROPDOWN ======

function initModelDropdown() {
  const dropdown = document.getElementById('model-dropdown');
  const trigger = document.getElementById('model-dropdown-trigger');
  const menu = document.getElementById('model-dropdown-menu');

  if (!dropdown || !trigger || !menu) return;

  // Build dropdown menu from PROVIDERS
  renderDropdownMenu(menu);

  // Restore saved model (will fallback to a valid default if missing)
  const savedModel = getSelectedModel();
  localStorage.setItem('ai_assistant_model', savedModel); // Ensure valid model is saved
  updateDropdownDisplay(savedModel);

  // Toggle open/close
  trigger.addEventListener('click', (e) => {
    e.stopPropagation();
    dropdown.classList.toggle('open');
  });

  // Close on click outside
  document.addEventListener('click', (e) => {
    if (!dropdown.contains(e.target)) {
      dropdown.classList.remove('open');
    }
  });

  // Close on Escape
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') dropdown.classList.remove('open');
  });
}

function renderDropdownMenu(menu) {
  menu.innerHTML = '';
  for (const [providerKey, provider] of Object.entries(PROVIDERS)) {
    const group = document.createElement('div');
    group.className = 'model-group';

    const label = document.createElement('div');
    label.className = 'model-group-label';
    label.innerHTML = `<span class="model-group-dot" style="background:${provider.color}"></span> ${provider.name}`;
    group.appendChild(label);

    for (const model of provider.models) {
      const option = document.createElement('div');
      option.className = 'model-option';
      option.dataset.model = model.id;
      option.dataset.provider = providerKey;

      const nameSpan = document.createElement('span');
      nameSpan.textContent = model.id;

      option.appendChild(nameSpan);

      if (model.tag) {
        const tag = document.createElement('span');
        tag.className = `model-tag ${model.tag}`;
        tag.textContent = model.tagLabel || model.tag;
        option.appendChild(tag);
      }

      option.addEventListener('click', (e) => {
        e.stopPropagation();
        selectModel(model.id);
        document.getElementById('model-dropdown').classList.remove('open');
      });

      group.appendChild(option);
    }

    menu.appendChild(group);
  }
}

function selectModel(modelId) {
  localStorage.setItem('ai_assistant_model', modelId);
  updateDropdownDisplay(modelId);
  const provider = getProviderForModel(modelId);
  showToast(`Model: ${modelId} (${provider.name})`);
}

function updateDropdownDisplay(modelId) {
  const provider = getProviderForModel(modelId);
  const dot = document.getElementById('provider-dot');
  const name = document.getElementById('model-display-name');
  if (dot) dot.style.background = provider.color;
  if (name) name.textContent = modelId;

  // Update active state in menu
  document.querySelectorAll('.model-option').forEach(opt => {
    opt.classList.toggle('active', opt.dataset.model === modelId);
  });
}

// ====== API KEYS SETTINGS PANE ======
function renderApiKeysSettings() {
  const container = document.getElementById('api-keys-list');
  if (!container) return;

  const keys = loadApiKeys();
  container.innerHTML = '';

  for (const [providerKey, provider] of Object.entries(PROVIDERS)) {
    const row = document.createElement('div');
    row.className = 'api-key-row';

    const label = document.createElement('label');
    label.innerHTML = `<span class="api-key-dot" style="background:${provider.color}"></span> ${provider.name}`;

    const input = document.createElement('input');
    input.type = 'password';
    input.id = `key-${providerKey}`;
    input.placeholder = provider.keyPlaceholder;
    input.autocomplete = 'off';
    input.value = keys[providerKey] || '';
    if (keys[providerKey]) input.classList.add('has-key');

    // Special: if gemini and no stored key, try loading from Tauri settings
    if (providerKey === 'gemini' && !keys[providerKey] && activeSettings?.gemini_key) {
      input.value = activeSettings.gemini_key;
      input.classList.add('has-key');
    }

    row.appendChild(label);
    row.appendChild(input);
    container.appendChild(row);
  }
}

function saveAllApiKeys() {
  const keys = loadApiKeys();
  for (const providerKey of Object.keys(PROVIDERS)) {
    const input = document.getElementById(`key-${providerKey}`);
    if (input && input.value.trim()) {
      keys[providerKey] = input.value.trim();
    } else if (input) {
      delete keys[providerKey];
    }
  }
  saveApiKeys(keys);

  // Also sync Gemini key to Tauri backend for backward compat
  if (keys.gemini && activeSettings) {
    activeSettings.gemini_key = keys.gemini;
    invoke('save_settings', { settings: activeSettings }).catch(e => console.error("Failed to sync gemini key", e));
  }

  showToast("All API keys saved!");
  document.getElementById('settings-pane').classList.remove('open');
}

function setupChips() {
  document.querySelectorAll('.prompt-chip').forEach(chip => {
    chip.addEventListener('click', () => {
      document.getElementById('chat-input').value = chip.dataset.prompt;
      sendMessage();
    });
  });
}

function setStatus(text) {
  const el = document.getElementById('status-text');
  if (el) el.textContent = text;
}

function setTokenCount(text) {
  const el = document.getElementById('token-count');
  if (el) el.textContent = text;
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

// ====== THEME ======
function applyTheme(settings) {
  if (!settings) return;

  // Inject Custom Themes first
  injectCustomThemes(settings);

  if (settings.theme) {
    document.body.className = '';
    document.body.classList.add(`theme-${settings.theme}`);
  }
  const root = document.documentElement;
  const accent = settings.accent_color || '#6366f1';
  root.style.setProperty('--accent-color', accent);
  root.style.setProperty('--accent-text', getContrastColor(accent));
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
    if (!settings) return;
    activeSettings = settings;
    applyTheme(activeSettings);
  } catch (e) {
    console.error("Failed to load settings:", e);
  }
}

function showToast(message, type = 'info') {
  let toast = document.getElementById('toast-notification');
  if (!toast) {
    toast = document.createElement('div');
    toast.id = 'toast-notification';
    toast.className = 'toast-msg';
    document.body.appendChild(toast);
  }
  toast.textContent = message;
  toast.classList.add('show');
  setTimeout(() => toast.classList.remove('show'), 2500);
}

// ====== MESSAGE RENDERING ======
function scrollToBottom() {
  if (!autoScroll) return;
  const container = document.getElementById('chat-messages');
  container.scrollTop = container.scrollHeight;
}

function appendMessage(sender, text, isAi = false) {
  // Remove welcome screen
  const welcome = document.getElementById('welcome-screen');
  if (welcome) welcome.remove();

  const container = document.getElementById('chat-messages');
  const msgDiv = document.createElement('div');
  msgDiv.className = `message ${isAi ? 'ai-msg' : 'user-msg'}`;

  const bubble = document.createElement('div');
  bubble.className = 'message-bubble';

  if (isAi) {
    bubble.innerHTML = parseMarkdown(text);
    addCopyButtons(bubble);
  } else {
    bubble.textContent = text;
  }

  addMessageCopyButton(bubble, text);

  msgDiv.appendChild(bubble);
  container.appendChild(msgDiv);
  scrollToBottom();
  return msgDiv;
}

function createStreamingBubble() {
  const welcome = document.getElementById('welcome-screen');
  if (welcome) welcome.remove();

  const container = document.getElementById('chat-messages');
  const msgDiv = document.createElement('div');
  msgDiv.className = 'message ai-msg';
  msgDiv.id = 'streaming-msg';

  const bubble = document.createElement('div');
  bubble.className = 'message-bubble';
  bubble.id = 'streaming-bubble';

  const cursor = document.createElement('span');
  cursor.className = 'streaming-cursor';
  cursor.id = 'streaming-cursor';

  bubble.appendChild(cursor);
  msgDiv.appendChild(bubble);
  container.appendChild(msgDiv);
  scrollToBottom();
  return bubble;
}

function updateStreamingBubble(fullText) {
  const bubble = document.getElementById('streaming-bubble');
  if (!bubble) return;
  const cursor = document.getElementById('streaming-cursor');
  bubble.innerHTML = parseMarkdown(fullText);
  if (cursor) bubble.appendChild(cursor);
  addCopyButtons(bubble);
  scrollToBottom();
}

function finalizeStreamingBubble(fullText) {
  const bubble = document.getElementById('streaming-bubble');
  if (!bubble) return;
  bubble.innerHTML = parseMarkdown(fullText);
  bubble.removeAttribute('id');
  addCopyButtons(bubble);
  addMessageCopyButton(bubble, fullText);

  const msg = document.getElementById('streaming-msg');
  if (msg) msg.removeAttribute('id');

  const cursor = document.getElementById('streaming-cursor');
  if (cursor) cursor.remove();
  scrollToBottom();
}

function showTypingIndicator() {
  const container = document.getElementById('chat-messages');
  const indicator = document.createElement('div');
  indicator.id = 'typing-indicator-el';
  indicator.className = 'message ai-msg';
  const bubble = document.createElement('div');
  bubble.className = 'message-bubble typing-indicator';
  bubble.innerHTML = `<span class="typing-dot"></span><span class="typing-dot"></span><span class="typing-dot"></span>`;
  indicator.appendChild(bubble);
  container.appendChild(indicator);
  scrollToBottom();
}

function removeTypingIndicator() {
  const el = document.getElementById('typing-indicator-el');
  if (el) el.remove();
}

function appendToolFeedbackCard(name, args) {
  args = args || {};
  const container = document.getElementById('chat-messages');
  const card = document.createElement('div');
  card.className = 'tool-feedback-card';

  const icons = {
    run_terminal_command: '', write_file: '📝', read_file: '📄', edit_file_content: '✏️',
    list_directory: '📁', create_directory: '📂', search_files: '🔍', save_plugin: '🧩', delete_plugin: '🗑️',
    get_plugins: '📦', save_custom_module: '⚙️', delete_custom_module: '🗑️',
    update_app_settings: '🔧', get_app_state: '📊',
    show_ai_terminal: '💻', show_settings: '⚙️', show_assistant: '⚡',
    show_design_maker: '🎨', create_plan: '📋', import_plugin: '📥', export_plugin: '📤',
    get_plugin_settings: '🔑', save_plugin_settings: '💾',
    save_theme: '🎨', delete_theme: '🗑️'
  };

  const titles = {
    run_terminal_command: 'Terminal', write_file: 'Write File', read_file: 'Read File',
    edit_file_content: 'Edit File', list_directory: 'List Directory', create_directory: 'Create Dir',
    search_files: 'Search Files', save_plugin: 'Install Plugin', delete_plugin: 'Delete Plugin',
    get_plugins: 'List Plugins', save_custom_module: 'Save Module', delete_custom_module: 'Delete Module',
    update_app_settings: 'Update Settings', get_app_state: 'Read App State',
    show_ai_terminal: 'Open AI Terminal', show_settings: 'Open Settings', show_assistant: 'Open Assistant',
    show_design_maker: 'Open Design Maker', create_plan: 'Create Plan', import_plugin: 'Import Plugin',
    export_plugin: 'Export Plugin', get_plugin_settings: 'Get Plugin Settings',
    save_plugin_settings: 'Save Plugin Settings', save_theme: 'Save Theme', delete_theme: 'Delete Theme'
  };

  let detail = '';
  if (args.cmd) detail = args.cmd;
  else if (args.path) detail = args.path;
  else if (args.directory) detail = `${args.directory} → "${args.pattern}"`;
  else if (args.plugin) detail = args.plugin.name || args.plugin.id;
  else if (args.id) detail = args.id;
  else if (args.find) detail = `${args.path}`;

  card.innerHTML = `
    <div class="tool-feedback-header">
      <span>${icons[name] || '🛠️'} ${titles[name] || name}</span>
      <span class="tool-feedback-status" style="background:rgba(245,158,11,0.12);color:#f59e0b;border:1px solid rgba(245,158,11,0.25);">Running</span>
    </div>
    <div class="tool-feedback-command">${escapeHtml(detail)}</div>
    <div class="tool-feedback-output" style="display:none;"></div>
  `;
  container.appendChild(card);
  scrollToBottom();
  return card;
}

function updateToolFeedbackCard(cardEl, name, success, result) {
  const statusEl = cardEl.querySelector('.tool-feedback-status');
  const outputEl = cardEl.querySelector('.tool-feedback-output');

  statusEl.textContent = success ? "Done" : "Error";
  statusEl.className = `tool-feedback-status ${success ? 'success' : 'error'}`;

  if (result) {
    const truncated = result.length > 500 ? result.substring(0, 500) + '...' : result;
    outputEl.textContent = truncated;
    outputEl.style.display = 'block';
    outputEl.title = 'Click to expand';
    outputEl.addEventListener('click', () => {
      if (outputEl.classList.contains('expanded')) {
        outputEl.classList.remove('expanded');
        outputEl.textContent = truncated;
      } else {
        outputEl.classList.add('expanded');
        outputEl.textContent = result;
      }
    });
  }
  scrollToBottom();
}

function escapeHtml(text) {
  if (!text) return '';
  return text.toString()
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

// ====== TOOL EXECUTOR (shared by all providers) ======
async function executeTool(name, args) {
  args = args || {};
  let result;
  let success = true;
  try {
    if (name === 'show_ai_terminal') {
      await invoke('show_ai_terminal');
      result = "AI Terminal window opened.";
    } else if (name === 'show_settings') {
      await invoke('show_settings');
      result = "Settings window opened.";
    } else if (name === 'show_assistant') {
      await invoke('show_assistant');
      result = "AI Assistant window opened.";
    } else if (name === 'run_terminal_command') {
      result = await invoke('run_terminal_command', { cmd: args.cmd });
    } else if (name === 'write_file') {
      await invoke('write_file', { path: args.path, content: args.content });
      result = "File written successfully.";
    } else if (name === 'read_file') {
      result = await invoke('read_file', { path: args.path });
    } else if (name === 'edit_file_content') {
      result = await invoke('edit_file_content', { path: args.path, find: args.find, replace: args.replace });
    } else if (name === 'list_directory') {
      const files = await invoke('list_directory', { path: args.path });
      result = files.join('\n');
    } else if (name === 'create_directory') {
      await invoke('create_directory', { path: args.path });
      result = "Directory created.";
    } else if (name === 'search_files') {
      const matches = await invoke('search_files', {
        directory: args.directory,
        pattern: args.pattern,
        maxResults: args.max_results || null
      });
      result = matches.map(m => `${m.path}:${m.line_number}: ${m.line_content}`).join('\n');
      if (!result) result = "No matches found.";
    } else if (name === 'save_plugin') {
      await invoke('save_plugin', { plugin: args.plugin });
      result = `Plugin '${args.plugin.name || args.plugin.id}' installed! Radial menu updated.`;
    } else if (name === 'delete_plugin') {
      await invoke('delete_plugin', { id: args.id });
      result = `Plugin '${args.id}' deleted.`;
    } else if (name === 'get_plugins') {
      const plugins = await invoke('get_plugins');
      result = JSON.stringify(plugins, null, 2);
    } else if (name === 'save_custom_module') {
      await invoke('save_custom_module', { module: args });
      result = `Module '${args.name}' deployed!`;
    } else if (name === 'delete_custom_module') {
      await invoke('delete_custom_module', { id: args.id });
      result = `Module '${args.id}' deleted.`;
    } else if (name === 'update_app_settings') {
      const current = await invoke('get_settings');
      const merged = { ...current, ...args.settings };
      await invoke('save_settings', { settings: merged });
      result = "Settings updated successfully.";
    } else if (name === 'get_active_app') {
      result = await invoke('get_active_app');
    } else if (name === 'get_app_state') {
      const state = await invoke('get_app_state');
      result = JSON.stringify(state, null, 2);
    } else if (name === 'save_theme') {
      const current = await invoke('get_settings');
      let customThemes = current.custom_themes || [];
      const index = customThemes.findIndex(t => t.name === args.name);
      if (index >= 0) {
        customThemes[index] = args;
      } else {
        customThemes.push(args);
      }
      current.custom_themes = customThemes;
      current.theme = args.name; // Automatically switch to the new theme
      await invoke('save_settings', { settings: current });
      result = `Theme '${args.name}' successfully generated and applied!`;
    } else if (name === 'delete_theme') {
      const current = await invoke('get_settings');
      if (current.custom_themes) {
        current.custom_themes = current.custom_themes.filter(t => t.name !== args.name);
        if (current.theme === args.name) current.theme = 'obsidian'; // fallback
        await invoke('save_settings', { settings: current });
        result = `Theme '${args.name}' deleted.`;
      } else {
        result = `No custom themes found.`;
      }
    } else if (name === 'show_design_maker') {
      await invoke('show_design_maker');
      result = "Design Maker window opened.";
    } else if (name === 'import_plugin') {
      result = await invoke('import_plugin', { json: args.json });
    } else if (name === 'export_plugin') {
      result = await invoke('export_plugin', { id: args.id });
    } else if (name === 'get_plugin_settings') {
      const settings = await invoke('get_plugin_settings', { id: args.id });
      result = JSON.stringify(settings, null, 2);
    } else if (name === 'save_plugin_settings') {
      await invoke('save_plugin_settings', { id: args.id, settings: args.settings });
      result = "Plugin settings saved.";
    } else if (name === 'create_plan') {
      appendPlanCard(args);
      result = `Plan "${args.title}" created with ${args.steps.length} steps and displayed to user. You can now proceed to execute steps.`;
    } else {
      throw new Error("Unknown tool: " + name);
    }
  } catch (e) {
    result = e.toString();
    success = false;
  }
  return { result: result || '', success };
}

// ====== PLAN VISUALIZATION ======
function appendPlanCard(plan) {
  const container = document.getElementById('chat-messages');
  const card = document.createElement('div');
  card.className = 'plan-card';
  card.style.cssText = 'background: rgba(0,0,0,0.3); border: 1px solid rgba(255,255,255,0.1); border-radius: 8px; margin: 10px 0; overflow: hidden;';

  let html = `
    <div style="background: rgba(99, 102, 241, 0.2); padding: 10px 14px; border-bottom: 1px solid rgba(255,255,255,0.1); display: flex; align-items: center; gap: 8px;">
      <span style="font-size: 16px;">📋</span>
      <h3 style="margin: 0; font-size: 13px; font-weight: 600; color: #fff;">${escapeHtml(plan.title)}</h3>
    </div>
    <div style="padding: 12px 14px;">
      <p style="margin: 0 0 12px 0; font-size: 11px; color: rgba(255,255,255,0.7);">${escapeHtml(plan.description || '')}</p>
      <div class="plan-steps" style="display: flex; flex-direction: column; gap: 8px;">
  `;

  (plan.steps || []).forEach((step, idx) => {
    html += `
      <div class="plan-step" data-step-id="${escapeHtml(step.id)}" style="background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.06); border-radius: 6px; padding: 10px; display: flex; flex-direction: column; gap: 6px;">
        <div style="display: flex; align-items: flex-start; gap: 8px;">
          <input type="checkbox" class="step-checkbox" id="step-${idx}" style="margin-top: 3px; accent-color: #6366f1;">
          <div style="flex: 1;">
            <label for="step-${idx}" style="font-size: 12px; font-weight: 500; color: #e2e8f0; cursor: pointer;">${idx + 1}. ${escapeHtml(step.title)}</label>
            <p style="margin: 4px 0 0 0; font-size: 10px; color: rgba(255,255,255,0.5);">${escapeHtml(step.description || '')}</p>
          </div>
          <button class="btn-execute-step" data-step-idx="${idx}" style="background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.1); border-radius: 4px; color: #fff; padding: 4px 8px; font-size: 10px; cursor: pointer; transition: background 0.2s;">Execute</button>
        </div>
      </div>
    `;
  });

  html += `
      </div>
      <div style="margin-top: 12px; display: flex; justify-content: flex-end;">
        <button id="btn-execute-all" style="background: linear-gradient(135deg, #6366f1, #8b5cf6); border: none; border-radius: 6px; color: #fff; padding: 6px 12px; font-size: 11px; font-weight: 500; cursor: pointer;">Execute Plan Automatically</button>
      </div>
    </div>
  `;

  card.innerHTML = html;
  container.appendChild(card);
  scrollToBottom();

  // Attach event listeners
  const executeBtns = card.querySelectorAll('.btn-execute-step');
  executeBtns.forEach(btn => {
    btn.addEventListener('click', () => {
      const idx = parseInt(btn.dataset.stepIdx);
      const step = plan.steps[idx];
      const checkbox = card.querySelector(`#step-${idx}`);
      checkbox.checked = true;
      btn.textContent = 'Executing...';
      btn.style.background = 'rgba(99, 102, 241, 0.3)';
      
      const input = document.getElementById('chat-input');
      input.value = `Please execute step ${idx + 1} of the plan: "${step.title}". Use the ${step.tool} tool with args: ${JSON.stringify(step.tool_args)}.`;
      sendMessage();
    });
  });

  const execAllBtn = card.querySelector('#btn-execute-all');
  if (execAllBtn) {
    execAllBtn.addEventListener('click', () => {
      execAllBtn.textContent = 'Executing Sequence...';
      execAllBtn.disabled = true;
      const input = document.getElementById('chat-input');
      input.value = `Please execute the entire plan: "${plan.title}". Go through all steps one by one automatically.`;
      sendMessage();
    });
  }

  return card;
}

// ====== BUILD SYSTEM PROMPT ======
function buildSystemPrompt() {
  const now = new Date();
  const dateStr = now.toISOString().split('T')[0];
  const timeStr = now.toTimeString().split(' ')[0].substring(0, 5);

  return `You are a powerful general-purpose and agentic AI assistant built into a custom Windows desktop Radial Menu utility.

## Dual Role & Core Directive
1. **General Assistant**: You are a fully-capable general-purpose AI. If the user asks you general questions (such as Minecraft building projects, recipes, programming, creative writing, explanations, general knowledge, etc.), you MUST answer them fully, creatively, and helpfully. Do NOT refuse general requests by saying you are only a utility AI.
2. **Radial Menu & System Agent**: You are also a system agent that can read/write files, run terminal commands, manage plugins, widgets, and app settings. Use your tools whenever the user asks you to customize their radial menu, run a script, manage files, or configure system settings.

## Your Capabilities
You are an autonomous agent that can chain multiple tool calls to accomplish complex tasks. You have access to:
- **Active App Query**: Call get_active_app to see the window title/tag of the currently focused foreground window.
- **Process Check**: Run powershell commands via run_terminal_command (e.g. Get-Process).
- **File System**: Read, write, edit, search, and list files/directories.
- **Terminal**: Execute any PowerShell command.
- **Theme Generation**: Call save_theme to instantly generate and apply beautiful custom CSS themes. Use modern styling like gradients, glassmorphism, and neon glows (using CSS vars like --bg, --surface, --primary, --text).
- **Design Maker**: Call show_design_maker to open the visual theme builder GUI with live preview, color pickers, geometry controls, and preset templates.
- **Plugin System**: Create/manage plugins that add new actions to the Radial Menu (hot-reloaded instantly).
  - Plugins now support: lifecycle hooks (on_menu_open, on_menu_close, on_action_execute, on_theme_change), custom icon SVG definitions, settings_schema for configurable settings, keybindings, and layout_overrides (inner_radius, outer_radius, hub_radius, gap_degrees, etc).
  - Use import_plugin/export_plugin to share plugins as JSON.
  - Use get_plugin_settings/save_plugin_settings to manage per-plugin configuration.
- **Advanced Modules ("Real Plugins")**: Create background processes or custom desktop overlay widgets (HTML/CSS/JS) via save_custom_module.

## Planning & Automation
When the user asks you to build something complex, you should:
1. **Create a plan first** using the create_plan tool — this renders an interactive checklist card in the chat.
2. **Execute each step** one by one, updating the user on progress.
3. **Use tasks.json** for long-term project management: read/write \`$env:APPDATA\\com.ralfm.tauri-radial-menu\\tasks.json\` (JSON array of { id, title, description, status: "todo"|"in-progress"|"done", dueDate: "YYYY-MM-DD" }).
4. **Background Autonomy**: For very long tasks, spawn a detached process: \`Start-Process powershell -WindowStyle Hidden -ArgumentList "-Command gemini -p 'Your long prompt here' --yolo"\`.

## Enhanced Plugin Schema
When creating plugins with save_plugin, you can now use these additional fields:
- **hooks**: Object mapping hook names to JS code strings. Available hooks: on_menu_open, on_menu_close, on_action_execute, on_theme_change. Hook functions receive: (data, invoke, listen, items, appSettings).
- **settings_schema**: Array of { key, label, type: "toggle"|"text"|"number"|"color"|"select"|"slider", default_value, options, min, max, step }. These render as a config panel in the Settings > Plugins tab.
- **icon_definitions**: Object mapping icon_name to SVG path strings (inner content of <svg viewBox="0 0 24 24">). These extend the built-in icon set.
- **keybindings**: Array of { id, label, shortcut, action: "command"|"function", value }.
- **layout_overrides**: { inner_radius, outer_radius, sub_inner_radius, sub_outer_radius, hub_radius, icon_radius, label_radius, gap_degrees } — override radial menu geometry.
- **version**: Plugin version string.
- **author**: Plugin author name.

## Critical Rules
1. **ALWAYS call get_app_state first** before modifying settings, plugins, or modules to understand the current configuration.
2. Chain as many tool calls as needed — do not stop early.
3. When editing files, use edit_file_content with exact matching text.
4. After creating a plugin or theme, confirm it was installed successfully.
5. If the user asks you to build a "real plugin" or something modular, heavily utilize \`save_custom_module\` to build a background JS agent or UI widget.
6. When asked to "make plans" or "plan something", use the create_plan tool to create a visual plan card.

## Context
- Date: ${dateStr}, Time: ${timeStr}
- OS: Windows
- User home: C:\\Users\\ralfm`;
}

// ====== SEND MESSAGE (Multi-Provider Router) ======
async function sendMessage() {
  const input = document.getElementById('chat-input');
  const text = input.value.trim();
  if (!text || isStreaming) return;

  input.value = '';
  input.style.height = 'auto';
  appendMessage('user', text, false);

  const selectedModel = getSelectedModel();
  const provider = getProviderForModel(selectedModel);
  const apiKey = getApiKeyForProvider(provider.key);

  if (provider.format !== 'gemini_cli' && !apiKey) {
    // Try loading settings first for backward compat
    if (!activeSettings) await loadSettings();
    const retryKey = getApiKeyForProvider(provider.key);
    if (!retryKey) {
      appendMessage('ai', `No API key found for **${provider.name}**. Click the ⚙️ icon to add your API key.`, true);
      return;
    }
  }

  const key = getApiKeyForProvider(provider.key);
  if (provider.format !== 'gemini_cli' && !key) {
    appendMessage('ai', `No API key found for **${provider.name}**. Click the ⚙️ icon to add your API key.`, true);
    return;
  }

  isStreaming = true;
  autoScroll = true;
  showTypingIndicator();

  conversationHistory.push({ role: 'user', parts: [{ text }] });
  saveCurrentChatState();

  const systemPrompt = buildSystemPrompt();

  try {
    if (provider.format === 'gemini_cli') {
      await handleGeminiCliStream(text);
    } else if (provider.format === 'gemini') {
      await handleGeminiStream(selectedModel, key, systemPrompt);
    } else if (provider.format === 'openai') {
      await handleOpenAIStream(provider.endpoint, key, selectedModel, systemPrompt);
    } else if (provider.format === 'anthropic') {
      await handleAnthropicStream(key, selectedModel, systemPrompt);
    }
  } catch (error) {
    removeTypingIndicator();
    appendMessage('ai', `Error: ${error.message}`, true);
    conversationHistory.pop();
    saveCurrentChatState();
  }

  isStreaming = false;
  setStatus('Ready');
}

// ====== GEMINI CLI STREAMING ======
async function handleGeminiCliStream(text) {
  const id = "gemini_cli_" + Math.random().toString(36).substring(2, 9);
  
  // Write prompt to temp file to avoid quoting issues in shell
  const tempPath = `prompt_${id}.txt`;
  await invoke('write_file', { path: tempPath, content: text });
  
  const cmd = `gemini -p "$(Get-Content '${tempPath}' -Raw)" --output-format stream-json --yolo --session-id "radial_menu_agent"`;
  
  let fullText = '';
  let streamingStarted = false;
  let hasFunctionCalls = false;
  let allParts = [];
  const toolCards = {};

  setStatus('Starting local agent...');

  const unlistenOut = await listen(`terminal-out-${id}`, (event) => {
    const line = event.payload;
    if (!line.startsWith('{')) {
      if (line.includes('Warning:')) return; // Ignore CLI warnings
      return;
    }
    try {
      const data = JSON.parse(line);
      
      if (data.type === 'message' && data.role === 'assistant') {
        removeTypingIndicator();
        setStatus('Generating response...');
        fullText += data.content || '';
        
        // Save text part for history
        if (data.content && !data.delta) {
          allParts.push({ text: data.content });
        } else if (data.content && data.delta) {
          // It streams delta, so we just reconstruct it at the end
        }

        if (!streamingStarted) {
          streamingStarted = true;
          createStreamingBubble();
        }
        updateStreamingBubble(fullText);
      } else if (data.type === 'tool_use') {
        removeTypingIndicator();
        setStatus(`Running tool: ${data.tool_name}...`);
        hasFunctionCalls = true;
        const card = appendToolFeedbackCard(data.tool_name, data.parameters);
        toolCards[data.tool_id] = card;
        
        allParts.push({
          functionCall: { name: data.tool_name, args: data.parameters }
        });
      } else if (data.type === 'tool_result') {
        const card = toolCards[data.tool_id];
        if (card) {
          updateToolFeedbackCard(card, data.tool_name, data.status === 'success', data.output || "Done");
        }
        
        allParts.push({
          functionResponse: { name: data.tool_name, response: { output: data.output } }
        });
      } else if (data.type === 'result') {
        if (data.stats) {
          setTokenCount(`${data.stats.input_tokens || 0} → ${data.stats.output_tokens || 0} tokens`);
        }
      }
    } catch (e) {}
  });

  const unlistenErr = await listen(`terminal-err-${id}`, (event) => {});

  await new Promise(async (resolve) => {
    const unlistenExit = await listen(`terminal-exit-${id}`, async (event) => {
      unlistenOut(); unlistenErr(); unlistenExit();
      await invoke('run_terminal_command', { cmd: `Remove-Item '${tempPath}' -ErrorAction SilentlyContinue` });
      resolve();
    });
    
    try {
      await invoke('spawn_terminal_command', { id, cmd });
    } catch (err) {
      unlistenOut(); unlistenErr(); unlistenExit();
      resolve();
    }
  });

  if (streamingStarted) {
    finalizeStreamingBubble(fullText);
  } else {
    removeTypingIndicator();
  }
  
  if (allParts.length > 0 || fullText.trim()) {
    if (!allParts.find(p => p.text)) {
      allParts.push({ text: fullText });
    }
    // We only update our local UI history for display purposes;
    // the CLI actually maintains the real session state for gemini!
    conversationHistory.push({ role: 'model', parts: allParts });
    saveCurrentChatState();
  }
}

// ====== GEMINI STREAMING ======
async function handleGeminiStream(model, key, systemPrompt) {
  let loopCount = 0;
  const maxLoops = 20;

  while (loopCount < maxLoops) {
    setStatus(`Thinking... (step ${loopCount + 1})`);

    const requestBody = {
      contents: conversationHistory,
      systemInstruction: { parts: [{ text: systemPrompt }] },
      tools: TOOLS_DECLARATION
    };

    const response = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/${model}:streamGenerateContent?alt=sse&key=${key}`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(requestBody)
      }
    );

    removeTypingIndicator();

    if (!response.ok) {
      const errData = await response.json().catch(() => ({}));
      appendMessage('ai', `API Error (${model}): ${errData.error?.message || response.statusText}`, true);
      conversationHistory.pop();
      saveCurrentChatState();
      break;
    }

    // Read SSE stream
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let fullText = '';
    let allParts = [];
    let hasFunctionCalls = false;
    let streamingStarted = false;
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (!line.startsWith('data: ')) continue;
        const jsonStr = line.substring(6).trim();
        if (!jsonStr || jsonStr === '[DONE]') continue;

        try {
          const chunk = JSON.parse(jsonStr);
          const parts = chunk.candidates?.[0]?.content?.parts;
          if (!parts) continue;

          for (const part of parts) {
            if (part.functionCall) {
              hasFunctionCalls = true;
              allParts.push(part);
            } else if (part.text) {
              fullText += part.text;
              allParts.push(part);

              if (!streamingStarted) {
                streamingStarted = true;
                createStreamingBubble();
              }
              updateStreamingBubble(fullText);
            }
          }

          // Token usage
          if (chunk.usageMetadata) {
            const u = chunk.usageMetadata;
            setTokenCount(`${u.promptTokenCount || 0} → ${u.candidatesTokenCount || 0} tokens`);
          }
        } catch (e) {
          // skip malformed chunk
        }
      }
    }

    // Process function calls
    if (hasFunctionCalls) {
      const functionCalls = allParts.filter(p => p.functionCall);
      conversationHistory.push({ role: 'model', parts: allParts });
      saveCurrentChatState();

      const responsePartsList = [];
      showTypingIndicator();

      for (const callPart of functionCalls) {
        const call = callPart.functionCall;
        const name = call.name;
        const args = call.args || {};

        setStatus(`Running: ${name}...`);
        const card = appendToolFeedbackCard(name, args);
        const { result, success } = await executeTool(name, args);
        updateToolFeedbackCard(card, name, success, result);

        responsePartsList.push({
          functionResponse: { name, response: { output: result } }
        });
      }

      conversationHistory.push({ role: 'user', parts: responsePartsList });
      saveCurrentChatState();
      loopCount++;
    } else {
      // Regular text response — finalize streaming
      if (streamingStarted) {
        finalizeStreamingBubble(fullText);
      } else if (fullText) {
        appendMessage('ai', fullText, true);
      } else {
        appendMessage('ai', "Received empty response.", true);
      }

      conversationHistory.push({ role: 'model', parts: [{ text: fullText }] });
      saveCurrentChatState();
      setStatus('Ready');
      break;
    }
  }
}

// ====== OPENAI-COMPATIBLE STREAMING (OpenAI, Mistral, Groq, OpenRouter) ======
async function handleOpenAIStream(endpoint, key, model, systemPrompt) {
  let loopCount = 0;
  const maxLoops = 20;
  const openaiTools = geminiToolsToOpenAI();

  while (loopCount < maxLoops) {
    setStatus(`Thinking... (step ${loopCount + 1})`);

    const messages = geminiHistoryToOpenAI(conversationHistory, systemPrompt);
    const requestBody = {
      model: model,
      messages: messages,
      tools: openaiTools,
      stream: true
    };

    const headers = {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${key}`
    };

    // OpenRouter needs extra headers
    if (endpoint.includes('openrouter.ai')) {
      headers['HTTP-Referer'] = 'https://radial-menu-assistant.app';
      headers['X-Title'] = 'Radial Menu Agent';
    }

    const response = await fetch(endpoint, {
      method: 'POST',
      headers,
      body: JSON.stringify(requestBody)
    });

    removeTypingIndicator();

    if (!response.ok) {
      const errData = await response.json().catch(() => ({}));
      const errMsg = errData.error?.message || errData.message || response.statusText;
      appendMessage('ai', `API Error (${model}): ${errMsg}`, true);
      conversationHistory.pop();
      saveCurrentChatState();
      break;
    }

    // Read SSE stream
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let fullText = '';
    let toolCalls = []; // array of { id, name, arguments_str }
    let streamingStarted = false;
    let buffer = '';
    let promptTokens = 0;
    let completionTokens = 0;

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (!line.startsWith('data: ')) continue;
        const jsonStr = line.substring(6).trim();
        if (!jsonStr || jsonStr === '[DONE]') continue;

        try {
          const chunk = JSON.parse(jsonStr);
          const delta = chunk.choices?.[0]?.delta;
          if (!delta) continue;

          // Text content
          if (delta.content) {
            fullText += delta.content;
            if (!streamingStarted) {
              streamingStarted = true;
              createStreamingBubble();
            }
            updateStreamingBubble(fullText);
          }

          // Tool calls (streamed incrementally)
          if (delta.tool_calls) {
            for (const tc of delta.tool_calls) {
              const idx = tc.index ?? toolCalls.length;
              if (!toolCalls[idx]) {
                toolCalls[idx] = { id: tc.id || `call_${idx}`, name: '', arguments_str: '' };
              }
              if (tc.id) toolCalls[idx].id = tc.id;
              if (tc.function?.name) toolCalls[idx].name += tc.function.name;
              if (tc.function?.arguments) toolCalls[idx].arguments_str += tc.function.arguments;
            }
          }

          // Usage
          if (chunk.usage) {
            promptTokens = chunk.usage.prompt_tokens || 0;
            completionTokens = chunk.usage.completion_tokens || 0;
          }
        } catch (e) {
          // skip malformed chunk
        }
      }
    }

    if (promptTokens || completionTokens) {
      setTokenCount(`${promptTokens} → ${completionTokens} tokens`);
    }

    // Process tool calls
    const validToolCalls = toolCalls.filter(tc => tc.name);
    if (validToolCalls.length > 0) {
      // Convert to Gemini format and store
      const geminiParts = [];
      if (fullText) geminiParts.push({ text: fullText });
      for (const tc of validToolCalls) {
        let parsedArgs = {};
        try { parsedArgs = JSON.parse(tc.arguments_str); } catch (e) {}
        geminiParts.push({
          functionCall: { name: tc.name, args: parsedArgs, _id: tc.id }
        });
      }

      if (streamingStarted) finalizeStreamingBubble(fullText);

      conversationHistory.push({ role: 'model', parts: geminiParts });
      saveCurrentChatState();

      // Execute tools
      const responsePartsList = [];
      showTypingIndicator();

      for (const tc of validToolCalls) {
        let parsedArgs = {};
        try { parsedArgs = JSON.parse(tc.arguments_str); } catch (e) {}

        setStatus(`Running: ${tc.name}...`);
        const card = appendToolFeedbackCard(tc.name, parsedArgs);
        const { result, success } = await executeTool(tc.name, parsedArgs);
        updateToolFeedbackCard(card, tc.name, success, result);

        responsePartsList.push({
          functionResponse: { name: tc.name, response: { output: result }, _tool_call_id: tc.id }
        });
      }

      conversationHistory.push({ role: 'user', parts: responsePartsList });
      saveCurrentChatState();
      loopCount++;
    } else {
      // Regular text response
      if (streamingStarted) {
        finalizeStreamingBubble(fullText);
      } else if (fullText) {
        appendMessage('ai', fullText, true);
      } else {
        appendMessage('ai', "Received empty response.", true);
      }

      conversationHistory.push({ role: 'model', parts: [{ text: fullText }] });
      saveCurrentChatState();
      setStatus('Ready');
      break;
    }
  }
}

// ====== ANTHROPIC STREAMING ======
async function handleAnthropicStream(key, model, systemPrompt) {
  let loopCount = 0;
  const maxLoops = 20;
  const anthropicTools = geminiToolsToAnthropic();

  while (loopCount < maxLoops) {
    setStatus(`Thinking... (step ${loopCount + 1})`);

    const messages = geminiHistoryToAnthropic(conversationHistory);
    const requestBody = {
      model: model,
      max_tokens: 8192,
      system: systemPrompt,
      messages: messages,
      tools: anthropicTools,
      stream: true
    };

    const response = await fetch('https://api.anthropic.com/v1/messages', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-api-key': key,
        'anthropic-version': '2023-06-01',
        'anthropic-dangerous-direct-browser-access': 'true'
      },
      body: JSON.stringify(requestBody)
    });

    removeTypingIndicator();

    if (!response.ok) {
      const errData = await response.json().catch(() => ({}));
      const errMsg = errData.error?.message || response.statusText;
      appendMessage('ai', `API Error (${model}): ${errMsg}`, true);
      conversationHistory.pop();
      saveCurrentChatState();
      break;
    }

    // Read SSE stream
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let fullText = '';
    let toolUses = []; // { id, name, input_json_str }
    let streamingStarted = false;
    let buffer = '';
    let currentBlockType = null;
    let currentBlockIdx = -1;
    let stopReason = null;

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (!line.startsWith('data: ')) continue;
        const jsonStr = line.substring(6).trim();
        if (!jsonStr) continue;

        try {
          const event = JSON.parse(jsonStr);

          if (event.type === 'content_block_start') {
            currentBlockIdx = event.index;
            if (event.content_block?.type === 'tool_use') {
              currentBlockType = 'tool_use';
              toolUses.push({
                id: event.content_block.id,
                name: event.content_block.name,
                input_json_str: ''
              });
            } else {
              currentBlockType = 'text';
            }
          } else if (event.type === 'content_block_delta') {
            if (event.delta?.type === 'text_delta') {
              fullText += event.delta.text;
              if (!streamingStarted) {
                streamingStarted = true;
                createStreamingBubble();
              }
              updateStreamingBubble(fullText);
            } else if (event.delta?.type === 'input_json_delta') {
              const lastTool = toolUses[toolUses.length - 1];
              if (lastTool) {
                lastTool.input_json_str += event.delta.partial_json;
              }
            }
          } else if (event.type === 'message_delta') {
            stopReason = event.delta?.stop_reason;
            if (event.usage) {
              setTokenCount(`${event.usage.input_tokens || 0} → ${event.usage.output_tokens || 0} tokens`);
            }
          }
        } catch (e) {
          // skip malformed
        }
      }
    }

    // Process tool uses
    if (toolUses.length > 0) {
      // Convert to Gemini format and store
      const geminiParts = [];
      if (fullText) geminiParts.push({ text: fullText });
      for (const tu of toolUses) {
        let parsedInput = {};
        try { parsedInput = JSON.parse(tu.input_json_str); } catch (e) {}
        geminiParts.push({
          functionCall: { name: tu.name, args: parsedInput, _id: tu.id }
        });
      }

      if (streamingStarted) finalizeStreamingBubble(fullText);

      conversationHistory.push({ role: 'model', parts: geminiParts });
      saveCurrentChatState();

      // Execute tools
      const responsePartsList = [];
      showTypingIndicator();

      for (const tu of toolUses) {
        let parsedInput = {};
        try { parsedInput = JSON.parse(tu.input_json_str); } catch (e) {}

        setStatus(`Running: ${tu.name}...`);
        const card = appendToolFeedbackCard(tu.name, parsedInput);
        const { result, success } = await executeTool(tu.name, parsedInput);
        updateToolFeedbackCard(card, tu.name, success, result);

        responsePartsList.push({
          functionResponse: { name: tu.name, response: { output: result }, _tool_call_id: tu.id }
        });
      }

      conversationHistory.push({ role: 'user', parts: responsePartsList });
      saveCurrentChatState();
      loopCount++;
    } else {
      // Regular text response
      if (streamingStarted) {
        finalizeStreamingBubble(fullText);
      } else if (fullText) {
        appendMessage('ai', fullText, true);
      } else {
        appendMessage('ai', "Received empty response.", true);
      }

      conversationHistory.push({ role: 'model', parts: [{ text: fullText }] });
      saveCurrentChatState();
      setStatus('Ready');
      break;
    }
  }
}

// ====== AI RESPONSE PROCESSING ======
function processAiResponse(rawText) {
  let displayText = rawText;
  const events = [];

  const jsonBlockRegex = /```json\s*\n([\s\S]*?)\n```/g;
  let match;
  while ((match = jsonBlockRegex.exec(rawText)) !== null) {
    try {
      const obj = JSON.parse(match[1].trim());
      if (obj.action === 'schedule_event') {
        events.push(obj);
        displayText = displayText.replace(match[0], '');
      }
    } catch (e) {}
  }

  const msgEl = appendMessage('ai', displayText, true);

  events.forEach(event => {
    appendEventProposalCard(msgEl, event);
  });
}

function appendEventProposalCard(parentEl, event) {
  const card = document.createElement('div');
  card.className = 'action-card';
  card.innerHTML = `
    <div class="action-header"><span>📅 Schedule Event</span></div>
    <div class="action-body">
      <div class="event-proposal-details">
        <div class="event-prop-row"><span class="event-prop-label">Title:</span><span>${event.title}</span></div>
        <div class="event-prop-row"><span class="event-prop-label">Date:</span><span>${event.date}</span></div>
        <div class="event-prop-row"><span class="event-prop-label">Time:</span><span>${event.time}</span></div>
      </div>
      <button class="action-btn">Schedule Event</button>
    </div>`;

  card.querySelector('.action-btn').addEventListener('click', async () => {
    try {
      const currentEvents = await invoke('get_events');
      currentEvents.push({
        id: Math.random().toString(36).substring(2, 9),
        title: event.title, description: event.description || '',
        date: event.date, time: event.time,
        script: event.script || null, completed: false, last_run: null
      });
      await invoke('save_events', { events: currentEvents });
      card.querySelector('.action-btn').textContent = '✓ Scheduled!';
      card.querySelector('.action-btn').classList.add('success');
      showToast("Event scheduled!");
    } catch (e) {
      showToast("Failed: " + e, "error");
    }
  });

  parentEl.appendChild(card);
}

// ====== MARKDOWN PARSER ======
function parseMarkdown(text) {
  if (!text) return '';
  const codeBlocks = [];

  // Extract code blocks
  let processed = text.replace(/```(\w*)\n([\s\S]*?)\n```/g, (match, lang, code) => {
    const id = codeBlocks.length;
    codeBlocks.push({ lang, code });
    return `__CODE_BLOCK_${id}__`;
  });

  // Escape HTML
  let html = processed
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");

  // Headers
  html = html.replace(/^### (.+)$/gm, '<h3>$1</h3>');
  html = html.replace(/^## (.+)$/gm, '<h2>$1</h2>');
  html = html.replace(/^# (.+)$/gm, '<h1>$1</h1>');

  // Bold & italic
  html = html.replace(/\*\*\*([\s\S]*?)\*\*\*/g, '<strong><em>$1</em></strong>');
  html = html.replace(/\*\*([\s\S]*?)\*\*/g, '<strong>$1</strong>');
  html = html.replace(/\*([\s\S]*?)\*/g, '<em>$1</em>');

  // Inline code
  html = html.replace(/`([^`]+)`/g, '<code>$1</code>');

  // Links
  html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank">$1</a>');

  // Unordered lists
  html = html.replace(/^- (.+)$/gm, '<li>$1</li>');
  html = html.replace(/(<li>.*<\/li>\n?)+/g, '<ul>$&</ul>');

  // Ordered lists
  html = html.replace(/^\d+\. (.+)$/gm, '<li>$1</li>');

  // Newlines
  html = html.replace(/\n/g, '<br>');

  // Fix consecutive <br> after headers/lists
  html = html.replace(/<\/h([123])><br>/g, '</h$1>');
  html = html.replace(/<\/ul><br>/g, '</ul>');
  html = html.replace(/<\/li><br>/g, '</li>');

  // Restore code blocks
  html = html.replace(/__CODE_BLOCK_(\d+)__/g, (match, idStr) => {
    const block = codeBlocks[parseInt(idStr)];
    const escaped = block.code.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    return `<pre><code class="language-${block.lang}">${escaped}</code></pre>`;
  });

  return html;
}

function addCopyButtons(bubble) {
  bubble.querySelectorAll('pre').forEach(pre => {
    if (pre.querySelector('.copy-btn')) return;
    const btn = document.createElement('button');
    btn.className = 'copy-btn';
    btn.textContent = 'Copy';
    btn.addEventListener('click', () => {
      const code = pre.querySelector('code');
      navigator.clipboard.writeText(code ? code.textContent : pre.textContent);
      btn.textContent = 'Copied!';
      setTimeout(() => btn.textContent = 'Copy', 1500);
    });
    pre.appendChild(btn);
  });
}

function addMessageCopyButton(bubble, text) {
  if (bubble.querySelector('.message-copy-btn')) return;
  const btn = document.createElement('button');
  btn.className = 'message-copy-btn';
  btn.title = 'Copy message';
  btn.innerHTML = `
    <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
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
        <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
          <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
        </svg>
      `;
    }, 1500);
  });
  bubble.appendChild(btn);
}

// ====== CHAT HISTORY & APP DETECTION ======

function generateUUID() {
  if (window.crypto && window.crypto.randomUUID) {
    return window.crypto.randomUUID();
  }
  return Math.random().toString(36).substring(2, 15) + Math.random().toString(36).substring(2, 15);
}

function loadSavedChats() {
  try {
    const raw = localStorage.getItem('ai_assistant_chats');
    const parsed = raw ? JSON.parse(raw) : [];
    savedChats = Array.isArray(parsed) ? parsed : [];
  } catch (e) {
    console.error("Failed to load saved chats", e);
    savedChats = [];
  }
}

function saveChatsToStorage() {
  try {
    localStorage.setItem('ai_assistant_chats', JSON.stringify(savedChats));
  } catch (e) {
    console.error("Failed to save chats to storage", e);
  }
}

function saveCurrentChatState() {
  if (conversationHistory.length === 0) return;

  let title = "New Chat";
  const firstUserTurn = conversationHistory.find(turn => turn.role === 'user' && turn.parts?.[0]?.text);
  if (firstUserTurn) {
    const text = firstUserTurn.parts[0].text;
    title = text.length > 28 ? text.substring(0, 25) + '...' : text;
  }

  const existingIndex = savedChats.findIndex(c => c.id === activeChatId);
  if (existingIndex !== -1) {
    savedChats[existingIndex].history = conversationHistory;
    savedChats[existingIndex].timestamp = Date.now();
    if (savedChats[existingIndex].title === "New Chat" && title !== "New Chat") {
      savedChats[existingIndex].title = title;
    }
  } else {
    savedChats.unshift({
      id: activeChatId,
      title: title,
      history: conversationHistory,
      timestamp: Date.now()
    });
  }

  saveChatsToStorage();
  renderSidebar();
}

function renderSidebar() {
  const sidebarList = document.getElementById('sidebar-list');
  if (!sidebarList) return;

  sidebarList.innerHTML = '';

  if (!Array.isArray(savedChats) || savedChats.length === 0) {
    const emptyLi = document.createElement('li');
    emptyLi.style.padding = '12px';
    emptyLi.style.fontSize = '12px';
    emptyLi.style.color = 'var(--text-dim)';
    emptyLi.style.textAlign = 'center';
    emptyLi.textContent = 'No saved chats';
    sidebarList.appendChild(emptyLi);
    return;
  }

  savedChats.forEach(chat => {
    if (!chat || !chat.id) return;
    const li = document.createElement('li');
    li.className = 'history-item';
    if (chat.id === activeChatId) {
      li.classList.add('active');
    }

    li.addEventListener('click', () => {
      if (chat.id !== activeChatId) {
        loadChatSession(chat.id);
      }
    });

    const titleSpan = document.createElement('span');
    titleSpan.className = 'history-title';
    titleSpan.textContent = chat.title || "Untitled Chat";
    li.appendChild(titleSpan);

    const deleteBtn = document.createElement('button');
    deleteBtn.className = 'history-delete-btn';
    deleteBtn.innerHTML = '✕';
    deleteBtn.title = 'Delete Chat';
    deleteBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      deleteChat(chat.id);
    });
    li.appendChild(deleteBtn);

    sidebarList.appendChild(li);
  });
}

function startNewChat() {
  activeChatId = generateUUID();
  conversationHistory = [];

  const container = document.getElementById('chat-messages');
  container.innerHTML = `
    <div class="welcome-screen" id="welcome-screen">
      <div class="welcome-icon">⚡</div>
      <h2>Radial Menu Agent</h2>
      <p id="welcome-desc-text">I can build plugins, edit files, run commands, and customize your radial menu — all in real-time.</p>
      <div class="chips-container">
        <button class="prompt-chip" data-prompt="Create a plugin that adds Steam, Epic Games, and Discord launchers to my radial menu">🎮 Game Launcher Plugin</button>
        <button class="prompt-chip" data-prompt="Build a live CPU and RAM monitor widget for my desktop overlay">📊 System Monitor</button>
        <button class="prompt-chip" data-prompt="Switch my app to a dark purple theme with higher blur">🎨 Change Theme</button>
        <button class="prompt-chip" data-prompt="Create a developer tools plugin with VS Code, Terminal, and Git shortcuts">🛠️ Dev Tools Plugin</button>
      </div>
    </div>
  `;

  setupChips();
  updateWelcomeGreeting();
  renderSidebar();
}

function appendCompletedToolCard(name, args, output) {
  const card = appendToolFeedbackCard(name, args);
  const isSuccess = !output.toString().toLowerCase().includes('error') && !output.toString().toLowerCase().includes('failed');
  updateToolFeedbackCard(card, name, isSuccess, output);
}

function loadChatSession(id) {
  const chat = savedChats.find(c => c.id === id);
  if (!chat) return;

  activeChatId = id;
  conversationHistory = JSON.parse(JSON.stringify(chat.history));

  const container = document.getElementById('chat-messages');
  container.innerHTML = '';

  conversationHistory.forEach((turn) => {
    if (turn.role === 'user') {
      const firstPart = turn.parts?.[0];
      if (firstPart && firstPart.functionResponse) {
        turn.parts.forEach(part => {
          if (part.functionResponse) {
            const name = part.functionResponse.name;
            const responseData = part.functionResponse.response || {};
            const output = responseData.output || '';
            
            let args = {};
            for (let i = conversationHistory.indexOf(turn) - 1; i >= 0; i--) {
              const prevTurn = conversationHistory[i];
              if (prevTurn.role === 'model' && prevTurn.parts) {
                const matchingCall = prevTurn.parts.find(p => p.functionCall && p.functionCall.name === name);
                if (matchingCall) {
                  args = matchingCall.functionCall.args || {};
                  break;
                }
              }
            }
            appendCompletedToolCard(name, args, output);
          }
        });
      } else if (firstPart && firstPart.text) {
        appendMessage('user', firstPart.text, false);
      }
    } else if (turn.role === 'model') {
      const firstPart = turn.parts?.[0];
      if (firstPart && firstPart.functionCall) {
        turn.parts.forEach(part => {
          if (part.text) {
            appendMessage('ai', part.text, true);
          }
        });
      } else if (firstPart && firstPart.text) {
        appendMessage('ai', firstPart.text, true);
      }
    }
  });

  renderSidebar();
  container.scrollTop = container.scrollHeight;
}

function deleteChat(id) {
  savedChats = savedChats.filter(c => c.id !== id);
  saveChatsToStorage();
  renderSidebar();

  if (id === activeChatId) {
    startNewChat();
  }
}

async function updateWelcomeGreeting() {
  const welcomeDesc = document.getElementById('welcome-desc-text');
  if (!welcomeDesc) return;

  try {
    const activeApp = await invoke('get_active_app');
    if (activeApp === 'Minecraft') {
      welcomeDesc.textContent = "Hi! I see you're playing Minecraft! Do you want to start a building project or do you need help with some crafting recipes?";
    } else if (activeApp === 'VSCode') {
      welcomeDesc.textContent = "Hey! I see you're in VS Code. Do you need help coding, writing a script, or configuring your workspace?";
    } else if (activeApp === 'Notepad') {
      welcomeDesc.textContent = "I see you have Notepad open. Do you want to draft a document or write some notes?";
    } else if (activeApp === 'Browser') {
      welcomeDesc.textContent = "I see you're browsing the web. Do you need help finding information or summarizing a web page?";
    } else if (activeApp && activeApp !== 'None') {
      welcomeDesc.textContent = `I see you have ${activeApp} open. How can I help you with it or anything else today?`;
    } else {
      welcomeDesc.textContent = "I can build plugins, edit files, run commands, and customize your radial menu — all in real-time.";
    }
  } catch (e) {
    console.error("Failed to check active app", e);
    welcomeDesc.textContent = "I can build plugins, edit files, run commands, and customize your radial menu — all in real-time.";
  }
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



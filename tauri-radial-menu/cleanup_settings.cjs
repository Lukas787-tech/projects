const fs = require('fs');
const path = require('path');
const file = path.join(process.env.APPDATA, 'com.ralfm.tauri-radial-menu', 'settings.json');
if (fs.existsSync(file)) {
  const data = JSON.parse(fs.readFileSync(file));
  const beforeLen = data.actions.length;
  // Let's filter out everything that looks like the game launchers plugin
  data.actions = data.actions.filter(a => !['steam', 'epic', 'xbox', 'game-launchers', 'games'].includes(a.id));
  if (data.actions.length < beforeLen) {
    fs.writeFileSync(file, JSON.stringify(data, null, 2));
    console.log("Cleaned up settings.json");
  }
}

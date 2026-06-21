const https = require('https');

const apiKey = "YOUR_API_KEY_HERE";
const url = `https://generativelanguage.googleapis.com/v1beta/models?key=${apiKey}`;

https.get(url, (res) => {
  let data = '';
  res.on('data', chunk => data += chunk);
  res.on('end', () => {
    try {
      const json = JSON.parse(data);
      if (json.models) {
        const available = json.models
          .filter(m => m.supportedGenerationMethods && m.supportedGenerationMethods.includes('generateContent'))
          .map(m => m.name.replace('models/', ''));
        console.log("AVAILABLE MODELS:", available.join(', '));
      } else {
        console.log("ERROR:", json);
      }
    } catch (e) {
      console.log("PARSE ERROR:", e);
    }
  });
}).on('error', err => console.log("REQ ERROR:", err));

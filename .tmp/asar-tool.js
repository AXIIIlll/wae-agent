// Read-only helper for inspecting the packed DSH app.asar.
// Usage:
//   asar-tool.js list <dir>
//   asar-tool.js grep <dir> <literal> [maxBytes]
const fs = require('node:fs');
const path = require('node:path');

const [command, target, extra, maxBytesArg] = process.argv.slice(2);

if (command === 'list') {
  for (const name of fs.readdirSync(target).sort()) {
    let kind = '?';
    try {
      const st = fs.statSync(path.join(target, name));
      kind = st.isDirectory() ? 'DIR ' : 'FILE';
    } catch {}
    console.log(`${kind} ${name}`);
  }
} else if (command === 'grep') {
  const literal = extra;
  const maxBytes = Number(maxBytesArg || 2_000_000);
  let hits = 0;
  const walk = (dir) => {
    let entries;
    try { entries = fs.readdirSync(dir); } catch { return; }
    for (const name of entries.sort()) {
      if (name === 'node_modules' || name === '.git') continue;
      const full = path.join(dir, name);
      let st;
      try { st = fs.statSync(full); } catch { continue; }
      if (st.isDirectory()) { walk(full); continue; }
      if (!/\.(md|json|yml|yaml|ts|js|cjs|mjs|txt)$/.test(name)) continue;
      if (st.size > maxBytes) continue;
      let text;
      try { text = fs.readFileSync(full, 'utf8'); } catch { continue; }
      if (!text.includes(literal)) continue;
      const lines = text.split(/\r?\n/);
      lines.forEach((line, index) => {
        if (line.includes(literal)) {
          hits += 1;
          const shown = line.length > 200 ? line.slice(0, 200) + '…' : line;
          console.log(`${full}:${index + 1}: ${shown}`);
        }
      });
    }
  };
  walk(target);
  console.log(`-- ${hits} matching line(s)`);
} else {
  console.error('unknown command');
  process.exit(2);
}

// Flattens a chat component (JSON on 1.20.1, NBT from 1.20.3) into visible text plus one resolved
// colour per character, so the Adventure (Paper) and legacy (Spigot) paths can be compared exactly.
const NAMED = {black: '#000000', dark_blue: '#0000aa', dark_green: '#00aa00', dark_aqua: '#00aaaa', dark_red: '#aa0000',
  dark_purple: '#aa00aa', gold: '#ffaa00', gray: '#aaaaaa', dark_gray: '#555555', blue: '#5555ff', green: '#55ff55',
  aqua: '#55ffff', red: '#ff5555', light_purple: '#ff55ff', yellow: '#ffff55', white: '#ffffff'};
function walk(c, color, out) {
  if (c === null || c === undefined) return;
  if (typeof c === 'string' || typeof c === 'number') { for (const ch of String(c)) out.push([ch, color]); return; }
  if (Array.isArray(c)) { if (!c.length) return; const parent = c[0]; const own = typeof parent === 'object' && parent && parent.color !== undefined ? (NAMED[parent.color] || String(parent.color).toLowerCase()) : color; walk(parent, color, out); c.slice(1).forEach(x => walk(x, own, out)); return; }
  const own = c.color !== undefined ? (NAMED[c.color] || String(c.color).toLowerCase()) : color;
  const text = c.text !== undefined ? c.text : (c[''] !== undefined ? c[''] : (c.translate !== undefined ? '{' + c.translate + '}' : ''));
  walk(String(text), own, out);
  if (c.extra) for (const x of c.extra) walk(x, own, out);
}
function flatten(component) {
  let c = component;
  if (typeof c === 'string') { try { c = JSON.parse(c); } catch (e) { /* plain text */ } }
  const out = [];
  walk(c, null, out);
  const runs = [];
  for (const [ch, color] of out) { const last = runs[runs.length - 1]; if (last && last.color === color) last.text += ch; else runs.push({text: ch, color}); }
  return {plain: out.map(x => x[0]).join(''), colors: out.map(x => x[1]), runs};
}
module.exports = {flatten};

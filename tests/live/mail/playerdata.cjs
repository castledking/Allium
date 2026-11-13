// Test-only independent stopped-playerdata observation; no production classes are used.
const fs = require('node:fs');
const zlib = require('node:zlib');
const path = require('node:path');
const nbt = require(path.join('/mnt/storage/.allium-validation/infra/client/node_modules', 'prismarine-nbt'));
try {
  const file = process.argv[2];
  if (!file || !file.includes('/.allium-validation/')) throw Error('owned validation playerdata path required');
  const max = 8 * 1024 * 1024;
  if (fs.statSync(file).size > max) throw Error('compressed playerdata exceeds bound');
  const bytes = zlib.gunzipSync(fs.readFileSync(file), {maxOutputLength: max});
  const root = nbt.simplify(nbt.parseUncompressed(bytes, 'big'));
  const inventory = root.Inventory ?? root.inventory;
  if (!Array.isArray(inventory)) throw Error('missing or unsupported native player inventory');
  const receipts = root.BukkitValues?.['allium:mail_receipts'] ?? null;
  if (receipts !== null && typeof receipts !== 'string') throw Error('non-string mail receipt');
  const stacks = inventory.map(item => {
    const count = item.Count ?? item.count;
    const slot = item.Slot ?? item.slot;
    if (typeof item.id !== 'string' || !Number.isInteger(count) || count < 1 || !Number.isInteger(slot))
      throw Error('malformed native inventory stack');
    return {slot, id: item.id, count, data: item.tag ?? item.components ?? null};
  });
  console.log(JSON.stringify({dataVersion: root.DataVersion, receipts, inventory: stacks},
    (_, value) => typeof value === 'bigint' ? value.toString() : value));
} catch (error) {
  console.error('PLAYERDATA-FAIL ' + error.message);
  process.exitCode = 1;
}

const {test} = require('node:test'), assert = require('node:assert/strict');
const {allReady, assertProviderChecks, assertReloadEvents} = require('./verification.cjs');
test('readiness requires the complete status of all three providers', () => {
  assert(allReady('S05-STATUS {nexo=READY, craftengine=READY, oraxen=READY}'));
  assert(!allReady('S05-STATUS {nexo=LOADING, craftengine=READY, oraxen=READY}'));
  assert(!allReady('S05-STATUS {nexo=READY, craftengine=FAILED, oraxen=READY}'));
  assert(!allReady('S05-DONE verify'));
});
test('READY and inventory assertions cannot replace successful native reload event assertions', () => {
  assert.throws(() => assertReloadEvents('S05-STATUS {nexo=READY, craftengine=READY, oraxen=READY}'), /Missing/);
  assert.throws(() => assertReloadEvents('S05-PASS reload-event-loading-nexo'), /craftengine/);
  assert.doesNotThrow(() => assertReloadEvents('S05-PASS reload-event-loading-nexo\nS05-PASS reload-event-loading-craftengine'));
});
test('a DONE marker cannot hide skipped providers', () => {
  assert.throws(() => assertProviderChecks('S05-DONE verify', 'verify'), /Missing nexo/);
  assert.throws(() => assertProviderChecks('S05-PASS nexo-create-signed\nS05-DONE create', 'create'), /Missing craftengine/);
});
test('both provider invariant sets and native CE execution must be present', () => {
  const log = ['nexo', 'craftengine'].flatMap(p => ['held-signature-unchanged', 'held-uuid-unchanged', 'held-state-unchanged', 'held-still-verifies', 'held-amount-unchanged'].map(s => `S05-PASS ${p}-${s}`)).join('\n');
  assert.doesNotThrow(() => assertProviderChecks(log, 'verify'));
  assert.throws(() => assertProviderChecks(log, 'nativeupdate'), /did not report running/);
  assert.doesNotThrow(() => assertProviderChecks(log + '\nS05-PASS craftengine-native-updater-ran', 'nativeupdate'));
});

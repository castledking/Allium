const allReady = output => /S05-STATUS \{nexo=READY, craftengine=READY, oraxen=READY\}/.test(output);
function assertProviderChecks(output, action) {
  const suffixes = action === 'create' ? ['create-signed']
    : action === 'checkpoint' ? ['checkpoint-valid-signature', 'checkpoint-spent-state']
    : ['held-signature-unchanged', 'held-uuid-unchanged', 'held-state-unchanged', 'held-still-verifies', 'held-amount-unchanged'];
  for (const provider of ['nexo', 'craftengine']) {
    for (const suffix of suffixes) {
      if (!output.includes(`S05-PASS ${provider}-${suffix}`)) throw Error(`Missing ${provider} ${action} assertion: ${suffix}`);
    }
  }
  if (action === 'nativeupdate' && !output.includes('S05-PASS craftengine-native-updater-ran')) throw Error('CE native updater did not report running');
}
function assertReloadEvents(output) {
  for (const provider of ['nexo', 'craftengine']) {
    if (!output.includes(`S05-PASS reload-event-loading-${provider}`)) throw Error('Missing successful native reload event: ' + provider);
  }
}
module.exports = {allReady, assertProviderChecks, assertReloadEvents};

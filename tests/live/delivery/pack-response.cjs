// Test-only: what an S06c gate bot does with a resource pack offer. accept: ACCEPTED, download the
// offered loopback URL, check its SHA-1 against the offered hash, then DOWNLOADED (1.20.3+) and
// SUCCESSFULLY_LOADED, or FAILED_DOWNLOAD on a mismatch. decline: DECLINED, and no download.
// Action ids: 0 SUCCESSFULLY_LOADED, 1 DECLINED, 2 FAILED_DOWNLOAD, 3 ACCEPTED, 4 DOWNLOADED.
const http = require('node:http'), crypto = require('node:crypto');
function download(url) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    if (u.hostname !== '127.0.0.1' || u.protocol !== 'http:') return reject(Error('TEST ONLY: loopback http URLs only, got ' + url));
    http.get(url, res => {
      if (res.statusCode !== 200) { res.resume(); return reject(Error('HTTP ' + res.statusCode)); }
      const parts = [];
      res.on('data', d => parts.push(d));
      res.on('end', () => resolve(Buffer.concat(parts)));
    }).on('error', reject);
  });
}
async function respond(offer, mode, downloadedStatus, reply) {
  const log = o => console.log('DELIVG-PACK ' + JSON.stringify(o));
  log(Object.assign({event: 'offer'}, offer));
  if (mode === 'decline') { reply(1); log({event: 'sent', uuid: offer.uuid, sent: ['DECLINED']}); return; }
  reply(3);
  const sent = ['ACCEPTED'];
  let data;
  try { data = await download(offer.url); } catch (e) { reply(2); log({event: 'sent', uuid: offer.uuid, error: String(e), sent: [...sent, 'FAILED_DOWNLOAD']}); return; }
  const sha1 = crypto.createHash('sha1').update(data).digest('hex');
  const matches = sha1 === String(offer.hash).toLowerCase();
  if (matches && downloadedStatus) { reply(4); sent.push('DOWNLOADED'); }
  reply(matches ? 0 : 2);
  sent.push(matches ? 'SUCCESSFULLY_LOADED' : 'FAILED_DOWNLOAD');
  log({event: 'sent', uuid: offer.uuid, bytes: data.length, sha1, matches, sent});
}
module.exports = {respond};

// Test-only: what the S06b gate bot does with a resource pack offer. It downloads the pack from the
// offered loopback URL, checks the SHA-1 the server sent, and runs Allium's validator for this
// client's format against Mojang's client jar for this version (JSON, references, vanilla parity,
// render simulation). The bot does not render; a clean validator report is its "loaded" result.
// Statuses use the vanilla action ids: 0 SUCCESSFULLY_LOADED, 1 DECLINED, 2 FAILED_DOWNLOAD,
// 3 ACCEPTED, 4 DOWNLOADED (1.20.3+), 6 FAILED_RELOAD.
const http = require('node:http'), crypto = require('node:crypto'), fs = require('node:fs'), path = require('node:path');
const {spawnSync} = require('node:child_process');

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

async function handleOffer({url, hash, uuid, mode, cfg, downloadedStatus, offerIndex}, respond) {
  const log = o => console.log('S06B-PACK ' + JSON.stringify(o));
  log({event: 'offer', url, hash, uuid, mode});
  if (mode === 'decline') { respond(1); log({event: 'sent', status: 'DECLINED'}); return; }
  respond(3);
  const sent = ['ACCEPTED'];
  let data;
  try { data = await download(url); } catch (e) { respond(2); log({event: 'result', error: String(e), sent: [...sent, 'FAILED_DOWNLOAD']}); return; }
  const sha1 = crypto.createHash('sha1').update(data).digest('hex');
  const file = path.join(cfg.workDir, `bot-download-${offerIndex}.zip`);
  fs.writeFileSync(file, data);
  if (downloadedStatus) { respond(4); sent.push('DOWNLOADED'); }
  const hashMatches = sha1 === String(hash).toLowerCase();
  const report = path.join(cfg.workDir, `bot-validate-${offerIndex}.json`);
  const run = spawnSync(cfg.java, ['-cp', cfg.alliumJar, 'codes.castled.allium.pack.CorePackMain', 'validate', file, cfg.manifest,
    '--lane', cfg.label, cfg.format, cfg.clientJar, '--report', report], {encoding: 'utf8'});
  let clean = false;
  try { clean = JSON.parse(fs.readFileSync(report, 'utf8')).clean === true; } catch (e) { /* reported below */ }
  const ok = hashMatches && clean && run.status === 0;
  respond(ok ? 0 : (hashMatches ? 6 : 2));
  sent.push(ok ? 'SUCCESSFULLY_LOADED' : (hashMatches ? 'FAILED_RELOAD' : 'FAILED_DOWNLOAD'));
  log({event: 'result', bytes: data.length, sha1, offeredHash: hash, hashMatches, validatorExit: run.status, clean, report, sent,
       validatorStderr: (run.stderr || '').slice(0, 500)});
}

module.exports = {handleOffer};

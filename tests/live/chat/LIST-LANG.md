# List-form language and message routing

Unit delta **+12**, 821 → **833**, full check/shadowJar with zero failures/errors/skips.
New codec/loader tests cover multiline text, pitch-first directives, omitted volume,
custom namespaces, explicit silence, old strings/sections and import compatibility.
Dedicated back/spawn/nickname acknowledgements are checked after persistence.

Native Spigot 1.20.1 and Paper 26.3 SQLite gates pass on one immutable jar:
- Baseline: 31/31 each, including Spanish list text/colors, bare vanilla sound ID,
  actual pitch 1.35 and server default volume 0.5 with no entry volume.
- Chat: 125/125 each, nickname storage/feedback and deletion/audience regression.
- Teleport: all 15 full named cases each, including dedicated back/spawn keys,
  cross-world movement, restart, cooldowns, refusal and history-write failures.

Exact counts, manifest digests and paths are in [machine evidence](list-lang.json).
Frozen jar SHA256 `1d2d4e8ba7ff1da0a3f8ab60eb2f8c4db74e61af663a5d3a72cf24188341eaab`.
The initial Paper teleport attempt passed behavior but failed final source-file
inventory integrity when an unrelated new hide-policy draft was added mid-run.
That draft was moved out of the repo; the complete Paper lane was rerun unchanged
and passes integrity as well. The failed attempt is retained, not relabelled.

Existing operator lang files are not rewritten. See [format and upgrade rules](../../../docs/language-defaults.md)
and the [static key audit](../../../docs/message-key-audit.md).

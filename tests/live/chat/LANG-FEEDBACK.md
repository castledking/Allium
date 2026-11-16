# GC and deletion language feedback fix

The screenshot's uncolored GC and deletion responses came from direct
`sendMessage` calls, bypassing the configured prefix and language renderer.
GC diagnostic rows and deletion confirmations/refusals now use language keys,
with matching English/Spanish defaults. GC rows stay silent; deletion success
uses a quiet page-turn sound. Existing installed strings/sound choices remain
unchanged; see [upgrade behavior](../../../docs/language-defaults.md).

Unit delta **+3**, 818 → **821**, zero failures/errors/skips. Regression coverage
checks configurable GC output, language-pipeline deletion feedback, and retention
of old plain-string messages when bundled defaults add sounds. Existing bundled
lang parity tests also cover all new keys, colors and placeholders.

| Final-jar gate | Spigot 1.20.1 | Paper 26.3 |
| --- | ---: | ---: |
| Utilities, including actual GC prefix/body colors | 117/117 | 117/117 |
| Chat, including actual deletion prefix/success colors | 125/125 | 125/125 |

Frozen jar SHA256: `bcc4640c0a507523d05599f1a433cd0a5719824f89f5c7fdbdbcd5412508784e`.
Disposable loopback lanes only; no production data/config edits.
Reports and checksums are in [machine evidence](lang-feedback.json). All four
lanes pass on this same jar. The existing chat packet-capture behavior is
unchanged; the user confirmed system replay works after installing PacketEvents.
Other hardcoded chat feedback is outside this focused fix.

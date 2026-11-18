# Review ledger after S25

Spigot means 1.20.1 and Paper means 26.3. Counts are per lane unless indicated. Live totals come from the linked suite records. A unit delta is the change in executed test cases.

| Commit | Scope | Unit delta and total | Live lanes and result | Limits |
| --- | --- | --- | --- | --- |
| `19d5850` | English and Spanish defaults and stock sounds | +1 → 818 | Bundled language subsequently exercised by the language-feedback and list gates | No isolated live result attributed to this hash |
| `044451e` | Configurable GC and deletion feedback | +3 → 821 | Both, utilities 117/117 and chat 125/125 | Configured prefix, text and color |
| `fcbab5b` | Pitch-first language lists and dedicated command acknowledgements | +12 → 833 | Both, baseline 31/31, chat 125/125, teleport 15/15 cases | Earlier Paper source-inventory failure retained |
| `fb4a2b7` | Automatic AFK admission permission | +2 → 835 | Both, player info 35/35 | Manual AFK and kick exemption have separate permissions |
| `06b3c4e` | Hide policy, permission resolver and hint pack | +42 → 877 | Both, hide 18/18 | PacketEvents present and absent |
| `f82301a` | Offline teleport aliases and stored logout destinations | +10 → 887 | Both, teleport 156/156 | Offline locations use the saved position |
| `153cb55` | Hide-aware help and locale synchronization | +9 → 896 | Both, hide/help 24/24 | Command discovery and stock locale updates |
| `3e6b7e0` | Item aliases, companion travel and enchantment safety | +23 → 919 | Both, parity 33/33 | Full teleport sweep used the prior jar with unchanged teleport source |
| `19186ad` | Numeric item IDs and sign usability | +10 → 929 | Spigot IDs 47/47, Paper IDs 48/48, sign gates 381/381 both | Paper ID lane includes an extra travel round trip |
| `3010c05` | Midair back destinations, unsafe grant and channel color | +3 → 932 | Both, flight safety 50/50 | Unsafe bypass remains explicit |
| `0ae7ee2` | Flight restoration and persistent slowfall | +4 → 936 | Both, flight 55/55 | Authorized login flight and slowfall |
| `712541a` | Language-color audit and configurable balance feedback | +4 → 940 | Both, message audit 61/61 | Existing customized stock-message replacements are preserved |
| This review commit | Repair sign presentation, paid-repair refusal, currency display, money alias, trade log and command ownership probes | +4 → 944 | Post-disable Spigot signs 384/384 and inspection 98/98. Both ownership lanes 62/62 | Paid repair stays disabled pending durable recovery and a live halt test |

S25 closed at 817 tests. S26, S27 and S28 were skipped as numbered slices in this branch history. The intervening commits above landed independently. CreativeManager and `/restore` snapshots remain open.

Sources: [unit ledger](../tests/live/regression/UNIT-DELTAS.md), [GC and deletion](../tests/live/chat/LANG-FEEDBACK.md), [language lists](../tests/live/chat/LIST-LANG.md), [AFK](../tests/live/playerinfo/AFK-AUTO.md), [hide](../tests/live/hide/RESULTS.md), [offline travel](../tests/live/teleport/OFFLINE.md), [help](../tests/live/hide/HELP.md), [parity](../tests/live/parity/RESULTS.md), [numeric IDs](../tests/live/parity/LEGACY-IDS.md), [sign usability](../tests/live/signs/USABILITY.md), [flight](../tests/live/flight/RESULTS.md), [language audit](language-color-audit.md).

## Reviewer context since S19

- S20 replaced the economy scaffold with exact SQLite money, a transaction journal,
  commands, a Vault adapter, and receipt-backed paid kit and mail reservations.
  Vault remains opt-in. The measured latency gate did not approve release.
- S21 added the eleven service signs with per-face identity and permissions.
  S22 produced the server/player shop design. Shop trading remains a design.
- S23 added persisted nicknames, channel routing, Discord relay, and exact-ID
  deletion and replay. S24 added read-only and explicitly authorized editable
  inventory and ender-chest inspection, including offline file guards.
- S25 added the parity utility command families and their two native lanes.

## Review evidence

The pre-disable artifact `34bf96780fceffc354e7c56cd2a06aa60bd343db20d5c6b486f13e90cb5059fc`
passed 384/384 on each sign/economy lane. The original Spigot run scored 380/384
because the external input path was the mutable Gradle output. The four failures
were the first after-stop check, the second before-boot check, the second
after-stop check, and the final check. The lane copy retained its original hash.
The frozen rerun passed all four without a rebuild.

The post-disable artifact `3be3340d46f14d3d825db53dae17de48e5dc3fa09e9b520e1613722980d8600f`
passed the Spigot sign/economy gate **384/384**, including a live priced-repair
refusal with unchanged damage and balance and no trade row. The first attempt
hit a shared test-port collision before exercising signs. No paid-repair halt
test was claimed. The same frozen artifact passed Spigot inspection **98/98**.
The offline target held seeded `K` and `S` player-data markers and a pending
SQL workflow hold. The editable command was refused, read-only inspection opened,
and the player-data hash stayed identical. Clearing the fixture hold allowed an
offline edit that preserved the markers through restart.

Run records are under `.allium-validation` in the following locations.

| Run | Record relative to validation root |
| --- | --- |
| Original Spigot integrity failure | `20261114/s20-economy/runs/repair-pricing-01/manifest.json` |
| Frozen pre-disable Spigot | `20261114/s20-economy/runs/repair-pricing-spigot-frozen/manifest.json` |
| Frozen pre-disable Paper | `20261114/s20-economy/runs/repair-pricing-paper-01/manifest.json` |
| Paid repair refused | `20261114/s20-economy/runs/review-paid-block-spigot-r2/manifest.json` |
| Offline edit guard | `20261115/s24-inspection/runs/review-pending-ks-spigot/manifest.json` |
| Utility command ownership | `20261115/s25-utilities/runs/review-command-{spigot,paper}-final/summary.json` |

The November 15 kernel log shows UAS write aborts, I/O errors on `sda4`,
an aborted ext4 journal and a USB disconnect. Direct SMART access was denied,
so the underlying hardware or connection fault remains unconfirmed.

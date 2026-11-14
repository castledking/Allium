# Recode history and branch audit (2026-11-14)

The earlier history cleanup rewrote 30 commits after `8e8fa8e`, ending at the
S07 commit: `1e40f19` became `6a74e7f`. The old chain remains available locally as
`backup/main-before-history-cleanup`. Comparing those final trees changes only
README and roadmap wording removing the explicit excluded-content sentence; no
runtime source, tests, configuration, or assets differ. This describes the tree
comparison and does not claim the original rewrite mechanism is recoverable.

Separately, local `main` was renamed `feat/recode` at `44ed23b`; that rename
changed no commit hashes. Subsequent S16–S18 commits were ordinary new commits.
No history was rewritten during this audit and no force push was used.

| Old hash | Current hash | Commit |
| --- | --- | --- |
| `d1ea652` | `6dbce96` | docs: add recode roadmap, implementation checklist, and initial give design |
| `fd0df51` | `0bada40` | docs: align give/item design with roadmap contracts |
| `bfa90de` | `8c1fc6d` | build(s01): pin Spigot 1.20/Java 17 baseline, retire pre-1.20 compat, and fix 26.3 boot |
| `c2933dc` | `5dd535a` | docs: plan own text formatting/animation module, retire Takion |
| `2b22b3a` | `cfc373a` | test(s01): verify 5-lane command matrix, extract text formatting design, and record InvUI 26.x risk |
| `092e89d` | `a7b4948` | feat(s01.5a): add static text formatter, own message sound playback, and retire Takion |
| `386fea9` | `1276549` | feat(s04): implement core item contract, behavior identity binding, and provider readiness states |
| `4cd723a` | `410b2a9` | feat(s04): add HMAC-signed behavior identity, schema version, and platform-neutral core |
| `de46a45` | `132180b` | feat(s02): implement platform-neutral command spec, family policy, and port 5 commands |
| `cd145a4` | `da20b3f` | feat(s02,s04): sign canonical item state with keyring rotation and refresh Brigadier commands |
| `3be33f9` | `6540cb3` | fix(items): verify state mutations and version signing payloads; prove client command refresh |
| `ddfeeca` | `df3ffcd` | feat(s05): isolate item providers and record native validation gates |
| `592f10f` | `e9fb117` | feat(s05): verify supplied provider runtimes and expand native gates |
| `6b49adb` | `81da4f0` | test(s05): validate CE count fix with native inventory regressions |
| `3a8b565` | `f43a505` | fix(s05): warn on provider migration hazards and qualify runtime support |
| `2e8dbfd` | `e5bee06` | feat(s06a): add Mob Disarmer behavior with protection service and live gate |
| `aa0c9db` | `f0ca8df` | fix(s06a): add configurable drop rule and live rejected-write fault case |
| `8ed7361` | `6ecd1b8` | feat(s06b): generate a deterministic core resource pack from an asset manifest |
| `21c3fe6` | `a845b9b` | fix(s06b): refresh recharge visuals on events and audit carrier collisions |
| `ef54a6b` | `0721653` | feat(s04): add item library and the M1 /give harness |
| `304e5f2` | `f1bbfe8` | fix(s04): match vanilla /give targeting on Paper and report degraded skulls |
| `1c67312` | `93ea1a3` | feat(s03): add player preferences with safe session loading |
| `52ebe48` | `44760ac` | fix(s03): retry failed loads, fail closed, and bound the shutdown drain |
| `bb5b979` | `1fc637b` | feat(m1): add the scheduler boundary and record the M1 close-out |
| `6f9fec2` | `fc438af` | test(m1): re-record the text formatter and item contract gates on five lanes |
| `6d4e1d6` | `1b72158` | feat(s09): add the minimal versioned addon API |
| `019a226` | `ff880df` | feat(s06c): deliver the core pack standalone or through RPM's mixer |
| `2911fb3` | `520222c` | feat(s09): add the AlliumAddon base class |
| `9d465ad` | `1fd92f0` | fix(s06c): re-check the hosted pack on /allium reload and allow a local pack build |
| `1e40f19` | `6a74e7f` | feat(s07): add private messaging with a shared delivery policy |

At resumption, `git log --oneline origin/feat/recode..HEAD` was:

```text
506c0dd fix(mail): release recovery inventory lock when in-flight transfer defers retry
3a3f9e4 fix(s17,s18): CommandMap mute identity, Spigot vanish warning, /whois op default, and M3 table
```

`git merge-base --is-ancestor origin/feat/recode HEAD` returned exit 0.
Gitleaks 8.30.1 scanned both commits (35.22 KB), with no findings. A regular
`git push origin feat/recode` advanced `a81cf3b` to `506c0dd`.

Remote branch inspection confirms only `master` (legacy, `3f86b2b`) and
`feat/recode`. Remote HEAD points to `master`. The requested redundant `main`
branch removal was already complete before resumption; no additional deletion
was performed.

Unit-test deltas: `3a3f9e4` adds four tests (545 → 549); `506c0dd` adds one test
(549 → 550). The latter fixes a mail inventory lock left held when another
in-flight transfer caused recovery to defer. Regression evidence is recorded
separately from the original slice gates.

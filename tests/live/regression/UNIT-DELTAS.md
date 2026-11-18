# Unit-count ledger

Validated commit boundaries; new startup and S19 changes were checked in isolated source archives.

- Existing `3a3f9e4`: +4, 545 → 549.
- Existing `506c0dd`: +1, 549 → 550.
- Existing `1e92082`: +2, 550 → 552.
- Startup identity diagnostic `f803b52`: +2, 552 → 554; isolated full check/shadowJar passed, 0 failures/skips.
- Regression harness and reports `6445d09`: +0.
- S19 implementation including movement-cancellation regression: +35, 554 → 589; isolated full check/shadowJar passed, 0 failures/skips.

The first S19 snapshot had 588 tests; live Paper controls exposed the movement correction defect, whose added regression test brings the final total to 589. Regression-only commits add zero tests. S19 retains the +35 boundary described above.

## S20 sequence

- `ead06be` follow-up: +2, 589 → 591.
- `c3cb482` E1: +9, 591 → 600.
- `a1f6e39` E2: +13, 600 → 613.
- `2de7b7a` E3: +5, 613 → 618.
- E4: +9, 618 → 627; full isolated check/shadowJar passes, zero failures/skips. Correctness passes 330/330; latency release gate fails and is reported without weakening FULL.

- E4 realistic/stress measurement follow-up: **+0**, 627 unchanged. Harness/tools/docs only; accepted production jar unchanged. No new unit build claimed.

- `f86f465` stall investigation: **+4**, 627 → 631; opt-in diagnostics and committed-state/read-only regression checks. Full isolated check/shadowJar, zero failures/skips.

- E5 receipt-backed reservations: **+21**, 631 → 652; full isolated check/shadowJar, zero failures/skips. Both live lanes pass 388 checks each on one frozen jar; no provider release.

- E5 safety/operator/startup follow-ups: **+9**, 652 → 661; full check/shadowJar,
  zero failures/skips. Default-off/explicit-opt-in provider tests, offline saved
  receipt/absence/unknown/online/kit recovery, audited capture/release preserving
  workflow, and full native inspection. Spigot live evidence: economy/FOLLOWUPS.md.

- S21 service signs: **+6**, 661 → 667; full check/shadowJar, zero failures/skips.
  Three identity/shared-budget regressions and three utility metadata/admission/
  visibility regressions. Real-client SQLite gates: Spigot 373/373, Paper 373/373
  on one frozen jar and identical harness sources. See signs/RESULTS.md.

- S22 shop-sign design only: **+0**, 667 unchanged. Documentation only; no shop
  code, schema migration, provider enablement or deployment. See docs/shop-signs-design.md.

- S21 service matrix and required sweep retention: **+55**, 667 → 722; actual SignService listeners across all eleven types, create/use/break permission checks, late protection cancellation, waxed/unwaxed routing and type/command/module disabling. Full test task passes, zero failures/skips; production code unchanged.

- S22 revised shop phases/withdrawal/griefing design: **+0**, 722 unchanged; docs only. Server shops first; player shops and trades require separate later gates.

- Fixed gamemode aliases: **+8**, 722 → 730; each shortcut verifies denied base
  permission, self selection, denied others, namespaced target selection and
  disabled-family enforcement. Existing command and completion policies are reused.

- Importer inventory/design: **+0**, 730 unchanged; source-backed mappings,
  explicit unsupported/proposed destinations, dry-run/idempotency/backup/rollback
  and frozen-format list. No importer implementation or data mutation.

- Language lists and dedicated completion keys: **+12**, 821 → 833. Native baseline,
  teleport and chat pass on Spigot 1.20.1/Paper 26.3; see chat/LIST-LANG.md.
- Automatic AFK permission: **+2**, 833 → 835. Native playerinfo 35/35 on both lanes;
  see playerinfo/AFK-AUTO.md.
- Legacy hide policy, permissions and hint pack: **+42**, 835 → 877. Native hide
  18/18 on both lanes, with and without PacketEvents; see hide/RESULTS.md.
- Offline teleport aliases and saved logout positions: **+10**, 877 → 887. Native
  full teleport 16/16 cases, 156/156 checks on Spigot 1.20.1 and Paper 26.3;
  see teleport/OFFLINE.md. Hide harness whitespace cleanup only: delta 0.

- Hide-aware help, legacy refusal keys, resolver delegation and block-list YAML
  formatting: **+9**, 887 → 896. Full check/shadowJar; native Spigot 1.20.1 and
  Paper 26.3 SQLite hide/help gates 24/24 each. See hide/HELP.md.

- Legacy item database/variants, live registry enchant completions and safe policy,
  teleport aliases/selectors and companion selection: **+23**, 896 → 919. Full
  check/shadowJar passes. Final native feature gates Spigot 1.20.1/Paper 26.3
  SQLite 33/33 each; full teleport sweep 16/16 cases on the prior jar with identical
  teleport sources. Per-artifact hashes/scopes: parity/RESULTS.md and results.json.

- Essentials numeric-ID correction and sign usability/language fixes: **+10**,
  919 → 929. Full check/shadowJar, zero failures/errors/skips. Numeric completion
  exclusion, exact ID/data defaults, backed-up customization-preserving upgrade,
  native potion flags, disabled-text/break-permission/editor behavior, and config
  checklist persistence. Native evidence is recorded in parity/LEGACY-IDS.md and
  signs/USABILITY.md after the immutable-artifact gates finish.

- Flight-aware teleport destinations, explicit default-false `allium.teleport.unsafe`,
  and colored channel-save acknowledgement: **+3**, 929 → 932; full check/shadowJar.
  Clear-air flying, hazard/border/chunk refusal, explicit unsafe admission and
  post-commit channel feedback are covered.

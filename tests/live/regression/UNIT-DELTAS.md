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

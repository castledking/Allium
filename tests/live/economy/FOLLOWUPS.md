# E5 follow-ups

Frozen jar SHA256 `dc65d8127d058e1d8a1644cda645224470eef2d4c7e9c5bfc39b8ed370b128b8`.
Full check/shadowJar: **661 tests, +9 from 652, zero failures/skips**; final rebuild
is byte-identical to the live artifact. Vault defaults off. The disposable harness
explicitly enables it; no provider release or deployment.

Spigot 1.20.1 ran the existing paid kit/mail gates, six actual JVM halts and
restart checks. Before any client rejoined, both KIT and MAIL were already
CAPTURED for saved receipts and RELEASED for valid pre-delivery absence. Operator
console capture/release preserved both pending intents and wrote actor/action/
required reason audit rows. Native markers are never changed by operator tooling.

The ten-minute realistic run included 3,003 synchronous Vault writes and 30,002
reads, 299 preference writes, 59 moderation audits, 39 ordinary background mails,
and **96 paid kit + 96 paid gift workflows**, all committed. Independent stopped-DB
queries verify exactly 96 KIT/CAPTURED and 96 MAIL/CAPTURED rows for the background
payer, and 96 gift rows; no held invariant or foreign-key violations.

| Vault calls | p50 ms | p99 ms | max ms |
| --- | ---: | ---: | ---: |
| Writes | 2.147322 | 4.981236 | 10.319101 |
| Reads | 0.094844 | 0.413980 | 5.643478 |
| Combined | 0.112237 | 2.884451 | 10.319101 |

Active duration 600.047 s; write/read p99.9 8.512/0.653 ms. Economy wall/tick p99
7.385 ms; 472/12,001 ticks (3.933%) exceeded 5 ms. No Vault errors. The proposed
budget is met here, provisional pending three SF-host runs. FULL, one database,
and pooled production reads remain unchanged. CPU/source drafting on this shared
development host was not isolated; no concurrent builds or other benchmark ran
during measurement. This does not establish a causal fix for the older stall.

The original manifest remains **397/398**, with its failure preserved: a newly
written harness assertion demanded 100 background rounds. Its five-second pause
plus command and receipt work actually completes 96 rounds; all had no errors.
The corrected harness requires 90. This is not presented as a rerun or a rewritten
PASS. The independent post-run database audit establishes the requested concurrent
paid workload. All other original checks, process cleanup and source/jar integrity
checks passed. [Evidence, exact percentiles and original failure](evidence/followups.json).

Raw manifest and samples remain at
`/mnt/storage/.allium-validation/20261115/s20-economy/runs/followups-spigot-r1`;
the evidence file records their hashes. No Paper follow-up run is claimed; the
requested follow-up live lane was Spigot. S21 is separately gated.

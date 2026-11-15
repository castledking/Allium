# S21 service sign gates

**Spigot 1.20.1: 373/373. Paper 26.3: 373/373.** Both use the same frozen
jar and identical harness source hashes. Full check/shadowJar passes **667 tests,
zero failures/skips; S21 adds 6 (661 → 667)**. A final rebuild matches the live jar.

SHA256: `588e98a030cddbd8e66d9e993dac2943c3b9c3e76d51a71d936f688a04e56df6`.
[Machine-readable evidence, source hashes and retained failures](evidence/results.json).

The disposable SQLite-only harness uses actual protocol clients, native sign
edit/update/use/break packets, real blocks and the normal Allium handlers. It
never invokes SignService directly. Each of the eleven types checks denied and
allowed create/use/break permissions, cancelled creation/use/break protection,
independent FRONT/BACK authority, waxed use, and authorized destruction. Standing
and hanging signs alternate across types. Each actionable type checks command,
module and enabled-type disabling through reload. Warp access is explicitly
public in the sign identity and requires the additional create permission.

Paid kit clicks reach the existing self-claim/reservation service and charge once.
A main/offhand packet burst creates exactly one claim. Color is formatting only;
balance/mail/info are read-only actions. Repair, heal and virtual inventory actions
are observed from health, item damage and native inventory views. The utility
regression tests cover preserving repair metadata, recovery admission, and hiding
invisible players from info counts.

Real chunk unload/reload and a complete process restart preserve the exact face
identity. A real explosion destroys it. A native piston extension moves the
support block; retraction removes the temporary supporting head and destroys the
authenticated sign. The replacement block has no inherited authority. The focused
piston probe also passes 81/81. Post-shutdown SQLite queries find no holds, held
invariant violations or foreign-key violations on either final lane. Both final
runs pass all process cleanup, artifact integrity and server/client error scans.

Earlier runs remain retained rather than rewritten as passes. Fixture corrections
covered natural regeneration, creative inventory names, sword break restrictions,
resetting a reused tile, and waiting for teleport tickets before chunk unload.
The piston initially retained a legitimate supporting head; extension/retraction
is now checked. Paper 26.3's sign packet changed to text fields followed by the
SignTextSlot, verified against its actual codec. The interrupted Paper r4 has no
final manifest and is not counted. Full final r5 reruns pass both lanes on the
current jar/harness; see evidence for original counts and failures.

Reproduce using `tests/live/economy/run.py --suite spigot --signs` (then `paper`)
with the required frozen jar/hash and run-root arguments described by the economy
harness. `--signs-piston-only` runs the isolated native destruction probe. Owned
loopback ports 25800–25802 and the harness's exclusive port lock are retained.
Production/default configuration has `enabledSigns: []` and
`economy.vault-provider-enabled: false`. These tests enable both only in disposable
copies. No provider release or production deployment is implied.

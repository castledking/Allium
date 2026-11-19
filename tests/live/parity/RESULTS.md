# Item database, enchantment and teleport parity

**2026-11-18 re-run (r13):** PASS **61/61** on Spigot 1.20.1 and **61/61** on Paper 26.3 on
Allium.jar `aaf722c8006ed72f5aba84268bb90fc620f2347893913378295ec6e6a4c80ea0` (commit `661b376`), runs `r13-parity-spigot` and `r13-parity-paper` under
`/mnt/storage/.allium-validation/20261118/s18-playerinfo/runs/`. `/nick` with no arguments now expects the usage card
(`/nick <nickname>` with the gold argument colour) instead of a red `Usage:` line.

Full `check shadowJar`: **919 units**, zero failures/errors/skips; delta **+23**
from 896. Tests cover the compressed database and preservation of overrides,
canonical precedence/completion bounds, potion/spawner and namespace parsing,
plugin enchantment namespace/compatibility/conflict/clamping behavior, real
canonical `/i` completion routing, selector permission/filter/bounds behavior,
and companion claim/ownership/protection/offhand/glow cleanup. Existing enchant
regressions now reflect the requested clamp-and-config policy; raw item tests
also verify stacks do not exceed their maximum.

Native Spigot 1.20.1 and Paper 26.3, SQLite, actual protocol clients:
**33/33 final feature checks on each**. This includes aliases, potion durations and
carrier variants, Turtle Master, spawners, numeric IDs, `/i` and native `/give`
completion, safe/unsafe/conflicting enchants, raw NBT/components, here aliases,
selectors, looked-at teleport, pet/entity selection, protection cancellation and
one-time selection consumption. Bukkit-registered third-party enchantment keys
are covered by SDK-shaped registry fixtures; no private enchant plugin or every
possible Minecraft component is claimed to have been installed/tested. Native
raw-data tests exercise the real server parser and verify retained metadata.

First Spigot attempt is retained as a failed run: it exposed the `i` versus
`item` completion routing defect and an issuer-vs-mover acknowledgement assumption
in the harness. The routing defect received a unit regression. A subsequent
Spigot run passed 26 checks before the additional potion edge cases. Initial complete feature runs used one frozen jar and covered 32 checks.
Raw-enchantment hardening then added two unit regressions and a 33rd live check;
both final feature runs passed on the new frozen jar. A harness indentation error
prevented an initial launch of those last runs; it is retained in validation logs
and is not counted as a server pass.

Full teleport regression sweep results and manifest hashes are recorded in
`results.json` alongside the feature-gate manifests. The frozen jar, build log
and production source inventory are retained under
`/mnt/storage/.allium-validation/20261117/parity-final`.

The full teleport sweep passed **16/16 cases, 156/156 checks on each lane** on
jar `2bc50f68a9cca5b94258f6bf27611e38528e977c690e1b51b5dd72de27c2cc1a`.
Afterward only the item builder/enchantment adapter changed to validate raw
embedded enchants; teleport production sources are identical. Final feature
gates, including raw NBT/components, passed **33/33 each** on final jar
`3f4b34f02482f68194e093d050967f27dc6db5ac090fb0909318fe7dd5d401af`.
The final jar/build log/source inventory is in
`/mnt/storage/.allium-validation/20261117/parity-release`. All owned gate ports
were released. No production server was used.

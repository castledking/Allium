# S25 Parity Utilities Live Gate Results

## 2026-11-18 rerun (r9)

On jar `18e32537…`: Spigot 1.20.1 143/143 (`r9-spigot`). Paper 26.3 142/143 on two runs (`r9-paper`,
`r9-paper-b`); the one failure both times is the log check, because Paper's own profile lookup for
`/skull Notch` got HTTP 429 from Mojang's session server. `/ci` now asks to repeat the command
before clearing (confirmation on by default), so the gate sends it twice.


**PASS: 232/232 live checks** across **Spigot 1.20.1** (Java 17) and **Paper 26.3** (Java 25) on identical frozen `build/libs/Allium.jar` (`SHA-256: eaae240d3ef404f2b695a52e05c83188c5791edc8cc925c18e554490652e10dc`, `2,897,957` bytes), SQLite storage backend with `economy.vault-provider-enabled: false`.

- **Spigot 1.20.1 / Java 17 / SQLite** (`spigot-r3`, matching identical final harness sources with `paper-r2`): **116/116 checks passed, 0 failures** (`66.91s`)
  - Summary: `/mnt/storage/.allium-validation/20261115/s25-utilities/runs/spigot-r3/summary.json`
  - Scopes: `spigot:repair-disposal-menus-info`, `spigot:speed-ping-fire-nv`, `spigot:clear-more-hat-skull-itemdb`, `spigot:gc-time-weather-enchant`, `spigot:disabled-commands`
  - `/gc` observation: `[Allium] Runtime uptime: 37s, TPS: N/A (Spigot)`
- **Paper 26.3 / Java 25 / SQLite** (`paper-r2`): **116/116 checks passed, 0 failures** (`60.96s`)
  - Summary: `/mnt/storage/.allium-validation/20261115/s25-utilities/runs/paper-r2/summary.json`
  - Scopes: `paper:repair-disposal-menus-info`, `paper:speed-ping-fire-nv`, `paper:clear-more-hat-skull-itemdb`, `paper:gc-time-weather-enchant`, `paper:disabled-commands`
  - `/gc` observation: `[Allium] Runtime uptime: 36s, TPS: 20.00, 20.00, 20.00`

Earlier harness runs on the same frozen jar preserved under `/mnt/storage/.allium-validation/20261115/s25-utilities/runs/`:
- `spigot-r1` (`56/57` FAIL): test harness waited on `"Essentials enabled"` / `"walk speed"` instead of `"Module [essentials] enabled"` / `"Walk speed set to 3"`.
- `spigot-r2` (`116/116` PASS, `66.86s`): passed on `spigot` before switching test `server.properties` from `difficulty=easy` to `difficulty=peaceful`.
- `paper-r1` (`104/104` passed before timeout FAIL): `difficulty=easy` allowed a flat-world Slime to spawn after `/night` and kill `UtilNorm`, dropping its held sword right before `/enchant UtilNorm sharpness 10`; fixed by setting `difficulty=peaceful` in `tests/live/utilities/run.py` and re-running both `paper-r2` and `spigot-r3`.

## Unit Deltas & Verification

Full `./gradlew check shadowJar --offline` passed on `fc416b0` (and `./gradlew check --offline --rerun-tasks` after docs/test additions): **817 unit tests** (`allium-api`: 4, `allium-core`: 283, root: 530), **0 failures, 0 errors, 0 skipped**.

| Commit | Change | Unit Delta | Total |
| --- | --- | ---: | ---: |
| `63f6446` | S25 `allium-core` parsers & `CoreCommandSpecs` (`ParityUtilitiesDomainTest`) | +5 | 804 |
| `fc416b0` | S25 Bukkit utility commands, `EssentialsModule` wiring, tests (`ParityUtilityCommandsTest`, `UtilityCommandTest`) | +13 | 817 |
| Follow-up | Live harness (`tests/live/utilities/`), docs (`docs/utilities.md`), `RESULTS.md`, `todo.md` | 0 | 817 |

## Live Scopes Verified (Both Lanes)

1. **`repair-disposal-menus-info`**: Console & permission gating; `/repair` in-place durability repair preserving display name, lore, enchantments, and `UtilitiesGate` PDC identity while leaving off-slot damaged items untouched; `/repair all` batch slot repair; `/trash` (`/disposal`) 27-slot virtual container accepting items and clearing on inventory close; `/craft` (`/workbench`) and `/anvil` GUI open; `/info` vanish-aware online player count.
2. **`speed-ping-fire-nv`**: `/speed walk` / `/speed fly` Bukkit speed mapping and `reset`; `/speed walk <n> <player>`; `/ping` self and others; vanish blindness across `/ping`, `/ci`, `/speed` execution and tab completion (respecting `allium.vanish.see`); `/burn` bounds & ignition + `/ext` extinguish + `/smite` visual lightning; `/nv` toggle/set/others Night Vision effect.
3. **`clear-more-hat-skull-itemdb`**: `/ci <player> <material> <maxCount>` exact partial removal; `/more <amount>` stack expansion and refusal on non-stackable `DIAMOND_SWORD`; `/hat` atomic swap between held item and helmet slot; `/skull Notch UtilNorm` async `SkullProfiles` resolution and delivery; `/itemdb` formatted material, durability, and enchantment readout.
4. **`gc-time-weather-enchant`**: `/gc` platform TPS reporting (`N/A (Spigot)` vs live `Bukkit.getTPS()` on Paper) and `[TIME PAUSED]` marker; `/day` (`1000`), `/night` (`13000`), `/time add 500`, `/time pause on|off` clock freeze + `DO_DAYLIGHT_CYCLE` restoration; `/weather rain|thunder|clear`; `/enchant` tab completion, safe enchantment application, refusal of above-max levels (`sharpness 10`) and incompatible enchantments (`fortune 1` on sword) without `allium.enchant.unsafe`, acceptance when `allium.enchant.unsafe` (`default: false`, excluded from `allium.*`) is explicitly granted, and `/enchant ... 0` removal; structured `[UtilitiesAudit]` log verification and clean log scan.
5. **`disabled-commands`**: Dedicated lane with `disabled-commands: [ci, uptime]` confirming `clearinventory` and `gc` families are absent from `CommandMap` while `/ping` continues working cleanly.

## Limits

- Live gates ran against **SQLite** with `economy.vault-provider-enabled: false` (no separate MariaDB live run or Vault provider enablement).
- `/skull Notch UtilNorm` verified async `SkullProfiles` delivery in this online environment; offline/unreachable Mojang skin endpoints degrade safely in unit/adapter layers without main-thread stalls.
- `/enchant` `allium.enchant.unsafe` defaults to `false` outside `allium.*` and must be granted explicitly.

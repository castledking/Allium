# Alt detection, `/seen` and `/whois` extras

## Alt detection (`modules.alts`)

Every join records the player's IP (`allium_ip_history`). An account that **first** joined from an
IP that an older account already used is an alt (legacy Allium's rule).

- **Alt group.** With `alts.assign-group: true` the account gets `alts.group` (default `alt`) as a
  parent: through `lp user <uuid> parent add <group>` when LuckPerms is installed (logged by
  LuckPerms, global context), else through Vault's group API. Without either, nothing is assigned
  and a warning is logged once per server run.
- **Alerts.** Holders of `allium.alerts` are told (`alts.detected`, naming the linked accounts) when
  the group is newly added, or on every join when no group can be assigned.
- **Proxies.** `alts.ignore-local-addresses` (default true) skips players connecting from a
  loopback address: behind a proxy without IP forwarding every player shares the proxy's address
  and would otherwise all be flagged.
- **`/altexempt <add|remove|check> <player> [reason]`** (`allium.altexempt`, op): an exempt account
  is never treated as an alt. Adding an exemption also removes the alt group.

## `/seen`

Holders of `allium.seen.alts` also see every account that shares any IP in the player's history
(so a shared IP keeps showing after either account moves); `allium.seen.ip` shows the last IP.

## `/whois` extras (`allium.whois.extra`)

Below the basic lines, staff see:

- gamemode, flight allowed, flying, god, vanished, AFK;
- health, food, level, ping, walk speed (online players);
- op, muted, banned, whitelisted, frozen, jailed;
- the IP (also needs `allium.whois.ip`) and the linked alt accounts;
- **clickable actions** from `info.whois.actions` in `config.yml`. Each has `text`, `hover`,
  `click` (`run-command`, `suggest-command`, `copy-to-clipboard`, `open-url`), `value` and an
  optional `permission`; `{player}` and `{uuid}` are replaced. The defaults are legacy Allium's:
  a CoreProtect diamond-ore lookup, LiteBans `/history` and ChatSentry `/kcs lookup`.

A vanished player's live state is never shown to staff who cannot see them: they get the offline
view.

## Verification

- **Live (Spigot 1.20.1 and Paper 26.3, [staff gate](../tests/live/staff/RESULTS.md)):** IP
  history, the alert naming the older account (no permissions plugin on the lanes, so the
  "cannot add group" path), `/seen` alt and IP lines (and their absence without the nodes),
  `/altexempt` add/check/remove and the quiet rejoin, every `/whois` extra line, the LiteBans
  click event (`/history PlayerBot`) and the basic view without `allium.whois.extra`.
- **Unit:** `AltRepositoryTest` (first-seen rule, linked accounts across IP changes, exemptions).
- **Not verified:** group assignment through LuckPerms or Vault (neither is on the lanes).

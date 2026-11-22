# Party Locator

Legacy Allium's `/party` and party locator bar as an addon. Install `PartyLocator.jar` in
`plugins/Allium/addons/` with Allium API 1.5 or later. For the locator bar, the server also needs
PacketEvents and Minecraft 1.21.6 or newer. Without either, `/party` still works and the bar is
left to vanilla.

```sh
./gradlew :addons:partylocator:build shadowJar
```

The jar is collected at `build/libs/addons/PartyLocator.jar`. Configuration lives in
`plugins/PartyLocator/config.yml`, and parties in `plugins/PartyLocator/parties.yml`.

## The locator bar

The locator bar is the row of waypoint dots above the hotbar (1.21.6+). Party members always appear
on each other's bar, at any distance in the same world, at their exact position. Everyone else
appears only within `show-non-party-members-radius` blocks (128 by default). As in legacy:

- staff locator mode (`allium.party.staff`) shows everyone; `/locatorbar staff` toggles it;
- a spectator viewer with `allium.gamemode.spectator` sees everyone, and spectators stay off the
  bar of viewers without it;
- `/locatorbar show|hide <player> <time>` forces one player on or off your own bar for a while,
  `/locatorbar reset <player>` clears that; a party member always stays on;
- the `locatorBar` game rule turns the bar off per world, and Citizens NPCs keep vanilla's dot.

Vanilla's ways of hiding a dot still apply, because the addon sends positions vanilla would not:
a sneaking or invisible player, or one wearing a carved pumpkin or a mob head, is off the bar.
So is a player hidden from the viewer by `hidePlayer`, Allium's vanish or another plugin's
`vanished` metadata.

The bar filters the waypoint packets themselves (through PacketEvents) instead of using
`hidePlayer`, so hidden players stay in the tab list, stay completable in commands and stay
visible to plugins that check `canSee`. Visibility is rechecked every `refresh-ticks` (10 by
default) and immediately after a join, world change, teleport, sneak, game mode change or party
change. A packet is only sent when a dot appears, disappears or its player moved a block.

## Commands

- `/party create` — start a party, named after you (`<name>'s party`).
- `/party invite <player>` — invite an online player (leader only).
- `/party join <player>` — join the party of an online player who invited you.
- `/party leave` — leave your party. The leader uses `/party disband` instead.
- `/party disband` — disband your party (leader only).
- `/party info`, `/party list` — your party, or every party.
- `/party reload`, `/party visibility` — reload the config, or force a locator refresh
  (`allium.party.reload`).
- `/locatorbar` (aliases `/partybar`, `/lbar`, `/pbar`) — `staff`, `show <player> <time>`,
  `hide <player> <time>`, `reset <player>`, `help`. Times are seconds or `30s`, `5m`, `2h`, `1d`.

Permissions keep legacy's names, so existing permission setups carry over: `allium.party`,
`allium.party.reload`, `allium.party.staff`, `allium.locatorbar` and `allium.locatorbar.help`.
All default to op, as in legacy.

## Membership

A player is in one party at a time. Members stay in their party when they log out, and parties
now survive restarts (legacy kept them in memory only): every change saves `parties.yml` by
writing a temporary file and moving it into place. Invites last until they are used, the party is
disbanded or the inviter logs out. With `sync-with-mcmmo: true` and mcMMO installed, creating,
joining, leaving and disbanding also update the matching mcMMO party.

Not ported yet: legacy's card bonus that widened a holder's radius, and the CrowBar feed's party
filter. Legacy had no stored parties, so there is nothing to import.

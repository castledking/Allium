# Message-key reachability audit

Scope: static references in production Java, command specs and non-locale
resources, excluding the enum declaration and tests. No key is removed: tools,
addon APIs, configurable message slots and legacy importer mappings can select
keys at runtime. This is a source audit, not proof that a key can never be used.

Fixed concrete routing defects: `/back` now acknowledges `teleport.back`, and
`/spawn` uses `spawn.teleport`, both after successful movement/history commit.
Nickname save/reset now use `player.nick-set`/`player.nick-reset` rather than
hardcoded acknowledgements. Ordinary teleport consumers retain `teleport.success`.

Examples of intentional or obsolete alternatives:

- `spawn.not-set`: spawn intentionally falls back to the primary world's spawn.
- `home.limit-reached`: current home/warp limits share `travel.limit`.
- `chat.msg-format`, `chat.no-reply`, `chat.socialspy-toggle`: replaced by the
  messaging service's sent/received/no-reply-target and socialspy keys.
- `utility.seen-online`, `utility.seen-offline`, `utility.near-none`: superseded
  by the structured `info.seen.*` and `info.near.*` output.
- `player.afk-toggle`, `player.vanish-toggle`: current info/moderation services
  use their detailed AFK/vanish keys.
- Economy legacy keys may remain unwired to the journalled engine's response
  strings; keeping the locale key alone does not prove a runtime caller exists.

## Keys without a static production reference

- `error.console-only` (`CONSOLE_ONLY`)
- `home.limit-reached` (`HOME_LIMIT`)
- `spawn.not-set` (`SPAWN_NOT_SET`)
- `economy.balance-other` (`BALANCE_OTHER`)
- `economy.pay-sent` (`PAY_SENT`)
- `economy.pay-received` (`PAY_RECEIVED`)
- `economy.insufficient` (`PAY_INSUFFICIENT`)
- `economy.invalid-amount` (`PAY_INVALID_AMOUNT`)
- `chat.msg-format` (`MSG_FORMAT`)
- `chat.no-reply` (`MSG_NO_REPLY`)
- `chat.socialspy-toggle` (`SOCIALSPY_TOGGLE`)
- `chat.broadcast` (`BROADCAST_FORMAT`)
- `moderation.tempban` (`BAN_TEMP`)
- `moderation.muted-chat` (`MUTED_CHAT`)
- `player.vanish-toggle` (`VANISH_TOGGLE`)
- `player.afk-toggle` (`AFK_TOGGLE`)
- `utility.seen-online` (`SEEN_ONLINE`)
- `utility.seen-offline` (`SEEN_OFFLINE`)
- `utility.near-none` (`NEAR_NONE`)
- `tool.mob-disarmer.disarmed` (`TOOL_DISARMER_DISARMED`)
- `tool.mob-disarmer.disarmed-bypass` (`TOOL_DISARMER_DISARMED_BYPASS`)
- `tool.mob-disarmer.last-charge` (`TOOL_DISARMER_LAST_CHARGE`)
- `tool.mob-disarmer.broke` (`TOOL_DISARMER_BROKE`)
- `tool.mob-disarmer.broken` (`TOOL_DISARMER_BROKEN`)
- `tool.mob-disarmer.recharging` (`TOOL_DISARMER_RECHARGING`)
- `tool.mob-disarmer.status` (`TOOL_DISARMER_STATUS`)
- `tool.mob-disarmer.status-bypass` (`TOOL_DISARMER_STATUS_BYPASS`)
- `tool.mob-disarmer.player-target` (`TOOL_DISARMER_PLAYER`)
- `tool.mob-disarmer.excluded` (`TOOL_DISARMER_EXCLUDED`)
- `tool.mob-disarmer.too-far` (`TOOL_DISARMER_TOO_FAR`)
- `tool.mob-disarmer.protected` (`TOOL_DISARMER_PROTECTED`)
- `tool.mob-disarmer.protected-reason` (`TOOL_DISARMER_PROTECTED_REASON`)
- `tool.mob-disarmer.single-item` (`TOOL_DISARMER_SINGLE`)
- `tool.mob-disarmer.update-failed` (`TOOL_DISARMER_UPDATE_FAILED`)
- `tool.phantom-obliterator.obliterated` (`TOOL_OBLITERATOR_OBLITERATED`)
- `tool.phantom-obliterator.obliterated-bypass` (`TOOL_OBLITERATOR_OBLITERATED_BYPASS`)
- `tool.phantom-obliterator.nothing-in-range` (`TOOL_OBLITERATOR_NOTHING_IN_RANGE`)
- `tool.phantom-obliterator.protected` (`TOOL_OBLITERATOR_PROTECTED`)
- `tool.phantom-obliterator.last-charge` (`TOOL_OBLITERATOR_LAST_CHARGE`)
- `tool.phantom-obliterator.broke` (`TOOL_OBLITERATOR_BROKE`)
- `tool.phantom-obliterator.broken` (`TOOL_OBLITERATOR_BROKEN`)
- `tool.phantom-obliterator.recharging` (`TOOL_OBLITERATOR_RECHARGING`)
- `tool.phantom-obliterator.single-item` (`TOOL_OBLITERATOR_SINGLE`)
- `tool.phantom-obliterator.update-failed` (`TOOL_OBLITERATOR_UPDATE_FAILED`)
- `tool.lazy-axe.too-large` (`TOOL_LAZY_AXE_TOO_LARGE`)
- `tool.lazy-axe.busy` (`TOOL_LAZY_AXE_BUSY`)
- `tool.lazy-axe.stopped-axe` (`TOOL_LAZY_AXE_STOPPED`)
- `mail.disabled` (`MAIL_DISABLED`)

# Language color audit

The attached en_US.yml and bundled English had four uncolored standalone notices:

- `moderation.discipline.ban-denied`
- `moderation.discipline.login-unavailable`
- `moderation.discipline.kick-notice`
- `info.afk.kick`

`messaging.console-name` intentionally remains plain because it is an inline name
inside colored surrounding messages. The balance entry was already colored; the
economy runtime bypassed it. Every player-facing standalone language default now
has formatting, enforced by a regression.

All economy feedback and chat validation/feedback now use Messages/MessageManager;
/itemdb, /info and the vanish-safe list response are also migrated. The utility
fallback formats its default prefix and colors. Console-only chat transcripts,
Discord API sends, already colored moderation lookup refusals, component transport
and rendering adapters remain outside the standalone notice migration.

Exact former stock notices are upgraded in existing language files, with a
`.before-color-fix` backup. Custom text and existing sounds are retained. New keys
are synchronized through the existing missing-key merge.

New message keys (58):

- `economy.unavailable`
- `economy.busy`
- `economy.invalid-arguments`
- `economy.below-minimum`
- `economy.storage-unavailable`
- `economy.account-missing`
- `economy.preferences-unavailable`
- `economy.preferences-save-failed`
- `economy.payments-enabled`
- `economy.payments-disabled`
- `economy.reservations-empty`
- `economy.reservations-unavailable`
- `economy.resolve-disconnect`
- `economy.resolve-retained`
- `economy.resolve-unavailable`
- `economy.payment-refused`
- `economy.payment-sent`
- `economy.payment-received`
- `economy.operation-committed`
- `economy.outcome-unknown`
- `economy.reservation-entry`
- `economy.top`
- `economy.operation-refused`
- `chat.write-channel-removed`
- `chat.profile-load-failed`
- `chat.profile-not-ready`
- `chat.muted-or-loading`
- `chat.vanished-public`
- `chat.channels-disabled-select-global`
- `chat.channel-write-denied`
- `chat.message-length-invalid`
- `chat.module-disabled`
- `chat.player-lookup-failed`
- `chat.nick-lookup-unavailable`
- `chat.nick-unavailable`
- `chat.profile-busy`
- `chat.nick-save-failed`
- `chat.nick-usage`
- `chat.nick-control-text`
- `chat.nick-invalid`
- `chat.nick-console-target`
- `chat.channel-player-only`
- `chat.channels-disabled-use-global`
- `chat.channel-usage`
- `chat.channel-unknown`
- `chat.channel-direction-invalid`
- `chat.channel-action-invalid`
- `chat.channel-join-denied`
- `chat.channels-list`
- `chat.argument-error`
- `utility.server-info`
- `utility.itemdb-item`
- `utility.itemdb-item-other`
- `utility.itemdb-durability`
- `utility.itemdb-enchantments`
- `utility.itemdb-id`
- `utility.itemdb-behavior`
- `info.filtered-list`

Full check/shadowJar: +4 units, 936 → 940; zero failures/errors/skips.

Run `python3 tools/audit-lang-colors <locale.yml>` to repeat the standalone-text
check. `--include-labels` also reports the intentionally plain Console label.
The attached English file was merged with the new defaults into a separate
validation artifact; the original attachment is untouched.

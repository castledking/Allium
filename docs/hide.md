# Command visibility and permission resolution

`hide.yml` ports the legacy command-path rules. The module loads by default;
`settings.enabled` defaults **false** so an upgrade does not apply an unconfigured
command whitelist. Configure groups, then `/allium hide enabled true` (alias
`/core hide`). `allium.hide.admin` and `allium.hide.bypass` default to operators.

Membership uses Vault's global group provider when available, or `group.<name>`
permissions. A configured `default` group applies if none match. Vault is optional.
Explicit `inherits.commands` and `inherits.tabcompletes` are independent. Loaded
LuckPerms parent groups are inherited when configured locally and their Vault
chat weight is no greater than the child's; absent weights are zero. Inheritance
cycles and missing explicit parents refuse the replacement configuration.

`commands` controls execution; `tabcompletes` controls suggestions. A root covers
its descendants; `party invite` covers `party invite Alice`, not `party invited`
or `party kick`. Completion whitelists include the ancestors needed to reach a
listed path. `^` in tabcompletes follows resolved command rules; `*` matches all,
and `plugin:*` matches that owner's qualified commands, including registered bare
aliases. Blacklist denials win over whitelist matches. Execution bypass adds a
wildcard whitelist but still respects member blacklists, as legacy did.
Completion bypass skips group/unsafe filtering. Namespace visibility is separate:
non-bypass viewers see only namespaced roots explicitly allowed in
`allow-namespaced-suggestions`. The two historical `*-for-ops` settings apply to
bypass holders. Unlike legacy's unused execution flag, the port actually enforces
`block-namespaced-commands-for-ops` when enabled.

Native permissions always apply after execution rules. The resolver inspects
Paper BasicCommand predicates, public NightCore-style root/child APIs, explicit
Bukkit permissions, and Brigadier requirements using the real native sender
source. It then considers specific owner/module/action permission candidates in
LuckPerms, registered permissions and effective attachments. A denied broad
wildcard alone does not identify a command's required permission. Framework
executors never run during inspection. Unknown metadata delegates to native
command dispatch; it grants no permission. Missing command labels are refused.

`/allium hide resolve <online-player> <command...>` reports the resolution source,
node and result without executing the command. `/allium hide fix <command>
<permission>` and `unfix <command>` manage explicit mappings used **only** when
metadata is unknown. Overrides are rows (`command`, `permission`) so dotted nodes
survive YAML. The player lookup/completion respects vanish visibility.

Administration also provides:

- `status`, `reload`, `enabled true|false`.
- `group <name> create whitelist|blacklist`.
- `group <name> whitelist true|false`.
- `group <name> inherit commands|tabcompletes <parent,parent>`.
- `group <name> add|remove|check [-c|-t|-tc] <path,path>`; default scope is both.

Edits use a bounded worker and atomic file replacement, preserving
`hide.yml.before-edit`. Actor/action requests and successful applications are
logged. A bad reload keeps the active policy. An invalid initial file blocks
non-bypass access until repaired. File parsing and hint-pack writes run outside
command/packet listeners. Execution resolves current permissions; immutable client
snapshots refresh within two seconds, with command-tree updates batched per tick.

With PacketEvents, outgoing command graphs are copied, filtered and compacted;
hidden detached nodes are removed and indices remapped. Scoped branches and
redirecting subtrees use server suggestions instead of cached literals. Suggestion
responses are filtered against the full original input and server replacement
offset. Correlation is bounded per player; reused pending request IDs are poisoned
until expiry. Spigot and Paper never answer a request whose completion is empty, so
pending entries pile up while a player types; at 32 pending the oldest is retired
(its late answer is dropped and a reuse of its ID stays poisoned) rather than refusing
the new request, which used to leave the player without suggestions until old entries
expired. Only a flood past 256 retired IDs within ten seconds is refused. Bare-slash
and unknown responses can be withheld. Packet listeners
use immutable snapshots and perform no provider, permission or disk calls.
The server-side completion filter resolves each command's permission once per batch
when the answer cannot depend on the arguments (a plain Bukkit permission), so `/i`
offering 64 item names costs one check, and caches per command class which
reflective trees (Paper basic, framework, Brigadier) exist instead of probing each time.
Without PacketEvents, affected scoped roots are withheld completely from the
client tree. Manually typed commands still obey execution rules. Native command
permissions remain the final authority in either mode.

The completion help line overrides Minecraft's `command.unknown.command` in a
small deterministic translation pack for 142 locale IDs. Colors use Allium's
existing formatter; hex maps to the nearest standard color and literal percent
signs are escaped. The client appends its own error-position suffix.
`Allium_unknown_command_resource_pack.zip` is exported before plugins enable into
ResourcePackManager's `mixer` directory, or `plugins/Allium/generated` for manual
merging. After changes, reload your mixer/delivery owner. It never replaces the
core item pack or offers a second standalone pack. Empty help text/disabled hide
removes only Allium-marked helper files; unrelated packs are preserved.

Compatibility evidence distinguishes native server/PacketEvents checks from
resolver API-shape tests; it does not certify every version of LuckPerms,
NightCore, or another command framework. See `tests/live/hide/RESULTS.md`.

## Help and localized refusals

`/help [page|command|plugin] [page]` supplies eight entries per page with clickable
command suggestions and page navigation. Plugin searches/completions require
`allium.admin`. Every page and completion resolves a fresh view: with hiding on,
only paths allowed in **both** commands and tabcompletes appear, then permission
resolution applies. Scoped rules list admitted subcommands rather than exposing
an unrestricted parent. With hiding off, help uses native command permissions.
`allium.help` defaults true; disabling the help command family also disables its
completion renderer.

### Usage cards

`/help <command>` and every Allium command typed with missing or wrong arguments show the same
command card: `help.command-header`, `help.command-description` (the plugin.yml description),
then `Usage: ...` for a single form or `Usage(s):` with one line per form
(`help.usage-line`: `/nick <nickname> - Changes your nickname to the given text`). Lines use
the label the player typed (`/gm <mode>` for `/gm`), colour literal words and arguments with
`help.usage-literal` and `help.usage-argument`, and suggest the command when clicked. Forms
behind a permission the sender lacks are left out, so `/nick` shows the `<player>` forms only
to holders of `allium.nick.others`. A wrong subcommand narrows the card to its forms
(`/core hide group` lists only the group lines), and a command that reports one exact form,
like the console's `/heal <player>`, shows just that line. The forms are written in
`CommandUsages` (allium-core); families without written forms list their subcommand usages, then
the usage the command reported. `/core hide` uses the same card instead of the former
`hide.usage` list, which was removed together with `chat.nick-usage`, `chat.channel-usage`,
`chat.delete.usage` and `moderation.discipline.usage`.

`settings.use-allium-permission-resolver: true` in hide.yml controls Allium's
permission inspection during hide execution/help/root filtering. False retains
hide execution and suggestion rules but delegates actual permission refusal to
the command's owning plugin. Native permissions remain authoritative either way.
A path admitted by commands that fails Allium's resolver uses root lang key
`no-permission` (`{cmd}`). A hidden/missing path uses `unknown-command` or
`unknown-command-suggestion` (`{cmd}`, `{suggestion}`); suggestions use only visible,
executable roots. The former `hide.unknown` entry remains for compatibility but
is no longer the active refusal key. The client-side resource-pack error line
remains a separate hide.yml setting.

Both `[one, two]` and block lists are valid YAML and already parse identically.
Startup/resource sync now serializes config/lang/hide lists as block sequences,
even if no keys were added. It keeps a one-time `.before-format` original and
atomically replaces changed files. Resource-sync normalization skips invalid files. New bundled
language keys are merged into existing locales without replacing custom text.

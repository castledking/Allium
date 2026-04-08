package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Party;
import net.survivalfun.core.managers.core.PartyManager;
import net.survivalfun.core.managers.core.PartyManager.PartyInviteResult;
import net.survivalfun.core.managers.core.PartyManager.PartyJoinResult;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

public class PartyCommand implements CommandExecutor, TabCompleter {
    private final PluginStart plugin;
    private final PartyManager partyManager;
    private final Lang lang;

    public PartyCommand(PluginStart plugin, PartyManager partyManager) {
        this.plugin = plugin;
        this.partyManager = partyManager;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player)) {
            Text.sendErrorMessage(sender, "not-a-player", lang);
            return true;
        }

        Player player = (Player) sender;
        UUID playerId = player.getUniqueId();

        if (args.length == 0) {
            showHelp(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "create":
                handleCreate(player);
                break;

            case "invite":
                if (args.length < 2) {
                    player.sendMessage(Text.colorize("&cUsage: /party invite <player>"));
                    return true;
                }
                handleInvite(player, args[1]);
                break;

            case "join":
                if (args.length < 2) {
                    player.sendMessage(Text.colorize("&cUsage: /party join <player>"));
                    return true;
                }
                handleJoin(player, args[1]);
                break;

            case "leave":
                handleLeave(player);
                break;

            case "disband":
                handleDisband(player);
                break;

            case "info":
                Party party = partyManager.getPlayerParty(playerId);
                if (party != null) {
                    String members = party.getMembers().stream()
                        .map(id -> {
                            Player p = Bukkit.getPlayer(id);
                            if (p != null) {
                                return "&a" + p.getName();
                            } else {
                                // Try to get name from offline player
                                org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
                                String name = offline.getName();
                                return name != null ? "&7" + name + " (offline)" : "&7Unknown (offline)";
                            }
                        })
                        .collect(Collectors.joining("&r, "));
                    String leaderName = party.getLeader().equals(playerId) ? "You" : 
                        Bukkit.getOfflinePlayer(party.getLeader()).getName();
                    player.sendMessage(Text.colorize("&6Party: &f" + party.getName()));
                    player.sendMessage(Text.colorize("&6Leader: &f" + leaderName));
                    player.sendMessage(Text.colorize("&6Members (&f" + party.getSize() + "&6): &r" + members));
                } else {
                    player.sendMessage(Text.colorize("&cYou're not in a party."));
                }
                break;

            case "reload":
                handleReload(player);
                break;

            case "visibility":
                handleVisibility(player);
                break;

            default:
                showHelp(player);
                break;
        }

        return true;
    }

    private void handleCreate(Player player) {
        UUID playerId = player.getUniqueId();
        Party existingParty = partyManager.getPlayerParty(playerId);
        if (existingParty != null) {
            if (existingParty.getLeader().equals(playerId)) {
                player.sendMessage(Text.colorize("&cYou are already the leader of party '" + existingParty.getName() + "'. Use /party disband to remove it first."));
            } else {
                player.sendMessage(Text.colorize("&cYou are already a member of party '" + existingParty.getName() + "'. Use /party leave to leave it first."));
            }
            return;
        }

        String partyName = generatePartyName(player.getName());
        if (partyManager.createParty(partyName, playerId)) {
            player.sendMessage(Text.colorize("&aParty '" + partyName + "' created!"));
        } else {
            player.sendMessage(Text.colorize("&cFailed to create party."));
        }
    }

    private void handleInvite(Player player, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            Text.sendErrorMessage(player, "player-not-found", lang, "{name}", targetName);
            return;
        }

        PartyInviteResult result = partyManager.invitePlayer(player.getUniqueId(), target.getUniqueId());
        switch (result) {
            case SUCCESS -> {
                player.sendMessage(Text.colorize("&aInvited " + target.getName() + " to your party."));
                target.sendMessage(Text.colorize("&e" + player.getName() + " has invited you to join their party. Use &a/party join " + player.getName() + " &eto accept."));
            }
            case NO_PARTY -> player.sendMessage(Text.colorize("&cYou must create a party before inviting players."));
            case NOT_LEADER -> player.sendMessage(Text.colorize("&cOnly the party leader can invite players."));
            case TARGET_IN_PARTY -> player.sendMessage(Text.colorize("&cThat player is already in a party."));
            case ALREADY_INVITED -> player.sendMessage(Text.colorize("&cThat player already has a pending invite."));
            case SELF_INVITE -> player.sendMessage(Text.colorize("&cYou cannot invite yourself."));
        }
    }

    private void handleJoin(Player player, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            Text.sendErrorMessage(player, "player-not-found", lang, "{name}", targetName);
            return;
        }

        Party targetParty = partyManager.getPlayerParty(target.getUniqueId());
        if (targetParty == null) {
            player.sendMessage(Text.colorize("&cThat player is not in a party."));
            return;
        }

        PartyJoinResult result = partyManager.joinParty(targetParty.getName(), player.getUniqueId());
        switch (result) {
            case SUCCESS -> {
                player.sendMessage(Text.colorize("&aJoined party '" + targetParty.getName() + "'!"));
                target.sendMessage(Text.colorize("&e" + player.getName() + " has joined your party."));
            }
            case NOT_FOUND -> player.sendMessage(Text.colorize("&cThat party no longer exists."));
            case ALREADY_IN_PARTY -> player.sendMessage(Text.colorize("&cYou are already in a party."));
            case NOT_INVITED -> player.sendMessage(Text.colorize("&cYou do not have an invite to that party."));
        }
    }

    private void showHelp(Player player) {
        player.sendMessage(Text.colorize("&6Party Commands:"));
        player.sendMessage(Text.colorize("&e/party create &7- Create a new party"));
        player.sendMessage(Text.colorize("&e/party invite <player> &7- Invite a player to your party"));
        player.sendMessage(Text.colorize("&e/party join <player> &7- Join the party of the specified player"));
        player.sendMessage(Text.colorize("&e/party leave &7- Leave your current party"));
        player.sendMessage(Text.colorize("&e/party disband &7- Disband your party (leader only)"));
        player.sendMessage(Text.colorize("&e/party info &7- Show your party info"));
        player.sendMessage(Text.colorize("&e/party list &7- List all parties"));
        player.sendMessage(Text.colorize("&e/party reload &7- Reload party configuration (admin only)"));
        player.sendMessage(Text.colorize("&e/party visibility &7- Force visibility refresh (admin only)"));
    }

    private String generatePartyName(String playerName) {
        return (playerName + "'s party").toLowerCase(Locale.ROOT);
    }

    private void handleVisibility(Player player) {
        if (!player.hasPermission("allium.party.reload")) {
            player.sendMessage(Text.colorize("&cYou don't have permission."));
            return;
        }
        partyManager.forceVisibilityRefresh();
        player.sendMessage(Text.colorize("&aVisibility refresh forced. Config: party-locator-bar=" + partyManager.isPartyLocatorBarEnabled()
                + ", radius=" + partyManager.getShowNonPartyMembersRadius() + ", locator-bar-enabled=" + Boolean.TRUE.equals(player.getWorld().getGameRuleValue(org.bukkit.GameRule.LOCATOR_BAR))));
    }

    private void handleLeave(Player player) {
        Party party = partyManager.getPlayerParty(player.getUniqueId());
        if (party == null) {
            player.sendMessage(Text.colorize("&cYou're not in a party."));
            return;
        }

        if (party.getLeader().equals(player.getUniqueId())) {
            player.sendMessage(Text.colorize("&cParty leaders must use &e/party disband &cto disband the party."));
            return;
        }

        if (partyManager.leaveParty(player.getUniqueId())) {
            player.sendMessage(Text.colorize("&aLeft the party."));
            Player leader = Bukkit.getPlayer(party.getLeader());
            if (leader != null) {
                leader.sendMessage(Text.colorize("&e" + player.getName() + " has left the party."));
            }
        } else {
            player.sendMessage(Text.colorize("&cFailed to leave the party."));
        }
    }

    private void handleReload(Player player) {
        // Check if player has permission to reload party config
        if (!player.hasPermission("allium.party.reload")) {
            player.sendMessage(Text.colorize("&cYou don't have permission to reload party configuration."));
            return;
        }

        partyManager.reloadConfig();
        partyManager.logCurrentConfig();
        player.sendMessage(Text.colorize("&aParty configuration reloaded successfully."));
    }

    private void handleDisband(Player player) {
        Party party = partyManager.getPlayerParty(player.getUniqueId());
        if (party == null) {
            player.sendMessage(Text.colorize("&cYou're not in a party."));
            return;
        }

        if (!party.getLeader().equals(player.getUniqueId())) {
            player.sendMessage(Text.colorize("&cOnly the party leader can disband the party."));
            return;
        }

        if (partyManager.disbandPartyIfLeader(player.getUniqueId())) {
            player.sendMessage(Text.colorize("&aYour party has been disbanded."));
            for (UUID memberId : party.getMembers()) {
                if (!memberId.equals(player.getUniqueId())) {
                    Player member = Bukkit.getPlayer(memberId);
                    if (member != null) {
                        member.sendMessage(Text.colorize("&cYour party has been disbanded by " + player.getName() + "."));
                    }
                }
            }
        } else {
            player.sendMessage(Text.colorize("&cFailed to disband the party."));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (!(sender instanceof Player)) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            String partial = args[0].toLowerCase();
            for (String sub : new String[]{"create", "invite", "join", "leave", "disband", "info", "list", "reload", "visibility"}) {
                if (sub.startsWith(partial)) {
                    completions.add(sub);
                }
            }
            return completions;
        } else if (args.length == 2 && "invite".equalsIgnoreCase(args[0])) {
            String partial = args[1].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                .filter(p -> !p.equals(sender))
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(partial))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
        } else if (args.length == 2 && "join".equalsIgnoreCase(args[0])) {
            String partial = args[1].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(partial))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}

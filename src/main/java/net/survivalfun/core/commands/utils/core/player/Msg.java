package net.survivalfun.core.commands.utils.core.player;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.config.Config;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.commands.utils.core.managers.Spy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * MessageHandler command implementation that handles:
 * - /msg (direct messages to online players)
 * - /reply (replies to last message received)
 * - /mail (saves messages to be read later by offline players)
 *
 * This command supports messaging between players, console to players,
 * and storing messages for offline players to read when they log in.
 */
public class Msg implements CommandExecutor, TabCompleter, Listener {

    private final PluginStart plugin;
    private final Lang lang;
    private final Database database;
    private final Spy spyCommand;

    // Command aliases for different functionalities
    private final List<String> messageCommandAliases;
    private final List<String> replyCommandAliases;
    private final List<String> mailCommandAliases;

    // Maps to store conversation history and mail messages
    private final Map<UUID, UUID> lastMessageSender;
    private final Map<String, List<MailMessage>> offlineMessages;
    private final Map<UUID, GiftSession> pendingGifts = new ConcurrentHashMap<>();

    /**
     * Constructs a new MessageHandler with the necessary plugin instance and configurations.
     *
     * @param plugin The main plugin instance
     */
    public Msg(PluginStart plugin, Spy spyCommand) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
        this.database = plugin.getDatabase();
        this.spyCommand = spyCommand;

        // Initialize command aliases
        this.messageCommandAliases = Arrays.asList("msg", "tell", "w", "whisper", "t", "message", "m");
        this.replyCommandAliases = Arrays.asList("r", "reply");
        this.mailCommandAliases = Collections.singletonList("mail");

        // Initialize maps for tracking conversations and offline messages
        this.lastMessageSender = new ConcurrentHashMap<>();
        this.offlineMessages = new ConcurrentHashMap<>();

        loadMailMessagesFromDatabase();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String usedCommand = label.toLowerCase();

        // Handle message command
        if (messageCommandAliases.contains(usedCommand)) {
            return handleMessageCommand(sender, args, label);
        }

        // Handle reply command
        if (replyCommandAliases.contains(usedCommand)) {
            return handleReplyCommand(sender, args);
        }

        // Handle mail command
        if (mailCommandAliases.contains(usedCommand)) {
            return handleMailCommand(sender, args);
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String commandName = command.getName().toLowerCase();

        // Tab completion for /msg and similar commands
        if (messageCommandAliases.contains(commandName)) {
            if (args.length == 1) {
                return getOnlinePlayerNames(args[0]);
            }
        }

        // Tab completion for /mail command
        if (mailCommandAliases.contains(commandName)) {
            if (args.length == 1) {
                return filterStartingWith(Arrays.asList("read", "send", "clear"), args[0]);
            } else if (args.length == 2 && "send".equalsIgnoreCase(args[0])) {
                return getOnlinePlayerNames(args[1]);
            }
        }

        return Collections.emptyList();
    }

    /**
     * Handles the /msg command by sending a direct message to an online player
     *
     * @param sender The sender of the message
     * @param args Command arguments: [recipient, message...]
     * @return true if the command was processed successfully
     */
    private boolean handleMessageCommand(CommandSender sender, String[] args, String label) {
        // Check permission
        if (!sender.hasPermission("allium.msg")) {
            Text.sendErrorMessage(sender, lang.get("no-permission").replace("{cmd}", label), lang);
            return true;
        }

        // Check arguments
        if (args.length < 2) {
            sender.sendMessage(lang.get("command-usage").replace("{cmd}", "msg").replace("{args}", "<player> <message>"));
            return true;
        }

        String targetPlayerName = args[0];
        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        Player targetPlayer = plugin.getServer().getPlayer(targetPlayerName);

        // If player is online, send direct message
        if (targetPlayer != null && targetPlayer.isOnline()) {
            sendDirectMessage(sender, targetPlayer, message);
            if (sender instanceof Player playerSender) {
                lastMessageSender.put(playerSender.getUniqueId(), targetPlayer.getUniqueId());
            }
            return true;
        } else {
            // Offer to send mail instead
            Text.sendErrorMessage(sender, "player-not-online", lang, "{name}", targetPlayerName);

            return true;
        }
    }

    private boolean handleReplyCommand(CommandSender sender, String[] args) {
        // Check permission
        if (!sender.hasPermission("allium.msg")) {
            sender.sendMessage(lang.get("no-permission"));
            return true;
        }

        // Check arguments
        if (args.length < 1) {
            sender.sendMessage(lang.get("command-usage").replace("{cmd}", "reply").replace("{args}", "<message>"));
            return true;
        }

        UUID senderUUID;
        UUID lastSenderUUID = null;

        if (sender instanceof Player player) {
            senderUUID = player.getUniqueId();
            lastSenderUUID = lastMessageSender.get(player.getUniqueId());
        } else {
            // For console, use a special UUID (all zeros)
            senderUUID = new UUID(0, 0);
            lastSenderUUID = lastMessageSender.get(new UUID(0, 0));
        }

        if (lastSenderUUID == null) {
            sender.sendMessage(lang.get("msg.no-reply-target"));
            return true;
        }

        // Special handling for console as recipient (UUID with all zeros)
        if (lastSenderUUID.equals(new UUID(0, 0))) {
            // The last message was from console, so reply to console
            String message = String.join(" ", args);
            CommandSender console = plugin.getServer().getConsoleSender();

            // Format the message for both sender and console
            String senderFormat = lang.get("msg.sent")
                    .replace("{recipient}", "Console")
                    .replace("{message}", message);
            String consoleFormat = lang.get("msg.received")
                    .replace("{sender}", sender.getName())
                    .replace("{message}", message);

            // Send the formatted messages
            sender.sendMessage(senderFormat);
            console.sendMessage(consoleFormat);

            // Update the conversation tracking - only track player-player conversations
            if (sender instanceof Player) {
                lastMessageSender.put(((Player) sender).getUniqueId(), ((Player) sender).getUniqueId());
            }

            return true;
        }

        Player replyTarget = Bukkit.getPlayer(lastSenderUUID);
        if (replyTarget == null || !replyTarget.isOnline()) {
            sender.sendMessage(lang.get("msg.reply"));
            return true;
        }

        String message = String.join(" ", args);
        sendDirectMessage(sender, replyTarget, message);
        return true;
    }

    /**
     * Handles the /mail command for sending messages to offline players
     *
     * @param sender The sender of the mail
     * @param args Command arguments: [read|send|clear, player, message...]
     * @return true if the command was processed successfully
     */
    private boolean handleMailCommand(CommandSender sender, String[] args) {
        // Check permission
        if (!sender.hasPermission("allium.msg")) {
            sender.sendMessage(lang.get("no-permission"));
            return true;
        }

        // Check arguments
        if (args.length < 1) {
            sender.sendMessage(lang.get("command-usage").replace("{cmd}", "mail")
                    .replace("{args}", "<read§8|§7send§8|§7clear§8|§7gift§8|§7claim> <player> <message...>"));
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "read":
                return handleMailRead((Player) sender);

            case "send":
                if (args.length < 3) {
                    sender.sendMessage(lang.get("command-usage").replace("{cmd}", "mail")
                            .replace("{args}", "send <player> <message>"));
                    return true;
                }
                String recipient = args[1];
                String message = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                return sendMailMessage(sender, recipient, message);

            case "gift":
                return handleMailGift(sender, Arrays.copyOfRange(args, 0, args.length));

            case "claim":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(lang.get("not-a-player"));
                    return true;
                }
                return handleMailClaim((Player) sender, args);

            case "clear":
                return handleMailClear((Player) sender);

            default:
                sender.sendMessage(lang.get("command-usage").replace("{cmd}", "mail")
                        .replace("{args}", "<read|send|clear|gift|claim> <player> <message...>"));
                return true;
        }

    }

    private boolean handleMailGift(CommandSender sender, String[] args) {
        // Check if the sender is a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("not-a-player"));
            return true;
        }

        // Check if player has permission
        if (!player.hasPermission("core.mail.gift")) {
            Text.sendErrorMessage(sender, lang.get("no-permission"), lang, "{cmd}", "§cmail gift");
            return true;
        }

        // Check if args length is at least 2 (subcommand + recipient username)
        if (args.length < 2) {
            player.sendMessage(lang.get("command-usage").replace("{cmd}", "mail gift")
                    .replace("{args}", "<player>"));
            return true;
        }

        String recipient = args[1];

        // Check if recipient exists by checking if they've played before
        OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayer(recipient);
        if (!offlinePlayer.hasPlayedBefore()) {
            Text.sendErrorMessage(sender, lang.get("player-not-found"), lang, "{name}", recipient);
            return true;
        }

        if (recipient.equals(player.getName())) {
            Text.sendErrorMessage(sender, "cannot-self", lang, "{action}", "§csend a gift to");
            return true;
        }

        // Create a dispenser inventory (3x3)
        Inventory giftInventory = Bukkit.createInventory(player, org.bukkit.event.inventory.InventoryType.DISPENSER,
                Text.parseColors("&5&lGIFT: &d" + recipient));

        // Store the inventory session for this player
        pendingGifts.put(player.getUniqueId(), new GiftSession(recipient, giftInventory));

        // Open the inventory for the player
        player.openInventory(giftInventory);

        // Send instructions
        player.sendMessage(lang.get("msg.gift-help")
                .replace("{name}", recipient));

        return true;
    }

    private boolean handleMailClaim(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(lang.get("command-usage").replace("{cmd}", "mail claim")
                    .replace("{args}", "<id>"));
            return true;
        }

        int giftId;
        try {
            giftId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            Text.sendErrorMessage(player, "invalid", lang, "{arg}", args[1]);
            return true;
        }

        // Get the gift from the database
        GiftEntry gift = getGiftById(giftId, player.getUniqueId());
        if (gift == null) {
            Text.sendErrorMessage(player, "msg.gift-not-found", lang, "{id}", String.valueOf(giftId));
            return true;
        }

        try {
            // Deserialize the items
            ItemStack[] items = plugin.getDatabase().deserializeItemStacks(gift.serializedItems().getBytes());

            // Check if player has enough inventory space
            int emptySlots = getEmptySlots(player.getInventory());
            int itemCount = countNonNullItems(items);

            if (emptySlots < itemCount) {
                Text.sendErrorMessage(player, "inventory-full", lang);
                return true;
            }

            // Add items to player's inventory
            for (ItemStack item : items) {
                if (item != null && item.getType() != Material.AIR) {
                    player.getInventory().addItem(item);
                }
            }

            // Mark gift as claimed
            markGiftAsClaimed(giftId);

            player.sendMessage(lang.get("msg.gift-claim").replace("{name}", gift.senderName()));

            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to process gift claim", e);
            Text.sendErrorMessage(player, "msg.gift-not-found", lang);
            return true;
        }
    }

    private boolean handleMailRead(Player player) {
        String playerName = player.getName().toLowerCase();
        List<MailMessage> messages = offlineMessages.get(playerName);

        if (messages == null || messages.isEmpty()) {
            player.sendMessage(lang.get("msg.mail-empty"));
            return true;
        }

        player.sendMessage(lang.get("msg.mail-header"));

        // Display all messages
        for (MailMessage message : messages) {
            String formattedTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(new Date(message.timestamp));

            player.sendMessage(lang.get("msg.mail-format")
                    .replace("{sender}", message.senderName())
                    .replace("{time}", formattedTime)
                    .replace("{message}", message.message()));
        }

        // Mark messages as read in database
        markMailMessagesAsRead(playerName);

        // Clear messages from memory
        offlineMessages.remove(playerName);

        player.sendMessage(lang.get("msg.mail-footer"));
        return true;
    }

    /**
     * Gets a gift by its ID and recipient
     */
    private GiftEntry getGiftById(int giftId, UUID recipient) {
        try {
            // Now get unclaimed gifts for this player
            String sql = "SELECT * FROM mail_gifts WHERE id = ? AND recipient = ? AND is_claimed = FALSE";
            
            Map<String, Object> row = database.queryRow(sql, giftId, recipient.toString());
            
            if (row != null) {
                String sender = (String) row.get("sender");
                String senderName = (String) row.get("sender_name");
                String items = (String) row.get("items");
                long sentTime = ((Timestamp) row.get("sent_time")).getTime();
                
                return new GiftEntry(giftId, UUID.fromString(sender), senderName, items, sentTime);
            } else {
                plugin.getLogger().warning("No gift found in database for id=" + giftId + " and recipient=" + recipient);
                return null;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to retrieve gift from database", e);
            return null;
        }
    }

    /**
     * Marks a gift as claimed in the database
     */
    private void markGiftAsClaimed(int giftId) {
        try {
            String sql = "UPDATE mail_gifts SET is_claimed = TRUE, claimed_at = CURRENT_TIMESTAMP WHERE id = ?";
            int updated = database.executeUpdate(sql, giftId);
            
            if (updated == 0) {
                plugin.getLogger().warning("Failed to mark gift as claimed - no rows updated for id: " + giftId);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to mark gift as claimed", e);
        }
    }

    /**
     * Counts the number of empty slots in an inventory
     */
    private int getEmptySlots(Inventory inventory) {
        int count = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item == null || item.getType() == Material.AIR) {
                count++;
            }
        }
        return count;
    }

    /**
     * Counts the number of non-null items in an array
     */
    private int countNonNullItems(ItemStack[] items) {
        int count = 0;
        for (ItemStack item : items) {
            if (item != null && item.getType() != Material.AIR) {
                count++;
            }
        }
        return count;
    }

    private void loadMailMessagesFromDatabase() {
        try {
            String sql = "SELECT * FROM mail_messages WHERE is_read = FALSE";

            database.executeQuery(sql, (ResultSet resultSet) -> {
                try {
                    while (resultSet.next()) {
                        String recipient = resultSet.getString("recipient");
                        String sender = resultSet.getString("sender");
                        String senderName = resultSet.getString("sender_name");
                        String message = resultSet.getString("message");
                        long sentTime = resultSet.getTimestamp("sent_time").getTime();
                        int messageId = resultSet.getInt("id");

                        MailMessage mailMessage = new MailMessage(
                                UUID.fromString(sender),
                                senderName,
                                message,
                                sentTime,
                                messageId
                        );

                        offlineMessages.computeIfAbsent(recipient.toLowerCase(), k -> new ArrayList<>())
                                .add(mailMessage);
                    }
                } catch (Exception e) {
                    plugin.getLogger().severe("Error processing mail message results: " + e.getMessage());
                }
                return null;
            });
        } catch (Exception e) {
            plugin.getLogger().severe("Error executing mail message query: " + e.getMessage());
        }
    }

    /**
     * Saves a mail message to the database
     *
     * @param recipient The recipient's name
     * @param senderUUID The UUID of the sender
     * @param senderName The name of the sender
     * @param message The message content
     * @return The ID of the saved message
     */
    private int saveMailMessageToDatabase(String recipient, UUID sender, String senderName, String message) {
        try {
            String sql = "INSERT INTO mail_messages (recipient, sender, sender_name, message, sent_time) VALUES (?, ?, ?, ?, ?)";
            
            return database.executeUpdate(sql, 
                recipient,
                sender.toString(),
                senderName,
                message,
                new Timestamp(System.currentTimeMillis())
            );
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save mail message to database: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Marks mail messages as read in the database
     *
     * @param recipient The recipient's UUID
     */
    private void markMailMessagesAsRead(String recipient) {
        try {
            String sql = "UPDATE mail_messages SET is_read = TRUE WHERE recipient = ? AND is_read = FALSE";
            database.executeUpdate(sql, recipient);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to mark mail messages as read: " + e.getMessage());
        }
    }

    public void savePendingMessages() {
    }

    public void deliverOfflineMessages(Player player) {
        try {
            if (player == null) {
                plugin.getLogger().warning("Attempted to deliver offline messages to null player");
                return;
            }

            String playerName = player.getName().toLowerCase();
            List<MailMessage> messages = offlineMessages.get(playerName);

            if (messages == null || messages.isEmpty()) {
                return;
            }

            // Add a slight delay to ensure the message is seen after join messages
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                try {
                    // Send notification about unread mail
                    player.sendMessage(lang.get("msg.mail-remind")
                            .replace("{n}", String.valueOf(messages.size())));
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Error delivering offline messages to " + playerName, e);
                }
            }, 40L); // Increased from 20 to 40 ticks (2 seconds) for more reliable delivery
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in deliverOfflineMessages", e);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        deliverOfflineMessages(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        savePendingMessages();
        
        // Save last message senders to database
        UUID playerId = player.getUniqueId();
        UUID lastSender = lastMessageSender.get(playerId);
        if (lastSender != null) {
            database.saveLastMessageSender(playerId, lastSender);
        }
    }

    /**
     * Gets a list of online player names that match the beginning of the input string
     * Optionally can filter based on sender permissions
     *
     * @param input The beginning of the player name to match
     * @param sender The command sender performing the tab completion
     * @param requirePermission Whether to check permissions before including players
     * @return List of matching online player names
     */
    private List<String> getOnlinePlayerNames(String input, CommandSender sender, boolean requirePermission) {
        List<String> names = new ArrayList<>();
        String lowercaseInput = input.toLowerCase();

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            // Skip vanished players or those the sender can't see
            if (requirePermission && sender instanceof Player &&
                    !((Player) sender).canSee(player)) {
                continue;
            }

            String playerName = player.getName();
            if (playerName.toLowerCase().startsWith(lowercaseInput)) {
                names.add(playerName);
            }
        }

        return names;
    }

    /**
     * Gets a list of online player names that match the beginning of the input string
     *
     * @param input The beginning of the player name to match
     * @return List of matching online player names
     */
    private List<String> getOnlinePlayerNames(String input) {
        return getOnlinePlayerNames(input, null, false);
    }

    /**
     * Gets a list of all player names (online and offline) that match
     * the beginning of the input string
     *
     * @param input The beginning of the player name to match
     * @return List of matching player names
     */
    private List<String> getAllPlayerNames(String input) {
        List<String> names = new ArrayList<>();

        // Add online players
        names.addAll(getOnlinePlayerNames(input)); // This will use the overload that doesn't check permissions by default

        // Add offline players who have played before
        for (OfflinePlayer offlinePlayer : plugin.getServer().getOfflinePlayers()) {
            // Ensure offlinePlayer.getName() is not null before using it
            if (offlinePlayer.hasPlayedBefore() && offlinePlayer.getName() != null && offlinePlayer.getName().toLowerCase().startsWith(input.toLowerCase())) {
                names.add(offlinePlayer.getName());
            }
        }
        // Deduplicate and sort if necessary, though distinct players should be handled by how they are added.
        return names.stream().distinct().sorted().collect(Collectors.toList());
    }

    /**
     * Filters a list of strings to only those starting with the given input
     *
     * @param options The list of possible options
     * @param input The beginning of the string to match
     * @return Filtered list of matching options
     */
    private List<String> filterStartingWith(List<String> options, String input) {
        return options.stream()
                .filter(option -> option.toLowerCase().startsWith(input.toLowerCase()))
                .collect(Collectors.toList());
    }

    private List<GiftEntry> getGiftsFromDatabase(UUID playerName) {
        List<GiftEntry> gifts = new ArrayList<>();
        
        try {
            // Now get unclaimed gifts for this player
            String sql = "SELECT * FROM mail_gifts WHERE recipient = ? AND is_claimed = FALSE";
            
            database.executeQuery(sql, resultSet -> {
                try {
                    while (resultSet.next()) {
                        int id = resultSet.getInt("id");
                        String senderUuid = resultSet.getString("sender");
                        String senderName = resultSet.getString("sender_name");
                        String items = resultSet.getString("items");
                        long sentTime = resultSet.getTimestamp("sent_time").getTime();
                        
                        gifts.add(new GiftEntry(id, UUID.fromString(senderUuid), senderName, items, sentTime));
                    }
                } catch (Exception e) {
                    plugin.getLogger().severe("Error retrieving gifts from database: " + e.getMessage());
                }
                return null;
            }, playerName.toString());
            
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to retrieve gifts from database: " + e.getMessage());
        }
        
        return gifts;
    }

    /**
     * Represents a gift entry from the database
     */
    private record GiftEntry(int id, UUID sender, String senderName, String serializedItems, long timestamp) {
    }

    /**
     * Represents a mail message that can be stored and retrieved later
     *
     * @param id Add ID field for database reference
     */
    private record MailMessage(UUID sender, String senderName, String message, long timestamp, int id) {
    }
    // Class to store gift session data
    private static class GiftSession {
        private final String recipient;
        private final Inventory inventory;

        public GiftSession(String recipient, Inventory inventory) {
            this.recipient = recipient;
            this.inventory = inventory;
        }

        // Getters
        public String getRecipient() {
            return recipient;
        }

        public Inventory getInventory() {
            return inventory;
        }
    }

    /**
     * Sends a direct message from sender to recipient
     * @param sender The sender of the message
     * @param recipient The player receiving the message
     * @param message The message content
     */
    private boolean sendDirectMessage(CommandSender sender, Player recipient, String message) {
        // Format and send messages
        String formattedToSender = lang.get("msg.sent")
                .replace("{recipient}", recipient.getName())
                .replace("{message}", message);

        String formattedToRecipient = lang.get("msg.received")
                .replace("{sender}", sender.getName())
                .replace("{message}", message);

        sender.sendMessage(Text.parseColors(formattedToSender));
        recipient.sendMessage(Text.parseColors(formattedToRecipient));

        // Only track player-player conversations
        if (sender instanceof Player && recipient instanceof Player) {
            lastMessageSender.put(((Player) sender).getUniqueId(), recipient.getUniqueId());
            lastMessageSender.put(recipient.getUniqueId(), ((Player) sender).getUniqueId());
        }

        // Send spy message
        UUID recipientUUID = recipient.getUniqueId();
        
        String spyMessage = lang.get("spy.format")
                .replace("{sender}", sender.getName())
                .replace("{recipient}", recipient.getName())
                .replace("{message}", message);

        spyCommand.broadcastSpyMessage(spyMessage, ((Player) sender).getUniqueId(), recipientUUID);
    

        return true;
    }

    /**
     * Clears all mail for a player without reading it
     *
     * @param player The player clearing their mail
     * @return true if mail was cleared
     */
    private boolean handleMailClear(Player player) {
        String playerName = player.getName().toLowerCase();

        // Check if player has mail
        if (!offlineMessages.containsKey(playerName) || offlineMessages.get(playerName).isEmpty()) {
            player.sendMessage(lang.get("msg.mail-empty"));
            return true;
        }

        // Mark messages as read in database
        markMailMessagesAsRead(playerName);

        // Clear from memory
        offlineMessages.remove(playerName);

        player.sendMessage(lang.get("msg.mail-clear"));
        return true;
    }

    /**
     * Sends a mail message to a player, either directly if online or stores it for later if offline
     * @param sender The sender of the message
     * @param recipient The recipient player name
     * @param message The message content
     * @return true if the message was sent successfully
     */
    private boolean sendMailMessage(CommandSender sender, String recipient, String message) {
        // Check if player is online
        Player onlinePlayer = plugin.getServer().getPlayer(recipient);
        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            // Player is online, use direct message instead of mail
            return sendDirectMessage(sender, onlinePlayer, message);
        }

        // For offline players, we can check if they have played before
        OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayer(recipient);
        if (!offlinePlayer.hasPlayedBefore()) {
            // Player hasn't played before
            sender.sendMessage(lang.get("player-not-found").replace("{name}", recipient));
            return false;
        }

        // Player exists but is offline, proceed with mail functionality
        UUID senderUUID = (sender instanceof Player) ? ((Player) sender).getUniqueId() : new UUID(0, 0);
        String senderName = (sender instanceof Player) ? sender.getName() : "Console";

        // Save to database
        int messageId = saveMailMessageToDatabase(recipient, senderUUID, senderName, message);

        if (messageId == -1) {
            Text.sendErrorMessage(sender, lang.get("contact-admin"), lang);
            return false;
        }

        // Create mail message object
        MailMessage mailMessage = new MailMessage(senderUUID, senderName, message, System.currentTimeMillis(), messageId);

        // Store in memory cache
        offlineMessages.computeIfAbsent(recipient.toLowerCase(), k -> new ArrayList<>())
                .add(mailMessage);

        sender.sendMessage(lang.get("msg.mail-sent").replace("{name}", recipient));
        return true;
    }
}
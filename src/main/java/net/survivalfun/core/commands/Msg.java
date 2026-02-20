package net.survivalfun.core.commands;

import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Text;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.util.SchedulerAdapter;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.Base64;
import org.bukkit.ChatColor;

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
    private final boolean placeholderAPIEnabled;
    private final MiniMessage miniMessage;

    // Command aliases for different functionalities
    private final List<String> messageCommandAliases;
    private final List<String> replyCommandAliases;
    private final List<String> mailCommandAliases;

    // Maps to store conversation history and mail messages
    private final Map<UUID, UUID> lastMessageSender;
    private final Map<String, List<MailMessage>> offlineMessages;
    private final Map<UUID, GiftSession> pendingGifts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> mailCooldowns = new ConcurrentHashMap<>();

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
        this.placeholderAPIEnabled = plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null;
        this.miniMessage = MiniMessage.miniMessage();

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
                return filterStartingWith(Arrays.asList("read", "send", "clear", "gift", "claim"), args[0]);
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
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label);
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
            Text.sendErrorMessage(sender, "player-not-online", lang, "{name}", Bukkit.getOfflinePlayer(targetPlayerName).getName());
            sender.sendMessage(lang.get("msg.mail-suggest").replace("{name}", Bukkit.getOfflinePlayer(targetPlayerName).getName()));
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
            sender.sendMessage(lang.get("msg.reply"));
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

    @EventHandler
    public void onGiftInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        GiftSession session = pendingGifts.get(player.getUniqueId());
        if (session == null) return;

        // Ensure this is the same inventory we opened
        if (event.getInventory() != session.getInventory()) return;

        try {
            // Collect items
            ItemStack[] contents = event.getInventory().getContents();
            int nonEmpty = countNonNullItems(contents);
            if (nonEmpty <= 0) {
                // No items placed, cancel session silently
                pendingGifts.remove(player.getUniqueId());
                player.sendMessage(lang.get("msg.gift-cancel"));
                return;
            }

            // Resolve recipient UUID
            OfflinePlayer offline = plugin.getServer().getOfflinePlayer(session.getRecipient());
            if (offline == null || (!offline.hasPlayedBefore() && !offline.isOnline())) {
                pendingGifts.remove(player.getUniqueId());
                Text.sendErrorMessage(player, "player-not-found", lang, "{name}", session.getRecipient());
                return;
            }

            // Save gift to database
            int giftId = plugin.getDatabase().saveMailGift(
                    player.getUniqueId(),
                    player.getName(),
                    offline.getUniqueId(),
                    contents
            );

            pendingGifts.remove(player.getUniqueId());

            player.sendMessage(lang.get("msg.gift-sent")
                    .replace("{name}", session.getRecipient()));

            Player recipient = plugin.getServer().getPlayer(session.getRecipient());
            if (recipient != null) {
                sendGiftNotificationToRecipient(recipient, player.getName(), contents, giftId);
            }
        } catch (Exception e) {
            pendingGifts.remove(player.getUniqueId());
            Text.sendDebugLog(ERROR, "Failed to save gift on inventory close", e);
            Text.sendErrorMessage(player, "unknown-error", lang);
        }
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
        if (!sender.hasPermission("allium.mail")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "mail");
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
                return sendMailMessage(sender, recipient, message, false);
            case "sendall":
                if (args.length < 3) {
                    sender.sendMessage(lang.get("command-usage").replace("{cmd}", "mail")
                            .replace("{args}", "sendall <message>"));
                    return true;
                }
                String broadcast = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                return sendMailMessage(sender, "all", broadcast, true);

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
        if (!player.hasPermission("allium.mail.gift")) {
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
            Text.sendErrorMessage(player, "invalid", lang, "{arg}", "&cgift ID: &7" + args[1], "{syntax}", "You must use an integer.");
            return true;
        }

        // Get the gift from the database
        GiftEntry gift = getGiftById(giftId, player.getUniqueId());
        if (gift == null) {
            Text.sendErrorMessage(player, "msg.gift-not-found", lang, "{id}", String.valueOf(giftId));
            return true;
        }

        try {
            // Deserialize the gift items using the database utility method
            ItemStack[] items = plugin.getDatabase().deserializeGiftItems(gift.serializedItems());

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
            Text.sendDebugLog(ERROR, "Failed to process gift claim", e);
            Text.sendErrorMessage(player, "msg.gift-not-found", lang);
            return true;
        }
    }

    private boolean handleMailRead(Player player) {
        String playerName = player.getName().toLowerCase();
        List<MailMessage> messages = offlineMessages.get(playerName);

        if (!player.hasPermission("allium.mail")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "mail");
            return true;
        }

        // Also load unclaimed gifts for this player
        List<GiftEntry> gifts = getGiftsFromDatabase(player.getUniqueId());

        if ((messages == null || messages.isEmpty()) && (gifts == null || gifts.isEmpty())) {
            player.sendMessage(lang.get("msg.mail-empty"));
            return true;
        }

        // Format header with proper placeholders
        int mailCount = messages == null ? 0 : messages.size();
        String headerText = lang.get("msg.mail-header")
                .replace("{n}", String.valueOf(mailCount))
                .replace("{title}", mailCount == 1 ? "Message" : "Messages");
        player.sendMessage(Text.parseColors(headerText));

        // Display all messages
        int messageIndex = 1;
        if (messages != null) for (MailMessage message : messages) {
            String formattedTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(new Date(message.timestamp));

            // Parse colors in the message content
            String parsedMessage = Text.parseColors(message.message());
            
            String mailFormatText = lang.get("msg.mail-format")
                    .replace("{i}", String.valueOf(messageIndex))
                    .replace("{sender}", message.senderName())
                    .replace("{time}", formattedTime)
                    .replace("{message}", parsedMessage);
            player.sendMessage(Text.parseColors(mailFormatText));
            messageIndex++;
        }

        // Do not mark as read or clear messages here; `/mail read` is non-destructive.

        // Show gifts section if any
        if (gifts != null && !gifts.isEmpty()) {
            String giftHeader = lang.get("msg.gift-header")
                    .replace("{n}", String.valueOf(gifts.size()));
            player.sendMessage(giftHeader);
            int gi = 1;
            for (GiftEntry gift : gifts) {
                String formattedTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(gift.timestamp()));
                String line = Text.parseColors("&7[&d" + gi + "&7] &5ID:&d " + gift.id() + " &7from &d" + gift.senderName() + " &7at &d" + formattedTime + "&7. Use &d/mail claim " + gift.id() + "&7.");
                player.sendMessage(line);
                gi++;
            }
        }

        // Format footer with current date
        String currentDate = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        String footerText = lang.get("msg.mail-footer")
                .replace("{date}", currentDate);
        player.sendMessage(Text.parseColors(footerText));
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
                // Normalize keys to be case-insensitive (some drivers return UPPERCASE column names)
                Map<String, Object> lower = new HashMap<>();
                for (Map.Entry<String, Object> e : row.entrySet()) {
                    if (e.getKey() != null) lower.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
                }

                String sender = lower.get("sender") != null ? String.valueOf(lower.get("sender")) : null;
                String senderName = lower.get("sender_name") != null ? String.valueOf(lower.get("sender_name")) : null;
                String items = lower.get("items") != null ? String.valueOf(lower.get("items")) : null;

                // Safely extract sent_time supporting various JDBC return types and possible null
                long sentTime;
                Object tsObj = lower.get("sent_time");
                if (tsObj instanceof Timestamp) {
                    sentTime = ((Timestamp) tsObj).getTime();
                } else if (tsObj instanceof java.util.Date) {
                    sentTime = ((java.util.Date) tsObj).getTime();
                } else if (tsObj instanceof Long) {
                    sentTime = (Long) tsObj;
                } else if (tsObj != null) {
                    try {
                        sentTime = Long.parseLong(tsObj.toString());
                    } catch (NumberFormatException nfe) {
                        Text.sendDebugLog(WARN, "Unexpected sent_time type: " + tsObj.getClass().getName() + ", value=" + tsObj + ". Defaulting to current time.");
                        sentTime = System.currentTimeMillis();
                    }
                } else {
                    Text.sendDebugLog(WARN, "sent_time was null for mail_gifts id=" + giftId + "; defaulting to current time.");
                    sentTime = System.currentTimeMillis();
                }

                if (sender == null || senderName == null || items == null) {
                    Text.sendDebugLog(WARN, "Gift row missing required fields for id=" + giftId + ": keys=" + row.keySet());
                    return null;
                }

                return new GiftEntry(giftId, UUID.fromString(sender), senderName, items, sentTime);
            } else {
                Text.sendDebugLog(WARN, "No gift found in database for id=" + giftId + " and recipient=" + recipient);
                return null;
            }
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Failed to retrieve gift from database", e);
            return null;
        }
    }

    /**
     * Marks a gift as claimed in the database
     */
    private void markGiftAsClaimed(int giftId) {
        try {
            String sql = "UPDATE mail_gifts SET is_claimed = TRUE WHERE id = ?";
            int updated = database.executeUpdate(sql, giftId);
            
            if (updated == 0) {
                Text.sendDebugLog(WARN, "Failed to mark gift as claimed - no rows updated for id: " + giftId);
            }
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Failed to mark gift as claimed", e);
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
        try (Connection conn = database.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT * FROM mail_messages WHERE is_read = FALSE")) {
            
            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Mail] Loading unread messages from database...");
            }

            try (ResultSet rs = stmt.executeQuery()) {
                int count = 0;
                while (rs.next()) {
                    try {
                        String recipient = rs.getString("recipient");
                        String sender = rs.getString("sender");
                        String senderName = rs.getString("sender_name");
                        String message = rs.getString("message");
                        long sentTime = rs.getTimestamp("sent_time").getTime();
                        int messageId = rs.getInt("id");
                        
                        if (plugin.isDebugMode()) {
                            Text.sendDebugLog(INFO, String.format(
                                "[Mail] Loading message: id=%d, recipient=%s, sender=%s, time=%d",
                                messageId, recipient, sender, sentTime
                            ));
                        }

                        MailMessage mailMessage = new MailMessage(
                                UUID.fromString(sender),
                                senderName,
                                message,
                                sentTime,
                                messageId
                        );

                        List<MailMessage> messages = offlineMessages.computeIfAbsent(
                            recipient.toLowerCase(), 
                            k -> new ArrayList<>()
                        );
                        messages.add(mailMessage);
                        count++;
                    } catch (Exception e) {
                        Text.sendDebugLog(ERROR, "Error processing mail message: " + e.getMessage());
                    }
                }
                
                if (plugin.isDebugMode()) {
                    Text.sendDebugLog(INFO, "[Mail] Loaded " + count + " unread messages for " + 
                        offlineMessages.size() + " recipients");
                }
            }
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Error loading mail messages from database: " + e.getMessage());
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
            Text.sendDebugLog(ERROR, "Failed to save mail message to database: " + e.getMessage());
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
            Text.sendDebugLog(ERROR, "Failed to mark mail messages as read: " + e.getMessage());
        }
    }

    public void savePendingMessages() {
    }

    public void deliverOfflineMessages(Player player) {
        try {
            if (player == null) {
                Text.sendDebugLog(WARN, "Attempted to deliver offline messages to null player");
                return;
            }

            if (plugin.isDebugMode()) {
                Text.sendDebugLog(INFO, "[Mail] Scheduling offline message delivery for " + player.getName() + 
                    " (UUID: " + player.getUniqueId() + ") in 40 ticks");
            }

            // Add a slight delay to ensure permissions and messages are ready (Folia-safe)
            SchedulerAdapter.runAtEntityLater(player, () -> {
                try {
                    if (plugin.isDebugMode()) {
                        Text.sendDebugLog(INFO, "[Mail] Processing offline messages for " + player.getName() + 
                            " (online: " + player.isOnline() + ")");
                    }

                    // Re-check permissions after delay (some providers attach after join)
                    boolean hasMail = player.hasPermission("allium.mail");
                    boolean hasRead = player.hasPermission("allium.mail.read");
                    
                    if (plugin.isDebugMode()) {
                        Text.sendDebugLog(INFO, "[Mail] Permissions for " + player.getName() + 
                            ": allium.mail=" + hasMail + ", allium.mail.read=" + hasRead);
                    }
                    
                    if (!(hasMail && hasRead)) {
                        if (plugin.isDebugMode()) {
                            Text.sendDebugLog(INFO, "[Mail] Skipping remind for " + player.getName() +
                                    " (missing required permissions)");
                        }
                        return;
                    }

                    String playerName = player.getName().toLowerCase();
                    String playerUuidKey = player.getUniqueId().toString().toLowerCase();

                    if (plugin.isDebugMode()) {
                        Text.sendDebugLog(INFO, "[Mail] Checking for messages for name='" + playerName + 
                            "' or UUID='" + playerUuidKey + "'");
                    }

                    List<MailMessage> messages = offlineMessages.get(playerName);
                    // Fallback: also check UUID key in case messages were stored by UUID due to legacy data
                    if ((messages == null || messages.isEmpty())) {
                        List<MailMessage> uuidMessages = offlineMessages.get(playerUuidKey);
                        if (uuidMessages != null && !uuidMessages.isEmpty()) {
                            messages = uuidMessages;
                            if (plugin.isDebugMode()) {
                                Text.sendDebugLog(INFO, "[Mail] Found " + messages.size() + " messages using UUID key for " + player.getName());
                            }
                        }
                    }

                    if (messages == null || messages.isEmpty()) {
                        if (plugin.isDebugMode()) {
                            int totalKeys = offlineMessages.size();
                            Text.sendDebugLog(INFO, "[Mail] No unread messages found for " + player.getName() + 
                                " (name='" + playerName + "', UUID='" + playerUuidKey + ", " +
                                "cached recipients: " + totalKeys + ")");
                            
                            // Log first few keys for debugging
                            int i = 0;
                            for (String key : offlineMessages.keySet()) {
                                if (i++ > 5) break;
                                Text.sendDebugLog(INFO, "[Mail] Cached recipient " + i + ": " + key);
                            }
                        }
                        return;
                    }

                    String remind = lang.get("msg.mail-remind").replace("{n}", String.valueOf(messages.size()));
                    if (plugin.isDebugMode()) {
                        Text.sendDebugLog(INFO, "[Mail] Sending reminder to " + player.getName() + ": " + remind);
                    }
                    player.sendMessage(remind);
                    if (plugin.isDebugMode()) {
                        Text.sendDebugLog(INFO, "[Mail] Sent mail reminder to " + player.getName() + ": count=" + messages.size());
                    }
                } catch (Exception e) {
                    Text.sendDebugLog(ERROR, "Error delivering offline messages to " + player.getName().toLowerCase(), e);
                }
            }, 40L);
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Error in deliverOfflineMessages", e);
        }
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
                    Text.sendDebugLog(ERROR, "Error retrieving gifts from database: " + e.getMessage());
                }
            }, playerName.toString());
            
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Failed to retrieve gifts from database: " + e.getMessage());
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

    private void sendGiftNotificationToRecipient(Player recipient, String senderName, ItemStack[] items, int giftId) {
        String rawMessage = lang.getRaw("msg.gift-received");
        if (rawMessage == null || rawMessage.isEmpty()) {
            recipient.sendMessage(Text.parseColors("&aYou have received a gift from &e" + senderName + "&a."));
            recipient.sendMessage(Text.parseColors("&aUse &e/mail claim " + giftId + " &ato claim it."));
            return;
        }

        String idColor = resolveLegacyColor(rawMessage, "{id}", "&d");
        String giftColor = resolveLegacyColor(rawMessage, "{gift}", "&5");
        String separatorColor = resolveLegacyColor(rawMessage, "{id}", "&d");
        String claimColor = resolveLegacyColor(rawMessage, "{claim}", "&a");

        String formattedItems = buildGiftItemList(items, giftColor, separatorColor);
        if (formattedItems.isEmpty()) {
            formattedItems = giftColor + "Unknown items";
        }

        String[] lines = rawMessage.split("\\n");
        LegacyComponentSerializer serializer = LegacyComponentSerializer.legacyAmpersand();
        String command = "/mail claim " + giftId;
        Component hoverText = serializer.deserialize("&7Click to suggest &f" + command);

        for (String line : lines) {
            String processed = line.replace("{name}", senderName)
                    .replace("{id}", String.valueOf(giftId))
                    .replace("{gift}", formattedItems);

            if (line.contains("{claim}")) {
                String claimText = claimColor + "Click to Claim! &7(/mail claim " + giftId + ")";
                processed = processed.replace("{claim}", claimText);
                Component component = serializer.deserialize(processed)
                        .clickEvent(ClickEvent.suggestCommand(command))
                        .hoverEvent(HoverEvent.showText(hoverText));
                recipient.sendMessage(component);
            } else {
                Component component = serializer.deserialize(processed);
                recipient.sendMessage(component);
            }
        }
    }

    private String resolveLegacyColor(String template, String placeholder, String fallback) {
        if (template == null || placeholder == null || placeholder.isEmpty()) {
            return fallback;
        }

        int index = template.indexOf(placeholder);
        if (index == -1) {
            return fallback;
        }

        String before = template.substring(0, index);
        String parsed = Text.parseColors(before);
        String lastColors = ChatColor.getLastColors(parsed);
        if (lastColors == null || lastColors.isEmpty()) {
            return fallback;
        }
        return lastColors.replace('§', '&');
    }

    private String buildGiftItemList(ItemStack[] items, String itemColor, String separatorColor) {
        if (items == null || items.length == 0) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        String itemColorCode = itemColor != null ? itemColor : "&f";
        String separatorColorCode = separatorColor != null ? separatorColor : "&f";

        for (ItemStack item : items) {
            String description = formatGiftItem(item);
            if (description == null || description.isEmpty()) {
                continue;
            }

            if (builder.length() > 0) {
                builder.append(separatorColorCode).append(", ");
            }

            builder.append(itemColorCode).append(description);
        }

        return builder.toString();
    }

    private String formatGiftItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        String name = null;
        if (meta != null && meta.hasDisplayName()) {
            String parsedName = Text.parseColors(meta.getDisplayName());
            name = ChatColor.stripColor(parsedName);
        }

        if (name == null || name.isEmpty()) {
            name = formatMaterialName(item.getType());
        }

        return item.getAmount() + "x " + name;
    }

    private String formatMaterialName(Material material) {
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        return Arrays.stream(parts)
                .filter(part -> !part.isEmpty())
                .map(part -> Character.toUpperCase(part.charAt(0)) + part.substring(1))
                .collect(Collectors.joining(" "));
    }

    /**
     * Sends a direct message from sender to recipient
     * @param sender The sender of the message
     * @param recipient The player receiving the message
     * @param message The message content
     */
    private boolean sendDirectMessage(CommandSender sender, Player recipient, String message) {
        // Filter colors based on sender's permissions
        String filteredMessage = filterMessageColors(sender, message);
        
        // Format and send messages
        String formattedToSender = lang.get("msg.sent")
                .replace("{recipient}", recipient.getName())
                .replace("{message}", filteredMessage);

        String formattedToRecipient = lang.get("msg.received")
                .replace("{sender}", sender.getName())
                .replace("{message}", filteredMessage);

        // Send messages using legacy format to avoid MiniMessage parsing conflicts
        // Convert any remaining MiniMessage tags to legacy format or strip them
        String senderMessage = convertMiniMessageToLegacy(formattedToSender);
        String recipientMessage = convertMiniMessageToLegacy(formattedToRecipient);
        
        sender.sendMessage(Text.parseColors(senderMessage));
        recipient.sendMessage(Text.parseColors(recipientMessage));

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
            .replace("{message}", filteredMessage);
        
        // Sender may be console or a command block; avoid casting to Player
        UUID senderUUID = (sender instanceof Player) ? ((Player) sender).getUniqueId() : null;
        spyCommand.broadcastSpyMessage(spyMessage, senderUUID, recipientUUID);
    

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

        if (!player.hasPermission("allium.mail")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "mail");
            return true;
        }

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
     * @param isGlobal Whether this is a global broadcast
     * @return true if the message was sent successfully
     */
    private boolean sendMailMessage(CommandSender sender, String recipient, String message, boolean isGlobal) {
        if (!sender.hasPermission("allium.mail.send")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "use /{cmd}", "send mail", true);
            return false;
        }

        // Check cooldown for players (not console)
        if (sender instanceof Player player) {
            if (!player.hasPermission("allium.mail.send.nocooldown")) {
                long cooldownSeconds = plugin.getConfig().getLong("mail.cooldown", 30);
                if (cooldownSeconds > 0) {
                    UUID playerUUID = player.getUniqueId();
                    long currentTime = System.currentTimeMillis();
                    Long lastSendTime = mailCooldowns.get(playerUUID);
                    
                    if (lastSendTime != null) {
                        long timeSinceLastSend = (currentTime - lastSendTime) / 1000;
                        if (timeSinceLastSend < cooldownSeconds) {
                            long remainingCooldown = cooldownSeconds - timeSinceLastSend;
                            Text.sendErrorMessage(player, "cooldown", lang, "{time}", Text.formatTime((int) remainingCooldown), "using /{cmd}", "sending mail", true);
                            return false;
                        }
                    }
                    
                    // Update cooldown time
                    mailCooldowns.put(playerUUID, currentTime);
                }
            }
        }

        UUID senderUUID = (sender instanceof Player) ? ((Player) sender).getUniqueId() : new UUID(0, 0);
        String senderName = (sender instanceof Player) ? sender.getName() : "Console";
        
        // Filter message based on sender's permissions
        String filteredMessage = filterMailColors(sender, message);

        if (isGlobal) {
            // Check specific permission for global mail broadcasts
            if (!sender.hasPermission("allium.mail.sendall")) {
                Text.sendErrorMessage(sender, "no-permission", lang, "use /{cmd}", "send mail to all players", true);
                return false;
            }
            
            // Global mail broadcast to all players in database
            try {
                String sql = "SELECT DISTINCT uuid, name FROM player_data WHERE name IS NOT NULL";
                List<String> allPlayers = new ArrayList<>();
                
                database.executeQuery(sql, (ResultSet resultSet) -> {
                    try {
                        while (resultSet.next()) {
                            String playerName = resultSet.getString("name");
                            if (playerName != null && !playerName.isEmpty()) {
                                allPlayers.add(playerName);
                            }
                        }
                    } catch (Exception e) {
                        Text.sendDebugLog(ERROR, "Error processing player names for global mail: " + e.getMessage());
                    }
                });

                int sentCount = 0;
                for (String playerName : allPlayers) {
                    // Check if player is online first
                    Player onlinePlayer = plugin.getServer().getPlayer(playerName);
                    if (onlinePlayer != null && onlinePlayer.isOnline()) {
                        // Send direct message to online players
                        sendDirectMessage(sender, onlinePlayer, filteredMessage);
                        sentCount++;
                    } else {
                        // Save to database for offline players
                        int messageId = saveMailMessageToDatabase(playerName, senderUUID, senderName, filteredMessage);
                        if (messageId != -1) {
                            // Create mail message object and store in memory cache
                            MailMessage mailMessage = new MailMessage(senderUUID, senderName, filteredMessage, System.currentTimeMillis(), messageId);
                            offlineMessages.computeIfAbsent(playerName.toLowerCase(), k -> new ArrayList<>())
                                    .add(mailMessage);
                            sentCount++;
                        }
                    }
                }

                String mailSentRaw = lang.getRaw("msg.mail-broadcast-sent");
                String mailSentFilled = mailSentRaw
                        .replace("{count}", String.valueOf(sentCount))
                        .replace("{message}", filteredMessage);
                String mailSentSafe = convertMiniMessageToLegacy(mailSentFilled);
                sender.sendMessage(Text.parseColors(mailSentSafe));
                return true;
                
            } catch (Exception e) {
                Text.sendDebugLog(ERROR, "Failed to send global mail: " + e.getMessage());
                Text.sendErrorMessage(sender, lang.get("contact-admin"), lang);
                return false;
            }
        } else {
            // Single recipient mail
            // Check if player is online
            Player onlinePlayer = plugin.getServer().getPlayer(recipient);
            if (onlinePlayer != null && onlinePlayer.isOnline()) {
                // Player is online, use direct message instead of mail
                return sendDirectMessage(sender, onlinePlayer, filteredMessage);
            }

            // For offline players, we can check if they have played before
            OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayer(recipient);
            if (!offlinePlayer.hasPlayedBefore()) {
                // Player hasn't played before
                sender.sendMessage(lang.get("player-not-found").replace("{name}", recipient));
                return false;
            }

            // Player exists but is offline, proceed with mail functionality
            // Save to database
            int messageId = saveMailMessageToDatabase(recipient, senderUUID, senderName, filteredMessage);

            if (messageId == -1) {
                Text.sendErrorMessage(sender, lang.get("contact-admin"), lang);
                return false;
            }

            // Create mail message object
            MailMessage mailMessage = new MailMessage(senderUUID, senderName, filteredMessage, System.currentTimeMillis(), messageId);

            // Store in memory cache
            offlineMessages.computeIfAbsent(recipient.toLowerCase(), k -> new ArrayList<>())
                    .add(mailMessage);

            String mailSentRaw = lang.getRaw("msg.mail-sent");
            String mailSentFilled = mailSentRaw
                    .replace("{name}", recipient)
                    .replace("{message}", filteredMessage);
            String mailSentSafe = convertMiniMessageToLegacy(mailSentFilled);
            sender.sendMessage(Text.parseColors(mailSentSafe));
            return true;
        }
    }
    
    /**
     * Filters message colors and formatting based on sender's permissions for direct messages
     * @param sender The sender of the message
     * @param message The original message
     * @return The filtered message
     */
    private String filterMessageColors(CommandSender sender, String message) {
        if (!(sender instanceof Player)) {
            // Process PlaceholderAPI for console (if applicable)
            return processPlaceholderAPI(sender, message, "allium.msg");
        }
        
        Player player = (Player) sender;
        String filteredMessage = message;
        
        // Process PlaceholderAPI first
        filteredMessage = processPlaceholderAPI(sender, filteredMessage, "allium.msg");
        
        // Filter legacy color codes
        filteredMessage = stripUnauthorizedFormatting(filteredMessage, player, "allium.msg");
        
        // Filter MiniMessage tags
        filteredMessage = stripUnauthorizedMiniMessage(filteredMessage, player, "allium.msg");
        
        return filteredMessage;
    }
    
    /**
     * Filters message colors and formatting based on sender's permissions for mail messages
     * @param sender The sender of the message
     * @param message The original message
     * @return The filtered message
     */
    private String filterMailColors(CommandSender sender, String message) {
        if (!(sender instanceof Player)) {
            // Process PlaceholderAPI for console (if applicable)
            return processPlaceholderAPI(sender, message, "allium.mail");
        }
        
        Player player = (Player) sender;
        String filteredMessage = message;
        
        // Process PlaceholderAPI first
        filteredMessage = processPlaceholderAPI(sender, filteredMessage, "allium.mail");
        
        // Filter legacy color codes
        filteredMessage = stripUnauthorizedFormatting(filteredMessage, player, "allium.mail");
        
        // Filter MiniMessage tags
        filteredMessage = stripUnauthorizedMiniMessage(filteredMessage, player, "allium.mail");
        
        return filteredMessage;
    }
    
    /**
     * Strips unauthorized formatting codes based on granular permissions
     * @param message The message to filter
     * @param player The player sending the message
     * @param permissionPrefix The permission prefix (allium.msg or allium.mail)
     * @return The filtered message
     */
    private String stripUnauthorizedFormatting(String message, Player player, String permissionPrefix) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        // Wildcard/group permissions support
        boolean allowAnyColor = player.hasPermission(permissionPrefix + ".color") || player.hasPermission(permissionPrefix + ".color.*");
        boolean allowAnyFormat = player.hasPermission(permissionPrefix + ".format") || player.hasPermission(permissionPrefix + ".format.*");
        boolean allowHex = allowAnyColor || player.hasPermission(permissionPrefix + ".color.hex");

        StringBuilder filteredMessage = new StringBuilder();
        int i = 0;

        while (i < message.length()) {
            if (i + 1 < message.length() && (message.charAt(i) == '&' || message.charAt(i) == '\u00a7')) {
                char colorChar = Character.toLowerCase(message.charAt(i + 1));
                boolean keepCode = false;

                switch (colorChar) {
                    case '0': keepCode = allowAnyColor || player.hasPermission(permissionPrefix + ".color.black"); break;
                    case '1': keepCode = allowAnyColor || player.hasPermission(permissionPrefix + ".color.dark_blue"); break;
                    case '2': keepCode = allowAnyColor || player.hasPermission(permissionPrefix + ".color.dark_green"); break;
                    case '3': keepCode = allowAnyColor || player.hasPermission(permissionPrefix + ".color.dark_aqua"); break;
                    case '4': keepCode = allowAnyColor || player.hasPermission(permissionPrefix + ".color.dark_red"); break;
                    case '5': keepCode = allowAnyColor || player.hasPermission(permissionPrefix + ".color.dark_purple"); break;
                    case '6': keepCode = allowAnyColor || player.hasPermission(permissionPrefix + ".color.gold"); break;
                    case '7': keepCode = allowAnyColor || player.hasPermission(permissionPrefix + ".color.gray"); break;
                    case '8': keepCode = allowAnyColor || player.hasPermission(permissionPrefix + ".color.dark_gray"); break;
                    case '9': keepCode = allowAnyColor || player.hasPermission(permissionPrefix + ".color.blue"); break;
                    case 'a': keepCode = allowAnyColor || player.hasPermission(permissionPrefix + ".color.green"); break;
                    case 'b': keepCode = allowAnyColor || player.hasPermission(permissionPrefix + ".color.aqua"); break;
                    case 'c': keepCode = allowAnyColor || player.hasPermission(permissionPrefix + ".color.red"); break;
                    case 'd': keepCode = allowAnyColor || player.hasPermission(permissionPrefix + ".color.light_purple"); break;
                    case 'e': keepCode = allowAnyColor || player.hasPermission(permissionPrefix + ".color.yellow"); break;
                    case 'f': keepCode = allowAnyColor || player.hasPermission(permissionPrefix + ".color.white"); break;
                    case 'l': keepCode = allowAnyFormat || player.hasPermission(permissionPrefix + ".format.bold"); break;
                    case 'o': keepCode = allowAnyFormat || player.hasPermission(permissionPrefix + ".format.italic"); break;
                    case 'n': keepCode = allowAnyFormat || player.hasPermission(permissionPrefix + ".format.underline"); break;
                    case 'm': keepCode = allowAnyFormat || player.hasPermission(permissionPrefix + ".format.strikethrough"); break;
                    case 'k': keepCode = allowAnyFormat || player.hasPermission(permissionPrefix + ".format.magic"); break;
                    case 'r': keepCode = allowAnyFormat || player.hasPermission(permissionPrefix + ".format.reset"); break;
                    default: keepCode = false; break;
                }

                if (keepCode) {
                    filteredMessage.append(message.charAt(i)).append(message.charAt(i + 1));
                    i += 2;
                } else {
                    i += 2;
                }
            } else if (i + 7 < message.length() && message.charAt(i) == '&' && message.charAt(i + 1) == '#' &&
                    message.substring(i + 2, i + 8).matches("[0-9a-fA-F]{6}")) {
                if (allowHex) {
                    filteredMessage.append(message, i, i + 8);
                    i += 8;
                } else {
                    i += 8;
                }
            } else {
                filteredMessage.append(message.charAt(i));
                i++;
            }
        }

        return filteredMessage.toString();
    }
    
    /**
     * Processes PlaceholderAPI placeholders in a message if the sender has permission
     * @param sender The sender of the message
     * @param message The message to process
     * @param permissionPrefix The permission prefix (allium.msg or allium.mail)
     * @return The processed message
     */
    private String processPlaceholderAPI(CommandSender sender, String message, String permissionPrefix) {
        if (!placeholderAPIEnabled || !(sender instanceof Player)) {
            return message;
        }
        
        Player player = (Player) sender;
        if (player.hasPermission(permissionPrefix + ".placeholderapi")) {
            return PlaceholderAPI.setPlaceholders(player, message);
        }
        
        return message;
    }
    
    /**
     * Checks if a player has permission for a specific MiniMessage feature
     * @param player The player to check
     * @param permissionPrefix The permission prefix (allium.msg or allium.mail)
     * @param feature The MiniMessage feature (color, format, hover, click, gradient, rainbow)
     * @return true if the player has permission
     */
    private boolean hasMiniMessagePermission(Player player, String permissionPrefix, String feature) {
        return player.hasPermission(permissionPrefix + ".minimessage." + feature);
    }
    
    /**
     * Strips unauthorized MiniMessage tags based on permissions
     * @param message The message to filter
     * @param player The player sending the message
     * @param permissionPrefix The permission prefix (allium.msg or allium.mail)
     * @return The filtered message
     */
    private String stripUnauthorizedMiniMessage(String message, Player player, String permissionPrefix) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        
        String filteredMessage = message;
        
        // Check for color tags
        if (!hasMiniMessagePermission(player, permissionPrefix, "color")) {
            // Remove color tags like <red>, <blue>, <#FF0000>, etc.
            filteredMessage = filteredMessage.replaceAll("<(?:black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|dark_gray|blue|green|aqua|red|light_purple|yellow|white|#[0-9a-fA-F]{6})>", "");
            filteredMessage = filteredMessage.replaceAll("</(?:black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|dark_gray|blue|green|aqua|red|light_purple|yellow|white|color)>", "");
        } else {
            // Check individual color permissions
            String[] colors = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", 
                             "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};
            for (String color : colors) {
                if (!hasMiniMessagePermission(player, permissionPrefix, "color." + color)) {
                    filteredMessage = filteredMessage.replaceAll("<" + color + ">", "");
                    filteredMessage = filteredMessage.replaceAll("</" + color + ">", "");
                }
            }
            
            // Check hex color permission
            if (!hasMiniMessagePermission(player, permissionPrefix, "color.hex")) {
                filteredMessage = filteredMessage.replaceAll("<#[0-9a-fA-F]{6}>", "");
            }
        }
        
        // Check for format tags
        if (!hasMiniMessagePermission(player, permissionPrefix, "format")) {
            filteredMessage = filteredMessage.replaceAll("<(?:bold|b|italic|i|underlined|u|strikethrough|st|obfuscated|obf)>", "");
            filteredMessage = filteredMessage.replaceAll("</(?:bold|b|italic|i|underlined|u|strikethrough|st|obfuscated|obf)>", "");
        } else {
            // Check individual format permissions
            String[] formats = {"bold", "italic", "underlined", "strikethrough", "obfuscated"};
            String[] formatAliases = {"b", "i", "u", "st", "obf"};
            for (int i = 0; i < formats.length; i++) {
                if (!hasMiniMessagePermission(player, permissionPrefix, "format." + formats[i])) {
                    filteredMessage = filteredMessage.replaceAll("<" + formats[i] + ">", "");
                    filteredMessage = filteredMessage.replaceAll("</" + formats[i] + ">", "");
                    filteredMessage = filteredMessage.replaceAll("<" + formatAliases[i] + ">", "");
                    filteredMessage = filteredMessage.replaceAll("</" + formatAliases[i] + ">", "");
                }
            }
        }
        
        // Check for hover tags
        if (!hasMiniMessagePermission(player, permissionPrefix, "hover")) {
            filteredMessage = filteredMessage.replaceAll("<hover:[^>]*>", "");
            filteredMessage = filteredMessage.replaceAll("</hover>", "");
        }
        
        // Check for click tags
        if (!hasMiniMessagePermission(player, permissionPrefix, "click")) {
            filteredMessage = filteredMessage.replaceAll("<click:[^>]*>", "");
            filteredMessage = filteredMessage.replaceAll("</click>", "");
        }
        
        // Check for gradient tags
        if (!hasMiniMessagePermission(player, permissionPrefix, "gradient")) {
            filteredMessage = filteredMessage.replaceAll("<gradient:[^>]*>", "");
            filteredMessage = filteredMessage.replaceAll("</gradient>", "");
        }
        
        // Check for rainbow tags
        if (!hasMiniMessagePermission(player, permissionPrefix, "rainbow")) {
            filteredMessage = filteredMessage.replaceAll("<rainbow:[^>]*>", "");
            filteredMessage = filteredMessage.replaceAll("<rainbow>", "");
            filteredMessage = filteredMessage.replaceAll("</rainbow>", "");
        }
        
        return filteredMessage;
    }
    
    /**
     * Converts MiniMessage tags to legacy format or strips them to avoid parsing conflicts
     * @param message The message containing potential MiniMessage tags
     * @return The message with MiniMessage tags converted or stripped
     */
    private String convertMiniMessageToLegacy(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        // If the message doesn't contain MiniMessage tags, return as-is
        if (!message.contains("<") || !message.contains(">")) {
            return message;
        }
        
        try {
            // Try to parse with MiniMessage and convert to plain text
            return PlainTextComponentSerializer.plainText().serialize(miniMessage.deserialize(message));
        } catch (Exception e) {
            // If MiniMessage parsing fails, strip all tags manually
            return message.replaceAll("<[^>]*>", "");
        }
    }
}
package net.survivalfun.core.commands.utils;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.survivalfun.core.managers.DB.Database;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.config.Config;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.sql.SQLException;
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
    private final Config config;
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
        this.config = plugin.getConfigManager();
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


    /**
     * Handles the /msg command by sending a direct message to an online player
     *
     * @param sender The sender of the message
     * @param args Command arguments: [recipient, message...]
     * @return true if the command was processed successfully
     */
    private boolean handleMessageCommand(CommandSender sender, String[] args, String label) {
        // Check permission
        if (!sender.hasPermission("core.msg")) {
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
            return sendDirectMessage(sender, targetPlayer, message);
        } else {
            // Offer to send mail instead
            Text.sendErrorMessage(sender, "player-not-online", lang, "{name}", targetPlayerName);

            return true;
        }
    }

    private boolean handleReplyCommand(CommandSender sender, String[] args) {
        // Check permission
        if (!sender.hasPermission("core.msg")) {
            sender.sendMessage(lang.get("no-permission"));
            return true;
        }

        // Check arguments
        if (args.length < 1) {
            sender.sendMessage(lang.get("command-usage").replace("{cmd}", "reply").replace("{args}", "<message>"));
            return true;
        }

        UUID senderUUID;
        UUID lastSenderUUID;

        if (sender instanceof Player player) {
            senderUUID = player.getUniqueId();
            lastSenderUUID = lastMessageSender.get(senderUUID);
        } else {
            // For console, use a special UUID (all zeros)
            senderUUID = new UUID(0, 0);
            lastSenderUUID = lastMessageSender.get(senderUUID);
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

            // Update the conversation tracking
            if (sender instanceof Player) {
                lastMessageSender.put(new UUID(0, 0), ((Player) sender).getUniqueId());
            }

            return true;
        }

        Player replyTarget = plugin.getServer().getPlayer(lastSenderUUID);
        if (replyTarget == null || !replyTarget.isOnline()) {
            sender.sendMessage(lang.get("msg.reply"));
            return true;
        }

        String message = String.join(" ", args);
        return sendDirectMessage(sender, replyTarget, message);
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
        if (!sender.hasPermission("core.msg")) {
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
            Text.sendErrorMessage(sender, lang.get("no-permission"), lang, "{cmd}", "mail gift");
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

        // Create a dispenser inventory (3x3)
        Inventory giftInventory = Bukkit.createInventory(player, InventoryType.DISPENSER,
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

    /**
     * Process the gift when a player closes the gift inventory
     *
     * @param event The InventoryCloseEvent
     */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        // Check if the inventory holder is a player
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        // Check if this player has a pending gift session
        GiftSession session = pendingGifts.get(player.getUniqueId());
        if (session == null) {
            return;
        }

        // Check if this is the gift inventory that was closed
        if (!event.getInventory().equals(session.getInventory())) {
            return;
        }

        // Remove the session
        pendingGifts.remove(player.getUniqueId());

        // Check if there are any items in the inventory
        boolean hasItems = false;
        for (ItemStack item : event.getInventory().getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                hasItems = true;
                break;
            }
        }

        if (!hasItems) {
            player.sendMessage(lang.get("msg.mail-empty"));
            return;
        }

        // Store the gift in the database
        try {
            storeGiftInDatabase(player, session.getRecipient(), event.getInventory().getContents());
            player.sendMessage(lang.get("msg.gift-sent").replace("{name}", session.getRecipient()));
        } catch (Exception e) {
            Text.sendErrorMessage(player, lang.get("msg.gift-failed"), lang, "{name}", session.getRecipient());
            plugin.getLogger().log(Level.SEVERE, "Failed to store gift in database", e);
        }
    }


    /**
     * Stores a gift in the database for the recipient
     *
     * @param sender The player sending the gift
     * @param recipient The recipient's name
     * @param items The gift items
     */
    private void storeGiftInDatabase(Player sender, String recipient, ItemStack[] items) {
        try {
            // Serialize the items to a Base64 string
            String serializedItems = serializeItems(items);

            // Create a database record with the gift information
            String sql = "INSERT INTO mail_gifts (sender, sender_name, recipient, items) VALUES (?, ?, ?, ?)";

            database.executeUpdate(sql,
                    sender.getUniqueId().toString(),
                    sender.getName(),
                    recipient.toLowerCase(),
                    serializedItems
            );

            // Send notification if recipient is online
            Player recipientPlayer = plugin.getServer().getPlayer(recipient);
            if (recipientPlayer != null && recipientPlayer.isOnline()) {
                recipientPlayer.sendMessage(lang.get("msg.gift-received")
                        .replace("{name}", sender.getName()));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to store gift in database", e);
            sender.sendMessage(lang.get("error.gift-save-failed"));
        }
    }

    /**
     * Serializes an array of ItemStacks to a Base64 string
     *
     * @param items The items to serialize
     * @return The serialized items as a Base64 string
     */
    private String serializeItems(ItemStack[] items) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        DataOutputStream dataOutput = new DataOutputStream(outputStream);

        dataOutput.writeInt(items.length); // Write the number of items

        for (ItemStack item : items) {
            if (item == null || item.getType() == Material.AIR) {
                dataOutput.writeInt(0); // Indicate a null or air item with 0 byte length
            } else {
                byte[] itemBytes = item.serializeAsBytes();
                dataOutput.writeInt(itemBytes.length); // Write the length of the byte array
                dataOutput.write(itemBytes);           // Write the byte array
            }
        }

        dataOutput.close();
        return Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }

    /**
     * Deserializes a Base64 string back to an array of ItemStacks
     *
     * @param data The Base64 encoded string
     * @return The deserialized ItemStack array
     */
    private ItemStack[] deserializeItems(String data) throws IOException {
        ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
        DataInputStream dataInput = new DataInputStream(inputStream);

        int arrayLength = dataInput.readInt(); // Read the number of items
        ItemStack[] items = new ItemStack[arrayLength];

        for (int i = 0; i < arrayLength; i++) {
            int itemBytesLength = dataInput.readInt(); // Read the length of the byte array for the item
            if (itemBytesLength == 0) {
                items[i] = null; // Represents a null or air item
            } else {
                byte[] itemBytes = new byte[itemBytesLength];
                dataInput.readFully(itemBytes); // Read the byte array
                items[i] = ItemStack.deserializeBytes(itemBytes);
            }
        }

        dataInput.close();
        return items;
    }

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

        // Update last message tracking for both parties
        UUID senderUUID;

        if (sender instanceof Player player) {
            senderUUID = player.getUniqueId();
        } else {
            // For console, use a special UUID (all zeros)
            senderUUID = new UUID(0, 0);
        }

        // Store who messaged the recipient so they can reply
        lastMessageSender.put(recipient.getUniqueId(), senderUUID);

        // Store who the sender messaged so they can reply
        lastMessageSender.put(senderUUID, recipient.getUniqueId());

        // Send spy message
        UUID recipientUUID = recipient.getUniqueId();
        
        String spyMessage = lang.get("spy.format")
                .replace("{sender}", sender.getName())
                .replace("{recipient}", recipient.getName())
                .replace("{message}", message);

        spyCommand.broadcastSpyMessage(spyMessage, senderUUID, recipientUUID);
    

        return true;
    }


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


    private boolean handleMailRead(Player player) {
        String playerName = player.getName().toLowerCase();
        List<MailMessage> messages = offlineMessages.get(playerName);

        // Get any gifts for the player
        List<GiftEntry> gifts = getGiftsFromDatabase(playerName);

        boolean hasMessages = messages != null && !messages.isEmpty();
        boolean hasGifts = gifts != null && !gifts.isEmpty();

        if (!hasMessages && !hasGifts) {
            player.sendMessage(lang.get("msg.mail-empty"));
            return true;
        }

        int messageCount = messages != null ? messages.size() : 0;
        int giftCount = gifts != null ? gifts.size() : 0;

        player.sendMessage(lang.get("msg.mail-header")
        .replace("{n}", String.valueOf(messageCount + giftCount))
        .replace("{title}", "new messages"));

        // Display all messages
        if (hasMessages) {
            for (int i = 0; i < messageCount; i++) {
                MailMessage message = messages.get(i);

                // Replace {n} dynamically with the current index (e.g., #1, #2)
                String mailIndex = String.valueOf(i + 1);

                player.sendMessage(lang.get("msg.mail-format")
                        .replace("{i}", mailIndex)
                        .replace("{sender}", message.senderName())
                        .replace("{message}", message.message()));
            }

            // Mark messages as read in database
            markMailMessagesAsRead(playerName);

            // Clear messages from memory
            offlineMessages.remove(playerName);
        }

        // Display gifts if there are any
        if (hasGifts) {
            // Get adventure audience for this player
            Audience audience = BukkitAudiences.create(plugin).player(player);

            audience.sendMessage(Component.text(lang.get("msg.gift")));

            for (int i = 0; i < gifts.size(); i++) {
                GiftEntry gift = gifts.get(i);

                // Create a clickable message to claim the gift
                Component message = Component.text(lang.get("msg.gift-format")
                                .replace("{i}", String.valueOf(i + 1))
                                .replace("{sender}", gift.senderName))
                        .clickEvent(ClickEvent.runCommand("/mail claim " + gift.id))
                        .hoverEvent(HoverEvent.showText(
                                Component.text("Click to claim this gift")
                                        .color(NamedTextColor.GRAY)
                        ));
                audience.sendMessage(message);
            }
        }


        player.sendMessage(lang.get("msg.mail-footer").replace("{date}", new SimpleDateFormat("dd/MM/yyyy").format(new Date())));
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
     * Handles the claim subcommand to give the player their gift items
     */
    private boolean handleMailClaim(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(lang.get("command-usate").replace("{command}", "/mail claim")
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
        GiftEntry gift = getGiftById(giftId, player.getName().toLowerCase());
        if (gift == null) {
            Text.sendErrorMessage(player, "msg.gift-not-found", lang, "{id}", String.valueOf(giftId));
            return true;
        }

        try {
            // Deserialize the items
            ItemStack[] items = deserializeItems(gift.serializedItems());

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

    /**
     * Gets a gift by its ID and recipient
     */
    private GiftEntry getGiftById(int giftId, String recipient) {
        try {
            String sql = "SELECT * FROM mail_gifts WHERE id = ? AND recipient = ? AND is_claimed = FALSE";

            AtomicReference<GiftEntry> result = new AtomicReference<>(null);

            database.executeQuery(sql, resultSet -> {
                try {
                    if (resultSet.next()) {
                        String sender = resultSet.getString("sender");
                        String senderName = resultSet.getString("sender_name");
                        String items = resultSet.getString("items");
                        long sentTime = resultSet.getTimestamp("sent_time").getTime();

                        result.set(new GiftEntry(giftId, UUID.fromString(sender), senderName, items, sentTime));
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "Error retrieving gift from database", e);
                }
            }, giftId, recipient.toLowerCase());

            return result.get();
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
            String sql = "UPDATE mail_gifts SET is_claimed = TRUE WHERE id = ?";
            database.executeUpdate(sql, giftId);
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


    /**
     * Formats a timestamp into a readable date/time string
     *
     * @param timestamp The timestamp in milliseconds
     * @return A formatted date/time string
     */
    private String formatTimestamp(long timestamp) {
        // Simple formatting for demo, could use SimpleDateFormat for more complex formatting
        Date date = new Date(timestamp);
        return date.toString();
    }

    private void loadMailMessagesFromDatabase() {
        try {
            String sql = "SELECT * FROM mail_messages WHERE is_read = FALSE";

            database.executeQuery(sql, resultSet -> {
                try {
                    while (resultSet.next()) {
                        String recipient = resultSet.getString("recipient").toLowerCase();
                        String sender = resultSet.getString("sender");
                        String senderName = resultSet.getString("sender_name");
                        String message = resultSet.getString("message");
                        long sentTime = resultSet.getTimestamp("sent_time").getTime();
                        int messageId = resultSet.getInt("id");

                        // Create a mail message object
                        MailMessage mailMessage = new MailMessage(
                                UUID.fromString(sender),
                                senderName,
                                message,
                                sentTime,
                                messageId
                        );

                        // Add to the in-memory map
                        offlineMessages.computeIfAbsent(recipient, k -> new ArrayList<>())
                                .add(mailMessage);
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "Error loading mail messages from database", e);
                }
            });
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load mail messages from database", e);
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
    private int saveMailMessageToDatabase(String recipient, UUID senderUUID, String senderName, String message) {
        try {
            String sql = "INSERT INTO mail_messages (recipient, sender, sender_name, message) VALUES (?, ?, ?, ?)";

            // Execute the update and get the generated ID
            int messageId = database.executeUpdate(sql,
                    recipient.toLowerCase(),
                    senderUUID.toString(),
                    senderName,
                    message
            );

            return messageId;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save mail message to database", e);
            return -1;
        }
    }

    /**
     * Marks mail messages as read in the database
     *
     * @param recipient The recipient's name
     */
    private void markMailMessagesAsRead(String recipient) {
        try {
            String sql = "UPDATE mail_messages SET is_read = TRUE WHERE recipient = ? AND is_read = FALSE";
            database.executeUpdate(sql, recipient.toLowerCase());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to mark mail messages as read", e);
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


    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        String lowerAlias = alias.toLowerCase(); // Use the actual alias used by the player

        // Tab completion for /msg and its direct aliases (tell, w, whisper, etc.)
        if (messageCommandAliases.contains(lowerAlias)) {
            if (args.length == 1) {
                // Suggest online players, considering visibility if sender is a player
                completions.addAll(getOnlinePlayerNames(args[0], sender, true));
            }
        }

        // Tab completion specifically for /mail (which is an alias of msg command but has unique subcommands)
        if ("mail".equals(lowerAlias)) { // Correctly check if the command used was "mail"
            List<String> mailSubcommands = Arrays.asList("read", "send", "clear", "gift", "claim");
            if (args.length == 1) { // For the first argument (subcommand)
                completions.addAll(filterStartingWith(mailSubcommands, args[0]));
            } else if (args.length == 2) { // For the second argument (typically player name)
                String subCommand = args[0].toLowerCase();
                if ("send".equals(subCommand) || "gift".equals(subCommand)) {
                    // Suggest online players, considering visibility
                    completions.addAll(getOnlinePlayerNames(args[1], sender, true));
                }
                // Add other suggestions for args[1] based on subCommand if needed
                // e.g., if 'claim' expects a gift ID, you might suggest available gift IDs
            }
        }
        // Note: /reply (r) typically doesn't take a player name as the first arg in this setup,
        // it implies replying to the last person. If it could, tab completion would be added here.

        // Filter and sort completions to avoid duplicates if different blocks add same suggestions
        return completions.stream().distinct().sorted().collect(Collectors.toList());
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

    /**
     * Retrieves gifts for a player from the database
     *
     * @param playerName The player's name
     * @return A list of gifts for the player
     */
    private List<GiftEntry> getGiftsFromDatabase(String playerName) {
        List<GiftEntry> gifts = new ArrayList<>();

        try {
            String sql = "SELECT * FROM mail_gifts WHERE recipient = ? AND is_claimed = FALSE";

            database.executeQuery(sql, resultSet -> {
                try {
                    while (resultSet.next()) {
                        int id = resultSet.getInt("id");
                        String sender = resultSet.getString("sender");
                        String senderName = resultSet.getString("sender_name");
                        String items = resultSet.getString("items");
                        long sentTime = resultSet.getTimestamp("sent_time").getTime();

                        GiftEntry gift = new GiftEntry(id, UUID.fromString(sender), senderName, items, sentTime);
                        gifts.add(gift);
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "Error loading gifts from database", e);
                }
            }, playerName.toLowerCase());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load gifts from database", e);
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
    private class GiftSession {
        private final String recipient;
        private final Inventory inventory;
        private final long timestamp;

        public GiftSession(String recipient, Inventory inventory) {
            this.recipient = recipient;
            this.inventory = inventory;
            this.timestamp = System.currentTimeMillis();
        }

        // Getters
        public String getRecipient() {
            return recipient;
        }

        public Inventory getInventory() {
            return inventory;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }


}
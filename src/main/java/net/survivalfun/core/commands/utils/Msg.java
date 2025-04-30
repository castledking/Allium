package net.survivalfun.core.commands.utils;

import net.survivalfun.core.managers.DB.Database;
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

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
public class Msg implements CommandExecutor, TabCompleter {

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
            Text.sendErrorMessage(sender, lang.get("player-not-found").replace("{player}", targetPlayerName), lang);
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
                    .replace("{args}", "<read|send|clear> [player] [message...]"));
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

            case "clear":
                return handleMailClear((Player) sender);

            default:
                sender.sendMessage(lang.get("command-usage").replace("{cmd}", "mail")
                        .replace("{args}", "<read|send|clear> [player] [message...]"));
                return true;
        }
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

        // Only send spy messages if the participants aren't themselves spying
        boolean senderIsSpying = (sender instanceof Player) &&
                (spyCommand.isGloballySpying(senderUUID) ||
                        spyCommand.isSpyingOn(senderUUID, recipientUUID));

        boolean recipientIsSpying = spyCommand.isGloballySpying(recipientUUID) ||
                spyCommand.isSpyingOn(recipientUUID, senderUUID);

        if (!(senderIsSpying || recipientIsSpying)) {
            String spyMessage = lang.get("spy.format")
                    .replace("{sender}", sender.getName())
                    .replace("{recipient}", recipient.getName())
                    .replace("{message}", message);

            spyCommand.broadcastSpyMessage(spyMessage, senderUUID, recipientUUID);
        }

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
            sender.sendMessage(lang.get("player-not-found").replace("{player}", recipient));
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

        sender.sendMessage(lang.get("msg.mail-sent").replace("{player}", recipient));
        return true;
    }


    /**
     * Displays all mail messages to a player and clears their mailbox
     *
     * @param player The player reading their mail
     * @return true if mail was displayed
     */
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
            String formattedTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(new java.util.Date(message.getTimestamp()));

            player.sendMessage(lang.get("msg.mail-format")
                    .replace("{sender}", message.getSenderName())
                    .replace("{time}", formattedTime)
                    .replace("{message}", message.getMessage()));
        }

        // Mark messages as read in database
        markMailMessagesAsRead(playerName);

        // Clear messages from memory
        offlineMessages.remove(playerName);

        player.sendMessage(lang.get("msg.mail-footer"));
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
     * Saves all offline messages to persistent storage
     * In a complete implementation, this would save to a database or file
     */
    private void saveOfflineMessages() {
        // Implementation would depend on your storage mechanism
        // For example, using a YAML config:
        // config.set("mail-messages", offlineMessages);
        // config.save();
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


        /**
         * Delivers any stored messages to a player when they log in
         *
         * @param player The player who logged in
         */
    public void deliverOfflineMessages(Player player) {
        String playerName = player.getName().toLowerCase();
        List<MailMessage> messages = offlineMessages.get(playerName);

        if (messages == null || messages.isEmpty()) {
            return;
        }

        // Add a slight delay to ensure the message is seen after join messages
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            // Send notification about unread mail
            player.sendMessage(lang.get("msg.mail-remind")
                    .replace("{count}", String.valueOf(messages.size())));

            // Add a reminder to check mail
            player.sendMessage(lang.get("msg.mail-remind"));
        }, 20L); // 20 ticks = 1 second delay
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
     * Gets a list of online player names that match the beginning of the input string
     *
     * @param input The beginning of the player name to match
     * @return List of matching player names
     */
    private List<String> getOnlinePlayerNames(String input) {
        return plugin.getServer().getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(input.toLowerCase()))
                .collect(Collectors.toList());
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
     * Represents a mail message that can be stored and retrieved later
     */
    private static class MailMessage {
        private final UUID sender;
        private final String senderName;
        private final String message;
        private final long timestamp;
        private final int id; // Add ID field for database reference

        public MailMessage(UUID sender, String senderName, String message, long timestamp, int id) {
            this.sender = sender;
            this.senderName = senderName;
            this.message = message;
            this.timestamp = timestamp;
            this.id = id;
        }

        // Add getters for all fields
        public UUID getSender() {
            return sender;
        }

        public String getSenderName() {
            return senderName;
        }

        public String getMessage() {
            return message;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public int getId() {
            return id;
        }
    }

}
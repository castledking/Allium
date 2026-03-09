package net.survivalfun.core.managers.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.survivalfun.core.PluginStart;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

public class DialogManager {
    private final PluginStart plugin;
    private final Map<String, Map<String, Object>> dialogs = new HashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson GSON_COMPACT = new Gson();
    private String dataPackName = "allium_dialogs";

    public DialogManager(PluginStart plugin) {
        this.plugin = plugin;
    }

    /**
     * Get all dialogs that should show on player join
     */
    public Map<String, Map<String, Object>> getJoinDialogs() {
        Map<String, Map<String, Object>> joinDialogs = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : dialogs.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue().get("show_on_join"))) {
                joinDialogs.put(entry.getKey(), entry.getValue());
            }
        }
        return joinDialogs;
    }

    /**
     * Get all dialogs that should show on player's first join
     */
    public Map<String, Map<String, Object>> getFirstJoinDialogs() {
        Map<String, Map<String, Object>> firstJoinDialogs = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : dialogs.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue().get("show_on_first_join"))) {
                firstJoinDialogs.put(entry.getKey(), entry.getValue());
            }
        }
        return firstJoinDialogs;
    }

    public void loadDialogs() {
        dialogs.clear();
        
        // Load dialogs from resources
        Set<String> dialogFiles = new HashSet<>();
        try {
            InputStream is = plugin.getClass().getResourceAsStream("/dialogs");
            if (is != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.endsWith(".yml")) {
                        dialogFiles.add(line.replace(".yml", ""));
                    }
                }
            }
        } catch (Exception e) {
            Text.sendDebugLog(INFO, "No dialogs directory in resources");
        }

        // Load each dialog file
        for (String fileName : dialogFiles) {
            InputStream is = plugin.getClass().getResourceAsStream("/dialogs/" + fileName + ".yml");
            if (is != null) {
                try {
                    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(is));
                    loadDialogsFromYaml(yaml, fileName);
                } catch (Exception e) {
                    Text.sendDebugLog(ERROR, "Error loading dialog file " + fileName, e);
                }
            }
        }

        // Also check data folder for custom dialogs - create if doesn't exist
        File dialogsFolder = new File(plugin.getDataFolder(), "dialogs");
        if (!dialogsFolder.exists()) {
            dialogsFolder.mkdirs();
            Text.sendDebugLog(INFO, "Created dialogs folder at: " + dialogsFolder.getPath());
            // Copy example dialogs to data folder
            try {
                plugin.saveResource("dialogs/examples.yml", false);
                Text.sendDebugLog(INFO, "Copied examples.yml to dialogs folder");
            } catch (Exception e) {
                Text.sendDebugLog(WARN, "Could not copy examples.yml: " + e.getMessage());
            }
        }
        
        if (dialogsFolder.exists() && dialogsFolder.isDirectory()) {
            File[] files = dialogsFolder.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files != null) {
                Text.sendDebugLog(INFO, "Found " + files.length + " dialog files in data folder");
                for (File file : files) {
                    try {
                        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                        loadDialogsFromYaml(yaml, file.getName().replace(".yml", ""));
                    } catch (Exception e) {
                        Text.sendDebugLog(ERROR, "Error loading dialog file " + file.getName(), e);
                    }
                }
            }
        }

        Text.sendDebugLog(INFO, "Loaded " + dialogs.size() + " dialogs");
    }

    private void loadDialogsFromYaml(YamlConfiguration yaml, String source) {
        for (String key : yaml.getKeys(false)) {
            if (yaml.isConfigurationSection(key)) {
                dialogs.put(key, convertToMap(yaml.getConfigurationSection(key)));
            }
        }
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, Object> convertToMap(org.bukkit.configuration.ConfigurationSection section) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof org.bukkit.configuration.MemorySection) {
                map.put(key, convertToMap((org.bukkit.configuration.ConfigurationSection) value));
            } else if (value instanceof List) {
                List<Object> list = new ArrayList<>();
                for (Object item : (List<?>) value) {
                    if (item instanceof org.bukkit.configuration.MemorySection) {
                        list.add(convertToMap((org.bukkit.configuration.ConfigurationSection) item));
                    } else {
                        list.add(item);
                    }
                }
                map.put(key, list);
            } else {
                map.put(key, value);
            }
        }
        return map;
    }

    public void generateDataPack() {
        if (dialogs.isEmpty()) {
            Text.sendDebugLog(INFO, "No dialogs to generate data pack for");
            return;
        }

        try {
            // Create data pack directory structure
            Path datapackDir = plugin.getDataFolder().toPath().resolve("datapacks").resolve(dataPackName);
            Files.createDirectories(datapackDir);

            // Create pack.mcmeta
            String packMcmeta = """
                {
                  "pack": {
                    "pack_format": 34,
                    "description": "Allium Dialogs"
                  }
                }
                """;
            Files.writeString(datapackDir.resolve("pack.mcmeta"), packMcmeta);

            // Create dialog JSON files
            Path dialogDir = Files.createDirectories(datapackDir.resolve("data/allium/dialog"));
            
            for (Map.Entry<String, Map<String, Object>> entry : dialogs.entrySet()) {
                String dialogName = entry.getKey();
                Map<String, Object> dialogData = entry.getValue();
                
                String json = convertToDialogJson(dialogData, dialogName);
                Files.writeString(dialogDir.resolve(dialogName + ".json"), json);
            }

            Text.sendDebugLog(INFO, "Generated data pack at: " + datapackDir);
            Text.sendDebugLog(INFO, "To use: Upload the datapack to your server's datapacks folder and restart/reload");

        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Error generating dialog data pack", e);
        }
    }

    private String convertToDialogJson(Map<String, Object> dialogData, String dialogName) {
        return convertToDialogJson(dialogData, dialogName, true);
    }

    private String convertToDialogJson(Map<String, Object> dialogData, String dialogName, boolean pretty) {
        Map<String, Object> json = new LinkedHashMap<>();

        // Type
        String type = (String) dialogData.getOrDefault("type", "notice");
        json.put("type", "minecraft:" + type);

        // Title (convert color codes)
        String title = convertColorCodes((String) dialogData.getOrDefault("title", "Dialog"));
        json.put("title", toJsonText(title));

        // Body
        if (dialogData.containsKey("body")) {
            List<Map<String, Object>> bodyList = new ArrayList<>();
            Object bodyObj = dialogData.get("body");
            
            if (bodyObj instanceof List) {
                for (Object item : (List<?>) bodyObj) {
                    if (item instanceof Map) {
                        Map<String, Object> bodyItem = (Map<String, Object>) item;
                        String bodyType = (String) bodyItem.getOrDefault("type", "plain_message");
                        Map<String, Object> bodyElement = new LinkedHashMap<>();
                        bodyElement.put("type", "minecraft:" + bodyType);
                        
                        if (bodyType.equals("plain_message")) {
                            String content = convertColorCodes((String) bodyItem.getOrDefault("content", ""));
                            bodyElement.put("contents", toJsonText(content));
                        }
                        bodyList.add(bodyElement);
                    }
                }
            }
            if (!bodyList.isEmpty()) {
                json.put("body", bodyList);
            }
        }

        // Inputs
        if (dialogData.containsKey("inputs")) {
            List<Map<String, Object>> inputsList = new ArrayList<>();
            Object inputsObj = dialogData.get("inputs");
            
            if (inputsObj instanceof List) {
                for (Object item : (List<?>) inputsObj) {
                    if (item instanceof Map) {
                        Map<String, Object> input = (Map<String, Object>) item;
                        Map<String, Object> inputElement = new LinkedHashMap<>();
                        
                        String inputType = (String) input.getOrDefault("type", "text");
                        inputElement.put("type", "minecraft:" + inputType);
                        inputElement.put("key", input.getOrDefault("key", "input"));
                        inputElement.put("label", toJsonText(convertColorCodes((String) input.getOrDefault("label", "Input:"))));
                        
                        if (inputType.equals("text")) {
                            if (input.containsKey("max_length")) {
                                inputElement.put("max_length", input.get("max_length"));
                            }
                            if (input.containsKey("initial")) {
                                inputElement.put("initial", input.get("initial"));
                            }
                        } else if (inputType.equals("boolean")) {
                            inputElement.put("initial", input.getOrDefault("initial", false));
                            inputElement.put("on_true", input.getOrDefault("on_true", "true"));
                            inputElement.put("on_false", input.getOrDefault("on_false", "false"));
                        }
                        
                        inputsList.add(inputElement);
                    }
                }
            }
            if (!inputsList.isEmpty()) {
                json.put("inputs", inputsList);
            }
        }

        // Actions based on dialog type
        if (type.equals("notice")) {
            // For notice type, use singular "action" (NBT compound), not "actions" array (wiki: Dialog § notice)
            Map<String, Object> actionData = (Map<String, Object>) dialogData.get("action");
            if (actionData != null) {
                Map<String, Object> action = new LinkedHashMap<>();
                
                // Set button text - MUST have "label" field at top level
                String label = convertColorCodes((String) actionData.getOrDefault("label", "OK"));
                Map<String, Object> labelObj = new LinkedHashMap<>();
                labelObj.put("text", label);
                action.put("label", labelObj);
                
                // Check for custom_id (dynamic/custom: server receives payload via PlayerCustomClickEvent)
                // Wiki: button has "label" and nested "action" with "type" and type-specific fields
                if (actionData.containsKey("custom_id")) {
                    String id = (String) actionData.get("custom_id");
                    Map<String, Object> inner = new LinkedHashMap<>();
                    inner.put("type", "minecraft:dynamic/custom");
                    inner.put("id", id != null && id.contains(":") ? id : "allium:" + id);
                    action.put("action", inner);
                } else if (actionData.containsKey("template")) {
                    Map<String, Object> inner = new LinkedHashMap<>();
                    inner.put("type", "minecraft:dynamic/run_command");
                    String template = convertColorCodes((String) actionData.get("template"));
                    inner.put("template", template);
                    action.put("action", inner);
                } else {
                    // Regular/static action: wiki says button has "action": { "type": "...", ... }
                    Map<String, Object> inner = new LinkedHashMap<>();
                    Object actionObj = actionData.get("action");
                    if (actionObj instanceof String) {
                        String actionType = (String) actionObj;
                        if (actionType.equals("run_command") && actionData.containsKey("command")) {
                            inner.put("type", "minecraft:run_command");
                            inner.put("command", convertColorCodes((String) actionData.get("command")));
                        } else if (actionType.equals("open_url") && actionData.containsKey("url")) {
                            inner.put("type", "minecraft:open_url");
                            inner.put("url", actionData.get("url"));
                        } else if (actionType.equals("show_dialog") && actionData.containsKey("dialog")) {
                            inner.put("type", "minecraft:show_dialog");
                            String dialog = actionData.get("dialog").toString();
                            inner.put("dialog", dialog.contains(":") ? dialog : "allium:" + dialog);
                        } else if (actionType.equals("close")) {
                            inner.put("type", "minecraft:close");
                        }
                    } else if (actionObj instanceof Map) {
                        Map<String, Object> nested = (Map<String, Object>) actionObj;
                        String nestedType = (String) nested.getOrDefault("type", "run_command");
                        if (nested.containsKey("template")) {
                            inner.put("type", "minecraft:dynamic/run_command");
                            inner.put("template", convertColorCodes((String) nested.get("template")));
                        } else {
                            inner.put("type", "minecraft:" + nestedType);
                            if (nested.containsKey("command")) {
                                inner.put("command", convertColorCodes((String) nested.get("command")));
                            }
                            if (nested.containsKey("url")) {
                                inner.put("url", nested.get("url"));
                            }
                        }
                    }
                    if (!inner.isEmpty()) {
                        action.put("action", inner);
                    }
                }
                
                if (!action.isEmpty()) {
                    json.put("action", action);
                }
            }
        } else if (type.equals("confirmation")) {
            // For confirmation type, use yes/no fields with proper format - MUST have "label" field
            Map<String, Object> yesData = (Map<String, Object>) dialogData.get("yes");
            Map<String, Object> noData = (Map<String, Object>) dialogData.get("no");
            
            if (yesData != null) {
                json.put("yes", convertConfirmationAction(yesData, "Yes"));
            } else {
                // Default yes button
                Map<String, Object> defaultYes = new LinkedHashMap<>();
                Map<String, Object> labelObj = new LinkedHashMap<>();
                labelObj.put("text", "Yes");
                defaultYes.put("label", labelObj);
                defaultYes.put("type", "minecraft:close");
                json.put("yes", defaultYes);
            }
            
            if (noData != null) {
                json.put("no", convertConfirmationAction(noData, "No"));
            } else {
                // Default no button
                Map<String, Object> defaultNo = new LinkedHashMap<>();
                Map<String, Object> labelObj = new LinkedHashMap<>();
                labelObj.put("text", "No");
                defaultNo.put("label", labelObj);
                defaultNo.put("type", "minecraft:close");
                json.put("no", defaultNo);
            }
        } else if (type.equals("multi_action")) {
            List<Map<String, Object>> actions = new ArrayList<>();
            Object actionsObj = dialogData.get("actions");
            if (actionsObj instanceof List) {
                for (Object item : (List<?>) actionsObj) {
                    if (item instanceof Map) {
                        actions.add(convertMultiAction((Map<String, Object>) item));
                    }
                }
            }
            if (!actions.isEmpty()) {
                json.put("actions", actions);
            }
            if (dialogData.containsKey("columns")) {
                json.put("columns", dialogData.get("columns"));
            }
            if (dialogData.containsKey("exit_action")) {
                json.put("exit_action", convertConfirmationAction((Map<String, Object>) dialogData.get("exit_action"), "Close"));
            }
        } else if (type.equals("dialog_list")) {
            List<String> dialogsList = new ArrayList<>();
            Object dialogsObj = dialogData.get("dialogs");
            if (dialogsObj instanceof List) {
                for (Object item : (List<?>) dialogsObj) {
                    String dialog = item.toString();
                    if (!dialog.contains(":")) {
                        dialog = "allium:" + dialog;
                    }
                    dialogsList.add(dialog);
                }
            }
            if (!dialogsList.isEmpty()) {
                json.put("dialogs", dialogsList);
            }
            if (dialogData.containsKey("exit_action")) {
                json.put("exit_action", convertConfirmationAction((Map<String, Object>) dialogData.get("exit_action"), "Close"));
            }
        }

        // Common fields (but not for confirmation which uses yes/no)
        if (!type.equals("confirmation")) {
            json.put("can_close_with_escape", dialogData.getOrDefault("can_close_with_escape", true));
            json.put("pause", dialogData.getOrDefault("pause", true));
            if (dialogData.containsKey("after_action")) {
                json.put("after_action", dialogData.get("after_action"));
            }
        }

        return (pretty ? GSON : GSON_COMPACT).toJson(json);
    }

    /**
     * Convert action for confirmation dialogs (yes/no) - MUST have "label" field at top level
     */
    private Map<String, Object> convertConfirmationAction(Map<String, Object> actionData, String defaultLabel) {
        Map<String, Object> action = new LinkedHashMap<>();
        
        // MUST have label at top level
        String label = convertColorCodes((String) actionData.getOrDefault("label", defaultLabel));
        Map<String, Object> labelObj = new LinkedHashMap<>();
        labelObj.put("text", label);
        action.put("label", labelObj);
        
        // Wiki: button has nested "action" with type and type-specific fields
        Map<String, Object> inner = new LinkedHashMap<>();
        if (actionData.containsKey("custom_id")) {
            String id = (String) actionData.get("custom_id");
            inner.put("type", "minecraft:dynamic/custom");
            inner.put("id", id != null && id.contains(":") ? id : "allium:" + id);
        } else if (actionData.containsKey("template")) {
            inner.put("type", "minecraft:dynamic/run_command");
            inner.put("template", convertColorCodes((String) actionData.get("template")));
        } else {
            String actionType = (String) actionData.getOrDefault("type", "close");
            inner.put("type", "minecraft:" + actionType);
            if (actionType.equals("run_command") && actionData.containsKey("command")) {
                inner.put("command", convertColorCodes((String) actionData.get("command")));
            } else if (actionType.equals("open_url") && actionData.containsKey("url")) {
                inner.put("url", actionData.get("url"));
            } else if (actionType.equals("show_dialog") && actionData.containsKey("dialog")) {
                String dialog = actionData.get("dialog").toString();
                inner.put("dialog", dialog.contains(":") ? dialog : "allium:" + dialog);
            }
        }
        action.put("action", inner);
        return action;
    }

    /**
     * Convert action for multi_action dialogs - MUST have "label" field at top level
     */
    private Map<String, Object> convertMultiAction(Map<String, Object> actionData) {
        Map<String, Object> action = new LinkedHashMap<>();
        
        // MUST have label at top level (not inside button)
        String label = convertColorCodes((String) actionData.getOrDefault("label", "OK"));
        Map<String, Object> labelObj = new LinkedHashMap<>();
        labelObj.put("text", label);
        action.put("label", labelObj);
        
        // Check for template (dynamic commands)
        if (actionData.containsKey("template")) {
            action.put("type", "minecraft:dynamic/run_command");
            String template = convertColorCodes((String) actionData.get("template"));
            action.put("template", template);
        } else {
            // Action type and content
            Object actionObj = actionData.get("action");
            if (actionObj instanceof String) {
                String actionType = (String) actionObj;
                if (actionType.equals("run_command") && actionData.containsKey("command")) {
                    action.put("type", "minecraft:run_command");
                    String cmd = convertColorCodes((String) actionData.get("command"));
                    action.put("command", cmd);
                } else if (actionType.equals("open_url") && actionData.containsKey("url")) {
                    action.put("type", "minecraft:open_url");
                    action.put("url", actionData.get("url"));
                } else if (actionType.equals("show_dialog") && actionData.containsKey("dialog")) {
                    action.put("type", "minecraft:show_dialog");
                    String dialog = actionData.get("dialog").toString();
                    if (!dialog.contains(":")) {
                        dialog = "allium:" + dialog;
                    }
                    action.put("dialog", dialog);
                }
            }
        }
        
        return action;
    }

    private String convertColorCodes(String text) {
        if (text == null) return "";
        return text.replace("&", "§").replace("{player_name}", "%player_name%");
    }

    private List<Map<String, Object>> toJsonText(String text) {
        Map<String, Object> textObj = new LinkedHashMap<>();
        textObj.put("text", text);
        return Collections.singletonList(textObj);
    }

    /**
     * Build inline dialog JSON from a programmatic map (no YAML/datapack).
     * Use for runtime-built dialogs e.g. nickname editor with current value as initial.
     */
    public String getInlineJsonFromData(Map<String, Object> dialogData, String dialogName) {
        return convertToDialogJson(dialogData, dialogName, false);
    }

    /**
     * Same as getInlineJsonFromData but runs PlaceholderAPI replacement for the player.
     */
    public String getInlineJsonFromData(Map<String, Object> dialogData, String dialogName, Player player) {
        String json = convertToDialogJson(dialogData, dialogName, false);
        if (player != null && player.isOnline()) {
            json = parsePlaceholderApi(json, player);
        }
        return json;
    }

    public void showDialog(Player player, String dialogName) {
        if (!dialogs.containsKey(dialogName)) {
            player.sendMessage("§cDialog not found: " + dialogName);
            return;
        }
        
        // Use Minecraft's dialog command
        String command = "dialog " + player.getName() + " allium:" + dialogName;
        plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command);
    }

    public void showDialog(CommandSender sender, String dialogName) {
        if (sender instanceof Player player) {
            showDialog(player, dialogName);
        } else {
            sender.sendMessage("§cThis command can only be used by players");
        }
    }

    public Map<String, Map<String, Object>> getDialogs() {
        return dialogs;
    }

    public boolean hasDialog(String name) {
        return dialogs.containsKey(name);
    }

    public String getDataPackPath() {
        return plugin.getDataFolder().getPath() + "/datapacks/" + dataPackName;
    }

    /**
     * Get dialog as inline JSON for instant display (no datapack needed)
     * Also parses PlaceholderAPI placeholders for the target player
     */
    public String getDialogInlineJson(String dialogName, String playerName) {
        Map<String, Object> dialogData = dialogs.get(dialogName);
        if (dialogData == null) {
            return null;
        }

        Player player = Bukkit.getPlayerExact(playerName);
        String json = convertToDialogJson(dialogData, dialogName, false);

        // Parse PlaceholderAPI if available and player is online
        if (player != null && player.isOnline()) {
            json = parsePlaceholderApi(json, player);
        }

        return json;
    }

    /**
     * Parse PlaceholderAPI placeholders in JSON string
     */
    private String parsePlaceholderApi(String json, Player player) {
        try {
            // Check if PlaceholderAPI is available
            me.clip.placeholderapi.PlaceholderAPIPlugin papi = me.clip.placeholderapi.PlaceholderAPIPlugin.getInstance();
            if (papi != null) {
                // Replace common placeholders
                json = json.replace("%player_name%", player.getName());
                json = json.replace("%player%", player.getName());
                json = json.replace("{player_name}", player.getName());

                // Use PlaceholderAPI to parse other placeholders
                json = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, json);
            }
        } catch (Exception e) {
            // PlaceholderAPI not available, continue with unparsed placeholders
        }
        return json;
    }
}

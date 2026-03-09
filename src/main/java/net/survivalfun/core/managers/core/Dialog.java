package net.survivalfun.core.managers.core;

import net.survivalfun.core.PluginStart;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared helper for showing text-edit dialogs (nickname, lore, rename).
 * Dialogs submit via the same command with a --apply tag (e.g. nick --apply $(nickname))
 * so no separate apply commands need to be exposed to players.
 */
public final class Dialog {

    private Dialog() {}

    /**
     * Build a notice-type dialog with one text input and a button that runs a
     * dynamic command template (e.g. nick --apply $(nickname)). Use $(key) for input substitution.
     */
    public static Map<String, Object> buildNoticeWithTextInput(
        String title,
        String bodyContent,
        String inputKey,
        String inputLabel,
        String initialValue,
        int maxLength,
        String actionLabel,
        String commandTemplate
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "notice");
        data.put("title", title);
        data.put("can_close_with_escape", true);
        data.put("pause", true);

        List<Map<String, Object>> body = new ArrayList<>();
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "plain_message");
        msg.put("content", bodyContent);
        data.put("body", body);
        body.add(msg);

        List<Map<String, Object>> inputs = new ArrayList<>();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("type", "text");
        input.put("key", inputKey);
        input.put("label", inputLabel);
        input.put("initial", initialValue != null ? initialValue : "");
        input.put("max_length", maxLength);
        inputs.add(input);
        data.put("inputs", inputs);

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("label", actionLabel);
        action.put("template", commandTemplate);
        data.put("action", action);

        return data;
    }

    /**
     * Show a text-edit dialog. Uses dynamic/custom so Apply sends payload to server; we handle it in DialogApplyListener.
     */
    public static void showTextInput(PluginStart plugin, Player player, String title, String bodyContent,
                                    String inputKey, String inputLabel, String initialValue, int maxLength,
                                    String actionLabel, String customId) {
        if (player == null || !player.isOnline()) return;
        Map<String, Object> dialogData = buildNoticeWithTextInput(
            title, bodyContent, inputKey, inputLabel,
            initialValue != null ? initialValue : "", maxLength, actionLabel, "");
        @SuppressWarnings("unchecked")
        Map<String, Object> action = (Map<String, Object>) dialogData.get("action");
        if (action != null && customId != null) {
            action.remove("template");
            action.put("custom_id", customId);
        }
        DialogManager dm = new DialogManager(plugin);
        String json = dm.getInlineJsonFromData(dialogData, "custom_edit", player);
        if (json == null || json.isEmpty()) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "dialog show " + player.getName() + " " + json);
    }

    /**
     * Build a notice dialog with multiple text inputs (e.g. one per lore line).
     * Each input has key "keyPrefix0", "keyPrefix1", ... and the template should reference $(keyPrefix0) | $(keyPrefix1) ...
     */
    public static Map<String, Object> buildNoticeWithMultipleTextInputs(
        String title,
        String bodyContent,
        String keyPrefix,
        String inputLabelPrefix,
        List<String> initialValues,
        int maxLengthPerInput,
        String actionLabel,
        String commandTemplate
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "notice");
        data.put("title", title);
        data.put("can_close_with_escape", true);
        data.put("pause", true);

        List<Map<String, Object>> body = new ArrayList<>();
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "plain_message");
        msg.put("content", bodyContent);
        data.put("body", body);
        body.add(msg);

        List<Map<String, Object>> inputs = new ArrayList<>();
        int count = initialValues != null && !initialValues.isEmpty() ? initialValues.size() : 1;
        for (int i = 0; i < count; i++) {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("type", "text");
            input.put("key", keyPrefix + i);
            input.put("label", inputLabelPrefix + (i + 1) + ":");
            input.put("initial", (initialValues != null && i < initialValues.size()) ? initialValues.get(i) : "");
            input.put("max_length", maxLengthPerInput);
            inputs.add(input);
        }
        data.put("inputs", inputs);

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("label", actionLabel);
        action.put("template", commandTemplate);
        data.put("action", action);

        return data;
    }

    /**
     * Build a confirmation-type dialog with multiple text inputs (e.g. lore lines) and two buttons:
     * "yes" = primary action (e.g. Apply), "no" = secondary (e.g. Add line).
     */
    public static Map<String, Object> buildConfirmationWithMultipleTextInputs(
        String title,
        String bodyContent,
        String keyPrefix,
        String inputLabelPrefix,
        List<String> initialValues,
        int maxLengthPerInput,
        String yesLabel,
        String yesCustomId,
        String noLabel,
        String noCustomId
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "confirmation");
        data.put("title", title);
        data.put("can_close_with_escape", true);
        data.put("pause", true);

        List<Map<String, Object>> body = new ArrayList<>();
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "plain_message");
        msg.put("content", bodyContent);
        data.put("body", body);
        body.add(msg);

        List<Map<String, Object>> inputs = new ArrayList<>();
        int count = (initialValues != null && !initialValues.isEmpty()) ? initialValues.size() : 1;
        for (int i = 0; i < count; i++) {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("type", "text");
            input.put("key", keyPrefix + i);
            input.put("label", inputLabelPrefix + (i + 1) + ":");
            input.put("initial", (initialValues != null && i < initialValues.size()) ? initialValues.get(i) : "");
            input.put("max_length", maxLengthPerInput);
            inputs.add(input);
        }
        data.put("inputs", inputs);

        Map<String, Object> yesAction = new LinkedHashMap<>();
        yesAction.put("label", yesLabel);
        yesAction.put("custom_id", yesCustomId);
        data.put("yes", yesAction);

        Map<String, Object> noAction = new LinkedHashMap<>();
        noAction.put("label", noLabel);
        noAction.put("custom_id", noCustomId);
        data.put("no", noAction);

        return data;
    }

    /**
     * Show lore edit dialog: one text box per lore line (or one empty box if no lore),
     * with "Apply" and "Add line" buttons. Add line re-opens the dialog with current values + one empty line.
     */
    public static void showLoreInput(PluginStart plugin, Player player, List<String> initialLines) {
        if (player == null || !player.isOnline()) return;
        int n = (initialLines != null && !initialLines.isEmpty()) ? initialLines.size() : 1;
        Map<String, Object> dialogData = buildConfirmationWithMultipleTextInputs(
            "Edit Lore",
            "One line per box. Supports color codes (&a, &b, etc.). Use \"Add line\" to add another.",
            "lore", "Line ", initialLines, 256,
            "Apply", "lore_apply",
            "Add line", "lore_add_line");
        DialogManager dm = new DialogManager(plugin);
        String json = dm.getInlineJsonFromData(dialogData, "lore_edit", player);
        if (json == null || json.isEmpty()) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "dialog show " + player.getName() + " " + json);
    }
}

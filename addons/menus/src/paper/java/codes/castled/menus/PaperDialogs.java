package codes.castled.menus;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.inventory.ItemStack;
import java.time.Duration;
import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

@SuppressWarnings("UnstableApiUsage")
public final class PaperDialogs implements DialogRenderer {
    private static final int FIRST_DIALOG_PROTOCOL = 771;

    @Override public boolean supports(Player player) {
        try {
            var plugins = Bukkit.getPluginManager();
            var via = plugins.getPlugin("ViaVersion");
            if (via != null && via.isEnabled()) {
                Class<?> entry = Class.forName("com.viaversion.viaversion.api.Via", true, via.getClass().getClassLoader());
                Object api = entry.getMethod("getAPI").invoke(null);
                Class<?> type = entry.getMethod("getAPI").getReturnType();
                return (int) type.getMethod("getPlayerVersion", UUID.class).invoke(api, player.getUniqueId()) >= FIRST_DIALOG_PROTOCOL;
            }
            if (Bukkit.spigot().getConfig().getBoolean("settings.bungeecord")) return false;
            return Bukkit.getUnsafe().getProtocolVersion() >= FIRST_DIALOG_PROTOCOL;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    @Override public void show(Player player, String title, List<Button> buttons) {
        var legacy = LegacyComponentSerializer.legacySection();
        List<ActionButton> actions = buttons.stream().map(button -> ActionButton.builder(legacy.deserialize(button.label()))
                .action(DialogAction.staticAction(ClickEvent.runCommand(button.command()))).build()).toList();
        player.showDialog(Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(legacy.deserialize(title)).pause(false).build())
                .type(DialogType.multiAction(actions).columns(2).build())));
    }
    @Override public void form(Player player, String title, String field, ItemStack preview, List<String> values,
                               int maxFields, int maxLength, BiConsumer<List<String>, Boolean> submit) {
        var inputs = IntStream.range(0, values.size()).mapToObj(index -> DialogInput.text("line_" + index,
                Component.text(field + (maxFields > 1 ? " " + (index + 1) : "")))
                .width(280).initial(values.get(index)).maxLength(maxLength).build()).toList();
        var base = DialogBase.builder(Component.text(title)).pause(false).inputs(inputs);
        if (preview != null) base.body(List.of(DialogBody.item(preview).build()));
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(formButton(player, "Save", values.size(), false, submit));
        if (values.size() < maxFields) buttons.add(formButton(player, "Add Line", values.size(), true, submit));
        player.showDialog(Dialog.create(builder -> builder.empty().base(base.build())
                .type(DialogType.multiAction(buttons).columns(2).build())));
    }

    private static ActionButton formButton(Player player, String label, int fields, boolean add,
                                           BiConsumer<List<String>, Boolean> submit) {
        var options = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build();
        return ActionButton.builder(Component.text(label)).action(DialogAction.customClick((response, audience) -> {
            var viewer = audience instanceof Player other ? other
                    : audience instanceof PlayerGameConnection connection ? connection.getPlayer() : null;
            if (viewer != player) return;
            List<String> values = new ArrayList<>(fields);
            for (int index = 0; index < fields; index++) values.add(response.getText("line_" + index));
            submit.accept(values, add);
        }, options)).build();
    }

}

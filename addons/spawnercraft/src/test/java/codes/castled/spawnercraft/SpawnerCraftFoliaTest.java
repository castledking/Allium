package codes.castled.spawnercraft;

import codes.castled.allium.api.AddonCommands;
import codes.castled.allium.api.AddonConnection;
import codes.castled.allium.api.AddonItems;
import codes.castled.allium.api.AddonText;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpawnerCraftFoliaTest {

    @Test
    void commandRecipeDispatchesToGlobalSchedulerOnFolia() {
        List<Runnable> globalTasks = new ArrayList<>();
        TaskScheduler scheduler = new TaskScheduler() {
            @Override public boolean isServerThread() { return false; }
            @Override public TaskHandle runOnServer(Runnable task) { globalTasks.add(task); return TaskHandle.NONE; }
            @Override public TaskHandle runOnServerLater(Runnable task, long delayMillis) { globalTasks.add(task); return TaskHandle.NONE; }
            @Override public TaskHandle repeatOnServer(BooleanSupplier step, long delayMillis, long periodMillis) { return TaskHandle.NONE; }
            @Override public TaskHandle runAsyncLater(Runnable task, long delayMillis) { return TaskHandle.NONE; }
            @Override public ThreadPoolExecutor boundedPool(String name, int threads, int queueCapacity) {
                throw new UnsupportedOperationException();
            }
        };
        AddonConnection folia = new AddonConnection() {
            @Override public Plugin plugin() { return null; }
            @Override public AddonItems items() { return null; }
            @Override public AddonCommands commands() { return null; }
            @Override public TaskScheduler scheduler() { return scheduler; }
            @Override public AddonText text() { return null; }
            @Override public boolean isFolia() { return true; }
        };

        HeadRecipes recipes = new HeadRecipes(null, folia, () -> null);
        HeadRecipes.Recipe cmdRecipe = new HeadRecipes.Recipe(
                "plushie", "Plushie", "", "HHH......", Map.of(),
                "give {player} diamond 1", "", false, false, Material.PAPER, Map.of());
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("Alex");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        // Must schedule on global tick thread rather than calling Bukkit.dispatchCommand inline.
        var result = recipes.deliver(player, new HeadRecipes.Match(cmdRecipe, "zombie"));
        assertTrue(result.succeeded());
        assertEquals(HeadRecipes.DeliveryStatus.COMMAND_SCHEDULED, result.status());
        assertEquals(1, globalTasks.size());
    }
}

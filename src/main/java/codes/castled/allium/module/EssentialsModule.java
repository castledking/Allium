package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.FeedCommand;
import codes.castled.allium.command.essentials.FlyCommand;
import codes.castled.allium.command.essentials.GamemodeCommand;
import codes.castled.allium.command.essentials.GodCommand;
import codes.castled.allium.command.essentials.HealCommand;
import codes.castled.allium.core.AbstractModule;

public final class EssentialsModule extends AbstractModule {

    public EssentialsModule(PluginStart plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "essentials";
    }

    @Override
    public void onEnable() {
        plugin.getCommandManager().register("heal", new HealCommand(plugin));
        plugin.getCommandManager().register("feed", new FeedCommand(plugin));
        plugin.getCommandManager().register("fly", new FlyCommand(plugin));
        plugin.getCommandManager().register("god", new GodCommand(plugin));
        plugin.getCommandManager().register("gamemode", new GamemodeCommand(plugin));
    }

    @Override
    public void onDisable() {
    }
}

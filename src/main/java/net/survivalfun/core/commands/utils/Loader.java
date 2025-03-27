package net.survivalfun.core.commands.utils;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;

import java.lang.reflect.Field;
import java.util.Map;

public class Loader {

    // Reflectively access the command map
    private static CommandMap getCommandMap() {
        try {
            Field commandMapField = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            commandMapField.setAccessible(true);
            return (CommandMap) commandMapField.get(Bukkit.getServer());
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve command map", e);
        }
    }

    /**
     * Registers a command dynamically.
     *
     * @param command The command to register.
     */
    public static void registerCommand(Command command) {
        CommandMap commandMap = getCommandMap();
        commandMap.register(command.getLabel(), command);
    }

    /**
     * Unregisters a command dynamically.
     *
     * @param commandLabel The name of the command to unregister.
     */
    public static void unregisterCommand(String commandLabel) {
        try {
            // Access the knownCommands map in the SimpleCommandMap class
            Field commandMapField = getCommandMap().getClass().getDeclaredField("knownCommands");
            commandMapField.setAccessible(true);

            @SuppressWarnings("unchecked")
            Map<String, Command> knownCommands = (Map<String, Command>) commandMapField.get(getCommandMap());

            // Remove the command by label
            if (knownCommands.containsKey(commandLabel.toLowerCase())) {
                knownCommands.remove(commandLabel.toLowerCase());
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to unregister command: " + commandLabel, e);
        }
    }

    /**
     * Check if a command is already registered.
     *
     * @param commandLabel The command name to check.
     * @return True if the command is registered; false otherwise.
     */
    public static boolean isCommandRegistered(String commandLabel) {
        try {
            Field commandMapField = getCommandMap().getClass().getDeclaredField("knownCommands");
            commandMapField.setAccessible(true);

            @SuppressWarnings("unchecked")
            Map<String, Command> knownCommands = (Map<String, Command>) commandMapField.get(getCommandMap());

            return knownCommands.containsKey(commandLabel.toLowerCase());
        } catch (Exception e) {
            throw new RuntimeException("Failed to check if command is registered: " + commandLabel, e);
        }
    }
}
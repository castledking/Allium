package codes.castled.allium.command.spec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandUsagesTest {

    private static List<String> syntaxes(List<CommandUsage> usages) {
        return usages.stream().map(CommandUsage::syntax).toList();
    }

    @Test
    void tokensKeepBracketedArgumentsWholeAndFindTheLiteralPrefix() {
        assertEquals(List.of("adopt-window", "<1s-1h, e.g. 10m>|off"), CommandUsage.tokens("adopt-window <1s-1h, e.g. 10m>|off"));
        assertEquals(List.of("hide", "group"), CommandUsage.of("hide group <name> create <whitelist|blacklist>", "").literalPrefix());
        assertEquals(List.of(), CommandUsage.of("<player> off", "").literalPrefix());
        assertTrue(CommandUsage.isArgument("[player]"));
        assertFalse(CommandUsage.isArgument("off"));
    }

    @Test
    void aPlainReportedUsageShowsEveryFormAndASubcommandNarrowsToItsForms() {
        List<CommandUsage> all = CommandUsages.select(CoreCommandSpecs.ALLIUM, CoreCommandSpecs.ALLIUM.selfUsage());
        assertEquals(CommandUsages.of("allium").size(), all.size());

        List<CommandUsage> hide = CommandUsages.select(CoreCommandSpecs.ALLIUM, "/allium hide");
        assertEquals(12, hide.size());
        assertTrue(hide.stream().allMatch(u -> u.syntax().startsWith("hide ")));
        assertTrue(hide.stream().allMatch(u -> "allium.hide.admin".equals(u.permission())));

        List<CommandUsage> group = CommandUsages.select(CoreCommandSpecs.ALLIUM, "/allium hide group");
        assertEquals(6, group.size());
        assertTrue(group.stream().allMatch(u -> u.syntax().startsWith("hide group <name> ")));

        assertEquals(List.of("import lang [apply]", "import vouchers [apply]", "import legacy [apply <run>]"),
                syntaxes(CommandUsages.select(CoreCommandSpecs.ALLIUM, "/allium import <lang [apply]|vouchers [apply]|legacy [apply <run>]>")));
    }

    @Test
    void powertoolUsesTheSharedCardWithEveryFormAndNarrowsToTheMistypedSubcommand() {
        List<CommandUsage> all = CommandUsages.select(CoreCommandSpecs.POWERTOOL, CoreCommandSpecs.POWERTOOL.selfUsage());
        assertEquals(CommandUsages.of("powertool").size(), all.size());
        assertEquals(23, all.size());
        assertTrue(all.stream().noneMatch(u -> u.description().isBlank()));

        assertEquals(List.of("set <index> [flags...] <action> [args...]"),
                syntaxes(CommandUsages.select(CoreCommandSpecs.POWERTOOL, "/pt set <index> [flags...] <action> [args...]")));
        assertEquals(List.of("add [flags...] <action> [args...]"),
                syntaxes(CommandUsages.select(CoreCommandSpecs.POWERTOOL,
                        "/pt add [--t <trigger>] [--cd <dur>] [--delay <dur>] [--perm <node>] <action> [args...]")));
        assertEquals(List.of("enable <tool>"), syntaxes(CommandUsages.select(CoreCommandSpecs.POWERTOOL, "/pt enable <tool>")));
    }

    @Test
    void creativeManagerCardNarrowsToTheMistypedHolderForm() {
        assertEquals(13, CommandUsages.select(CoreCommandSpecs.CREATIVEMANAGER, CoreCommandSpecs.CREATIVEMANAGER.selfUsage()).size());
        assertEquals(List.of("player <player> add <flag>"),
                syntaxes(CommandUsages.select(CoreCommandSpecs.CREATIVEMANAGER, "/creativemanager player <player> add <flag>")));
        List<CommandUsage> group = CommandUsages.select(CoreCommandSpecs.CREATIVEMANAGER, "/creativemanager group");
        assertEquals(5, group.size());
        assertTrue(group.stream().allMatch(u -> u.syntax().startsWith("group ")));
        assertTrue(CommandUsages.of("creativemanager").stream().allMatch(u -> "allium.gamemode.creative.manage".equals(u.permission())));
        assertEquals(2, CommandUsages.select(CoreCommandSpecs.ALLIUM, "/allium creativemanager").size());
    }

    @Test
    void anExactReportedFormGetsOnlyItsLineSoTheConsoleSeesTheTargetForm() {
        assertEquals(List.of("<player>"), syntaxes(CommandUsages.select(CoreCommandSpecs.HEAL, CoreCommandSpecs.HEAL.consoleUsage())));
        assertEquals(List.of("<mode> <player>"),
                syntaxes(CommandUsages.select(CoreCommandSpecs.GAMEMODE, CoreCommandSpecs.GAMEMODE.consoleUsage())));
        assertEquals(List.of("", "<player>"), syntaxes(CommandUsages.select(CoreCommandSpecs.HEAL, CoreCommandSpecs.HEAL.selfUsage())));
    }

    @Test
    void familiesWithoutWrittenLinesFallBackToSubcommandsThenToTheReportedUsage() {
        List<CommandUsage> mail = CommandUsages.select(CoreCommandSpecs.MAIL, CoreCommandSpecs.MAIL.selfUsage());
        assertEquals(List.of("read", "send <player> <message>", "gift <player> [amount] [note]", "claim <id|all>", "delete <id>", "clear"),
                syntaxes(mail));
        assertEquals("allium.mail.send", mail.get(1).permission());
        assertEquals(List.of("gift <player> [amount] [note]"),
                syntaxes(CommandUsages.select(CoreCommandSpecs.MAIL, "/mail gift <player> [amount] [note]")));

        List<CommandUsage> hat = CommandUsages.select(CoreCommandSpecs.HAT, "/hat");
        assertEquals(List.of(""), syntaxes(hat));
        assertTrue(hat.get(0).description().isEmpty());

        assertEquals(List.of("<x> <y> <z> [yaw] [pitch] [world]"), syntaxes(CommandUsages.derive("/tppos <x> <y> <z> [yaw] [pitch] [world]")));
        assertEquals(List.of("<player> [target]", "<x> <y> <z>"), syntaxes(CommandUsages.derive("/tp <player> [target] | /tp <x> <y> <z>")));
    }

    @Test
    void theNickCardMatchesTheEssentialsLayoutWithOthersFormsBehindTheirNode() {
        List<CommandUsage> nick = CommandUsages.of("nick");
        assertEquals(List.of("<nickname>", "off", "<player> <nickname>", "<player> off"), syntaxes(nick));
        assertNull(nick.get(0).permission());
        assertEquals("allium.nick.others", nick.get(2).permission());
        assertEquals("Changes your nickname to the given text", nick.get(0).description());
    }

    @Test
    void writtenLinesOnlyNameRegisteredCommands() {
        java.util.Set<String> known = new java.util.HashSet<>();
        for (var field : CoreCommandSpecs.class.getFields()) {
            try {
                Object value = field.get(null);
                if (value instanceof CommandSpec spec) known.add(spec.canonicalName());
                if (value instanceof List<?> list) for (Object o : list) if (o instanceof CommandSpec spec) known.add(spec.canonicalName());
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        for (String command : List.of("heal", "gamemode", "allium", "nick", "home", "homes", "bed", "rename", "lore", "xp", "customtext")) {
            assertTrue(known.contains(command), command);
            assertFalse(CommandUsages.of(command).isEmpty(), command);
        }
        assertEquals("give", CommandUsages.commandOf("/give <player> <item>"));
        assertEquals("gm", CommandUsages.commandOf("/allium:gm <mode>"));
        assertNull(CommandUsages.commandOf("player"));
    }
}

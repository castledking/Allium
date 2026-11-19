package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.moderation.CommandSpyCommand;
import codes.castled.allium.command.moderation.EnderchestCommand;
import codes.castled.allium.command.moderation.InvseeCommand;
import codes.castled.allium.command.moderation.MuteCommand;
import codes.castled.allium.command.moderation.MutesCommand;
import codes.castled.allium.command.moderation.SpectateCommand;
import codes.castled.allium.command.moderation.SpyCommand;
import codes.castled.allium.command.moderation.UnmuteCommand;
import codes.castled.allium.command.moderation.VanishCommand;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.moderation.InspectionService;
import codes.castled.allium.moderation.ModerationRepository;
import codes.castled.allium.moderation.ModerationService;
import codes.castled.allium.moderation.DisciplineService;
import codes.castled.allium.moderation.StaffSpyService;
import codes.castled.allium.command.moderation.DisciplineCommand;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.storage.SqlDialect;

import java.sql.SQLException;
import java.util.concurrent.Executor;

/**
 * Moderation module: {@code /vanish}, {@code /mute}, {@code /unmute}, {@code /mutes},
 * discipline commands, and inventory/enderchest inspection ({@code /invsee}, {@code /enderchest}).
 */
public final class ModerationModule extends AbstractModule {

    private ModerationRepository repository;
    private ModerationService moderation;
    private DisciplineService discipline;
    private InspectionService inspection;
    private StaffSpyService spy;

    public ModerationModule(PluginStart plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "moderation";
    }

    @Override
    public void onEnable() {
        SqlDialect dialect = plugin.getDatabaseManager().dialect();
        repository = new ModerationRepository(
                plugin.getDatabaseManager().connectionSource(),
                dialect,
                System::currentTimeMillis
        );
        try {
            repository.migrate();
        } catch (SQLException e) {
            throw new IllegalStateException("moderation storage is unavailable: " + e.getMessage(), e);
        }

        int workers = dialect == SqlDialect.MYSQL ? 2 : 1;
        Executor storagePool = plugin.getScheduler().boundedPool("Allium-Moderation", workers, 512);
        moderation = new ModerationService(plugin, repository, storagePool, System::currentTimeMillis);
        try {
            moderation.enable();
        } catch (SQLException e) {
            throw new IllegalStateException("moderation state could not be loaded: " + e.getMessage(), e);
        }

        if (plugin.getModuleManager().getModule("messaging") instanceof MessagingModule messagingModule
                && messagingModule.messaging() != null) {
            messagingModule.messaging().setVanishSource(moderation);
            messagingModule.messaging().setMuteSource(moderation);
        }

        Executor disciplinePool = plugin.getScheduler().boundedPool("Allium-Discipline", 1, 512);
        discipline = new DisciplineService(plugin, repository, disciplinePool, System::currentTimeMillis);
        try {
            discipline.enable();
        } catch (Exception failure) {
            discipline.disable();
            throw new IllegalStateException("discipline enforcement could not initialize", failure);
        }
        moderation.setActionPolicy(discipline::authorizeAction);
        for (var spec : CoreCommandSpecs.DISCIPLINE_SPECS) {
            plugin.getCommandManager().register(new DisciplineCommand(plugin, discipline, spec));
        }

        Executor inspectionPool = plugin.getScheduler().boundedPool("Allium-Inspection", 1, 256);
        inspection = new InspectionService(plugin, repository, discipline, inspectionPool);
        inspection.enable();

        spy = new StaffSpyService(plugin);
        spy.enable();

        plugin.getCommandManager().register(new VanishCommand(plugin, moderation));
        plugin.getCommandManager().register(new MuteCommand(plugin, moderation));
        plugin.getCommandManager().register(new UnmuteCommand(plugin, moderation));
        plugin.getCommandManager().register(new MutesCommand(plugin, moderation));
        plugin.getCommandManager().register(new InvseeCommand(plugin, inspection));
        plugin.getCommandManager().register(new EnderchestCommand(plugin, inspection));
        plugin.getCommandManager().register(new CommandSpyCommand(plugin, spy));
        plugin.getCommandManager().register(new SpyCommand(plugin, spy));
        plugin.getCommandManager().register(new SpectateCommand(plugin, spy));
        plugin.logger().info("Moderation enabled: /vanish, /mute, /unmute, /mutes, /invsee, /enderchest, /commandspy, /spy, /spectate.");
    }

    @Override
    public void onDisable() {
        if (plugin.getModuleManager().getModule("messaging") instanceof MessagingModule messagingModule
                && messagingModule.messaging() != null) {
            messagingModule.messaging().setVanishSource(null);
            messagingModule.messaging().setMuteSource(null);
        }
        if (spy != null) {
            spy.disable();
        }
        if (inspection != null) {
            inspection.disable();
        }
        if (discipline != null) {
            discipline.disable();
        }
        if (moderation != null) {
            moderation.disable();
        }
    }

    public ModerationService moderation() {
        return moderation;
    }

    public DisciplineService discipline() {
        return discipline;
    }

    public InspectionService inspection() {
        return inspection;
    }

    public StaffSpyService spy() {
        return spy;
    }

    public ModerationRepository repository() {
        return repository;
    }
}

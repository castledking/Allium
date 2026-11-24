package codes.castled.allium.module;
import codes.castled.allium.PluginStart;
import codes.castled.allium.chat.*;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.spec.*;
import codes.castled.allium.core.AbstractModule;
public final class ChatModule extends AbstractModule {
    private ChatService service;
    public ChatModule(PluginStart plugin){super(plugin);}
    @Override public String getName(){return "chat";}
    public ChatService chat(){return service;}
    @Override public void onEnable(){
        var repository=new ChatRepository(plugin.getDatabaseManager().connectionSource(),plugin.getDatabaseManager().dialect());
        try{repository.migrate();}catch(java.sql.SQLException error){throw new IllegalStateException("Chat schema unavailable",error);}
        service=new ChatService(plugin,repository,plugin.getScheduler().boundedPool("Allium-Chat-Storage",1,256));service.enable();
        plugin.getCommandManager().register(new SpecCommandHandler() {
            public CommandSpec spec() { return CoreCommandSpecs.REALNAME; }
            public void execute(BukkitCommandContext context) { service.realName(context); }
        });
        plugin.getCommandManager().register(new SpecCommandHandler() {
            public CommandSpec spec() { return CoreCommandSpecs.CLEAR_CHAT; }
            public void execute(BukkitCommandContext context) { service.clearChat(context); }
        });
        for(var spec:CoreCommandSpecs.CHAT_SPECS)plugin.getCommandManager().register(new SpecCommandHandler(){public CommandSpec spec(){return spec;}public void execute(BukkitCommandContext context){service.dispatch(context);}});
    }
    @Override public void onDisable(){if(service!=null)service.close();}
    @Override public int reloadOrder(){return 85;}
    @Override public codes.castled.allium.config.ReloadCoordinator.Prepared prepareReload(org.bukkit.configuration.file.FileConfiguration candidateRoot) throws Exception {
        return service == null ? null : service.prepareReload(candidateRoot);
    }
}

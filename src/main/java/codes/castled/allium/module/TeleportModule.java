package codes.castled.allium.module;
import codes.castled.allium.PluginStart;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.command.teleport.TeleportCommand;
import codes.castled.allium.teleport.TeleportRepository;
import codes.castled.allium.teleport.TeleportService;
import java.sql.SQLException;
public final class TeleportModule extends AbstractModule {
    private TeleportService service;
    public TeleportModule(PluginStart plugin){super(plugin);}
    @Override public String getName(){return "teleport";}
    @Override public void onEnable(){
        TeleportRepository repository=new TeleportRepository(plugin.getDatabaseManager().connectionSource(),plugin.getDatabaseManager().dialect(),System::currentTimeMillis);
        try{repository.migrate();}catch(SQLException e){throw new IllegalStateException("teleport storage unavailable",e);}
        service=new TeleportService(plugin,repository,plugin.getScheduler().boundedPool("Allium-Teleport",1,512));service.enable();
        for(var spec:CoreCommandSpecs.TELEPORT_SPECS)plugin.getCommandManager().register(new TeleportCommand(plugin,service,spec));
    }
    @Override public void onDisable(){if(service!=null)service.disable();}
    public TeleportService teleports(){return service;}
}

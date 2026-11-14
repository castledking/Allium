package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.command.travel.TravelCommand;
import codes.castled.allium.travel.TravelRepository;
import codes.castled.allium.travel.TravelService;
import java.sql.SQLException;

public final class TravelModule extends AbstractModule {
    private TravelService service;
    public TravelModule(PluginStart plugin){super(plugin);}
    @Override public String getName(){return "travel";}
    @Override public void onEnable(){
        if(!(plugin.getModuleManager().getModule("teleport") instanceof TeleportModule teleport)||!teleport.isEnabled())
            throw new IllegalStateException("travel requires Allium's teleport module");
        TravelRepository repository=new TravelRepository(plugin.getDatabaseManager().connectionSource(),plugin.getDatabaseManager().dialect(),System::currentTimeMillis);
        try{repository.migrate();}catch(SQLException e){throw new IllegalStateException("travel storage unavailable",e);}
        service=new TravelService(plugin,repository,teleport.teleports(),plugin.getScheduler().boundedPool("Allium-Travel",1,512));service.enable();
        for(var spec:CoreCommandSpecs.TRAVEL_SPECS)plugin.getCommandManager().register(new TravelCommand(service,spec));
    }
    @Override public void onDisable(){if(service!=null)service.disable();}
    public TravelService travel(){return service;}
}

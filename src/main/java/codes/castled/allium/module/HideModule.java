package codes.castled.allium.module;
import codes.castled.allium.PluginStart;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.hide.HideService;
public final class HideModule extends AbstractModule {
    private HideService service;
    public HideModule(PluginStart plugin){super(plugin);}
    public String getName(){return "hide";}
    public HideService hide(){return service;}
    public void onEnable(){service=new HideService(plugin);service.enable();}
    public void onDisable(){if(service!=null)service.close();}
}

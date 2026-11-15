package codes.castled.allium.module;
import codes.castled.allium.PluginStart;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.sign.SignService;
public final class SignsModule extends AbstractModule {
    private SignService signs;
    public SignsModule(PluginStart plugin){super(plugin);}
    @Override public String getName(){return "signs";}
    @Override public void onEnable(){signs=new SignService(plugin);signs.enable();}
    public void reloadSettings(){var replacement=new SignService(plugin);if(signs!=null)signs.close();signs=replacement;signs.enable();}
    @Override public void onDisable(){if(signs!=null)signs.close();}
}

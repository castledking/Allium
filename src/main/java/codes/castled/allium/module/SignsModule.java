package codes.castled.allium.module;
import codes.castled.allium.PluginStart;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.sign.PaidSignService;
import codes.castled.allium.sign.SignService;
public final class SignsModule extends AbstractModule {
    private SignService signs;
    /** Survives /allium reload so an in-flight paid repair always settles. */
    private codes.castled.allium.sign.PaidRepairService paidRepairs;
    private PaidSignService paidSigns;
    public SignsModule(PluginStart plugin){super(plugin);}
    @Override public String getName(){return "signs";}
    @Override public void onEnable(){
        try{paidRepairs=new codes.castled.allium.sign.PaidRepairService(plugin);paidRepairs.enable();}
        catch(IllegalStateException unavailable){paidRepairs=null;plugin.logger().warning("Paid repair signs are unavailable: "+unavailable.getMessage());}
        paidSigns=new PaidSignService(plugin);
        signs=new SignService(plugin,paidRepairs,paidSigns);signs.enable();
    }
    public void reloadSettings(){var replacement=new SignService(plugin,paidRepairs,paidSigns);if(signs!=null)signs.close();signs=replacement;signs.enable();}
    @Override public void onDisable(){
        if(signs!=null)signs.close();
        if(paidSigns!=null){paidSigns.close();paidSigns=null;}
        if(paidRepairs!=null){paidRepairs.close();paidRepairs=null;}
    }
    public SignService signs(){return signs;}
    public codes.castled.allium.sign.PaidRepairService paidRepairs(){return paidRepairs;}
    public PaidSignService paidSigns(){return paidSigns;}
}

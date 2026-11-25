package codes.castled.allium.module;
import codes.castled.allium.PluginStart;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.sign.PaidSignService;
import codes.castled.allium.sign.SignService;
import codes.castled.allium.sign.TradeSignRepository;
import codes.castled.allium.sign.TradeSignService;
public final class SignsModule extends AbstractModule {
    private SignService signs;
    /** Survives /allium reload so an in-flight paid repair always settles. */
    private codes.castled.allium.sign.PaidRepairService paidRepairs;
    private PaidSignService paidSigns;
    private TradeSignService tradeSigns;
    public SignsModule(PluginStart plugin){super(plugin);}
    @Override public String getName(){return "signs";}
    @Override public void onEnable(){
        try{paidRepairs=new codes.castled.allium.sign.PaidRepairService(plugin);paidRepairs.enable();}
        catch(IllegalStateException unavailable){paidRepairs=null;plugin.logger().warning("Paid repair signs are unavailable: "+unavailable.getMessage());}
        paidSigns=new PaidSignService(plugin);
        tradeSigns=startTradeSigns();
        signs=new SignService(plugin,paidRepairs,paidSigns,tradeSigns);signs.enable();
    }
    private TradeSignService startTradeSigns() {
        if (plugin.getDatabaseManager() == null) return null;
        try {
            var repo = new TradeSignRepository(plugin.getDatabaseManager().connectionSource(),
                    plugin.getDatabaseManager().dialect(), System::currentTimeMillis);
            repo.migrate();
            return new TradeSignService(plugin, repo);
        } catch (java.sql.SQLException | RuntimeException unavailable) {
            plugin.logger().warning("Trade signs are unavailable: " + unavailable.getMessage());
            return null;
        }
    }
    public void reloadSettings(){var replacement=new SignService(plugin,paidRepairs,paidSigns,tradeSigns);if(signs!=null)signs.close();signs=replacement;signs.enable();}
    @Override public void onDisable(){
        if(signs!=null)signs.close();
        if(tradeSigns!=null){tradeSigns.close();tradeSigns=null;}
        if(paidSigns!=null){paidSigns.close();paidSigns=null;}
        if(paidRepairs!=null){paidRepairs.close();paidRepairs=null;}
    }
    public SignService signs(){return signs;}
    public codes.castled.allium.sign.PaidRepairService paidRepairs(){return paidRepairs;}
    public PaidSignService paidSigns(){return paidSigns;}
    public TradeSignService tradeSigns(){return tradeSigns;}
    @Override public int reloadOrder(){return 80;}
    @Override public codes.castled.allium.config.ReloadCoordinator.Prepared prepareReload(org.bukkit.configuration.file.FileConfiguration candidateRoot) {
        return codes.castled.allium.config.ReloadCoordinator.Prepared.of(this::reloadSettings);
    }
}

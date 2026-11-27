package codes.castled.allium.module;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.AbstractModule;
import codes.castled.allium.economy.EconomyRuntime;
import codes.castled.allium.economy.EconomyUnavailableException;
import codes.castled.allium.naming.PlayerNameRepository;
import java.sql.SQLException;

/** Enables the journaled economy after the configured database and recovery checks pass. */
public final class EconomyModule extends AbstractModule {
    private EconomyRuntime runtime;
    private AutoCloseable vaultRegistration;
    public EconomyModule(PluginStart plugin){super(plugin);}
    @Override public String getName(){return "economy";}
    public EconomyRuntime runtime(){return runtime;}
    @Override public int reloadOrder(){return 20;}
    @Override public codes.castled.allium.config.ReloadCoordinator.Prepared prepareReload(org.bukkit.configuration.file.FileConfiguration candidateRoot) throws Exception {
        return runtime == null ? null : runtime.prepareReload(candidateRoot);
    }
    @Override public void onEnable(){
        try {
            runtime=new EconomyRuntime(plugin);runtime.enable();
            for(var spec:codes.castled.allium.command.spec.CoreCommandSpecs.ECONOMY_SPECS)
                plugin.getCommandManager().register(new codes.castled.allium.economy.EconomyCommands(spec,runtime));
            if(!runtime.ready())throw new IllegalStateException("economy schema/recovery is not ready");
            plugin.logger().info("Journalled economy enabled on " + plugin.getDatabaseManager().getType() + ".");
            if(!plugin.getConfig().getBoolean("economy.vault-provider-enabled",true)){
                plugin.logger().info("Vault economy provider is off (economy.vault-provider-enabled=false).");
            }else if(plugin.getServer().getPluginManager().getPlugin("Vault")!=null){
                try{vaultRegistration=new VaultRegistration();}
                catch(LinkageError error){plugin.logger().warning("Vault API unavailable; economy commands remain enabled: "+error.getMessage());}
            }
        }catch(SQLException|RuntimeException error){if(runtime!=null)runtime.close();runtime=null;throw new IllegalStateException("economy unavailable",error);}
    }
    @Override public void onDisable(){
        if(vaultRegistration!=null){try{vaultRegistration.close();}catch(Exception error){plugin.logger().warning("Vault unregister failed: "+error.getMessage());}vaultRegistration=null;}
        if(runtime!=null){runtime.close();runtime=null;}
    }
    /** Loaded only after optional Vault presence and actual schema/recovery readiness. */
    private final class VaultRegistration implements AutoCloseable {
        private final codes.castled.allium.economy.VaultEconomyProvider provider;
        VaultRegistration(){
            var names=new PlayerNameRepository(plugin.getDatabaseManager().connectionSource(),plugin.getDatabaseManager().dialect(),System::currentTimeMillis);
            provider=new codes.castled.allium.economy.VaultEconomyProvider(runtime,name->{
                try{return names.byName(name).map(codes.castled.allium.naming.NameCache.Entry::uuid).orElse(null);}
                catch(SQLException error){throw new EconomyUnavailableException("authoritative name storage unavailable",error);}
            });
            plugin.getServer().getServicesManager().register(net.milkbowl.vault.economy.Economy.class,provider,PluginStart.bukkitPlugin(plugin),org.bukkit.plugin.ServicePriority.Highest);
            plugin.logger().info("Vault economy registered after schema and recovery checks.");
        }
        @Override public void close(){plugin.getServer().getServicesManager().unregister(net.milkbowl.vault.economy.Economy.class,provider);}
    }
}

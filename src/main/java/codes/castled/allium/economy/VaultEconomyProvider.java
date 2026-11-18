package codes.castled.allium.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import java.util.*;
import java.util.function.*;
import static codes.castled.allium.economy.EconomyResult.*;

/** Optional Vault linkage lives here, after the engine's schema/recovery readiness gate. */
public final class VaultEconomyProvider implements Economy {
    private final EconomyEngine engine;
    private java.util.function.Supplier<EconomyDisplay> display=()->EconomyDisplay.DEFAULT;
    private final Function<String,UUID> names;
    private final BooleanSupplier enabled;
    private final Function<UUID,Balance> balances;
    private final Function<EconomyRequest,Mutation> mutate;
    public VaultEconomyProvider(EconomyRuntime runtime,Function<String,UUID> names){
        this(runtime.engine(),names,runtime::ready,runtime::synchronousBalance,runtime::vaultMutation);
        display=runtime::display;
    }
    public VaultEconomyProvider(EconomyEngine engine,Function<String,UUID> names,BooleanSupplier enabled,
                                Function<UUID,Balance> balances,Function<EconomyRequest,Mutation> mutate){
        this.engine=engine;this.names=names;this.enabled=enabled;this.balances=balances;this.mutate=mutate;
    }
    private UUID resolve(String name){
        if(name==null||!name.matches("[A-Za-z0-9_]{1,16}"))return null;
        return names.apply(name); // Local authoritative name storage only; never OfflinePlayer(String).
    }
    private Balance read(UUID id){
        if(!enabled.getAsBoolean())throw new EconomyUnavailableException("economy provider is unavailable");
        Balance result=id==null?new Missing():balances.apply(id);
        if(result instanceof Unavailable error)throw new EconomyUnavailableException(error.diagnostic());
        return result;
    }
    private double balance(UUID id){Balance result=read(id);return result instanceof Found found?VaultMoney.egress(found.available()):0.0;}
    private boolean account(UUID id){return read(id) instanceof Found;}
    private boolean has(UUID id,double amount){
        Money requested;
        try{requested=VaultMoney.amount(amount);}catch(RuntimeException invalid){return false;}
        Balance result=read(id);return result instanceof Found found&&found.available().compareTo(requested)>=0;
    }
    private EconomyResponse change(UUID id,double raw,EconomyRequest.Kind kind){
        if(!enabled.getAsBoolean())return failure("UNAVAILABLE",null);
        if(id==null)return failure("MISSING_ACCOUNT",null);
        Money amount;
        try{amount=VaultMoney.amount(raw).positive();VaultMoney.egress(amount);}catch(RuntimeException invalid){return failure("INVALID_AMOUNT",null);}
        UUID operation=UUID.randomUUID();
        var request=engine.request(operation,"VAULT",operation.toString(),kind,"VAULT",id,null,amount);
        Mutation result=mutate.apply(request);
        if(result instanceof Committed done)return new EconomyResponse(VaultMoney.egress(amount),VaultMoney.egress(done.source().available()),EconomyResponse.ResponseType.SUCCESS,null);
        if(result instanceof Unknown unknown)return failure("OUTCOME_UNKNOWN:"+unknown.operationId(),null);
        if(result instanceof Rejected rejected)return failure(rejected.reason(),rejected.source());
        return failure("UNAVAILABLE",null);
    }
    private EconomyResponse change(String name,double raw,EconomyRequest.Kind kind){
        try{return change(resolve(name),raw,kind);}catch(EconomyUnavailableException error){return failure("UNAVAILABLE",null);}
    }
    private boolean open(UUID id){
        if(id==null||!enabled.getAsBoolean())return false;
        UUID operation=UUID.nameUUIDFromBytes(("Allium-OPEN:"+id).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        Mutation result=mutate.apply(engine.request(operation,"OPEN",id.toString(),EconomyRequest.Kind.OPEN,"SYSTEM",id,null,engine.settings().starting()));
        if(result instanceof Unknown unknown)throw new EconomyUnavailableException("OUTCOME_UNKNOWN:"+unknown.operationId());
        if(result instanceof Unavailable unavailable)throw new EconomyUnavailableException(unavailable.diagnostic());
        return result instanceof Committed;
    }
    private static EconomyResponse failure(String reason,Found balance){return new EconomyResponse(0,balance==null?0:VaultMoney.egress(balance.available()),EconomyResponse.ResponseType.FAILURE,reason);}
    private static UUID id(OfflinePlayer player){return player==null?null:player.getUniqueId();}
    @Override public boolean isEnabled(){return enabled.getAsBoolean();}
    @Override public String getName(){return "Allium";}
    @Override public boolean hasBankSupport(){return false;}
    @Override public int fractionalDigits(){return 2;}
    @Override public String format(double value){
        if(!Double.isFinite(value))throw new IllegalArgumentException("finite value required");
        return display.get().format(java.math.BigDecimal.valueOf(value).setScale(2,java.math.RoundingMode.HALF_EVEN));
    }
    @Override public String currencyNamePlural(){return "dollars";}
    @Override public String currencyNameSingular(){return "dollar";}
    @Override public boolean hasAccount(OfflinePlayer p){return account(id(p));}
    @Override public boolean hasAccount(String p){return account(resolve(p));}
    @Override public boolean hasAccount(OfflinePlayer p,String w){return hasAccount(p);}
    @Override public boolean hasAccount(String p,String w){return hasAccount(p);}
    @Override public double getBalance(OfflinePlayer p){return balance(id(p));}
    @Override public double getBalance(String p){return balance(resolve(p));}
    @Override public double getBalance(OfflinePlayer p,String w){return getBalance(p);}
    @Override public double getBalance(String p,String w){return getBalance(p);}
    @Override public boolean has(OfflinePlayer p,double amount){return has(id(p),amount);}
    @Override public boolean has(String p,double amount){return has(resolve(p),amount);}
    @Override public boolean has(OfflinePlayer p,String w,double amount){return has(p,amount);}
    @Override public boolean has(String p,String w,double amount){return has(p,amount);}
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p,double amount){return change(id(p),amount,EconomyRequest.Kind.WITHDRAW);}
    @Override public EconomyResponse withdrawPlayer(String p,double amount){return change(p,amount,EconomyRequest.Kind.WITHDRAW);}
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p,String w,double amount){return withdrawPlayer(p,amount);}
    @Override public EconomyResponse withdrawPlayer(String p,String w,double amount){return withdrawPlayer(p,amount);}
    @Override public EconomyResponse depositPlayer(OfflinePlayer p,double amount){return change(id(p),amount,EconomyRequest.Kind.DEPOSIT);}
    @Override public EconomyResponse depositPlayer(String p,double amount){return change(p,amount,EconomyRequest.Kind.DEPOSIT);}
    @Override public EconomyResponse depositPlayer(OfflinePlayer p,String w,double amount){return depositPlayer(p,amount);}
    @Override public EconomyResponse depositPlayer(String p,String w,double amount){return depositPlayer(p,amount);}
    @Override public boolean createPlayerAccount(OfflinePlayer p){return open(id(p));}
    @Override public boolean createPlayerAccount(String p){return open(resolve(p));}
    @Override public boolean createPlayerAccount(OfflinePlayer p,String w){return createPlayerAccount(p);}
    @Override public boolean createPlayerAccount(String p,String w){return createPlayerAccount(p);}
    private EconomyResponse unsupported(){return new EconomyResponse(0,0,EconomyResponse.ResponseType.NOT_IMPLEMENTED,"Banks are not supported");}
    @Override public EconomyResponse createBank(String n,String p){return unsupported();}
    @Override public EconomyResponse createBank(String n,OfflinePlayer p){return unsupported();}
    @Override public EconomyResponse deleteBank(String n){return unsupported();}
    @Override public EconomyResponse bankBalance(String n){return unsupported();}
    @Override public EconomyResponse bankHas(String n,double a){return unsupported();}
    @Override public EconomyResponse bankWithdraw(String n,double a){return unsupported();}
    @Override public EconomyResponse bankDeposit(String n,double a){return unsupported();}
    @Override public EconomyResponse isBankOwner(String n,String p){return unsupported();}
    @Override public EconomyResponse isBankOwner(String n,OfflinePlayer p){return unsupported();}
    @Override public EconomyResponse isBankMember(String n,String p){return unsupported();}
    @Override public EconomyResponse isBankMember(String n,OfflinePlayer p){return unsupported();}
    @Override public List<String> getBanks(){return List.of();}
}

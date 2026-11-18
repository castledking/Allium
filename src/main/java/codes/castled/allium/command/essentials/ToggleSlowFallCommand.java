package codes.castled.allium.command.essentials;
import codes.castled.allium.command.*;
import codes.castled.allium.command.spec.*;
import codes.castled.allium.player.FlightRestoration;
import org.bukkit.entity.Player;
public final class ToggleSlowFallCommand implements SpecCommandHandler {
 private final FlightRestoration flights;
 public ToggleSlowFallCommand(FlightRestoration flights){this.flights=flights;}
 public CommandSpec spec(){return CoreCommandSpecs.TOGGLE_SLOW_FALL;}
 public void execute(BukkitCommandContext c){if(!(c.sender() instanceof Player p)){c.sendInvalidArgs("Players only");return;}if(c.args().length>1){c.sendInvalidArgs(spec().selfUsage());return;}try{flights.toggle(p,c.args().length==0?"":c.args()[0]);}catch(IllegalArgumentException invalid){c.sendInvalidArgs(spec().selfUsage());}}
}

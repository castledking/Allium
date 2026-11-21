package codes.castled.allium.config;

import codes.castled.allium.message.LanguageEntry;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.*;

/** Upgrade only exact former stock text so custom text, sounds and entry shapes survive. */
final class LanguageColors {
    private LanguageColors() {}

    private record Replacement(String key,String before,String after) {}

    private static final List<Replacement> STOCK = List.of(
            color("moderation.discipline.ban-denied","You are banned: {message}"),
            color("moderation.discipline.login-unavailable","Moderation data is unavailable. Please try again shortly."),
            color("moderation.discipline.kick-notice","You were kicked: {message}"),
            color("info.afk.kick","You were kicked for being AFK."),
            new Replacement("item.give.delivered","{prefix} &aGave &f{amount}x {item} &ato &f{player}&a.","{prefix} &aGave &f{amount} {item} &ato &f{player}&a."),
            new Replacement("item.give.degraded","{prefix} &eGave &f{amount}x {item} &eto &f{player}&e, but a head was given without its skin.","{prefix} &eGave &f{amount} {item} &eto &f{player}&e, but a head was given without its skin."),
            new Replacement("item.give.received","{prefix} &aYou received &f{amount}x {item}&a.","{prefix} &aYou received &f{amount} {item}&a."),
            new Replacement("item.give.delivered","{prefix} &aEntregado &f{amount}x {item} &aa &f{player}&a.","{prefix} &aEntregado &f{amount} {item} &aa &f{player}&a."),
            new Replacement("item.give.degraded","{prefix} &eEntregado &f{amount}x {item} &ea &f{player}&e, pero la cabeza se entrego sin su skin.","{prefix} &eEntregado &f{amount} {item} &ea &f{player}&e, pero la cabeza se entrego sin su skin."),
            new Replacement("item.give.received","{prefix} &aRecibiste &f{amount}x {item}&a.","{prefix} &aRecibiste &f{amount} {item}&a."),
            // Ignoring someone now hides their chat too, not only their private messages.
            new Replacement("messaging.ignore.added","{prefix} &aYou are now ignoring &f{player}&a's private messages.","{prefix} &aYou are now ignoring &f{player}&a."),
            new Replacement("messaging.ignore.added","{prefix} &aAhora ignoras los mensajes privados de &f{player}&a.","{prefix} &aAhora ignoras a &f{player}&a."),
            // The teleport warmup moved to the action bar, where it counts down.
            new Replacement("teleport.warmup","{prefix} &eTeleporting in {time}s. Stay still and avoid damage.","[actionbar] &eTeleporting in &f{warmup}s&e. Stay still and avoid damage."),
            new Replacement("teleport.warmup","{prefix} &eTeletransportando en {time}s. Quedate quieto y evita dano.","[actionbar] &eTeletransportando en &f{warmup}s&e. Quedate quieto y evita dano."),
            // Payment receipts no longer show the operation id or the tax account.
            new Replacement("economy.payment-sent","{prefix} &aPaid &f{amount} &ato &f{player}&a. &7Operation {operation}","{prefix} &aPaid &f{amount} &ato &f{player}&a."),
            new Replacement("economy.payment-sent-taxed","{prefix} &aPaid &f{net} &ato &f{player} &7({tax} tax to {account})&a. &7Operation {operation}","{prefix} &aPaid &f{net} &ato &f{player} &7({tax} tax)&a."),
            new Replacement("economy.payment-sent-taxed","{prefix} &aPagaste &f{net} &aa &f{player} &7({tax} impuesto a {account})&a. &7Operacion {operation}","{prefix} &aPagaste &f{net} &aa &f{player} &7({tax} impuesto)&a."),
            new Replacement("economy.cheque-created-taxed","{prefix} &aCreated a cheque for &e{amount} &7({tax} tax to {account})&a.","{prefix} &aCreated a cheque for &e{amount} &7({tax} tax)&a."),
            new Replacement("economy.cheque-created-taxed","{prefix} &aCheque creado por &e{amount} &7({tax} impuesto a {account})&a.","{prefix} &aCheque creado por &e{amount} &7({tax} impuesto)&a."),
            new Replacement("economy.operation-refused","{prefix} &cEconomy refused: &f{reason}","{prefix} &cThat economy transaction could not be completed (&f{reason}&c)."),
            // tfly-on's first text described the while-flying count, now tfly-on-flying.
            new Replacement("player.tfly-on","{prefix} &aTimed flight on: &f{time} &aleft. It only runs while you fly.","{prefix} &aTimed flight on: &f{time} &aleft."),
            new Replacement("player.tfly-on","{prefix} &aVuelo temporal activado: quedan &f{time}&a. Solo corre mientras vuelas.","{prefix} &aVuelo temporal activado: quedan &f{time}&a."),
            new Replacement("utility.repair-held","{prefix} &aRepaired the held item.","{prefix} &aRepaired &f{items}&a."),
            new Replacement("utility.repair-all","{prefix} &aRepaired &f{count} &adamaged item(s).","{prefix} &aRepaired &f{items}&a."),
            new Replacement("utility.repair-all","{prefix} &aReparado(s) &f{count} &aobjeto(s) danado(s).","{prefix} &aReparaste &f{items}&a."),
            // The text-file reader names the file that failed (info.txt, motd.txt, rules.txt or custom.txt).
            new Replacement("customtext.failed","{prefix} &cThe custom text could not be read. Check the console.","{prefix} &f{file} &ccould not be read. Check the console."),
            new Replacement("customtext.failed","{prefix} &cNo se pudo leer el texto personalizado. Revisa la consola.","{prefix} &f{file} &cno se pudo leer. Revisa la consola."),
            // /whois shows one field per line.
            new Replacement("info.whois.dates","&7First joined: &f{first_join} &8| &7{seen_label}: &f{seen_value}","&7First joined: &f{first_join}\n&7{seen_label}: &f{seen_value}"),
            new Replacement("info.whois.state","&7Gamemode: &f{gamemode} &8| &7Fly: {fly} &8| &7Flying: {flying} &8| &7God: {god} &8| &7Vanished: {vanished} &8| &7AFK: {afk}","&7Gamemode: &f{gamemode}\n&7Fly: {fly}\n&7Flying: {flying}\n&7God: {god}\n&7Vanished: {vanished}\n&7AFK: {afk}"),
            new Replacement("info.whois.vitals","&7Health: &f{health}&7/&f{max_health} &8| &7Food: &f{food} &8| &7Level: &f{level} &8| &7Ping: &f{ping}ms &8| &7Walk speed: &f{speed}","&7Health: &f{health}&7/&f{max_health}\n&7Food: &f{food}\n&7Level: &f{level}\n&7Ping: &f{ping}ms\n&7Walk speed: &f{speed}"),
            new Replacement("info.whois.flags","&7Op: {op} &8| &7Muted: {muted} &8| &7Banned: {banned} &8| &7Whitelisted: {whitelisted} &8| &7Frozen: {frozen} &8| &7Jailed: {jailed}","&7Op: {op}\n&7Muted: {muted}\n&7Banned: {banned}\n&7Whitelisted: {whitelisted}\n&7Frozen: {frozen}\n&7Jailed: {jailed}"),
            new Replacement("info.whois.dates","&7Primer ingreso: &f{first_join} &8| &7{seen_label}: &f{seen_value}","&7Primer ingreso: &f{first_join}\n&7{seen_label}: &f{seen_value}"),
            new Replacement("info.whois.state","&7Modo: &f{gamemode} &8| &7Vuelo: {fly} &8| &7Volando: {flying} &8| &7Dios: {god} &8| &7Invisible: {vanished} &8| &7AFK: {afk}","&7Modo: &f{gamemode}\n&7Vuelo: {fly}\n&7Volando: {flying}\n&7Dios: {god}\n&7Invisible: {vanished}\n&7AFK: {afk}"),
            new Replacement("info.whois.vitals","&7Vida: &f{health}&7/&f{max_health} &8| &7Comida: &f{food} &8| &7Nivel: &f{level} &8| &7Ping: &f{ping}ms &8| &7Velocidad: &f{speed}","&7Vida: &f{health}&7/&f{max_health}\n&7Comida: &f{food}\n&7Nivel: &f{level}\n&7Ping: &f{ping}ms\n&7Velocidad: &f{speed}"),
            new Replacement("info.whois.flags","&7Op: {op} &8| &7Silenciado: {muted} &8| &7Baneado: {banned} &8| &7En lista blanca: {whitelisted} &8| &7Congelado: {frozen} &8| &7Encarcelado: {jailed}","&7Op: {op}\n&7Silenciado: {muted}\n&7Baneado: {banned}\n&7En lista blanca: {whitelisted}\n&7Congelado: {frozen}\n&7Encarcelado: {jailed}"));

    private static Replacement color(String key,String text) {
        return new Replacement(key,text,"&c"+text);
    }

    static int upgrade(YamlConfiguration yaml) {
        int count=0;
        for(var replacement:STOCK){
            String key=replacement.key();
            Object raw=yaml.get(key);
            var parsed=LanguageEntry.read(raw);
            if(parsed==null||!parsed.text().equals(replacement.before()))continue;
            if(raw instanceof List<?> values){
                // A multi-line replacement becomes one list entry per line.
                var copy=new ArrayList<Object>();
                for(Object value:values){
                    if(replacement.before().equals(value))copy.addAll(List.of(replacement.after().split("\n",-1)));
                    else copy.add(value);
                }
                yaml.set(key,copy);
            }else if(yaml.isConfigurationSection(key)){
                yaml.set(key+".text",replacement.after());
            }else{
                yaml.set(key,replacement.after());
            }
            count++;
        }
        return count;
    }
}

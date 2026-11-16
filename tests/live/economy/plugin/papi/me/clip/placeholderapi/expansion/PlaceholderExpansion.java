package me.clip.placeholderapi.expansion;
import org.bukkit.entity.Player;
public abstract class PlaceholderExpansion {
    public abstract String getIdentifier();public abstract String getAuthor();public abstract String getVersion();
    public boolean persist(){return false;}
    public boolean register(){me.clip.placeholderapi.PlaceholderAPI.allium=this;return true;}
    public boolean unregister(){me.clip.placeholderapi.PlaceholderAPI.allium=null;return true;}
    public String onPlaceholderRequest(Player player,String params){return null;}
}

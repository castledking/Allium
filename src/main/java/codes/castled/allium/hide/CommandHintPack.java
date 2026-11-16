package codes.castled.allium.hide;

import codes.castled.allium.PluginStart;
import codes.castled.allium.text.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Client language override only. It is merged/exported, never offered as a replacement asset pack. */
public final class CommandHintPack {
    public static final String FILE="Allium_unknown_command_resource_pack.zip";
    private static final String KEY="command.unknown.command";
    private static final List<String> LOCALES=List.of("af_za", "ar_sa", "ast_es", "az_az", "ba_ru", "bar", "be_by", "be_latn", "bg_bg", "br_fr", "brb", "bs_ba", "ca_es", "cs_cz", "cv_cu", "cy_gb", "da_dk", "de_at", "de_ch", "de_de", "el_gr", "en_au", "en_ca", "en_gb", "en_nz", "en_pt", "en_ud", "en_us", "enp", "enws", "eo_uy", "es_ar", "es_cl", "es_ec", "es_es", "es_mx", "es_uy", "es_ve", "esan", "et_ee", "eu_es", "fa_ir", "fi_fi", "fil_ph", "fo_fo", "fr_ca", "fr_ch", "fr_fr", "fra_de", "fur_it", "fy_nl", "ga_ie", "gd_gb", "gl_es", "go_fr", "hal_ua", "haw_us", "he_il", "hi_in", "hn_no", "hr_hr", "hu_hu", "hy_am", "id_id", "ig_ng", "io_en", "is_is", "isv", "it_it", "ja_jp", "jbo_en", "ka_ge", "kk_kz", "kn_in", "ko_kr", "ksh", "kw_gb", "ky_kg", "la_la", "lb_lu", "li_li", "lmo", "lo_la", "lol_us", "lt_lt", "lv_lv", "lzh", "mk_mk", "mn_mn", "ms_my", "mt_mt", "nah", "nds_de", "nl_be", "nl_nl", "nn_no", "no_no", "oc_fr", "ovd", "pl_pl", "pls", "pt_br", "pt_pt", "qcb_es", "qid", "qya_aa", "ro_ro", "rpr", "ru_ru", "ry_ua", "sah_sah", "se_no", "sk_sk", "sl_si", "so_so", "sq_al", "sr_cs", "sr_sp", "sv_se", "sxu", "szl", "ta_in", "th_th", "tl_ph", "tlh_aa", "tok", "tr_tr", "tt_ru", "tzo_mx", "uk_ua", "uz_uz", "val_es", "vec_it", "vi_vn", "vp_vl", "vro", "yi_de", "yo_ng", "zh_cn", "zh_hk", "zh_tw", "zlm_arab");
    private CommandHintPack(){}
    public static String languageText(String raw){
        var glyphs=new ArrayList<FormattedText.StyledGlyph>();for(var g:TextFormatter.parse(raw).glyphs()){
            char color=g.legacyColorChar();if(g.rgb()!=-1){long distance=Long.MAX_VALUE;for(char c:"0123456789abcdef".toCharArray()){
                int rgb=TextFormatter.legacyColorRgb(c),dr=(g.rgb()>>16&255)-(rgb>>16&255),dg=(g.rgb()>>8&255)-(rgb>>8&255),db=(g.rgb()&255)-(rgb&255);long d=(long)dr*dr+(long)dg*dg+(long)db*db;
                if(d<distance){distance=d;color=c;}
            }}glyphs.add(new FormattedText.StyledGlyph(g.codePoint(),-1,color,g.decorations()));
        }return new FormattedText(glyphs).toLegacy().replace("%","%%");
    }
    public static byte[] build(String text)throws IOException{
        if(text.length()>4096)throw new IllegalArgumentException("command hint too long");
        var metadata=new JsonObject();metadata.addProperty("allium-command-hints",1);var pack=new JsonObject();pack.addProperty("pack_format",15);var range=new JsonArray();range.add(15);range.add(999);pack.add("supported_formats",range);pack.addProperty("min_format",15);pack.addProperty("max_format",999);pack.addProperty("description","Allium command completion helper");metadata.add("pack",pack);
        var lang=new JsonObject();lang.addProperty(KEY,languageText(text));byte[] translated=lang.toString().getBytes(StandardCharsets.UTF_8);
        var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)){
            entry(zip,"pack.mcmeta",metadata.toString().getBytes(StandardCharsets.UTF_8));for(String locale:LOCALES)entry(zip,"assets/minecraft/lang/"+locale+".json",translated);
        }return bytes.toByteArray();
    }
    private static void entry(ZipOutputStream zip,String name,byte[] data)throws IOException{var e=new ZipEntry(name);e.setTime(1704067200000L);zip.putNextEntry(e);zip.write(data);zip.closeEntry();}
    /** Called before other plugins enable so the mixer can see this helper at startup. */
    public static void startup(PluginStart plugin){
        try{
            var file=new File(plugin.getDataFolder(),"hide.yml");org.bukkit.configuration.file.YamlConfiguration c;
            if(file.isFile()){c=new org.bukkit.configuration.file.YamlConfiguration();c.load(file);}else try(var in=plugin.getResource("hide.yml")){if(in==null)return;c=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new InputStreamReader(in,StandardCharsets.UTF_8));}
            boolean enabled=plugin.getConfig().getBoolean("modules.hide",true)&&c.getBoolean("settings.enabled",false);
            export(plugin,enabled?c.getString("settings.unknown-command-completion-help-message",""):"");
        }catch(Exception error){plugin.getLogger().warning("Command hint pack export unavailable: "+error.getClass().getSimpleName());}
    }
    public record Targets(Path generated,Path mixer,boolean hasMixer) {}
    public static Targets targets(PluginStart plugin){
        var rpm=plugin.getServer().getPluginManager().getPlugin("ResourcePackManager");
        return new Targets(plugin.getDataFolder().toPath().resolve("generated").resolve(FILE),(rpm==null?plugin.getDataFolder().toPath().getParent().resolve("ResourcePackManager"):rpm.getDataFolder().toPath()).resolve("mixer").resolve(FILE),rpm!=null);
    }
    public static Path export(PluginStart plugin,String text)throws IOException{return export(targets(plugin),text);}
    public static Path export(Targets targets,String text)throws IOException{
        Path generated=targets.generated(),mixer=targets.mixer();
        Path target=targets.hasMixer()?mixer:generated,other=targets.hasMixer()?generated:mixer;
        if(text.isEmpty()){removeOwned(generated);removeOwned(mixer);return target;}
        byte[] content=build(text);if(Files.exists(target)){
            if(!owned(target))throw new IOException("helper target belongs to another pack");
            if(Arrays.equals(Files.readAllBytes(target),content)){removeOwned(other);return target;}
        }
        Files.createDirectories(target.getParent());Path temp=Files.createTempFile(target.getParent(),".allium-command-hint-",".tmp");
        try{Files.write(temp,content);Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}
        removeOwned(other);return target;
    }
    private static void removeOwned(Path file)throws IOException{if(Files.exists(file)&&owned(file))Files.delete(file);}
    static boolean owned(Path file)throws IOException{
        if(!Files.isRegularFile(file)||Files.size(file)>2_000_000)return false;
        try(var zip=new ZipFile(file.toFile())){
            var meta=zip.getEntry("pack.mcmeta");if(meta==null||meta.getSize()>16384)return false;
            try(var in=zip.getInputStream(meta)){byte[] bytes=in.readNBytes(16385);if(bytes.length>16384)return false;var obj=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();return obj.has("allium-command-hints")&&obj.get("allium-command-hints").getAsInt()==1;}
        }catch(JsonParseException|IllegalStateException|NumberFormatException|ZipException invalid){return false;}
    }
}

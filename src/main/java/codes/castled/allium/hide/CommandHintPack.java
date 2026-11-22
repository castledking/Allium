package codes.castled.allium.hide;

import codes.castled.allium.PluginStart;
import codes.castled.allium.pack.PackMerger;
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
    /** Reads the same hint used by the hide module without depending on module startup. */
    public static String configured(PluginStart plugin) throws IOException, org.bukkit.configuration.InvalidConfigurationException {
        if (!plugin.getConfig().getBoolean("modules.hide", true)) return "";
        var file = new File(plugin.getDataFolder(), "hide.yml");
        var config = new org.bukkit.configuration.file.YamlConfiguration();
        if (file.isFile()) config.load(file);
        else try (var in = plugin.getResource("hide.yml")) {
            if (in == null) return "";
            config.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return config.getBoolean("settings.enabled", false)
                ? config.getString("settings.unknown-command-completion-help-message", "") : "";
    }

    public static byte[] merge(byte[] pack, String text) throws IOException {
        if (text.isEmpty()) return pack;
        if (text.length() > 4096) throw new IllegalArgumentException("command hint too long");
        var paths = LOCALES.stream().map(locale -> "assets/minecraft/lang/" + locale + ".json").toList();
        return PackMerger.setTranslations(pack, paths, KEY, languageText(text));
    }

    public static void cleanup(PluginStart plugin, Path mixer) throws IOException {
        Path generated = plugin.getDataFolder().toPath().resolve("generated");
        cleanup(generated, mixer);
    }

    static void cleanup(Path generated, Path mixer) throws IOException {
        if (Files.isDirectory(generated, LinkOption.NOFOLLOW_LINKS)) {
            removeOwned(generated.resolve(FILE));
            try { Files.delete(generated); } catch (DirectoryNotEmptyException preserved) { }
        }
        if (mixer != null && !Files.isSymbolicLink(mixer)) removeOwned(mixer.resolve(FILE));
    }

    private static void removeOwned(Path file) throws IOException {
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && owned(file)) Files.delete(file);
    }
    static boolean owned(Path file)throws IOException{
        if(!Files.isRegularFile(file)||Files.size(file)>2_000_000)return false;
        try(var zip=new ZipFile(file.toFile())){
            var meta=zip.getEntry("pack.mcmeta");if(meta==null||meta.getSize()>16384)return false;
            try(var in=zip.getInputStream(meta)){byte[] bytes=in.readNBytes(16385);if(bytes.length>16384)return false;var obj=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();return obj.has("allium-command-hints")&&obj.get("allium-command-hints").getAsInt()==1;}
        }catch(JsonParseException|IllegalStateException|NumberFormatException|ZipException invalid){return false;}
    }
}

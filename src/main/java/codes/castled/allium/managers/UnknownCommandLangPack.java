package codes.castled.allium.managers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import codes.castled.allium.managers.core.Text;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a tiny resource pack overriding the client-side
 * {@code command.unknown.command} translation: the helper line the chat box
 * shows while typing a command that does not exist. The client resolves that
 * text locally, so a language override is the only way to change it.
 *
 * With ResourcePackManager installed the pack is dropped into its mixer folder
 * and merged into the server pack; otherwise it is written to
 * {@code plugins/Allium/generated/} for manual merging.
 */
public final class UnknownCommandLangPack {

    public static final String DEFAULT_MESSAGE = "&c&lERROR &8» &7Unknown command. Type &c/help &7for a list of commands.";

    static final String TRANSLATION_KEY = "command.unknown.command";

    private static final String PACK_FILE = "Allium_resource_pack.zip";
    private static final String RSPM_PLUGIN = "ResourcePackManager";
    // Fixed entry time so identical content always produces identical bytes.
    private static final long ENTRY_TIME = 1704067200000L;
    private static final String PACK_MCMETA = "{\"pack\":{\"pack_format\":46,\"supported_formats\":[15,999],"
            + "\"min_format\":15,\"max_format\":999,\"description\":\"Allium unknown-command helper text\"}}";

    // The client loads the selected locale on top of en_us, so the override
    // has to exist in every locale to win over the vanilla translation.
    static final List<String> LOCALES = List.of(
            "af_za", "ar_sa", "ast_es", "az_az", "ba_ru", "bar", "be_by", "be_latn", "bg_bg", "br_fr", "brb", "bs_ba",
            "ca_es", "cs_cz", "cv_cu", "cy_gb", "da_dk", "de_at", "de_ch", "de_de", "el_gr", "en_au", "en_ca", "en_gb",
            "en_nz", "en_pt", "en_ud", "en_us", "enp", "enws", "eo_uy", "es_ar", "es_cl", "es_ec", "es_es", "es_mx",
            "es_uy", "es_ve", "esan", "et_ee", "eu_es", "fa_ir", "fi_fi", "fil_ph", "fo_fo", "fr_ca", "fr_ch", "fr_fr",
            "fra_de", "fur_it", "fy_nl", "ga_ie", "gd_gb", "gl_es", "go_fr", "hal_ua", "haw_us", "he_il", "hi_in",
            "hn_no", "hr_hr", "hu_hu", "hy_am", "id_id", "ig_ng", "io_en", "is_is", "isv", "it_it", "ja_jp", "jbo_en",
            "ka_ge", "kk_kz", "kn_in", "ko_kr", "ksh", "kw_gb", "ky_kg", "la_la", "lb_lu", "li_li", "lmo", "lo_la",
            "lol_us", "lt_lt", "lv_lv", "lzh", "mk_mk", "mn_mn", "ms_my", "mt_mt", "nah", "nds_de", "nl_be", "nl_nl",
            "nn_no", "no_no", "oc_fr", "ovd", "pl_pl", "pls", "pt_br", "pt_pt", "qcb_es", "qid", "qya_aa", "ro_ro",
            "rpr", "ru_ru", "ry_ua", "sah_sah", "se_no", "sk_sk", "sl_si", "so_so", "sq_al", "sr_cs", "sr_sp", "sv_se",
            "sxu", "szl", "ta_in", "th_th", "tl_ph", "tlh_aa", "tok", "tr_tr", "tt_ru", "tzo_mx", "uk_ua", "uz_uz",
            "val_es", "vec_it", "vi_vn", "vp_vl", "vro", "yi_de", "yo_ng", "zh_cn", "zh_hk", "zh_tw", "zlm_arab"
    );

    private static final String LEGACY_CODES = "0123456789abcdef";
    private static final int[] LEGACY_RGB = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };
    private static final Pattern SECTION_HEX = Pattern.compile("§x((?:§[0-9a-fA-F]){6})");

    private UnknownCommandLangPack() {}

    public static void apply(Plugin plugin, String message) {
        Plugin rspm = Bukkit.getPluginManager().getPlugin(RSPM_PLUGIN);
        File target = rspm != null
                ? new File(new File(rspm.getDataFolder(), "mixer"), PACK_FILE)
                : new File(new File(plugin.getDataFolder(), "generated"), PACK_FILE);
        try {
            if (message == null || message.isBlank()) {
                if (Files.deleteIfExists(target.toPath())) {
                    Text.sendDebugLog(INFO, "Removed unknown-command lang pack " + target
                            + (rspm != null ? "; run /rspm reload to restore the vanilla message." : "."));
                }
                return;
            }

            byte[] pack = buildPack(toLangValue(Text.parseColors(message)));
            if (target.isFile() && Arrays.equals(Files.readAllBytes(target.toPath()), pack)) {
                return;
            }
            Files.createDirectories(target.getParentFile().toPath());
            Files.write(target.toPath(), pack);
            Text.sendDebugLog(INFO, rspm != null
                    ? "Wrote unknown-command lang pack to ResourcePackManager mixer; run /rspm reload to send it to players."
                    : "Wrote unknown-command lang pack to " + target + "; merge it into your server resource pack to apply it.");
        } catch (IOException e) {
            Text.sendDebugLog(ERROR, "Failed to write unknown-command lang pack " + target, e);
        }
    }

    /**
     * Makes a §-formatted string safe for a language file: hex colors are
     * rounded to the nearest of the 16 standard colors (the client only reads
     * single-character codes there) and '%' is escaped, since language values
     * are format strings.
     */
    static String toLangValue(String legacy) {
        Matcher matcher = SECTION_HEX.matcher(legacy);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            int rgb = Integer.parseInt(matcher.group(1).replace("§", ""), 16);
            matcher.appendReplacement(out, Matcher.quoteReplacement("§" + nearestLegacyCode(rgb)));
        }
        matcher.appendTail(out);
        return out.toString().replace("%", "%%");
    }

    static byte[] buildPack(String langValue) throws IOException {
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        byte[] lang = gson.toJson(Map.of(TRANSLATION_KEY, langValue)).getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            putEntry(zip, "pack.mcmeta", PACK_MCMETA.getBytes(StandardCharsets.UTF_8));
            for (String locale : LOCALES) {
                putEntry(zip, "assets/minecraft/lang/" + locale + ".json", lang);
            }
        }
        return bytes.toByteArray();
    }

    private static void putEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(ENTRY_TIME);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private static char nearestLegacyCode(int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < LEGACY_RGB.length; i++) {
            int dr = r - (LEGACY_RGB[i] >> 16 & 0xFF);
            int dg = g - (LEGACY_RGB[i] >> 8 & 0xFF);
            int db = b - (LEGACY_RGB[i] & 0xFF);
            long distance = (long) dr * dr + (long) dg * dg + (long) db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return LEGACY_CODES.charAt(best);
    }
}

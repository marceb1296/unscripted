package dev.unscripted.core;

import java.util.Locale;

/**
 * Textos que el servidor manda ya traducidos: el cliente vanilla no tiene el archivo de idioma del mod.
 * El cliente informa su idioma ("es_mx", "en_us"...); todo español recibe español, el resto inglés.
 */
public final class Lang {
    private Lang() {
    }

    public static boolean spanish(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("es");
    }

    public static String pick(String locale, String english, String spanish) {
        return spanish(locale) ? spanish : english;
    }
}

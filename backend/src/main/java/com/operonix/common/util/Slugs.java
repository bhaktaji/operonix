package com.operonix.common.util;

import java.text.Normalizer;
import java.util.Locale;

public final class Slugs {

    private Slugs() {
    }

    public static String fromName(String name) {
        String normalised = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (normalised.length() > 55) {
            normalised = normalised.substring(0, 55).replaceAll("-+$", "");
        }
        if (normalised.length() < 2) {
            normalised = "workspace";
        }
        return normalised;
    }
}

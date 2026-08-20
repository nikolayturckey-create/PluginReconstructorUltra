package dev.nik.reconstructor.decompile;

import java.util.Locale;

public enum EngineMode {
    AUTO,
    VINEFLOWER,
    CFR,
    PROCYON,
    BOTH,
    ALL,
    NONE;

    public static EngineMode parse(String value) {
        if (value == null) return AUTO;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "auto" -> AUTO;
            case "vineflower", "vf" -> VINEFLOWER;
            case "cfr" -> CFR;
            case "procyon", "pc" -> PROCYON;
            case "both" -> BOTH;
            case "all" -> ALL;
            case "none", "off", "analysis" -> NONE;
            default -> throw new IllegalArgumentException("Unknown engine mode: " + value);
        };
    }
}

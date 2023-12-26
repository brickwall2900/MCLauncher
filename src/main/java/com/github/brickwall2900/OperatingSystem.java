package com.github.brickwall2900;

import java.util.Locale;

public enum OperatingSystem {
    WINDOWS("windows"), OSX("osx"), LINUX("linux");

    public final String name;

    OperatingSystem(String name) {
        this.name = name;
    }

    public static OperatingSystem getOperatingSystem(String osName) {
        for (OperatingSystem operatingSystem : values()) {
            if (operatingSystem.name.equalsIgnoreCase(osName)) return operatingSystem;
        }
        return null;
    }

    public static OperatingSystem detectOperatingSystem() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (osName.contains("mac")) return OSX;
        else if (osName.contains("nux")) return LINUX;
        else if (osName.contains("win")) return WINDOWS;
        else throw new IllegalStateException("Operating system not found!");
    }
}

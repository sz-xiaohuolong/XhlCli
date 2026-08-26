package com.xhlcli.config;

import java.util.Locale;

public enum LogLevel {
    ERROR,
    WARN,
    INFO,
    DEBUG;

    public boolean allows(LogLevel eventLevel) {
        return eventLevel.ordinal() <= ordinal();
    }

    public static LogLevel parse(String value) throws ConfigurationException {
        try {
            return LogLevel.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new ConfigurationException("Log level must be one of ERROR, WARN, INFO, or DEBUG.");
        }
    }
}

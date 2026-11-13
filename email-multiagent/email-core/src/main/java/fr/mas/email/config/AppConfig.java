package fr.mas.email.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Loads application configuration from {@code config.properties}. */
public final class AppConfig {

    private final Properties properties;

    private AppConfig(Properties properties) {
        this.properties = properties;
    }

    public static AppConfig load() {
        Properties properties = new Properties();
        try (InputStream in = AppConfig.class.getResourceAsStream("/config.properties")) {
            if (in == null) {
                throw new IllegalStateException("config.properties not found on classpath");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load config.properties", e);
        }
        return new AppConfig(properties);
    }

    /** Builds a config from explicit values (tests, embedding). */
    public static AppConfig of(Properties properties) {
        return new AppConfig(properties);
    }

    public String get(String key, String fallback) {
        return properties.getProperty(key, fallback);
    }

    public double getDouble(String key, double fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Double.parseDouble(value);
    }

    public int getInt(String key, int fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Integer.parseInt(value);
    }
}

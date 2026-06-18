package dev.memnos.controlbridge;

import org.bukkit.configuration.file.FileConfiguration;

/** Immutable view of the operator-supplied plugin configuration. */
public final class BridgeConfig {

    private final String controllerUrl;
    private final String authToken;
    private final int reconnectDelaySeconds;

    private BridgeConfig(String controllerUrl, String authToken, int reconnectDelaySeconds) {
        this.controllerUrl = controllerUrl;
        this.authToken = authToken;
        this.reconnectDelaySeconds = reconnectDelaySeconds;
    }

    /** Read configuration from the plugin's config.yml. */
    public static BridgeConfig from(FileConfiguration config) {
        String url = config.getString("controller-url", "");
        String token = config.getString("auth-token", "");
        int delay = config.getInt("reconnect-delay-seconds", 5);
        return new BridgeConfig(url, token, delay);
    }

    public String controllerUrl() {
        return controllerUrl;
    }

    public String authToken() {
        return authToken;
    }

    public int reconnectDelaySeconds() {
        return reconnectDelaySeconds;
    }

    public boolean hasControllerUrl() {
        return controllerUrl != null && !controllerUrl.isBlank();
    }
}
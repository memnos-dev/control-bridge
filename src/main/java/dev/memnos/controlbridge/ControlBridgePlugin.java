package dev.memnos.controlbridge;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point. Loads configuration, opens the controller connection,
 * and tears it down on disable. Holds no game logic — it is a transport bridge.
 */
public final class ControlBridgePlugin extends JavaPlugin {

    private static final String ADAPTER_VERSION = "0.1.0";

    private BridgeClient client;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        BridgeConfig config = BridgeConfig.from(getConfig());

        if (!config.hasControllerUrl()) {
            getLogger().warning(
                    "No controller-url configured; bridge is idle. Set it in config.yml.");
            return;
        }

        // world_id is read from the server at runtime, not from config.
        String worldId = resolveWorldId();

        client = new BridgeClient(this, config, worldId, ADAPTER_VERSION);
        client.connect();
        getLogger().info("ControlBridge enabled; connecting to controller.");
    }

    @Override
    public void onDisable() {
        if (client != null) {
            client.shutdown();
            client = null;
        }
        getLogger().info("ControlBridge disabled.");
    }

    /**
     * Resolve the primary world's unique id. The bridge binds to the server's
     * main world for now; multi-world routing is a later concern.
     */
    private String resolveWorldId() {
        if (!getServer().getWorlds().isEmpty()) {
            return getServer().getWorlds().get(0).getUID().toString();
        }
        return "unknown";
    }
}
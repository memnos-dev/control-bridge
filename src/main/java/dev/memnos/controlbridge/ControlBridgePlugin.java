package dev.memnos.controlbridge;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.trait.TraitInfo;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point. Loads configuration, registers the identity trait, wires
 * the bridge components, opens the controller connection, and tears it down on
 * disable. Holds no game logic - it is a transport bridge.
 */
public final class ControlBridgePlugin extends JavaPlugin {

    private static final String ADAPTER_VERSION = "0.1.0";

    private BridgeClient client;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        BridgeConfig config = BridgeConfig.from(getConfig());

        org.bukkit.plugin.Plugin citizens = getServer().getPluginManager().getPlugin("Citizens");
        if (citizens == null || !citizens.isEnabled()) {
            getLogger().severe("Citizens not enabled; NPC features unavailable.");
            return;
        }
        CitizensAPI.getTraitFactory().registerTrait(TraitInfo.create(IdentityTrait.class));

        if (!config.hasControllerUrl()) {
            getLogger().warning("No controller-url configured; bridge is idle. Set it in config.yml.");
            return;
        }

        String worldId = resolveWorldId();
        NpcManager npcManager = new NpcManager(this, config.proximityRadius(), config.debugWireLogging());

        client = new BridgeClient(this, config, worldId, ADAPTER_VERSION);
        ChoiceRenderer choiceRenderer = new ChoiceRenderer(this, client);
        CommandDispatcher dispatcher = new CommandDispatcher(
                this, npcManager, choiceRenderer, config.debugWireLogging());
        client.attach(dispatcher);

        getServer().getPluginManager().registerEvents(
                new GameEventListener(this, client, npcManager), this);

        client.connect();
        // Rebuild the id<->NPC index on the next tick, after Citizens has loaded
        // its NPCs (the trait persists across restarts, ADR-002 E3).
        getServer().getScheduler().runTask(this, npcManager::rebuildIndex);

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
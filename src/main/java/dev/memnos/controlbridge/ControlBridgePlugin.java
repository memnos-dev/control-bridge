package dev.memnos.controlbridge;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.trait.TraitInfo;
import org.bukkit.plugin.java.JavaPlugin;
import net.citizensnpcs.api.event.CitizensEnableEvent;
import net.citizensnpcs.api.event.CitizensReloadEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

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
        WorldQueryHandler worldQueryHandler = new WorldQueryHandler(this, npcManager, client);
        CommandDispatcher dispatcher = new CommandDispatcher(
                this, npcManager, choiceRenderer, client, worldQueryHandler, config.debugWireLogging());
        client.attach(dispatcher);
        client.attachNpcReportSource(npcManager::indexedNpcIds);


        getServer().getPluginManager().registerEvents(
                new GameEventListener(this, client, npcManager), this);

        client.connect();
        // Rebuild the id<->NPC index when Citizens signals its NPCs are loaded
        // (restart-safe; a fixed next-tick rebuild races Citizens' own load).
        // CitizensReloadEvent covers /citizens reload. Index size is logged so an
        // empty index is visible, never silent (ADR-002 E3).
                getServer().getPluginManager().registerEvents(new Listener() {
                    @EventHandler
                    public void onCitizensEnable(CitizensEnableEvent event) {
                        npcManager.rebuildIndex();
                        getLogger().info("NPC index rebuilt (Citizens enabled): "
                                + npcManager.indexSize() + " NPC(s).");
                    }

                    @EventHandler
                    public void onCitizensReload(CitizensReloadEvent event) {
                        npcManager.rebuildIndex();
                        getLogger().info("NPC index rebuilt (Citizens reloaded): "
                                + npcManager.indexSize() + " NPC(s).");
                    }
                }, this);
        // Reactive approach detection: poll once per second on the main thread
        // (Citizens/location reads are not thread-safe). Edge-triggered + radius-gated.
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (NpcManager.Approach a : npcManager.scanApproaches()) {
                client.send(WireSender.playerApproach(a.playerUuid(), a.npcId(), a.distance()));
            }
        }, 20L, 20L);

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
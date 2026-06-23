package dev.memnos.controlbridge;

import com.google.gson.JsonObject;
import org.bukkit.plugin.Plugin;

import java.util.Optional;

/**
 * Handles inbound "world_query": asks NpcManager for a situational snapshot and
 * returns it correlated (echoing the command's msg_id as correlation_id).
 *
 * Threading: handle() is ALREADY on the main thread — CommandDispatcher.onWireMessage
 * hops there before dispatch (see handleItemTransfer). So NO runTask here.
 */
public final class WorldQueryHandler {

    private final Plugin plugin;
    private final NpcManager npcManager;
    private final BridgeClient client;

    public WorldQueryHandler(Plugin plugin, NpcManager npcManager, BridgeClient client) {
        this.plugin = plugin;
        this.npcManager = npcManager;
        this.client = client;
    }

    public void handle(JsonObject msg) {
        String correlationId = msg.get("msg_id").getAsString();
        String npcId = msg.get("npc_id").getAsString();

        Optional<WorldQueryResult> snapshot = npcManager.observe(npcId);
        if (snapshot.isEmpty()) {
            // NPC absent (rare race, e.g. despawned mid-tick). The world_query result
            // schema has no success/reason field and there is no generic error result
            // on the wire, so we deliberately send nothing: the controller's request
            // times out and BehaviorService degrades to lore-only (CC-WO-02).
            plugin.getLogger().warning("world_query for absent NPC; no snapshot sent.");
            return;
        }
        client.send(WireSender.worldQueryResult(correlationId, snapshot.get()));
    }
}
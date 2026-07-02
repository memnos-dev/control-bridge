package dev.memnos.controlbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses inbound controller commands and dispatches them to game actions.
 * Parsing runs on the WebSocket thread; every game action is hopped to the
 * main thread because Citizens/Bukkit are not thread-safe. Malformed or unknown
 * messages are dropped, never fatal (the bridge must not destabilise the server).
 */
public final class CommandDispatcher {

    private final Plugin plugin;
    private final NpcManager npcManager;
    private final ChoiceRenderer choiceRenderer;
    private final BridgeClient client;
    private final WorldQueryHandler worldQueryHandler;
    private final boolean debugWireLogging;

    public CommandDispatcher(Plugin plugin, NpcManager npcManager,
                             ChoiceRenderer choiceRenderer, BridgeClient client, WorldQueryHandler worldQueryHandler, boolean debugWireLogging) {
        this.plugin = plugin;
        this.npcManager = npcManager;
        this.choiceRenderer = choiceRenderer;
        this.client = client;
        this.worldQueryHandler = worldQueryHandler;
        this.debugWireLogging = debugWireLogging;
    }

    /** Entry point from BridgeClient.onMessage (WebSocket thread). */
    public void onWireMessage(String raw) {
        final JsonObject msg;
        try {
            msg = JsonParser.parseString(raw).getAsJsonObject();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Dropped unparseable controller message.");
            return;
        }
        if (!msg.has("command")) {
            return; // not a command envelope; ignore
        }
        String command = msg.get("command").getAsString();
        if (debugWireLogging) {
            plugin.getLogger().info("WIRE IN: " + command);
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> handle(command, msg));
    }

    private void handle(String command, JsonObject msg) {
        switch (command) {
            case "spawn_npc" -> npcManager.spawn(
                    str(msg, "npc_id"), str(msg, "name"),
                    dbl(msg, "x"), dbl(msg, "y"), dbl(msg, "z"),
                    str(msg, "world_id"), optStr(msg, "skin_ref"));
            case "despawn_npc" -> npcManager.despawn(str(msg, "npc_id"));
            case "npc_say" -> renderSay(str(msg, "npc_id"), str(msg, "text"), str(msg, "audience"));
            case "npc_thinking" -> renderThinking(str(msg, "npc_id"), str(msg, "audience"));
            case "present_choices" -> choiceRenderer.present(
                    str(msg, "npc_id"), str(msg, "player_id"),
                    str(msg, "prompt"), parseOptions(msg));
            case "show_disclosure" -> renderDisclosure(str(msg, "player_id"), str(msg, "text"));
            case "npc_move" -> npcManager.move(
                    str(msg, "npc_id"),
                    dbl(msg, "x"), dbl(msg, "y"), dbl(msg, "z"),
                    str(msg, "world_id"));
            case "npc_place" -> npcManager.place(
                    str(msg, "npc_id"),
                    dbl(msg, "x"), dbl(msg, "y"), dbl(msg, "z"),
                    str(msg, "world_id"));
            case "item_transfer" -> handleItemTransfer(msg);
            case "world_query" -> worldQueryHandler.handle(msg);
            default -> plugin.getLogger().warning("Unknown command from controller: " + command);
        }
    }

    private void renderSay(String npcId, String text, String audience) {
        Component line = Component.text(npcManager.displayName(npcId) + ": " + text);
        for (Player p : npcManager.resolveAudience(npcId, audience)) {
            p.sendMessage(line);
        }
    }

    private void renderThinking(String npcId, String audience) {
        // D4: transient action-bar latency signal (5-10s LLM latency UX).
        Component line = Component.text(npcManager.displayName(npcId) + " is thinking...");
        for (Player p : npcManager.resolveAudience(npcId, audience)) {
            p.sendActionBar(line);
        }
    }

    private void renderDisclosure(String playerId, String text) {
        Player p = playerById(playerId);
        if (p != null) {
            p.sendMessage(Component.text(text)); // AI Act Art. 50 wording owned by core
        }
    }

    private List<ChoiceRenderer.Choice> parseOptions(JsonObject msg) {
        List<ChoiceRenderer.Choice> out = new ArrayList<>();
        if (msg.has("options") && msg.get("options").isJsonArray()) {
            JsonArray arr = msg.getAsJsonArray("options");
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                out.add(new ChoiceRenderer.Choice(str(o, "choice_id"), str(o, "display_text")));
            }
        }
        return out;
    }

    private Player playerById(String playerId) {
        try {
            return plugin.getServer().getPlayer(java.util.UUID.fromString(playerId));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String str(JsonObject o, String field) {
        return o.get(field).getAsString();
    }

    private static String optStr(JsonObject o, String field) {
        return o.has(field) && !o.get(field).isJsonNull() ? o.get(field).getAsString() : null;
    }

    private static double dbl(JsonObject o, String field) {
        return o.get(field).getAsDouble();
    }

    /**
     * GIVE/TAKE on the player inventory. Pure mechanism, no consent logic (that lives
     * server-side; the dev tool bypasses it). Already on the main thread (dispatcher
     * hops there), so NO runTask here. Always sends exactly one result.
     */
    private void handleItemTransfer(JsonObject msg) {
        String correlationId = str(msg, "msg_id");   // result echoes the command's msg_id
        String playerId = str(msg, "player_id");
        String itemType = str(msg, "item_type");
        int count = msg.get("count").getAsInt();
        String direction = str(msg, "direction");

        Player player = playerById(playerId);
        if (player == null) {
            client.send(WireSender.itemTransferResult(correlationId, false, "player_not_found"));
            return;
        }

        org.bukkit.Material material = org.bukkit.Material.matchMaterial(itemType);
        if (material == null) {
            client.send(WireSender.itemTransferResult(correlationId, false, "unknown_item_type"));
            return;
        }

        org.bukkit.inventory.ItemStack stack = new org.bukkit.inventory.ItemStack(material, count);
        boolean success;
        String reason;

        if ("give".equals(direction)) {
            // addItem returns the leftover that did not fit (inventory full).
            boolean leftover = !player.getInventory().addItem(stack).isEmpty();
            success = !leftover;
            reason = leftover ? "inventory_full" : null;
        } else if ("take".equals(direction)) {
            // removeItem returns what could NOT be removed (player lacked enough).
            boolean leftover = !player.getInventory().removeItem(stack).isEmpty();
            success = !leftover;
            reason = leftover ? "insufficient_items" : null;
        } else {
            success = false;
            reason = "unknown_direction";
        }

        client.send(WireSender.itemTransferResult(correlationId, success, reason));
    }
}
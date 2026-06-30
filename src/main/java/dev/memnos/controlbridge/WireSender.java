package dev.memnos.controlbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds enveloped wire messages (ADR-002 E2). Pure construction, no I/O.
 * Every outbound message carries schema_version / msg_id / ts. Numeric fields
 * use Gson's addProperty(Number), which is locale-independent (no "String.format").
 */
public final class WireSender {

    private static final int SCHEMA_VERSION = 1;

    private WireSender() {
    }

    private static JsonObject envelope() {
        JsonObject msg = new JsonObject();
        msg.addProperty("schema_version", SCHEMA_VERSION);
        msg.addProperty("msg_id", UUID.randomUUID().toString());
        msg.addProperty("ts", Instant.now().toString());
        return msg;
    }

    /** Handshake (plugin -> core). auth_token is in the payload, never logged. */
    public static JsonObject handshake(String authToken, String worldId, String adapterVersion) {
        JsonObject msg = envelope();
        msg.addProperty("type", "plugin");
        msg.addProperty("auth_token", authToken);
        msg.addProperty("world_id", worldId);
        msg.addProperty("adapter_version", adapterVersion);
        return msg;
    }

    /** player_join. player_id is the canonical MC UUID (ADR-002 E3). */
    public static JsonObject playerJoin(UUID playerUuid) {
        JsonObject msg = envelope();
        msg.addProperty("event", "player_join");
        msg.addProperty("player_id", playerUuid.toString());
        msg.addProperty("player_uuid", playerUuid.toString());
        return msg;
    }

    /** player_chat. Sent only when the player is within proximity (ADR-002 E4). */
    public static JsonObject playerChat(UUID playerUuid, String npcId, String message, double distance, int minuteOfDay) {
        JsonObject msg = envelope();
        msg.addProperty("event", "player_chat");
        msg.addProperty("player_id", playerUuid.toString());
        msg.addProperty("player_uuid", playerUuid.toString());
        msg.addProperty("npc_id", npcId);
        msg.addProperty("message", message);
        msg.addProperty("distance", distance);
        msg.addProperty("minute_of_day", minuteOfDay);
        return msg;
    }

    /** player_approach. Fired once on the outside→inside-radius transition (NPC-local). */
    public static JsonObject playerApproach(UUID playerUuid, String npcId, double distance) {
        JsonObject msg = envelope();
        msg.addProperty("event", "player_approach");
        msg.addProperty("player_id", playerUuid.toString());
        msg.addProperty("player_uuid", playerUuid.toString());
        msg.addProperty("npc_id", npcId);
        msg.addProperty("distance", distance);
        return msg;
    }

    /** player_choice. presented_at is the timestamp captured when choices were shown. */
    public static JsonObject playerChoice(String playerId, String npcId, String choiceId, Instant presentedAt) {
        JsonObject msg = envelope();
        msg.addProperty("event", "player_choice");
        msg.addProperty("player_id", playerId);
        msg.addProperty("npc_id", npcId);
        msg.addProperty("choice_id", choiceId);
        msg.addProperty("presented_at", presentedAt.toString());
        return msg;
    }

    /** item_transfer result. Strict contract: correlation_id + success + reason ONLY. */
    public static JsonObject itemTransferResult(String correlationId, boolean success, String reason) {
        JsonObject msg = envelope();           // sets schema_version, msg_id, ts
        msg.addProperty("correlation_id", correlationId);
        msg.addProperty("success", success);
        if (reason != null) {
            msg.addProperty("reason", reason);
        }
        // NOTE: no "type"/"command"/"event" field — Python correlates on correlation_id
        // and has extra="forbid". Do NOT add a discriminator here.
        return msg;
    }

    /**
     * world_query result. Correlates on correlation_id, NO discriminator (Python has
     * extra="forbid"), same convention as itemTransferResult. nearby_players is an array
     * of {player_id, distance, x, y, z}; numbers via addProperty (locale-independent).
     */
    public static JsonObject worldQueryResult(String correlationId, WorldQueryResult result) {
        JsonObject msg = envelope();                       // schema_version, msg_id, ts
        msg.addProperty("correlation_id", correlationId);
        msg.addProperty("minute_of_day", result.minuteOfDay());
        JsonArray players = new JsonArray();
        for (WorldQueryResult.NearbyPlayer p : result.nearbyPlayers()) {
            JsonObject o = new JsonObject();
            o.addProperty("player_id", p.playerId());
            o.addProperty("distance", p.distance());
            o.addProperty("x", p.x());
            o.addProperty("y", p.y());
            o.addProperty("z", p.z());
            players.add(o);
        }
        msg.add("nearby_players", players);
        return msg;
    }
}
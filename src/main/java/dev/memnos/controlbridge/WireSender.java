package dev.memnos.controlbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds enveloped wire messages. Pure construction, no I/O.
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

    /** player_join. player_id is the canonical MC UUID. */
    public static JsonObject playerJoin(UUID playerUuid) {
        JsonObject msg = envelope();
        msg.addProperty("event", "player_join");
        msg.addProperty("player_id", playerUuid.toString());
        msg.addProperty("player_uuid", playerUuid.toString());
        return msg;
    }

    /** player_chat. Sent only when the player is within proximity. */
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
        msg.addProperty("npc_x", result.npcX());
        msg.addProperty("npc_y", result.npcY());
        msg.addProperty("npc_z", result.npcZ());

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

    /**
     * world_scan result. Correlates on correlation_id, NO discriminator (Python
     * has extra="forbid") — same convention as worldQueryResult. candidates[]
     * carries the PoiCandidateIn wire shape verbatim; bounds echoes the
     * effective scan area (center_x, center_z, radius).
     */
    public static JsonObject worldScanResult(String correlationId,
                                             JsonArray candidates, JsonObject bounds) {
        JsonObject msg = envelope();
        msg.addProperty("correlation_id", correlationId);
        msg.add("candidates", candidates);
        msg.add("bounds", bounds);
        return msg;
    }

    /**
     * unknown_command error event. Correlates via the triggering command's
     * msg_id — loud-failure channel for python-declared, java-missing
     * handlers. Field shape mirrors memnos UnknownCommandMsg
     * (extra="forbid" on the Python side: no additional fields).
     */
    public static JsonObject unknownCommand(String correlationId, String command) {
        JsonObject msg = envelope();
        msg.addProperty("correlation_id", correlationId);
        msg.addProperty("error", "unknown_command");
        msg.addProperty("command", command);
        return msg;
    }

    /** disclosure_ack. Echoes the version shown so core can prove WHICH text was acked. */
    public static JsonObject disclosureAck(String playerId, String disclosureVersion) {
        JsonObject msg = envelope();
        msg.addProperty("event", "disclosure_ack");
        msg.addProperty("player_id", playerId);
        msg.addProperty("disclosure_version", disclosureVersion);
        return msg;
    }

    /**
     * npc_report (plugin -> core). Sent after every successful (re)connect,
     * right after the handshake. IDs only — spawn positions come
     * from the POI anchor, despawns need none.
     */
    public static JsonObject npcReport(java.util.Collection<String> npcIds) {
        JsonObject msg = envelope();
        msg.addProperty("event", "npc_report");
        JsonArray ids = new JsonArray();
        for (String id : npcIds) {
            ids.add(id);
        }
        msg.add("npc_ids", ids);
        return msg;
    }

    /**
     * capability_report (plugin -> core). Sent after every successful
     * (re)connect; standalone message so a future re-scan (e.g. on
     * PluginEnable/DisableEvent) needs no reconnect.
     */
    public static JsonObject capabilityReport(java.util.Collection<String> capabilities) {
        JsonObject msg = envelope();
        msg.addProperty("event", "capability_report");
        JsonArray caps = new JsonArray();
        for (String c : capabilities) {
            caps.add(c);
        }
        msg.add("capabilities", caps);
        return msg;
    }
}
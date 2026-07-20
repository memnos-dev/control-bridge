package dev.memnos.controlbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.citizensnpcs.api.CitizensAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Marker;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.Plugin;

/**
 * Handles inbound "world_scan": scans a bounded area of the primary
 * world for author-relevant markers (signs, villagers, display entities, marker
 * entities, item frames, named mobs) and returns them as neutral POI candidates
 * in the PoiCandidateIn wire shape, source "live_scan".
 *
 * Threading: handle() is ALREADY on the main thread — CommandDispatcher hops
 * there before dispatch (same as WorldQueryHandler). So NO runTask here.
 *
 * Chunk loading: getChunkAt() sync-loads unloaded chunks. At
 * DEFAULT_RADIUS 96 that is a 13x13 chunk square around spawn — spawn chunks
 * are typically resident, so the sync cost is acceptable for v0. If large
 * custom bounds ever stutter the main thread, the seam is this loop:
 * switch to world.getChunkAtAsync + candidate accumulation across ticks.
 */
public final class WorldScanHandler {

    private static final double DEFAULT_RADIUS = 96.0;
    private static final int MAX_CANDIDATES = 500;
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    private final Plugin plugin;
    private final BridgeClient client;
    private final String worldId;

    public WorldScanHandler(Plugin plugin, BridgeClient client, String worldId) {
        this.plugin = plugin;
        this.client = client;
        this.worldId = worldId;
    }

    public void handle(JsonObject msg) {
        String correlationId = msg.get("msg_id").getAsString();

        World world = plugin.getServer().getWorlds().get(0); // same binding as resolveWorldId()
        Location spawn = world.getSpawnLocation();
        double centerX = hasNonNull(msg, "center_x") ? msg.get("center_x").getAsDouble() : spawn.getX();
        double centerZ = hasNonNull(msg, "center_z") ? msg.get("center_z").getAsDouble() : spawn.getZ();
        double radius = hasNonNull(msg, "radius") ? msg.get("radius").getAsDouble() : DEFAULT_RADIUS;

        JsonArray candidates = new JsonArray();
        int minChunkX = (int) Math.floor((centerX - radius) / 16.0);
        int maxChunkX = (int) Math.floor((centerX + radius) / 16.0);
        int minChunkZ = (int) Math.floor((centerZ - radius) / 16.0);
        int maxChunkZ = (int) Math.floor((centerZ + radius) / 16.0);
        int chunks = 0;

        outer:
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                Chunk chunk = world.getChunkAt(cx, cz); // sync-load, see class doc
                chunks++;
                for (BlockState state : chunk.getTileEntities()) {
                    if (candidates.size() >= MAX_CANDIDATES) break outer;
                    scanBlockState(state, candidates);
                }
                for (Entity entity : chunk.getEntities()) {
                    if (candidates.size() >= MAX_CANDIDATES) break outer;
                    scanEntity(entity, candidates);
                }
            }
        }
        if (candidates.size() >= MAX_CANDIDATES) {
            plugin.getLogger().warning("world_scan truncated at " + MAX_CANDIDATES
                    + " candidates; narrow the bounds.");
        }
        plugin.getLogger().info("world_scan: " + candidates.size()
                + " candidate(s) from " + chunks + " chunk(s).");

        JsonObject bounds = new JsonObject();
        bounds.addProperty("center_x", centerX);
        bounds.addProperty("center_z", centerZ);
        bounds.addProperty("radius", radius);
        client.send(WireSender.worldScanResult(correlationId, candidates, bounds));
    }

    private void scanBlockState(BlockState state, JsonArray out) {
        if (!(state instanceof Sign sign)) {
            return;
        }
        StringBuilder allText = new StringBuilder();
        String name = null;
        for (Component line : sign.getSide(Side.FRONT).lines()) {
            String plain = PLAIN.serialize(line).trim();
            if (plain.isEmpty()) {
                continue;
            }
            if (name == null) {
                name = plain; // first non-empty line names the candidate
            }
            if (allText.length() > 0) {
                allText.append(" | ");
            }
            allText.append(plain);
        }
        if (name == null) {
            return; // blank sign — no authoring signal
        }
        JsonObject raw = new JsonObject();
        raw.addProperty("text", allText.toString());
        out.add(candidate(name, "sign",
                state.getX() + 0.5, state.getY(), state.getZ() + 0.5, raw));
    }

    private void scanEntity(Entity entity, JsonArray out) {
        if (CitizensAPI.getNPCRegistry().isNPC(entity)) {
            return; // never scan input
        }
        Location loc = entity.getLocation();

        if (entity instanceof Villager villager) {
            String profession = villager.getProfession().getKey().getKey();
            String name = customNameOrNull(entity);
            JsonObject raw = new JsonObject();
            raw.addProperty("profession", profession);
            out.add(candidate(
                    name != null ? name : "Villager (" + profession + ")",
                    "villager", loc.getX(), loc.getY(), loc.getZ(), raw));
            return;
        }
        if (entity instanceof TextDisplay display) {
            String text = PLAIN.serialize(display.text()).trim();
            if (text.isEmpty()) {
                return;
            }
            JsonObject raw = new JsonObject();
            raw.addProperty("text", text);
            out.add(candidate(text, "text_display",
                    loc.getX(), loc.getY(), loc.getZ(), raw));
            return;
        }
        if (entity instanceof Marker) {
            String name = customNameOrNull(entity);
            out.add(candidate(name != null ? name : "Marker", "marker",
                    loc.getX(), loc.getY(), loc.getZ(), new JsonObject()));
            return;
        }
        if (entity instanceof ItemFrame frame) {
            if (frame.getItem().getType().isAir()) {
                return; // empty frame — no signal
            }
            String itemType = frame.getItem().getType().getKey().getKey();
            JsonObject raw = new JsonObject();
            raw.addProperty("item_type", itemType);
            out.add(candidate(itemType, "item_frame",
                    loc.getX(), loc.getY(), loc.getZ(), raw));
            return;
        }
        if (entity instanceof LivingEntity) {
            String name = customNameOrNull(entity);
            if (name == null) {
                return; // unnamed mob — ambient, not authored
            }
            JsonObject raw = new JsonObject();
            raw.addProperty("entity_type", entity.getType().getKey().getKey());
            out.add(candidate(name, "named_entity",
                    loc.getX(), loc.getY(), loc.getZ(), raw));
        }
    }

    private static String customNameOrNull(Entity entity) {
        Component custom = entity.customName();
        if (custom == null) {
            return null;
        }
        String plain = PLAIN.serialize(custom).trim();
        return plain.isEmpty() ? null : plain;
    }

    /** PoiCandidateIn wire shape verbatim; source fixed "live_scan". */
    private JsonObject candidate(String name, String poiType,
                                 double x, double y, double z, JsonObject rawData) {
        JsonObject position = new JsonObject();
        position.addProperty("world_id", worldId);
        position.addProperty("x", x);
        position.addProperty("y", y);
        position.addProperty("z", z);

        JsonObject c = new JsonObject();
        c.addProperty("name", name);
        c.addProperty("poi_type", poiType);
        c.addProperty("source", "live_scan");
        c.add("position", position);
        c.add("raw_data", rawData);
        return c;
    }

    /** Wire contract: a bounds field that is absent OR JSON null counts as unset. */
    private static boolean hasNonNull(JsonObject o, String field) {
        return o.has(field) && !o.get(field).isJsonNull();
    }
}
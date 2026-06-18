package dev.memnos.controlbridge;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns every Citizens call (ADR-002 E1 encapsulation boundary). All methods run
 * on the server main thread (the dispatcher and listener hop to it before
 * calling in), so plain HashMaps are safe.
 */
public final class NpcManager {

    /** Nearest spawned NPC to a player, with its distance in blocks. */
    public record NearestNpc(String npcId, double distance) {
    }

    private final Plugin plugin;
    private final double radius;
    private final boolean debugWireLogging;

    // npc_id -> Citizens NPC. Rebuilt from the registry on enable.
    private final Map<String, NPC> index = new HashMap<>();
    // npc_id -> last player who spoke to it (resolves the "direct" audience).
    private final Map<String, UUID> lastInteractor = new HashMap<>();

    public NpcManager(Plugin plugin, double radius, boolean debugWireLogging) {
        this.plugin = plugin;
        this.radius = radius;
        this.debugWireLogging = debugWireLogging;
    }

    /** Rebuild the id<->NPC index by scanning Citizens for the identity trait. */
    public void rebuildIndex() {
        index.clear();
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            IdentityTrait trait = npc.getTraitNullable(IdentityTrait.class);
            if (trait != null && trait.getNpcId() != null && !trait.getNpcId().isBlank()) {
                index.put(trait.getNpcId(), npc);
            }
        }
    }

    /** Spawn at the controller-supplied position. Idempotent (wire is at-least-once). */
    public void spawn(String npcId, String name, double x, double y, double z,
                      String worldId, String skinRef) {
        if (index.containsKey(npcId)) {
            return; // already present; ignore duplicate spawn
        }
        World world = resolveWorld(worldId);
        if (world == null) {
            plugin.getLogger().warning("Spawn skipped for " + npcId + ": world not found.");
            return;
        }
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, name);
        npc.getOrAddTrait(IdentityTrait.class).setNpcId(npcId);
        if (skinRef != null && !skinRef.isBlank()) {
            npc.getOrAddTrait(SkinTrait.class).setSkinName(skinRef);
        }
        npc.spawn(new Location(world, x, y, z)); // coordinates are never logged
        index.put(npcId, npc);
        plugin.getLogger().info("Spawned NPC " + npcId);
    }

    /** Despawn and forget. Idempotent. */
    public void despawn(String npcId) {
        NPC npc = index.remove(npcId);
        lastInteractor.remove(npcId);
        if (npc != null) {
            npc.destroy();
            plugin.getLogger().info("Despawned NPC " + npcId);
        }
    }

    /** Nearest spawned NPC within radius, same world. Empty = proximity filter drops the event. */
    public Optional<NearestNpc> findNearest(Player player) {
        Location ploc = player.getLocation();
        String bestId = null;
        double best = Double.MAX_VALUE;
        for (Map.Entry<String, NPC> entry : index.entrySet()) {
            NPC npc = entry.getValue();
            if (debugWireLogging) {
                plugin.getLogger().info("findNearest: spawned=" + npc.isSpawned()
                        + " entity=" + (npc.getEntity() == null ? "null" : "present"));
            }
            if (!npc.isSpawned() || npc.getEntity() == null) {
                continue; // not fully materialised yet (Citizens async lifecycle)
            }
            Location nloc = npc.getEntity().getLocation();
            if (nloc.getWorld() == null || !nloc.getWorld().equals(ploc.getWorld())) {
                continue;
            }
            double d = nloc.distance(ploc);
            if (debugWireLogging) {
                plugin.getLogger().info("findNearest: dist=" + d + " radius=" + radius);
            }
            if (d <= radius && d < best) {
                best = d;
                bestId = entry.getKey();
            }
        }
        return bestId == null ? Optional.empty() : Optional.of(new NearestNpc(bestId, best));
    }

    public void recordInteractor(String npcId, UUID playerUuid) {
        lastInteractor.put(npcId, playerUuid);
    }

    /** Resolve the audience for npc_say / npc_thinking from the audience scope. */
    public List<Player> resolveAudience(String npcId, String audience) {
        switch (audience) {
            case "broadcast":
                return new ArrayList<>(Bukkit.getOnlinePlayers());
            case "direct": {
                UUID uuid = lastInteractor.get(npcId);
                Player p = uuid == null ? null : Bukkit.getPlayer(uuid);
                return p == null ? List.of() : List.of(p);
            }
            case "nearby": {
                NPC npc = index.get(npcId);
                if (npc == null || !npc.isSpawned() || npc.getEntity().getLocation().getWorld() == null) {
                    return List.of();
                }
                Location loc = npc.getEntity().getLocation();
                List<Player> out = new ArrayList<>();
                for (Player p : loc.getWorld().getPlayers()) {
                    if (p.getLocation().distance(loc) <= radius) {
                        out.add(p);
                    }
                }
                return out;
            }
            default:
                return List.of();
        }
    }

    /** Display name for rendering; falls back if the NPC is unknown. */
    public String displayName(String npcId) {
        NPC npc = index.get(npcId);
        return npc != null ? npc.getName() : "NPC";
    }

    private World resolveWorld(String worldId) {
        try {
            World w = Bukkit.getWorld(UUID.fromString(worldId));
            if (w != null) {
                return w;
            }
        } catch (IllegalArgumentException ignored) {
            // not a UUID -> fall through to name lookup (eases manual testing)
        }
        return Bukkit.getWorld(worldId);
    }
}
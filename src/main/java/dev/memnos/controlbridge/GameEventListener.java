package dev.memnos.controlbridge;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;

/**
 * Translates Bukkit events into outbound wire messages. Player chat is
 * proximity-filtered (ADR-002 E4): only chat within radius of an NPC is sent.
 * No player data (uuid, message, distance) is ever logged.
 */
public final class GameEventListener implements Listener {

    private final Plugin plugin;
    private final BridgeClient client;
    private final NpcManager npcManager;

    public GameEventListener(Plugin plugin, BridgeClient client, NpcManager npcManager) {
        this.plugin = plugin;
        this.client = client;
        this.npcManager = npcManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        client.send(WireSender.playerJoin(event.getPlayer().getUniqueId()));
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        // AsyncChatEvent fires off the main thread; Citizens/location reads must
        // be on the main thread. We do not cancel the event (normal chat stays).
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Optional<NpcManager.NearestNpc> nearest = npcManager.findNearest(player);
            if (nearest.isEmpty()) {
                return; // outside any NPC's radius -> never sent (cost discipline)
            }
            NpcManager.NearestNpc target = nearest.get();
            npcManager.recordInteractor(target.npcId(), uuid);
            client.send(WireSender.playerChat(uuid, target.npcId(), text, target.distance()));
        });
    }
}
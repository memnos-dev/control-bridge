package dev.memnos.controlbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Renders clickable dialogue choices. Each option carries a server-side click
 * callback (no registered command) that sends player_choice back with the
 * stable choice_id and the presented_at captured at render time.
 */
public final class ChoiceRenderer {

    /** One option as received from the controller. */
    public record Choice(String choiceId, String displayText) {
    }

    private final Plugin plugin;
    private final BridgeClient client;

    public ChoiceRenderer(Plugin plugin, BridgeClient client) {
        this.plugin = plugin;
        this.client = client;
    }

    /** Runs on the main thread (dispatcher hops there). playerId is the MC UUID. */
    public void present(String npcId, String playerId, String prompt, List<Choice> options) {
        Player player = resolvePlayer(playerId);
        if (player == null) {
            return; // player offline; nothing to show
        }
        Instant presentedAt = Instant.now();
        player.sendMessage(Component.text(prompt));
        for (Choice choice : options) {
            Component line = Component.text("» " + choice.displayText())
                    .clickEvent(ClickEvent.callback(audience ->
                            client.send(WireSender.playerChoice(
                                    playerId, npcId, choice.choiceId(), presentedAt))));
            player.sendMessage(line);
        }
    }

    private Player resolvePlayer(String playerId) {
        try {
            return Bukkit.getPlayer(UUID.fromString(playerId));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
package dev.memnos.controlbridge;

import com.google.gson.JsonObject;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Manages the single outbound WebSocket connection to the controller: connect,
 * handshake on open, dispatch inbound commands, reconnect on drop, and send
 * outbound messages. Holds no game logic.
 */
public final class BridgeClient {

    private final Plugin plugin;
    private final BridgeConfig config;
    private final String worldId;
    private final String adapterVersion;
    private static final int CLOSE_AUTH_REJECTED = 4401;
    private java.util.function.Supplier<java.util.Set<String>> npcIdsSupplier;
    private volatile boolean indexReady = false;

    private CommandDispatcher dispatcher;
    private WebSocketClient socket;
    private BukkitTask reconnectTask;
    private volatile boolean shuttingDown = false;

    public BridgeClient(Plugin plugin, BridgeConfig config, String worldId, String adapterVersion) {
        this.plugin = plugin;
        this.config = config;
        this.worldId = worldId;
        this.adapterVersion = adapterVersion;
    }

    /** Wire the dispatcher before connecting (breaks the construction cycle). */
    public void attach(CommandDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** Wire the npc_report source before connecting (same cycle-breaker as attach). */
    public void attachNpcReportSource(java.util.function.Supplier<java.util.Set<String>> supplier) {
        this.npcIdsSupplier = supplier;
    }

    /** Open the connection. Safe to call repeatedly (reconnect path). */
    public void connect() {
        if (shuttingDown) {
            return;
        }
        // Close any previous socket before opening a new one. Without this, a
        // reconnect can leave an old connection alive alongside the new one,
        // causing every inbound message to be dispatched twice.
        if (socket != null) {
            socket.close();
            socket = null;
        }
        final URI uri;
        try {
            uri = new URI(config.controllerUrl());
        } catch (URISyntaxException e) {
            plugin.getLogger().severe("Invalid controller-url; bridge will not connect.");
            return;
        }

        socket = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                plugin.getLogger().info("Connected to controller; sending handshake.");
                BridgeClient.this.send(WireSender.handshake(config.authToken(), worldId, adapterVersion));
                // Connect reports are gated on the NPC index (see sendConnectReports):
                // at server start Citizens loads after connect, so the Citizens-enable
                // handler triggers them; on a runtime reconnect the index is long ready
                // and this call sends them immediately.
                BridgeClient.this.sendConnectReports();
            }

            @Override
            public void onMessage(String message) {
                if (dispatcher != null) {
                    dispatcher.onWireMessage(message);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                if (code == CLOSE_AUTH_REJECTED) {
                    plugin.getLogger().severe(
                            "Auth token rejected (close code 4401). "
                                    + "Check authToken in config.yml -- if the token was revoked, "
                                    + "mint a new one. NOT reconnecting; fix the config, then "
                                    + "restart the server or reload the plugin.");
                    return;
                }
                plugin.getLogger().warning("Controller connection closed; scheduling reconnect.");
                scheduleReconnect();
            }

            @Override
            public void onError(Exception ex) {
                // Do not log the exception message verbatim - it may contain the URL.
                plugin.getLogger().warning("Controller connection error; will retry.");
            }
        };
        socket.connect();
    }

    /** Extract the message type for logging WITHOUT exposing payload/player data. */
    private static String wireType(JsonObject msg) {
        if (msg.has("command")) {
            return msg.get("command").getAsString();
        }
        if (msg.has("event")) {
            return msg.get("event").getAsString();
        }
        if (msg.has("type")) {
            return msg.get("type").getAsString();
        }
        return "unknown";
    }

    /** Send an enveloped wire message. Type-only logging (no payload/player data). */
    public void send(JsonObject msg) {
        WebSocketClient s = socket;
        boolean open = s != null && s.isOpen();
        if (config.debugWireLogging()) {
            plugin.getLogger().info("WIRE OUT: " + wireType(msg) + " open=" + open);
        }
        if (open) {
            s.send(msg.toString());
        } else {
            plugin.getLogger().warning("WIRE OUT DROPPED: " + wireType(msg)
                    + " socket=" + (s == null ? "null" : "closed"));
        }
    }

    private void scheduleReconnect() {
        if (shuttingDown) {
            return;
        }
        long delayTicks = config.reconnectDelaySeconds() * 20L;
        reconnectTask = plugin.getServer().getScheduler()
                .runTaskLaterAsynchronously(plugin, this::connect, delayTicks);
    }

    /** Close the connection and stop reconnecting. */
    public void shutdown() {
        shuttingDown = true;
        if (reconnectTask != null) {
            reconnectTask.cancel();
            reconnectTask = null;
        }
        if (socket != null) {
            socket.close();
            socket = null;
        }
    }

    /** Send the connect reports once both conditions hold: socket open AND NPC index built.
     *  Called from onOpen (reconnect during runtime) and from the Citizens-enable handler
     *  (server start, where Citizens loads after connect). Idempotent by wire semantics:
     *  a duplicate report yields an empty diff core-side (ADR-008). */
    public void sendConnectReports() {
        WebSocketClient s = socket;
        if (!indexReady || s == null || !s.isOpen()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            send(WireSender.capabilityReport(CapabilityScanner.scan()));
            if (npcIdsSupplier != null) {
                send(WireSender.npcReport(npcIdsSupplier.get()));
            }
        });
    }

    /** The NPC index is built; reports may now reflect reality. */
    public void markIndexReady() {
        this.indexReady = true;
        sendConnectReports();
    }
}
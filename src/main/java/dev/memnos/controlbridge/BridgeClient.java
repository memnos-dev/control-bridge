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
            }

            @Override
            public void onMessage(String message) {
                if (dispatcher != null) {
                    dispatcher.onWireMessage(message);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
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
}
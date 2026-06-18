package dev.memnos.controlbridge;

import com.google.gson.JsonObject;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.UUID;

/**
 * Manages the single outbound WebSocket connection to the controller.
 *
 * Responsibilities in this slice: connect, send the handshake on open,
 * and reconnect after a drop. Inbound command dispatch and game actions
 * are added in the next slice.
 */
public final class BridgeClient {

    private static final int SCHEMA_VERSION = 1;

    private final Plugin plugin;
    private final BridgeConfig config;
    private final String worldId;
    private final String adapterVersion;

    private WebSocketClient socket;
    private BukkitTask reconnectTask;
    private volatile boolean shuttingDown = false;

    public BridgeClient(Plugin plugin, BridgeConfig config, String worldId, String adapterVersion) {
        this.plugin = plugin;
        this.config = config;
        this.worldId = worldId;
        this.adapterVersion = adapterVersion;
    }

    /** Open the connection. Safe to call repeatedly (reconnect path). */
    public void connect() {
        if (shuttingDown) {
            return;
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
                sendHandshake();
            }

            @Override
            public void onMessage(String message) {
                // Inbound command dispatch is implemented in the next slice.
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                plugin.getLogger().warning("Controller connection closed; scheduling reconnect.");
                scheduleReconnect();
            }

            @Override
            public void onError(Exception ex) {
                // Do not log the exception message verbatim — it may contain the URL.
                plugin.getLogger().warning("Controller connection error; will retry.");
            }
        };
        socket.connect();
    }

    private void sendHandshake() {
        JsonObject msg = new JsonObject();
        msg.addProperty("schema_version", SCHEMA_VERSION);
        msg.addProperty("msg_id", UUID.randomUUID().toString());
        msg.addProperty("ts", Instant.now().toString());
        msg.addProperty("type", "plugin");
        msg.addProperty("auth_token", config.authToken());
        msg.addProperty("world_id", worldId);
        msg.addProperty("adapter_version", adapterVersion);
        // auth_token is part of the payload but is never written to any log.
        socket.send(msg.toString());
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
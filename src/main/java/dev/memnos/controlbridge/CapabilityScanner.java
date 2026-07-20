package dev.memnos.controlbridge;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Assembles the instance capability report (ADR-031-N1). Strings mirror the
 * memnos Capability enum values (core/domain/capability.py) verbatim.
 *
 * v0: every capability this plugin serves depends only on Paper + Citizens,
 * and Citizens is a hard dependency (plugin.yml: depend: [Citizens]) — if this
 * code runs, both are present. The scan is therefore constant today; this
 * class exists as the seam for conditional capabilities.
 *
 * Contract-depth rule (ADR-031-N1): a capability is reported only when the
 * providing CONTRACT is resolvable now, never on mere plugin presence.
 * Example seam (NOT active — first real case will be Vault/economy):
 *
 *   RegisteredServiceProvider<Economy> rsp =
 *       Bukkit.getServicesManager().getRegistration(Economy.class);
 *   if (rsp != null) caps.add("economy_transfer");
 *
 * Presence checks (getPluginManager().getPlugin("Vault") != null) are
 * forbidden here: Vault without an economy provider is present and useless —
 * a presence scan would report a capability that does not exist.
 * Conditional checks must run on the server main thread.
 */
final class CapabilityScanner {

    private CapabilityScanner() {
    }

    /** Capabilities this plugin instance can serve, contract-depth verified. */
    static Set<String> scan() {
        Set<String> caps = new LinkedHashSet<>();
        // Hard-dependency block: guaranteed by depend: [Citizens].
        caps.add("chat_send");
        caps.add("chat_receive");
        caps.add("npc_spawn");
        caps.add("npc_despawn");
        caps.add("npc_move");
        caps.add("npc_place");
        caps.add("world_query");
        caps.add("world_scan");
        caps.add("item_transfer");
        caps.add("present_choices");
        caps.add("disclosure_notice");
        // NOT reported: player_info (no handler).
        // Conditional capabilities go below, contract-depth checked (see class doc).
        return caps;
    }
}
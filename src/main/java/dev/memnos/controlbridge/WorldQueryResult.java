package dev.memnos.controlbridge;

import java.util.List;

/**
 * In-memory snapshot carrier for a world_query (CC-WO-03). NOT a wire DTO —
 * WireSender.worldQueryResult serializes it (wire correlates on correlation_id and
 * forbids extra fields, so no discriminator). minute_of_day is the canonical, game-
 * neutral 24h clock; the Python Core derives the day phase (CC-WO-01).
 */
public record WorldQueryResult(int minuteOfDay, List<NearbyPlayer> nearbyPlayers,
                               double npcX, double npcY, double npcZ) {

    public record NearbyPlayer(String playerId, double distance, double x, double y, double z) {
    }

    /**
     * Canonical 24h clock minute (0..1439) from MC world time-of-day (0..23999).
     * MC tick 0 = 06:00. 0.06 = 1440/24000; +360 = 06:00 offset (ADR-002 E3). Pure.
     */
    public static int minuteOfDay(long worldTime) {
        long tod = ((worldTime % 24000L) + 24000L) % 24000L;
        long minutes = (long) Math.floor(tod * 0.06 + 360.0);
        return (int) (((minutes % 1440L) + 1440L) % 1440L);
    }
}
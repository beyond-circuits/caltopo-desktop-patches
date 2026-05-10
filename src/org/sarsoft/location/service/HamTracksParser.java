package org.sarsoft.location.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.sarsoft.base.geometry.CTPoint;
import org.sarsoft.location.LocationReport;

/**
 * Parses HamTracks route packets (APRS experimental type {{X$HT).
 *
 * Packet format:
 *   CALLSIGN>DEST,...:{{X$HT,<unix_ts>,<geohash9>,<dt1>,<suffix1>,<dt2>,<suffix2>,...
 *
 * The 9-char geohash encodes the current position. Historical positions share a
 * common prefix with the current geohash; only the trailing suffix is transmitted.
 * Suffix length is constant within a packet: prefix = geohash9[0 : 9-suffix.length()].
 * dt values are seconds before the packet timestamp (larger dt = older point).
 *
 * On each packet, only positions newer than the last injected timestamp for that
 * callsign are published, oldest first. This correctly handles any beacon interval
 * and any number of recorded points between beacons.
 */
class HamTracksParser {

    private static final String PACKET_PREFIX = "{{X$HT,";
    private static final String GEOHASH_CHARS = "0123456789bcdefghjkmnpqrstuvwxyz";

    private static final Map<String, Long> lastInjectedTs = new HashMap<>();

    static boolean isHamTracksPacket(String line) {
        int colon = line.indexOf(':');
        return colon >= 0 && line.startsWith(PACKET_PREFIX, colon + 1);
    }

    static List<LocationReport> parsePacket(String line) {
        List<LocationReport> reports = new ArrayList<>();

        int gt = line.indexOf('>');
        int colon = line.indexOf(':');
        if (gt < 0 || colon < 0 || gt >= colon) return reports;

        String callsign = line.substring(0, gt);
        String payload = line.substring(colon + 1 + PACKET_PREFIX.length());
        String[] fields = payload.split(",");

        // fields[0] = unix timestamp (seconds), fields[1] = 9-char current geohash,
        // fields[2+2i], fields[3+2i] = dt_i, suffix_i pairs
        if (fields.length < 2) return reports;

        long unixTs;
        try {
            unixTs = Long.parseLong(fields[0]);
        } catch (NumberFormatException e) {
            return reports;
        }

        String currentGeohash = fields[1];
        if (currentGeohash.length() != 9) return reports;

        // Collect all (timestamp, geohash) pairs from history and current position.
        List<Long> timestamps = new ArrayList<>();
        List<String> geohashes = new ArrayList<>();

        int pairCount = (fields.length - 2) / 2;
        for (int i = 0; i < pairCount; i++) {
            try {
                long dt = Long.parseLong(fields[2 + i * 2]);
                String suffix = fields[3 + i * 2];
                if (suffix.isEmpty() || suffix.length() >= 9) continue;
                String prefix = currentGeohash.substring(0, 9 - suffix.length());
                timestamps.add(unixTs - dt);
                geohashes.add(prefix + suffix);
            } catch (NumberFormatException e) {
                // skip malformed pair
            }
        }
        timestamps.add(unixTs);
        geohashes.add(currentGeohash);

        // Sort by timestamp ascending so we inject oldest-first.
        Integer[] order = new Integer[timestamps.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Long.compare(timestamps.get(a), timestamps.get(b)));

        // Inject only positions newer than the last one we already sent.
        long threshold = lastInjectedTs.containsKey(callsign)
                ? lastInjectedTs.get(callsign)
                : Long.MIN_VALUE;
        String deviceId = "LOCAL:" + callsign;

        for (int i : order) {
            if (timestamps.get(i) <= threshold) continue;
            CTPoint pt = decodeGeohash(geohashes.get(i));
            if (pt != null) {
                reports.add(new LocationReport(deviceId, pt.withTime(System.currentTimeMillis())));
            }
        }

        lastInjectedTs.put(callsign, unixTs);
        return reports;
    }

    private static CTPoint decodeGeohash(String geohash) {
        double minLat = -90, maxLat = 90, minLng = -180, maxLng = 180;
        boolean isLng = true;
        for (char c : geohash.toCharArray()) {
            int val = GEOHASH_CHARS.indexOf(c);
            if (val < 0) return null;
            for (int i = 4; i >= 0; i--) {
                int bit = (val >> i) & 1;
                if (isLng) {
                    double mid = (minLng + maxLng) / 2;
                    if (bit == 1) minLng = mid; else maxLng = mid;
                } else {
                    double mid = (minLat + maxLat) / 2;
                    if (bit == 1) minLat = mid; else maxLat = mid;
                }
                isLng = !isLng;
            }
        }
        return new CTPoint((minLng + maxLng) / 2, (minLat + maxLat) / 2);
    }
}

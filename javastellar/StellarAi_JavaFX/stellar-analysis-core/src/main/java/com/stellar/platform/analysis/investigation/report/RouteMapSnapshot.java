package com.stellar.platform.analysis.investigation.report;

/**
 * Numeric summary of one already-built route. No tile URLs, credentials, or measurement paths.
 */
public record RouteMapSnapshot(
        long startNanos,
        long endNanos,
        int sampleCount,
        Double distanceMeters,
        double south,
        double north,
        double west,
        double east,
        boolean imageAvailable
) {
    public boolean covers(double latitude, double longitude) {
        return latitude >= south && latitude <= north && longitude >= west && longitude <= east;
    }

    public String toJson() {
        return "{\"startNanos\":" + startNanos
                + ",\"endNanos\":" + endNanos
                + ",\"sampleCount\":" + sampleCount
                + ",\"distanceMeters\":" + (distanceMeters == null ? "null" : Double.toString(distanceMeters))
                + ",\"south\":" + south
                + ",\"north\":" + north
                + ",\"west\":" + west
                + ",\"east\":" + east
                + ",\"imageAvailable\":" + imageAvailable
                + "}";
    }

    public static RouteMapSnapshot parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            long start = longField(json, "startNanos");
            long end = longField(json, "endNanos");
            int count = (int) longField(json, "sampleCount");
            Double distance = doubleOrNull(json, "distanceMeters");
            double south = doubleField(json, "south");
            double north = doubleField(json, "north");
            double west = doubleField(json, "west");
            double east = doubleField(json, "east");
            boolean image = json.contains("\"imageAvailable\":true");
            return new RouteMapSnapshot(start, end, count, distance, south, north, west, east, image);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static long longField(String json, String name) {
        return (long) doubleField(json, name);
    }

    private static double doubleField(String json, String name) {
        Double value = doubleOrNull(json, name);
        if (value == null) {
            throw new IllegalArgumentException(name);
        }
        return value;
    }

    private static Double doubleOrNull(String json, String name) {
        String token = "\"" + name + "\":";
        int at = json.indexOf(token);
        if (at < 0) {
            return null;
        }
        int start = at + token.length();
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        String raw = json.substring(start, end).trim();
        if (raw.equals("null") || raw.isEmpty()) {
            return null;
        }
        return Double.parseDouble(raw);
    }
}

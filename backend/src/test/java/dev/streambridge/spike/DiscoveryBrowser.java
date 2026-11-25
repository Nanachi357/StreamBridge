package dev.streambridge.spike;

/** Browser identity is an allowlisted launch choice, never an executable supplied by HTTP. */
enum DiscoveryBrowser {
    EDGE("msedge"), CHROME("chrome");

    final String channel;
    DiscoveryBrowser(String channel) { this.channel = channel; }

    static DiscoveryBrowser parse(String value) {
        return switch (value) {
            case "edge" -> EDGE;
            case "chrome" -> CHROME;
            default -> throw new IllegalArgumentException("UNSUPPORTED_BROWSER");
        };
    }

    static DiscoveryBrowser fromUserAgent(String agent) {
        if (agent != null && agent.contains("Windows NT")) {
            if (agent.contains("Edg/")) return EDGE;
            if (agent.contains("Chrome/") && !agent.contains("OPR/") && !agent.contains("Vivaldi/")) return CHROME;
        }
        throw new IllegalArgumentException("UNSUPPORTED_BROWSER");
    }

    String argument() { return name().toLowerCase(java.util.Locale.ROOT); }
}

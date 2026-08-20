package dev.deepclient;

import java.net.InetAddress;
import java.net.UnknownHostException;

public record DeepClientConfig(
        String bindAddress,
        int port,
        String token,
        int requestTimeoutSeconds,
        int streamFps,
        int nearbyEntityLimit
) {
    public static DeepClientConfig fromEnvironment() {
        String bindAddress = env("DEEPCLIENT_BIND", "127.0.0.1");
        int port = envInt("DEEPCLIENT_PORT", 8080, 1, 65535);
        String token = System.getenv().getOrDefault("DEEPCLIENT_TOKEN", "");

        if (!isLoopback(bindAddress) && token.isBlank()) {
            throw new IllegalStateException(
                    "DEEPCLIENT_TOKEN is required when DEEPCLIENT_BIND is not a loopback address");
        }

        return new DeepClientConfig(
                bindAddress,
                port,
                token,
                envInt("DEEPCLIENT_REQUEST_TIMEOUT_SECONDS", 10, 1, 120),
                envInt("DEEPCLIENT_STREAM_FPS", 4, 1, 20),
                envInt("DEEPCLIENT_NEARBY_ENTITY_LIMIT", 64, 1, 256));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int envInt(String name, int fallback, int min, int max) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw);
            if (value < min || value > max) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer from " + min + " to " + max);
        }
    }

    private static boolean isLoopback(String address) {
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }
}

package dev.deepclient;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;

public final class DeepClientHttpServer {
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final byte[] STREAM_BOUNDARY = "--deepclient-frame\r\n".getBytes(StandardCharsets.US_ASCII);

    private final DeepClientConfig config;
    private final DeepClientController controller;
    private HttpServer server;

    public DeepClientHttpServer(DeepClientConfig config, DeepClientController controller) {
        this.config = config;
        this.controller = controller;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(config.bindAddress(), config.port()), 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not bind Deep Client HTTP server", exception);
        }
        server.createContext("/v1/health", exchange -> route(exchange, false, this::health));
        server.createContext("/openapi.yaml", exchange -> route(exchange, false, this::openApi));
        server.createContext("/v1/state", exchange -> route(exchange, true, this::state));
        server.createContext("/v1/capabilities", exchange -> route(exchange, true, this::capabilities));
        server.createContext("/v1/player/position", exchange -> route(exchange, true, this::position));
        server.createContext("/v1/players", exchange -> route(exchange, true, this::players));
        server.createContext("/v1/screen", exchange -> route(exchange, true, this::screen));
        server.createContext("/v1/world/block", exchange -> route(exchange, true, this::block));
        server.createContext("/v1/chat", exchange -> route(exchange, true, this::chat));
        server.createContext("/v1/navigation/walk-to", exchange -> route(exchange, true, this::walkTo));
        server.createContext("/v1/navigation", exchange -> route(exchange, true, this::navigation));
        server.createContext("/v1/screenshot", exchange -> route(exchange, true, this::screenshot));
        server.createContext("/v1/stream.mjpeg", exchange -> route(exchange, true, this::stream));
        server.createContext("/v1/actions", exchange -> route(exchange, true, this::action));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public void stop() {
        if (server != null) server.stop(1);
    }

    private void route(HttpExchange exchange, boolean requiresAuth, Handler handler) throws IOException {
        addCommonHeaders(exchange.getResponseHeaders());
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if (requiresAuth && !authenticated(exchange)) {
            sendError(exchange, 401, "unauthorized", "A valid Bearer token is required");
            return;
        }
        try {
            handler.handle(exchange);
        } catch (IllegalArgumentException exception) {
            sendError(exchange, 400, "bad_request", exception.getMessage());
        } catch (IllegalStateException exception) {
            sendError(exchange, 409, "invalid_state", exception.getMessage());
        } catch (Exception exception) {
            DeepClientMod.LOGGER.error("Deep Client request failed", exception);
            sendError(exchange, 500, "internal_error", exception.getMessage());
        }
    }

    private void health(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "GET");
        JsonObject body = new JsonObject();
        body.addProperty("ok", true);
        body.addProperty("service", DeepClientMod.MOD_ID);
        body.addProperty("time", Instant.now().toString());
        sendJson(exchange, 200, body);
    }

    private void state(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        sendJson(exchange, 200, controller.state());
    }

    private void capabilities(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "GET");
        sendJson(exchange, 200, controller.capabilities());
    }

    private void players(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        sendJson(exchange, 200, controller.players());
    }

    private void screen(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        sendJson(exchange, 200, controller.screenState());
    }

    private void block(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        Map<String, String> query = query(exchange);
        JsonObject request = new JsonObject();
        request.addProperty("x", requiredIntegerQuery(query, "x"));
        request.addProperty("y", requiredIntegerQuery(query, "y"));
        request.addProperty("z", requiredIntegerQuery(query, "z"));
        sendJson(exchange, 200, controller.block(request));
    }

    private void position(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        sendJson(exchange, 200, controller.position());
    }

    private void chat(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        Map<String, String> query = query(exchange);
        int limit = integerQuery(query, "limit", 20, 1, 100);
        long after = longQuery(query, "after", 0);
        sendJson(exchange, 200, controller.recentMessages(limit, after));
    }

    private void walkTo(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        sendJson(exchange, 202, controller.walkTo(readJsonBody(exchange)));
    }

    private void navigation(HttpExchange exchange) throws Exception {
        switch (exchange.getRequestMethod()) {
            case "GET" -> sendJson(exchange, 200, controller.navigationStatus());
            case "DELETE" -> sendJson(exchange, 200, controller.cancelNavigation());
            default -> throw new IllegalArgumentException("Expected GET or DELETE request");
        }
    }

    private void openApi(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "GET");
        byte[] body;
        try (var input = DeepClientHttpServer.class.getResourceAsStream("/openapi.yaml")) {
            if (input == null) throw new IllegalStateException("Bundled API contract is missing");
            body = input.readAllBytes();
        }
        exchange.getResponseHeaders().set("Content-Type", "application/yaml; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private void screenshot(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        byte[] png = controller.screenshot();
        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, png.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(png);
        }
    }

    private void action(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        sendJson(exchange, 200, controller.perform(readJsonBody(exchange)));
    }

    private static JsonObject readJsonBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) throw new IllegalArgumentException("Request body is too large");
        JsonObject request;
        try {
            request = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Request body must be a JSON object");
        }
        return request;
    }

    private void stream(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        byte[] firstFrame = toJpeg(controller.screenshot());
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "multipart/x-mixed-replace; boundary=deepclient-frame");
        headers.set("Cache-Control", "no-store, no-cache, must-revalidate");
        exchange.sendResponseHeaders(200, 0);
        long frameDelayMillis = 1000L / config.streamFps();

        try (OutputStream output = exchange.getResponseBody()) {
            byte[] jpeg = firstFrame;
            while (true) {
                long started = System.nanoTime();
                output.write(STREAM_BOUNDARY);
                output.write(("Content-Type: image/jpeg\r\nContent-Length: " + jpeg.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                output.write(jpeg);
                output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
                Thread.sleep(Math.max(1, frameDelayMillis - elapsedMillis));
                jpeg = toJpeg(controller.screenshot());
            }
        } catch (IOException ignored) {
            // The viewer closed the stream.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            DeepClientMod.LOGGER.warn("Framebuffer stream stopped", exception);
        }
    }

    static byte[] toJpeg(byte[] png) throws IOException {
        var image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) throw new IOException("Framebuffer PNG could not be decoded");
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(png.length / 2);
        if (!ImageIO.write(rgb, "jpeg", output)) throw new IOException("No JPEG encoder is available");
        return output.toByteArray();
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> result = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) return result;
        for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }

    private static int integerQuery(Map<String, String> query, String name, int fallback, int min, int max) {
        if (!query.containsKey(name)) return fallback;
        try {
            int value = Integer.parseInt(query.get(name));
            if (value < min || value > max) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer from " + min + " to " + max);
        }
    }

    private static long longQuery(Map<String, String> query, String name, long fallback) {
        if (!query.containsKey(name)) return fallback;
        try {
            long value = Long.parseLong(query.get(name));
            if (value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a non-negative integer");
        }
    }

    private static int requiredIntegerQuery(Map<String, String> query, String name) {
        if (!query.containsKey(name)) throw new IllegalArgumentException("Missing query parameter: " + name);
        try {
            return Integer.parseInt(query.get(name));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }

    private boolean authenticated(HttpExchange exchange) {
        if (config.token().isBlank()) return true;
        String provided = exchange.getRequestHeaders().getFirst("Authorization");
        if (provided == null || !provided.startsWith("Bearer ")) return false;
        return MessageDigest.isEqual(
                config.token().getBytes(StandardCharsets.UTF_8),
                provided.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8));
    }

    private static void requireMethod(HttpExchange exchange, String expected) {
        if (!expected.equals(exchange.getRequestMethod())) {
            throw new IllegalArgumentException("Expected " + expected + " request");
        }
    }

    private static void sendJson(HttpExchange exchange, int status, JsonObject value) throws IOException {
        byte[] body = GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        JsonObject error = new JsonObject();
        error.addProperty("ok", false);
        error.addProperty("error", code);
        error.addProperty("message", message == null ? code : message);
        sendJson(exchange, status, error);
    }

    private static void addCommonHeaders(Headers headers) {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
        headers.set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        headers.set("X-Content-Type-Options", "nosniff");
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }
}

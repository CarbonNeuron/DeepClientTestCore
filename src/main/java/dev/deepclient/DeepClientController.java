package dev.deepclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.GlDebugInfo;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.util.Deque;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public final class DeepClientController {
    private static final List<String> INPUT_NAMES = List.of(
            "forward", "back", "left", "right", "jump", "sneak", "sprint", "attack", "use", "drop",
            "inventory", "swap_hands", "pick", "chat", "player_list", "command", "social",
            "screenshot", "perspective", "smooth_camera", "fullscreen", "spectator_outlines", "advancements",
            "save_toolbar", "load_toolbar",
            "hotbar_1", "hotbar_2", "hotbar_3", "hotbar_4", "hotbar_5", "hotbar_6", "hotbar_7", "hotbar_8", "hotbar_9");

    private final DeepClientConfig config;
    private final DeepClientBaritone navigation = new DeepClientBaritone();
    private final Map<String, Boolean> heldInputs = new LinkedHashMap<>();
    private final Map<String, Integer> pulsedInputs = new LinkedHashMap<>();
    private final Deque<JsonObject> events = new ArrayDeque<>();
    private long clientTicks;
    private long eventSequence;
    private BlockPos breakingBlock;
    private Direction breakingSide;

    public DeepClientController(DeepClientConfig config) {
        this.config = config;
        INPUT_NAMES.forEach(name -> heldInputs.put(name, false));
    }

    public void onEndTick(MinecraftClient client) {
        clientTicks++;
        navigation.onTick(client, clientTicks);
        if (breakingBlock != null && client.player != null && client.interactionManager != null) {
            if (client.interactionManager.updateBlockBreakingProgress(breakingBlock, breakingSide)) {
                client.player.swingHand(Hand.MAIN_HAND);
            }
        }
        for (String name : INPUT_NAMES) {
            int remaining = pulsedInputs.getOrDefault(name, 0);
            key(client, name).setPressed(Boolean.TRUE.equals(heldInputs.get(name)) || remaining > 0);
            if (remaining == 1) pulsedInputs.remove(name);
            else if (remaining > 1) pulsedInputs.put(name, remaining - 1);
        }
    }

    public void recordMessage(String kind, String text, boolean overlay, String senderName, String senderUuid) {
        JsonObject event = new JsonObject();
        event.addProperty("sequence", ++eventSequence);
        event.addProperty("tick", clientTicks);
        event.addProperty("timestamp", Instant.now().toString());
        event.addProperty("kind", kind);
        event.addProperty("text", text);
        event.addProperty("overlay", overlay);
        if (senderName != null) event.addProperty("sender_name", senderName);
        if (senderUuid != null) event.addProperty("sender_uuid", senderUuid);
        events.addLast(event);
        while (events.size() > 100) events.removeFirst();
    }

    public JsonObject state() throws Exception {
        return onClientThread(this::buildState);
    }

    public JsonObject position() throws Exception {
        return onClientThread(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null) {
                throw new IllegalStateException("No player is currently in a world");
            }
            JsonObject result = vec(client.player.getPos());
            result.addProperty("block_x", client.player.getBlockX());
            result.addProperty("block_y", client.player.getBlockY());
            result.addProperty("block_z", client.player.getBlockZ());
            result.addProperty("yaw", client.player.getYaw());
            result.addProperty("pitch", client.player.getPitch());
            result.addProperty("dimension", client.world.getRegistryKey().getValue().toString());
            result.addProperty("on_ground", client.player.isOnGround());
            return result;
        });
    }

    public JsonObject recentMessages(int limit, long after) throws Exception {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be from 1 to 100");
        if (after < 0) throw new IllegalArgumentException("after must be zero or greater");
        return onClientThread(() -> {
            JsonArray messages = new JsonArray();
            events.stream()
                    .filter(event -> event.get("sequence").getAsLong() > after)
                    .skip(Math.max(0, events.stream()
                            .filter(event -> event.get("sequence").getAsLong() > after).count() - limit))
                    .forEach(event -> messages.add(event.deepCopy()));
            JsonObject result = new JsonObject();
            result.add("messages", messages);
            result.addProperty("count", messages.size());
            result.addProperty("latest_sequence", eventSequence);
            return result;
        });
    }

    public JsonObject walkTo(JsonObject request) throws Exception {
        return onClientThread(() -> {
            releaseAll();
            int radius = request.has("radius") ? request.get("radius").getAsInt() : 0;
            return navigation.walkTo(MinecraftClient.getInstance(), requiredInt(request, "x"),
                    requiredInt(request, "y"), requiredInt(request, "z"), radius, clientTicks);
        });
    }

    public JsonObject navigationStatus() throws Exception {
        return onClientThread(() -> navigation.status(MinecraftClient.getInstance(), clientTicks));
    }

    public JsonObject baritoneGoal(JsonObject request) throws Exception {
        return onClientThread(() -> {
            releaseAll();
            return navigation.setGoal(MinecraftClient.getInstance(), request, clientTicks);
        });
    }

    public JsonObject baritoneMine(JsonObject request) throws Exception {
        return onClientThread(() -> {
            releaseAll();
            return navigation.mine(MinecraftClient.getInstance(), request, clientTicks);
        });
    }

    public JsonObject baritoneGetToBlock(JsonObject request) throws Exception {
        return onClientThread(() -> {
            releaseAll();
            return navigation.getToBlock(MinecraftClient.getInstance(), request, clientTicks);
        });
    }

    public JsonObject baritoneFollow(JsonObject request) throws Exception {
        return onClientThread(() -> {
            releaseAll();
            return navigation.follow(MinecraftClient.getInstance(), request, clientTicks);
        });
    }

    public JsonObject baritoneFarm(JsonObject request) throws Exception {
        return onClientThread(() -> {
            releaseAll();
            return navigation.farm(MinecraftClient.getInstance(), request, clientTicks);
        });
    }

    public JsonObject baritoneExplore(JsonObject request) throws Exception {
        return onClientThread(() -> {
            releaseAll();
            return navigation.explore(MinecraftClient.getInstance(), request, clientTicks);
        });
    }

    public JsonObject baritoneClearArea(JsonObject request) throws Exception {
        return onClientThread(() -> {
            releaseAll();
            return navigation.clearArea(MinecraftClient.getInstance(), request, clientTicks);
        });
    }

    public JsonObject baritoneCommand(JsonObject request) throws Exception {
        return onClientThread(() -> {
            releaseAll();
            return navigation.executeCommand(MinecraftClient.getInstance(), request, clientTicks);
        });
    }

    public JsonObject baritoneCommands() throws Exception {
        return onClientThread(() -> navigation.commands(MinecraftClient.getInstance()));
    }

    public JsonObject baritoneScan(JsonObject request) throws Exception {
        // Baritone's scanner may wait on chunk/cache workers and must not block the render thread.
        return navigation.scan(MinecraftClient.getInstance(), request);
    }

    public JsonObject baritoneSettings() throws Exception {
        return onClientThread(navigation::settings);
    }

    public JsonObject updateBaritoneSettings(JsonObject request) throws Exception {
        return onClientThread(() -> navigation.updateSettings(request));
    }

    public JsonObject baritoneWaypoints() throws Exception {
        return onClientThread(() -> navigation.waypoints(MinecraftClient.getInstance()));
    }

    public JsonObject addBaritoneWaypoint(JsonObject request) throws Exception {
        return onClientThread(() -> navigation.addWaypoint(MinecraftClient.getInstance(), request));
    }

    public JsonObject removeBaritoneWaypoint(JsonObject request) throws Exception {
        return onClientThread(() -> navigation.removeWaypoint(MinecraftClient.getInstance(), request));
    }

    public JsonObject navigateBaritoneWaypoint(JsonObject request) throws Exception {
        return onClientThread(() -> {
            releaseAll();
            return navigation.navigateWaypoint(MinecraftClient.getInstance(), request, clientTicks);
        });
    }

    public JsonObject baritonePath(int limit) throws Exception {
        return onClientThread(() -> navigation.path(MinecraftClient.getInstance(), limit));
    }

    public JsonObject screenState() throws Exception {
        return onClientThread(() -> buildScreenState(MinecraftClient.getInstance()));
    }

    public JsonObject players() throws Exception {
        return onClientThread(this::buildPlayers);
    }

    public JsonObject block(JsonObject request) throws Exception {
        return onClientThread(() -> {
            MinecraftClient client = requireWorld();
            BlockPos pos = blockPos(request);
            var state = client.world.getBlockState(pos);
            JsonObject result = blockPosition(pos);
            result.addProperty("id", Registries.BLOCK.getId(state.getBlock()).toString());
            result.addProperty("air", state.isAir());
            result.addProperty("solid", state.isSolidBlock(client.world, pos));
            result.addProperty("replaceable", state.isReplaceable());
            result.addProperty("luminance", state.getLuminance());
            return result;
        });
    }

    public JsonObject capabilities() {
        JsonObject result = new JsonObject();
        result.addProperty("api_version", "v1");
        JsonArray inputs = new JsonArray();
        INPUT_NAMES.forEach(inputs::add);
        result.add("inputs", inputs);
        JsonArray actions = new JsonArray();
        List.of("input", "pulse", "look", "look_at", "chat", "select_slot", "gui_click", "gui_move",
                "gui_scroll", "gui_drag", "gui_key", "gui_type", "open_inventory", "open_chat",
                "close_screen", "container_click", "container_button", "use_item", "stop_using_item", "use_block",
                "attack_entity", "interact_entity", "start_break_block", "stop_break_block", "swing_hand",
                "drop_item", "scroll_hotbar", "respawn", "connect", "disconnect", "release_all", "clear_events")
                .forEach(actions::add);
        result.add("actions", actions);
        JsonArray slotActions = new JsonArray();
        for (SlotActionType action : SlotActionType.values()) slotActions.add(action.name().toLowerCase());
        result.add("slot_actions", slotActions);
        JsonArray baritone = new JsonArray();
        List.of("goal", "mine", "get_to_block", "follow", "farm", "explore", "clear_area", "scan",
                "path", "settings", "waypoints", "commands", "command", "cancel").forEach(baritone::add);
        result.add("baritone", baritone);
        return result;
    }

    public JsonObject cancelNavigation() throws Exception {
        return onClientThread(() -> navigation.cancel(MinecraftClient.getInstance(), clientTicks));
    }

    public byte[] screenshot() throws Exception {
        return onClientThread(() -> {
            try (var image = ScreenshotRecorder.takeScreenshot(MinecraftClient.getInstance().getFramebuffer())) {
                return image.getBytes();
            } catch (Exception exception) {
                throw new IllegalStateException("Could not capture framebuffer", exception);
            }
        });
    }

    public JsonObject perform(JsonObject request) throws Exception {
        Objects.requireNonNull(request, "request");
        return onClientThread(() -> performOnClient(request));
    }

    public void releaseAll() {
        MinecraftClient client = MinecraftClient.getInstance();
        Runnable release = () -> {
            heldInputs.replaceAll((name, ignored) -> false);
            pulsedInputs.clear();
            INPUT_NAMES.forEach(name -> key(client, name).setPressed(false));
            if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
            breakingBlock = null;
            breakingSide = null;
        };
        if (client.isOnThread()) release.run();
        else client.execute(release);
    }

    private JsonObject performOnClient(JsonObject request) {
        MinecraftClient client = MinecraftClient.getInstance();
        String type = requiredString(request, "type");
        JsonObject response = new JsonObject();

        switch (type) {
            case "input" -> updateInput(client, request);
            case "pulse" -> pulseInput(request);
            case "look" -> updateLook(client, request);
            case "chat" -> sendChat(client, requiredString(request, "message"));
            case "select_slot" -> selectSlot(client, requiredInt(request, "slot"));
            case "scroll_hotbar" -> requirePlayer(client).getInventory()
                    .scrollInHotbar(requiredDouble(request, "amount"));
            case "gui_click" -> guiClick(client, request);
            case "gui_move" -> guiMove(client, request);
            case "gui_scroll" -> guiScroll(client, request);
            case "gui_drag" -> guiDrag(client, request);
            case "gui_key" -> guiKey(client, request);
            case "gui_type" -> guiType(client, requiredString(request, "text"), request.has("modifiers")
                    ? request.get("modifiers").getAsInt() : 0);
            case "close_screen" -> client.setScreen(null);
            case "open_inventory" -> openInventory(client);
            case "open_chat" -> client.setScreen(new ChatScreen(request.has("initial")
                    ? request.get("initial").getAsString() : ""));
            case "container_click" -> containerClick(client, request);
            case "container_button" -> containerButton(client, request);
            case "use_item" -> response.addProperty("result", useItem(client, request));
            case "stop_using_item" -> {
                if (client.interactionManager == null) throw new IllegalStateException("Interaction manager is unavailable");
                client.interactionManager.stopUsingItem(requirePlayer(client));
            }
            case "use_block" -> response.addProperty("result", useBlock(client, request));
            case "attack_entity" -> attackEntity(client, request);
            case "interact_entity" -> response.addProperty("result", interactEntity(client, request));
            case "start_break_block" -> startBreakBlock(client, request);
            case "stop_break_block" -> stopBreakBlock(client);
            case "swing_hand" -> requirePlayer(client).swingHand(hand(request));
            case "drop_item" -> response.addProperty("dropped", requirePlayer(client)
                    .dropSelectedItem(request.has("entire_stack") && request.get("entire_stack").getAsBoolean()));
            case "respawn" -> requirePlayer(client).requestRespawn();
            case "look_at" -> lookAt(client, request);
            case "clear_events" -> events.clear();
            case "release_all" -> releaseAll();
            case "connect" -> connect(client, requiredString(request, "address"));
            case "disconnect" -> disconnect(client);
            default -> throw new IllegalArgumentException("Unknown action type: " + type);
        }

        response.addProperty("ok", true);
        response.addProperty("type", type);
        response.addProperty("tick", clientTicks);
        return response;
    }

    private void updateInput(MinecraftClient client, JsonObject request) {
        JsonObject values = request.has("inputs") ? request.getAsJsonObject("inputs") : request;
        for (String name : INPUT_NAMES) {
            if (values.has(name)) heldInputs.put(name, values.get(name).getAsBoolean());
            key(client, name).setPressed(Boolean.TRUE.equals(heldInputs.get(name))
                    || pulsedInputs.getOrDefault(name, 0) > 0);
        }
    }

    private void pulseInput(JsonObject request) {
        String input = requiredString(request, "input");
        if (!INPUT_NAMES.contains(input)) throw new IllegalArgumentException("Unknown input: " + input);
        int ticks = request.has("ticks") ? request.get("ticks").getAsInt() : 1;
        if (ticks < 1 || ticks > 1200) throw new IllegalArgumentException("ticks must be from 1 to 1200");
        pulsedInputs.put(input, ticks);
        KeyBinding binding = key(MinecraftClient.getInstance(), input);
        KeyBinding.onKeyPressed(InputUtil.fromTranslationKey(binding.getBoundKeyTranslationKey()));
        binding.setPressed(true);
    }

    private static void updateLook(MinecraftClient client, JsonObject request) {
        if (client.player == null) throw new IllegalStateException("No player is currently in a world");
        float yaw = request.has("yaw")
                ? request.get("yaw").getAsFloat()
                : client.player.getYaw() + floatOr(request, "delta_yaw", 0);
        float pitch = request.has("pitch")
                ? request.get("pitch").getAsFloat()
                : client.player.getPitch() + floatOr(request, "delta_pitch", 0);
        client.player.setYaw(MathHelper.wrapDegrees(yaw));
        client.player.setPitch(MathHelper.clamp(pitch, -90, 90));
        client.player.setHeadYaw(client.player.getYaw());
    }

    private static void sendChat(MinecraftClient client, String message) {
        if (client.getNetworkHandler() == null) throw new IllegalStateException("Not connected to a server");
        if (message.startsWith("/")) client.getNetworkHandler().sendChatCommand(message.substring(1));
        else client.getNetworkHandler().sendChatMessage(message);
    }

    private static void selectSlot(MinecraftClient client, int slot) {
        if (client.player == null) throw new IllegalStateException("No player is currently in a world");
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("slot must be from 0 to 8");
        client.player.getInventory().selectedSlot = slot;
    }

    private static void guiClick(MinecraftClient client, JsonObject request) {
        if (client.currentScreen == null) throw new IllegalStateException("No GUI screen is currently open");
        double x = requiredDouble(request, "x");
        double y = requiredDouble(request, "y");
        int button = request.has("button") ? request.get("button").getAsInt() : 0;
        String action = request.has("action") ? request.get("action").getAsString() : "click";
        if (button < 0 || button > 2) throw new IllegalArgumentException("button must be from 0 to 2");
        switch (action) {
            case "press" -> client.currentScreen.mouseClicked(x, y, button);
            case "release" -> client.currentScreen.mouseReleased(x, y, button);
            case "click" -> {
                client.currentScreen.mouseClicked(x, y, button);
                client.currentScreen.mouseReleased(x, y, button);
            }
            default -> throw new IllegalArgumentException("gui_click action must be click, press, or release");
        }
    }

    private static void guiMove(MinecraftClient client, JsonObject request) {
        if (client.currentScreen == null) throw new IllegalStateException("No GUI screen is currently open");
        client.currentScreen.mouseMoved(requiredDouble(request, "x"), requiredDouble(request, "y"));
    }

    private static void guiScroll(MinecraftClient client, JsonObject request) {
        if (client.currentScreen == null) throw new IllegalStateException("No GUI screen is currently open");
        client.currentScreen.mouseScrolled(requiredDouble(request, "x"), requiredDouble(request, "y"),
                request.has("horizontal") ? request.get("horizontal").getAsDouble() : 0,
                requiredDouble(request, "vertical"));
    }

    private static void guiDrag(MinecraftClient client, JsonObject request) {
        if (client.currentScreen == null) throw new IllegalStateException("No GUI screen is currently open");
        int button = request.has("button") ? request.get("button").getAsInt() : 0;
        client.currentScreen.mouseDragged(requiredDouble(request, "x"), requiredDouble(request, "y"), button,
                requiredDouble(request, "delta_x"), requiredDouble(request, "delta_y"));
    }

    private static void guiKey(MinecraftClient client, JsonObject request) {
        if (client.currentScreen == null) throw new IllegalStateException("No GUI screen is currently open");
        int keycode = requiredInt(request, "keycode");
        int scancode = request.has("scancode") ? request.get("scancode").getAsInt() : 0;
        int modifiers = request.has("modifiers") ? request.get("modifiers").getAsInt() : 0;
        String action = request.has("action") ? request.get("action").getAsString() : "press";
        switch (action) {
            case "press" -> client.currentScreen.keyPressed(keycode, scancode, modifiers);
            case "release" -> client.currentScreen.keyReleased(keycode, scancode, modifiers);
            default -> throw new IllegalArgumentException("gui_key action must be press or release");
        }
    }

    private static void guiType(MinecraftClient client, String text, int modifiers) {
        if (client.currentScreen == null) throw new IllegalStateException("No GUI screen is currently open");
        text.codePoints().forEach(codePoint -> {
            if (Character.isBmpCodePoint(codePoint)) client.currentScreen.charTyped((char) codePoint, modifiers);
        });
    }

    private static void openInventory(MinecraftClient client) {
        client.setScreen(new InventoryScreen(requirePlayer(client)));
    }

    private static void containerClick(MinecraftClient client, JsonObject request) {
        var player = requirePlayer(client);
        if (client.interactionManager == null) throw new IllegalStateException("Interaction manager is unavailable");
        ScreenHandler handler = player.currentScreenHandler;
        int slot = requiredInt(request, "slot");
        int button = request.has("button") ? request.get("button").getAsInt() : 0;
        SlotActionType action;
        try {
            action = SlotActionType.valueOf(requiredString(request, "action").toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "action must be pickup, quick_move, swap, clone, throw, quick_craft, or pickup_all");
        }
        if (slot < ScreenHandler.EMPTY_SPACE_SLOT_INDEX || slot >= handler.slots.size()) {
            throw new IllegalArgumentException("slot is outside this screen handler");
        }
        client.interactionManager.clickSlot(handler.syncId, slot, button, action, player);
    }

    private static void containerButton(MinecraftClient client, JsonObject request) {
        var player = requirePlayer(client);
        if (client.interactionManager == null) throw new IllegalStateException("Interaction manager is unavailable");
        client.interactionManager.clickButton(player.currentScreenHandler.syncId, requiredInt(request, "button"));
    }

    private static String useItem(MinecraftClient client, JsonObject request) {
        var player = requirePlayer(client);
        if (client.interactionManager == null) throw new IllegalStateException("Interaction manager is unavailable");
        Hand hand = hand(request);
        ActionResult result = client.interactionManager.interactItem(player, hand);
        if (result.shouldSwingHand()) player.swingHand(hand);
        return result.name().toLowerCase();
    }

    private static String useBlock(MinecraftClient client, JsonObject request) {
        var player = requirePlayer(client);
        if (client.interactionManager == null) throw new IllegalStateException("Interaction manager is unavailable");
        BlockPos pos = blockPos(request);
        Direction side = direction(request);
        double hitX = request.has("hit_x") ? request.get("hit_x").getAsDouble() : pos.getX() + 0.5;
        double hitY = request.has("hit_y") ? request.get("hit_y").getAsDouble() : pos.getY() + 0.5;
        double hitZ = request.has("hit_z") ? request.get("hit_z").getAsDouble() : pos.getZ() + 0.5;
        Hand hand = hand(request);
        ActionResult result = client.interactionManager.interactBlock(player, hand,
                new BlockHitResult(new Vec3d(hitX, hitY, hitZ), side, pos, false));
        if (result.shouldSwingHand()) player.swingHand(hand);
        return result.name().toLowerCase();
    }

    private static void attackEntity(MinecraftClient client, JsonObject request) {
        var player = requirePlayer(client);
        if (client.interactionManager == null) throw new IllegalStateException("Interaction manager is unavailable");
        Entity entity = entity(client, requiredInt(request, "entity_id"));
        client.interactionManager.attackEntity(player, entity);
        player.swingHand(Hand.MAIN_HAND);
    }

    private static String interactEntity(MinecraftClient client, JsonObject request) {
        var player = requirePlayer(client);
        if (client.interactionManager == null) throw new IllegalStateException("Interaction manager is unavailable");
        Hand hand = hand(request);
        ActionResult result = client.interactionManager.interactEntity(
                player, entity(client, requiredInt(request, "entity_id")), hand);
        if (result.shouldSwingHand()) player.swingHand(hand);
        return result.name().toLowerCase();
    }

    private void startBreakBlock(MinecraftClient client, JsonObject request) {
        var player = requirePlayer(client);
        if (client.interactionManager == null) throw new IllegalStateException("Interaction manager is unavailable");
        breakingBlock = blockPos(request);
        breakingSide = direction(request);
        client.interactionManager.attackBlock(breakingBlock, breakingSide);
        player.swingHand(Hand.MAIN_HAND);
    }

    private void stopBreakBlock(MinecraftClient client) {
        if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
        breakingBlock = null;
        breakingSide = null;
    }

    private static void lookAt(MinecraftClient client, JsonObject request) {
        var player = requirePlayer(client);
        double x;
        double y;
        double z;
        if (request.has("entity_id")) {
            Entity target = entity(client, requiredInt(request, "entity_id"));
            x = target.getX();
            y = target.getEyeY();
            z = target.getZ();
        } else {
            x = requiredDouble(request, "x");
            y = requiredDouble(request, "y");
            z = requiredDouble(request, "z");
        }
        Vec3d eye = player.getEyePos();
        double dx = x - eye.x;
        double dy = y - eye.y;
        double dz = z - eye.z;
        player.setYaw(MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(dz, dx)) - 90));
        player.setPitch(MathHelper.clamp((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))), -90, 90));
        player.setHeadYaw(player.getYaw());
    }

    private static void connect(MinecraftClient client, String addressText) {
        if (client.world != null) throw new IllegalStateException("Disconnect before connecting to another server");
        ServerAddress address = ServerAddress.parse(addressText);
        ServerInfo info = new ServerInfo(addressText, addressText, ServerInfo.ServerType.OTHER);
        ConnectScreen.connect(new TitleScreen(), client, address, info, false, null);
    }

    private static void disconnect(MinecraftClient client) {
        if (client.world == null) return;
        client.disconnect(new TitleScreen());
    }

    private JsonObject buildState() {
        MinecraftClient client = MinecraftClient.getInstance();
        JsonObject root = new JsonObject();
        root.addProperty("tick", clientTicks);
        root.addProperty("connected", client.world != null && client.player != null);
        root.addProperty("paused", client.isPaused());
        root.addProperty("screen", client.currentScreen == null ? null : client.currentScreen.getClass().getSimpleName());
        root.addProperty("fps", client.getCurrentFps());
        JsonObject graphics = new JsonObject();
        graphics.addProperty("vendor", GlDebugInfo.getVendor());
        graphics.addProperty("renderer", GlDebugInfo.getRenderer());
        graphics.addProperty("opengl_version", GlDebugInfo.getVersion());
        root.add("graphics", graphics);
        JsonObject window = new JsonObject();
        window.addProperty("gui_width", client.getWindow().getScaledWidth());
        window.addProperty("gui_height", client.getWindow().getScaledHeight());
        window.addProperty("framebuffer_width", client.getWindow().getFramebufferWidth());
        window.addProperty("framebuffer_height", client.getWindow().getFramebufferHeight());
        root.add("window", window);
        root.add("inputs", inputState());
        root.add("events", eventState());
        root.add("navigation", navigation.status(client, clientTicks));

        if (client.getCurrentServerEntry() != null) {
            root.addProperty("server", client.getCurrentServerEntry().address);
        }
        if (client.player == null || client.world == null) return root;

        JsonObject player = new JsonObject();
        player.addProperty("uuid", client.player.getUuidAsString());
        player.addProperty("name", client.player.getName().getString());
        player.add("position", vec(client.player.getPos()));
        player.add("velocity", vec(client.player.getVelocity()));
        player.addProperty("yaw", client.player.getYaw());
        player.addProperty("pitch", client.player.getPitch());
        player.addProperty("health", client.player.getHealth());
        player.addProperty("max_health", client.player.getMaxHealth());
        player.addProperty("food", client.player.getHungerManager().getFoodLevel());
        player.addProperty("saturation", client.player.getHungerManager().getSaturationLevel());
        player.addProperty("experience_level", client.player.experienceLevel);
        player.addProperty("on_ground", client.player.isOnGround());
        player.addProperty("in_water", client.player.isTouchingWater());
        player.addProperty("selected_slot", client.player.getInventory().selectedSlot);
        player.addProperty("air", client.player.getAir());
        player.addProperty("max_air", client.player.getMaxAir());
        player.addProperty("fire_ticks", client.player.getFireTicks());
        player.addProperty("fall_distance", client.player.fallDistance);
        player.addProperty("sprinting", client.player.isSprinting());
        player.addProperty("sneaking", client.player.isSneaking());
        player.addProperty("swimming", client.player.isSwimming());
        player.addProperty("using_item", client.player.isUsingItem());
        player.addProperty("alive", client.player.isAlive());
        if (client.player.getVehicle() != null) player.addProperty("vehicle_entity_id", client.player.getVehicle().getId());
        PlayerAbilities abilities = client.player.getAbilities();
        JsonObject abilityState = new JsonObject();
        abilityState.addProperty("invulnerable", abilities.invulnerable);
        abilityState.addProperty("flying", abilities.flying);
        abilityState.addProperty("allow_flying", abilities.allowFlying);
        abilityState.addProperty("creative", abilities.creativeMode);
        abilityState.addProperty("allow_modify_world", abilities.allowModifyWorld);
        player.add("abilities", abilityState);
        JsonArray effects = new JsonArray();
        client.player.getStatusEffects().forEach(effect -> {
            JsonObject value = new JsonObject();
            value.addProperty("id", Registries.STATUS_EFFECT.getId(effect.getEffectType().value()).toString());
            value.addProperty("amplifier", effect.getAmplifier());
            value.addProperty("duration", effect.getDuration());
            value.addProperty("ambient", effect.isAmbient());
            effects.add(value);
        });
        player.add("effects", effects);
        player.add("main_hand", item(client.player.getMainHandStack()));
        player.add("off_hand", item(client.player.getOffHandStack()));
        if (client.interactionManager != null) {
            player.addProperty("game_mode", client.interactionManager.getCurrentGameMode().getName());
        }
        player.add("inventory", inventory(client));
        root.add("player", player);

        JsonObject world = new JsonObject();
        world.addProperty("dimension", client.world.getRegistryKey().getValue().toString());
        world.addProperty("time", client.world.getTime());
        world.addProperty("time_of_day", client.world.getTimeOfDay());
        world.addProperty("raining", client.world.isRaining());
        world.addProperty("thundering", client.world.isThundering());
        root.add("world", world);
        root.add("crosshair", crosshair(client, client.crosshairTarget));
        root.add("nearby_entities", nearbyEntities(client));
        root.add("screen_state", buildScreenState(client));
        return root;
    }

    private JsonObject inputState() {
        JsonObject result = new JsonObject();
        heldInputs.forEach(result::addProperty);
        return result;
    }

    private JsonArray eventState() {
        JsonArray result = new JsonArray();
        events.forEach(result::add);
        return result;
    }

    private static JsonArray inventory(MinecraftClient client) {
        JsonArray inventory = new JsonArray();
        for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;
            JsonObject value = item(stack);
            value.addProperty("slot", slot);
            inventory.add(value);
        }
        return inventory;
    }

    private JsonArray nearbyEntities(MinecraftClient client) {
        JsonArray result = new JsonArray();
        List<Entity> entities = client.world.getOtherEntities(
                client.player, client.player.getBoundingBox().expand(32), entity -> true);
        entities.stream()
                .sorted((a, b) -> Double.compare(a.squaredDistanceTo(client.player), b.squaredDistanceTo(client.player)))
                .limit(config.nearbyEntityLimit())
                .forEach(entity -> {
                    JsonObject value = new JsonObject();
                    value.addProperty("id", entity.getId());
                    value.addProperty("uuid", entity.getUuidAsString());
                    value.addProperty("type", Registries.ENTITY_TYPE.getId(entity.getType()).toString());
                    value.addProperty("name", entity.getName().getString());
                    value.add("position", vec(entity.getPos()));
                    value.addProperty("distance", entity.distanceTo(client.player));
                    if (entity instanceof LivingEntity living) {
                        value.addProperty("health", living.getHealth());
                        value.addProperty("max_health", living.getMaxHealth());
                    }
                    result.add(value);
                });
        return result;
    }

    private static JsonObject buildScreenState(MinecraftClient client) {
        JsonObject result = new JsonObject();
        result.addProperty("open", client.currentScreen != null);
        if (client.currentScreen != null) {
            result.addProperty("class", client.currentScreen.getClass().getName());
            result.addProperty("title", client.currentScreen.getTitle().getString());
        }
        if (client.player == null) return result;

        ScreenHandler handler = client.player.currentScreenHandler;
        result.addProperty("sync_id", handler.syncId);
        result.addProperty("revision", handler.getRevision());
        String handlerType = "player";
        try {
            if (handler.getType() != null) handlerType = Registries.SCREEN_HANDLER.getId(handler.getType()).toString();
        } catch (UnsupportedOperationException ignored) {
            // PlayerScreenHandler deliberately has no registered constructor/type.
        }
        result.addProperty("handler_type", handlerType);
        result.add("cursor_stack", item(handler.getCursorStack()));
        JsonArray slots = new JsonArray();
        for (Slot slot : handler.slots) {
            JsonObject value = new JsonObject();
            value.addProperty("slot", slot.id);
            value.addProperty("inventory_index", slot.getIndex());
            value.addProperty("x", slot.x);
            value.addProperty("y", slot.y);
            value.addProperty("enabled", slot.isEnabled());
            value.addProperty("can_take", slot.canTakeItems(client.player));
            value.add("stack", item(slot.getStack()));
            slots.add(value);
        }
        result.add("slots", slots);
        return result;
    }

    private JsonObject buildPlayers() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() == null) throw new IllegalStateException("Not connected to a server");
        JsonArray players = new JsonArray();
        client.getNetworkHandler().getListedPlayerListEntries().forEach(entry -> {
            JsonObject value = new JsonObject();
            value.addProperty("uuid", entry.getProfile().getId().toString());
            value.addProperty("name", entry.getProfile().getName());
            value.addProperty("display_name", entry.getDisplayName() == null
                    ? entry.getProfile().getName() : entry.getDisplayName().getString());
            value.addProperty("latency", entry.getLatency());
            value.addProperty("game_mode", entry.getGameMode().getName());
            players.add(value);
        });
        JsonObject result = new JsonObject();
        result.add("players", players);
        result.addProperty("count", players.size());
        return result;
    }

    private static JsonObject item(ItemStack stack) {
        JsonObject result = new JsonObject();
        result.addProperty("empty", stack.isEmpty());
        if (stack.isEmpty()) return result;
        result.addProperty("id", Registries.ITEM.getId(stack.getItem()).toString());
        result.addProperty("name", stack.getName().getString());
        result.addProperty("count", stack.getCount());
        result.addProperty("max_count", stack.getMaxCount());
        if (stack.isDamageable()) {
            result.addProperty("damage", stack.getDamage());
            result.addProperty("max_damage", stack.getMaxDamage());
        }
        return result;
    }

    private static JsonElement crosshair(MinecraftClient client, HitResult hit) {
        if (hit == null || hit.getType() == HitResult.Type.MISS) return new JsonPrimitive("miss");
        JsonObject result = new JsonObject();
        result.addProperty("type", hit.getType().name().toLowerCase());
        result.add("position", vec(hit.getPos()));
        if (hit instanceof BlockHitResult block) {
            result.addProperty("block_x", block.getBlockPos().getX());
            result.addProperty("block_y", block.getBlockPos().getY());
            result.addProperty("block_z", block.getBlockPos().getZ());
            result.addProperty("side", block.getSide().asString());
            if (client.world != null) {
                result.addProperty("block", Registries.BLOCK.getId(
                        client.world.getBlockState(block.getBlockPos()).getBlock()).toString());
            }
        } else if (hit instanceof EntityHitResult entity) {
            result.addProperty("entity_id", entity.getEntity().getId());
            result.addProperty("entity_uuid", entity.getEntity().getUuidAsString());
        }
        return result;
    }

    private static JsonObject vec(Vec3d value) {
        JsonObject result = new JsonObject();
        result.addProperty("x", value.x);
        result.addProperty("y", value.y);
        result.addProperty("z", value.z);
        return result;
    }

    private static JsonObject blockPosition(BlockPos value) {
        JsonObject result = new JsonObject();
        result.addProperty("x", value.getX());
        result.addProperty("y", value.getY());
        result.addProperty("z", value.getZ());
        return result;
    }

    private static MinecraftClient requireWorld() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            throw new IllegalStateException("No player is currently in a world");
        }
        return client;
    }

    private static net.minecraft.client.network.ClientPlayerEntity requirePlayer(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            throw new IllegalStateException("No player is currently in a world");
        }
        return client.player;
    }

    private static BlockPos blockPos(JsonObject request) {
        return new BlockPos(requiredInt(request, "x"), requiredInt(request, "y"), requiredInt(request, "z"));
    }

    private static Direction direction(JsonObject request) {
        String value = request.has("side") ? request.get("side").getAsString() : "up";
        Direction direction = Direction.byName(value.toLowerCase());
        if (direction == null) throw new IllegalArgumentException("side must be down, up, north, south, west, or east");
        return direction;
    }

    private static Hand hand(JsonObject request) {
        String value = request.has("hand") ? request.get("hand").getAsString() : "main_hand";
        try {
            return Hand.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("hand must be main_hand or off_hand");
        }
    }

    private static Entity entity(MinecraftClient client, int entityId) {
        if (client.world == null) throw new IllegalStateException("No player is currently in a world");
        Entity entity = client.world.getEntityById(entityId);
        if (entity == null) throw new IllegalArgumentException("No loaded entity has id " + entityId);
        return entity;
    }

    private static KeyBinding key(MinecraftClient client, String name) {
        return switch (name) {
            case "forward" -> client.options.forwardKey;
            case "back" -> client.options.backKey;
            case "left" -> client.options.leftKey;
            case "right" -> client.options.rightKey;
            case "jump" -> client.options.jumpKey;
            case "sneak" -> client.options.sneakKey;
            case "sprint" -> client.options.sprintKey;
            case "attack" -> client.options.attackKey;
            case "use" -> client.options.useKey;
            case "drop" -> client.options.dropKey;
            case "inventory" -> client.options.inventoryKey;
            case "swap_hands" -> client.options.swapHandsKey;
            case "pick" -> client.options.pickItemKey;
            case "chat" -> client.options.chatKey;
            case "player_list" -> client.options.playerListKey;
            case "command" -> client.options.commandKey;
            case "social" -> client.options.socialInteractionsKey;
            case "screenshot" -> client.options.screenshotKey;
            case "perspective" -> client.options.togglePerspectiveKey;
            case "smooth_camera" -> client.options.smoothCameraKey;
            case "fullscreen" -> client.options.fullscreenKey;
            case "spectator_outlines" -> client.options.spectatorOutlinesKey;
            case "advancements" -> client.options.advancementsKey;
            case "save_toolbar" -> client.options.saveToolbarActivatorKey;
            case "load_toolbar" -> client.options.loadToolbarActivatorKey;
            case "hotbar_1" -> client.options.hotbarKeys[0];
            case "hotbar_2" -> client.options.hotbarKeys[1];
            case "hotbar_3" -> client.options.hotbarKeys[2];
            case "hotbar_4" -> client.options.hotbarKeys[3];
            case "hotbar_5" -> client.options.hotbarKeys[4];
            case "hotbar_6" -> client.options.hotbarKeys[5];
            case "hotbar_7" -> client.options.hotbarKeys[6];
            case "hotbar_8" -> client.options.hotbarKeys[7];
            case "hotbar_9" -> client.options.hotbarKeys[8];
            default -> throw new IllegalArgumentException("Unknown input: " + name);
        };
    }

    private <T> T onClientThread(Supplier<T> operation) throws Exception {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.isOnThread()) return operation.get();

        CompletableFuture<T> result = new CompletableFuture<>();
        client.execute(() -> {
            try {
                result.complete(operation.get());
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        });
        try {
            return result.get(config.requestTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            if (cause instanceof Error error) throw error;
            throw new Exception(cause);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (TimeoutException exception) {
            throw new IllegalStateException("Minecraft client thread did not respond in time", exception);
        }
    }

    private static String requiredString(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonPrimitive()
                || !object.getAsJsonPrimitive(name).isString()) {
            throw new IllegalArgumentException("Missing string field: " + name);
        }
        String value = object.get(name).getAsString();
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static int requiredInt(JsonObject object, String name) {
        if (!object.has(name)) throw new IllegalArgumentException("Missing integer field: " + name);
        try {
            return object.get(name).getAsInt();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }

    private static double requiredDouble(JsonObject object, String name) {
        if (!object.has(name)) throw new IllegalArgumentException("Missing number field: " + name);
        try {
            return object.get(name).getAsDouble();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(name + " must be a number");
        }
    }

    private static float floatOr(JsonObject object, String name, float fallback) {
        return object.has(name) ? object.get(name).getAsFloat() : fallback;
    }
}

package dev.deepclient;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.cache.IWaypoint;
import baritone.api.cache.IWorldData;
import baritone.api.cache.Waypoint;
import baritone.api.command.ICommand;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.process.IBaritoneProcess;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.SettingsUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

public final class DeepClientBaritone {
    private static final int MAX_GOAL_DEPTH = 8;
    private static final int MAX_VALUES = 32;

    private IBaritone baritone;
    private boolean listenerRegistered;
    private String jobId;
    private String jobType;
    private String status = "idle";
    private String failureReason;
    private Goal activeGoal;
    private JsonObject requestDetails;
    private long startedAtTick;
    private long finishedAtTick;
    private long lastObservedTick;
    private long inactiveSinceTick;
    private boolean observedActivity;

    public JsonObject walkTo(MinecraftClient client, int x, int y, int z, int radius, long tick) {
        if (radius < 0 || radius > 32) throw new IllegalArgumentException("radius must be from 0 to 32");
        JsonObject request = new JsonObject();
        request.addProperty("type", radius == 0 ? "block" : "near");
        request.addProperty("x", x);
        request.addProperty("y", y);
        request.addProperty("z", z);
        if (radius > 0) request.addProperty("radius", radius);
        return setGoal(client, request, tick);
    }

    public JsonObject setGoal(MinecraftClient client, JsonObject request, long tick) {
        requireWorld(client);
        Goal goal = parseGoal(request, 0);
        IBaritone instance = begin(client, "goal", request, tick);
        activeGoal = goal;
        boolean path = !request.has("path") || request.get("path").getAsBoolean();
        if (path) instance.getCustomGoalProcess().setGoalAndPath(goal);
        else instance.getCustomGoalProcess().setGoal(goal);
        status = path ? "calculating" : "goal_set";
        return status(client, tick);
    }

    public JsonObject mine(MinecraftClient client, JsonObject request, long tick) {
        requireWorld(client);
        List<String> blocks = stringArray(request, "blocks", 1, MAX_VALUES);
        int count = intOr(request, "count", 1, 1, 2304);
        IBaritone instance = begin(client, "mine", request, tick);
        instance.getMineProcess().mineByName(count, blocks.toArray(String[]::new));
        status = "running";
        return status(client, tick);
    }

    public JsonObject getToBlock(MinecraftClient client, JsonObject request, long tick) {
        requireWorld(client);
        IBaritone instance = begin(client, "get_to_block", request, tick);
        instance.getGetToBlockProcess().getToBlock(new BlockOptionalMeta(requiredString(request, "block")));
        status = "running";
        return status(client, tick);
    }

    public JsonObject follow(MinecraftClient client, JsonObject request, long tick) {
        requireWorld(client);
        Predicate<Entity> filter = entityFilter(request);
        IBaritone instance = begin(client, "follow", request, tick);
        instance.getFollowProcess().follow(filter);
        status = "running";
        return status(client, tick);
    }

    public JsonObject farm(MinecraftClient client, JsonObject request, long tick) {
        requireWorld(client);
        int radius = intOr(request, "radius", 64, 1, 256);
        BlockPos center = hasPosition(request) ? blockPos(request) : client.player.getBlockPos();
        IBaritone instance = begin(client, "farm", request, tick);
        instance.getFarmProcess().farm(radius, center);
        status = "running";
        return status(client, tick);
    }

    public JsonObject explore(MinecraftClient client, JsonObject request, long tick) {
        requireWorld(client);
        int x = request.has("x") ? requiredInt(request, "x") : client.player.getBlockX();
        int z = request.has("z") ? requiredInt(request, "z") : client.player.getBlockZ();
        IBaritone instance = begin(client, "explore", request, tick);
        instance.getExploreProcess().explore(x, z);
        status = "running";
        return status(client, tick);
    }

    public JsonObject clearArea(MinecraftClient client, JsonObject request, long tick) {
        requireWorld(client);
        BlockPos from = blockPos(requiredObject(request, "from"));
        BlockPos to = blockPos(requiredObject(request, "to"));
        long width = Math.abs((long) from.getX() - to.getX()) + 1;
        long height = Math.abs((long) from.getY() - to.getY()) + 1;
        long depth = Math.abs((long) from.getZ() - to.getZ()) + 1;
        if (width > 1_000_000 || height > 1_000_000 || depth > 1_000_000) {
            throw new IllegalArgumentException("clear-area volume must not exceed 1000000 blocks");
        }
        long volume = width * height * depth;
        if (volume > 1_000_000) throw new IllegalArgumentException("clear-area volume must not exceed 1000000 blocks");
        IBaritone instance = begin(client, "clear_area", request, tick);
        instance.getBuilderProcess().clearArea(from, to);
        status = "running";
        return status(client, tick);
    }

    public JsonObject executeCommand(MinecraftClient client, JsonObject request, long tick) {
        requireWorld(client);
        String command = requiredString(request, "command");
        if (command.length() > 512) throw new IllegalArgumentException("command must be at most 512 characters");
        IBaritone instance = baritone(client);
        jobId = UUID.randomUUID().toString();
        jobType = "command";
        requestDetails = request.deepCopy();
        activeGoal = null;
        failureReason = null;
        startedAtTick = tick;
        finishedAtTick = 0;
        inactiveSinceTick = 0;
        observedActivity = false;
        boolean accepted = instance.getCommandManager().execute(command);
        boolean active = accepted && (instance.getPathingBehavior().isPathing()
                || processes(instance).stream().anyMatch(entry -> entry.process().isActive()));
        observedActivity = active;
        status = !accepted ? "rejected" : active ? "running" : "complete";
        if (!active) finishedAtTick = tick;
        JsonObject result = status(client, tick);
        result.addProperty("accepted", accepted);
        return result;
    }

    public JsonObject commands(MinecraftClient client) {
        JsonArray values = new JsonArray();
        baritone(client).getCommandManager().getRegistry().stream()
                .filter(command -> !command.hiddenFromHelp())
                .sorted(Comparator.comparing(command -> command.getNames().getFirst()))
                .forEach(command -> values.add(commandJson(command)));
        return arrayResult("commands", values);
    }

    public JsonObject scan(MinecraftClient client, JsonObject request) {
        requireWorld(client);
        List<String> names = stringArray(request, "blocks", 1, MAX_VALUES);
        int limit = intOr(request, "limit", 64, 1, 512);
        int radius = intOr(request, "chunk_radius", 10, 1, 64);
        int yThreshold = intOr(request, "y_level_threshold", 10, 0, 384);
        BlockOptionalMetaLookup lookup = new BlockOptionalMetaLookup(names.toArray(String[]::new));
        List<BlockPos> positions = BaritoneAPI.getProvider().getWorldScanner()
                .scanChunkRadius(baritone(client).getPlayerContext(), lookup, limit, yThreshold, radius);
        JsonArray values = new JsonArray();
        positions.stream().limit(limit).forEach(position -> values.add(positionJson(position)));
        JsonObject result = arrayResult("blocks", values);
        result.addProperty("chunk_radius", radius);
        result.addProperty("from_cache", true);
        return result;
    }

    public JsonObject settings() {
        JsonArray values = new JsonArray();
        BaritoneAPI.getSettings().allSettings.stream()
                .sorted(Comparator.comparing(Settings.Setting::getName))
                .forEach(setting -> {
                    JsonObject value = new JsonObject();
                    value.addProperty("name", setting.getName());
                    value.addProperty("type", setting.getType().getTypeName());
                    value.addProperty("java_only", setting.isJavaOnly());
                    value.addProperty("writable", !setting.isJavaOnly() && scalarSetting(setting));
                    value.add("value", jsonValue(setting.value));
                    value.add("default", jsonValue(setting.defaultValue));
                    values.add(value);
                });
        return arrayResult("settings", values);
    }

    public JsonObject updateSettings(JsonObject request) {
        JsonObject updates = request.has("settings") ? requiredObject(request, "settings") : request;
        if (updates.isEmpty() || updates.size() > 64) {
            throw new IllegalArgumentException("settings must contain from 1 to 64 entries");
        }
        JsonArray changed = new JsonArray();
        for (String name : updates.keySet()) {
            Settings.Setting<?> setting = BaritoneAPI.getSettings().byLowerName.get(name.toLowerCase(Locale.ROOT));
            if (setting == null) throw new IllegalArgumentException("Unknown Baritone setting: " + name);
            if (setting.isJavaOnly() || !scalarSetting(setting)) {
                throw new IllegalArgumentException("Setting is not writable through REST: " + setting.getName());
            }
            JsonElement value = updates.get(name);
            if (value == null || value.isJsonNull()) setting.reset();
            else assignSetting(setting, settingValue(setting, value));
            JsonObject item = new JsonObject();
            item.addProperty("name", setting.getName());
            item.add("value", jsonValue(setting.value));
            changed.add(item);
        }
        SettingsUtil.save(BaritoneAPI.getSettings());
        return arrayResult("changed", changed);
    }

    public JsonObject waypoints(MinecraftClient client) {
        JsonArray values = new JsonArray();
        worldData(client).getWaypoints().getAllWaypoints().stream()
                .sorted(Comparator.comparingLong(IWaypoint::getCreationTimestamp).reversed())
                .forEach(waypoint -> values.add(waypointJson(waypoint)));
        return arrayResult("waypoints", values);
    }

    public JsonObject addWaypoint(MinecraftClient client, JsonObject request) {
        requireWorld(client);
        String name = requiredString(request, "name");
        if (name.length() > 128) throw new IllegalArgumentException("name must be at most 128 characters");
        IWaypoint.Tag tag = waypointTag(request.has("tag") ? request.get("tag").getAsString() : "user");
        BlockPos position = hasPosition(request) ? blockPos(request) : client.player.getBlockPos();
        IWaypoint waypoint = new Waypoint(name, tag, new BetterBlockPos(position));
        worldData(client).getWaypoints().addWaypoint(waypoint);
        return waypointJson(waypoint);
    }

    public JsonObject removeWaypoint(MinecraftClient client, JsonObject request) {
        String name = requiredString(request, "name");
        IWaypoint.Tag tag = request.has("tag") ? waypointTag(request.get("tag").getAsString()) : null;
        IWorldData world = worldData(client);
        List<IWaypoint> matches = new ArrayList<>(world.getWaypoints().getAllWaypoints()).stream()
                .filter(waypoint -> waypoint.getName().equals(name) && (tag == null || waypoint.getTag() == tag))
                .toList();
        matches.forEach(world.getWaypoints()::removeWaypoint);
        JsonObject result = new JsonObject();
        result.addProperty("removed", matches.size());
        return result;
    }

    public JsonObject navigateWaypoint(MinecraftClient client, JsonObject request, long tick) {
        String name = requiredString(request, "name");
        IWaypoint.Tag tag = request.has("tag") ? waypointTag(request.get("tag").getAsString()) : null;
        IWaypoint waypoint = worldData(client).getWaypoints().getAllWaypoints().stream()
                .filter(item -> item.getName().equals(name) && (tag == null || item.getTag() == tag))
                .max(Comparator.comparingLong(IWaypoint::getCreationTimestamp))
                .orElseThrow(() -> new IllegalArgumentException("Waypoint not found: " + name));
        JsonObject goal = positionJson(waypoint.getLocation());
        goal.addProperty("type", "block");
        return setGoal(client, goal, tick);
    }

    public JsonObject path(MinecraftClient client, int limit) {
        if (limit < 1 || limit > 1024) throw new IllegalArgumentException("limit must be from 1 to 1024");
        IBaritone instance = baritone(client);
        IPathExecutor executor = instance.getPathingBehavior().getCurrent();
        String source = "current";
        if (executor == null) {
            executor = instance.getPathingBehavior().getNext();
            source = "next";
        }
        JsonObject result = new JsonObject();
        result.addProperty("available", executor != null);
        if (executor == null) return result;
        IPath path = executor.getPath();
        int position = Math.max(0, executor.getPosition());
        List<BetterBlockPos> positions = path.positions();
        JsonArray nodes = new JsonArray();
        for (int index = position; index < positions.size() && nodes.size() < limit; index++) {
            JsonObject node = positionJson(positions.get(index));
            node.addProperty("index", index);
            nodes.add(node);
        }
        result.addProperty("source", source);
        result.addProperty("position_index", position);
        result.addProperty("length", path.length());
        result.addProperty("nodes_considered", path.getNumNodesConsidered());
        result.addProperty("remaining_nodes", Math.max(0, positions.size() - position));
        result.addProperty("truncated", positions.size() - position > nodes.size());
        result.add("nodes", nodes);
        result.add("goal", goalJson(path.getGoal()));
        return result;
    }

    public JsonObject cancel(MinecraftClient client, long tick) {
        if (baritone != null) cancelAll(baritone);
        if (isRunning()) finish("canceled", null);
        activeGoal = null;
        return status(client, tick);
    }

    public void onTick(MinecraftClient client, long tick) {
        lastObservedTick = tick;
        if (!isRunning() || client.player == null) return;
        if (activeGoal != null) {
            BlockPos position = client.player.getBlockPos();
            if (activeGoal.isInGoal(position.getX(), position.getY(), position.getZ())) {
                finish("complete", null);
                return;
            }
        }
        if (baritone != null) {
            boolean activeNow = baritone.getPathingBehavior().isPathing()
                    || processes(baritone).stream().anyMatch(entry -> entry.process().isActive());
            if (activeNow) {
                observedActivity = true;
                inactiveSinceTick = 0;
                if (baritone.getPathingBehavior().isPathing()) status = "pathing";
            } else if (observedActivity) {
                if (inactiveSinceTick == 0) inactiveSinceTick = tick;
                else if (tick - inactiveSinceTick >= 5) finish("complete", null);
            }
        }
    }

    public JsonObject status(MinecraftClient client, long tick) {
        JsonObject result = new JsonObject();
        result.addProperty("status", status);
        result.addProperty("active", isRunning());
        result.addProperty("tick", tick);
        if (jobId != null) result.addProperty("job_id", jobId);
        if (jobType != null) result.addProperty("job_type", jobType);
        if (requestDetails != null) result.add("request", requestDetails.deepCopy());
        if (activeGoal != null) result.add("goal", goalJson(activeGoal));
        result.addProperty("started_at_tick", startedAtTick);
        if (finishedAtTick > 0) result.addProperty("finished_at_tick", finishedAtTick);
        if (failureReason != null) result.addProperty("failure_reason", failureReason);
        if (client.player != null) result.add("position", positionJson(client.player.getBlockPos()));
        if (baritone != null) {
            if (client.player == null || client.world == null) addUnavailableBaritoneStatus(result, baritone);
            else addBaritoneStatus(result, baritone);
        }
        return result;
    }

    private static void addUnavailableBaritoneStatus(JsonObject result, IBaritone instance) {
        result.addProperty("pathing", false);
        result.addProperty("has_path", false);
        JsonArray values = new JsonArray();
        for (NamedProcess entry : processes(instance)) {
            JsonObject item = new JsonObject();
            item.addProperty("name", entry.name());
            item.addProperty("active", false);
            item.addProperty("available", false);
            values.add(item);
        }
        result.add("processes", values);
    }

    private void addBaritoneStatus(JsonObject result, IBaritone instance) {
        var pathing = instance.getPathingBehavior();
        result.addProperty("pathing", pathing.isPathing());
        result.addProperty("has_path", pathing.hasPath());
        pathing.estimatedTicksToGoal().ifPresent(value -> result.addProperty("estimated_ticks_to_goal", value));
        JsonArray values = new JsonArray();
        for (NamedProcess entry : processes(instance)) {
            IBaritoneProcess process = entry.process();
            JsonObject item = new JsonObject();
            item.addProperty("name", entry.name());
            item.addProperty("display_name", process.displayName());
            item.addProperty("active", process.isActive());
            item.addProperty("temporary", process.isTemporary());
            item.addProperty("priority", process.priority());
            item.addProperty("available", true);
            values.add(item);
        }
        result.add("processes", values);
    }

    private IBaritone begin(MinecraftClient client, String type, JsonObject request, long tick) {
        IBaritone instance = baritone(client);
        cancelAll(instance);
        jobId = UUID.randomUUID().toString();
        jobType = type;
        requestDetails = request.deepCopy();
        activeGoal = null;
        status = "starting";
        failureReason = null;
        startedAtTick = tick;
        finishedAtTick = 0;
        inactiveSinceTick = 0;
        observedActivity = false;
        return instance;
    }

    private synchronized IBaritone baritone(MinecraftClient client) {
        if (baritone == null) {
            baritone = BaritoneAPI.getProvider().getBaritoneForMinecraft(client);
            if (baritone == null) baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone == null) throw new IllegalStateException("Baritone is unavailable");
        }
        if (!listenerRegistered) {
            baritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
                @Override
                public void onPathEvent(PathEvent event) {
                    switch (event) {
                        case AT_GOAL -> finish("complete", null);
                        case CALC_FAILED, NEXT_CALC_FAILED -> finish("failed", event.name().toLowerCase(Locale.ROOT));
                        // CANCELED can arrive late after replacing a process and does not identify
                        // which job it belonged to. Explicit REST cancellation updates state itself.
                        case CANCELED -> { }
                        default -> {
                            if (isRunning() && event.name().contains("EXECUTING")) status = "pathing";
                        }
                    }
                }
            });
            listenerRegistered = true;
        }
        return baritone;
    }

    private void finish(String newStatus, String reason) {
        status = newStatus;
        failureReason = reason;
        finishedAtTick = lastObservedTick;
    }

    private boolean isRunning() {
        return List.of("starting", "calculating", "running", "pathing", "goal_set").contains(status);
    }

    private static void cancelAll(IBaritone instance) {
        instance.getPathingBehavior().cancelEverything();
        for (NamedProcess entry : processes(instance)) {
            if (entry.process().isActive()) entry.process().onLostControl();
        }
    }

    private static List<NamedProcess> processes(IBaritone instance) {
        return List.of(new NamedProcess("custom_goal", instance.getCustomGoalProcess()),
                new NamedProcess("mine", instance.getMineProcess()),
                new NamedProcess("follow", instance.getFollowProcess()),
                new NamedProcess("farm", instance.getFarmProcess()),
                new NamedProcess("explore", instance.getExploreProcess()),
                new NamedProcess("get_to_block", instance.getGetToBlockProcess()),
                new NamedProcess("builder", instance.getBuilderProcess()),
                new NamedProcess("elytra", instance.getElytraProcess()));
    }

    private record NamedProcess(String name, IBaritoneProcess process) {}

    private static Goal parseGoal(JsonObject request, int depth) {
        if (depth > MAX_GOAL_DEPTH) throw new IllegalArgumentException("goal nesting is too deep");
        return switch (requiredString(request, "type").toLowerCase(Locale.ROOT)) {
            case "block" -> new GoalBlock(blockPos(request));
            case "near" -> new GoalNear(blockPos(request), intOr(request, "radius", 1, 1, 32));
            case "xz" -> new GoalXZ(requiredInt(request, "x"), requiredInt(request, "z"));
            case "y_level" -> new GoalYLevel(requiredInt(request, "y"));
            case "get_to_block" -> new GoalGetToBlock(blockPos(request));
            case "run_away" -> {
                JsonArray from = requiredArray(request, "from", 1, MAX_VALUES);
                BlockPos[] positions = new BlockPos[from.size()];
                for (int index = 0; index < from.size(); index++) {
                    if (!from.get(index).isJsonObject()) throw new IllegalArgumentException("from must contain positions");
                    positions[index] = blockPos(from.get(index).getAsJsonObject());
                }
                Integer maintainY = request.has("maintain_y") ? requiredInt(request, "maintain_y") : null;
                yield new GoalRunAway(doubleOr(request, "distance", 16, 1, 100000), maintainY, positions);
            }
            case "composite" -> {
                JsonArray children = requiredArray(request, "goals", 1, MAX_VALUES);
                Goal[] goals = new Goal[children.size()];
                for (int index = 0; index < children.size(); index++) {
                    if (!children.get(index).isJsonObject()) throw new IllegalArgumentException("goals must contain objects");
                    goals[index] = parseGoal(children.get(index).getAsJsonObject(), depth + 1);
                }
                yield new GoalComposite(goals);
            }
            default -> throw new IllegalArgumentException("Unknown goal type");
        };
    }

    private static JsonObject goalJson(Goal goal) {
        JsonObject result = new JsonObject();
        result.addProperty("description", goal.toString());
        if (goal instanceof GoalBlock value) {
            result.addProperty("type", "block");
            result.add("position", positionJson(value.getGoalPos()));
        } else if (goal instanceof GoalNear value) {
            result.addProperty("type", "near");
            result.add("position", positionJson(value.getGoalPos()));
        } else if (goal instanceof GoalXZ value) {
            result.addProperty("type", "xz");
            result.addProperty("x", value.getX());
            result.addProperty("z", value.getZ());
        } else if (goal instanceof GoalYLevel value) {
            result.addProperty("type", "y_level");
            result.addProperty("y", value.level);
        } else if (goal instanceof GoalGetToBlock value) {
            result.addProperty("type", "get_to_block");
            result.add("position", positionJson(value.getGoalPos()));
        } else if (goal instanceof GoalComposite value) {
            result.addProperty("type", "composite");
            JsonArray children = new JsonArray();
            for (Goal child : value.goals()) children.add(goalJson(child));
            result.add("goals", children);
        } else if (goal instanceof GoalRunAway) result.addProperty("type", "run_away");
        else result.addProperty("type", goal.getClass().getSimpleName());
        return result;
    }

    private static Predicate<Entity> entityFilter(JsonObject request) {
        if (request.has("entity_id")) {
            int id = requiredInt(request, "entity_id");
            return entity -> entity.getId() == id;
        }
        if (request.has("uuid")) {
            try {
                UUID uuid = UUID.fromString(request.get("uuid").getAsString());
                return entity -> entity.getUuid().equals(uuid);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("uuid must be a valid UUID");
            }
        }
        if (request.has("name")) {
            String name = requiredString(request, "name");
            return entity -> entity.getName().getString().equalsIgnoreCase(name);
        }
        if (request.has("entity_type")) {
            String type = requiredString(request, "entity_type");
            return entity -> Registries.ENTITY_TYPE.getId(entity.getType()).toString().equals(type);
        }
        throw new IllegalArgumentException("follow requires entity_id, uuid, name, or entity_type");
    }

    private IWorldData worldData(MinecraftClient client) {
        requireWorld(client);
        IWorldData world = baritone(client).getWorldProvider().getCurrentWorld();
        if (world == null) {
            throw new IllegalStateException("Baritone world data is unavailable");
        }
        return world;
    }

    private static IWaypoint.Tag waypointTag(String name) {
        IWaypoint.Tag tag = IWaypoint.Tag.getByName(name);
        if (tag == null) throw new IllegalArgumentException("tag must be home, death, bed, or user");
        return tag;
    }

    private static JsonObject waypointJson(IWaypoint waypoint) {
        JsonObject value = positionJson(waypoint.getLocation());
        value.addProperty("name", waypoint.getName());
        value.addProperty("tag", waypoint.getTag().getName());
        value.addProperty("created_at", waypoint.getCreationTimestamp());
        return value;
    }

    private static JsonObject commandJson(ICommand command) {
        JsonObject value = new JsonObject();
        JsonArray names = new JsonArray();
        command.getNames().forEach(names::add);
        value.add("names", names);
        value.addProperty("description", command.getShortDesc());
        JsonArray details = new JsonArray();
        command.getLongDesc().forEach(details::add);
        value.add("details", details);
        return value;
    }

    private static boolean scalarSetting(Settings.Setting<?> setting) {
        Class<?> type = setting.getValueClass();
        return type == Boolean.class || type == Integer.class || type == Long.class || type == Float.class
                || type == Double.class || type == String.class || type.isEnum();
    }

    private static Object settingValue(Settings.Setting<?> setting, JsonElement value) {
        Class<?> type = setting.getValueClass();
        try {
            if (type == Boolean.class) return value.getAsBoolean();
            if (type == Integer.class) return value.getAsInt();
            if (type == Long.class) return value.getAsLong();
            if (type == Float.class) return value.getAsFloat();
            if (type == Double.class) return value.getAsDouble();
            if (type == String.class) return value.getAsString();
            if (type.isEnum()) for (Object item : type.getEnumConstants()) {
                if (((Enum<?>) item).name().equalsIgnoreCase(value.getAsString())) return item;
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid value for setting " + setting.getName());
        }
        throw new IllegalArgumentException("Invalid value for setting " + setting.getName());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void assignSetting(Settings.Setting setting, Object value) {
        setting.value = value;
    }

    private static JsonElement jsonValue(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof Boolean item) return new JsonPrimitive(item);
        if (value instanceof Number item) return new JsonPrimitive(item);
        if (value instanceof String item) return new JsonPrimitive(item);
        if (value instanceof Enum<?> item) return new JsonPrimitive(item.name().toLowerCase(Locale.ROOT));
        if (value instanceof Block item) return new JsonPrimitive(Registries.BLOCK.getId(item).toString());
        if (value instanceof Item item) return new JsonPrimitive(Registries.ITEM.getId(item).toString());
        if (value instanceof Color item) {
            JsonObject result = new JsonObject();
            result.addProperty("red", item.getRed());
            result.addProperty("green", item.getGreen());
            result.addProperty("blue", item.getBlue());
            result.addProperty("alpha", item.getAlpha());
            return result;
        }
        if (value instanceof Collection<?> items) {
            JsonArray result = new JsonArray();
            items.forEach(item -> result.add(jsonValue(item)));
            return result;
        }
        return new JsonPrimitive(value.toString());
    }

    private static JsonObject arrayResult(String name, JsonArray values) {
        JsonObject result = new JsonObject();
        result.add(name, values);
        result.addProperty("count", values.size());
        return result;
    }

    private static List<String> stringArray(JsonObject object, String name, int min, int max) {
        JsonArray array = requiredArray(object, name, min, max);
        List<String> result = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString() || element.getAsString().isBlank()) {
                throw new IllegalArgumentException(name + " must contain non-empty strings");
            }
            result.add(element.getAsString());
        }
        return result;
    }

    private static JsonArray requiredArray(JsonObject object, String name, int min, int max) {
        if (!object.has(name) || !object.get(name).isJsonArray()) throw new IllegalArgumentException("Missing array field: " + name);
        JsonArray value = object.getAsJsonArray(name);
        if (value.size() < min || value.size() > max) {
            throw new IllegalArgumentException(name + " must contain from " + min + " to " + max + " values");
        }
        return value;
    }

    private static JsonObject requiredObject(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonObject()) throw new IllegalArgumentException("Missing object field: " + name);
        return object.getAsJsonObject(name);
    }

    private static boolean hasPosition(JsonObject object) {
        return object.has("x") || object.has("y") || object.has("z");
    }

    private static BlockPos blockPos(JsonObject object) {
        return new BlockPos(requiredInt(object, "x"), requiredInt(object, "y"), requiredInt(object, "z"));
    }

    private static JsonObject positionJson(BlockPos position) {
        JsonObject value = new JsonObject();
        value.addProperty("x", position.getX());
        value.addProperty("y", position.getY());
        value.addProperty("z", position.getZ());
        return value;
    }

    private static String requiredString(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonPrimitive() || !object.getAsJsonPrimitive(name).isString()) {
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

    private static int intOr(JsonObject object, String name, int fallback, int min, int max) {
        int value = object.has(name) ? requiredInt(object, name) : fallback;
        if (value < min || value > max) throw new IllegalArgumentException(name + " must be from " + min + " to " + max);
        return value;
    }

    private static double doubleOr(JsonObject object, String name, double fallback, double min, double max) {
        double value;
        try {
            value = object.has(name) ? object.get(name).getAsDouble() : fallback;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(name + " must be a number");
        }
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(name + " must be from " + min + " to " + max);
        }
        return value;
    }

    private static void requireWorld(MinecraftClient client) {
        if (client.player == null || client.world == null) throw new IllegalStateException("No player is currently in a world");
    }
}

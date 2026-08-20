package dev.deepclient;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

public final class DeepClientNavigation {
    private IBaritone baritone;
    private boolean listenerRegistered;
    private String jobId;
    private String status = "idle";
    private String failureReason;
    private BlockPos target;
    private int radius;
    private long startedAtTick;
    private long finishedAtTick;
    private long lastObservedTick;

    public JsonObject walkTo(MinecraftClient client, int x, int y, int z, int radius, long tick) {
        if (client.player == null || client.world == null) {
            throw new IllegalStateException("No player is currently in a world");
        }
        if (radius < 0 || radius > 32) {
            throw new IllegalArgumentException("radius must be from 0 to 32");
        }

        IBaritone instance = baritone(client);
        instance.getPathingBehavior().cancelEverything();
        target = new BlockPos(x, y, z);
        this.radius = radius;
        jobId = UUID.randomUUID().toString();
        status = "calculating";
        failureReason = null;
        startedAtTick = tick;
        finishedAtTick = 0;

        Goal goal = radius == 0 ? new GoalBlock(target) : new GoalNear(target, radius);
        instance.getCustomGoalProcess().setGoalAndPath(goal);
        return status(client, tick);
    }

    public JsonObject cancel(MinecraftClient client, long tick) {
        if (baritone != null) baritone.getPathingBehavior().cancelEverything();
        if (isRunning()) {
            status = "canceled";
            finishedAtTick = tick;
        }
        return status(client, tick);
    }

    public void onTick(MinecraftClient client, long tick) {
        lastObservedTick = tick;
        if (!isRunning() || target == null || client.player == null) return;
        BlockPos position = client.player.getBlockPos();
        Goal goal = radius == 0 ? new GoalBlock(target) : new GoalNear(target, radius);
        if (goal.isInGoal(position.getX(), position.getY(), position.getZ())) {
            status = "complete";
            finishedAtTick = tick;
            return;
        }

        if (baritone != null && baritone.getPathingBehavior().isPathing()) status = "pathing";
    }

    public JsonObject status(MinecraftClient client, long tick) {
        JsonObject result = new JsonObject();
        result.addProperty("status", status);
        result.addProperty("active", isRunning());
        result.addProperty("tick", tick);
        if (jobId != null) result.addProperty("job_id", jobId);
        if (target != null) {
            JsonObject targetJson = new JsonObject();
            targetJson.addProperty("x", target.getX());
            targetJson.addProperty("y", target.getY());
            targetJson.addProperty("z", target.getZ());
            targetJson.addProperty("radius", radius);
            result.add("target", targetJson);
        }
        result.addProperty("started_at_tick", startedAtTick);
        if (finishedAtTick > 0) result.addProperty("finished_at_tick", finishedAtTick);
        if (failureReason != null) result.addProperty("failure_reason", failureReason);
        if (client.player != null) {
            JsonObject position = new JsonObject();
            position.addProperty("x", client.player.getX());
            position.addProperty("y", client.player.getY());
            position.addProperty("z", client.player.getZ());
            result.add("position", position);
            if (target != null) {
                result.addProperty("distance", Math.sqrt(client.player.getBlockPos().getSquaredDistance(target)));
            }
        }
        if (baritone != null) {
            baritone.getPathingBehavior().estimatedTicksToGoal()
                    .ifPresent(value -> result.addProperty("estimated_ticks_to_goal", value));
        }
        return result;
    }

    private boolean isRunning() {
        return "calculating".equals(status) || "pathing".equals(status);
    }

    private IBaritone baritone(MinecraftClient client) {
        if (baritone == null) {
            baritone = BaritoneAPI.getProvider().getBaritoneForMinecraft(client);
            if (baritone == null) baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        }
        if (!listenerRegistered) {
            baritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
                @Override
                public void onPathEvent(PathEvent event) {
                    switch (event) {
                        case AT_GOAL -> {
                            status = "complete";
                            finishedAtTick = lastObservedTick;
                        }
                        case CALC_FAILED, NEXT_CALC_FAILED -> {
                            status = "failed";
                            finishedAtTick = lastObservedTick;
                            failureReason = event.name().toLowerCase();
                        }
                        case CANCELED -> {
                            if (isRunning()) {
                                status = "canceled";
                                finishedAtTick = lastObservedTick;
                            }
                        }
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
}

package com.example.exampleaddon;

import com.blitz.module.Category;
import com.blitz.module.Module;
import com.blitz.module.setting.DoubleSetting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Pearllowestspotmodule extends Module {

    // --- Vanilla pearl physics ---
    private static final double THROW_POWER = 1.5;
    private static final double GRAVITY_PER_TICK = 0.03;
    private static final double DRAG_PER_TICK = 0.99;

    // --- Search tuning ---
    private static final double MIN_PITCH = -90.0;
    private static final double MAX_PITCH = 70.0;
    private static final double ANGLE_STEP_DEGREES = 2.0;
    private static final int MAX_SIMULATED_TICKS = 180;
    private static final int RECOMPUTE_INTERVAL_TICKS = 6;

    private final DoubleSetting maxDistance = addSetting(new DoubleSetting("Max Distance", 60.0, 10.0, 128.0, 5.0));
    private final DoubleSetting minimumFall = addSetting(new DoubleSetting("Minimum Fall (blocks)", 4.0, 0.0, 50.0, 0.5));

    private BlockPos bestLanding;
    private double bestFallDistance;
    private int ticksUntilRecompute;
    private boolean wasUseKeyPressed;

    private record Candidate(BlockPos block, double drop, double horizontalDistance) {}

    public Pearllowestspotmodule(Category category) {
        super("PearlLowestSpot", "Redirects pearls to the lowest reachable ground (closer if tied)", category);

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client == null || client.player == null || !isEnabled()) return;
            onStartTick(client);
        });
    }

    private void onStartTick(MinecraftClient client) {
        boolean usePressed = client.options.useKey.isPressed();
        if (usePressed && !wasUseKeyPressed) {
            if (client.player.getMainHandStack().isOf(Items.ENDER_PEARL)) {
                redirectIfValid(client);
            }
        }
        wasUseKeyPressed = usePressed;
    }

    private void redirectIfValid(MinecraftClient client) {
        if (bestLanding == null) return;
        if (bestFallDistance < minimumFall.get()) return;

        Vec3d targetPos = Vec3d.ofCenter(bestLanding);
        Vec3d eyePos = client.player.getEyePos();
        Vec3d diff = targetPos.subtract(eyePos);
        float yaw = (float) Math.toDegrees(Math.atan2(-diff.x, diff.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-diff.y, Math.sqrt(diff.x*diff.x + diff.z*diff.z)));

        client.player.networkHandler.sendPacket(
                new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, client.player.isOnGround(), false)
        );
        client.player.setYaw(yaw);
        client.player.setPitch(pitch);
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            bestLanding = null;
            bestFallDistance = 0;
            return;
        }

        if (ticksUntilRecompute-- <= 0) {
            ticksUntilRecompute = RECOMPUTE_INTERVAL_TICKS;
            recomputeBestSpot();
        }
    }

    private void recomputeBestSpot() {
        Candidate best = findBestCandidate();
        if (best != null) {
            bestLanding = best.block;
            bestFallDistance = best.drop;
        } else {
            bestLanding = null;
            bestFallDistance = 0;
        }
    }

    private Candidate findBestCandidate() {
        Vec3d start = mc.player.getEyePos();
        float yaw = mc.player.getYaw();
        double maxDistSq = maxDistance.get() * maxDistance.get();

        // Use explicit getters to avoid mapping issues
        double px = mc.player.getX();
        double py = mc.player.getY();
        double pz = mc.player.getZ();

        List<Candidate> candidates = new ArrayList<>();

        for (double pitch = MIN_PITCH; pitch <= MAX_PITCH; pitch += ANGLE_STEP_DEGREES) {
            BlockPos landing = simulateThrow(start, (float) pitch, yaw);
            if (landing == null) continue;
            if (!mc.world.getBlockState(landing).isSolidBlock(mc.world, landing)) continue;

            double dx = landing.getX() + 0.5 - px;
            double dz = landing.getZ() + 0.5 - pz;
            double horizDist = Math.sqrt(dx*dx + dz*dz);

            double dy = landing.getY() + 0.5 - py;
            double distSq = horizDist*horizDist + dy*dy;
            if (distSq > maxDistSq) continue;

            double drop = py - landing.getY() - 1.0;
            candidates.add(new Candidate(landing, drop, horizDist));
        }

        if (candidates.isEmpty()) return null;

        candidates.sort((a, b) -> {
            int dropComp = Double.compare(b.drop, a.drop);
            if (dropComp != 0) return dropComp;
            return Double.compare(a.horizontalDistance, b.horizontalDistance);
        });

        return candidates.get(0);
    }

    private BlockPos simulateThrow(Vec3d start, float pitch, float yaw) {
        Vec3d velocity = getRotationVector(pitch, yaw).multiply(THROW_POWER);
        Vec3d pos = start;

        for (int tick = 0; tick < MAX_SIMULATED_TICKS; tick++) {
            Vec3d next = pos.add(velocity);

            BlockPos currentBlock = new BlockPos((int)pos.x, (int)pos.y, (int)pos.z);
            BlockPos below = currentBlock.down();

            if (mc.world.getBlockState(below).isSolidBlock(mc.world, below)) {
                return currentBlock;
            }

            BlockPos midBlock = new BlockPos(
                    (int)((pos.x + next.x)/2),
                    (int)((pos.y + next.y)/2),
                    (int)((pos.z + next.z)/2)
            );
            if (mc.world.getBlockState(midBlock).isSolidBlock(mc.world, midBlock)) {
                return midBlock;
            }

            pos = next;
            velocity = velocity.add(0, -GRAVITY_PER_TICK, 0).multiply(DRAG_PER_TICK);

            if (pos.y < mc.world.getBottomY() - 8) {
                return null;
            }
        }
        return null;
    }

    private static Vec3d getRotationVector(float pitch, float yaw) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double x = -Math.sin(yawRad) * Math.cos(pitchRad);
        double y = -Math.sin(pitchRad);
        double z = Math.cos(yawRad) * Math.cos(pitchRad);
        return new Vec3d(x, y, z);
    }
}
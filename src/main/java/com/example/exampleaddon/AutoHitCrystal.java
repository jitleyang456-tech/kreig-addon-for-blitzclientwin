package com.example.exampleaddon;

import com.blitz.module.Category;
import com.blitz.module.Module;
import com.blitz.module.setting.BoolSetting;
import com.blitz.module.setting.DoubleSetting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class AutoHitCrystal extends Module {
    public static AutoHitCrystal INSTANCE;

    // Settings
    private final BoolSetting enabled = addSetting(new BoolSetting("Enabled", true));
    private final DoubleSetting cooldown = addSetting(new DoubleSetting("Cooldown (s)", 0.5, 0.05, 5.0, 0.05));
    private final DoubleSetting delay = addSetting(new DoubleSetting("Delay (ms)", 200, 0, 2000, 50));
    private final DoubleSetting smoothing = addSetting(new DoubleSetting("Smoothing (ticks)", 5, 0, 20, 1));
    private final BoolSetting evenGroundOnly = addSetting(new BoolSetting("Even Ground Only", true));
    private final BoolSetting onlyPlayers = addSetting(new BoolSetting("Only Players", true));
    private final BoolSetting autoSwitch = addSetting(new BoolSetting("Auto-switch to Obsidian", true));
    private final DoubleSetting distance = addSetting(new DoubleSetting("Distance (blocks)", 1.0, 0.5, 3.0, 0.5));

    // Cooldown & pending state
    private long lastPlaceTime = 0;
    private boolean pending = false;
    private long pendingTime = 0;
    private BlockPos pendingClickBlock;
    private Vec3d pendingHitPos;

    // Smoothing state
    private boolean smoothingActive = false;
    private float startYaw, startPitch;
    private float targetYaw, targetPitch;
    private int smoothingTicks;
    private int smoothingProgress;

    public AutoHitCrystal(Category category) {
        super("AutoHitCrystal", "Places obsidian on the ground after an attack (Dtap)", category);
        INSTANCE = this;

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client == null || client.player == null) return;
            if (!isEnabled()) return;

            if (pending) {
                if (System.currentTimeMillis() >= pendingTime) {
                    // Start smoothing (if any)
                    int smoothTicks = (int) smoothing.get().doubleValue();
                    if (smoothTicks <= 0) {
                        // Instant placement
                        executePendingPlacement(client);
                        pending = false;
                    } else {
                        // Start smoothing
                        smoothingActive = true;
                        smoothingTicks = smoothTicks;
                        smoothingProgress = 0;
                        startYaw = client.player.getYaw();
                        startPitch = client.player.getPitch();
                        // targetYaw, targetPitch are already stored
                        pending = false; // no longer pending, we are smoothing
                    }
                }
            }

            if (smoothingActive) {
                // Perform rotation smoothing
                smoothingProgress++;
                float progress = Math.min(1.0f, (float) smoothingProgress / smoothingTicks);
                // Interpolate with sine easing for smoothness
                float eased = (float) (1 - Math.cos(progress * Math.PI / 2)); // ease-out
                float currentYaw = startYaw + (targetYaw - startYaw) * eased;
                float currentPitch = startPitch + (targetPitch - startPitch) * eased;
                // Normalize yaw
                currentYaw = normalizeYaw(currentYaw);

                // Set client rotation
                client.player.setYaw(currentYaw);
                client.player.setPitch(currentPitch);
                // Send packet
                client.player.networkHandler.sendPacket(
                        new PlayerMoveC2SPacket.LookAndOnGround(currentYaw, currentPitch, client.player.isOnGround(), false)
                );

                if (smoothingProgress >= smoothingTicks) {
                    // Final set to exact target to avoid rounding errors
                    client.player.setYaw(targetYaw);
                    client.player.setPitch(targetPitch);
                    client.player.networkHandler.sendPacket(
                            new PlayerMoveC2SPacket.LookAndOnGround(targetYaw, targetPitch, client.player.isOnGround(), false)
                    );
                    smoothingActive = false;
                    // Now execute placement
                    executePendingPlacement(client);
                }
            }
        });
    }

    // Helper to normalize yaw to [-180, 180)
    private float normalizeYaw(float yaw) {
        yaw = yaw % 360;
        if (yaw >= 180) yaw -= 360;
        if (yaw < -180) yaw += 360;
        return yaw;
    }

    public void onHitEntity(Entity target) {
        if (!isEnabled()) return;
        if (!(target instanceof LivingEntity)) return;

        if (onlyPlayers.get() && !(target instanceof PlayerEntity)) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null) return;

        if (evenGroundOnly.get()) {
            if (!client.player.isOnGround() || !target.isOnGround()) {
                return;
            }
            double yDiff = Math.abs(client.player.getY() - target.getY());
            if (yDiff > 0.5) {
                return;
            }
        }

        long now = System.currentTimeMillis();
        double cooldownSec = cooldown.get();
        if (now - lastPlaceTime < (long)(cooldownSec * 1000)) {
            return;
        }

        if (pending || smoothingActive) return;

        // Calculate placement target
        BlockPos feetPos = client.player.getBlockPos();
        float yaw = client.player.getYaw();
        Vec3d forward = new Vec3d(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
        double dist = distance.get();
        int dx = (int) Math.round(forward.x * dist);
        int dz = (int) Math.round(forward.z * dist);

        BlockPos targetAirBlock = new BlockPos(feetPos.getX() + dx, feetPos.getY(), feetPos.getZ() + dz);
        if (!client.world.getBlockState(targetAirBlock).isReplaceable()) {
            return;
        }

        BlockPos clickBlock = targetAirBlock.down();
        Direction face = Direction.UP;
        Vec3d hitPos = clickBlock.toCenterPos().add(0, 0.5, 0);

        // Calculate target rotation
        Vec3d eyePos = client.player.getEyePos();
        Vec3d diff = hitPos.subtract(eyePos);
        float yawTarget = (float) Math.toDegrees(Math.atan2(-diff.x, diff.z));
        float pitchTarget = (float) Math.toDegrees(Math.atan2(-diff.y, Math.sqrt(diff.x*diff.x + diff.z*diff.z)));

        // Store for pending
        pending = true;
        pendingClickBlock = clickBlock;
        pendingHitPos = hitPos;
        targetYaw = yawTarget;
        targetPitch = pitchTarget;
        pendingTime = System.currentTimeMillis() + (long)delay.get().doubleValue();
    }

    private void executePendingPlacement(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) return;

        // Set final rotation (should already be set, but ensure)
        client.player.setYaw(targetYaw);
        client.player.setPitch(targetPitch);
        client.player.networkHandler.sendPacket(
                new PlayerMoveC2SPacket.LookAndOnGround(targetYaw, targetPitch, client.player.isOnGround(), false)
        );

        // Ensure obsidian in hand
        if (autoSwitch.get()) {
            int slot = findObsidianSlot(client);
            if (slot == -1) {
                client.player.sendMessage(Text.literal("§cNo obsidian in hotbar!"), true);
                return;
            }
            client.player.getInventory().setSelectedSlot(slot);
        } else {
            if (!client.player.getMainHandStack().isOf(Items.OBSIDIAN)) {
                client.player.sendMessage(Text.literal("§cHold obsidian in your main hand!"), true);
                return;
            }
        }

        BlockHitResult hitResult = new BlockHitResult(pendingHitPos, Direction.UP, pendingClickBlock, false);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);

        lastPlaceTime = System.currentTimeMillis();
    }

    private int findObsidianSlot(MinecraftClient client) {
        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getStack(i).isOf(Items.OBSIDIAN)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void onDisable() {
        pending = false;
        smoothingActive = false;
    }
}
package com.example.exampleaddon;

import com.blitz.module.Category;
import com.blitz.module.Module;
import com.blitz.module.setting.BoolSetting;
import com.blitz.module.setting.DoubleSetting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class FeetPlaceModule extends Module {
    public static FeetPlaceModule INSTANCE;

    // ---- Settings ----
    private final BoolSetting smartMode = addSetting(new BoolSetting("Smart Mode", true));
    private final BoolSetting onlyHighGround = addSetting(new BoolSetting("Only High Ground", true));
    private final BoolSetting smoothRotation = addSetting(new BoolSetting("Smooth Rotation", true));
    private final DoubleSetting smoothSpeed = addSetting(new DoubleSetting("Smooth Speed (°/tick)", 10.0, 2.0, 45.0, 1.0));
    private final DoubleSetting range = addSetting(new DoubleSetting("Range", 8.0, 3.0, 16.0, 1.0));
    private final DoubleSetting cooldown = addSetting(new DoubleSetting("Cooldown (s)", 0.5, 0.1, 3.0, 0.1));

    // ---- Internal state ----
    private long lastPlaceTime = 0;
    private boolean pendingPlace = false;
    private int pendingTicks = 0;
    private int pendingY = 0;

    // ---- Rotation smoothing state ----
    private boolean rotating = false;
    private float targetYaw;
    private float targetPitch;
    private float startYaw;
    private float startPitch;
    private float remainingYaw;
    private float remainingPitch;
    private boolean placementReady = false; // true once rotation is complete

    // ---- Self‑placement tracking (for mixin) ----
    private BlockPos lastSelfPlacedAnchor = null;
    private long lastSelfPlaceTime = 0;

    public FeetPlaceModule(Category category) {
        super("FeetPlace", "Places obsidian at your feet to attempt to block explosion damage", category);
        INSTANCE = this;

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client == null || client.player == null || !isEnabled()) return;
            onTick(client);
        });
    }

    private void onTick(MinecraftClient client) {
        // ---- Handle rotation smoothing ----
        if (rotating) {
            // Gradual rotation
            float yawStep = (float) Math.min(Math.abs(remainingYaw), smoothSpeed.get());
            float pitchStep = (float) Math.min(Math.abs(remainingPitch), smoothSpeed.get());
            // Keep sign
            float newYaw = client.player.getYaw() + Math.signum(remainingYaw) * yawStep;
            float newPitch = client.player.getPitch() + Math.signum(remainingPitch) * pitchStep;

            // Clamp so we don't overshoot
            if (Math.abs(remainingYaw) <= yawStep) newYaw = targetYaw;
            if (Math.abs(remainingPitch) <= pitchStep) newPitch = targetPitch;

            client.player.setYaw(newYaw);
            client.player.setPitch(newPitch);
            // Send rotation packet each tick during smoothing
            client.player.networkHandler.sendPacket(
                    new PlayerMoveC2SPacket.LookAndOnGround(newYaw, newPitch, client.player.isOnGround(), false)
            );

            remainingYaw = targetYaw - newYaw;
            remainingPitch = targetPitch - newPitch;

            // Check if we reached target
            if (Math.abs(remainingYaw) < 0.5f && Math.abs(remainingPitch) < 0.5f) {
                // Snap to exact target
                client.player.setYaw(targetYaw);
                client.player.setPitch(targetPitch);
                remainingYaw = 0;
                remainingPitch = 0;
                rotating = false;
                placementReady = true;
            }
        }

        // ---- Placement logic ----
        if (smartMode.get()) {
            // Smart mode: only when triggered by mixin
            if (pendingPlace && pendingTicks-- <= 0) {
                if (smoothRotation.get()) {
                    // If smoothing, start rotation and place after it finishes
                    if (!rotating && !placementReady) {
                        // Calculate target and start rotating
                        startPlacement(client);
                    }
                    if (placementReady) {
                        performPlacement(client);
                        pendingPlace = false;
                        placementReady = false;
                    }
                } else {
                    // Instant rotation + placement
                    performPlacement(client);
                    pendingPlace = false;
                }
            }
        } else {
            // Dumb mode: always place (with cooldown)
            if (smoothRotation.get()) {
                // If not already rotating and not ready, start
                if (!rotating && !placementReady) {
                    // Only start if we're not on cooldown
                    long now = System.currentTimeMillis();
                    if (now - lastPlaceTime >= (long)(cooldown.get() * 1000)) {
                        startPlacement(client);
                    }
                }
                if (placementReady) {
                    performPlacement(client);
                    placementReady = false;
                }
            } else {
                // Instant placement
                performPlacement(client);
            }
        }
    }

    /** Starts the rotation to the target block (if not already rotating) */
    private void startPlacement(MinecraftClient client) {
        if (rotating) return;

        // Calculate target block and hit result
        BlockHitResult hitResult = calculateTarget(client);
        if (hitResult == null) return; // invalid placement

        // Compute target yaw/pitch to face the hit position
        Vec3d hitPos = hitResult.getPos();
        Vec3d eyePos = client.player.getEyePos();
        Vec3d diff = hitPos.subtract(eyePos);
        float yaw = (float) Math.toDegrees(Math.atan2(-diff.x, diff.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-diff.y, Math.sqrt(diff.x*diff.x + diff.z*diff.z)));

        // Normalize yaw to [-180,180] for smooth interpolation
        float currentYaw = client.player.getYaw();
        float diffYaw = yaw - currentYaw;
        while (diffYaw > 180) diffYaw -= 360;
        while (diffYaw < -180) diffYaw += 360;
        yaw = currentYaw + diffYaw;

        targetYaw = yaw;
        targetPitch = pitch;
        startYaw = currentYaw;
        startPitch = client.player.getPitch();
        remainingYaw = targetYaw - startYaw;
        remainingPitch = targetPitch - startPitch;
        rotating = true;
        placementReady = false;
    }

    /** Actually places the obsidian at the pre‑calculated target */
    private void performPlacement(MinecraftClient client) {
        if (client == null || client.player == null) return;

        BlockHitResult hitResult = calculateTarget(client);
        if (hitResult == null) return;

        // Switch to obsidian
        int obsidianSlot = findObsidianSlot(client);
        if (obsidianSlot == -1) return;

        // Ensure we're facing the correct direction (already done if smoothing)
        if (!smoothRotation.get()) {
            // Snap rotation instantly
            Vec3d hitPos = hitResult.getPos();
            Vec3d eyePos = client.player.getEyePos();
            Vec3d diff = hitPos.subtract(eyePos);
            float yaw = (float) Math.toDegrees(Math.atan2(-diff.x, diff.z));
            float pitch = (float) Math.toDegrees(Math.atan2(-diff.y, Math.sqrt(diff.x*diff.x + diff.z*diff.z)));
            client.player.setYaw(yaw);
            client.player.setPitch(pitch);
            client.player.networkHandler.sendPacket(
                    new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, client.player.isOnGround(), false)
            );
        }

        // Place
        int prevSlot = client.player.getInventory().getSelectedSlot();
        client.player.getInventory().setSelectedSlot(obsidianSlot);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);
        client.player.getInventory().setSelectedSlot(prevSlot);

        lastPlaceTime = System.currentTimeMillis();
    }

    /** Calculates the target block and returns a BlockHitResult, or null if invalid */
    private BlockHitResult calculateTarget(MinecraftClient client) {
        BlockPos feetPos = client.player.getBlockPos();
        float yaw = client.player.getYaw();
        Vec3d forward = new Vec3d(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
        int dx = (int) Math.round(forward.x);
        int dz = (int) Math.round(forward.z);
        BlockPos targetAirBlock = new BlockPos(feetPos.getX() + dx, feetPos.getY(), feetPos.getZ() + dz);

        // Validation
        if (client.world.getBlockState(targetAirBlock).getBlock() == Blocks.OBSIDIAN) {
            return null;
        }
        if (!client.world.getBlockState(targetAirBlock).isReplaceable()) {
            return null;
        }
        BlockPos clickBlock = targetAirBlock.down();
        if (!client.world.getBlockState(clickBlock).isSolidBlock(client.world, clickBlock)) {
            return null;
        }
        Vec3d eyePos = client.player.getEyePos();
        Vec3d blockCenter = Vec3d.ofCenter(targetAirBlock);
        if (eyePos.distanceTo(blockCenter) > 6.0) {
            return null;
        }

        Vec3d hitPos = clickBlock.toCenterPos().add(0, 0.5, 0);
        return new BlockHitResult(hitPos, Direction.UP, clickBlock, false);
    }

    // ---- Public methods for mixin ----
    public void triggerSmartPlace(int yLevel) {
        if (!smartMode.get()) return;
        if (onlyHighGround.get()) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;
            if (client.player.getY() <= yLevel) return;
        }
        pendingPlace = true;
        pendingTicks = 2;
        pendingY = yLevel;
    }

    public void onSelfPlaceAnchor(BlockPos pos) {
        lastSelfPlacedAnchor = pos;
        lastSelfPlaceTime = System.currentTimeMillis();
    }

    public boolean isSelfPlacedAnchor(BlockPos pos) {
        if (lastSelfPlacedAnchor == null) return false;
        if (!lastSelfPlacedAnchor.equals(pos)) return false;
        if (System.currentTimeMillis() - lastSelfPlaceTime > 2000) {
            lastSelfPlacedAnchor = null;
            return false;
        }
        return true;
    }

    public double getRange() {
        return range.get();
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
        pendingPlace = false;
        pendingTicks = 0;
        rotating = false;
        placementReady = false;
        lastSelfPlacedAnchor = null;
    }
}
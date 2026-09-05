package com.example.exampleaddon.mixin;

import com.example.exampleaddon.FeetPlaceModule;
import com.example.exampleaddon.TotemPopModule;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

    private static final byte TOTEM_OF_UNDYING_STATUS = 35;

    // ========== 1. TOTEM POP DETECTION (existing) ==========
    @Inject(method = "onEntityStatus", at = @At("HEAD"))
    private void blitzExampleAddon$onEntityStatus(EntityStatusS2CPacket packet, CallbackInfo ci) {
        if (packet.getStatus() != TOTEM_OF_UNDYING_STATUS) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        Entity entity = packet.getEntity(client.world);
        if (entity instanceof LivingEntity living) {
            TotemPopModule.onTotemPop(living);
        }
    }

    // ========== 2. RESPAWN ANCHOR DETECTION (anonymous block updates) ==========
    @Inject(method = "onBlockUpdate", at = @At("HEAD"))
    private void blitzExampleAddon$onBlockUpdate(BlockUpdateS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;

        BlockPos pos = packet.getPos();
        // Use getState() instead of getBlockState()
        if (packet.getState().getBlock() == Blocks.RESPAWN_ANCHOR) {
            // Ignore our own recently placed anchors
            if (FeetPlaceModule.INSTANCE != null &&
                    FeetPlaceModule.INSTANCE.isSelfPlacedAnchor(pos)) {
                return;
            }

            double range = FeetPlaceModule.INSTANCE != null
                    ? FeetPlaceModule.INSTANCE.getRange()
                    : 8.0;
            if (client.player.getBlockPos().getSquaredDistance(pos) > range * range) return;

            FeetPlaceModule.INSTANCE.triggerSmartPlace(pos.getY());
        }
    }

    // ========== 3. END CRYSTAL DETECTION (entity spawn) ==========
    @Inject(method = "onEntitySpawn", at = @At("HEAD"))
    private void blitzExampleAddon$onEntitySpawn(EntitySpawnS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;

        // Check if the spawned entity is an end crystal
        if (packet.getEntityType() == EntityType.END_CRYSTAL) {
            BlockPos crystalPos = new BlockPos(
                    (int) packet.getX(),
                    (int) packet.getY(),
                    (int) packet.getZ()
            );

            // Range check
            double range = FeetPlaceModule.INSTANCE != null
                    ? FeetPlaceModule.INSTANCE.getRange()
                    : 8.0;
            if (client.player.getBlockPos().getSquaredDistance(crystalPos) > range * range) return;

            // Trigger smart feet‑placement
            FeetPlaceModule.INSTANCE.triggerSmartPlace(crystalPos.getY());
        }
    }
}
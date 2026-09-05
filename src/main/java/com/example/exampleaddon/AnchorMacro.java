package com.example.exampleaddon;

import com.blitz.module.Category;
import com.blitz.module.Module;
import com.blitz.module.setting.DoubleSetting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class AnchorMacro extends Module {

    private final DoubleSetting actionDelay = addSetting(new DoubleSetting("Action Delay (ticks)", 3, 1, 10, 1));

    private enum State { IDLE, PLACING, WAITING_PLACE, CHARGING, WAITING_CHARGE, EXPLODING, DONE }
    private State state = State.IDLE;
    private int tickCounter = 0;

    private BlockPos targetBlock;
    private BlockHitResult placementHit;
    private int anchorSlot = -1;
    private int glowstoneSlot = -1;
    private int previousSlot = -1;
    private BlockPos anchorPos;

    private boolean registered = false;

    public AnchorMacro(Category category) {
        super("AnchorMacro", "Places, charges once, and explodes a respawn anchor, then disables", category);

        if (!registered) {
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (client == null || client.player == null) return;
                if (isEnabled()) {
                    onTick(client);
                }
            });
            registered = true;
        }
    }

    @Override
    protected void onEnable() {
        state = State.IDLE;
        tickCounter = 0;
        startSequence(MinecraftClient.getInstance());
    }

    private void onTick(MinecraftClient client) {
        switch (state) {
            case PLACING:
                if (tickCounter-- <= 0) {
                    placeAnchor(client);
                }
                break;
            case WAITING_PLACE:
                if (tickCounter-- <= 0) {
                    if (!client.world.getBlockState(anchorPos).isOf(Blocks.RESPAWN_ANCHOR)) {
                        // Placement didn't land where expected — bail instead of
                        // charging/exploding whatever's actually there.
                        client.player.sendMessage(Text.literal("§cAnchor placement failed!"), true);
                        client.player.getInventory().setSelectedSlot(previousSlot);
                        setEnabled(false);
                        return;
                    }
                    // Switch to glowstone and start charging
                    client.player.getInventory().setSelectedSlot(glowstoneSlot);
                    state = State.CHARGING;
                    tickCounter = 0; // immediate
                }
                break;
            case CHARGING:
                if (tickCounter-- <= 0) {
                    chargeAnchor(client);
                }
                break;
            case WAITING_CHARGE:
                if (tickCounter-- <= 0) {
                    // Switch back to anchor for detonation
                    client.player.getInventory().setSelectedSlot(anchorSlot);
                    state = State.EXPLODING;
                    tickCounter = 0;
                }
                break;
            case EXPLODING:
                if (tickCounter-- <= 0) {
                    explodeAnchor(client);
                }
                break;
            case DONE:
                setEnabled(false);
                state = State.IDLE;
                break;
            default:
                break;
        }
    }

    private void startSequence(MinecraftClient client) {
        // Find items
        anchorSlot = findItemSlot(Items.RESPAWN_ANCHOR);
        glowstoneSlot = findItemSlot(Items.GLOWSTONE);
        if (anchorSlot == -1 || glowstoneSlot == -1) {
            client.player.sendMessage(Text.literal("§cMissing anchor or glowstone in hotbar!"), true);
            setEnabled(false);
            return;
        }

        // Raycast to find block to place on
        HitResult hitResult = client.player.raycast(6.0, 1.0f, false);
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
            client.player.sendMessage(Text.literal("§cNo block in sight!"), true);
            setEnabled(false);
            return;
        }
        BlockHitResult hit = (BlockHitResult) hitResult;

        targetBlock = hit.getBlockPos();
        placementHit = hit;

        // Where the anchor will actually land: if the clicked block is
        // replaceable (snow layers, fire, tall grass, water, etc.), vanilla
        // places the new block AT that same position, not offset onto the
        // clicked face. Only non-replaceable (solid) blocks push the anchor
        // out onto the face. Getting this wrong doesn't change where the
        // real anchor ends up — the client/server already resolve that
        // correctly from the raw hit result — it only breaks the mod's own
        // bookkeeping of where to look for and interact with it afterward,
        // which is what caused the false failures and wrong charge/explode
        // targeting on snow and fire.
        BlockState clickedState = client.world.getBlockState(targetBlock);
        anchorPos = clickedState.isReplaceable() ? targetBlock : targetBlock.offset(hit.getSide());

        previousSlot = client.player.getInventory().getSelectedSlot();
        client.player.getInventory().setSelectedSlot(anchorSlot);

        state = State.PLACING;
        tickCounter = 0;
    }

    private void placeAnchor(MinecraftClient client) {
        // Right‑click to place the anchor
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, placementHit);

        // Wait a few ticks for the server to process
        state = State.WAITING_PLACE;
        tickCounter = actionDelay.get().intValue();
    }

    private void chargeAnchor(MinecraftClient client) {
        // Right‑click the anchor with glowstone
        BlockHitResult anchorHit = new BlockHitResult(
                Vec3d.ofCenter(anchorPos),
                Direction.UP,
                anchorPos,
                false
        );
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, anchorHit);

        // Wait a few ticks before detonation
        state = State.WAITING_CHARGE;
        tickCounter = actionDelay.get().intValue();
    }

    private void explodeAnchor(MinecraftClient client) {
        // Right‑click the anchor with the anchor itself (explode)
        BlockHitResult anchorHit = new BlockHitResult(
                Vec3d.ofCenter(anchorPos),
                Direction.UP,
                anchorPos,
                false
        );
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, anchorHit);

        // Restore previous slot and finish
        client.player.getInventory().setSelectedSlot(previousSlot);
        state = State.DONE;
    }

    private int findItemSlot(net.minecraft.item.Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(item)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void onDisable() {
        state = State.IDLE;
        tickCounter = 0;
    }
}
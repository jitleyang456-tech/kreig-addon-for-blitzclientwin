package com.example.exampleaddon;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import com.blitz.module.Category;
import com.blitz.module.Module;
import com.blitz.module.setting.DoubleSetting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Same sequence as AnchorMacro (place -> charge -> explode, fully automatic,
 * then disables) but inserts an obsidian shield first. The anchor is placed
 * one block further out from the player than the obsidian, along the same
 * horizontal line — player -> obsidian -> anchor — so the obsidian absorbs
 * the blast on the player's side while the anchor's far side stays open.
 */
public class SafeAnchorMacro extends Module {

    private final DoubleSetting actionDelay = addSetting(new DoubleSetting("Action Delay (ticks)", 3, 1, 10, 1));
    private final DoubleSetting rotationDelay = addSetting(new DoubleSetting("Rotation Delay (ticks)", 1, 0, 5, 1));

    private enum State {
        IDLE,
        PLACE_OBSIDIAN, WAITING_OBSIDIAN,
        ROTATE_ANCHOR, PLACING, WAITING_PLACE,
        ROTATE_CHARGE, CHARGING, WAITING_CHARGE,
        ROTATE_EXPLODE, EXPLODING, DONE
    }
    private State state = State.IDLE;
    private int tickCounter = 0;

    private BlockPos targetBlock;
    private BlockHitResult obsidianPlacementHit;
    private BlockHitResult anchorPlacementHit;

    private int obsidianSlot = -1;
    private int anchorSlot = -1;
    private int glowstoneSlot = -1;
    private int previousSlot = -1;

    private BlockPos obsidianPos;
    private BlockPos anchorPos;
    private Direction awayFromPlayer;

    private boolean registered = false;

    public SafeAnchorMacro(Category category) {
        super("SafeAnchorMacro", "Places an obsidian shield, an anchor beyond it, charges once, and explodes, then disables", category);

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
            case PLACE_OBSIDIAN:
                if (tickCounter-- <= 0) {
                    placeObsidian(client);
                }
                break;
            case WAITING_OBSIDIAN:
                if (tickCounter-- <= 0) {
                    onObsidianConfirmed(client);
                }
                break;
            case ROTATE_ANCHOR:
                if (tickCounter-- <= 0) {
                    rotateTo(client, anchorPlacementHit.getPos());
                    state = State.PLACING;
                    tickCounter = (int) rotationDelay.get().intValue();
                }
                break;
            case PLACING:
                if (tickCounter-- <= 0) {
                    placeAnchor(client);
                }
                break;
            case WAITING_PLACE:
                if (tickCounter-- <= 0) {
                    // Switch to glowstone and start charging
                    client.player.getInventory().setSelectedSlot(glowstoneSlot);
                    state = State.ROTATE_CHARGE;
                    tickCounter = 0; // immediate
                }
                break;
            case ROTATE_CHARGE:
                if (tickCounter-- <= 0) {
                    rotateTo(client, Vec3d.ofCenter(anchorPos));
                    state = State.CHARGING;
                    tickCounter = (int) rotationDelay.get().intValue();
                }
                break;
            case CHARGING:
                if (tickCounter-- <= 0) {
                    chargeAnchor(client);
                }
                break;
            case WAITING_CHARGE:
                if (tickCounter-- <= 0) {
                    // Switch to obsidian (any non-glowstone item works) for detonation
                    client.player.getInventory().setSelectedSlot(obsidianSlot);
                    state = State.ROTATE_EXPLODE;
                    tickCounter = 0;
                }
                break;
            case ROTATE_EXPLODE:
                if (tickCounter-- <= 0) {
                    rotateTo(client, Vec3d.ofCenter(anchorPos));
                    state = State.EXPLODING;
                    tickCounter = (int) rotationDelay.get().intValue();
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
        obsidianSlot = findItemSlot(Items.OBSIDIAN);
        anchorSlot = findItemSlot(Items.RESPAWN_ANCHOR);
        glowstoneSlot = findItemSlot(Items.GLOWSTONE);
        if (obsidianSlot == -1 || anchorSlot == -1 || glowstoneSlot == -1) {
            client.player.sendMessage(Text.literal("§cMissing obsidian, anchor, or glowstone in hotbar!"), true);
            setEnabled(false);
            return;
        }

        // Raycast to find block to place the obsidian shield on
        HitResult hitResult = client.player.raycast(6.0, 1.0f, false);
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
            client.player.sendMessage(Text.literal("§cNo block in sight!"), true);
            setEnabled(false);
            return;
        }
        BlockHitResult hit = (BlockHitResult) hitResult;

        targetBlock = hit.getBlockPos();
        obsidianPlacementHit = hit;
        obsidianPos = resolvePlacementPos(client, hit);

        previousSlot = client.player.getInventory().getSelectedSlot();
        client.player.getInventory().setSelectedSlot(obsidianSlot);

        state = State.PLACE_OBSIDIAN;
        tickCounter = 0;
    }

    private void placeObsidian(MinecraftClient client) {
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, obsidianPlacementHit);

        state = State.WAITING_OBSIDIAN;
        tickCounter = actionDelay.get().intValue();
    }

    private void onObsidianConfirmed(MinecraftClient client) {
        if (!client.world.getBlockState(obsidianPos).isOf(Blocks.OBSIDIAN)) {
            // Obsidian didn't land (blocked, someone else's placement, etc).
            client.player.sendMessage(Text.literal("§cObsidian placement failed!"), true);
            client.player.getInventory().setSelectedSlot(previousSlot);
            setEnabled(false);
            return;
        }

        // Dominant horizontal axis from the player through the obsidian —
        // the anchor goes one block further out along this same line, so
        // the obsidian stays between the player and the anchor.
        awayFromPlayer = horizontalDirectionAwayFromPlayer(client, obsidianPos);
        anchorPos = obsidianPos.offset(awayFromPlayer);

        if (!client.world.getBlockState(anchorPos).isReplaceable()) {
            client.player.sendMessage(Text.literal("§cAnchor spot is occupied!"), true);
            client.player.getInventory().setSelectedSlot(previousSlot);
            setEnabled(false);
            return;
        }

        // Rather than assuming the obsidian's far face is the only valid way
        // to reach anchorPos, search for whichever adjacent solid face is
        // actually clickable — preferring the obsidian's far face (keeps the
        // shielding geometry) but falling back to any other reachable face
        // if that one is somehow obstructed or out of range.
        BlockHitResult foundHit = findValidPlacementHit(client, anchorPos, awayFromPlayer.getOpposite());
        if (foundHit == null) {
            client.player.sendMessage(Text.literal("§cNo valid face to place the anchor from!"), true);
            client.player.getInventory().setSelectedSlot(previousSlot);
            setEnabled(false);
            return;
        }
        anchorPlacementHit = foundHit;

        client.player.getInventory().setSelectedSlot(anchorSlot);
        state = State.ROTATE_ANCHOR;
        tickCounter = 0;
    }

    private void placeAnchor(MinecraftClient client) {
        // Right‑click to place the anchor beyond the obsidian
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, anchorPlacementHit);

        // Wait a few ticks for the server to process
        state = State.WAITING_PLACE;
        tickCounter = actionDelay.get().intValue();
    }

    private void chargeAnchor(MinecraftClient client) {
        // Right‑click any exposed, reachable face of the anchor with glowstone
        // — don't assume the geometry-derived "toward player" face is still
        // the one that's actually clickable.
        BlockHitResult anchorHit = findValidInteractionHit(client, anchorPos, awayFromPlayer.getOpposite());
        if (anchorHit == null) {
            client.player.sendMessage(Text.literal("§cNo valid face to charge the anchor from!"), true);
            client.player.getInventory().setSelectedSlot(previousSlot);
            setEnabled(false);
            return;
        }
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, anchorHit);

        // Wait a few ticks before detonation
        state = State.WAITING_CHARGE;
        tickCounter = actionDelay.get().intValue();
    }

    private void explodeAnchor(MinecraftClient client) {
        // Right‑click any exposed, reachable face with anything but glowstone
        // (obsidian here) — triggers detonation
        BlockHitResult anchorHit = findValidInteractionHit(client, anchorPos, awayFromPlayer.getOpposite());
        if (anchorHit == null) {
            client.player.sendMessage(Text.literal("§cNo valid face to trigger the anchor from!"), true);
            client.player.getInventory().setSelectedSlot(previousSlot);
            setEnabled(false);
            return;
        }
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, anchorHit);

        // Restore previous slot and finish
        client.player.getInventory().setSelectedSlot(previousSlot);
        state = State.DONE;
    }

    /**
     * Snaps the player's yaw/pitch to look directly at a world-space point.
     * Needed because the BlockHitResults we build for the anchor face are
     * synthetic — the player's actual rotation never moved to match them on
     * its own, so without this the server (or any anti-cheat resolving reach
     * / look direction) sees a placement that doesn't match where the player
     * is actually facing and can reject it or resolve it against the old
     * facing instead, which is what was causing the anchor to land back
     * inside/against the obsidian instead of past it.
     */
    private void rotateTo(MinecraftClient client, Vec3d target) {
        Vec3d eyePos = client.player.getEyePos();
        double dx = target.x - eyePos.x;
        double dy = target.y - eyePos.y;
        double dz = target.z - eyePos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDist));

        client.player.setYaw(yaw);
        client.player.setPitch(pitch);
    }

    /** Default survival block-interaction reach; adjust if your server uses a different value. */
    private static final double PLACEMENT_REACH = 5.0;

    /**
     * Finds a valid way to place a new block AT targetPos, instead of
     * assuming one specific face is always clickable. Tries preferredDir
     * first (the direction from targetPos to the neighbor block we'd like to
     * click, e.g. "back toward the obsidian"), then falls back through the
     * other five directions. A direction is valid if the neighbor in that
     * direction is solid (non-replaceable, i.e. actually clickable) and its
     * face is within reach of the player's eyes.
     *
     * @param preferredDir direction from targetPos to the neighbor to try first
     * @return a BlockHitResult ready to hand to interactBlock, or null if no face works
     */
    private BlockHitResult findValidPlacementHit(MinecraftClient client, BlockPos targetPos, Direction preferredDir) {
        Vec3d eyePos = client.player.getEyePos();

        Direction[] order = orderedDirections(preferredDir);
        for (Direction dir : order) {
            BlockPos neighborPos = targetPos.offset(dir);
            BlockState neighborState = client.world.getBlockState(neighborPos);
            if (neighborState.isReplaceable()) continue; // nothing solid here to click against

            Direction sideTowardTarget = dir.getOpposite();
            Vec3d faceCenter = Vec3d.ofCenter(neighborPos).add(
                    sideTowardTarget.getOffsetX() * 0.5,
                    sideTowardTarget.getOffsetY() * 0.5,
                    sideTowardTarget.getOffsetZ() * 0.5
            );

            if (eyePos.squaredDistanceTo(faceCenter) > PLACEMENT_REACH * PLACEMENT_REACH) continue;

            return new BlockHitResult(faceCenter, sideTowardTarget, neighborPos, false);
        }
        return null;
    }

    /**
     * Finds a valid way to right-click an EXISTING block (the already-placed
     * anchor), instead of assuming one specific face is still the clickable
     * one. A face is valid if the space just outside it is open (so the face
     * isn't buried against another block) and it's within reach.
     *
     * @param preferredDir direction from blockPos to the exposed face to try first
     * @return a BlockHitResult ready to hand to interactBlock, or null if no face works
     */
    private BlockHitResult findValidInteractionHit(MinecraftClient client, BlockPos blockPos, Direction preferredDir) {
        Vec3d eyePos = client.player.getEyePos();

        Direction[] order = orderedDirections(preferredDir);
        for (Direction dir : order) {
            BlockPos outsidePos = blockPos.offset(dir);
            if (!client.world.getBlockState(outsidePos).isReplaceable()) continue; // face is buried

            Vec3d faceCenter = Vec3d.ofCenter(blockPos).add(
                    dir.getOffsetX() * 0.5,
                    dir.getOffsetY() * 0.5,
                    dir.getOffsetZ() * 0.5
            );

            if (eyePos.squaredDistanceTo(faceCenter) > PLACEMENT_REACH * PLACEMENT_REACH) continue;

            return new BlockHitResult(faceCenter, dir, blockPos, false);
        }
        return null;
    }

    /** preferredDir first, then the remaining five directions in a fixed order. */
    private Direction[] orderedDirections(Direction preferredDir) {
        Direction[] result = new Direction[6];
        result[0] = preferredDir;
        int i = 1;
        for (Direction d : Direction.values()) {
            if (d != preferredDir) result[i++] = d;
        }
        return result;
    }

    /**
     * Mirrors vanilla's own placement-position logic (ItemPlacementContext):
     * if the block the player clicked is replaceable (snow layers, fire,
     * tall grass, water, etc.), the new block lands directly AT that
     * position, not offset from it. Only non-replaceable blocks (stone,
     * obsidian, most solid terrain) push the new block to the offset
     * position on the clicked face. Using the wrong one here doesn't change
     * where the real block ends up (the server/client already gets that
     * right from the raw hit result) — it only breaks OUR bookkeeping of
     * where to check for and build off of it, which is what was causing the
     * false "placement failed" on snow/fire and the anchor logic building
     * against thin air afterward.
     */
    private BlockPos resolvePlacementPos(MinecraftClient client, BlockHitResult hit) {
        BlockPos clickedPos = hit.getBlockPos();
        BlockState clickedState = client.world.getBlockState(clickedPos);
        if (clickedState.isReplaceable()) {
            return clickedPos;
        }
        return clickedPos.offset(hit.getSide());
    }

    /**
     * Picks the dominant horizontal axis between the player and a block, and
     * returns the Direction pointing from the player through that block and
     * onward. Falls back to the player's facing if the block is directly
     * above/below/on the player.
     */
    private Direction horizontalDirectionAwayFromPlayer(MinecraftClient client, BlockPos pos) {
        double dx = pos.getX() + 0.5 - client.player.getX();
        double dz = pos.getZ() + 0.5 - client.player.getZ();

        if (Math.abs(dx) < 1.0e-4 && Math.abs(dz) < 1.0e-4) {
            return client.player.getHorizontalFacing();
        }
        return Math.abs(dx) >= Math.abs(dz)
                ? (dx > 0 ? Direction.EAST : Direction.WEST)
                : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
    }

    private int findItemSlot(Item item) {
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
package com.example.exampleaddon;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.blitz.command.CommandManager;
import com.blitz.module.Category;
import com.blitz.module.Module;
import com.blitz.module.setting.BoolSetting;
import com.blitz.module.setting.ColorSetting;
import com.blitz.module.setting.DoubleSetting;
import com.blitz.module.setting.StringSetting;

import net.minecraft.entity.LivingEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

/**
 * Announces totem of undying pops -- yours, and optionally anyone else's within range -- and keeps a
 * running count per entity that resets whenever that entity dies and comes back.
 *
 * <p>Detection lives in {@link com.example.exampleaddon.mixin.ClientPlayNetworkHandlerMixin}, which
 * injects into {@code onEntityStatus} and forwards status {@code 35} (the totem animation) here via
 * {@link #onTotemPop(LivingEntity)}. That injection fires on every totem pop regardless of whether
 * this module is enabled -- packet handling doesn't know about the ClickGUI toggle -- so filtering by
 * {@link #isEnabled()} happens inside {@link #handle(LivingEntity)}, not in the mixin.</p>
 *
 * <p><b>Why pops were arriving twice:</b> when you pop your own totem, the server sends this exact
 * packet to your own client twice for the same event -- once through the normal entity-tracking
 * broadcast, and once more as a direct packet, because the tracking system doesn't count you as
 * "tracking yourself." Anything that hooks the raw packet sees both. {@link #lastPopTime} filters
 * this out by remembering the last world-time tick a given entity's pop was counted and ignoring a
 * second one on that same tick -- see {@link #handle(LivingEntity)}.</p>
 *
 * <p>The mixin needs a way to reach "the currently registered instance of this module" without Blitz
 * handing it one, so construction stashes itself in a static field. Safe here because {@code AddonApi}
 * only ever constructs one of these (see {@code ExampleAddon.onInit}).</p>
 */
public class TotemPopModule extends Module {

    private static TotemPopModule instance;

    private final BoolSetting self = addSetting(new BoolSetting("Self", true));
    private final BoolSetting others = addSetting(new BoolSetting("Others", true));
    private final BoolSetting actionBar = addSetting(new BoolSetting("Action Bar", false));
    private final DoubleSetting range = addSetting(new DoubleSetting("Range", 30.0, 5.0, 100.0, 5.0));
    private final StringSetting prefix = addSetting(new StringSetting("Prefix", "[Totem]"));
    private final ColorSetting prefixColor = addSetting(new ColorSetting("Prefix Color", 0xFFFFD700));
    private final ColorSetting messageColor = addSetting(new ColorSetting("Message Color", 0xFFFFFFFF));

    /** How many totems each entity has popped since its current life started. Keyed by UUID so a
     *  player keeps the same key across a death -- {@link #lastLife} is what actually detects the
     *  death and resets the count, not this map by itself. */
    private final Map<UUID, Integer> counts = new HashMap<>();

    /** The specific {@link LivingEntity} object last seen for a given UUID. Minecraft constructs a
     *  brand new entity object client-side whenever something respawns, even though the UUID (for a
     *  player) stays the same -- so "the object in this map changed" is exactly "this is a new life,"
     *  with no extra death event or tick-polling required to detect it. */
    private final Map<UUID, LivingEntity> lastLife = new HashMap<>();

    /** World time of the last pop counted for a UUID, purely to reject the same-tick duplicate packet
     *  described above. Not a queue of any kind -- one long per entity, overwritten every pop. */
    private final Map<UUID, Long> lastPopTime = new HashMap<>();

    public TotemPopModule(Category category) {
        super("TotemPopNotify", "announces totem of undying pops and counts them per life", category);
        instance = this;
    }

    /** Called from the mixin for every status-35 packet, whether or not the module is on. */
    public static void onTotemPop(LivingEntity entity) {
        if (instance != null) {
            instance.handle(entity);
        }
    }

    private void handle(LivingEntity entity) {
        if (!isEnabled() || mc.player == null || mc.world == null) {
            return;
        }

        boolean isSelf = entity == mc.player;
        if (isSelf && !self.isEnabled()) {
            return;
        }
        if (!isSelf && !others.isEnabled()) {
            return;
        }

        double distance = mc.player.distanceTo(entity);
        if (!isSelf && distance > range.get()) {
            return;
        }

        UUID id = entity.getUuid();

        // Reject the duplicate self-pop packet -- see the class doc. Two packets for the same real
        // pop land in the same world tick; a genuine second totem (e.g. one in each hand) would not.
        long now = mc.world.getTime();
        Long last = lastPopTime.put(id, now);
        if (last != null && last == now) {
            return;
        }

        // A different object instance for a UUID we've already seen means the old one died and this
        // is a respawn -- start the count over for this new life.
        LivingEntity previousInstance = lastLife.put(id, entity);
        if (previousInstance != entity) {
            counts.put(id, 0);
        }

        int count = counts.merge(id, 1, Integer::sum);

        String who = isSelf ? "You" : entity.getName().getString();
        String have = isSelf ? "have" : "has";
        String totemWord = count == 1 ? "totem" : "totems";
        String suffix = isSelf ? "" : " (" + Math.round(distance) + "m)";
        announce(who + " " + have + " popped " + count + " " + totemWord + "." + suffix);
    }

    private void announce(String body) {
        MutableText tag = Text.literal(prefix.get() + " ")
                .styled(style -> style
                        .withColor(TextColor.fromRgb(prefixColor.getArgb() & 0xFFFFFF))
                        .withBold(false));
        MutableText message = Text.literal(body)
                .styled(style -> style.withColor(TextColor.fromRgb(messageColor.getArgb() & 0xFFFFFF)));
        Text full = tag.append(message);

        CommandManager.sendFeedback(full);
        if (actionBar.isEnabled() && mc.player != null) {
            mc.player.sendMessage(full, true);
        }
    }
}

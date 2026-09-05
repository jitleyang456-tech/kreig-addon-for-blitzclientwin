package com.example.exampleaddon;

import com.blitz.addon.AddonApi;
import com.blitz.addon.BlitzAddon;
import com.blitz.module.Category;

import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.ActionResult;

public class KreigAddon implements BlitzAddon {

    @Override
    public void onInit(AddonApi api) {
        Category own = api.category("Kreig Addon");
        api.register(new FeetPlaceModule(own));
        api.register(new SafeAnchorMacro(own));
        api.register(new Pearllowestspotmodule(own));
        api.register(new TotemPopModule(own));
        api.register(new AnchorMacro(own));

        // 1. Register the module
        AutoHitCrystal module = new AutoHitCrystal(own);
        api.register(module);

        // 2. Hook the attack event to call the module
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            // Only react if it's the local player
            if (player == MinecraftClient.getInstance().player) {
                if (AutoHitCrystal.INSTANCE != null) {
                    AutoHitCrystal.INSTANCE.onHitEntity(entity);
                }
            }
            return ActionResult.PASS; // don't cancel the attack
        });

        api.registerCommand(new ExampleCommand());

        System.out.println("[" + api.name() + "] loaded. Data folder: " + api.dataDir());
    }

    @Override
    public void onShutdown() {
        // clean up if needed
    }
}
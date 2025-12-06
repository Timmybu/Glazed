package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class AutoBoneManager extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
            .name("delay")
            .description("Delay in ticks between actions.")
            .defaultValue(3)
            .min(1)
            .sliderMax(20)
            .build()
    );

    private final Setting<String> orderCommand = sgGeneral.add(new StringSetting.Builder()
            .name("order-command")
            .description("Command to open the orders menu.")
            .defaultValue("/orders bones")
            .build()
    );

    private final Setting<String> sellCommand = sgGeneral.add(new StringSetting.Builder()
            .name("sell-command")
            .description("Command to open the sell menu.")
            .defaultValue("/sell")
            .build()
    );

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
            .name("debug")
            .description("Show debug messages.")
            .defaultValue(false)
            .build()
    );

    private enum Stage {
        IDLE,
        LOOTING_CHEST,
        OPENING_ORDERS,
        FULFILLING_ORDERS,
        CHECKING_INVENTORY,
        OPENING_SELL,
        SELLING_SHULKERS
    }

    private Stage stage = Stage.IDLE;
    private int timer = 0;
    private int attempts = 0;

    public AutoBoneManager() {
        super(GlazedAddon.CATEGORY, "AutoBoneManager", "Loots bone shulkers, fulfills orders, and sells empty shulkers.");
    }

    @Override
    public void onActivate() {
        stage = Stage.IDLE;
        timer = 0;
        attempts = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (timer > 0) {
            timer--;
            return;
        }

        switch (stage) {
            case IDLE:
                // Wait for user to open a chest manually
                if (mc.currentScreen instanceof GenericContainerScreen) {
                    if (debug.get()) info("Chest detected, starting loot sequence.");
                    stage = Stage.LOOTING_CHEST;
                    timer = delay.get();
                }
                break;

            case LOOTING_CHEST:
                handleLooting();
                break;

            case OPENING_ORDERS:
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                }
                ChatUtils.sendPlayerMsg(orderCommand.get());
                stage = Stage.FULFILLING_ORDERS;
                timer = 20; // Wait for GUI to open
                attempts = 0;
                break;

            case FULFILLING_ORDERS:
                handleOrders();
                break;

            case CHECKING_INVENTORY:
                checkInventoryState();
                break;

            case OPENING_SELL:
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                }
                ChatUtils.sendPlayerMsg(sellCommand.get());
                stage = Stage.SELLING_SHULKERS;
                timer = 20; // Wait for GUI to open
                break;

            case SELLING_SHULKERS:
                handleSelling();
                break;
        }
    }

    private void handleLooting() {
        if (!(mc.currentScreen instanceof GenericContainerScreen)) {
            // Screen closed unexpectedly or finished
            stage = Stage.OPENING_ORDERS;
            timer = delay.get();
            return;
        }

        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) {
            return;
        }

        // Calculate chest size
        int invSize = handler.getInventory().size();

        boolean foundItem = false;

        // Scan chest slots
        for (int i = 0; i < invSize; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (isBoneShulker(stack)) {
                // If player inventory is full, we should stop
                if (isPlayerInventoryFull()) {
                    if (debug.get()) info("Inventory full, moving to orders.");
                    mc.player.closeHandledScreen();
                    stage = Stage.OPENING_ORDERS;
                    return;
                }

                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                foundItem = true;
                timer = delay.get();
                return; // One action per tick delay
            }
        }

        if (!foundItem) {
            if (debug.get()) info("No more bone shulkers found in chest.");
            mc.player.closeHandledScreen();
            stage = Stage.OPENING_ORDERS;
            timer = delay.get();
        }
    }

    private void handleOrders() {
        if (!(mc.currentScreen instanceof GenericContainerScreen)) {
            // Wait a bit longer or retry if GUI hasn't opened yet
            if (attempts < 5) {
                attempts++;
                timer = 10;
                return;
            }
            if (debug.get()) warning("Order menu did not open.");
            toggle();
            return;
        }

        ScreenHandler handler = mc.player.currentScreenHandler;
        boolean clickedOrder = false;

        // Logic to find a fulfillable order.
        for (int i = 0; i < handler.slots.size() - 36; i++) { // Don't click player inv
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty() && isOrderButton(stack)) {
                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, mc.player);
                clickedOrder = true;
                break;
            }
        }

        if (clickedOrder) {
            // We clicked an order, wait a bit for server to process transaction
            stage = Stage.CHECKING_INVENTORY;
            timer = 20;
        } else {
            if (debug.get()) info("No valid orders found.");
            stage = Stage.CHECKING_INVENTORY;
            timer = 10;
        }
    }

    private void checkInventoryState() {
        // Double check that shulkers are empty of bones
        boolean hasBonesLeft = false;
        int emptyShulkers = 0;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock) {
                if (containsBones(stack)) {
                    hasBonesLeft = true;
                } else {
                    emptyShulkers++;
                }
            }
        }

        if (hasBonesLeft) {
            if (debug.get()) warning("Still have bones in shulkers! Orders might be full or failed.");
            mc.player.closeHandledScreen();

            if (emptyShulkers > 0) {
                stage = Stage.OPENING_SELL;
            } else {
                info("No empty shulkers to sell. Stopping.");
                toggle();
            }
        } else {
            if (debug.get()) info("All shulkers empty. Proceeding to sell.");
            mc.player.closeHandledScreen();
            stage = Stage.OPENING_SELL;
        }
        timer = delay.get();
    }

    private void handleSelling() {
        if (!(mc.currentScreen instanceof GenericContainerScreen)) {
            if (attempts < 5) {
                attempts++;
                timer = 10;
                return;
            }
            if (debug.get()) warning("Sell menu did not open.");
            toggle();
            return;
        }

        ScreenHandler handler = mc.player.currentScreenHandler;
        boolean soldItem = false;

        // Iterate player inventory slots in the current open container
        int containerSlots = handler.slots.size() - 36;

        for (int i = containerSlots; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            ItemStack stack = slot.getStack();

            // Sell ONLY empty shulkers
            if (isEmptyShulker(stack)) {
                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                soldItem = true;
                timer = delay.get();
                return; // Sell one at a time to be safe
            }
        }

        if (!soldItem) {
            if (debug.get()) info("Finished selling empty shulkers.");
            mc.player.closeHandledScreen();
            toggle();
        }
    }

    // Helpers

    private boolean isBoneShulker(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof ShulkerBoxBlock)) {
            return false;
        }
        return containsBones(stack);
    }

    private boolean isEmptyShulker(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof ShulkerBoxBlock)) {
            return false;
        }
        return !containsItems(stack);
    }

    private boolean containsBones(ItemStack stack) {
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container == null) return false;

        AtomicBoolean hasBone = new AtomicBoolean(false);
        container.stream().forEach(itemStack -> {
            if (itemStack.getItem() == Items.BONE || itemStack.getItem() == Items.BONE_BLOCK) {
                hasBone.set(true);
            }
        });
        return hasBone.get();
    }

    private boolean containsItems(ItemStack stack) {
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container == null) return false;

        // If stream has any elements, it's not empty
        return container.stream().findAny().isPresent();
    }

    private boolean isOrderButton(ItemStack stack) {
        if (stack.getItem() == Items.BONE || stack.getItem() == Items.BONE_BLOCK) return true;

        List<net.minecraft.text.Text> tooltip = stack.getTooltip(net.minecraft.item.Item.TooltipContext.create(mc.world), mc.player, net.minecraft.item.tooltip.TooltipType.BASIC);
        for (net.minecraft.text.Text text : tooltip) {
            String str = text.getString().toLowerCase();
            if (str.contains("bone")) return true;
        }
        return false;
    }

    private boolean isPlayerInventoryFull() {
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
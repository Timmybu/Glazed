package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.block.BlockState;
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
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class AutoBoneManager extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
            .name("delay")
            .description("Delay in ticks between actions.")
            .defaultValue(5)
            .min(1)
            .sliderMax(20)
            .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("range")
            .description("The radius to search for chests.")
            .defaultValue(4.0)
            .min(1.0)
            .sliderMax(6.0)
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
        SCANNING,
        ROTATING,
        INTERACTING,
        WAIT_FOR_OPEN,
        LOOTING_CHEST,

        // Order Fulfillment Cycle
        START_ORDERS,
        WAIT_FOR_ORDERS_GUI,
        SELECT_HIGHEST_ORDER,
        WAIT_FOR_FILL_GUI,
        FILLING_ORDER,
        CLOSE_FILL_GUI,
        WAIT_FOR_CONFIRM_GUI,
        CONFIRM_ORDER,
        WAIT_FOR_RETURN_GUI,
        CLOSE_RETURN_GUI,
        WAIT_FOR_ORDERS_RETURN,
        CHECK_REMAINING_BONES,
        REFRESH_ORDERS,

        // Selling Cycle
        START_SELLING,
        WAIT_FOR_SELL_GUI,
        SELLING_SHULKERS,

        IDLE
    }

    private Stage stage = Stage.SCANNING;
    private int timer = 0;
    private int attempts = 0;
    private final Set<BlockPos> processedChests = new HashSet<>();
    private BlockPos currentTarget = null;

    public AutoBoneManager() {
        super(GlazedAddon.CATEGORY, "AutoBoneManager", "Automatically checks chests, loots bone shulkers, fills orders, and sells empty/junk shulkers.");
    }

    @Override
    public void onActivate() {
        stage = Stage.SCANNING;
        timer = 0;
        processedChests.clear();
        currentTarget = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        // Ensure we keep looking at the target while preparing to interact or retrying
        if ((stage == Stage.ROTATING || stage == Stage.INTERACTING || stage == Stage.WAIT_FOR_OPEN)
                && currentTarget != null
                && mc.currentScreen == null) {
            lookAtBlock(currentTarget);
        }

        if (timer > 0) {
            timer--;
            return;
        }

        switch (stage) {
            case SCANNING:
                handleScanning();
                break;

            case ROTATING:
                stage = Stage.INTERACTING;
                break;

            case INTERACTING:
                if (currentTarget == null) {
                    stage = Stage.SCANNING;
                    return;
                }

                if (debug.get()) info("Interacting with chest at " + currentTarget.toShortString() + " (Attempt " + (attempts + 1) + ")");

                if (mc.currentScreen == null) lookAtBlock(currentTarget);

                BlockHitResult hitResult = new BlockHitResult(
                        new Vec3d(currentTarget.getX() + 0.5, currentTarget.getY() + 1.0, currentTarget.getZ() + 0.5),
                        Direction.UP,
                        currentTarget,
                        false
                );
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
                mc.player.swingHand(Hand.MAIN_HAND);

                stage = Stage.WAIT_FOR_OPEN;
                timer = 20;
                break;

            case WAIT_FOR_OPEN:
                if (mc.currentScreen instanceof GenericContainerScreen) {
                    if (debug.get()) info("Container opened successfully.");
                    // Do NOT mark as processed here. Only mark when empty.
                    stage = Stage.LOOTING_CHEST;
                    timer = delay.get();
                } else if (timer <= 0) {
                    attempts++;
                    if (attempts > 4) {
                        if (debug.get()) warning("Failed to open chest at " + currentTarget.toShortString() + " after 4 attempts.");
                        if (currentTarget != null) markChestAsProcessed(currentTarget); // Mark bad chests to skip
                        stage = Stage.SCANNING;
                        attempts = 0;
                    } else {
                        stage = Stage.INTERACTING;
                        timer = 0;
                    }
                }
                break;

            case LOOTING_CHEST:
                handleLooting();
                break;

            // --- Order Logic ---
            case START_ORDERS:
                if (mc.currentScreen != null) mc.player.closeHandledScreen();
                ChatUtils.sendPlayerMsg(orderCommand.get());
                stage = Stage.WAIT_FOR_ORDERS_GUI;
                timer = 10;
                break;

            case WAIT_FOR_ORDERS_GUI:
            case WAIT_FOR_ORDERS_RETURN:
                if (mc.currentScreen instanceof GenericContainerScreen) {
                    stage = (stage == Stage.WAIT_FOR_ORDERS_RETURN) ? Stage.CHECK_REMAINING_BONES : Stage.SELECT_HIGHEST_ORDER;
                    timer = delay.get();
                }
                break;

            case SELECT_HIGHEST_ORDER:
                clickSlot(0);
                stage = Stage.WAIT_FOR_FILL_GUI;
                timer = 10;
                break;

            case WAIT_FOR_FILL_GUI:
                if (mc.currentScreen instanceof GenericContainerScreen) {
                    stage = Stage.FILLING_ORDER;
                    timer = delay.get();
                }
                break;

            case FILLING_ORDER:
                handleFillingOrder();
                break;

            case CLOSE_FILL_GUI:
                mc.player.closeHandledScreen();
                stage = Stage.WAIT_FOR_CONFIRM_GUI;
                timer = 10;
                break;

            case WAIT_FOR_CONFIRM_GUI:
                if (mc.currentScreen instanceof GenericContainerScreen) {
                    stage = Stage.CONFIRM_ORDER;
                    timer = delay.get();
                }
                break;

            case CONFIRM_ORDER:
                handleConfirmOrder();
                break;

            case WAIT_FOR_RETURN_GUI:
                if (mc.currentScreen instanceof GenericContainerScreen) {
                    stage = Stage.CLOSE_RETURN_GUI;
                    timer = delay.get();
                }
                break;

            case CLOSE_RETURN_GUI:
                mc.player.closeHandledScreen();
                stage = Stage.WAIT_FOR_ORDERS_RETURN;
                timer = 10;
                break;

            case CHECK_REMAINING_BONES:
                if (hasSellableItems()) {
                    if (debug.get()) info("Items remaining. Refreshing orders.");
                    stage = Stage.REFRESH_ORDERS;
                } else {
                    if (debug.get()) info("No items left. Moving to sell.");
                    stage = Stage.START_SELLING;
                }
                timer = delay.get();
                break;

            case REFRESH_ORDERS:
                handleRefresh();
                break;

            // --- Sell Logic ---
            case START_SELLING:
                if (mc.currentScreen != null) mc.player.closeHandledScreen();
                ChatUtils.sendPlayerMsg(sellCommand.get());
                stage = Stage.WAIT_FOR_SELL_GUI;
                timer = 10;
                break;

            case WAIT_FOR_SELL_GUI:
                if (mc.currentScreen instanceof GenericContainerScreen) {
                    stage = Stage.SELLING_SHULKERS;
                    timer = delay.get();
                }
                break;

            case SELLING_SHULKERS:
                handleSelling();
                break;

            case IDLE:
                stage = Stage.SCANNING;
                break;
        }
    }

    private void handleScanning() {
        if (mc.currentScreen != null) {
            mc.player.closeHandledScreen();
            timer = 10;
            return;
        }

        BlockPos playerPos = mc.player.getBlockPos();
        int r = (int) Math.ceil(range.get());

        List<BlockPos> nearbyChests = new ArrayList<>();

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    if (Math.sqrt(pos.getSquaredDistance(playerPos)) > range.get()) continue;

                    BlockState state = mc.world.getBlockState(pos);
                    if (isContainer(state) && !processedChests.contains(pos)) {
                        nearbyChests.add(pos);
                    }
                }
            }
        }

        if (nearbyChests.isEmpty()) {
            if (debug.get() && timer == 0) info("No unchecked chests in range.");
            timer = 20;
            return;
        }

        nearbyChests.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(playerPos)));

        currentTarget = nearbyChests.get(0);

        if (debug.get()) info("Found chest at " + currentTarget.toShortString());

        stage = Stage.ROTATING;
        attempts = 0;
        timer = 5;
    }

    private void lookAtBlock(BlockPos pos) {
        Vec3d targetPos = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5);
        Vec3d playerPos = mc.player.getEyePos();
        Vec3d direction = targetPos.subtract(playerPos).normalize();

        double yaw = Math.toDegrees(Math.atan2(-direction.x, direction.z));
        double pitch = Math.toDegrees(-Math.asin(direction.y));

        double jitter = (Math.random() - 0.5) * 3.0;

        mc.player.setYaw((float) (yaw + jitter));
        mc.player.setPitch((float) (pitch + jitter));
    }

    private boolean isContainer(BlockState state) {
        return state.isOf(Blocks.CHEST) ||
                state.isOf(Blocks.TRAPPED_CHEST) ||
                state.isOf(Blocks.BARREL) ||
                state.getBlock() instanceof ShulkerBoxBlock;
    }

    private void markChestAsProcessed(BlockPos pos) {
        processedChests.add(pos);
        if (mc.world == null) return;

        BlockState state = mc.world.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock) {
            ChestType type = state.get(ChestBlock.CHEST_TYPE);
            if (type != ChestType.SINGLE) {
                Direction facing = state.get(ChestBlock.FACING);
                Direction otherDir = type == ChestType.LEFT ? facing.rotateYClockwise() : facing.rotateYCounterclockwise();
                BlockPos otherPos = pos.offset(otherDir);
                processedChests.add(otherPos);
            }
        }
    }

    private void handleLooting() {
        if (!(mc.currentScreen instanceof GenericContainerScreen)) {
            returnToScanningOrOrders();
            return;
        }

        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) {
            return;
        }

        int invSize = handler.getInventory().size();

        int boneShulkerCount = 0;
        for (int i = 0; i < invSize; i++) {
            if (isBoneShulker(handler.getSlot(i).getStack())) {
                boneShulkerCount++;
            }
        }

        if (boneShulkerCount == 0) {
            if (debug.get()) info("No bone shulkers found in chest. Marking processed.");

            // Mark as processed ONLY when it is empty of target items
            if (currentTarget != null) markChestAsProcessed(currentTarget);

            mc.player.closeHandledScreen();
            returnToScanningOrOrders();
            return;
        }

        // Bulk move
        if (boneShulkerCount > 5) {
            for (int i = 0; i < invSize; i++) {
                ItemStack stack = handler.getSlot(i).getStack();
                if (isBoneShulker(stack)) {
                    if (isPlayerInventoryFull()) {
                        if (debug.get()) info("Inventory full during bulk move, starting orders.");
                        mc.player.closeHandledScreen();
                        stage = Stage.START_ORDERS;
                        return;
                    }
                    mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                }
            }
            timer = delay.get();
        } else {
            // Single move
            for (int i = 0; i < invSize; i++) {
                ItemStack stack = handler.getSlot(i).getStack();
                if (isBoneShulker(stack)) {
                    if (isPlayerInventoryFull()) {
                        if (debug.get()) info("Inventory full, starting orders.");
                        mc.player.closeHandledScreen();
                        stage = Stage.START_ORDERS;
                        return;
                    }
                    clickSlot(i);
                    timer = delay.get();
                    return;
                }
            }
        }
    }

    private void returnToScanningOrOrders() {
        if (hasSellableItems()) {
            stage = Stage.START_ORDERS;
        } else {
            stage = Stage.SCANNING;
        }
        timer = delay.get();
    }

    private void handleFillingOrder() {
        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) return;

        int playerStart = handler.slots.size() - 36;
        int eligibleItems = 0;

        for (int i = playerStart; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (isBoneShulker(stack) || isSellableItem(stack)) {
                eligibleItems++;
            }
        }

        if (eligibleItems == 0) {
            stage = Stage.CLOSE_FILL_GUI;
            timer = delay.get();
            return;
        }

        if (eligibleItems > 5) {
            for (int i = playerStart; i < handler.slots.size(); i++) {
                ItemStack stack = handler.getSlot(i).getStack();
                if (isBoneShulker(stack) || isSellableItem(stack)) {
                    clickSlot(i);
                }
            }
            timer = delay.get();
        } else {
            for (int i = playerStart; i < handler.slots.size(); i++) {
                ItemStack stack = handler.getSlot(i).getStack();
                if (isBoneShulker(stack) || isSellableItem(stack)) {
                    clickSlot(i);
                    timer = delay.get();
                    return;
                }
            }
        }
    }

    private void handleConfirmOrder() {
        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) return;

        for (int i = 0; i < handler.getInventory().size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.getItem() == Items.LIME_STAINED_GLASS_PANE) {
                clickSlot(i);
                stage = Stage.WAIT_FOR_RETURN_GUI;
                timer = 10;
                return;
            }
        }

        if (debug.get()) warning("Could not find confirmation button!");
        mc.player.closeHandledScreen();
        stage = Stage.START_ORDERS;
    }

    private void handleRefresh() {
        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) return;

        for (int i = 0; i < handler.getInventory().size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.getItem() == Items.FILLED_MAP || stack.getItem() == Items.MAP) {
                clickSlot(i);
                stage = Stage.SELECT_HIGHEST_ORDER;
                timer = 20;
                return;
            }
        }

        stage = Stage.SELECT_HIGHEST_ORDER;
    }

    private void handleSelling() {
        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) {
            stage = Stage.SCANNING;
            return;
        }

        int playerStart = handler.slots.size() - 36;
        int sellableShulkerCount = 0;

        for (int i = playerStart; i < handler.slots.size(); i++) {
            if (isSellableShulker(handler.getSlot(i).getStack())) {
                sellableShulkerCount++;
            }
        }

        if (sellableShulkerCount == 0) {
            if (debug.get()) info("Selling complete. Returning to scan.");
            mc.player.closeHandledScreen();
            stage = Stage.SCANNING;
            return;
        }

        if (sellableShulkerCount > 5) {
            for (int i = playerStart; i < handler.slots.size(); i++) {
                if (isSellableShulker(handler.getSlot(i).getStack())) {
                    clickSlot(i);
                }
            }
            timer = delay.get();
        } else {
            for (int i = playerStart; i < handler.slots.size(); i++) {
                if (isSellableShulker(handler.getSlot(i).getStack())) {
                    clickSlot(i);
                    timer = delay.get();
                    return;
                }
            }
        }
    }

    // --- Helpers ---

    private void clickSlot(int slotId) {
        if (mc.interactionManager != null && mc.player != null && mc.player.currentScreenHandler != null) {
            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slotId, 0, SlotActionType.QUICK_MOVE, mc.player);
        }
    }

    private boolean hasSellableItems() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isBoneShulker(stack) || isSellableItem(stack)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSellableItem(ItemStack stack) {
        return stack.getItem() == Items.BONE || stack.getItem() == Items.BONE_BLOCK;
    }

    private boolean isBoneShulker(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof ShulkerBoxBlock)) {
            return false;
        }
        return containsBones(stack);
    }

    private boolean isSellableShulker(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof ShulkerBoxBlock)) {
            return false;
        }
        // Sell if it DOES NOT contain bones.
        // It can be empty, or contain junk (arrows), but if it has bones we must keep it for ordering.
        return !containsBones(stack);
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

    private boolean isPlayerInventoryFull() {
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
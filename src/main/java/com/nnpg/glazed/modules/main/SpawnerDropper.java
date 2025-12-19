package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SpawnerDropper extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    // --- General Settings ---
    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
            .name("delay")
            .description("Delay between clicks/actions in ticks.")
            .defaultValue(5)
            .min(1)
            .max(200)
            .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("range")
            .description("The radius to search for spawners.")
            .defaultValue(4.0)
            .min(1.0)
            .sliderMax(6.0)
            .build()
    );

    private final Setting<Double> rotationSpeed = sgGeneral.add(new DoubleSetting.Builder()
            .name("rotation-speed")
            .description("Rotation speed in degrees per tick.")
            .defaultValue(10.0)
            .min(1.0)
            .max(180.0)
            .build()
    );

    private final Setting<Boolean> boneOnly = sgGeneral.add(new BoolSetting.Builder()
            .name("bone-only")
            .description("Enable the arrow detection logic (Sell/Re-open).")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
            .name("debug")
            .description("Show debug messages.")
            .defaultValue(false)
            .build()
    );

    // --- Render Settings ---
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
            .name("shape-mode")
            .description("How the shapes are rendered.")
            .defaultValue(ShapeMode.Both)
            .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
            .name("side-color")
            .description("The side color of the spawner highlight.")
            .defaultValue(new SettingColor(255, 0, 0, 50))
            .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
            .name("line-color")
            .description("The line color of the spawner highlight.")
            .defaultValue(new SettingColor(255, 0, 0, 255))
            .build()
    );

    // --- State Machine ---
    private enum Stage {
        SCANNING,       // Look for spawners
        ROTATING,       // Aim at the spawner
        INTERACTING,    // Right click
        WAIT_FOR_OPEN,  // Wait for GUI
        CHECK_CONTENTS, // Determine if we Sell or Drop
        DROPPING,       // Perform the drop logic (with re-open check)
        SELLING,        // Click Gold Ingot
        CONFIRM_SELL    // Click Green Pane
    }

    private Stage stage = Stage.SCANNING;

    // --- Tracking Variables ---
    private final Set<BlockPos> processedSpawners = new HashSet<>();
    private BlockPos currentTarget = null;
    private Vec3d currentTargetOffset = Vec3d.ZERO;
    private Direction currentFace = Direction.UP;

    // Timer for general state delays
    private int timer = 0;
    private int attempts = 0;
    private int nullScreenCounter = 0; // Tracks how long screen has been closed during transition
    private int sellMenuSyncId = -1; // Used to detect menu changes

    // Drop Logic Specifics
    private int currentStep = 0;

    public SpawnerDropper() {
        super(GlazedAddon.CATEGORY, "SpawnerDropper", "Automatically scans, opens, drops items, and sells junk from spawners.");
    }

    @Override
    public void onActivate() {
        stage = Stage.SCANNING;
        timer = 0;
        processedSpawners.clear();
        currentTarget = null;
        currentTargetOffset = Vec3d.ZERO;
        currentFace = Direction.UP;
        currentStep = 0;
        attempts = 0;
        nullScreenCounter = 0;
        sellMenuSyncId = -1;
    }

    @Override
    public void onDeactivate() {
        currentStep = 0;
        if (mc.player != null) {
            mc.player.closeHandledScreen();
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        // Render current target
        if (currentTarget != null) {
            event.renderer.box(currentTarget, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (timer > 0) {
            timer--;
            // Maintain rotation while waiting
            if ((stage == Stage.INTERACTING || stage == Stage.WAIT_FOR_OPEN) && currentTarget != null && mc.currentScreen == null) {
                rotateTowards(currentTarget);
            }
            return;
        }

        switch (stage) {
            case SCANNING:
                handleScanning();
                break;

            case ROTATING:
                if (currentTarget == null) {
                    stage = Stage.SCANNING;
                    return;
                }
                if (rotateTowards(currentTarget)) {
                    stage = Stage.INTERACTING;
                }
                break;

            case INTERACTING:
                if (currentTarget == null) {
                    stage = Stage.SCANNING;
                    return;
                }

                if (debug.get()) info("Interacting with spawner at " + currentTarget.toShortString());

                if (mc.currentScreen == null) rotateTowards(currentTarget);

                BlockHitResult hitResult = new BlockHitResult(
                        new Vec3d(currentTarget.getX() + currentTargetOffset.x, currentTarget.getY() + currentTargetOffset.y, currentTarget.getZ() + currentTargetOffset.z),
                        currentFace,
                        currentTarget,
                        false
                );
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
                mc.player.swingHand(Hand.MAIN_HAND);

                stage = Stage.WAIT_FOR_OPEN;
                timer = 15;
                break;

            case WAIT_FOR_OPEN:
                if (mc.currentScreen instanceof HandledScreen) {
                    stage = Stage.CHECK_CONTENTS;
                    timer = delay.get();
                } else if (timer <= 0) {
                    attempts++;
                    if (attempts > 3) {
                        if (debug.get()) warning("Failed to open spawner. Skipping.");
                        processedSpawners.add(currentTarget);
                        stage = Stage.SCANNING;
                        attempts = 0;
                    } else {
                        generateRandomOffset(currentTarget);
                        stage = Stage.INTERACTING;
                        timer = 0;
                    }
                }
                break;

            case CHECK_CONTENTS:
                handleCheckContents();
                break;

            case DROPPING:
                handleDropping();
                break;

            case SELLING:
                handleSelling();
                break;

            case CONFIRM_SELL:
                handleConfirmSell();
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
        List<BlockPos> nearbySpawners = new ArrayList<>();

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    if (Math.sqrt(pos.getSquaredDistance(playerPos)) > range.get()) continue;

                    BlockState state = mc.world.getBlockState(pos);
                    if (state.getBlock() == Blocks.SPAWNER) {
                        if (!processedSpawners.contains(pos)) {
                            nearbySpawners.add(pos);
                        }
                    }
                }
            }
        }

        if (nearbySpawners.isEmpty()) {
            if (!processedSpawners.isEmpty()) {
                if (debug.get()) info("All nearby spawners checked. Resetting loop.");
                processedSpawners.clear();
            } else {
                timer = 20;
            }
            return;
        }

        nearbySpawners.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(playerPos)));

        currentTarget = nearbySpawners.get(0);
        generateRandomOffset(currentTarget);

        if (debug.get()) info("Found spawner at " + currentTarget.toShortString());

        stage = Stage.ROTATING;
        attempts = 0;
        timer = 0;
    }

    private void handleCheckContents() {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            stage = Stage.SCANNING;
            return;
        }

        if (hasArrowsInInventory(screen)) {
            if (debug.get()) info("Arrows detected on open! Selling.");
            stage = Stage.SELLING;
            attempts = 0;
        } else {
            if (debug.get()) info("No arrows on open. Starting drop sequence.");
            stage = Stage.DROPPING;
            currentStep = 0;
        }
        timer = delay.get();
    }

    private void handleDropping() {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            stage = Stage.SCANNING;
            return;
        }

        if (hasArrowsInInventory(screen)) {
            if (debug.get()) info("Arrows detected during drop. Re-entering.");
            mc.player.closeHandledScreen();
            stage = Stage.INTERACTING;
            timer = 10;
            return;
        }

        switch (currentStep) {
            case 0:
                mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, 50, 0, SlotActionType.PICKUP, mc.player);
                currentStep = 1;
                timer = delay.get();
                break;
            case 1:
                mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, 53, 0, SlotActionType.PICKUP, mc.player);
                currentStep = 2;
                timer = delay.get();
                break;
            case 2:
                currentStep = 3;
                break;
            case 3:
                mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, 50, 0, SlotActionType.PICKUP, mc.player);
                currentStep = 4;
                timer = delay.get();
                break;
            case 4:
                mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, 53, 0, SlotActionType.PICKUP, mc.player);
                currentStep = 5;
                timer = delay.get();
                break;
            case 5:
                currentStep = 0;
                break;
        }
    }

    private void handleSelling() {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            // Wait for screen logic not strictly necessary here as we just came from CheckContents
            // But if it closed unexpectedly:
            stage = Stage.SCANNING;
            return;
        }

        int goldSlot = findSlot(screen, Items.GOLD_INGOT);
        if (goldSlot != -1) {
            // Store the current ID to ensure we wait for the next menu
            sellMenuSyncId = screen.getScreenHandler().syncId;

            mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, goldSlot, 0, SlotActionType.PICKUP, mc.player);
            stage = Stage.CONFIRM_SELL;
            timer = delay.get();
            attempts = 0;
            nullScreenCounter = 0;
        } else {
            if (debug.get()) warning("Could not find Gold Ingot (Sell All). Skipping spawner.");
            mc.player.closeHandledScreen();
            processedSpawners.add(currentTarget);
            stage = Stage.SCANNING;
        }
    }

    private void handleConfirmSell() {
        // --- GRACE PERIOD LOGIC ---
        // The server often closes the menu to open the confirmation menu.
        // We must tolerate a few ticks of "null" screen.
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            nullScreenCounter++;
            if (nullScreenCounter > 20) { // Tolerate ~1 second of closed screen
                if (debug.get()) warning("Menu closed for too long during confirmation.");
                stage = Stage.SCANNING;
            }
            return; // Wait for next tick
        }
        nullScreenCounter = 0; // Screen is open, reset null counter

        // --- VALIDATE WE ARE IN THE NEW MENU ---
        // If the Sync ID is the same AND the Gold Ingot is still there, we are likely still in the Sell Menu.
        // This prevents clicking decorative stained glass in the Sell Menu.
        boolean sameId = (screen.getScreenHandler().syncId == sellMenuSyncId);
        boolean hasGold = (findSlot(screen, Items.GOLD_INGOT) != -1);

        if (sameId && hasGold) {
            // We are likely still in the old menu. Wait.
            timer = 2;
            return;
        }

        // --- BUTTON SEARCH LOGIC ---
        // Servers may use Lime (Light Green) or Green (Dark Green) panes.
        int confirmSlot = findSlot(screen, Items.LIME_STAINED_GLASS_PANE);
        if (confirmSlot == -1) {
            confirmSlot = findSlot(screen, Items.GREEN_STAINED_GLASS_PANE);
        }

        if (confirmSlot != -1) {
            mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, confirmSlot, 0, SlotActionType.PICKUP, mc.player);
            if (debug.get()) info("Sold successfully. Moving to next.");
            mc.player.closeHandledScreen();
            processedSpawners.add(currentTarget);
            stage = Stage.SCANNING;
            timer = delay.get();
        } else {
            // Button not found yet (lag or menu still loading)
            attempts++;
            if (attempts > 30) { // ~3 seconds wait time total
                if (debug.get()) warning("Could not find Confirm button (Green/Lime Pane).");
                mc.player.closeHandledScreen();
                processedSpawners.add(currentTarget);
                stage = Stage.SCANNING;
                attempts = 0;
            } else {
                timer = 2; // Check frequently
            }
        }
    }

    // --- Helpers ---

    private int findSlot(HandledScreen<?> screen, net.minecraft.item.Item item) {
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.getStack().getItem() == item) {
                return slot.id;
            }
        }
        return -1;
    }

    private void generateRandomOffset(BlockPos pos) {
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d playerEye = mc.player.getEyePos();
        double dx = playerEye.x - center.x;
        double dy = playerEye.y - center.y;
        double dz = playerEye.z - center.z;

        currentFace = Direction.getFacing(dx, dy, dz);

        double x = 0.5;
        double y = 0.5;
        double z = 0.5;

        double off1 = (Math.random() * 0.4) - 0.2;
        double off2 = (Math.random() * 0.4) - 0.2;

        switch (currentFace) {
            case UP -> { y = 1.0; x += off1; z += off2; }
            case DOWN -> { y = 0.0; x += off1; z += off2; }
            case NORTH -> { z = 0.0; x += off1; y += off2; }
            case SOUTH -> { z = 1.0; x += off1; y += off2; }
            case WEST -> { x = 0.0; z += off1; y += off2; }
            case EAST -> { x = 1.0; z += off1; y += off2; }
        }

        currentTargetOffset = new Vec3d(x, y, z);
    }

    private boolean rotateTowards(BlockPos pos) {
        Vec3d targetPos = new Vec3d(pos.getX() + currentTargetOffset.x, pos.getY() + currentTargetOffset.y, pos.getZ() + currentTargetOffset.z);
        Vec3d playerPos = mc.player.getEyePos();
        Vec3d direction = targetPos.subtract(playerPos).normalize();

        double targetYaw = Math.toDegrees(Math.atan2(-direction.x, direction.z));
        double targetPitch = Math.toDegrees(-Math.asin(direction.y));

        float currentYaw = mc.player.getYaw();
        float currentPitch = mc.player.getPitch();

        float yawDelta = (float) MathHelper.wrapDegrees(targetYaw - currentYaw);
        float pitchDelta = (float) MathHelper.wrapDegrees(targetPitch - currentPitch);

        float speed = rotationSpeed.get().floatValue();
        float yawChange = MathHelper.clamp(yawDelta, -speed, speed);
        float pitchChange = MathHelper.clamp(pitchDelta, -speed, speed);

        mc.player.setYaw(currentYaw + yawChange);
        mc.player.setPitch(currentPitch + pitchChange);

        return Math.abs(yawDelta) < 5.0 && Math.abs(pitchDelta) < 5.0;
    }

    private boolean hasArrowsInInventory(HandledScreen<?> screen) {
        // Checking first 5 rows (0-44) to avoid "Next Page" button in row 6
        for (int i = 0; i < 45; i++) {
            if (i >= screen.getScreenHandler().slots.size()) break;

            ItemStack stack = screen.getScreenHandler().getSlot(i).getStack();
            if (!stack.isEmpty() && stack.getItem() == Items.ARROW) {
                return true;
            }
        }
        return false;
    }
}
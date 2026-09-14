package dev.kianj.materialsgui.box;

import dev.kianj.materialsgui.data.BoxKey;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Works out which placed container the open screen belongs to. The server never tells the client, so we remember
 * the block the player last right-clicked and match it to the next chest/barrel/shulker screen that opens.
 */
public final class BoxTracker {
	private static final long MATCH_WINDOW_MS = 5000;

	private static @Nullable BoxKey pendingKey;
	private static long pendingTime;
	private static @Nullable Screen boxScreen;
	private static @Nullable BoxKey boxKey;
	private static int boxSize;

	private BoxTracker() {}

	public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (level.isClientSide() && !BoxRefresher.isUsingBlock()) {
			BlockPos pos = normalize(level, hit.getBlockPos());
			pendingKey = pos == null ? null : BoxKey.of(level.dimension().identifier().toString(), pos);
			pendingTime = System.currentTimeMillis();
		}
		return InteractionResult.PASS;
	}

	/** Registry id of the block at a position, e.g. "minecraft:red_shulker_box". */
	public static String blockId(Level level, BlockPos pos) {
		return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
	}

	/** Number of slots in the container at a canonical position (54 for a double chest), or 0 if it isn't one. */
	public static int containerSize(Level level, BlockPos canonical) {
		BlockState state = level.getBlockState(canonical);
		if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			return 54;
		}
		return level.getBlockEntity(canonical) instanceof Container container ? container.getContainerSize() : 0;
	}

	/** Returns the canonical position of a container block (lower-coordinate half for double chests), or null. */
	public static @Nullable BlockPos normalize(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof EnderChestBlock || !(level.getBlockEntity(pos) instanceof Container)) {
			return null;
		}
		if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
			return other.compareTo(pos) < 0 ? other : pos;
		}
		return pos;
	}

	/** Called when a screen initializes. Returns the box key when the screen is a tracked container. */
	public static @Nullable BoxKey attach(Screen screen) {
		if (screen == boxScreen) {
			return boxKey;
		}
		if (!(screen instanceof ContainerScreen || screen instanceof ShulkerBoxScreen)
			|| pendingKey == null
			|| System.currentTimeMillis() - pendingTime > MATCH_WINDOW_MS) {
			return null;
		}
		boxScreen = screen;
		boxKey = pendingKey;
		pendingKey = null;
		boxSize = containerSize(((AbstractContainerScreen<?>) screen).getMenu());
		return boxKey;
	}

	/** Number of the menu's slots that belong to the container rather than the player's inventory. */
	public static int containerSize(AbstractContainerMenu menu) {
		int size = 0;
		for (Slot slot : menu.slots) {
			if (!(slot.container instanceof Inventory)) {
				size++;
			}
		}
		return size;
	}

	public static @Nullable BoxKey keyFor(Screen screen) {
		return screen == boxScreen ? boxKey : null;
	}

	/** The box whose screen is open, or null. */
	public static @Nullable BoxKey openKey() {
		return boxKey;
	}

	/** True if the player right-clicked a container within the last {@code ms} and its screen hasn't opened yet. */
	public static boolean hasRecentPending(long ms) {
		return pendingKey != null && System.currentTimeMillis() - pendingTime < ms;
	}

	public static int size() {
		return boxSize;
	}

	public static void detach(Screen screen) {
		if (screen == boxScreen) {
			boxScreen = null;
			boxKey = null;
		}
	}
}

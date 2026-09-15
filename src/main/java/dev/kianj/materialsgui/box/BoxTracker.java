package dev.kianj.materialsgui.box;

import dev.kianj.materialsgui.data.BoxKey;
import dev.kianj.materialsgui.mixin.ClientLevelAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.client.multiplayer.ClientLevel;
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
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Works out which placed container the open screen belongs to. The server never tells the client, so we remember
 * the chest, barrel or shulker box the player last right-clicked and match it to the next screen of the same size. The
 * click is forgotten as soon as it can't be the one: when the server acknowledges it without opening anything (the
 * chest was blocked, say), when any other container opens, or when the player right-clicks an entity.
 */
public final class BoxTracker {
	private static final long MATCH_WINDOW_MS = 5000;

	private static @Nullable BoxKey pendingKey;
	private static long pendingTime;
	/** The block-prediction sequence of the pending click, once it's been sent; -1 until then. */
	private static int pendingSequence = -1;
	private static boolean awaitingSequence;
	private static @Nullable Screen boxScreen;
	private static @Nullable BoxKey boxKey;
	private static int boxSize;

	private BoxTracker() {}

	public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (level.isClientSide() && !BoxRefresher.isUsingBlock()) {
			BlockPos pos = normalize(level, hit.getBlockPos());
			pendingKey = pos == null ? null : BoxKey.of(level.dimension().identifier().toString(), pos);
			pendingTime = System.currentTimeMillis();
			pendingSequence = -1;
			awaitingSequence = pendingKey != null;
		}
		return InteractionResult.PASS;
	}

	/** Called after a block right-click has been sent, to note the sequence the server will acknowledge it with. */
	public static void afterUseItemOn(ClientLevel level) {
		if (awaitingSequence) {
			awaitingSequence = false;
			pendingSequence = ((ClientLevelAccessor) level).materialsgui$getPredictionHandler().currentSequence();
		}
	}

	/** The server has handled clicks up to this sequence. A screen one of them opened would already have arrived. */
	public static void onBlockChangedAck(int sequence) {
		if (pendingKey != null && pendingSequence >= 0 && sequence >= pendingSequence) {
			clearPending();
		}
	}

	public static void clearPending() {
		pendingKey = null;
		pendingSequence = -1;
		awaitingSequence = false;
	}

	/** Forgets everything; called when joining a world or server. */
	public static void reset() {
		clearPending();
		boxScreen = null;
		boxKey = null;
		boxSize = 0;
	}

	/** Registry id of the block at a position, e.g. "minecraft:red_shulker_box". */
	public static String blockId(Level level, BlockPos pos) {
		return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
	}

	/** Chests (including trapped and copper ones), barrels and shulker boxes can be Material Boxes. */
	public static boolean isBoxBlock(BlockState state) {
		Block block = state.getBlock();
		return block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock;
	}

	/** Number of slots in the container at a canonical position (54 for a double chest), or 0 if it isn't one. */
	public static int containerSize(Level level, BlockPos canonical) {
		BlockState state = level.getBlockState(canonical);
		if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			return 54;
		}
		return level.getBlockEntity(canonical) instanceof Container container ? container.getContainerSize() : 0;
	}

	/** Returns the canonical position of a box block (lower-coordinate half for double chests), or null. */
	public static @Nullable BlockPos normalize(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!isBoxBlock(state) || !(level.getBlockEntity(pos) instanceof Container)) {
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
		// The inventory opens without the server, so it doesn't use up a click on a container.
		if (!(screen instanceof AbstractContainerScreen<?> containerScreen) || screen instanceof InventoryScreen
			|| screen instanceof CreativeModeInventoryScreen) {
			return null;
		}
		BoxKey key = pendingKey;
		boolean recent = key != null && System.currentTimeMillis() - pendingTime <= MATCH_WINDOW_MS;
		// Any container opening uses up the click, so a later one (a chest boat, a server's menu) is never matched to it.
		clearPending();
		if (!recent || !(screen instanceof ContainerScreen || screen instanceof ShulkerBoxScreen)) {
			return null;
		}
		int size = containerSize(containerScreen.getMenu());
		Level level = Minecraft.getInstance().level;
		if (level == null || size != containerSize(level, new BlockPos(key.x(), key.y(), key.z()))) {
			return null;
		}
		boxScreen = screen;
		boxKey = key;
		boxSize = size;
		return key;
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

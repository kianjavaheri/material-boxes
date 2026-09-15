package dev.kianj.materialsgui.box;

import dev.kianj.materialsgui.data.BoxKey;
import dev.kianj.materialsgui.data.ModConfig;
import dev.kianj.materialsgui.data.Project;
import dev.kianj.materialsgui.data.ProjectStore;
import dev.kianj.materialsgui.mixin.ClientLevelAccessor;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LidBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Keeps Material Box contents current without the player opening them. The client only sees what's in a container
 * while it's open, so a box someone else filled (or you did, from another computer) looks unchanged until it's opened.
 *
 * <p>A box is <em>fresh</em> once its contents have been seen this session. It stops being fresh when its chunk
 * unloads (changes there can't be noticed) or when its lid opens while we aren't the one using it. With the setting on,
 * a box that isn't fresh, is closed, and is within reach and in sight is opened in the background: the server is sent
 * the same right-click a player would make, the menu it opens gets no screen, and it's closed as soon as its contents
 * arrive.
 *
 * <p>The server handles clicks in order and acknowledges each one after any container it opened. So a menu that
 * arrives before the click is acknowledged is the box, and an acknowledgement with no menu means the box didn't open
 * (something on top of the chest, say). While a box is being read the player's own right-clicks wait, so the server
 * never has two containers to open or close at once.
 */
public final class BoxRefresher {
	/** The server always acknowledges a click; this is only a safety net. */
	private static final int TIMEOUT_TICKS = 200;
	/** A box that didn't open isn't tried again for this long. */
	private static final int RETRY_TICKS = 600;
	private static final int GAP_TICKS = 10;
	/** A lid still open this long after its box was closed means someone else has it open. */
	private static final int LID_SETTLE_TICKS = 100;
	/** A container the player right-clicked within this long is theirs to open, so no refresh starts meanwhile. */
	private static final long PLAYER_USE_MS = 1000;
	/** Opening a container angers piglins this close that can see you. */
	private static final double PIGLIN_RANGE = 16;

	private static final Set<BoxKey> seen = new HashSet<>();
	private static final Set<BoxKey> fresh = new HashSet<>();
	private static final Map<BoxKey, Long> retryAt = new HashMap<>();
	/** Boxes just closed by us or the player, whose lid may still be coming down, with when they closed. */
	private static final Map<BoxKey, Long> settling = new HashMap<>();
	private static @Nullable Check check;
	private static long ticks;
	private static long lastStart = Long.MIN_VALUE / 2;
	private static boolean usingBlock;

	private BoxRefresher() {}

	/** A background refresh. The menu is null until the server opens it. */
	private static final class Check {
		final BoxKey key;
		final MenuType<?> type;
		final long started;
		int sequence = -1;
		boolean acked;
		@Nullable AbstractContainerMenu menu;

		Check(BoxKey key, MenuType<?> type, long started) {
			this.key = key;
			this.type = type;
			this.started = started;
		}
	}

	/** Forgets everything seen; called when joining or leaving a world or server. */
	public static void reset() {
		seen.clear();
		fresh.clear();
		retryAt.clear();
		settling.clear();
		check = null;
	}

	/** Called while a box's screen shows its contents. */
	public static void markSeen(BoxKey key) {
		seen.add(key);
		fresh.add(key);
	}

	/** Called when the player closes a box's screen. */
	public static void closedByPlayer(BoxKey key) {
		settling.put(key, ticks);
	}

	/** The box's contents may have changed without its lid moving, so read it again when possible. */
	public static void markStale(BoxKey key) {
		fresh.remove(key);
	}

	/** A box moved, e.g. its double chest was split or joined. It keeps having been seen, but its new half is unknown. */
	public static void moved(BoxKey from, BoxKey to) {
		if (seen.remove(from)) {
			seen.add(to);
		}
		fresh.remove(from);
		fresh.remove(to);
		settling.remove(from);
	}

	/** True while the refresher itself is right-clicking a box, so it isn't taken for the player's click. */
	public static boolean isUsingBlock() {
		return usingBlock;
	}

	/** True while a box is being read, when the player's own right-clicks have to wait. */
	public static boolean blocksPlayerUse() {
		return check != null;
	}

	/** Placed Material Boxes of the current list whose contents haven't been seen since joining. */
	public static int uncheckedCount() {
		int count = 0;
		for (Project.BoxEntry box : ProjectStore.project().boxes) {
			if (!box.pickedUp && !box.missing && !seen.contains(box.key())) {
				count++;
			}
		}
		return count;
	}

	public static void tick(Minecraft mc) {
		ticks++;
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null) {
			check = null;
			return;
		}
		if (check != null) {
			continueCheck(mc, player);
		}
		String dimension = level.dimension().identifier().toString();
		watchLids(level, dimension);
		if (check == null && ModConfig.get().refreshBoxes && canStart(mc, player)) {
			start(mc, player, level, dimension);
		}
	}

	/** Marks boxes that are out of sight, or that someone else has opened, as needing a fresh look. */
	private static void watchLids(ClientLevel level, String dimension) {
		BoxKey open = BoxTracker.openKey();
		for (Project.BoxEntry box : ProjectStore.project().boxes) {
			if (box.pickedUp || box.missing) {
				continue;
			}
			BoxKey key = box.key();
			BlockPos pos = new BlockPos(box.x, box.y, box.z);
			if (!box.dimension.equals(dimension) || !level.isLoaded(pos)) {
				fresh.remove(key);
				continue;
			}
			if (key.equals(open) || (check != null && key.equals(check.key))) {
				continue;
			}
			boolean lidOpen = isOpen(level, pos);
			Long closed = settling.get(key);
			if (closed != null) {
				// Our own lid coming down isn't someone else opening the box, however long the server takes to say so.
				if (!lidOpen) {
					settling.remove(key);
				} else if (ticks - closed > LID_SETTLE_TICKS) {
					settling.remove(key);
					fresh.remove(key);
				}
				continue;
			}
			if (lidOpen) {
				fresh.remove(key);
			}
		}
	}

	/** Whether a container's lid is open (or still moving), i.e. someone is using it. */
	private static boolean isOpen(ClientLevel level, BlockPos pos) {
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (blockEntity instanceof ShulkerBoxBlockEntity shulker) {
			return !shulker.isClosed();
		}
		if (blockEntity instanceof LidBlockEntity lid) {
			return lid.getOpenNess(1.0F) > 0;
		}
		BlockState state = level.getBlockState(pos);
		return state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
	}

	/** Only when the player isn't in a screen or doing something the click could get in the way of. */
	private static boolean canStart(Minecraft mc, LocalPlayer player) {
		return ticks - lastStart >= GAP_TICKS
			&& mc.gui.screen() == null
			&& mc.gameMode != null
			&& mc.getConnection() != null
			&& player.containerMenu == player.inventoryMenu
			&& player.isAlive()
			&& !player.isSpectator()
			// Sneaking with an item in hand would use the item (placing a block) instead of opening the box.
			&& !player.isSecondaryUseActive()
			&& !player.isUsingItem()
			&& !mc.options.keyUse.isDown()
			&& !BoxTracker.hasRecentPending(PLAYER_USE_MS);
	}

	private static void start(Minecraft mc, LocalPlayer player, ClientLevel level, String dimension) {
		Boolean piglinsNearby = null;
		for (Project.BoxEntry box : ProjectStore.project().boxes) {
			BoxKey key = box.key();
			BlockPos pos = new BlockPos(box.x, box.y, box.z);
			Long retry = retryAt.get(key);
			if (box.pickedUp || box.missing || fresh.contains(key) || (retry != null && ticks < retry) || !box.dimension.equals(dimension)
				|| !level.isLoaded(pos) || !player.isWithinBlockInteractionRange(pos, 0) || isOpen(level, pos)) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			int size = BoxTracker.containerSize(level, pos);
			// Opening a trapped chest sends a redstone signal, and a chest with something on top can't open at all.
			if (state.getBlock() instanceof TrappedChestBlock || (state.getBlock() instanceof ChestBlock && ChestBlock.isChestBlockedAt(level, pos))
				|| size == 0 || size != box.size) {
				continue;
			}
			// Click the box where a line from the player's eyes meets it, and only if nothing is in the way.
			BlockHitResult hit = level.clip(new ClipContext(player.getEyePosition(), Vec3.atCenterOf(pos), ClipContext.Block.OUTLINE,
				ClipContext.Fluid.NONE, player));
			if (hit.getType() != HitResult.Type.BLOCK || !pos.equals(BoxTracker.normalize(level, hit.getBlockPos()))) {
				continue;
			}
			if (piglinsNearby == null) {
				piglinsNearby = !level.getEntitiesOfClass(Piglin.class, player.getBoundingBox().inflate(PIGLIN_RANGE)).isEmpty();
			}
			if (piglinsNearby) {
				return;
			}
			MenuType<?> type = state.getBlock() instanceof ShulkerBoxBlock ? MenuType.SHULKER_BOX : size == 54 ? MenuType.GENERIC_9x6 : MenuType.GENERIC_9x3;
			Check c = new Check(key, type, ticks);
			check = c;
			lastStart = ticks;
			retryAt.put(key, ticks + RETRY_TICKS);
			usingBlock = true;
			try {
				mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
			} finally {
				usingBlock = false;
			}
			c.sequence = ((ClientLevelAccessor) level).materialsgui$getPredictionHandler().currentSequence();
			return;
		}
	}

	/**
	 * Called when the server opens a menu. Returns true if it's the box being refreshed (the right kind of menu, before
	 * the click was acknowledged), in which case the menu is set up without a screen.
	 */
	public static boolean onOpenScreen(MenuType<?> type, int containerId) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (check == null || check.menu != null || check.acked || type != check.type || player == null) {
			return false;
		}
		check.menu = type.create(containerId, player.getInventory());
		player.containerMenu = check.menu;
		return true;
	}

	/** The server has handled clicks up to this sequence, and sent any menu they opened before this. */
	public static void onBlockChangedAck(int sequence) {
		if (check != null && check.sequence >= 0 && sequence >= check.sequence) {
			check.acked = true;
		}
	}

	private static void continueCheck(Minecraft mc, LocalPlayer player) {
		Check c = check;
		boolean timedOut = ticks - c.started > TIMEOUT_TICKS;
		if (c.menu == null) {
			// Acknowledged without a menu: the server didn't open the box. It's tried again later.
			if (c.acked || timedOut) {
				check = null;
			}
			return;
		}
		if (player.containerMenu != c.menu) {
			// The server closed it.
			check = null;
			return;
		}
		if (c.menu.getStateId() == 0) {
			// Waiting for the contents.
			if (timedOut) {
				check = null;
				close(mc, player, c);
			}
			return;
		}
		check = null;
		Project project = ProjectStore.project();
		int index = project.indexOfBox(c.key);
		int size = BoxTracker.containerSize(c.menu);
		if (index >= 0 && size > 0) {
			Project.BoxEntry box = project.boxes.get(index);
			boolean changed = box.size != size;
			if (changed) {
				box.resize(size);
			}
			ContainerHooks.Contents contents = ContainerHooks.read(c.menu, size);
			changed |= box.setContents(contents.items(), contents.counts(), contents.nested());
			if (changed) {
				ProjectStore.changed();
			}
		}
		markSeen(c.key);
		retryAt.remove(c.key);
		close(mc, player, c);
	}

	/**
	 * Closes the hidden menu. The server closes whatever container is open when told to, so this only tells it while
	 * the hidden menu is still the open one.
	 */
	private static void close(Minecraft mc, LocalPlayer player, Check c) {
		settling.put(c.key, ticks);
		if (player.containerMenu != c.menu) {
			return;
		}
		if (mc.getConnection() != null) {
			mc.getConnection().send(new ServerboundContainerClosePacket(c.menu.containerId));
		}
		player.containerMenu = player.inventoryMenu;
	}
}

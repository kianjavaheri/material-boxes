package dev.kianj.materialsgui.box;

import dev.kianj.materialsgui.data.BoxKey;
import dev.kianj.materialsgui.data.ModConfig;
import dev.kianj.materialsgui.data.Project;
import dev.kianj.materialsgui.data.ProjectStore;
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
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.ClipContext;
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
 */
public final class BoxRefresher {
	/** How long to wait for the server to open a box before giving up. */
	private static final int TIMEOUT_TICKS = 40;
	/** A box that couldn't be opened (something on top of the chest, say) isn't tried again for this long. */
	private static final int RETRY_TICKS = 600;
	private static final int GAP_TICKS = 10;
	/** A lid keeps moving for a while after the box closes, so it isn't mistaken for someone else opening it. */
	private static final int OWN_CLOSE_GRACE_TICKS = 40;
	/** A container the player right-clicked within this long is theirs to open, so no refresh starts meanwhile. */
	private static final long PLAYER_USE_MS = 1000;

	private static final Set<BoxKey> seen = new HashSet<>();
	private static final Set<BoxKey> fresh = new HashSet<>();
	private static final Map<BoxKey, Long> retryAt = new HashMap<>();
	private static final Map<BoxKey, Long> closedAt = new HashMap<>();
	private static @Nullable Check check;
	private static long ticks;
	private static long lastStart = Long.MIN_VALUE / 2;
	private static boolean usingBlock;

	private BoxRefresher() {}

	/** A background refresh: the menu is null until the server opens it. */
	private static final class Check {
		final BoxKey key;
		final long started;
		@Nullable AbstractContainerMenu menu;

		Check(BoxKey key, long started) {
			this.key = key;
			this.started = started;
		}
	}

	/** Forgets everything seen; called when joining a world or server. */
	public static void reset() {
		seen.clear();
		fresh.clear();
		retryAt.clear();
		closedAt.clear();
		check = null;
	}

	/** Called while a box's screen shows its contents. */
	public static void markSeen(BoxKey key) {
		seen.add(key);
		fresh.add(key);
	}

	/** Called when the player closes a box's screen. */
	public static void closedByPlayer(BoxKey key) {
		closedAt.put(key, ticks);
	}

	/** True while the refresher itself is right-clicking a box, so it isn't taken for the player's click. */
	public static boolean isUsingBlock() {
		return usingBlock;
	}

	/** Placed Material Boxes of the current list whose contents haven't been seen since joining. */
	public static int uncheckedCount() {
		int count = 0;
		for (Project.BoxEntry box : ProjectStore.project().boxes) {
			if (!box.pickedUp && !seen.contains(box.key())) {
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
			if (box.pickedUp) {
				continue;
			}
			BoxKey key = box.key();
			BlockPos pos = new BlockPos(box.x, box.y, box.z);
			if (!box.dimension.equals(dimension) || !level.isLoaded(pos)) {
				fresh.remove(key);
				continue;
			}
			Long closed = closedAt.get(key);
			boolean ours = key.equals(open) || (check != null && key.equals(check.key)) || (closed != null && ticks - closed < OWN_CLOSE_GRACE_TICKS);
			if (!ours && isOpen(level, pos)) {
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
		for (Project.BoxEntry box : ProjectStore.project().boxes) {
			BoxKey key = box.key();
			BlockPos pos = new BlockPos(box.x, box.y, box.z);
			Long retry = retryAt.get(key);
			if (box.pickedUp || fresh.contains(key) || (retry != null && ticks < retry) || !box.dimension.equals(dimension)
				|| !level.isLoaded(pos) || !player.isWithinBlockInteractionRange(pos, 0) || isOpen(level, pos)) {
				continue;
			}
			// Click the box where a line from the player's eyes meets it, and only if nothing is in the way.
			BlockHitResult hit = level.clip(new ClipContext(player.getEyePosition(), Vec3.atCenterOf(pos), ClipContext.Block.OUTLINE,
				ClipContext.Fluid.NONE, player));
			if (hit.getType() != HitResult.Type.BLOCK || !pos.equals(BoxTracker.normalize(level, hit.getBlockPos()))) {
				continue;
			}
			check = new Check(key, ticks);
			lastStart = ticks;
			retryAt.put(key, ticks + RETRY_TICKS);
			usingBlock = true;
			try {
				mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
			} finally {
				usingBlock = false;
			}
			return;
		}
	}

	/**
	 * Called when the server opens a menu. Returns true if it's the box being refreshed, in which case the menu is set
	 * up without a screen.
	 */
	public static boolean onOpenScreen(MenuType<?> type, int containerId) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (check == null || check.menu != null || player == null
			|| !(type == MenuType.GENERIC_9x3 || type == MenuType.GENERIC_9x6 || type == MenuType.SHULKER_BOX)) {
			return false;
		}
		check.menu = type.create(containerId, player.getInventory());
		player.containerMenu = check.menu;
		return true;
	}

	private static void continueCheck(Minecraft mc, LocalPlayer player) {
		Check c = check;
		if (c.menu == null || c.menu.getStateId() == 0) {
			// Still waiting for the server to open it, or for its contents.
			if (ticks - c.started > TIMEOUT_TICKS) {
				if (c.menu != null) {
					close(mc, player, c);
				}
				check = null;
			}
			return;
		}
		check = null;
		if (player.containerMenu != c.menu) {
			// The server closed it, or the player opened something else.
			return;
		}
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
		if (player.containerMenu != c.menu) {
			return;
		}
		if (mc.getConnection() != null) {
			mc.getConnection().send(new ServerboundContainerClosePacket(c.menu.containerId));
		}
		player.containerMenu = player.inventoryMenu;
		closedAt.put(c.key, ticks);
	}
}

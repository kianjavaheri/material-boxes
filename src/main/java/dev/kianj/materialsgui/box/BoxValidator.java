package dev.kianj.materialsgui.box;

import dev.kianj.materialsgui.data.BoxKey;
import dev.kianj.materialsgui.data.Project;
import dev.kianj.materialsgui.data.ProjectStore;
import dev.kianj.materialsgui.data.SavedLists;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jspecify.annotations.Nullable;

/**
 * Keeps Material Boxes in sync with the world. It follows double chests that are split or joined, removes boxes the
 * player breaks, and marks boxes whose block went some other way as missing: they stop counting, and come back if the
 * block does. Missing rather than removed, because a box can look gone when it isn't, for example on another server
 * behind the same address. Checks the current list's boxes and every saved list's boxes in this world. Only boxes in
 * loaded chunks of the current dimension can be checked.
 */
public final class BoxValidator {
	private static final int INTERVAL_TICKS = 10;
	/** A box whose block the player broke this recently is removed rather than kept as missing. */
	private static final long PLAYER_BREAK_MS = 10_000;

	private static final Set<BoxKey> missingLastCheck = new HashSet<>();
	private static final Map<BoxKey, Long> brokenByPlayer = new HashMap<>();
	private static int ticks;

	private BoxValidator() {}

	/** Forgets everything; called when joining a world or server. */
	public static void reset() {
		missingLastCheck.clear();
		brokenByPlayer.clear();
	}

	/** Called when the player breaks a block. */
	public static void onPlayerBreak(ClientLevel level, LocalPlayer player, BlockPos pos, BlockState state) {
		brokenByPlayer.put(BoxKey.of(level.dimension().identifier().toString(), pos), System.currentTimeMillis());
	}

	private static boolean wasBrokenByPlayer(BoxKey key) {
		Long at = brokenByPlayer.get(key);
		return at != null && System.currentTimeMillis() - at < PLAYER_BREAK_MS;
	}

	public static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null || mc.player == null || ++ticks % INTERVAL_TICKS != 0) {
			return;
		}
		String dimension = level.dimension().identifier().toString();
		Project project = ProjectStore.project();
		Set<BoxKey> missingNow = new HashSet<>();

		boolean changed = validate(mc, level, dimension, project.boxes, true, missingNow);
		boolean listsChanged = false;
		for (SavedLists.SavedList list : SavedLists.all()) {
			List<Project.BoxEntry> boxes = list.boxes.get(ProjectStore.worldKey());
			// The current list's boxes are the project's, checked above.
			if (boxes != null && !list.name.equalsIgnoreCase(project.listName)) {
				listsChanged |= validate(mc, level, dimension, boxes, false, missingNow);
			}
		}

		missingLastCheck.clear();
		missingLastCheck.addAll(missingNow);
		long now = System.currentTimeMillis();
		brokenByPlayer.values().removeIf(at -> now - at >= PLAYER_BREAK_MS);
		if (changed) {
			ProjectStore.changed();
		}
		if (listsChanged) {
			SavedLists.write();
		}
	}

	/** Checks one list's boxes. Only the current list's changes are announced. Returns true if anything changed. */
	private static boolean validate(Minecraft mc, ClientLevel level, String dimension, List<Project.BoxEntry> boxes, boolean current,
		Set<BoxKey> missingNow) {
		boolean changed = false;
		for (Iterator<Project.BoxEntry> it = boxes.iterator(); it.hasNext(); ) {
			Project.BoxEntry box = it.next();
			BoxKey key = box.key();
			BlockPos pos = new BlockPos(box.x, box.y, box.z);
			if (box.pickedUp || !box.dimension.equals(dimension) || !level.isLoaded(pos)) {
				continue;
			}
			BlockPos canonical = BoxTracker.normalize(level, pos);
			if (canonical == null && box.size == 54 && missingLastCheck.contains(key)) {
				// The lower half of a double chest was broken: the box lives on in the other half.
				canonical = survivingHalf(level, pos, box, boxes, dimension);
			}
			BoxKey newKey = canonical == null ? null : BoxKey.of(dimension, canonical);
			boolean gone = newKey == null;
			// Joined into a double chest with another Material Box, which now covers both halves.
			boolean merged = !gone && !newKey.equals(key) && Project.indexOfBox(boxes, newKey) >= 0;
			if (gone || merged) {
				// Require two misses in a row so a mispredicted client-side break doesn't count.
				missingNow.add(key);
				if (!missingLastCheck.contains(key)) {
					continue;
				}
				int number = boxes.indexOf(box) + 1;
				if (gone && box.isShulker()) {
					// A broken shulker box keeps its items, so the Material Box lives on until it's placed again.
					box.pickedUp = true;
					changed = true;
					announce(mc, current, "Material Box #" + number + " picked up. Its items still count, and it reconnects when you place and open it.");
				} else if (merged || wasBrokenByPlayer(key)) {
					it.remove();
					changed = true;
					announce(mc, current, merged ? "Material Box #" + number + " was joined to another Material Box's chest"
						: "Material Box at " + key.describe() + " is gone, so it was removed");
				} else if (!box.missing) {
					box.missing = true;
					changed = true;
					announce(mc, current, "Material Box at " + key.describe() + " is missing, so its items don't count. Remove it in Boxes... if it's gone for good.");
				}
				continue;
			}
			if (box.missing) {
				box.missing = false;
				changed = true;
				BoxRefresher.markStale(key);
			}
			if (box.block == null) {
				// Saves from 1.0.0 didn't record the block.
				box.block = BoxTracker.blockId(level, canonical);
				changed = true;
			}
			int size = BoxTracker.containerSize(level, canonical);
			if (!newKey.equals(key) || size != box.size) {
				reshape(level, box, pos, canonical, size);
				box.moveTo(newKey);
				changed = true;
				BoxRefresher.moved(key, newKey);
			}
		}
		return changed;
	}

	private static void announce(Minecraft mc, boolean current, String message) {
		if (current && mc.player != null) {
			mc.player.sendOverlayMessage(Component.literal(message));
		}
	}

	/** The single chest left beside a double chest's lower half after that half was broken, or null. */
	private static @Nullable BlockPos survivingHalf(ClientLevel level, BlockPos pos, Project.BoxEntry box, List<Project.BoxEntry> boxes, String dimension) {
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos other = pos.relative(direction);
			BlockState state = level.getBlockState(other);
			if (other.compareTo(pos) > 0 && state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) == ChestType.SINGLE
				&& state.getValue(ChestBlock.FACING).getAxis() != direction.getAxis()
				&& (box.block == null || box.block.equals(BoxTracker.blockId(level, other)))
				&& Project.indexOfBox(boxes, BoxKey.of(dimension, other)) < 0) {
				return other;
			}
		}
		return null;
	}

	/**
	 * Keeps the recorded contents lined up when a double chest splits or forms. A double chest's menu shows its first
	 * half (the one of type RIGHT) in slots 0-26 and the other half in 27-53. Which of those is the lower-coordinate
	 * half, where the box is kept, depends on which way the chest faces.
	 */
	private static void reshape(ClientLevel level, Project.BoxEntry box, BlockPos oldPos, BlockPos canonical, int size) {
		BlockState state = level.getBlockState(canonical);
		if (box.size == 54 && size == 27 && state.getBlock() instanceof ChestBlock) {
			Direction facing = state.getValue(ChestBlock.FACING);
			// The broken half is the old position if the box moved, otherwise the neighbor on the higher side.
			BlockPos broken = !canonical.equals(oldPos) ? oldPos : higherSide(canonical, facing);
			// A RIGHT half's partner is counter-clockwise of its facing.
			boolean survivorWasFirst = broken.equals(canonical.relative(facing.getCounterClockWise()));
			box.reshape(27, survivorWasFirst ? 0 : 27, 0, 27);
		} else if (box.size == 27 && size == 54 && level.getBlockState(oldPos).getBlock() instanceof ChestBlock) {
			boolean oldHalfIsFirst = level.getBlockState(oldPos).getValue(ChestBlock.TYPE) == ChestType.RIGHT;
			box.reshape(54, 0, oldHalfIsFirst ? 0 : 27, 27);
		} else if (size != box.size) {
			box.resize(size);
		}
	}

	private static BlockPos higherSide(BlockPos pos, Direction facing) {
		BlockPos clockwise = pos.relative(facing.getClockWise());
		return clockwise.compareTo(pos) > 0 ? clockwise : pos.relative(facing.getCounterClockWise());
	}
}

package dev.kianj.materialsgui.data;

import net.minecraft.core.BlockPos;

/** Identifies a placed container by dimension + block position (the lower half's position for double chests). */
public record BoxKey(String dimension, int x, int y, int z) {
	public static BoxKey of(String dimension, BlockPos pos) {
		return new BoxKey(dimension, pos.getX(), pos.getY(), pos.getZ());
	}

	public String describe() {
		return x + ", " + y + ", " + z;
	}
}

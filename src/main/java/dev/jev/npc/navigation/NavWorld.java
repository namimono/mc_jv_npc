package dev.jev.npc.navigation;

import net.minecraft.core.BlockPos;

/** Read-only view the planner searches over; the game adapter also encodes permissions and tools. */
public interface NavWorld {
    Cell cell(int x, int y, int z);
    default Cell cell(BlockPos pos) { return cell(pos.getX(), pos.getY(), pos.getZ()); }
}

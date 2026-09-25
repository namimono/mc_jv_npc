package dev.jev.npc.navigation;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.function.Predicate;
import java.util.function.ToIntBiFunction;

/** Classifies real blocks for one planning pass. Only reads loaded chunks; results are cached for the pass. */
public final class LevelNavWorld implements NavWorld {
    private final Level level;
    private final Predicate<BlockPos> mayModify;
    private final ToIntBiFunction<BlockState, BlockPos> breakTicks;
    private final Long2ObjectOpenHashMap<Cell> cache = new Long2ObjectOpenHashMap<>();

    public LevelNavWorld(Level level, Predicate<BlockPos> mayModify, ToIntBiFunction<BlockState, BlockPos> breakTicks) {
        this.level = level;
        this.mayModify = mayModify;
        this.breakTicks = breakTicks;
    }

    @Override public Cell cell(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        Cell cell = cache.get(key);
        if (cell == null) {
            cell = classify(new BlockPos(x, y, z));
            cache.put(key, cell);
        }
        return cell;
    }

    private Cell classify(BlockPos pos) {
        BlockState state = loaded(pos);
        if (state == null) return Cell.UNLOADED;
        if (state.getFluidState().is(FluidTags.LAVA)) return Cell.lava(state.canBeReplaced() && mayModify.test(pos));
        if (hazard(state)) return Cell.HAZARD;
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            boolean placeable = state.canBeReplaced() && mayModify.test(pos);
            return state.getFluidState().is(FluidTags.WATER) ? Cell.water(placeable) : Cell.open(placeable);
        }
        double top = shape.max(Direction.Axis.Y);
        boolean floor = state.isFaceSturdy(level, pos, Direction.UP) || top >= 0.5 && top <= 1.0;
        int ticks = breakable(pos, state) ? breakTicks.applyAsInt(state, pos) : -1;
        return floor ? Cell.solid(ticks, terrain(state)) : Cell.obstacle(ticks, terrain(state));
    }

    private BlockState loaded(BlockPos pos) {
        if (level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)) return null;
        return level.getBlockState(pos);
    }

    private boolean breakable(BlockPos pos, BlockState state) {
        if (state.getDestroySpeed(level, pos) < 0 || state.hasBlockEntity() || !mayModify.test(pos)) return false;
        BlockState above = loaded(pos.above());
        if (above == null || above.getBlock() instanceof FallingBlock) return false;
        for (Direction direction : Direction.values()) {
            BlockState neighbor = loaded(pos.relative(direction));
            if (neighbor == null || neighbor.getFluidState().is(FluidTags.LAVA)) return false;
        }
        return true;
    }

    /** Natural terrain the NPC may dig on its own. Anything else could belong to a player's build. */
    public static boolean terrain(BlockState state) {
        if (state.is(BlockTags.LEAVES))
            return !state.hasProperty(LeavesBlock.PERSISTENT) || !state.getValue(LeavesBlock.PERSISTENT);
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD)
            || state.is(BlockTags.BASE_STONE_NETHER) || state.is(BlockTags.NYLIUM) || state.is(BlockTags.SNOW)
            || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE)
            || state.is(BlockTags.COAL_ORES) || state.is(BlockTags.IRON_ORES) || state.is(BlockTags.COPPER_ORES)
            || state.is(BlockTags.GOLD_ORES) || state.is(BlockTags.REDSTONE_ORES) || state.is(BlockTags.LAPIS_ORES)
            || state.is(BlockTags.DIAMOND_ORES) || state.is(BlockTags.EMERALD_ORES);
    }

    private static boolean hazard(BlockState state) {
        return state.is(BlockTags.FIRE) || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH)
            || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.COBWEB) || state.is(Blocks.WITHER_ROSE)
            || state.is(BlockTags.CAMPFIRES) && state.getValue(CampfireBlock.LIT);
    }
}

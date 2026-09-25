package dev.jev.npc.navigation;

/** Why travelling failed, phrased so the task layer can act on it. */
public sealed interface NavOutcome {
    /** A route exists if the NPC carries {@code needed} placeable blocks. */
    record NeedBlocks(int needed, int carried) implements NavOutcome {}
    /** A route exists only with a grant: {@code risk} or {@code break_built} ({@code blocks} counts built blocks to break). */
    record NeedsPermission(String kind, int blocks) implements NavOutcome {}
    /** {@code no_path}, {@code unloaded}, {@code too_far}, {@code stuck} or an execution problem. */
    record Unreachable(String reason) implements NavOutcome {}

    default String code() {
        return switch (this) {
            case NeedBlocks need -> "need_blocks:" + need.needed();
            case NeedsPermission permission -> "needs_permission:" + permission.kind();
            case Unreachable unreachable -> "unreachable:" + unreachable.reason();
        };
    }
}

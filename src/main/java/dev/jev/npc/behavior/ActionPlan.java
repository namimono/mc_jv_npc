package dev.jev.npc.behavior;

import dev.jev.npc.ai.Candidate;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import java.util.UUID;

/** Fully bound arguments chosen by game code, never arbitrary model-generated commands. */
public record ActionPlan(String id, Skill skill, BlockPos position, UUID target,
                         String argument, int count, String description) {
    public ActionPlan {
        if (position != null) position = position.immutable();
        count = Math.clamp(count, 1, 64);
        if (argument == null) argument = "";
    }
    public Candidate candidate() { return new Candidate(id, description); }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putString("skill", skill.name());
        if (position != null) tag.putLong("position", position.asLong());
        if (target != null) tag.putUUID("target", target);
        tag.putString("argument", argument);
        tag.putInt("count", count);
        tag.putString("description", description);
        return tag;
    }

    public static ActionPlan load(CompoundTag tag) {
        return new ActionPlan(tag.getString("id"), Skill.valueOf(tag.getString("skill")),
            tag.contains("position") ? BlockPos.of(tag.getLong("position")) : null,
            tag.hasUUID("target") ? tag.getUUID("target") : null,
            tag.getString("argument"), tag.getInt("count"), tag.getString("description"));
    }
}

package dev.jev.npc.entity;

import dev.jev.npc.JevNpcMod;
import dev.jev.npc.ai.Communicator;
import dev.jev.npc.ai.NpcBrain;
import dev.jev.npc.behavior.SkillRunner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

public final class JevNpcEntity extends PathfinderMob {
    private UUID ownerId;
    private String personality = "cautious";
    private BlockPos home;
    private final SimpleContainer backpack = new SimpleContainer(27);
    private final ArrayDeque<String> memories = new ArrayDeque<>();
    private final SkillRunner skills = new SkillRunner(this);
    private final NpcBrain brain = new NpcBrain(this);
    private final Communicator speech = new Communicator(this::deliver);

    public JevNpcEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        setCanPickUpLoot(false);
    }

    public static AttributeSupplier.Builder attributes() {
        return createMobAttributes().add(Attributes.MAX_HEALTH, 30)
            .add(Attributes.MOVEMENT_SPEED, 0.28).add(Attributes.ATTACK_DAMAGE, 3)
            .add(Attributes.FOLLOW_RANGE, 32);
    }

    @Override protected void registerGoals() { goalSelector.addGoal(0, new FloatGoal(this)); }

    public void initialize(ServerPlayer owner, String personality) {
        this.ownerId = owner.getUUID();
        this.personality = personality;
        this.home = blockPosition();
        setCustomName(Component.literal("小杰 · " + personality));
        setCustomNameVisible(true);
        setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        backpack.addItem(new ItemStack(Items.IRON_AXE));
        backpack.addItem(new ItemStack(Items.IRON_PICKAXE));
        backpack.addItem(new ItemStack(Items.IRON_CHESTPLATE));
        backpack.addItem(new ItemStack(Items.OAK_PLANKS, 32));
        backpack.addItem(new ItemStack(Items.BREAD, 8));
        remember("Owner introduced me to the world. The demo starter kit was issued once.");
        brain.event("spawn", "Meet the owner; choose an initial activity", true);
    }

    @Override public void tick() {
        super.tick();
        if (!level().isClientSide && isAlive()) {
            skills.tick();
            brain.tick();
        }
    }

    @Override public boolean hurt(DamageSource source, float amount) {
        boolean applied = super.hurt(source, amount);
        if (applied && !level().isClientSide && isAlive()) {
            String attacker = source.getEntity() == null ? source.getMsgId() : source.getEntity().getName().getString();
            brain.event("damaged", "Took damage from " + attacker + "; health=" + getHealth(), false);
            if (getHealth() <= 8 || isOnFire() || isInLava()) skills.emergencyRetreat();
        }
        return applied;
    }

    @Override protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!isOwner(player)) return InteractionResult.PASS;
        if (!level().isClientSide && player instanceof ServerPlayer serverPlayer) {
            ItemStack held = player.getItemInHand(hand);
            if (!held.isEmpty()) {
                ItemStack gift = held.copyWithCount(1);
                if (backpack.canAddItem(gift)) {
                    backpack.addItem(gift);
                    held.shrink(1);
                    remember("Owner gave me " + gift.getHoverName().getString());
                    brain.event("gift", "Owner gave me " + BuiltInRegistries.ITEM.getKey(gift.getItem()), false);
                    serverPlayer.sendSystemMessage(Component.literal("[小杰] 收到了：" + gift.getHoverName().getString()));
                } else serverPlayer.sendSystemMessage(Component.literal("[小杰] 背包满了。"));
            } else serverPlayer.sendSystemMessage(Component.literal(status()));
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    public boolean isOwner(Player player) { return ownerId != null && ownerId.equals(player.getUUID()); }
    public UUID ownerId() { return ownerId; }
    public ServerPlayer owner() {
        return getServer() == null || ownerId == null ? null : getServer().getPlayerList().getPlayer(ownerId);
    }
    public String personality() { return personality; }
    public void personality(String value) {
        personality = value;
        setCustomName(Component.literal("小杰 · " + value));
        brain.event("personality_changed", value, true);
    }
    public BlockPos home() { return home == null ? blockPosition() : home; }
    public void home(BlockPos value) { home = value.immutable(); }
    public SimpleContainer backpack() { return backpack; }
    public SkillRunner skills() { return skills; }
    public NpcBrain brain() { return brain; }
    public ServerLevel serverLevel() { return (ServerLevel) level(); }
    public List<String> memories() { return List.copyOf(memories); }

    public void remember(String text) {
        memories.addLast(text.length() > 240 ? text.substring(0, 240) : text);
        while (memories.size() > 8) memories.removeFirst();
    }

    public Communicator speech() { return speech; }
    public void tellOwner(String message) { speech.report(message, level().getGameTime()); }
    public void say(String text) { speech.say(text, level().getGameTime()); }

    private void deliver(Communicator.Channel channel, String text) {
        ServerPlayer owner = owner();
        switch (channel) {
            case REPORT -> { if (owner != null) owner.sendSystemMessage(Component.literal("[小杰] " + text)); }
            case TO_OWNER -> { if (owner != null) owner.sendSystemMessage(Component.literal("<小杰> " + text)); }
            case NEARBY -> {
                for (ServerPlayer player : serverLevel().players())
                    if (player.distanceToSqr(this) <= 32 * 32) player.sendSystemMessage(Component.literal("<小杰> " + text));
            }
        }
        brain.trace("speech", "sent", dev.jev.npc.trace.TraceRecorder.data("channel", channel.name(), "text", text));
        JevNpcMod.LOGGER.info("Jev speech npc={} channel={} text=\"{}\"", getUUID(), channel, text);
    }

    public String status() {
        return "[小杰] 性格=" + personality + " 血量=" + Math.round(getHealth()) + "/30 "
            + "行为=" + skills.summary() + " 决策=" + brain.status();
    }

    @Override protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
        for (ItemStack stack : backpack.removeAllItems()) if (!stack.isEmpty()) spawnAtLocation(stack);
    }

    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (ownerId != null) tag.putUUID("JevOwner", ownerId);
        tag.putString("JevPersonality", personality);
        tag.putLong("JevHome", home().asLong());
        tag.put("JevBackpack", backpack.createTag(registryAccess()));
        tag.put("JevSkills", skills.save());
        tag.put("JevBrain", brain.save());
        ListTag list = new ListTag();
        memories.forEach(memory -> list.add(StringTag.valueOf(memory)));
        tag.put("JevMemories", list);
    }

    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        ownerId = tag.hasUUID("JevOwner") ? tag.getUUID("JevOwner") : null;
        personality = tag.contains("JevPersonality") ? tag.getString("JevPersonality") : "cautious";
        if (tag.contains("JevHome")) home = BlockPos.of(tag.getLong("JevHome"));
        backpack.clearContent();
        backpack.fromTag(tag.getList("JevBackpack", 10), registryAccess());
        skills.load(tag.getCompound("JevSkills"));
        brain.load(tag.getCompound("JevBrain"));
        memories.clear();
        ListTag list = tag.getList("JevMemories", 8);
        for (int i = Math.max(0, list.size() - 8); i < list.size(); i++) remember(list.getString(i));
    }
}

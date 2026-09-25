package dev.jev.npc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.jev.npc.JevNpcMod;
import dev.jev.npc.behavior.ActionPlan;
import dev.jev.npc.behavior.Skill;
import dev.jev.npc.config.ConfigStore;
import dev.jev.npc.entity.JevNpcEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class NpcCommands {
    private static final List<String> PERSONALITIES = List.of("cautious", "brave", "diligent");
    private static final List<String> SKILLS = List.of("follow", "wait", "guard", "home", "attack", "flee", "harvest", "mine", "build", "equip", "eat", "talk", "resume");
    private static final SimpleCommandExceptionType NO_NPC = new SimpleCommandExceptionType(Component.literal("32 格内没有属于你的 NPC，先使用 /jev spawn"));

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("jev").requires(source -> source.hasPermission(2))
            .executes(context -> help(context.getSource()))
            .then(literal("spawn").executes(context -> spawn(context, "cautious"))
                .then(argument("personality", StringArgumentType.word())
                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(PERSONALITIES, builder))
                    .executes(context -> spawn(context, StringArgumentType.getString(context, "personality")))))
            .then(literal("ask").then(argument("message", StringArgumentType.greedyString()).executes(context -> {
                var player = context.getSource().getPlayerOrException();
                require(player).brain().chat(player, StringArgumentType.getString(context, "message"));
                if (JevNpcMod.config().effectiveKey().isBlank()) player.sendSystemMessage(Component.literal(
                    "尚未配置 API key。填写 " + ConfigStore.secretPath(JevNpcMod.configPath()) + " 后 /jev reload；本地测试用 /jev do。"));
                return 1;
            })))
            .then(literal("do").then(argument("skill", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(SKILLS, builder))
                .executes(NpcCommands::executeSkill)))
            .then(literal("move").then(argument("position", BlockPosArgument.blockPos()).executes(context -> {
                var npc = require(context.getSource().getPlayerOrException());
                BlockPos destination = BlockPosArgument.getLoadedBlockPos(context, "position");
                if (npc.distanceToSqr(Vec3.atCenterOf(destination)) > 32 * 32) return fail(context, "Demo 移动目标限制在 32 格内。");
                npc.brain().hold();
                npc.skills().start(plan("manual_move", Skill.MOVE, destination, "移动到 " + destination.toShortString()), false);
                return 1;
            })))
            .then(literal("stop").executes(context -> {
                var npc = require(context.getSource().getPlayerOrException());
                npc.brain().hold();
                npc.skills().stop();
                npc.tellOwner("已取消任务并暂停 Jev 决策。/jev ask 或 /jev think 恢复。");
                return 1;
            }))
            .then(literal("think").executes(context -> {
                require(context.getSource().getPlayerOrException()).brain().wake();
                return 1;
            }))
            .then(literal("status").executes(context -> {
                var npc = require(context.getSource().getPlayerOrException());
                context.getSource().sendSuccess(() -> Component.literal(npc.status()), false);
                return 1;
            }))
            .then(literal("inventory").executes(context -> {
                var npc = require(context.getSource().getPlayerOrException());
                String content = npc.backpack().getItems().stream().filter(stack -> !stack.isEmpty())
                    .map(stack -> stack.getHoverName().getString() + "×" + stack.getCount()).reduce((a, b) -> a + "，" + b).orElse("空");
                npc.tellOwner("背包：" + content + "；主手：" + npc.getMainHandItem().getHoverName().getString());
                return 1;
            }))
            .then(literal("personality").then(argument("value", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(PERSONALITIES, builder))
                .executes(context -> {
                    String value = StringArgumentType.getString(context, "value");
                    if (!PERSONALITIES.contains(value)) return fail(context, "性格只能是 cautious / brave / diligent");
                    require(context.getSource().getPlayerOrException()).personality(value);
                    return 1;
                })))
            .then(literal("sethome").executes(context -> {
                var player = context.getSource().getPlayerOrException();
                var npc = require(player);
                npc.home(player.blockPosition());
                npc.tellOwner("已将你当前的位置设为家。");
                return 1;
            }))
            .then(literal("remove").executes(context -> {
                var npc = require(context.getSource().getPlayerOrException());
                npc.brain().hold();
                npc.discard();
                return 1;
            }))
            .then(literal("reload").executes(context -> {
                try {
                    JevNpcMod.reload(context.getSource().getServer());
                    context.getSource().sendSuccess(() -> Component.literal("Jev 配置已加载；keyConfigured="
                        + !JevNpcMod.config().effectiveKey().isBlank() + "；model=" + JevNpcMod.config().model), false);
                    return 1;
                } catch (IOException exception) { return fail(context, "配置 JSON 无效，保留原配置。检查引号、逗号和字段类型。"); }
            })));
    }

    public static Optional<JevNpcEntity> nearest(ServerPlayer player) {
        return player.serverLevel().getEntitiesOfClass(JevNpcEntity.class, player.getBoundingBox().inflate(32),
            npc -> npc.isOwner(player) && npc.isAlive()).stream().min(Comparator.comparingDouble(player::distanceToSqr));
    }

    private static JevNpcEntity require(ServerPlayer player) throws CommandSyntaxException { return nearest(player).orElseThrow(NO_NPC::create); }
    private static int spawn(CommandContext<CommandSourceStack> context, String personality) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        if (!PERSONALITIES.contains(personality)) return fail(context, "性格只能是 cautious / brave / diligent");
        if (player.serverLevel().getEntitiesOfClass(JevNpcEntity.class, player.getBoundingBox().inflate(64)).size() >= 10)
            return fail(context, "Demo 限制附近最多 10 个 NPC。");
        JevNpcEntity npc = JevNpcMod.NPC.create(player.serverLevel());
        if (npc == null) return fail(context, "无法创建 NPC");
        npc.moveTo(player.getX() + 2, player.getY(), player.getZ(), player.getYRot(), 0);
        if (!player.level().noCollision(npc)) return fail(context, "玩家东侧两格被占用，请到空地再召唤。");
        npc.initialize(player, personality);
        player.serverLevel().addFreshEntity(npc);
        npc.tellOwner("已生成。/jev ask 跟着我 使用 Jev；/jev do follow 可直接测试。右键赠送手中一件物品，空手右键查看状态。");
        return 1;
    }

    private static int executeSkill(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer owner = context.getSource().getPlayerOrException();
        JevNpcEntity npc = require(owner);
        String name = StringArgumentType.getString(context, "skill");
        if (!SKILLS.contains(name)) return fail(context, "未知技能，按 Tab 查看支持列表。");
        BlockPos anchor = owner.blockPosition();
        ActionPlan action = switch (name) {
            case "follow" -> plan("manual_follow", Skill.FOLLOW, null, "跟随主人");
            case "wait" -> plan("manual_wait", Skill.WAIT, null, "原地等待");
            case "guard" -> plan("manual_guard", Skill.GUARD, anchor, "守卫主人当前的位置");
            case "home" -> plan("manual_home", Skill.MOVE, npc.home(), "回家");
            case "harvest" -> new ActionPlan("manual_harvest", Skill.HARVEST, anchor, null, "", 4, "采集附近 4 块天然原木");
            case "mine" -> new ActionPlan("manual_mine", Skill.MINE, anchor, null, "", 4, "挖掘附近 4 块裸露石头");
            case "build" -> new ActionPlan("manual_build", Skill.BUILD, anchor.offset(3, 0, 0), null, "oak_platform_3x3", 9, "在主人东侧搭建 3×3 橡木平台");
            case "equip" -> plan("manual_equip", Skill.EQUIP, null, "装备铁胸甲");
            case "eat" -> plan("manual_eat", Skill.EAT, null, "吃面包");
            case "talk" -> new ActionPlan("manual_talk", Skill.SPEAK, null, null, "greet", 1, "打招呼");
            case "resume" -> plan("manual_resume", Skill.RESUME, null, "恢复暂停的工作");
            case "attack", "flee" -> npc.brain().options().get(name.equals("attack") ? "attack_enemy" : "flee_enemy");
            default -> null;
        };
        if (action == null) return fail(context, "附近没有可用目标。");
        npc.brain().hold();
        npc.skills().start(action, name.equals("attack") || name.equals("flee"));
        return 1;
    }

    private static ActionPlan plan(String id, Skill skill, BlockPos position, String description) {
        return new ActionPlan(id, skill, position, null, "", 1, description);
    }

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Jev NPC Demo：/jev spawn [cautious|brave|diligent]；/jev ask <话语>；"
            + "/jev do <技能>；/jev move <x y z>；/jev status；/jev inventory；/jev stop；/jev think；/jev reload。"
            + "直接技能指令会暂停 Jev，/jev ask 或 /jev think 恢复。"), false);
        return 1;
    }
    private static int fail(CommandContext<CommandSourceStack> context, String text) {
        context.getSource().sendFailure(Component.literal(text));
        return 0;
    }
    private NpcCommands() {}
}

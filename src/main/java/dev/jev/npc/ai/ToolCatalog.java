package dev.jev.npc.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** The shared executable-method catalog, used by conversation planning and argument validation. */
public final class ToolCatalog {
    private static final Map<String, String> METHODS = new LinkedHashMap<>();
    static {
        METHODS.put("follow", "持续跟随主人，直到被替换或取消；只应作为计划的末阶段。");
        METHODS.put("wait", "停留等待；只应作为计划的末阶段。");
        METHODS.put("guard", "持续守卫主人下请求的位置；只应作为计划的末阶段。");
        METHODS.put("go_to", "移动到 place=owner（收到请求时主人位置）、home（另存的家点，不是当前建筑的出口）或附近可搜索的 water。一次移动包含离开当前建筑并到达目的地；寻路负责跳、挖路、搭桥、权限提问和恢复，无需额外的“出门”阶段。");
        METHODS.put("harvest", "搜索并采集天然原木；material=log，amount 为正整数，deliver_to_owner 指是否交给主人。Jev 选择真实起始目标；批量采集匹配所有天然原木，尚不支持精确树种筛选。");
        METHODS.put("mine", "搜索并挖掘 material=ground（表层泥土）或 stone（石头／圆石）；amount 为正整数，deliver_to_owner 指是否交给主人。");
        METHODS.put("attack", "攻击已观察到的指定非玩家生物。把目标描述写在 purpose；实体标识由世界观察提供，服务器拒绝攻击玩家。");
        METHODS.put("equip", "穿上背包中的铁胸甲。");
        METHODS.put("eat", "吃背包中的面包，恢复六点生命。");
        METHODS.put("build", "在请求位置东侧搭一个 3×3 橡木平台，需要九块橡木木板，不覆盖已有方块。尚无自由建筑或合成工具。");
    }
    public static Set<String> names() { return Set.copyOf(METHODS.keySet()); }
    public static JsonObject json() {
        JsonObject root = new JsonObject();
        JsonArray methods = new JsonArray();
        METHODS.forEach((name, description) -> {
            JsonObject method = new JsonObject();
            method.addProperty("name", name);
            method.addProperty("description", description);
            JsonObject parameters = new JsonObject();
            if (name.equals("mine") || name.equals("harvest")) {
                parameters.addProperty("material", name.equals("mine") ? "ground | stone" : "log");
                parameters.addProperty("amount", "integer 1..256; omitted defaults to " + (name.equals("mine") ? 1 : 4));
                parameters.addProperty("deliver_to_owner", "boolean; deliver only items collected by this stage");
            }
            if (name.equals("go_to")) parameters.addProperty("place", "owner | home | water");
            parameters.addProperty("purpose", "describe the intended object, result and ordering constraints");
            method.add("parameters", parameters);
            method.addProperty("preconditions", "relevant inventory, reachable loaded terrain, current world permissions; checked again by execution");
            method.addProperty("failure", "missing resources, unreachable target or permission needed returns evidence to Jev; no automatic language planning");
            methods.add(method);
        });
        root.add("methods", methods);
        root.addProperty("runtime_methods", "observe nearby/wider loaded environment; choose real target; deliver stage items; continue current work; heal/equip without replacing work; switch unfinished stage when dependencies permit; report inability; finish only after verification. These are generated from world state, not free-form coordinates.");
        root.addProperty("execution", "Jev chooses actual observed targets, tool order, recovery and completion. Plans describe objectives, not completed actions.");
        root.addProperty("max_stage_amount", 256);
        root.addProperty("max_plan_stages", 8);
        return root;
    }
    /** Adoption checks need the semantics of the proposed methods, not every unrelated capability. */
    public static JsonObject forPlan(GoalPlan plan) {
        JsonObject result = new JsonObject();
        plan.stages().forEach(stage -> result.addProperty(stage.method().verb(), METHODS.get(stage.method().verb())));
        return result;
    }
    private ToolCatalog() {}
}

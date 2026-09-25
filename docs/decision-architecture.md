# NPC 决策架构

更新：2026-09-24。这是当前代码的结构说明，方案边界见 [design.md](design.md)，交付状态见 [development-progress.md](development-progress.md)。

**模型只做有限选择，游戏代码拥有世界和执行。** 一次判断可以驱动几十到几百个 tick 的寻路、采集或战斗。危险不等云端，由本地技能立刻处理。

```mermaid
flowchart TD
    subgraph triggers [什么会触发一次判断]
        Chat["主人附近的 /jev ask 或 @小杰"]
        Events["受击、天气切换、赠送、出生、配置重载"]
        Idle["无事件时约 30 秒一次周期检查"]
        ToolDone["工具完成、失败或被打断"]
    end

    Chat --> NewTask["新建 AgentTask，替换旧目标"]
    Events --> Inbox["EventInbox：同类事件合并，默认等 8 tick"]
    Idle --> Inbox
    ToolDone --> Inbox
    NewTask --> Inbox

    Inbox --> Gate{"NpcBrain 调度门"}
    Gate -->|"手动控制 / 技能还在跑 / 已有在途请求 / 冷却中 / 无 key"| Keep["继续本地技能，不访问模型"]
    Gate -->|"主人在线、同维度、48 格内，且预算允许"| Mode{"当前有持久目标?"}

    Mode -->|"没有"| Auto["代码列出自主候选：跟随、守卫、回家、进食、迎敌或撤退"]
    Mode -->|"有目标，意图还没解释"| Intent["第一次 Jev：解释 verb / 材料 / 地点 / 数量 / 是否交付"]
    Mode -->|"意图已经写下"| Tools["EnvironmentTools 按意图列出下一步工具"]

    Auto --> Http["异步 POST api.typesafe.ai<br/>状态是 JSON，不带世界对象"]
    Intent --> Http
    Tools --> Http

    Http --> Back["回到服务器线程，DecisionGate 核对请求版本和年龄"]
    Back -->|"过期、主人离开、置信度低于 0.35、或正在紧急避险"| Discard["丢弃；目标任务记一次失败并稍后重试"]
    Back -->|"解释结果"| SaveIntent["写成 GoalIntent，立刻再排一次选工具"]
    Back -->|"选中的候选 ID"| Plan["用 ID 取回代码早就绑定好的 ActionPlan"]

    Plan --> Observe["观察：先 8 格，再 16 格；坐标和 UUID 只来自扫描"]
    Plan --> Run["SkillRunner：寻路、采集、挖掘、攻击、建造、交付"]
    Plan --> End["代码验证数量和交付后才允许结束，或报告做不到"]
    Observe --> Evidence["观察结果、进度、失败目标写回 AgentTask"]
    Run --> Evidence
    Evidence --> Inbox

    Hurt["血量 ≤ 8、着火或岩浆"] --> Flee["本地撤退约 4 秒，原工作暂停一级"]
    Flee --> Run
```

## 两条决策路径

主人说话会立刻建一个 `AgentTask`。它保住原话、类型化意图、轮数、采集进度、本任务入包账本和最近 12 条工具结果。之后的循环是两段模型调用。

1. **解释意图。** `task.intent` 还是空时，`JevClient` 一次问完六个问题：动作（`verb`）、挖什么、去哪里、数量是否说死、具体是 1 还是 4、要不要交给主人。只有被采用的分支才计入置信度。没说数量时，砍树默认 4 块、挖地默认 1 块。交付概率至少 0.4 就视为要交付，所以「帮我搞点木头」也会送回去。解释完只把 `GoalIntent` 存下来，不执行任何动作。
2. **选下一步工具。** 意图写好之后，`EnvironmentTools` 按这个意图生成候选。采集、攻击、去水边需要目标时，先给 `observe_nearby` / `observe_wider`，观察后再把真实方块或实体变成带坐标、UUID 的 `ActionPlan`。不需要搜索的动作（跟随、等待、守卫、装备、建造平台）直接给出唯一的已绑定计划。数量和交付都由代码核对通过后，候选里才会出现 `finish_goal`。

没有主人目标时走另一条自主目录：继续当前工作、等待、跟随、守卫、回家、进食、穿甲；附近有敌对生物才加上攻击和撤退。改世界的砍树、挖矿、搭平台只挂在主人目标上，不会在闲逛时自己提议。

Jev 的返回值只是候选 ID，或那一组意图字段。应用层用 ID 找回原来的 `ActionPlan`。模型填不了坐标，也拼不出「去攻击一个没扫到的实体」这种组合。

## 调度约束

这些门都在 `NpcBrain.tick` 里。除 HTTP 完成回调外，决策相关逻辑都在服务器线程上。

| 约束 | 默认值 | 作用 |
| --- | --- | --- |
| 事件合并 | 8 tick | 同一窗口里的受击、天气等合成一次请求 |
| 决策冷却 | 40 tick | 每个 NPC 两次请求的最小间隔 |
| 单在途 | `DecisionGate` 一张票 | 新指令把 revision 加一，晚到的旧响应直接丢弃 |
| 结果年龄 | 4 秒 | 超时响应不能覆盖当前世界 |
| 全服预算 | 60 次/分钟 | 超出后本地等待，tick 不阻塞 |
| 置信度 | 0.35 | 低于此保持当前行为；解释阶段不确定就结束目标并请主人补充 |
| 目标预算 | 16 轮或 3600 tick，连续 4 次无进展 | 到顶就结束，并告诉主人 |

技能还在执行，或者本地紧急锁还没解除时，不会发新的周期决策。工具结束后才根据 `tool_results` 和 `environment` 决定下一步。攻击和撤退可以暂停手头工作，只保留一级，之后用 `resume_task` 恢复。`/jev do`、`/jev move`、`/jev stop` 进入手动控制并停掉模型；`/jev ask` 或 `/jev think` 再恢复。

存档记下主人、性格、家、背包、当前目标和那一级暂停任务。在途的 HTTP 请求不存，读档后重新观察环境。

## 代码落点

| 代码 | 职责 |
| --- | --- |
| `ai/NpcBrain` | 事件调度、目标生命周期、组状态、应用判断结果 |
| `ai/AgentTask`、`ai/GoalIntent` | 持久目标、进度、交付账本、有限历史 |
| `ai/EnvironmentTools` | 有界观察，把真实方块和实体绑成候选 |
| `ai/JevClient` | 官网请求；意图解释与下一步选择分两种问题 |
| `ai/DecisionGate`、`EventInbox`、`RequestBudget` | 旧结果隔离、事件合并、全服调用预算 |
| `behavior/ActionPlan`、`Skill`、`SkillRunner` | 已绑定参数的动作，以及寻路、工作和本地避险 |

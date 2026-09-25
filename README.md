# Jev NPC Demo · Fabric 1.21.1

一个使用 TypeSafe 官网 Jev 决策的事件驱动 NPC 模组。Java 21、Minecraft **1.21.1**、Fabric Loader **0.19.5**、Fabric API **0.116.17+1.21.1**。客户端和服务器都需要安装本模组；单人游戏使用集成服务器。

方案：[docs/design.md](docs/design.md)。当前决策结构：[docs/decision-architecture.md](docs/decision-architecture.md)。原始调研：[docs/research.md](docs/research.md)。验证和限制：[docs/development-progress.md](docs/development-progress.md)。

**开发环境启动。** 本机已有 Homebrew Java 21，脚本会自动选择它：

```bash
cd /Users/nakami/Documents/code/mc_jv_npc
./scripts/dev.sh runClient
```

创建一个允许作弊的创造模式测试世界。世界中输入 `/jev spawn`，会在玩家东侧两格生成名为“小杰”的人形 NPC；那个位置需要留空。NPC 首次生成时自带铁剑、斧、镐、铁胸甲、32 块橡木木板和 8 块面包，方便验证行为。

**配置分成两份文件。** 行为参数在 `jev-npc.json`，可以提交和复用。API key 只放在同目录的 `jev-npc.secret.json`，这个文件名被 `.gitignore` 排除。仓库里的可提交默认配置是 `config/jev-npc.json`；密钥示例是 `config/jev-npc.secret.example.json`，其中 `apiKey` 为空。

开发时游戏读取的是另一份实例配置：`run/config/jev-npc.json` 和 `run/config/jev-npc.secret.json`。首次缺少文件时，模组会按仓库默认值创建它们，并尽量把密钥文件权限设为仅本人可读写。若要把仓库里改过的行为参数用于当前开发实例，只复制 `jev-npc.json`，不要覆盖已有的 `jev-npc.secret.json`。

**填写 API key。** 编辑 `run/config/jev-npc.secret.json`，保留 JSON 引号：

```json
{
  "apiKey": "你的官网 API key"
}
```

不要把 key 提交到仓库或发到聊天。模型默认固定 `jev-1.13.0`；端点固定为 `https://api.typesafe.ai/v1/systemone`。也可以使用环境变量 `TYPESAFE_API_KEY`，它优先于密钥文件。修改后游戏内输入 `/jev reload`，输出只显示 key 是否已配置。

旧版把 `apiKey` 写在 `jev-npc.json` 里。下次加载时，模组会把其中的 key 移入 `jev-npc.secret.json`（该文件已有非空 key 时保留原密钥），并从行为配置中删除 `apiKey`。

用普通启动器安装 jar 时，两份文件都在该游戏实例的 `config/` 下。专用服务器则读取服务器工作目录的 `config/`。它们与仓库里的 `config/jev-npc.json` 是不同文件。

**先测试本地技能，无需 key。**

```text
/jev spawn diligent
/jev do follow
/jev do wait
/jev do guard
/jev do harvest
/jev do mine
/jev do build
/jev do equip
/jev do talk
/jev inventory
/jev status
```

砍树需要附近有带树叶的原木；挖掘只支持裸露石头／圆石；建造是在玩家执行指令位置东侧 3 格起，搭一个 3×3 橡木平台。请留出空地。工作需要已有工具或材料；右键 NPC 可赠送手中一件物品，空手右键显示状态。

`/jev do` 是直接执行技能，会暂停 Jev，便于单独验证执行器。它不代表模型理解了自然语言。`/jev stop` 立即取消工作并暂停模型，`/jev ask` 或 `/jev think` 恢复。

**配置好 key 后验证模型决策。**

```text
/jev reload
/jev ask 跟着我，别离我太远
/jev ask 在这里守卫，看到怪物就保护我
/jev ask 帮我收集附近的木头
/jev ask 去水里
/jev ask 挖一下地面
/jev ask 去打那个铁傀儡
/jev ask 在旁边搭一个橡木平台
/jev ask 穿上铁胸甲
/jev ask 介绍一下你自己
```

也可以在聊天框发送 `@小杰 跟着我` 或 `@jev 跟着我`。首版仅处理附近主人的定向聊天；有多个属于自己的 NPC 时选择最近的一个。模型判断、轮次、工具结果、耗时、输入 token 和置信度只记录在服务器日志中；聊天显示任务开始、进度结果与必要的失败提示。`NO_KEY`、`HTTP_401`、`HTTP_429`、`NETWORK_OR_TIMEOUT` 等状态会明确显示；请求失败不会伪装成模型已成功决策。

**多轮目标执行。** 每条新指令先由 Jev 解释为类型化目标，随后按需选择观察环境、扩大搜索、执行已发现的目标、交付或结束。工具结果会进入下一轮上下文；执行中的工作不会被定时检查替换。新指令立即替换旧目标。默认每个目标最多 16 次模型调用（`maxGoalRounds`）、3600 个活动 tick（`maxGoalTicks`），连续四次无进展失败后停止。采集只交付本次任务入包的物品，不交出初始装备和物资。木头即使被树叶包住也会进入观察结果；采集可清理距目标树干四格内、真正挡住视线的天然树叶（每次采集步骤最多 24 片），不会为此挖石头或破坏玩家放置的树叶。

聊天使用预定义台词：Jev 决定是否打招呼、求助或说明能力，Demo 不生成自由文本，也未接入其他大语言模型。

| 指令 | 用途 |
| --- | --- |
| `/jev spawn [cautious\|brave\|diligent]` | 生成谨慎／勇敢／勤勉伙伴 |
| `/jev ask <自然语言>` | 唤醒 Jev，按当前场景选择行为 |
| `/jev do <技能>` | 手动执行本地技能，Tab 查看候选 |
| `/jev move <x y z>` | 移动到 32 格内已加载的位置 |
| `/jev stop`、`/jev think` | 停止并暂停／唤醒模型决策 |
| `/jev status`、`/jev inventory` | 查看执行状态、Jev 状态和背包 |
| `/jev personality <性格>` | 更换性格 |
| `/jev sethome` | 将玩家当前位置设为家 |
| `/jev do home`、`/jev do resume` | 返回家／恢复被危险打断的工作 |
| `/jev reload` | 重新加载配置，不显示 key |
| `/jev remove` | 移除最近的自有 Demo NPC（背包随之删除） |

**构建与测试。**

```bash
./scripts/dev.sh test build
./scripts/dev.sh runGameTest
```

发布 jar 位于 `build/libs/jev-npc-0.1.0.jar`；不要安装 `-sources.jar`。在普通启动器的 Fabric 1.21.1 实例中，将发布 jar 和对应 Fabric API jar 放入 `mods/`。Gradle wrapper 在其他平台可直接使用，但运行 Gradle 的 JDK 也必须是 21。

JUnit 使用本地 HTTP 测试服务器，检查真实请求格式、超时、错误处理、候选校验和事件调度，不会使用你的 key。GameTest 关闭 Jev，验证 Minecraft 世界中的动作与存档行为。两者都不能替代官网模型效果评估。

**重要的 Demo 边界。** 只支持普通地面寻路，不会挖穿地形寻找路线，不会任意设计建筑。自然语言支持明确的一块或四块；未明确数量时采木默认四块、挖掘默认一块，挖掘支持表层泥土类方块及石头／圆石；任意数量、任意方块和装备选择尚未实现。浅水搜索限已加载区域，不会探索远处未知区块。手动修改原始世界应在专用测试存档进行；第三方领地保护兼容、长期生存表现和中文决策准确率待后续验证。NPC 未配置自然生成规则，只能通过指令或实体召唤生成。

配置中 `allowBlockChanges=false` 可关闭砍树／挖掘／建造。`debugToOwner` 是兼容旧配置的字段，决策诊断现在始终仅写日志。默认请求超时 2.5 秒、同一 NPC 决策冷却 2 秒、事件合并 0.4 秒、低频检查 30 秒、全服务器每分钟最多 60 次请求。Jev 的 Choice 置信度来自候选概率分布，并不是动作正确性的保证。

**真实客户端与官网 API 验收。** 配置好 key 后运行 `./scripts/dev.sh runClientValidation`。该任务会调用官网模型，在 `build/client-validation/` 下建立新世界，检查密集树冠中的中文采集并交付、进入浅水，以及带旧失败记忆的“挖地面”，保存 `result.txt` 和四张游戏截图后关闭客户端。密钥优先取 `run/config/jev-npc.secret.json`，不存在时取仓库 `config/` 下的密钥；环境变量仍具有最高优先级。验收模组独立于发布 JAR。结果与本机 Gradle 下载问题的复跑方法见 [客户端验收记录](docs/client-validation.md)。

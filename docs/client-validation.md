# Jev 主导 Harness 验收 · 2026-09-25 16:30

**PASS。** 方案先提交为 `1617cdf`，实现位于 `codex/jev-led-harness`。执行 `./scripts/dev.sh test build runGameTest runClientValidation --console=plain`，70 项 JUnit、31 项 GameTest、发布构建和隔离真实客户端全部通过；本轮耗时 2 分 18 秒。模型为官网 `jev-1.13.0` 与 `deepseek-flash`，没有模拟客户端验收中的模型结果。

| 场景 | 可失败断言与实际结果 |
| --- | --- |
| 闲聊 | Jev ROUTE → DeepSeek → Jev REVIEW → 回复；没有创建目标或动作 |
| 持续跟随时询问天气 | 对话期间 FOLLOW 始终存在，回复后仍在执行；对话不会停身体动作 |
| 三格缺口，背包无垫脚方块 | 经 Jev 采用移动计划；本地挖五块泥土、搭三格桥，到达对岸；执行反馈由 Jev 处理 |
| 木板房内要求走到主人处 | Jev 采用提案并选移动；未授权前墙体完整；权限问题经 Jev 决定委派 DeepSeek 表达，再经 Jev 发送 |
| 追问“为什么要挖墙？” | 回复解释挡路方块；原目标 ID 和问题对象相同，未授权，墙体未破坏 |
| 关闭 Jev 后继续提问 | 等待 240 tick；DeepSeek 调用计数不增加，目标和待答问题保留，有失败提示；没有绕过 Jev |
| 恢复 Jev，普通聊天“可以，挖吧” | Jev 直接选择同意，授予本目标 break_built；实际挖掉两块木板并到达主人处 |
| “挖十二块圆石交给我，然后回家，受伤先吃面包” | DeepSeek 提出两阶段计划，Jev 采用。挖矿中注入六点伤害，Jev 选择 aux_eat，恰好消耗一块面包并恢复满血；实际交付十二块圆石，再返回家点；两个阶段都具备完成证据。共八轮行动判断，**执行期间新增 DeepSeek 调用为零** |
| 空闲时缺垫脚方块 | Jev 选择 stock_blocks；191 tick 后已有四块，完整八块采集继续本地运行 |
| 黄昏事件 | Jev 选择 drop_message，不调用 DeepSeek，不发言。验收检查事件确实由 Jev 处理，允许自主选择沉默，没有强制所有提醒都必须发言 |

**调用与时间。** 最终运行记录 37 次成功 Jev 判断，观测中位数 566 ms、范围 477–1593 ms；11 次 DeepSeek 调用，其中六次理解玩家、五次组织发言，中位数 1519 ms、范围 1137–2929 ms。这些时间包含 HTTP 往返；不代表模型本身推理耗时，也不是大样本 P50/P95 基准。交流需要路由、语言生成和审查，不能据此宣称交流是毫秒级；动作执行无需等待生成式模型。

**调试中实际遇到的失败。** 早期完整运行暴露了低置信度丢弃新请求、提案只有口头承诺没有计划、旧任务状态干扰新提案、把 home 误作建筑出口，以及把措辞偏好分散误判为不能发言。分别调整了窄问题、一次有界理解修正、审查上下文、工具契约和按后果区分的置信度策略。另一次真实正反样本检查中，错误目的地曾被选为采用但置信度仅 0.12，会被门槛拦截；这说明 Jev 判断本身仍可能出错，不能将一次通过解释为普遍可靠。

**离线覆盖。** 单测验证语言调用需 Jev 选择、结果需再次采用、重复消费／过期回复失效、发言模式无法改计划或授权、理解修正最多一次、数量与方法参数校验、多阶段进度和交付证据存档，以及 HTTP 错误与超时。GameTest 验证真实技能／寻路／交付和权限、聊天不直接替换动作、无 Jev 时目标暂停且不自主开工。GameTest 与单测不使用付费 API。

**画面。** 已查看本轮 stage-3、stage-7、stage-4：stage-3 的完整木板墙与自然语言授权问题同屏；stage-7 显示 NPC、已打开的木板房、交付／抵达回执以及玩家物品栏的十二块圆石；stage-4 显示黄昏和没有新增台词的场景，NPC 靠近镜头而部分裁切，行为依据仍是服务器断言与 Jev 日志。夹具等待必要消息收到后再等待渲染帧，避免截到消息到达前的画面。

证据：`build/jev-led-final.log`、`build/client-validation/result.txt`、`build/client-validation/logs/latest.log`、`build/client-validation/screenshots/stage-1.png` 至 `stage-7.png`。发布 JAR 为 `build/libs/jev-npc-0.1.0.jar`，已检查不包含测试入口或密钥。测试客户端已自动退出。重跑会清除旧结果和七张旧截图；缺文件、断言失败、超时或崩溃都会使 Gradle 任务失败。

**边界。** 方法集合和世界候选覆盖仍决定能力；没有任意建筑、合成或无限开放规划。多人并发、长期生存、全部中文表达和网络压力尚未验收。旧消息／取消和阶段切换的协议边界有离线测试，不等于已做大规模真实并发验证。方案与后续范围见 [Harness 方案](deepseek-harness-proposal.md)。

以下保留历史版本记录，旧证据路径可能已被新运行覆盖。

---

# DeepSeek 对话收尾验收 · 2026-09-25 13:26

结论：PASS。真实 `deepseek-flash` 与官网 Jev 在隔离客户端完成对话、任务和授权流程；新增单测与 GameTest 均通过。此前提交 `ac06a0a` 已完成四段场景，本轮补足交接中提出的上下文和无 Jev 路径缺口。

| 输入／场景 | 可失败断言与实际结果 |
| --- | --- |
| 普通聊天 `@小杰 你好，今天心情怎么样？` | 客户端正常聊天管线发送，`ClientReceiveMessageEvents` 收到自然语言“挺好的，刚闲着呢。有啥要我干的活吗？”；收到回复和截图等待期间没有目标或技能启动 |
| “走到我现在站的位置” | DeepSeek 返回 go_to/owner，任务文本采用复述“走到主人现在的位置”；日志中的 owner message 已有 GoalIntent，省去 Jev 解释轮。随后 Jev 选工具、先挖五块泥土、搭三格桥、到达对岸；两轮完成 |
| 木板房内要求走到主人身边，然后追问“为什么要挖墙？” | Jev 分类后的非答复进入 DeepSeek，回复解释直线路线穿过两个方块；原问题对象和目标 ID 不变、未获授权、墙体完整 |
| 暂时关闭 Jev，继续问“挖墙会弄坏房子吗？” | DeepSeek 仍能结合问题和刚才发言解释会留下洞；原问题与目标仍保留，未授权或破坏方块 |
| 恢复 Jev，普通聊天“可以，挖吧” | 关键词授权 break_built；挖掉两块木板，从房子走到主人身边并完成，木板剩 32 块 |
| 空闲且没有垫脚方块 | 真实 Jev 选择 stock_blocks，200 tick 内采到四块；拨到黄昏后客户端收到天黑提醒 |

五次 DeepSeek 调用均返回可解析 JSON，延迟分别为 1709、1469、1207、1160、1274 ms。本轮沿用生产客户端的 JSON Output 和 `thinking.type=disabled` 请求，完成了实际密钥、模型名和接口冒烟验证。这是少量实际样本，不是准确率或延迟分布统计。

**代码回归覆盖。** 66 项 JUnit 全通过：新增任务复述优先、任务／闲聊 JSON 历史、实际发言缓冲及去重检查。31 项 GameTest 全通过：新增无模型时已知意图采集并交付、请求攻击铁傀儡时拒绝猜测且附近村民和铁傀儡均不受伤。有／无 Jev 的追问路由及问题保留由本轮真实客户端断言覆盖。发布 JAR 不包含 ClientValidation 或 NpcGameTests。

**画面检查。** 已查看 stage-2、stage-4、stage-5、stage-6：泥土桥连接两岛且 NPC 已过桥；黄昏能看到胸甲、挖出的门洞与天黑提醒；闲聊文本与 NPC 同屏；两轮追问及解释在聊天 HUD 中清晰可读，墙体尚未破坏。stage-1、stage-3 也由运行任务生成并检查文件存在。

第一轮扩展验收因措辞断言过窄失败：回复用“屋里”“方块”解释路线，断言只接受“墙／木板／房”。补充同义表达后重跑通过；目标、问题、授权和墙体完整性断言始终保留。

证据（由下次运行覆盖）：`build/client-validation/result.txt`、`build/client-validation/logs/latest.log`、`build/handoff-client-validation.log`、`build/handoff-verification.log`，以及 `build/client-validation/screenshots/stage-1.png` 至 `stage-6.png`。测试客户端已自动退出。

```bash
./scripts/dev.sh test build runGameTest --console=plain
./scripts/dev.sh runClientValidation --console=plain
```

每阶段最多 2600 服务端 tick、全局最多 480 秒；Gradle 删除旧结果和六张旧截图，缺文件、FAIL 或超时均导致任务失败。密钥保持在忽略文件中，未写入文档或提交。

**未覆盖范围。** 多人并发对话、长时间生存、所有中文表达和延迟响应竞态未做真实客户端压力验收；其他主动提醒仍沿用此前的覆盖边界。对话记忆和最近发言仅保留在内存中，不跨存档恢复。

以下保留历史记录，旧证据路径已由本轮运行覆盖。

---

# 分层 Agent 首轮客户端验收 · 2026-09-25 12:48

最新隔离客户端验收为 PASS，全程使用真实官网 Jev 和真实 DeepSeek（`deepseek-flash`），没有模拟模型结果。场景改为本次改动的触发路径；上一轮的砍树、入水、挖地面场景由 GameTest 继续覆盖。

| 场景 | 实际过程与断言 |
| --- | --- |
| 悬空石岛，背包没有垫脚方块，主人在 3 格缺口对面说“走到我现在站的位置” | DeepSeek 回复“好，我这就过去你那边。”并给出 go_to/owner 意图（跳过 Jev 解释）；Jev 选前往（2 轮）；寻路报告缺 3 块 → 恢复规则挖掉岛上 5 块泥土 → 搭 3 格泥土桥 → 到达对岸并完成。断言桥上有 3 块泥土、岛上泥土被挖、NPC 在对岸 |
| NPC 关在木板房里，主人说“从房子里出来，走到我这儿” | DeepSeek 给出 go_to；寻路判定只有挖穿木板才能出去，NPC 在聊天框问“路线要挖穿约 2 个可能是别人放置的方块。可以挖穿吗？”；回答前没有破坏任何方块。客户端通过真实聊天发送不带 @ 的“可以，挖吧” → 关键词判为同意 → 本目标获得 break_built 授权 → 挖开 2 块木板走到主人身边并完成 |
| 空闲、无垫脚方块的勤快型 NPC | Jev 在自主候选中选择 stock_blocks（置信度 0.89，需求紧迫度 1.05），约 200 tick 内挖到 4 块泥土 |
| 把时间拨到 12100 | 客户端聊天收到“天快黑了，怪物要出来了。” |

已查看四张实际截图：stage-1 为两座石岛和缺口；stage-2 为 3 格泥土桥、岛上泥土已挖空、NPC 在对岸；stage-3 为木板房和聊天栏里的提问；stage-4 为黄昏、NPC 穿铁胸甲在草地挖坑、木板房被挖出门洞、聊天栏显示开始挖掘和天黑提醒。

验收过程中发现并修复的问题：

- 配置 DeepSeek 后 `/jev ask` 变为异步，夹具改为先等目标出现再判断完成。
- 服务器停止时 `server.execute` 会在 HTTP 线程直接执行回调；现在所有模型回调都先确认在服务器线程。
- 关闭自主时 Jev 仍能看到 stock_blocks 候选，并在木板房里反复失败。现在关闭自主就不提供自主候选；自主行动失败后冷却 60 秒；采集时连续 3 个目标走不过去就整体放弃。
- 同一句指令第二次下达时，Jev 受“不要重复已完成请求”约束影响，置信度降到 0.01–0.11；DeepSeek 也曾凭对话记忆只反问不行动。现在约束说明当前 `task` 是刚发出的新请求，系统提示要求能做的指令照做、只在指令含糊时反问。
- 权限失败时先发一条汇报再提问，内容重复；现在只发问题。

证据：`build/client-validation/result.txt`、`build/client-validation/logs/latest.log`、`build/layered-client-validation.log`、`build/client-validation/screenshots/stage-1.png` 至 `stage-4.png`。

```bash
./scripts/dev.sh test build
./scripts/dev.sh runClientValidation
```

62 项 JUnit、29 项 GameTest、构建通过；连续 5 轮 GameTest 无随机失败。发布 JAR 不包含验收或 GameTest 入口。验收会发生真实 Jev 与 DeepSeek 调用；两个 key 都不写入报告和日志。每次运行的模型回复与轮数可能不同，这里是单次运行记录，不是准确率统计。

以下为历史记录，证据路径由最新验收覆盖。

---

# 树冠遮挡与缺省数量回归 · 2026-09-23

最新隔离客户端验收为 PASS，测试使用真实官网 Jev。四格云杉树干的所有侧面均被低垂树叶包住，复现用户在树林中被告知没有木头的问题。原实现的发现、采集和高树干地面站位测试均先失败，修复后通过。

| 场景 | 最新结果 |
| --- | --- |
| 帮我搞点木头可以吗 | 5 轮；观察到四个被树叶遮挡的树干，清除局部挡路的天然树叶，采集并交付四块云杉原木；保留初始物资 |
| 去水里 | 4 轮；进入目标水格中央；检查实际入水与客户端画面 |
| 挖地面 | 5 轮；使用 cautious 性格和报告中的七条旧失败／请求记忆；正确理解 mine/ground，未指定数量采用一块，成功挖出泥土并交付 |

挖地面的原始复现：verb=mine、material=ground，二者置信度均 1.0；amount 却返回 unsupported、置信度约 0.22–0.29，拖低整个判断并重复失败。新增 amount_explicit 后，最终实测为 0.04，数量分支被忽略，整体采用的置信度为 0.98。明确指定的数量仍检查数量分支；不是全局降低置信度门限。

测试命令：

```bash
./scripts/dev.sh test build
./scripts/dev.sh runClientValidation
```

27 项 JUnit、17 项 GameTest 通过。新增覆盖包括叶遮树干仍可发现、清叶后采集、四格高树干地面站位、保留玩家放置的树叶、区分权限拒绝与没有木头，以及缺省数量不能否定已明确的挖掘意图。

当前证据：`build/client-validation/result.txt`、`build/client-validation/logs/latest.log`、`build/forest-client-validation.log`。截图 stage-1 是完整树冠，stage-2 是清除局部遮挡并交付后，stage-3 为入水，stage-4 为挖掘结束。已查看 stage-1 和 stage-2：树冠仅局部被清理，NPC 已返回主人旁边；背包数量另由服务端和客户端断言验证。

叶子清理限制：实际射线命中、可触及、权限允许、距目标树干四格内、每步骤最多 24 片天然树叶。不会自动挖石头或清理玩家放置的 persistent 树叶。复杂地形仍可能失败，但有目标却无法接近时不再表述为没有目标。

以下为历史记录，证据路径由最新验收覆盖。

---

# 首次多轮 Agent Loop 客户端验收 · 2026-09-23

结论：真实官网 Jev → 观察工具 → 下一轮决策 → 实际执行 → 结果反馈的循环通过。使用隔离客户端、新建固定种子平坦世界和真实 key；没有模拟模型结果。测试完成后客户端自动退出，未修改用户开发世界。

| 自然语言输入 | 实际循环与断言 |
| --- | --- |
| 帮我搞点木头可以吗 | 6 轮模型调用；解释采集四块并交付 → 观察 → 首次采集只有两块，保留目标 → 换目标补足两块 → 走到主人旁边交付 → 验证结束。主人获得四块原木，NPC 原木为零；初始 32 木板和 8 面包保留 |
| 去水里 | 4 轮；解释 → 观察浅水位置 → 前往真实候选 → 结束；服务端及客户端均检查 NPC 实际在水里 |
| 挖一下地面 | 5 轮；解释为表层地面一块 → 观察 → 挖掘 → 交付 → 结束；泥土总数增加一块 |

这些轮数是本次实际运行记录，不保证模型每次选择相同路径。只测试有限样本；攻击铁傀儡尚未做官网自然语言端到端验证。

测试修复了采集后仍提供旧目标的问题，以及靠近树干时被树叶遮住视线的问题。执行器会寻找能看到目标且可寻路到达的站位；不可接近的方块跳过，剩余数量和失败反馈进入下一轮。独立 GameTest 还验证完全被遮挡的原木不会让整个采集提前中止。

## 当前证据与复跑

- `build/client-validation/result.txt`：最新 PASS、三条任务的轮次和工具结果。
- `build/client-validation/logs/latest.log`：真实模型与工具调用日志，诊断不发到聊天。
- `build/client-validation/screenshots/stage-1.png`：测试地形，包含遮挡树干及浅水。
- `build/client-validation/screenshots/stage-2.png`：采集后 NPC 返回主人身边；物品转移另有客户端背包断言。
- `build/client-validation/screenshots/stage-3.png`：NPC 站在水里。已查看本帧和 stage-2 的真实渲染画面。
- `build/client-validation/screenshots/stage-4.png`：挖地面后的场景。
- JUnit 26 项通过；GameTest 12 项通过；构建通过。
- 发布 JAR 不包含客户端验收／GameTest 入口。

```bash
./scripts/dev.sh test build
./scripts/dev.sh runClientValidation
```

每阶段最多 2600 服务端 tick，全局最多 300 秒；每次删除旧结果和四张旧截图，缺失或失败使 Gradle 返回失败。场景使用独立 `build/client-validation/` 运行目录；会发生真实 API 调用。密钥不会出现在报告中。

以下保留历史记录；上列当前证据路径会被最新测试覆盖。

---

# 历史验收 · 2026-09-22

结论：Demo 的官网 Jev → 游戏行为 → 客户端显示核心流程可用。使用实际配置的 key 和默认 `jev-1.13.0`，没有模拟模型响应，也没有修改生产代码或放宽请求超时。

## 覆盖与断言

独立测试模组 `src/clientValidation` 自动创建固定种子 42042 的创造模式平坦世界，固定白天、晴天，关闭生物生成。由服务端线程执行真实 `/jev spawn`、`/jev ask` 命令，客户端线程检查实体、装备和方块同步并捕获渲染缓冲区。

| 输入 | 可失败的检查 |
| --- | --- |
| 跟着我，别离我太远 | 选择 follow_owner，距离主人小于 3.5 格且实际接近超过 3 格 |
| 停下来，原地等待 | 选择 wait_here，执行器进入 WAIT |
| 穿上背包里的铁胸甲 | 选择 equip_armor，服务端和客户端胸甲槽均为铁胸甲 |
| 在旁边搭一个3×3橡木平台 | 实际完成建造，服务端和客户端均有指定位置的 9 块橡木木板，背包木板从 32 减至 23 |

初次完整验收四次官网响应分别为 853、366、319、296 ms，置信度为 0.93、0.94、0.97、0.80。这是少量样本，不代表延迟或中文理解准确率的统计保证。

每个阶段最多等待 700 服务端 tick，客户端总超时 240 秒。每轮删除旧结果和四张旧截图，缺失结果、断言失败、超时或截图缺失均使 Gradle 任务失败。每轮创建独立新世界，结束后关闭测试客户端。

## 证据

- `build/client-validation/result.txt`：最近一次整体断言结果与模型调用记录。
- `build/client-validation/logs/latest.log`：客户端和集成服务器日志。
- `build/client-validation/screenshots/stage-1.png`：初始 NPC。
- `build/client-validation/screenshots/stage-2.png`：跟随靠近后的 NPC。
- `build/client-validation/screenshots/stage-3.png`：已装备铁胸甲。
- `build/client-validation/screenshots/stage-4.png`：完成的橡木平台与 NPC。
- 已查看真实截图，人物、姓名、手持物品、铁胸甲和平台可见。初次截图中聊天 HUD 会遮挡部分下方画面，最终夹具在装备与建造截图阶段隐藏 HUD，并固定建造截图镜头朝向。
- JUnit 19/19 通过：调度 6 项、HTTP 8 项、配置 5 项。
- Minecraft GameTest 6/6 通过：移动、采集、挖掘、建造、阻挡保护、装备/进食/持久化。
- 发布产物 `build/libs/jev-npc-0.1.0.jar`，不包含验收或 GameTest 入口。

## 复跑

正常环境：

```bash
./scripts/dev.sh test build runGameTest
./scripts/dev.sh runClientValidation
```

本机下载 Gradle wrapper 9.4.1 遇到 TLS 握手错误，此次使用已安装的 Gradle 9.5.1，未修改 wrapper 或项目锁定版本：

```bash
/Users/nakami/.gradle/wrapper/dists/gradle-9.5.1-bin/iq79hdu3mqx29lgffhp8bfmx/gradle-9.5.1/bin/gradle test build runGameTest --console=plain
/Users/nakami/.gradle/wrapper/dists/gradle-9.5.1-bin/iq79hdu3mqx29lgffhp8bfmx/gradle-9.5.1/bin/gradle runClientValidation --console=plain
```

验收会发生真实 API 调用。测试配置复制到独立目录；密钥内容不写入报告、日志或版本控制。用户存档与普通开发实例保持不变。

未覆盖：官网模型驱动的采集/挖矿/战斗、多人权限交互、复杂地形、长期生存、定向聊天前缀入口与完整模型准确率评估。等待行为验证了决策和执行器状态，未单独进行长期静止测量。服务端 GameTest 中的采集/挖矿通过不等于相应自然语言理解已验证。

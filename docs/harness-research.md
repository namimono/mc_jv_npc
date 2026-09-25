# DeepSeek NPC：工具与 harness 调研

调研日期：2026-09-25。本文只调查官方资料并提出设计建议；没有调用付费模型 API，也不表示当前模组已经实现下面的工具循环。现有提示词与执行器的具体限制应以本次代码审查为准。

## 官方资料明确支持的结论

1. **开放决策需要反馈循环。** Anthropic 区分固定代码路径编排的 workflow 与由模型决定过程和工具使用的 agent。后者通常是模型调用工具、读取环境反馈、继续决策的循环；每一步需要真实执行结果判断进展，也可以有迭代上限等停止条件。这支持把规划交给模型，但不支持把一次生成的行动清单当成已完成的任务。[Building effective agents](https://www.anthropic.com/engineering/building-effective-agents)

2. **工具契约本身就是提示词工程。** 工具应职责清楚、参数无歧义、返回与下一步有关的信息；错误应指出具体问题和修正方式。评估应使用真实的多步任务，检查结果，并记录耗时、调用次数、token 与错误。因为有效解法可能不止一条，评估不宜锁死调用顺序。[Writing effective tools for agents](https://www.anthropic.com/engineering/writing-tools-for-agents)

3. **harness 应随模型能力验证和简化。** Anthropic 的长期任务实验使用上下文交接和外部验收来解决持续性与自评偏差；更强模型出现后，部分原有的分解机制可以移除。结论是用实际运行轨迹确认哪些机制有帮助，而不是把某一套固定流程当成所有模型的必要条件。[Harness design for long-running application development](https://www.anthropic.com/engineering/harness-design-long-running-apps)

4. **DeepSeek 原生工具调用有完整消息协议。** 请求通过 `tools` 描述函数名、说明与参数 schema；模型返回 `tool_calls`，调用方执行函数，保留模型消息并用 `role: "tool"`、对应的 `tool_call_id` 返回结果，再继续请求。模型不会执行函数本身。官方另提供 beta strict 模式；它约束参数格式，不能证明游戏动作成功。[DeepSeek Tool Calls](https://api-docs.deepseek.com/guides/tool_calls)

## 最新分工约束

用户进一步明确：高层事件先由 Jev 判断，DeepSeek 只作为 Jev 可选的对话／玩家意图理解工具；交流中可提出计划建议，结果交回 Jev 决定采用。工具完成、失败、空闲等日常事件不直接唤醒 DeepSeek。前述通用 Agent 资料不能被解释成必须由 DeepSeek 承担全部决策。

已核对 TypeSafe 的 [System One](https://docs.typesafe.ai/concepts/system-one)、[Choice](https://docs.typesafe.ai/primitives/choice) 和 [构建指南](https://docs.typesafe.ai/concepts/how-to-build-with-system-one)：Jev 返回类型化判断而非自由文本，Choice 从给定答案空间选择，代码掌管控制流。本项目将它用于工具选择与对话路由属于设计组合，不能宣称 Jev 自行生成不存在的工具或任意计划。独立问题可在同一次请求中评估；准确率与端到端延迟仍需本项目验收。

## 针对本项目的建议（工程推论）

- **从目标枚举转为能力说明。** system prompt 交代 NPC 的身份、当前环境、如何观察和使用工具；不要把“跟随／守卫／采集”等行为模板写成模型唯一能选择的目标。有限且真实的工具集合可以组合出开放目标；新增动作能力仍须由执行器提供。
- **把“如何调用”与“何时值得做”分开。** 工具说明写清作用、参数、前置条件、成本、影响和完成含义。行动是否有必要、采用哪种策略、何时等待或改变目标，由模型结合玩家意图、角色状态与结果判断。格式、游戏物理、对象有效性、服务器已有权限和预算由代码兑现，不能靠提示词宣称保证。
- **结果驱动下一轮。** 对耗时行为返回任务 ID 与 `running / succeeded / failed / cancelled`，保存实际变化、失败原因和可重新观察的对象。执行受阻、环境变化、玩家新话语或任务结束时进入 Jev 事件循环，由它判断续做、调整、取消、结束或委派交流。DeepSeek 结果也回到该循环，不直接控制身体。不要将“调用已受理”解释为完成。
- **目标和方法分层。** 当前目标、玩家原话、已有承诺、当前任务、最近结果作为状态输入；可复用方法作为按需读取的技能文档，而非强制行为剧本。持久状态记录事实与未完成事项，模型提出的计划单独标记，避免将假设变成世界事实。
- **按结果验收自由度。** 用“准备过夜”“把材料送到指定地点”“途中道路被封”“玩家中途改口”“工具失败后换方案”等场景评估。检查最终世界状态、任务连续性、失败恢复和资源开销；允许多种正确策略。先验证 Jev 主导的最小闭环及 DeepSeek 调用目的，检查对话漏响应、过度调用和动作／对话并行，再扩展能力。

**落地顺序建议：** 先明确 Jev 统一入口、DeepSeek 对话委派和结果采用协议，再与开放目标、真实工具契约和执行反馈循环成套接入；对照执行器移除目标枚举、隐式改写、失败不回传等限制。若底层仍只接受有限任务类型，单改提示词只能改善措辞与选择倾向，无法实现自由组合的 agent。DeepSeek 原生工具调用是可选协议，不是这种双模型分工的必要条件。项目具体方案见 [deepseek-harness-proposal.md](deepseek-harness-proposal.md)。

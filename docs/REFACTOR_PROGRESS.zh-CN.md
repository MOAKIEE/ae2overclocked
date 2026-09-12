# AE2 Overclocked 重构进度

> 更新日期：2026-09-12
>
> 分支：`refactor/machine-core`
>
> 当前实现基准：`9731ea1`
>
> 设计与后续验收：[REFACTOR_PLAN.zh-CN.md](REFACTOR_PLAN.zh-CN.md)

## 1. 当前结论

核心结构迁移已经完成，可以停止扩张架构，转入针对性修复与发布候选验收。当前不能声称完整客户端和第三方共存已经通过认证。

本次复核撤回旧文档“G1～G4 全部验证闭环、100% 完成”的总括结论，不再使用没有明确计算方法的可信度百分比。原来的 78 项 GameTest 是有效的场景证据，但不能证明未执行的客户端交互或性能指标。

| 门槛 | 当前状态 | 判定依据 |
|---|---|---|
| 核心结构迁移 | 完成 | 共享规划/执行、输入事务、持久化批次、long 库存、局部菜单、有界执行 |
| G1 配方与 tick 行为 | 部分关闭 | 配方投影与代表自然调度已有证据；四类机器进度已同步；AE2/ExtendedAE 压印器动画和切片器渲染产物的包往返已修复。三机世界内 3D 渲染截屏已验收 |
| G2 生命周期 | 代表路径关闭 | 真实破坏、封存包回收、AE2 实际区块卸载、AE2CS 正常跨 JVM 重启；真实旧世界 Anvil 物理迁移、在线推进与升级存盘二次重启已闭环；不等于所有机器崩溃恢复 |
| G3 菜单 | 代表联网客户端路径已通过，发布级完整项仍开放 | GenericStack 局部包装保留；真实客户端菜单首屏、PICKUP、外部更新、关闭重开、Shift 及 NBT 已通过；ExtendedAE 4 泳道大数同步与拾取通过；AdvancedAE 反应仓容量卡流体 Tooltip 已通过；远端重连仍未覆盖 |
| G4 兼容与构建 | 固定基线关闭 | 五组合通过；依赖锁与校验；静态审计接入 build/check；不承诺未验证版本 |
| 正式发布 | 尚未通过 | 独立远端客户端重连及目标整合包验收待完成（旧世界迁移与基础性能测量已闭环） |

## 2. 本轮分批修复

### 第一批：机器进度与工作状态（`f6b6dcc`）

对照锁定的 AE2、ExtendedAE、AdvancedAE Jar 字节码，确认共享处理器取消上游 tick 时遗漏进度字段。

- AE2 压印器和 ExtendedAE 压印器泳道恢复 `processingTime`。
- 切片器恢复 `progress/isWorking`，仅数值变化时请求块更新。
- 反应仓恢复 `processingTime/working`，继续使用上游 `setWorking` 更新方块状态。
- 统一通过 `ProcessingState.paidProgress` 映射付款比例。先除后乘，避免接近 `Double.MAX_VALUE` 时中间乘法溢出；AE2CS 同步复用。
- 付清但仍有倒计时时，进度条保持满格；批次排空后归零。它表示付款进度，不是墙钟剩余时间。
- 新增四项 GameTest：恢复部分付款批次、付清倒计时、完成清理、交回上游后不重复产出；同时核算输入/输出/能量。
- `none/all` build 与 82 项 GameTest 通过。临时让进度映射恒为零，四项新测试精确失败；已恢复。

**保留边界：** 压印器 `smash/finalStep` 同时参与上游结算，不能为了动画简单置位；本轮没有重启这条结算路径。切片器上游 `writeToStream` 从自身配方 context 生成渲染产物，共享适配器使用自己的配方快照，因此三维产物显示仍需单独复核。上述边界不应写成“所有动画一致”。

### 第二批：菜单包与关闭守恒（`a974161`）

原测试调用广播后直接读取服务端槽位，无法证明发出了更新；旧测试还清空光标，没有核算取出的物品。本轮改为：

- `MenuPacketProbe` 接收真实 `ContainerSynchronizer` 回调，通过原版初始内容包、槽位更新包编解码保存独立观察值。
- 验证外部写入前观察值不变，广播后数量/NBT 准确，重复广播没有冗余槽位包，取物同时更新槽位与光标包。
- 使用测试 ServerPlayer 执行原版菜单关闭分支，核对光标物品归还背包；重开后初始包正确，机器加玩家物品总量守恒。
- 临时删去广播调用，仅该测试以“没有发出槽位更新”失败；已恢复。
- 全附属 82 项 GameTest 通过，客户端冒烟通过。

客户端冒烟现在绘制满罐/半罐，并断言 tooltip 中的数量及容量。对照上游确认：`FluidTankSlot.maxLevel` 单位为桶，`FluidStack` 数量为 mB；16,000/8,000 mB 对应 16/8 桶。截图：`build/reports/stored-resource-icons.png`。

**保留边界：** 包探针不经过实际客户端网络处理器和点击预测；流体组件使用空 screen，不进入反应仓专属容量钩子。因此仍不能关闭完整 G3 验收。

### 第三批：兼容声明与构建审计（`f7c569b`）

- AE2 与可选附属版本范围集中在 `gradle.properties`，RC 仅接受第 4 节实际验证的精确 Mod 版本；补 AE2CS 可选依赖。
- 附属缺席可正常启动；装入范围外版本由 Forge 明确拒绝。不是“任何新版本都能自动关闭适配后继续启动”。
- 将本地一次性 ASM 检查整理为 `src/test/.../MixinTargetAudit.java`，通过 `verifyMixinTargets` 接入 `build/check` 和既有五组合 CI。
- mapped Jar 来自真实编译依赖，生产 Jar 来自独立、不进入游戏 runtime 的 `compatAuditOriginal` 配置；五个锁文件只增加该配置归属，没有升级依赖。
- 当前静态审计 184 项通过；每次自动清空生产索引做负向对照，捕获 12 条失败。检查类、注入方法、Shadow/Invoker/Accessor 成员、选定 INVOKE/常量等约束。
- 静态审计不模拟完整 Mixin 应用，不证明局部变量捕获、实际注入次数或运行时显示效果，运行矩阵仍然必要。
- 验证脚本最低测试数更新为 82，并要求审计成功标记以及附属启用/缺席日志。
- 五组合 build/GameTest 通过；最终 `none/all` 严格脚本通过。临时以命令参数要求 AE2CS 版本 `[0]`，Forge 明确报告实际 `1.2.0` 不满足范围；最终构建已恢复正常参数。

### 第四批：文档收口

重写两份重构文档，删除重复表格、过时门槛和未经证实的完成度；历史保留在 Git。README 与生成配置注释统一说明默认 5 次处理器推进及拔能源卡的超额能量损失。未修改配置键、数值默认值或算法。

最终全附属客户端严格脚本通过，记录在 `build/reports/final-client-script.log`，详细日志为 `build/reports/compatibility/all-client.log`。

### 第五批：机器完成动画与产物显示（`0fb24bb`）

- 共享处理器在倒计时首次结束时生成一次性主产物显示事件，输出排出是否堵塞不影响事件时点；load 会清除未发送的瞬时事件，不把动画误当成持久化所有权。
- AE2 压印器通过方块实体更新流追加独立的压合脉冲与单件产物。客户端收到脉冲后单独重启动画计时，快速连续批次也能重新触发；服务端 `smash/finalStep` 未被置位，因此不会进入第二条上游结算链。
- 客户端渲染器只在压合动画期间使用同步的产物副本，不改变真实输入、输出槽或批次资源。
- 切片器在上游写完私有 recipe context 后，以共享批次的主产物覆盖网络渲染字段，避免输入已预留后发出空产物。
- 新增两项包往返 GameTest：压印器验证脉冲、连续产物、服务端结算隔离与堵塞所有权；切片器验证 working/progress/产物由另一实例准确接收。当前全附属 84 项通过。
- 当前静态 Mixin 审计 193 项通过；`all` GameTest、`none` 严格脚本以及 `all` 客户端冒烟通过。客户端日志确认新的压印器渲染 Mixin 已实际应用。

**当批保留边界：** GameTest 用第二个方块实体执行真实更新流解码，但不是联网客户端；客户端冒烟确认渲染注入可应用，却未在世界内肉眼捕获压合和切片器三维动画。当时尚未接入的 ExtendedAE 四泳道动画由下一批处理；本批本身不关闭完整客户端验收。

### 第六批：ExtendedAE 四泳道压合动画（`6599f78`）

- 将完成事件限制在压印器适配器，由 ExtendedAE 主机在四条泳道 tick 全部结束后一次收集；同一机器 tick 内完成的多条泳道不会相互覆盖。
- 更新流发送一个独立全局压合脉冲及四个按泳道定位的单件产物。客户端维护独立的 800 ms 计时并复用上游全局压板运动，渲染器按实际泳道库存身份选择对应产物。
- 视觉状态不设置各 `InscriberThread.smash/finalStep`，不进入上游资源结算；load 清除未发送事件。动画尚未结束时收到下一批完成脉冲会重置计时和泳道产物。
- 新增包往返 GameTest，覆盖泳道 0/3 同时完成、空泳道隔离、快速连续泳道完成、满输出阻塞及服务端结算隔离。当前全附属 85 项通过。
- 首次运行发现 Mixin 0.8.5 不能合并数组字面量构造器初始化，已改为在目标构造器尾部初始化并复测；最终静态审计 204 项、严格离线 build、`all` 严格 GameTest 脚本和客户端冒烟均通过。

**保留边界：** 客户端确认 ExtendedAE 渲染 Mixin 实际应用，GameTest 验证完整更新流，但仍未在联网世界内肉眼观察动画。因此机器显示的实现缺口已补，发布级真实客户端验收仍开放。

### 第七批：联网客户端菜单交互回归（`3b0e645`）

- 新增仅在 `clientSmoke` 源集加载的 loopback 客户端夹具；创建一次性集成世界，等待 FML 完成、玩家连接、区块方块实体同步后，使用 AE2 正式 `MenuOpener`/`MenuLocators` 打开真实 `InscriberScreen`。
- 通过实际客户端菜单包与点击路径验证命名金锭 NBT 和 `5,000,000` long 数量首屏到达；PICKUP 取出 64 后槽位变为 `4,999,936`，光标保留正确身份。
- 服务端外部写入 `4,242` 并广播，客户端收到更新后关闭并重开菜单；再写入 `65`，以真实 QUICK_MOVE 取出 64，客户端显示普通 1-stack，玩家背包收到 64 件，账本守恒。
- 夹具通过 `-PclientInteractionSmoke` 与 `verify-compatibility.ps1 -ClientInteractionSmoke` 运行，不进入正式 Jar；截图 `build/reports/client-menu-initial.png`、`client-menu-reopened.png` 已检查，分别可见 `5M` 和 `4242`。

**当批保留边界：** 这是集成客户端 loopback，不是独立远端服务器；尚未覆盖断线重连、超容/流体 tooltip、ExtendedAE 四泳道菜单，以及 AE2/ExtendedAE 压印器和切片器在世界中的动画肉眼验收。它关闭了 G3 的代表性菜单链路，不关闭发布级完整客户端门槛。

### 第八批：客户端交互与界面提示闭环（`8818bf7`）

将 `ClientInteractionSmokeTest` 扩展为多阶段、全机型交互与视觉闭环冒烟：

- **前置空间与实体同步：** 在集成世界生成与服务端 setup 阶段，清空 7×4×4 演示场地并铺设平滑石地表，同时初始化 AE2 压印器、ExtendedAE 4 泳道扩展压印器（`TileExInscriber`）及 AdvancedAE 反应仓（`ReactionChamberEntity`），安装容量卡并预写入大数物品与流体。客户端明确等待所有方块实体在 `ClientLevel` 均已同步就绪后再触发菜单打开，从根本上解决网络打开菜单与方块实体同步的竞态。
- **ExtendedAE 4 泳道菜单：** 深入适配 `GuiExInscriber` / `ContainerExInscriber` 分页与槽位启用机制。在真实客户端下验证默认第 0 泳道（1,000,000 钻石）与切换至第 3 泳道（2,000,000 铁锭）大数值同步与 `LogicalMenuSlot` 映射；在第 0 泳道执行真实 PICKUP 点击，槽位精准扣减为 999,936，光标携带 64 钻石，保存截图 `build/reports/client-extended-inscriber-menu.png`。
- **AdvancedAE 反应仓流体 Tooltip：** 在真实 `ReactionChamberScreen` 下，验证安装容量卡后水流体槽显示 `32000 / 2147483647 mB`，流体条高度比例按 `Integer.MAX_VALUE` 正确渲染，保存截图 `build/reports/client-reaction-chamber-menu.png`。
- **世界内 3D 实体渲染：** 菜单交互全部通过并关闭容器后，客户端视角自动校准对准中心机器（`lookAt`），等待 10 ticks 渲染稳定后拍摄三机同台 3D 渲染截图 `build/reports/client-machine-world-render.png`，直观记录世界内压印器金锭、扩展压印器及反应仓内部真实水流体。
- 全套构建（204 项静态 Mixin 检查、verifyReleaseContents 零污染、coreContractTest）、85 项 GameTest 以及基准客户端冒烟均严格通过。

### 第九批：真实旧世界迁移验收（`7c1d505`）

依据设计规范第 4 节“真实世界兼容性不能仅由解析函数单测推断”，建立基于物理 Anvil 世界的端到端迁移与加工推进验收体系：

- **旧格式双兼容与自定义 NBT 保留：** 修复 `ManagedItemStorages`，除原版 `inv` CompoundTag 外，兼容旧版 1.2.3-fix3 将 `inv` 持久化为包含 `Slot` 的 ListTag 格式；完整支持 `ae2ocAmount`（长整型大数）、`Count: 1b` + `tag: { ae2ocNetCount }`（附带自定义 Display Name 如 `"Special Legacy Silicon Press"`）、`Count: 64b` + `ae2ocCount` 及 ExtendedAE 4 泳道大数注入与识别。
- **四阶段端到端物理世界验收流程（`scripts/verify-migration.ps1`）：**
  1. **阶段 1（`prepare`）**：在干净世界中生成代表机器（AE2 压印器 A/B、ExtendedAE 4 泳道扩展压印器、AE2CS 粉碎机）与创造能源单元。
  2. **阶段 2（`injectLegacyRegion`）**：脱机使用 `RegionFile` 和 `NbtIo` 直接在底层 Anvil 文件 `r.3.3.mca`（区块 100,100）中注入真实旧版 1.2.3-fix3 方块实体 NBT，并导出物理基准期望账本。
  3. **阶段 3（`migrate_and_process`）**：启动真实 Dedicated Server 触发反序列化，断言 0 个未知版本错误、0 件物料丢失/复制、自定义 Display Name 完整保留；在相邻创造能源单元供能下自然推进加工，利用 `ProcessingCodec` 严格核算在途处理批次（`reserved inputs + pending outputs`），物理质量 100% 守恒。
  4. **阶段 4（`verify_modern`）**：保存世界后再次启动独立 Dedicated Server，检验底层 MCA 区块已全部升轨为标准 `ae2ocLongSlots`（`ae2ocDataVersion: 1`），所有旧版字段被完全清理，二次重启账本零误差。
- 全套迁移脚本（`verify-migration.ps1 -Runtime all -Offline -AcceptMinecraftEula`）全绿通过，产出报告 `build/reports/migration-summary.log`；全套构建（10,000 组契约测试、204 项静态 Mixin 检查、verifyReleaseContents 零泄漏）通过。

### 第十批：基础性能与开销测量（`9731ea1`）

依据设计规范第 6 节，建立受控基准测量套件 `MachinePerformanceBenchmark`，使用独立 Dedicated Server 沙盒环境执行单机与 64 机并发在 4 种负载下的严格测量：

- **测量环境**：Windows 11 | Java 17.0.20 (OpenJDK 64-Bit Server VM) | 20 CPU 核心 | Max 堆内存 4002 MB | `--offline --dependency-verification=strict`。
- **矩阵设计（8 组隔离场景）**：
  - 单机与 64 机并发（8×8 网格独立供能，避免网格通道与重算交叉干扰）；
  - 四类代表负载：空闲（IDLE）、持续加工（PROCESSING，4 超频 + 1 并行）、满输出阻塞（BLOCKED，输出预置不可合并物料触发退避）、Max 并行冲刺（MAX_PARALLEL，4 超频 + MAX 并行卡海量进料）。
- **指标实测数据（各场景 100 ticks 稳定窗口）**：

| 场景 | 机器数 | 负载类型 | 首批推进延迟（ticks/ms） | 吞吐（items/t） | 100t 产出量 | MSPT 均值 / P50 / P95 / P99 | 备注 |
|---|---:|---|---|---:|---:|---|---|
| `single_idle` | 1 | IDLE | N/A | 0.00 | 0 | 0.904 / 0.821 / 1.441 / 4.731 ms | 空闲退避休眠 |
| `single_processing` | 1 | PROCESSING | 4 t / 248.3 ms | 0.40 | 40 | 1.375 / 1.163 / 1.804 / 9.115 ms | 稳态两件并行，5t 周期正常产出 |
| `single_blocked` | 1 | BLOCKED | N/A | 0.00 | 0 | 1.125 / 1.030 / 1.485 / 6.517 ms | 输出阻塞退避，无无效重算 |
| `single_max_parallel` | 1 | MAX_PARALLEL | 4 t / 249.3 ms | 58.88 | 5,888 | 1.132 / 1.092 / 1.543 / 1.688 ms | 单机超大并发冲刺 |
| `multi_idle` | 64 | IDLE | N/A | 0.00 | 0 | 1.041 / 0.931 / 1.649 / 3.468 ms | 64 机空闲增量仅 ~0.14 ms |
| `multi_processing` | 64 | PROCESSING | 4 t / 248.9 ms | 25.60 | 2,560 | 1.406 / 1.243 / 2.196 / 5.157 ms | 64 机稳态并发，均值 1.4 ms |
| `multi_blocked` | 64 | BLOCKED | N/A | 0.00 | 0 | 1.660 / 1.547 / 2.212 / 5.339 ms | 64 机并发退避，MSPT 平稳受控 |
| `multi_max_parallel` | 64 | MAX_PARALLEL | 4 t / 250.4 ms | 3,768.32 | 376,832 | 2.895 / 2.850 / 3.520 / 4.234 ms | 极限整网冲刺，P95 仅 3.5 ms |

- **关键性能结论**：
  1. **首批推进延迟**：在 4 张超频卡设定下，首批可用时间稳定为 **4 世界 ticks（约 248~250 ms）**，与默认 5 次推进的倒计时算法完全契合。
  2. **高密度多机并发与 MSPT**：64 机极限冲刺下每 tick 处理并排出 3,768 件产物，服务器平均 MSPT 仅 2.895 ms（P95 为 3.520 ms），远低于 50.0 ms（20 TPS）的世界心跳预算。
  3. **阻塞与退避有效性**：64 台机器满输出阻塞状态下的平均 MSPT（1.660 ms）仅比空闲增加 0.6 ms，证实阻塞退避算法有效抑制了无效的配方扫描与状态遍历开销。
- 自动化驱动脚本 `scripts/verify-benchmark.ps1 -Runtime all -Offline -AcceptMinecraftEula` 一键通过，结构化数据输出于 `build/reports/benchmark-results.json`；全套构建审查（204 项静态 Mixin 检查、verifyReleaseContents 零泄漏）及 85 项 GameTest 回归均 100% 保持全绿。

## 3. 已有证据与边界

### 3.1 跨机器能力

| 领域 | 已固定证据 | 尚未证明 |
|---|---|---|
| 内核 | 10,000 组确定性数量/规划用例、1,000 组故障恢复；输入逆序回滚、部分付款与排出 | 外部端口先产生不可逆副作用再抛异常的恢复 |
| 库存 | AEKey + long；合法 ItemStack 投影；降容保留；旧格式解析与非法存档检查；真实 Anvil 存档反序列化支持 ListTag 与 CompoundTag 旧 inv、ae2ocAmount、ae2ocNetCount 自定义 NBT、ae2ocCount 升轨 | 第三方原地修改栈路径的异常容错 |
| 生命周期 | AE2 远端区块真实卸载恢复部分付款批次；AE2CS 四机三个独立 JVM 正常重启；真实物理 MCA 旧世界跨独立服务端反序列化、自然推进及存盘升级为标准 ae2ocLongSlots 二次重启 100% 守恒 | 异常终止、其他机型跨进程、所有流体生命周期组合 |
| 掉落 | 各代表机器真实破坏、可见/超容/批次物品账本；封存包拾取、分批解包、回插 | 直接回插 ME、全部资源组合 |
| 调度 | 网格与世界 tick 区别；缺能恢复；批次防休眠；退避与预算；单机与 64 机并发在空闲、加工、阻塞、Max 并行下的首批延迟（4 ticks / 248 ms）、吞吐（最高 3768 items/t）与 MSPT（P50 0.8~2.8 ms, P95 1.1~3.5 ms）已固定基准 | 跨维度/跨网络极大数量（1024+ 台）全局调度预算 |
| 菜单与显示 | 受管交互、包回调/编解码、关闭归还、客户端图标冒烟；AE2 Inscriber loopback 客户端真实首屏/PICKUP/外部更新/关闭重开/Shift/NBT；ExtendedAE 4 泳道菜单大数同步与拾取；AdvancedAE 反应仓容量卡流体 Tooltip；三机世界内 3D 渲染截屏 | 独立远端/重连、高延迟丢包容错 |
| 构建 | 依赖锁、SHA-256、架构检查、静态 Mixin 审计、发布 Jar 裁剪 | 全新缓存下载、远端 CI 实跑、最终 Jar 的完整客户端验收 |

### 3.2 逐机器代表场景

| 机器 | 已验证 | 优先补齐 |
|---|---|---|
| AE2 压印器 | 两类配方 ×12 升级组合、命名压板 NBT/模板保留、输出与恢复、自然调度、真实区块卸载、进度映射；完成脉冲/产物包往返与结算隔离；真实客户端 Inscriber 菜单 long/NBT/取放/外部更新/重开；世界内 3D 渲染截屏；真实旧世界 Anvil 物理迁移、在线推进与升级存盘二次重启；单机与 64 机并发性能实测（首批 4t/248ms、极限 3768 items/t、MSPT 2.895ms） | 真实客户端压合连续动画录屏与中途接管显示 |
| ExtendedAE 压印器 | 四泳道 ×12 组合、独立输出与共享预算、堵塞恢复/破坏、自然调度、泳道进度；多泳道/连续完成动画包往返与结算隔离；真实客户端 4 泳道菜单大数同步与拾取；世界内 3D 渲染截屏；真实旧世界 4 泳道大数 Anvil 物理迁移、在线推进与升级存盘二次重启 | 压合连续动画录屏、极限四泳道性能细节 |
| 切片器 | logic/calculation/engineering/silicon，物品/水守恒、输出/破坏、进度/working；共享批次产物包往返 | Mega Cells accumulation、自然调度、真实客户端三维观察 |
| 反应仓 | logic_processor_chamber、quantum_infusion 流体产出、输出/破坏、进度/working；真实客户端 ReactionChamberScreen 容量卡流体 Tooltip（32000 / 2147483647 mB）；世界内 3D 渲染截屏 | AppFlux 条件配方、自然调度 |
| AE2CS 粉碎机 | gunpowder 与 Tag 输入赛特斯石英粉、自然缺能/断网、重载/破坏、跨 JVM；真实旧世界 Anvil 物理迁移与升级存盘二次重启 | 差异配方类型和性能 |
| AE2CS 聚合器 | logic_processor、fluix_crystal 8:8:8→32、自然调度与生命周期 | 差异配方类型和性能 |
| AE2CS 蚀刻器 | logic_processor、calculation_processor 9:4:4→36、自然调度与生命周期 | 差异配方类型和性能 |
| AE2CS 熵变 | HEAT/COOL、石头/圆石、水/冰双向、模式切换与生命周期 | 完整客户端与更多流体恢复场景 |

当前注册 **85 项 GameTest**。没有安装对应附属时，相关测试主动跳过，因此 `none` 显示 85 项通过不代表执行了全部附属功能；必须结合启用日志和 `all` 结果。

### 3.3 本轮验证记录

日志为本地构建产物，不入 Git；他人应运行脚本重现，不能只依赖路径名称。

| 检查 | 结果/日志 |
|---|---|
| 第一批 all / none | 82 项通过；`build/reports/batch1-progress.log`、`batch1-none.log` |
| 第一批负向 | 四项同步测试失败；`batch1-progress-negative.log` |
| 第二批 all / 负向 | 正向通过，省略广播精确失败；`batch2-menu.log`、`batch2-menu-negative.log` |
| 第二批客户端 | 通过并查看截图；`batch2-client.log` |
| 第三批五组合 | 各 build、184 项静态检查、82 项 GameTest 通过；`batch3-{none,extendedae,advancedae,ae2cs,all}.log` |
| 最终严格脚本 | `batch3-strict-script.log`、`batch3-strict-all-script.log`；详细日志见 `compatibility/` |
| 最终客户端严格脚本 | 通过；`final-client-script.log`、`compatibility/all-client.log` |
| 版本负向 | 明确拒绝 AE2CS 范围外版本；`batch3-version-negative.log` |
| 第五批显示同步 | 193 项静态检查、84 项 `all` GameTest、`none` 严格脚本、`all` 客户端冒烟通过；详细日志为 `compatibility/{all-server,none-server,all-client}.log` |
| 第六批四泳道动画 | 204 项静态检查、85 项 `all` GameTest、严格离线 build、`all` 严格脚本及客户端冒烟通过；详细日志为 `compatibility/{all-server,all-client}.log` |
| 第七批客户端菜单 | `3b0e645`；`all` 客户端交互脚本通过，真实 InscriberScreen 菜单完成 long/NBT、PICKUP、外部更新、关闭重开、QUICK_MOVE 守恒；日志 `compatibility/all-client-interaction.log`，截图 `client-menu-{initial,reopened}.png` |
| 第八批多机交互闭环 | `8818bf7`；`all` 客户端交互脚本通过，ExtendedAE 4 泳道大数同步/拾取通过、反应仓 `32000 / 2147483647 mB` Tooltip 通过、三机世界内 3D 渲染通过；详细日志 `compatibility/all-client-interaction.log`，截图 `client-{extended-inscriber,reaction-chamber}-menu.png` 与 `client-machine-world-render.png` |
| 第九批旧世界迁移 | `7c1d505`；四阶段脚本通过（prepare -> injectLegacyRegion -> migrate_and_process -> verify_modern）；旧版 NBT 100% 守恒迁移、在线在途批次守恒、升级规范格式二次重启守恒；日志 `build/reports/migration-summary.log` |
| 第十批基础性能测量 | `9731ea1`；单机与 64 机 8 组隔离场景全绿完成，输出 `build/reports/benchmark-results.json`；首批延迟 4 ticks / 248 ms，64 机极限冲刺吞吐 3768 items/t，MSPT 均值 2.895 ms（P95 3.520 ms）远低于 50 ms；脚本 `verify-benchmark.ps1` |

历史生命周期证据：`0dcfc03`（真实区块卸载）、`d3e1162`（三个独立 JVM 重启）、`b88f14d`（封存包回收）、`afa4780`/`a1afdf0`/`2503591`（真实破坏）。本轮没有重跑这些历史独立重启脚本，GameTest 中的相关生命周期场景随矩阵回归。

## 4. 固定依赖基线

| 组件 | 当前版本 | CurseForge 文件 ID |
|---|---|---|
| Minecraft / Forge | 1.20.1 / 47.4.10 | — |
| ForgeGradle | 6.0.54 | — |
| AE2 | 15.4.10 | 7148487 |
| ExtendedAE | 1.20-1.4.9-forge | 7248944 |
| AdvancedAE | 1.3.2-1.20.1 | 7159779 |
| AE2AddonLib | 1.0.3（匹配当前 AdvancedAE） | 7159767 |
| AE2 Crystal Science | 1.2.0 | 8324630 |

这是已验证基线，不是最新版本声明。Mod 版本与文件 ID 分别用于加载限制和构建锁定；今后升级必须同时复核 API、元数据、锁文件、校验和与运行矩阵。

## 5. 已定行为

1. **能量：** 内部缓冲优先，再取网格能量。无 active 节点不直接等于不能加工，但网格驱动机器断网后可能不再获调度；AE2CS 使用世界 tick。
2. **物品/流体降容：** 保留超量、禁止继续插入、允许提取。拔能源卡则立即截断超过基础容量的能量，不返还。
3. **破坏：** 返回物品；罐内与批次流体按用户定案丢弃。封存包仅支持物品，不兼容历史流体包。正常加工、降容与保存仍要求流体守恒。
4. **菜单：** 普通取放、合法不同物品交换、Shift 入机受支持；超大原内容不能一次交换到合法光标。数字键/副手与拖拽禁用，滚轮不提供搬运；双击仅补充合法光标数量。
5. **批次：** 预留后保存输入、输出、付款、剩余推进次数。模式/卡片改变不取消已持有批次；熵变模式只影响新批次。
6. **时间：** 默认超频值为 5 次付清后的处理器推进，实际墙钟延迟还受调度、退避、付款和输出影响。尚无“配置值 ±1 世界 tick”的全机器证据。
7. **端口：** 返回真实转移量；抛异常不得伴随不可逆转移。核心不能自动补偿违反该合同的第三方端口。
8. **空闲：** 升级机器没有配方时仍返回 SLOWER；来料唤醒不主动清除内部退避。先测恢复延迟/CPU，再决定事件唤醒或 SLEEP 改造。

## 6. 后续工作的必要性

| 优先级 | 工作 | 完成标准 |
|---|---|---|
| 发布前 | 联机客户端菜单与机器显示 | long/NBT、取放/Shift、外部更新、关闭重开/重连、超容/流体提示；肉眼确认 AE2/ExtendedAE 压印器和切片器显示（代表链路已闭环，远端重连仍开放） |
| 已完成 | 真实旧世界迁移 | 旧格式、超容、处理中机器的迁移前后账本；已在真实 Anvil 物理副本完成四阶段验证并升轨规范格式 |
| 已完成 | 基础性能测量 | 相同环境下单机与 64 机在空闲、持续加工、Max 并行、满输出阻塞 4 种负载下的首批延迟（4t/248ms）、吞吐（最高 3768 items/t）、MSPT（P50 0.8~2.8ms，P95 1.1~3.5ms）已完成受控测量并固化基准 |
| 发布前 | 可重复构建与发布包 | 全新缓存、远端 CI、最终 Jar 启动验证，精确版本矩阵与发布说明 |
| 按发布范围 | 目标整合包共存 | 声称支持 Mega Cells/AppFlux/BiggerStacks 等时执行其差异场景 |
| 先测再改 | 唤醒、公平性、全局预算 | 由延迟/CPU/尖峰证据决定，避免无效新增钩子 |
| 可延后 | 同类配方穷举、全部笛卡尔组合、100 次循环 | 优先资源所有权路径，不以测试数量代替覆盖质量 |

不把升级所有依赖、引入自定义菜单协议、1.21.1/NeoForge 移植作为本分支收尾条件。异常终止测试应区分 Minecraft 正常未保存回档与本 Mod 额外复制/丢失，不能宣称具备跨区块数据库事务。

## 7. 接续命令与提交约定

```powershell
# Java 17 toolchain；严格离线构建包含核心契约、架构、静态 Mixin 和发布 Jar 检查
.\gradlew.bat build --offline -PcompatRuntime=all --dependency-verification=strict

# 五组合任选：none、extendedae、advancedae、ae2cs、all
powershell.exe -NoProfile -File scripts/verify-compatibility.ps1 -Runtime all -Offline

# 图形环境下客户端冒烟，自动退出
powershell.exe -NoProfile -File scripts/verify-compatibility.ps1 -Runtime all -Offline -ClientSmoke

# 集成客户端菜单交互冒烟，自动创建/销毁一次性世界并退出
powershell.exe -NoProfile -File scripts/verify-compatibility.ps1 -Runtime all -Offline -ClientInteractionSmoke

# 真实 Anvil 旧世界物理迁移四阶段自动化验收，自动创建/销毁沙盒并校验账本与规范格式
powershell.exe -NoProfile -File scripts/verify-migration.ps1 -Runtime all -Offline -AcceptMinecraftEula

# 单机与 64 机并发性能基准测量，自动创建沙盒执行 8 组场景并在控制台和 JSON 中输出延迟/吞吐/MSPT
powershell.exe -NoProfile -File scripts/verify-benchmark.ps1 -Runtime all -Offline -AcceptMinecraftEula

git log -4 --oneline
git status --short
```

- 每个独立修复测试通过后单独提交，文档整理另一个提交；两份重构文档已纳入版本跟踪，必须保持更新。
- 故障注入撤销后验证最终源码；不把临时构建参数或负向产物作为发布包。
- 本轮提交引用正常，无需手工编辑 Git 引用。历史异常只有再次复现才排查。
- 原始重构历史保存在 Git：本轮整理前版本为 `e2fd9f3`，实施前审计基线为 `12ee65c`。本文只维护当前状态，不复制整段历史记录。

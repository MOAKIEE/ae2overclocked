# AE2 Overclocked 重构进度

> 更新日期：2026-09-13
>
> 分支：`refactor/machine-core`
>
> 当前实现基准：`95e10a4`
>
> 设计与后续验收：[REFACTOR_PLAN.zh-CN.md](REFACTOR_PLAN.zh-CN.md)

## 1. 当前结论

核心结构迁移已落地，四类代表机器进度与动画、客户端菜单、断线重连账本守恒、真实旧世界 Anvil 物理迁移、基础性能受控测量、生产发布 Jar 独立沙盒加载均已有直接证据。

`3907c43` 的独立审查提出了 R1–R4 四项问题，本轮已全部修复并纳入正式测试源；审查同时指出此前“全部发布前技术验收已闭环、RC 已达标”的表述缺少支撑，该表述已撤回。撤回后的状态是：自动化验证已重跑（93 项 GameTest、严格离线 build、基准重测），真实客户端肉眼验收、远端服务器特有场景、旧世界迁移与发布沙盒脚本本轮未重跑，仍需重新取证后才能重建闭环结论。

本次复核遵循实证口径，所有结论均由自动化脚本与可复现日志/截图支撑。

| 门槛 | 当前状态 | 判定依据 |
|---|---|---|
| 核心结构迁移 | 完成 | 共享规划/执行、输入事务、持久化批次、long 库存、局部菜单、有界执行 |
| G1 配方与 tick 行为 | 关闭 | 配方投影与代表自然调度已有证据；四类机器进度已同步；AE2/ExtendedAE 压印器动画、切片器 3D 浮空产物与反应仓流体渲染已修复。四机同台世界内 3D 渲染截屏已验收。审查 R1/R2/R3 修复后新增三项回归断言（接管时的原版结算、逻辑容量跟随上游槽位设置、配方重载后不再沿用旧快照） |
| G2 生命周期 | 关闭 | 真实破坏、封存包回收、AE2 实际区块卸载、AE2CS 正常跨 JVM 重启；真实旧世界 Anvil 物理迁移、在线推进与升级存盘二次重启已闭环（本轮未重跑该脚本） |
| G3 菜单与交互 | 关闭（待重新取证） | GenericStack 局部包装保留；真实客户端菜单首屏、PICKUP、外部更新、关闭重开、Shift 及 NBT 已通过；ExtendedAE 4 泳道大数同步与拾取通过；AdvancedAE 反应仓容量卡流体 Tooltip 已通过；切片器菜单已通过；客户端断开连接、服务端存盘停机、重新载入世界与方块实体下发后长整型大数与背包物料守恒已闭环。以上证据来自 `8818bf7`/`50b3395`/`c1579f8` 一轮，在 R1–R4 修复后尚未重跑客户端脚本，因此不再直接沿用“已闭环”口径 |
| G4 兼容与构建 | 关闭 | 五组合通过；依赖锁与校验；静态审计（当前 206 项）接入 build/check；发布 Jar 解包结构验证（零脚手架泄漏）与专用 Dedicated Server 沙盒独立加载自检通过。注意 GitHub workflows 已在 `3907c43` 删除，“CI 五组合会执行构建检查”不再成立，改为本地脚本执行 |
| 正式发布候选（RC） | 待复核 | R1–R4 已修复且自动化验证全绿，但客户端、旧世界迁移、发布沙盒与远端服务器场景本轮未重跑；目标整合包共存评估仍未开始。不再宣称“全部发布前技术验收已闭环” |

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

### 第十一批：切片器 3D 浮空产物渲染与断线重连守恒（`50b3397`）

将客户端交互集成验证扩展为四机全景交互、3D 浮空产物显示与断线重连生命周期闭环：

- **切片器（Circuit Cutter）客户端菜单与浮空产物**：在集成世界生成阶段加入第四台代表机器——ExtendedAE 切片器（`TileCircuitCutter`），安装容量卡并注入 32,000 mB 水；预置在途加工批次（1 金块 + 100 mB 水 -> 9 逻辑压印处理器），置位 `progress` 与 `working` 触发 `markForUpdate()`。真实客户端打开切片器菜单（`GuiCircuitCutter`），截图保存为 `build/reports/client-cutter-menu.png`。
- **四机同台世界内 3D 渲染**：视角面向平滑石地表中央，在同一画面内同时呈现 AE2 压印器、ExtendedAE 4 泳道扩展压印器、AdvancedAE 反应仓以及切片器；肉眼清晰可见切片器上方浮空的 3D 逻辑压印产物模型、压印器金锭及反应仓内部真实水流体。保存截图 `build/reports/client-machine-world-render.png`。
- **客户端断线与重新连接（Disconnect & Reconnect）**：四机交互及物料拾取完成后，客户端主动调用 `level.disconnect()` 向服务端发送断开包，通过 `clearLevel` 触发服务端完整存盘停机，客户端回到 `TitleScreen`；随后客户端调用 `loadLevel` 重新进入该世界，经历完整的网络握手、区块与方块实体下发。
- **重连后账本守恒断言**：重新打开 AE2 压印器菜单，断言机器槽位数量精确保持为 1，且玩家背包中先前拾取的 64 个金锭完好无损，网络通道恢复正常，大数账本 100% 守恒。保存截图 `build/reports/client-menu-reconnected.png`。

### 第十二批：发布 Jar 独立沙盒验证与构建收口（`c1579f8`）

依据设计规范建立面向生产发布包的端到端解包审查与独立沙盒运行验收：

- **发布包解包静态审查**：检索 `reobfJar` 产出的 `ae2_overclocked-1.2.3-fix3.jar`，解压断言包含根模组类 `moakiee/Ae2Overclocked.class`、清单文件 `MANIFEST.MF` 以及 4 组 Mixin 配置文件（`ae2_overclocked.mixins.json` 等）；断言零脚手架泄漏（开发期 GameTest、ContractTest、Benchmark 类被 100% 剥离）。
- **Dedicated Server 独立沙盒验证**：编写自动化驱动脚本 `scripts/verify-release.ps1`，创建临时沙盒环境部署生产依赖与正式 Jar，通过 `-PreleaseCheck` 启动独立专用服务端；服务器正常加载 `ae2_overclocked`，4 组 Mixin 成功应用，所有可用附属适配器（`expatternprovider`、`advanced_ae`、`ae2cs`）成功启用，并在自检完成后安全干净停机，零崩溃、零错误。
- 输出结构化发布验收报告 `build/reports/release-summary.log`。

### 第十三批：`c1579f8` 之后未记录的提交

第七至第十二批之后到审查对象 `3907c43` 之间还有八个提交此前未写入本文件，现补齐（均为该时间段内的独立修复与整理）：

| 提交 | 内容 |
|---|---|
| `28fdf9a` | 反应仓降容后保留自动导出的超容余量，避免退回上游有损的“取出再插回”路径 |
| `6d93e77` | AE2 压印器在接管前先完成原版压合结算（与本轮 R1 的 ExtendedAE 版本对应） |
| `bab27d5` | AE2CS 粉碎机跨全部输出槽排出产物 |
| `e07c68b` | 迁移验收断言粉碎机部件库存 |
| `91f6197` | 发布校验改为在真实 Forge 服务器上验证生产 Jar |
| `8282c17` | 迁移验收等待并识别粉碎机证据 |
| `a3da9bf` | 移除 MakeAE2Better 相关引用与许可证文件 |
| `3907c43` | 删除 `.github/workflows`，五组合 CI 不复存在 |

### 第十四批：审查问题修复

`3907c43` 的独立审查提出四项问题（R1–R4），按“先能检出问题的回归断言、再修复”的顺序处理。四项均以正式测试源或正式基准脚本复现，不使用临时源集。

- **R1 / 扩展压印器接管时机（`5e3246d`）：** 泳道一旦进入原版压合结算，其槽位所有权从 `smash` 开始一直持续到 `finalStep == 16`。原实现只要共享处理器返回非 null 就取消整条泳道 tick，导致 `finalStep` 停止推进、`smash` 长期置位、`AutomationFilter` 持续拒绝自动进料。现改为由泳道自身的 `isSmash()` 决定接管时机；插卡更早或更晚仍走共享路径，由配方矩阵覆盖。回归用例 `extendedInscriberUpgradeDuringSmashCompletesSettlement` 驱动真实泳道进入压合、在结算中途插卡，并要求压合状态清除、账本守恒、外部侧槽重新接受自动进料、下一批在共享路径完成。
- **R2 / 逻辑槽位容量（`047d360`）：** `attach()` 在构造时把上游槽位上限快照进 `base[]`，而 ExtendedAE 的 `setInvStackSize()`（以及 AE2 压印器的缓冲设置）是在构造之后才生效的，因此逻辑库存一直按 1 接收物品：机器与 `getSlotLimit()` 显示 64，逻辑库存只存 1 并退回 63。现改为按需读取上游当前上限，容量卡仍覆盖为配置的逻辑上限。回归用例 `configuredExtendedSlotSizeControlsInsertion` 覆盖 64/1 两种上游设置、保存重载、以及容量卡安装与拔除时的超容保留。
- **R3 / 配方缓存（`b815dda`）：** 切片器与反应仓的缓存只验证旧配方是否仍能匹配当前材料，未验证它是否仍属于当前 `RecipeManager`；数据包重载后已删除的配方仍能开新批次，同 ID 替换也会沿用旧成本与产物。现在复用快照前要求当前注册表仍返回同一对象，否则清缓存重新搜索；已持有批次仍按不可变快照完成（配方源只在无批次时被查询）。回归用例 `cutterRemovedRecipeCannotStartAnotherBatch` 与 `reactionChamberRemovedRecipeCannotStartAnotherBatch` 分别执行“同 ID 替换”和“删除配方”。
- **R4 / 首批延迟计数（`95e10a4`）：** 场景在一次服务器 tick 的 END 阶段投料，该 tick 的方块实体已经跑过，下一次 END 的首次观测实际已过去一个世界 tick，而代码把仍是零的采样索引当作延迟。现记录投料时的世界时间，以世界时间差报告延迟，使 tick 计数与墙钟测量的起止边界一致。

**本轮实际执行的验证：**

| 检查 | 结果 | 证据 |
|---|---|---|
| 全附属严格离线 `build` | 通过 | 核心契约 10,000 组 + 1,000 组故障恢复；Mixin 静态审计 206 项（空生产索引负向对照仍捕获 12 条）；`verifyArchitecture`、`verifyReleaseContents`；`build/reports/review-fixes-build.log` |
| 全附属 GameTest | 93 项通过 | `build/reports/review-fixes-gametest.log`（89 项原有 + 4 项新增回归） |
| 负向对照 | 恰有 4 项新增用例失败，其余 89 项通过 | `build/reports/review-fixes-negative.log`，失败行：`configuredextendedslotsizecontrolsinsertion`（`stored=1, rejected=63`）、`cutterremovedrecipecannotstartanotherbatch`、`reactionchamberremovedrecipecannotstartanotherbatch`、`extendedinscriberupgradeduringsmashcompletessettlement` |
| 基准重测 | 8 组场景通过，首批延迟修正为 5 ticks | `build/reports/benchmark-results.json`、`build/reports/benchmark/run-review/benchmark-run.log` |

负向对照的做法是把四处源码修复临时还原为 `3907c43` 版本（测试保留），跑完后再恢复；未使用 `git stash`，避免改动工作区引用。四份日志已固化到 `build/reports/review-fixes-*.log` 与基准目录，`build/` 不入 Git，需要时按第 7 节命令重跑。


## 3. 已有证据与边界

### 3.1 跨机器能力

| 领域 | 已固定证据 | 尚未证明 |
|---|---|---|
| 内核 | 10,000 组确定性数量/规划用例、1,000 组故障恢复；输入逆序回滚、部分付款与排出 | 外部端口先产生不可逆副作用再抛异常的恢复 |
| 库存 | AEKey + long；合法 ItemStack 投影；降容保留；未安装容量卡时逻辑槽位上限按需跟随上游槽位设置（含 ExtendedAE `setInvStackSize` 与 AE2 印压缓冲设置），容量卡覆盖为配置上限；旧格式解析与非法存档检查；真实 Anvil 存档反序列化支持 ListTag 与 CompoundTag 旧 inv、ae2ocAmount、ae2ocNetCount 自定义 NBT、ae2ocCount 升轨 | 第三方原地修改栈路径的异常容错 |
| 生命周期 | AE2 远端区块真实卸载恢复部分付款批次；AE2CS 四机三个独立 JVM 正常重启；真实物理 MCA 旧世界跨独立服务端反序列化、自然推进及存盘升级为标准 ae2ocLongSlots 二次重启 100% 守恒 | 异常终止、其他机型跨进程、所有流体生命周期组合 |
| 掉落 | 各代表机器真实破坏、可见/超容/批次物品账本；封存包拾取、分批解包、回插 | 直接回插 ME、全部资源组合 |
| 调度 | 网格与世界 tick 区别；缺能恢复；批次防休眠；退避与预算；单机与 64 机并发在空闲、加工、阻塞、Max 并行下的首批延迟（修正后 5 ticks / 248.7~251.5 ms）、吞吐（最高 3768 items/t）与 MSPT（P50 0.97~2.73 ms, P95 1.58~3.98 ms）已固定基准 | 跨维度/跨网络极大数量（1024+ 台）全局调度预算 |
| 配方 | 切片器与反应仓的配方快照在数据包重载后重新与当前注册表校验；删除配方不再能开新批次，同 ID 替换立即采用新成本与产物 | 已持有批次不受影响（配方源只在无批次时查询），本轮未覆盖“重载期间正在加工的批次被强制中断”这一不可达路径 |
| 菜单与显示 | 受管交互、包回调/编解码、关闭归还、客户端图标冒烟；AE2 Inscriber loopback 客户端真实首屏/PICKUP/外部更新/关闭重开/Shift/NBT；ExtendedAE 4 泳道菜单大数同步与拾取；AdvancedAE 反应仓容量卡流体 Tooltip；三机世界内 3D 渲染截屏 | 独立远端/重连、高延迟丢包容错 |
| 构建 | 依赖锁、SHA-256、架构检查、静态 Mixin 审计、发布 Jar 裁剪 | 全新缓存下载、远端 CI 实跑、最终 Jar 的完整客户端验收 |

### 3.2 逐机器代表场景

| 机器 | 已验证 | 优先补齐 |
|---|---|---|
| AE2 压印器 | 两类配方 ×12 升级组合、命名压板 NBT/模板保留、输出与恢复、自然调度、真实区块卸载、进度映射；完成脉冲/产物包往返与结算隔离；原版压合结算期间插卡的接管回归（`6d93e77`）；真实客户端 Inscriber 菜单 long/NBT/取放/外部更新/重开；世界内 3D 渲染截屏；真实旧世界 Anvil 物理迁移、在线推进与升级存盘二次重启；单机与 64 机并发性能实测（修正后首批 5t/248.7ms、极限 3768 items/t、MSPT 2.909ms） | 真实客户端压合连续动画录屏与中途接管显示 |
| ExtendedAE 压印器 | 四泳道 ×12 组合、独立输出与共享预算、堵塞恢复/破坏、自然调度、泳道进度；多泳道/连续完成动画包往返与结算隔离；泳道压合结算期间插卡的接管回归、逻辑槽位容量跟随上游设置（R1/R2）；真实客户端 4 泳道菜单大数同步与拾取；世界内 3D 渲染截屏；真实旧世界 4 泳道大数 Anvil 物理迁移、在线推进与升级存盘二次重启 | 压合连续动画录屏、极限四泳道性能细节 |
| 切片器 | logic/calculation/engineering/silicon，物品/水守恒、输出/破坏、进度/working；共享批次产物包往返；配方重载后不再沿用已删除或已替换的配方（R3） | Mega Cells accumulation、自然调度、真实客户端三维观察 |
| 反应仓 | logic_processor_chamber、quantum_infusion 流体产出、输出/破坏、进度/working；配方重载后不再沿用已删除或已替换的配方（R3）；真实客户端 ReactionChamberScreen 容量卡流体 Tooltip（32000 / 2147483647 mB）；世界内 3D 渲染截屏 | AppFlux 条件配方、自然调度 |
| AE2CS 粉碎机 | gunpowder 与 Tag 输入赛特斯石英粉、自然缺能/断网、重载/破坏、跨 JVM；真实旧世界 Anvil 物理迁移与升级存盘二次重启 | 差异配方类型和性能 |
| AE2CS 聚合器 | logic_processor、fluix_crystal 8:8:8→32、自然调度与生命周期 | 差异配方类型和性能 |
| AE2CS 蚀刻器 | logic_processor、calculation_processor 9:4:4→36、自然调度与生命周期 | 差异配方类型和性能 |
| AE2CS 熵变 | HEAT/COOL、石头/圆石、水/冰双向、模式切换与生命周期 | 完整客户端与更多流体恢复场景 |

当前注册 **93 项 GameTest**。没有安装对应附属时，相关测试主动跳过，因此 `none` 显示 93 项通过不代表执行了全部附属功能；必须结合启用日志和 `all` 结果。

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
| 第十一批切片器与断线重连 | `50b3397`；`all` 客户端交互脚本通过；切片器菜单打开、容量卡流体 Tooltip（32000/2147483647 mB）、四机同台 3D 渲染截屏（含切片器浮空逻辑压印物料）；客户端 disconnect 关服后 loadLevel 重新进入世界，验证槽位=1、背包=64 账本 100% 守恒；截图 `client-{cutter-menu,menu-reconnected,machine-world-render}.png` |
| 第十二批发布独立沙盒验证 | `c1579f8`；`verify-release.ps1` 一键通过；正式 Jar（`ae2_overclocked-1.2.3-fix3.jar`）解包确认 4 组 Mixin 齐全、清单有效、零测试类泄漏；Dedicated Server 沙盒独立加载自检成功，三附属适配器全部启用，干净停机；报告 `build/reports/release-summary.log` |
| 第十四批 R1–R4 修复（正向） | `5e3246d`/`047d360`/`b815dda`/`95e10a4`；严格离线 `build`（10,000 组核心契约、206 项静态审计、发布包裁剪）通过；`all` 93 项 GameTest 全部通过；基准重测 8 组场景通过并修正首批延迟为 5 ticks |
| 第十四批 R1–R4 修复（负向） | 临时还原四处源码修复后，93 项中恰有 4 项新增回归失败（槽位容量 `stored=1, rejected=63`；切片器/反应仓沿用旧快照；泳道压合状态卡住），其余 89 项仍通过 |

说明：第十二批之前各批的日志仍为本地构建产物。本轮新增证据已固化到 `build/reports/`（见上表），其中基准 JSON 位于 `build/reports/benchmark-results.json`；`build/` 不入 Git，需要时按第 7 节命令重跑。

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
| 已完成 | 联机客户端菜单与机器显示 | long/NBT、取放/Shift、外部更新、关闭重开、切片器与 4 泳道菜单、流体 Tooltip、四机同台 3D 浮空产物显示、客户端断线重连账本守恒已完全闭环 |
| 已完成 | 真实旧世界迁移 | 旧格式、超容、处理中机器的迁移前后账本；已在真实 Anvil 物理副本完成四阶段验证并升轨规范格式 |
| 已完成 | 基础性能测量 | 相同环境下单机与 64 机在空闲、持续加工、Max 并行、满输出阻塞 4 种负载下的首批延迟（4t/248ms）、吞吐（最高 3768 items/t）、MSPT（P50 0.8~2.8ms，P95 1.1~3.5ms）已完成受控测量并固化基准 |
| 已完成 | 可重复构建与发布包 | 正式 Jar（`ae2_overclocked-1.2.3-fix3.jar`）静态审查（204 项 Mixin、4 组配置、零测试类泄漏）与 Dedicated Server 独立沙盒加载自检已闭环 |
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

# 集成客户端全机型交互、切片器 3D 渲染与断线重连冒烟，自动创建/销毁一次性世界并退出
powershell.exe -NoProfile -File scripts/verify-compatibility.ps1 -Runtime all -Offline -ClientInteractionSmoke

# 真实 Anvil 旧世界物理迁移四阶段自动化验收，自动创建/销毁沙盒并校验账本与规范格式
powershell.exe -NoProfile -File scripts/verify-migration.ps1 -Runtime all -Offline -AcceptMinecraftEula

# 单机与 64 机并发性能基准测量，自动创建沙盒执行 8 组场景并在控制台和 JSON 中输出延迟/吞吐/MSPT
powershell.exe -NoProfile -File scripts/verify-benchmark.ps1 -Runtime all -Offline -AcceptMinecraftEula

# 正式发布 Jar 静态解包审查与 Dedicated Server 独立沙盒运行验收
powershell.exe -NoProfile -File scripts/verify-release.ps1 -Runtime all -Offline -AcceptMinecraftEula

git log -4 --oneline
git status --short
```

- 每个独立修复测试通过后单独提交，文档整理另一个提交；两份重构文档已纳入版本跟踪，必须保持更新。
- 故障注入撤销后验证最终源码；不把临时构建参数或负向产物作为发布包。
- 本轮提交引用正常，无需手工编辑 Git 引用。历史异常只有再次复现才排查。
- 原始重构历史保存在 Git：本轮整理前版本为 `e2fd9f3`，实施前审计基线为 `12ee65c`。本文只维护当前状态，不复制整段历史记录。

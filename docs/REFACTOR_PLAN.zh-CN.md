# AE2 Overclocked 全面重构设计

> 文档状态：实施前设计稿  
> 审计基线：仓库 `12ee65c`，Mod `1.2.3-fix3`  
> 版本核对日期：2026-09-10  
> 目标平台：Minecraft 1.20.1 + Forge；先完成稳定重构，再单独评估 1.21.1/NeoForge 移植

## 实施跟进（2026-09-12）

**资源封存包 UI 定案**：仅支持物品，使用内部物品自身图标（保留 NBT 模型和颜色），不兼容旧流体包，不提供流体兜底图标。数量继续以 long 保存，渲染只使用数量为 1 的合法 ItemStack；服务端取出与回插仍为权威路径。

**用户定案：破坏机器时流体不掉落。** 罐内流体、未完成批次预留流体、已完成待输出流体均丢弃，不生成封存包；仅破坏操作适用，正常加工、降容、卸载和保存仍要求流体守恒。本条优先于下文原始设计中的破坏全资源无损要求。四台 AE2CS 混合物品真实破坏已补齐；all 离线 build 和 67 项 GameTest 通过。

本文件保留实施前审计与目标设计；当前状态以 [重构进度](REFACTOR_PROGRESS.zh-CN.md) 为准。生命周期验收已新增 AE2CS 三台物品机器的混合资源真实破坏场景（每路可见输入 3、槽内输出 130、已完成待输出 45），离线 ae2cs build 与 65 项 GameTest 通过。熵变可见槽物品回收随后已验证；真实区块卸载和资源包回收仍须继续。

## 1. 结论

AE2 Overclocked 是一个面向 AE2 后期自动化的升级卡 Mod。它为 AE2 及其附属 Mod 的单方块机器增加超大堆叠、能源缓存、并行处理、定时超频和高速网络搬运，并为超量物品补充存档、菜单及网络同步逻辑。

当前版本能成功编译，但不应直接在现有结构上继续添加机器兼容。现有 52 个 Java 文件约 7,089 行，其中 35 个是 Mixin 文件，约有 355 个反射操作点和 122 个异常捕获点。核心处理逻辑分别复制在 AE2 压印器、ExtendedAE 扩展压印器、电路切片器、AdvancedAE 反应仓和 AE2CS 机器中；三个最大的兼容 Mixin 分别达到 737、1,031 和 704 行。

重构的核心不是减少文件数量，而是把系统改成：

1. **稳定的领域内核**：升级状态、配方快照、约束计算、批处理计划和数值安全只实现一次。
2. **薄适配层**：每个外部 Mod 只负责把机器 API 转换成内核接口，不自行实现结算算法。
3. **持久化待处理输出**：材料、能量与产物不依赖不可回滚的“模拟后直接写网络”，区块卸载或网络满时不丢物、不复制。
4. **有上限的每 tick 工作预算**：Max 卡表示逻辑并行无上限，不表示允许一次服务器 tick 执行十亿次循环。
5. **局部协议**：彻底取消对 Minecraft 全局 `FriendlyByteBuf` 物品协议的修改。

在完成这些基础工作前，不建议仅通过调高倍率或更新依赖发布新版本。

## 2. 当前 Mod 的功能与代码归属

### 2.1 内容注册

| 功能 | 当前实现 | 实际行为 |
|---|---|---|
| 并行卡 | `ModItems`、`ParallelCard`、`ModUpgrades` | 注册 ×2、×8、×64、×1024、Max 五档；同机只允许一种并行卡 |
| 堆叠卡 | `CapacityCard`、容量类 Mixin | 将机器物品槽或流体槽上限提高至配置值，默认 `Integer.MAX_VALUE` |
| 超级能源卡 | `EnergyCard`、能源类 Mixin | 将机器内部缓存提高到默认 2,000,000,000 FE，按 2 FE = 1 AE 换算 |
| 超频卡 | `OverclockCard`、各机器 Overclock Mixin | 默认在 5 tick 内完成处理，并一次性或加速扣除配方能量 |
| 超速卡 | `SuperSpeedCard`、I/O Bus/Port Mixin | 放大 AE2/ExtendedAE 总线与 I/O Port 的单次吞吐，默认倍率 512 |
| 防误拆 | `MachineBreakProtection` | 内部物品总数超过阈值时，需要按 Shift 才能破坏机器 |
| 资源包 | `Ae2Overclocked` | 注册三套内置可选卡片材质 |

注册表 ID、物品 ID、配方 ID、翻译键和资源包 ID 都是已有世界/整合包接口，重构时必须保持不变。

### 2.2 机器兼容范围

| 外部组件 | 机器/部件 | 卡片功能 | 当前入口 |
|---|---|---|---|
| AE2 | 压印器 | 并行、堆叠、能源、超频、产物入网 | `MixinInscriberOverclock` 等 |
| AE2 | 输入/输出总线 | 超速 | `MixinIOBusPartSuperSpeed` |
| AE2 | I/O Port | 超速 | `MixinIOPortSuperSpeed` |
| ExtendedAE | 扩展压印器（4 线程） | 并行、堆叠、能源、超频、产物入网 | `MixinExInscriberThreadOverclock` |
| ExtendedAE | 电路切片器 | 并行、堆叠、能源、超频、物品/流体处理 | `MixinCircuitCutterOverclock` |
| ExtendedAE | 扩展总线与 I/O Port | 超速 | `MixinEx*SuperSpeed` |
| AdvancedAE | 反应仓 | 并行、堆叠、能源、超频、物品/流体入网 | `MixinReactionChamberOverclock` 等 |
| AE2 Crystal Science | 电路蚀刻器、晶能粉碎机、晶能聚合器、熵变反应器 | 并行、堆叠、能源、超频、产物入网 | `MixinAE2CS*` |

### 2.3 当前横切补丁

- `MixinInternalInventory` 全量覆盖 AE2 `InternalInventory.insertItem`。
- `MixinAppEngInternalInventory` 接管超量堆叠的槽位限制、提取和 NBT。
- `MixinAEBaseMenu`、`MixinAppEngSlot`、`MixinAEBaseScreen` 接管超量堆叠的交互与显示。
- `MixinConfigInventory`、`MixinConfigMenuInventory`、`MixinGenericStackInv` 修改 AE2 通用配置库存与通用栈库存。
- `MixinFriendlyByteBuf` 修改 Minecraft 全局 ItemStack 序列化协议。
- 三组机器网络 Mixin 在真实 ItemStack NBT 上临时写入 `ae2ocNetCount`。
- `OverstackingRegistry` 用 `WeakHashMap` 标记允许超量堆叠的 `GenericStackInv`。

这些横切补丁影响面远大于本 Mod 注册的机器，是当前兼容风险的主要来源。

## 3. 构建与版本基线

### 3.1 当前解析到的版本

| 组件 | 当前仓库 | 说明 |
|---|---:|---|
| Minecraft | 1.20.1 | 保持 |
| Forge | 47.4.10 | Forge 官方仍列为 1.20.1 Recommended |
| AE2 | 15.4.10 | 已是 1.20.1 Forge 最新稳定版 |
| ExtendedAE | 1.20-1.4.9 | 明显落后 |
| AdvancedAE | 1.3.2 | 落后 |
| AE2AddonLib | 1.0.3 | 落后 |
| AE2 Crystal Science | 未声明 | 有兼容代码，但构建和 `mods.toml` 都缺少依赖声明 |

执行 `gradlew clean build --warning-mode all` 成功，但没有测试源。构建产生 24 条警告，包括找不到若干混淆方法目标、多个公开 Mixin 目标用字符串声明、`@Shadow` 缺映射、`@Overwrite` 文档警告，以及 Forge 上下文 API 弃用。成功编译不能证明这些 Mixin 在生产运行时成功注入。

### 3.2 重构验证版本矩阵

以下是截至核对日同属 **Minecraft 1.20.1 + Forge** 的近期版本，不把 1.21.1/NeoForge 或 26.1.x 版本混入本轮：

| 组件 | 建议验证目标 | 发布策略 |
|---|---:|---|
| Forge | 47.4.10 与 47.4.23 | 47.4.10 为最低/推荐基线；47.4.23 为最新兼容性验证，不立即抬高最低版本 |
| AE2 | 15.4.10 | 必选、锁定 |
| ExtendedAE | 1.20-1.4.19 与 1.4.20 | 1.4.19 作为稳定基线；1.4.20 是 2026-09-09 刚发布的最新版本，先进入兼容 CI，验证通过后再成为发布基线 |
| AdvancedAE | 1.3.6 | 兼容测试目标 |
| AE2AddonLib | 1.0.4 | 仅在目标 AdvancedAE 的元数据确认需要时引入 |
| AE2 Crystal Science | 1.20.1-1.2.0 | 新增 `compileOnly` 和可选运行测试配置 |

版本来源：[Forge 1.20.1 下载页](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html)、[AE2 文件页](https://www.curseforge.com/minecraft/mc-mods/applied-energistics-2/files/all)、[ExtendedAE 文件页](https://www.curseforge.com/minecraft/mc-mods/ex-pattern-provider/files/all?page=1)、[AdvancedAE 文件页](https://www.curseforge.com/minecraft/mc-mods/advancedae/files/all?page=1&version=1.20.1)、[AE2AddonLib 文件页](https://www.curseforge.com/minecraft/mc-mods/ae2addonlib/files/all)、[AE2 Crystal Science 1.20.1-1.2.0](https://www.curseforge.com/minecraft/mc-mods/ae2-crystal-science/files/8324632)。

版本策略必须满足：

- 依赖版本集中在 `gradle.properties` 或 Version Catalog，禁止把 CurseForge file ID 散落在 `build.gradle`。
- 提交 Gradle dependency locking 与校验元数据；CI 不得静默漂移依赖。
- `implementation` 只用于真正必选的 AE2；可选 Mod 使用 `compileOnly`，测试运行配置单独加 `runtimeOnly`。
- 从每个目标 Jar 的 `mods.toml` 自动核对其传递依赖。当前 AdvancedAE 1.3.2 Jar 声明的是 `ae2wtlib`，而仓库手动加入的是 `ae2addonlib`，不能继续靠猜测维护。
- `mods.toml` 为 AE2 设置经过验证的范围，例如 `[15.4.10,15.5)`；可选附属也应写入真实最低版本，而不是 `[0,)`。
- 每次 Dependabot/Renovate 更新只提升一个外部 Mod，并运行完整兼容矩阵和基准。

## 4. 必须先处理的风险

### P0：可能卡服、丢数据、复制物品或破坏协议

1. **无界同步循环**：AE2CS 在单 tick 内按 `extraRounds` 循环，电路切片器也逐轮生成额外输出。Max 卡默认可达 `Integer.MAX_VALUE`。这会把“高吞吐”变成主线程长时间停顿，直接伤害 TPS。
2. **整数溢出**：压印器用 `singleOutput * parallel` 计算 `int` 总产量；超速工具在检查前先做 long 乘法；`GenericStackInv` 使用 `currentAmount + amount`。溢出可能产生负数、错误限制或物品损失。
3. **非事务式结算**：部分路径先扣电再检查/生成输出，部分路径先写 ME 网络再扣材料；模拟插入与真实插入之间没有可恢复的承诺。反射失败被吞掉后，状态可能只完成一半。
4. **处理中状态未持久化**：`pendingParallel`、超频计数和缓存配方只存在于 Mixin 字段。已扣电后卸载区块、重启或异常，可能丢失进度；不同路径也存在重复结算风险。
5. **全局网络协议改写**：`MixinFriendlyByteBuf` 改变所有 Mod 的 ItemStack 编解码。只要客户端/服务端组合、另一个核心 Mod 或某个自定义协议假定原版格式，就会发生包错位。当前只对 BiggerStacks 做单点禁用，无法覆盖所有冲突。
6. **容量卡拔除会销毁实物资源**：容量卡移除时直接把超量物品或流体截到原容量，多余部分永久消失。物品和流体必须改成无损降级；能源卡拔除后的超额能量则按本 Mod 的既定玩法直接截断，并作为明确的用户契约处理。
7. **“可选依赖”加载不安全**：若干 ExtendedAE/AdvancedAE 字符串目标 Mixin 没有统一 `@Pseudo` 和按 Mod ID 的加载门控；配置又是 `required: true`。缺少某个附属 Mod 时必须可靠地不加载其兼容类。

### P1：持续 TPS 与延迟风险

1. **热路径反射**：每 tick 重复查找字段、方法和类，并大量调用 `setAccessible`。三套机器代码复制同一查找逻辑，异常还是正常控制流。
2. **每 tick 重扫升级槽和禁用列表**：一次机器 tick 中会多次调用容量、并行、能源和超频检测；每次又可能递归解析 host、反射并线性扫描字符串配置。
3. **持续 `URGENT` 调度**：超频动画/等待期及 I/O 路径频繁要求 AE2 紧急 tick，即使本 tick 无法取得材料、能量或输出空间。
4. **每 tick 主动 flush 输出到 ME**：网络写入、模拟写入和服务查找会随机器数量线性放大；输出阻塞时仍可能不断重试。
5. **“提高单 tick 工作量”冒充性能优化**：总线和 I/O Port 把操作数直接乘 512，确实降低单批延迟，却可能让单 tick MSPT 暴涨。延迟和 TPS 必须分别度量并同时设预算。
6. **容量维护轮询**：部分机器在每次 tick 调整容量并遍历所有槽位做 clamp，本应是升级变化/配置重载时触发的工作。

### P2：维护性与兼容脆弱性

- 直接覆盖 `InternalInventory.insertItem` 和 AE2 I/O Port 的 `tickingRequest`，会遮蔽上游修复并与其他 Mixin 冲突。
- `MixinGenericStackInv.setStack` 对任意数量大于 64 的实例绕过限制，即使该库存从未注册，影响 AE2 和其他附属的全局行为。
- 异常大量被忽略，兼容失效时没有一次性告警、指标或安全降级。
- 临时网络同步方案会修改真实 ItemStack 的 NBT；若写包过程抛异常，TAIL 清理不保证执行。
- 多处用 `IActionSource.empty()` 写网络，绕开机器动作来源，不利于安全、审计及其他 Mod 的权限集成。
- 防误拆计数使用 `int`，总量可溢出，且支持机器列表与实际功能列表不一致。
- README 写“1 tick 完成”，配置默认实际为 5 tick；用户契约与代码不一致。
- 没有 GameTest、单元测试、集成测试、性能基线或 CI。

## 5. 目标架构

### 5.1 包与模块

建议单 Jar、多源码集/清晰包边界；只有当发布与依赖管理确实需要时再拆成多个 Jar。

```text
moakiee.ae2oc
├─ api
│  ├─ MachineAdapter
│  ├─ RecipeView
│  ├─ ResourcePort
│  └─ UpgradeProfile
├─ core
│  ├─ planning       # 并行约束、批次计划、预算
│  ├─ execution      # 状态机、提交、恢复
│  ├─ quantity       # long 数量、饱和运算、单位换算
│  └─ observability  # 计时、计数器、诊断命令
├─ platform/forge    # 注册、配置、网络、事件
├─ compat/ae2
├─ compat/extendedae
├─ compat/advancedae
├─ compat/ae2cs
├─ client            # 显示和菜单同步
└─ migration         # 旧 NBT/配置迁移
```

外部 Mod 类型只能出现在对应 `compat/*`。`core` 只能依赖自有接口和不可变值对象。每个兼容包拥有独立 Mixin JSON，并由插件按 Mod ID、版本范围和目标签名门控；核心 Mixin 加载失败应阻止启动，单个可选兼容失败应禁用该适配并输出一次明确告警。

### 5.2 机器适配接口

建议的最小接口：

```java
interface MachineAdapter {
    MachineIdentity identity();
    UpgradeProfile upgrades();
    Optional<RecipeView> activeRecipe();
    ConstraintSnapshot snapshot(RecipeView recipe);
    CommitResult commit(BatchPlan plan, PendingOutputBuffer outputBuffer);
    void requestWakeup(WakeupReason reason);
}
```

适配器不得暴露外部机器私有字段给核心。若外部 Mod 没有公开 API，优先用 Accessor/Invoker Mixin 在加载期绑定；不得在每 tick 通过字段名做反射搜索。实在需要版本适配时，在初始化阶段解析并缓存 `MethodHandle`，验证签名后选择对应版本实现，失败即关闭该 compat，而不是每 tick 捕获异常。

### 5.3 升级状态缓存

引入每台机器的 `UpgradeProfile`：

```text
parallelLimit, processTicks, capacityLimit,
energyCapacity, ioRate, enabledFeatureBits, revision
```

只在以下事件重新计算：升级库存变化、配置重载、机器载入、目标 Mod 注册变化。tick 热路径只读取不可变快照。`disabledMachineIds` 在配置加载时解析成 `Set<ResourceLocation>`；机器的 block ID 在构造/载入时缓存，禁止 tick 中反射和字符串小写转换。

### 5.4 批处理状态机

统一状态：

```text
IDLE → PLANNED → PROCESSING → COMMITTING → DRAINING_OUTPUT → IDLE
                    ↘ BLOCKED_ENERGY / BLOCKED_OUTPUT ↗
```

`BatchPlan` 至少持久化：配方 ID/指纹、计划次数、已执行次数、预计输入、预计能量、完成 tick、输出缓冲和格式版本。区块卸载后恢复同一批次，不重新免费生成，也不重复收费。

安全提交顺序：

1. 从稳定快照计算材料、能源、本地待输出容量和卡片上限。
2. 对所有输入执行 exact simulate；任一输入不足则不提交。
3. 为产物确认 **本机持久化输出缓冲** 容量；ME 网络只作为异步排出目标，不能是完成配方的唯一落点。
4. 在服务器线程提交 exact 输入提取；若返回量不等，立即把已取资源放回本机恢复槽并中止。
5. exact 扣能；失败则恢复输入并中止。
6. 生成产物到持久化缓冲，记录状态并 `setChanged/saveChanges`。
7. 之后按 I/O 预算将缓冲排入 ME；网络断开或已满时保留缓冲。

随机产物必须为每个批次保存种子或逐批生成结果，不能用“单次结果 × 并行数”改变概率语义。容器物品、配方剩余物、NBT、耐久工具、流体和多输出都必须进入统一资源账本。

## 6. 数值与大堆叠重构

### 6.1 数量类型

- 内核物品/流体数量统一使用非负 `long`；ItemStack 只作为物品身份/NBT 的边界对象。
- 提供 `SaturatedMath.addNonNegative`、`multiplyNonNegative`、`floorDivToLong`，禁止裸 `a + b` 或 `a * b` 处理数量。
- 能量计算继续使用 AE2 的 `double` 边界，但计划内部检查 `isFinite`、非负和 epsilon；FE/AE 换算集中到一个单位类型。
- `Max` 用 `OptionalLong.empty()` 或专用 `UNBOUNDED` 表示，不用 `Integer.MAX_VALUE` 冒充业务无上限。

### 6.2 大堆叠存储

不要让常规 Minecraft `ItemStack` 长期持有超出原版栈限制的数量。机器大槽改成 `AEKey + long amount` 的逻辑槽，复用 AE2 的 GenericStack 思路，但只在本 Mod 管理的机器端口生效。

客户端菜单发送：槽位索引、AEKey/物品 NBT、VarLong 数量、revision。客户端渲染一个只用于图标的合法 ItemStack，数量文字来自 long 字段。点击、Shift 点击和滚轮操作发送意图与数量，由服务端校验后执行；不再覆盖 `AEBaseMenu.quickMoveStack`、`AppEngSlot` 或全局 ItemStack 编解码。

旧世界迁移读取以下格式：原版 Count、`ae2ocCount`、遗留 `ae2ocNetCount`。迁移成功后写入带 `dataVersion` 的新逻辑槽；保留至少一个发布周期的旧格式读取器，写入只使用新格式。

### 6.3 拔卡策略

拔掉容量卡后不能截断资源。采用“保留但冻结”语义：

- 已超过基础容量的库存仍保留并可向外提取，但不能继续插入。
- 数量降回基础容量后自动恢复普通状态。
- GUI 显示醒目的 over-capacity 状态。
- 破坏机器时所有资源使用 long 安全的分页掉落、直接入玩家/ME 或持久化掉落容器；绝不能一次构造十亿数量的 ItemStack。

能源采用不同语义：拔掉超级能源卡时，内部能量上限立即恢复为机器基础值，超出新上限的能量直接截断，不建立 overflow 字段，也不返还到 ME 网络。该操作只在升级库存发生变化时执行一次，随后立即标记脏数据并同步客户端；不得为了维持上限而每 tick 重复写入。Tooltip、README 和配置说明必须明确提示“拔卡会损失超额能量”。

## 7. 延迟与 TPS 设计

### 7.1 两类指标不能混为一谈

- **处理延迟**：从配方满足条件到首批产物可用的 tick 数。
- **服务器成本**：单 tick MSPT、P95/P99 tick 时间、机器 tick CPU、自定义分配率和网络调用次数。

超频卡只承诺首批延迟，例如配置的 5 tick；并行卡决定总工作量；超速卡决定搬运速率。任何卡都不能绕过服务器预算。

### 7.2 工作预算与背压

新增配置，建议安全默认值：

```toml
[performance]
maxCraftBatchesPerMachineTick = 64
maxRecipeOperationsPerMachineTick = 4096
maxTransferKeysPerMachineTick = 64
maxTransferAmountPerMachineTick = 1048576
blockedRetryMinTicks = 5
blockedRetryMaxTicks = 100
```

原则：

- 尽量以批量 long 数量一次提交，禁止按并行次数循环。
- 无法批量的外部配方分帧执行，保存 `remainingOperations`，每 tick 不超过预算。
- 材料/能量不足使用较慢重试；库存、网络或升级变化事件到来时立即唤醒。
- 输出已满采用指数退避，避免每 tick 对 ME 做相同的失败模拟。
- 只有实际取得进展时返回 `URGENT`；阻塞返回 `SLOWER/IDLE/SLEEP`。
- 可选的全局每维度 token budget 防止数千台机器同时吃满各自上限；预算以操作数定义，不能用墙钟时间影响游戏确定性。

### 7.3 超速 I/O

删除 I/O Port 的完整 `@Overwrite`。优先寻找 AE2/ExtendedAE 的吞吐查询扩展点；没有扩展点时只修改局部常量/返回值并保留上游控制流。最终吞吐为：

```text
allowed = min(cardRate, perMachineBudget, remainingGlobalBudget, sourceAvailable, destinationSpace)
```

每个 tick 按 AEKey 批量搬运，不按单物品操作。缓存 grid/service/actionSource 到当前 node revision；网络重建后失效。对受保护网络使用机器真实 action source。

## 8. 各兼容层的具体重构

### 8.1 AE2 压印器

- 用 typed Accessor/Invoker 获取三侧库存、任务和动画字段，删除运行时反射。
- 把动画与工艺结算分离；动画只同步视觉状态，不决定是否扣料/产出。
- PRESS 模板是否消耗必须完全遵循 AE2 当前配方语义，不能简单把上下槽都按并行数扣除。
- 能耗从 AE2/配方 API 获取；禁止硬编码每份 2000 AE，除非这是明确配置并有迁移说明。
- 产物先进入本机 pending buffer，再异步入网。

### 8.2 ExtendedAE 扩展压印器

- 每个 `InscriberThread` 只保存线程状态，升级快照由 host 共享。
- 4 个线程共享机器/全局预算，不能每线程各拿完整 Max 预算。
- 对目标版本提供一个适配器实现；版本签名变化由加载期探测发现。

### 8.3 ExtendedAE 电路切片器

- 物品与流体都转换成统一 `ResourceAmount`；一次计算全部输入比例，不能只用最小槽数量近似。
- 多输出先整体检查 pending buffer，保证全有或全无。
- 删除 HEAD 取消整个 `tickingRequest` 的大段复制代码；只挂入状态机入口。

### 8.4 AdvancedAE 反应仓

- 将 1,031 行 Mixin 拆成不超过约 100 行的绑定层与可测试核心。
- 保留配方有效输入、物品/流体配比和多输出语义；测试混合输入、NBT 输入、概率输出和网络断开。
- 容量只在 upgrade/config/load 事件变化时更新，禁止每 tick clamp。
- UI 最大液位由本 Mod 同步包提供，不在每帧反射 GUI 私有字段。

### 8.5 AE2 Crystal Science

- 添加真实 `compileOnly` 依赖和 `mods.toml` 可选依赖。
- 分别为三物品输入、单物品输入和 GenericStack 输入实现适配器；不要再按类名后缀在一个方法中分支。
- 删除 `extraRounds` 逐轮反射循环；支持批量的配方用数学聚合，不支持的配方按预算分帧。
- `getEnergyPerTick` 只改变进度速率，不得与额外轮次扣能形成双重收费。

### 8.6 容量、能源、菜单和防误拆

- 删除对所有 AE2 库存生效的通用覆盖，只给已注册机器挂载大槽组件。
- `OverstackingRegistry` 替换为明确生命周期的机器组件引用，不依赖 WeakHashMap/GC。
- 防误拆使用 `long` 饱和求和，覆盖全部适配机器，并与 disabled 配置、创造模式和管理员策略保持一致。
- 配置改为启动时验证并生成不可变快照；非法 ID 给出一次带原值的告警。

## 9. 兼容与协议约束

### 9.1 必须保持的兼容契约

- 所有物品、配方、翻译与资源包 ID 不变。
- 旧配置键继续读取；新键缺失时生成默认值。变更默认行为必须写迁移说明。
- 已放置机器、旧 `ae2ocCount` 大堆叠、处理中机器均可加载且不丢资源。
- 客户端与服务端要求相同主协议版本；握手失败时给出清晰版本错误，不尝试猜测包格式。
- 未安装任意可选附属时，核心 Mod 仍能启动并工作。
- BiggerStacks、GTLCore 等已知会改库存/协议/吞吐的 Mod 需要独立组合测试；不再靠硬编码“发现一个就关一个全局 Mixin”。

### 9.2 Mixin 规则

- 禁止新的 `@Overwrite`，现有两个覆盖必须在第一阶段移除。
- 精确描述符优先；核心注入 `require = 1`，可选版本专用注入也应由加载门控保证后使用 `require = 1`。不要用 `require = 0` 静默隐藏失效。
- 一个 Mixin 只负责一个绑定目的；不得在注入类内实现配方算法。
- CI 对目标 Jar 运行 Mixin audit：类、方法描述符、字段、接口和注入数均必须匹配。
- 启动日志列出启用的 compat、目标版本和禁用原因。

## 10. 测试与性能验收

### 10.1 单元与性质测试

- 并行约束：卡片、材料、能源、输出、预算任一成为瓶颈。
- 饱和算术：0、1、边界值、`Integer.MAX_VALUE`、`Long.MAX_VALUE` 和溢出输入。
- 配方账本：多输入、多输出、流体、模板/催化剂、容器物品、NBT 和随机产物。
- 状态机：每个状态的保存/重载、取消、阻塞恢复和幂等提交。
- 基于性质的守恒验证：任意步骤异常后，输入 + 输出 + pending 的资源总量保持预期变化，能源只按已完成批次减少。

### 10.2 GameTest/集成矩阵

至少运行：

1. AE2 单独。
2. AE2 + ExtendedAE。
3. AE2 + AdvancedAE 及其元数据声明的完整依赖。
4. AE2 + AE2CS。
5. 全部附属一起。
6. 上述关键组合分别加 BiggerStacks；如可公开获取，再加 GTLCore。
7. 专用服务端启动、客户端连接、断线重连和版本不匹配。

每台机器、每张卡、每个档位覆盖：插卡、拔卡、材料不足、能源不足、输出满、ME 离线、区块卸载、服务器重启、机器破坏和配置热重载。使用固定世界夹具验证升级前后 NBT；能源卡拔除测试必须断言 `storedEnergy = min(拔卡前能量, 基础容量)`，物品和流体拔卡测试则必须断言总量不变。

### 10.3 基准场景

建立可重复的空世界：

- 1、64、256、1024 台空闲机器。
- 256 台持续处理机器，分别使用无卡、×64、×1024、Max。
- 128 个输入/输出总线与 32 个 I/O Port，目的网络为空、半满、已满三种状态。
- 输出网络每 20 tick 断开/恢复；每 200 tick 卸载/加载区块。
- 每轮预热 5 分钟、采样 10 分钟，固定 JVM、堆大小、视距和模组列表。

记录：总体 MSPT P50/P95/P99、目标机器 CPU 占比、每 tick 最大处理操作、产物首批延迟、吞吐/秒、分配率、GC 暂停、ME insert/extract 调用数和网络字节。使用 JFR 做实现级剖析，可辅以 Spark 生成服主可复现报告。

首版验收门槛：

- 目标环境稳定 20 TPS；P95 MSPT < 45 ms，P99 < 50 ms。
- 256 台 ×1024 场景相较旧版，目标机器 CPU/tick 至少降低 50%，且没有 >100 ms 的本 Mod单次 tick 峰值。
- 输出满的阻塞场景相比正常处理场景，本 Mod CPU 不超过其 10%。
- 超频首批完成延迟等于配置值 ±1 tick。
- Max 场景每台每 tick 执行量不超过预算，吞吐随更多 tick 平滑完成。
- 30 分钟压力测试及 100 次卸载/重启循环中，物品/流体守恒，重复和丢失均为 0。
- 无卡时行为与上游相同；基准 CPU 回归不超过 2%，网络包大小不无故增长。

阈值应在第一阶段测得旧版真实数据后校准，但“无丢失/无复制/无界循环为零”不可放宽。

## 11. 分阶段实施计划

### 阶段 0：冻结行为与建立基线

- 给当前 Jar、依赖 Jar、测试世界和配置计算哈希并归档。
- 补齐旧格式 NBT 夹具与每台机器的功能行为表。
- 跑上述基准，保存 JFR/Spark、日志和结果 JSON。
- 修正文档与代码不一致的 1 tick/5 tick 描述。

完成条件：可以量化回答“旧版产多少、耗多少电、何时卡顿、何处丢数据”。

### 阶段 1：构建和兼容隔离

- 集中版本、锁依赖、添加 AE2CS，按真实元数据修复 AdvancedAE 依赖。
- 拆分四个 Mixin 配置并实现按 Mod/版本门控。
- 添加 CI：build、Mixin audit、核心单测、四类运行组合、专服启动。
- 把反射解析移到加载期，先保持行为不变。

完成条件：任意可选 Mod 缺失都能启动；目标版本签名不匹配会明确失败/禁用。

### 阶段 2：核心规划器、状态机与数值安全

- 实现 quantity、ledger、constraint planner、batch budget 和持久化状态机。
- 为所有溢出点加边界测试。
- 先接入 AE2 压印器作为参考实现。

完成条件：AE2 压印器不使用热路径反射，无卡行为等价，所有故障注入测试守恒。

### 阶段 3：大堆叠与网络协议

- 引入逻辑大槽、专用同步包和服务端权威菜单操作。
- 实现旧 `ae2ocCount`/`ae2ocNetCount` 迁移。
- 移除 `MixinFriendlyByteBuf`、`MixinInternalInventory`、全局菜单/库存覆盖。
- 实现物品/流体拔卡冻结、安全破坏/掉落，以及能源卡拔除时的一次性超额能量截断。

完成条件：原版 ItemStack 网络协议完全未修改；与 BiggerStacks 共存；升级/降级不丢物。

### 阶段 4：逐个迁移附属机器

按 ExtendedAE 压印器 → 电路切片器 → AdvancedAE 反应仓 → AE2CS 三类处理模型的顺序迁移。每迁移一类就删除对应旧算法，不允许新旧两条结算链长期并存。

完成条件：所有适配器共享同一 planner/executor；兼容层只有绑定和类型转换。

### 阶段 5：I/O、调度与性能

- 移除 I/O Port `@Overwrite`。
- 接入机器/全局预算、批量转移、失败退避和事件唤醒。
- 根据基准调整安全默认值，记录吞吐与延迟权衡。

完成条件：达到第 10.3 节门槛，Max 卡不会造成 tick 尖峰。

### 阶段 6：发布与迁移

- 在旧世界副本执行自动迁移和回滚演练。
- 发布候选版至少覆盖一个完整整合包测试周期。
- 发布说明列出目标版本矩阵、配置新增项、备份要求及已知冲突。
- 保留前一稳定 Jar 和只读迁移诊断命令；不要承诺新格式世界可由旧 Jar 打开。

## 12. 建议的提交拆分

每个提交都应可编译、可测试，建议顺序：

1. `build: lock 1.20.1 dependency matrix and add compat test runs`
2. `test: capture legacy nbt and machine behavior fixtures`
3. `core: add saturated quantities and batch planner`
4. `core: add persisted processing state and pending outputs`
5. `compat(ae2): migrate inscriber to adapter`
6. `storage: replace oversized ItemStack with logical long slots`
7. `network: add versioned big-slot sync and remove global buffer mixin`
8. `compat(extendedae): migrate machines and I/O`
9. `compat(advancedae): migrate reaction chamber`
10. `compat(ae2cs): add declared dependency and typed adapters`
11. `perf: add budgets, backoff, metrics and benchmark harness`
12. `cleanup: delete legacy mixins and reflection helpers`
13. `release: migration notes, compatibility matrix and RC`

不要把依赖大升级、核心算法替换、NBT 迁移和全部兼容层改写塞入同一个提交；这会使资源异常无法二分定位。

## 13. 完成定义

全面重构只有同时满足以下条件才算完成：

- 功能表中每个机器/卡片组合都有自动化测试。
- 所有旧世界夹具可无损迁移，资源守恒验证通过；唯一允许的主动减量是用户拔除能源卡时按契约截断的超额能量。
- 没有全局 FriendlyByteBuf、InternalInventory 或 AEBaseMenu 行为覆盖。
- 没有以并行次数为边界的无预算主线程循环。
- tick 热路径无反射查找、无异常控制流、无重复配置解析。
- 可选依赖真正可选，加载门控与目标版本有自动审计。
- 依赖锁定到经验证的近期 1.20.1 版本，且发布页给出精确矩阵。
- 延迟/TPS 指标达到第 10.3 节门槛，并附可复现基准数据。
- 编译零警告（或对不可消除的上游警告有显式白名单），测试和专服 smoke test 全绿。
- README、配置注释、游戏内 tooltip 和实际行为一致。

## 14. 不在本轮内的事项

- 不在同一分支同时迁移 Minecraft 1.21.1/NeoForge。其 AE2 API、数据组件、加载器和附属版本都会扩大变量，应在 1.20.1 重构稳定后，从领域内核开独立平台适配分支。
- 不为了追求纸面吞吐取消工作预算。
- 不使用新的全局协议 Mixin 解决单个机器的大数量显示问题。
- 不在没有备份和迁移报告的真实服务器世界上直接运行首个重构构建。

# AE2 Overclocked

面向 AE2 后期自动化场景的功能增强模组，重点强化单方块处理机器的吞吐、容量和能量上限。

## 主要功能

- 并行卡体系：×2 / ×8 / ×64 / ×1024 / Max
- 堆叠卡：提升机器内部物品槽位上限
- 超级能源卡：提升机器内部能量缓存上限
- 超频卡：默认在付清能量后经过 5 次处理器推进完成批次，可配置；实际延迟还受调度、退避与输出空间影响。产物可直接输出至 ME 网络
- 超速卡：提升 I/O 端口与输入输出总线吞吐（AE2 / ExtendedAE）
- 并行卡互斥：同一机器仅允许安装一种并行卡

## 支持机器

- AE2 压印器
- ExtendedAE 扩展压印器
- ExtendedAE 电路切片器
- AdvancedAE 反应仓
- AE2 Crystal Science 电路蚀刻器（Circuit Etcher）
- AE2 Crystal Science 晶能粉碎机（Crystal Pulverizer）
- AE2 Crystal Science 晶能聚合器（Crystal Aggregator）
- AE2 Crystal Science 熵变反应器（Entropy Variation Reaction Chamber）

## 依赖与兼容

- 当前开发基线：Minecraft 1.20.1、Forge 47.4.10
- 必选依赖：AE2 (`ae2`) 15.4.10
- 可选兼容：ExtendedAE (`expatternprovider`) 1.20-1.4.9-forge
- 可选兼容：AdvancedAE (`advanced_ae`) 1.3.2-1.20.1
- 可选兼容：AE2 Crystal Science (`ae2cs`) 1.2.0

本重构分支的 RC 范围仅接受上述已验证 Mod 版本。可选附属可以不安装；安装其他版本会被加载器明确拒绝，扩展范围前需重新验证。当前尚未完成正式发布验收，详见 [重构进度](docs/REFACTOR_PROGRESS.zh-CN.md) 和 [设计与验收计划](docs/REFACTOR_PLAN.zh-CN.md)。

## 库存与菜单行为

- 拔容量卡保留超容物品和流体，可取出但不能继续增加；拔能源卡会损失超过基础容量的能量。
- 破坏机器返回物品，丢弃罐内与批次流体。封存包仅支持物品，可分批取出，不兼容历史流体包。
- 受管菜单支持普通取放、合法物品交换和 Shift 入机；原内容超过一个合法光标栈时拒绝交换。数字键/副手交换和拖拽禁用，滚轮不提供搬运。
- 共享批次的进度条表示付款比例；付清后的剩余处理期间保持满格，完成排空后归零。压合动画和完整联机菜单仍在验收范围内。

## 配置文件

- 配置文件类型：Forge Common Config
- 文件位置：`config/ae2_overclocked-common.toml`
- 首次启动游戏后会自动生成；未手动修改时使用本 Mod 当前默认值

### 可配置项

- `cards.capacityCardSlotLimit`
	- 说明：堆叠卡生效时的槽位上限
	- 默认：`2147483647`

- `cards.superEnergyCardBufferFE`
	- 说明：超级能源卡生效时的能量缓存上限（单位 `FE`）
	- 默认：`2000000000.0`（内部按 `1 AE = 2 FE` 换算）

- `cards.parallelCardMaxMultiplier`
	- 说明：并行卡 Max 的并行倍率
	- 默认：`2147483647`

- `cards.superSpeedCardMultiplier`
	- 说明：超速卡吞吐倍率
	- 默认：`512`

- `cards.overclockCardProcessTicks`
	- 说明：批次付清能量后所需的处理器推进次数，范围 1–200
	- 默认：`5`

`performance` 下的配方、待输出、搬运与退避预算始终限制 Max 卡的实际执行量；配置默认值及测量边界见 [设计计划](docs/REFACTOR_PLAN.zh-CN.md)。

- `protection.breakProtectionItemThreshold`
	- 说明：机器防误拆阈值；当机器内部物品总数超过该值时，必须按住 `Shift` 才能拆除
	- 默认：`1000`

- `machines.disabledMachineIds`
	- 说明：禁用指定机器的超频/并行/堆叠/能源效果（仅支持方块ID，`namespace:path`）
	- 示例：`["ae2:inscriber", "ae2cs:crystal_pulverizer"]`
	- 默认：`[]`

### 示例

```toml
[cards]
capacityCardSlotLimit = 2147483647
superEnergyCardBufferFE = 2000000000.0
parallelCardMaxMultiplier = 2147483647
superSpeedCardMultiplier = 512
overclockCardProcessTicks = 5

[protection]
breakProtectionItemThreshold = 1000

[machines]
disabledMachineIds = ["ae2:inscriber", "ae2cs:crystal_pulverizer"]
```

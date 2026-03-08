# AE2 Overclocked

面向 AE2 后期自动化场景的功能增强模组，重点强化单方块处理机器的吞吐、容量和能量上限。

## 主要功能

- 并行卡体系：×2 / ×8 / ×64 / ×1024 / Max
- 堆叠卡：提升机器内部物品槽位上限
- 超级能源卡：提升机器内部能量缓存上限
- 超频卡：将处理过程压缩到 1 tick，并可将产物直接输出至 ME 网络（在满足材料、能量、输出空间前提下）
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

- 必选依赖：AE2 (`ae2`)
- 可选兼容：ExtendedAE (`expatternprovider`)
- 可选兼容：AdvancedAE (`advanced_ae`)
- 可选兼容：AE2 Crystal Science (`ae2cs`)

## 配置文件

- 配置文件类型：Forge Common Config
- 文件位置：`config/ae2_overclocked-common.toml`
- 首次启动游戏后会自动生成；未手动修改时使用默认值（即当前原版模组行为）

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

[protection]
breakProtectionItemThreshold = 1000

[machines]
disabledMachineIds = ["ae2:inscriber", "ae2cs:crystal_pulverizer"]
```

## 声明与致谢

- 本项目中的超速卡相关思路与部分实现参考了 MakeAE2Better（作者：QiuYe）。
- 本项目包含基于 MakeAE2Better 的适配代码与资源，并在仓库中保留其许可证文本：`src/main/resources/LICENSE_MakeAE2Better.txt`。
- 特别感谢 MakeAE2Better 项目为 AE2 生态提供的实践与启发。

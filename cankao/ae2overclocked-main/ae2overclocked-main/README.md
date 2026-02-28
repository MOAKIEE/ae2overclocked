# AE2 Overclocked (1.20.1)

面向 AE2 后期自动化场景的功能增强模组，重点强化单方块处理机器的吞吐、容量和能量上限。

## 主要功能

- 并行卡体系：×2 / ×8 / ×64 / ×1024 / Max
- 堆叠卡：提升机器内部物品槽位上限
- 超级能源卡：提升机器内部能量缓存上限
- 超频卡：将处理过程压缩到 1 tick（在满足材料、能量、输出空间前提下）
- 并行卡互斥：同一机器仅允许安装一种并行卡

## 支持机器

- AE2 压印器
- ExtendedAE 扩展压印器
- ExtendedAE 电路切片器
- AdvancedAE 反应仓

## 依赖与兼容

- 必选依赖：AE2 (`ae2`)
- 可选兼容：ExtendedAE (`expatternprovider`)
- 可选兼容：AdvancedAE (`advanced_ae`)

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

- `protection.breakProtectionItemThreshold`
	- 说明：机器防误拆阈值；当机器内部物品总数超过该值时，必须按住 `Shift` 才能拆除
	- 默认：`1000`

### 示例

```toml
[cards]
capacityCardSlotLimit = 2147483647
superEnergyCardBufferFE = 2000000000.0
parallelCardMaxMultiplier = 2147483647

[protection]
breakProtectionItemThreshold = 1000
```

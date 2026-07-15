# ViaBedrock 自定义可消耗物品翻译设计

- 日期：2026-07-15
- 状态：设计已确认，待实现
- worktree：`/home/ec/workspace/worktrees/viabedrock-custom-consumables/ViaProxyWorkspace`
- 分支：ViaProxyWorkspace 与 ViaBedrock 均使用 `fix/custom-consumable-items`
- 范围：ViaBedrock 源码、测试与本地构建；不修改 CodeFunCore/Nukkit/BedrockLoader，不推送、不部署、不重启服务

## 1. 问题与结论

CodeFunCore 的 `ECStackablePotion` 是 Bedrock 自定义物品。Nukkit 通过 Item Registry 组件声明 `minecraft:food` 与 32 tick 使用时长，资源包通过 `minecraft:use_animation: drink` 声明饮用动画，服务端在消费完成后负责扣除数量、生成空瓶并施加药水效果。

ViaBedrock 当前只从自定义物品定义中保留图标、显示名和护甲值。没有 BedrockLoader custom item 映射时，它使用 Java `minecraft:paper` 承载自定义模型，却没有添加 Java `CONSUMABLE` 数据组件；持续使用判断也只认识少数原版物品和静态 `minecraft:is_food` 标签。因此 Java 客户端看到正确贴图，却没有完整的饮用状态和消费完成时序。

修复应以 Bedrock 物品组件为权威来源，合并资源包与 Item Registry 中的使用语义，给 Java 载体添加客户端可理解的 `CONSUMABLE`，并让 ViaBedrock 的持续使用状态机读取同一份语义。

## 2. 目标

- 修复全部 16 种 `easecation:stackable_potion_*` 在 Java/ViaBedrock 下无法正常饮用的问题。
- 通用支持通过 Bedrock 组件声明的自定义食物和饮料，包括现有 `stackable_milk_bucket` 与 `spinach`。
- 保持自定义物品现有名称、lore、模型和最大堆叠数量。
- Java 客户端显示正确的 eat/drink 动画，并在配置的使用时长后完成消费协议时序。
- Nukkit 继续作为 gameplay 权威端，负责效果、营养、扣除数量和容器残留物。
- 缺失或损坏的组件安全降级，不中断登录或游戏连接。

## 3. 非目标

- 不在 ViaBedrock 中解释或复制 `custom_effects` NBT，也不在 Java 客户端本地施加药水效果。
- 不修改 CodeFunCore 的 `ECStackablePotion`、Nukkit `ItemEdible`、Food 注册或资源包文件。
- 不修改 BedrockLoader custom mapping snapshot schema。
- 不把所有带 `use_animation` 的物品都视为可消耗物；弓、弩、盾牌等继续走现有路径。
- 不重新设计原版 potion、milk bucket、food tag、bow/crossbow/trident 的翻译行为。
- 不在本次任务中发布分支、触发远端 CI、部署 bbdev/生产环境或操作服务。

## 4. 方案选择

### 4.1 采用：组件驱动翻译

解析并合并 Bedrock 自定义物品的 food、use duration 和 use animation，为 Java 载体生成 consumable 数据，并复用于 ViaBedrock 使用状态机。

优点：覆盖现有药水和未来自定义食物，不依赖 EaseCation identifier 前缀，数据来源与 Bedrock 服务端一致。

### 4.2 不采用：标识符特判

仅识别 `easecation:stackable_potion_*` 或维护自定义 allowlist。改动小，但新物品会重复出现相同缺口，也无法正确表达不同使用时长和动画。

### 4.3 不采用：映射为原版 Java 药水

原版 potion 自带饮用组件，但默认最大堆叠数、物品组件、客户端预测和模型语义与自定义堆叠药水冲突。继续使用当前 `paper + item_model` 载体并补充必要组件更安全。

## 5. 权威数据与合并规则

### 5.1 资源包定义

`items/*.item.json` 提供客户端表现信息。本修复读取：

- `minecraft:use_animation`：当前目标值为 `eat` 或 `drink`。

资源包中的 animation 只描述表现，不能单独证明物品可消耗。带 `bow`、`block` 或未知动画但没有 food 组件的物品不能进入消费路径。

### 5.2 Item Registry 网络定义

Bedrock `ITEM_REGISTRY` 中的组件是 gameplay 使用语义的权威来源。本修复读取：

- `components -> minecraft:food`：存在即表示该自定义物品可消耗。
- `components -> minecraft:use_duration`：使用时长，单位为 Bedrock tick。
- `components -> item_properties -> use_animation`：若协议/服务端提供，则作为网络侧 animation。
- `components -> item_properties -> use_duration`：若协议/服务端提供，则作为网络侧时长。

当前 CodeFunCore 的 stackable potion 以 legacy custom item 形式发送组件，因此即使 item entry 不是 DataDriven，只要它是非 `minecraft` 自定义物品且 component data 中存在 `components` compound，ViaBedrock 也必须读取。

### 5.3 合并优先级

同一 identifier 的定义按以下规则合并：

1. 网络定义提供的字段覆盖资源包同字段。
2. 网络定义缺少的字段保留资源包值，不能因为 Item Registry 到达而丢失 `drink` 动画。
3. `minecraft:food` 只由网络定义决定 consumable 身份。
4. food 存在但 animation 缺失或未知时降级为 `eat`。
5. food 存在但时长缺失或非法时使用 32 tick。

## 6. 组件设计

### 6.1 `ItemDefinitions.ItemUseDefinition`

为每个物品保存窄化后的使用语义：

- 是否可消耗。
- 使用时长 tick。
- Java 使用动画类型。
- 对应的通用使用音效。
- 是否显示消费粒子。

该对象不保存药水效果、营养、残留物或 cooldown。它提供 Java consumable 构建和协议状态机需要的只读信息。

### 6.2 `ItemDefinitions`

职责扩展为：

- 从资源包 JSON 解析 use animation。
- 从网络 NBT 解析 food、use duration 与可选 item properties。
- 将网络字段合并进已有资源包定义，而不是整条替换。
- 对损坏时长和未知 animation 做安全降级与按 identifier 去重警告。

现有 icon、display name 和 armor protection 行为必须保持。DataDriven 与 legacy custom item 都改为字段级合并：网络 gameplay 字段优先，资源包独有的表现字段保留；`networkDefinition` 在成功读取网络组件后保持为 true。Join 阶段不再预先删除同 identifier 的资源包定义。

### 6.3 `JoinPackets`

保留现有 DataDriven component item 解析，并额外允许以下 entry 进入 `ItemDefinitions.addFromNetworkTag()`：

- identifier 不是 `minecraft:*`。
- component data 非空且包含 `components` compound。

所有进入解析的 entry 都与已加载的资源包定义做字段级合并。该限制避免把所有原版 legacy registry entry 的行为一起放宽。

### 6.4 `ItemRewriter`

自定义物品仍按现有顺序选择 Java 载体：

1. 明确 Bedrock-to-Java mapping。
2. BedrockLoader 同步 custom item ID。
3. `minecraft:paper` fallback 加自定义模型。

当 `ItemUseDefinition` 表示 consumable 时，在最终 Java item 上附加 `StructuredDataKey.CONSUMABLE1_21_2`：

- `consumeSeconds = useDurationTicks / 20F`。
- animation：`eat=1`、`drink=2`。
- sound：`minecraft:entity.generic.eat` 或 `minecraft:entity.generic.drink`。
- eat 显示粒子，drink 不显示粒子。
- consume effects 为空数组，由 Bedrock 服务端执行效果。

不添加 Java `FOOD` 或本地药水效果组件，避免 Java 端抢占 gameplay 权威。现有最大堆叠数量由载体和服务端库存同步保持。

### 6.5 `ExperimentalFeatures`

现有原版集合和静态 food tag 判断保留。对其他物品，增加查询 `ItemUseDefinition`：

- consumable 自定义物品属于 continuous use item。
- 完成阈值读取该物品的 use duration tick，不再对自定义物品固定写死 32。
- 达到阈值后使用现有 consumable completion transaction。
- 提前松开时仍发送 Release，不完成消费。

Item Rewriter 和状态机必须读取同一个定义，避免客户端动画时长与代理完成时长漂移。

## 7. 数据流

```text
Bedrock resource pack items/*.json
  -> use_animation
                         \
                          -> ItemDefinitions merge -> ItemUseDefinition
                         /
Bedrock ITEM_REGISTRY
  -> food + use_duration

ItemUseDefinition
  -> ItemRewriter adds Java CONSUMABLE to paper/custom carrier
  -> Java client starts eat/drink animation and sends use/release packets
  -> ExperimentalFeatures tracks Bedrock use state for configured ticks
  -> existing Bedrock consume completion transaction
  -> Nukkit applies effects, decrements stack and returns residue
  -> inventory update reconciles Java client
```

## 8. 校验与降级

- 有效 use duration 必须是有限、正数且不超过 72000 tick（1 小时）的数值。
- 缺失、零、负数、非数值或超过 72000 tick 时使用 32 tick。
- animation 字符串忽略大小写；只映射 `eat` 和 `drink`。
- food 存在但 animation 未识别时使用 eat 动画和音效。
- animation 存在但 food 不存在时不生成 consumable。
- 损坏组件最多按 identifier 每连接警告一次，不逐 tick、逐 slot 或逐包刷日志。
- 解析异常不离开 Item Registry 协议线程；该物品退化为现有非 consumable 翻译。

## 9. 自动化测试

### 9.1 `ItemDefinitionsTest`

- 从资源组件解析 eat/drink animation。
- 从 legacy custom item 网络 NBT 解析 food 与 use duration。
- 网络定义合并时保留资源包独有 animation。
- 网络 animation/duration 覆盖资源包同字段。
- 缺少 food 时不判定 consumable。
- food 存在但 animation 缺失时降级为 eat。
- duration 缺失、零、负数、错误类型和过大值降级为 32 tick。
- 损坏定义只警告一次。
- 现有 display name、icon 和 armor protection 测试继续通过。

### 9.2 Java item 数据测试

- paper fallback 保留原 identifier 对应名称、模型与 lore。
- drink 生成 animation type 2、1.6 秒、drink sound、无粒子、无本地效果。
- eat 生成 animation type 1、eat sound和消费粒子。
- 非 consumable 自定义物品不附加 `CONSUMABLE`。
- 自定义堆叠数量不因 consumable 注入变成 1。

### 9.3 使用状态测试

- 自定义 consumable 被识别为 continuous use item。
- 31 tick 不完成、32 tick 完成。
- 自定义非 32 tick 时长按定义完成。
- 提前 release 不消费。
- 原版 potion、milk、food、bow/crossbow/trident 行为不回归。

## 10. 本地验证

所有 Gradle 命令从隔离的 ViaProxyWorkspace 根目录执行。首次运行前确认 Java 21：

```bash
cd /home/ec/workspace/worktrees/viabedrock-custom-consumables/ViaProxyWorkspace
./gradlew --version
./gradlew :ViaBedrock:test :ViaBedrock:compileJava --rerun-tasks --no-daemon
./gradlew build --no-daemon
git -C ViaBedrock diff --check
```

如完整 workspace build 因主分支既有问题失败，需用未修改的基线执行同一命令对比后再归因。本任务不把既有失败算作修复回归。

## 11. 手动验收

| 场景 | 预期 |
| --- | --- |
| 单个堆叠治疗药水 | 右键显示 drink 动画，1.6 秒后扣除 1 个并获得效果 |
| 多个同类堆叠药水 | 每次只扣除 1 个，剩余堆叠仍可继续饮用 |
| 空腹/满饥饿值 | `can_always_eat` 语义下均可饮用 |
| 中途松开右键 | 不扣除、不施加效果 |
| 快速切换槽位 | 不消费错误槽位物品，库存由服务端校正 |
| stackable milk bucket | drink 动画与服务端效果正常 |
| spinach | eat 动画与服务端效果正常 |
| 普通自定义纸张/图标 | 仍不可食用 |
| 原版药水与食物 | 行为与修复前一致 |
| 旧版 Java 客户端 | 经 ViaVersion 降级后不崩溃、不导致断线 |

## 12. 完成条件

- `ECStackablePotion` 代表样例在自动化测试中具有 drink/32 tick consumable 语义。
- 资源包与网络定义合并不会丢失任一来源的有效字段。
- Java item 与 ViaBedrock 状态机读取同一份使用定义。
- 定向测试、编译、diff check 和可执行的 workspace 验证通过。
- 主工作区与其他 worktree 未被修改。
- 未推送、未部署、未重启任何服务。

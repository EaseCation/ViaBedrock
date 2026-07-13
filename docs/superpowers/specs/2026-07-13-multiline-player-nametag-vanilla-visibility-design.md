# ViaBedrock 玩家多行名牌原版可见性设计

- 日期：2026-07-13
- 状态：设计已确认，等待实现
- 范围：ViaBedrock 玩家多行名牌、自动化测试与本地构建
- 非范围：非玩家 `TextDisplay` 名牌、ViaBedrockUtility、发布、部署和服务操作

## 1. 结论

当前玩家多行名牌由一个玩家原生队伍名牌和若干虚拟盔甲架名牌组成。玩家本体已经正确接收 Java 潜行与隐身标志，但虚拟盔甲架始终使用 `SHARED_FLAGS=0x20` 和 `CUSTOM_NAME_VISIBLE=true`，不会继承宿主玩家状态，因此附加行在宿主潜行或隐身后仍然常亮。

修复只放在 ViaBedrock 的 `MultilineNametagTracker`。宿主元数据变化时立即同步潜行和隐身状态；每个客户端连接每 tick 仅对当前玩家多行名牌做轻量距离对账；只有状态变化或潜行玩家跨越 32 格边界时才发送盔甲架元数据。玩家底行继续交给原版玩家渲染器，非玩家 `TextDisplay` 路径保持不变。

## 2. 原版行为契约

整组多行名牌必须与原版 Java 单行玩家名牌一致：

| 玩家状态 | 原版表现 |
| --- | --- |
| 正常站立 | 最远约 64 格显示，可隔墙显示 |
| 潜行且不超过 32 格 | 显示为半透明，不可隔墙显示 |
| 潜行且超过 32 格 | 隐藏 |
| 隐身，观察者不是旁观者 | 隐藏 |
| 隐身，观察者是旁观者 | 忽略隐身，继续应用潜行和距离规则 |

距离边界采用原版比较口径：

```text
distanceSquared <= 32 * 32：潜行名牌显示
distanceSquared > 32 * 32：潜行名牌隐藏
```

正常状态不由追踪器重复实现 64 格裁剪。Java `EntityRenderer` 已统一执行 `64 * 64` 外层距离判断，虚拟盔甲架和玩家底行自然受到该判断约束。

## 3. 现状与根因

`MultilineNametagTracker` 对玩家使用以下结构：

- 玩家原生队伍名牌承担最下面一行。
- 每条额外行对应一个客户端虚拟盔甲架。
- 所有虚拟盔甲架通过 `SET_PASSENGERS` 骑乘宿主玩家。
- 盔甲架模型以 `SHARED_FLAGS=0x20` 隐藏。
- 名字以 `CUSTOM_NAME_VISIBLE=true` 强制显示。

宿主的 `ActorFlags.SNEAKING` 和 `ActorFlags.INVISIBLE` 已由 `EntityMetadataRewriter` 映射到玩家 Java 共享标志和姿态。缺口只在虚拟盔甲架：

- 状态判断只读取名字和 `NAMETAG_ALWAYS_SHOW`。
- 名字不变时，盔甲架更新方法会提前返回。
- 盔甲架没有同步宿主的潜行样式。
- 盔甲架没有在宿主隐身时关闭名字。
- `ArmorStandRenderer` 直接按 `CUSTOM_NAME_VISIBLE` 决定是否显示名字，不执行玩家的 32 格潜行距离判断。

## 4. 目标

- 玩家所有名牌行在正常、潜行和隐身状态下表现一致。
- 潜行附加行在 32 格内半透明且不可穿墙，在 32 格外隐藏。
- 隐身附加行对普通观察者隐藏，对旁观者按原版显示。
- 状态变化最多一个游戏 tick 内收敛，元数据变化应立即收敛。
- 名字增减行、隐身期间改名、维度切换和实体移除不产生闪现或残留。
- 稳定状态不产生每 tick 协议包。
- 普通 Java 客户端和装有 ViaBedrockUtility 的客户端均得到正确行为。

## 5. 非目标

- 不修改非玩家实体的多行 `TextDisplay` 名牌。
- 不修改单行玩家名牌路径。
- 不修改 ViaBedrockUtility 或增加客户端 Mixin。
- 不重新设计虚拟盔甲架的行距、缩放或乘客关系。
- 不改变 `NAMETAG_ALWAYS_SHOW`、名字空行裁剪或格式转换规则。
- 不在本次改动中重构整个多行名牌功能为 `FeatureModule`。
- 不实现 ViaBedrock 当前不存在的跨玩家队伍关系。
- 不发布、不推送、不部署、不重启服务。

## 6. 方案选择

### 6.1 采用：元数据事件加轻量 tick 对账

- 宿主元数据变化时立即重新计算状态。
- 每 tick 只检查已有玩家多行名牌的距离和观察者模式。
- 仅在状态发生变化时发送差异元数据。

该方案能覆盖本地移动、远端移动、服务端位置纠正、传送、骑乘和游戏模式变化，而不需要为每条位置路径增加专用回调。

### 6.2 不采用：完全事件驱动

完全事件驱动必须同时覆盖本地输入、远端移动、服务端纠正、传送、骑乘、重生和维度变化。漏掉任一路径都会让 32 格边界状态卡住，可靠性低于轻量对账。

### 6.3 不采用：ViaBedrock 与 VBU 联动

客户端方案需要稳定标识哪些盔甲架属于多行名牌，并要求 ViaProxy 与客户端整合包同步升级。它增加跨仓协议、Mixin 冲突和版本漂移风险，也不能修复未安装 VBU 的普通 Java 客户端。

### 6.4 不采用：本次同步重构 FeatureModule

将现有包处理器整体迁移到模块生命周期会同时影响玩家盔甲架和非玩家 `TextDisplay`，超出本缺陷所需范围。

## 7. 状态模型

使用无分配的四值枚举表达玩家附加行渲染状态：

```text
VISIBLE_NORMAL
VISIBLE_SNEAKING
HIDDEN_NORMAL
HIDDEN_SNEAKING
```

每个枚举值包含：

- `sharedFlags`：`0x20` 或 `0x22`。
- `nameVisible`：`true` 或 `false`。

`ArmorStandInfo` 保存 `lastRenderState`，用于差异更新。

纯状态函数接收：

- 宿主是否潜行。
- 宿主是否隐身。
- 当前观察者是否旁观者。
- 观察者到宿主的距离平方。

状态解析规则：

```text
sneakingStyle = hostSneaking

invisibleToViewer =
    hostInvisible
    && observerGameMode != SPECTATOR

visible =
    !invisibleToViewer
    && (!hostSneaking || distanceSquared <= 1024)
```

复合状态优先级：

```text
NAMETAG_ALWAYS_SHOW=false 或名字不再是多行
    -> 拆除附加行

否则，宿主隐身且观察者不是旁观者
    -> 隐藏附加行

否则，宿主潜行且距离大于 32 格
    -> 隐藏附加行

否则
    -> 显示附加行
```

隐藏状态仍保留正常或潜行样式。解除隐身后可以直接恢复正确样式，不依赖新的潜行元数据。

## 8. 组件与代码边界

### 8.1 `MultilineNametagTracker`

新增职责：

- 解析玩家附加行期望状态。
- 将状态差异应用到全部虚拟盔甲架。
- 每 tick 对玩家 `ArmorStandInfo` 做距离和观察者模式对账。
- 防御性清理已失去宿主的显示对象。

建议增加以下窄方法：

```text
resolvePlayerRenderState(...)
reconcilePlayerRenderState(...)
applyArmorStandRenderState(...)
sendArmorStandRenderStateUpdate(...)
tick()
```

`resolvePlayerRenderState` 保持纯计算，供单元测试直接覆盖。名字内容更新与渲染状态更新保持独立，但由同一入口按固定顺序协调。

### 8.2 `ArmorStandInfo`

新增最后已发送状态：

```text
PlayerNametagRenderState lastRenderState
```

新建显示时立即初始化。新增行直接使用当前状态，不能先以默认可见状态生成。

### 8.3 `MultilineNametagTickTask`

新增实验性 tick task，遵循现有 `ScriptDebugTextTickTask` 模式：

- 遍历 ViaVersion 当前连接。
- 获取该连接的 `MultilineNametagTracker`。
- 将 `tick()` 提交到连接自己的 Netty event loop。
- channel 不活跃时跳过。
- 不在平台调度线程直接读写连接状态。

### 8.4 `ExperimentalFeatures`

在 `registerTasks()` 中以 `1L` 周期注册 `MultilineNametagTickTask`。

### 8.5 明确不修改

- `EntityTracker`
- `PlayerEntity`
- `EntityMetadataRewriter`
- `JavaPassengerTracker`
- ViaBedrockUtility
- 非玩家 `TextDisplay` 路径

## 9. Java 元数据映射

虚拟盔甲架只需要控制两个字段：

| 状态 | `SHARED_FLAGS` | `CUSTOM_NAME_VISIBLE` |
| --- | ---: | ---: |
| 正常显示 | `0x20` | `true` |
| 潜行且在 32 格内 | `0x22` | `true` |
| 潜行且超过 32 格 | `0x22` | `false` |
| 隐身且观察者非旁观者 | 保持对应样式 | `false` |
| 隐身且观察者为旁观者 | 保持对应样式 | 按潜行距离规则 |

`0x20` 隐藏盔甲架模型；`0x02` 让盔甲架的名字进入原版潜行渲染样式。潜行时 `EntityRenderer.renderNameTag()` 使用半透明、非 `SEE_THROUGH` 的绘制方式，因此附加行会变为半透明且不能穿墙。

盔甲架渲染器不执行玩家的 32 格判断，所以 ViaBedrock 通过 `CUSTOM_NAME_VISIBLE` 补齐该距离规则。

每条附加行每次状态变化最多发送一个 `SET_ENTITY_DATA`：

- 仅样式变化时只包含 `SHARED_FLAGS`。
- 仅可见性变化时只包含 `CUSTOM_NAME_VISIBLE`。
- 两者同时变化时合并到同一个实体元数据列表。
- 名字未变化时不发送 `CUSTOM_NAME`。

Java `SET_ENTITY_DATA` 一次只能指向一个实体，因此有 `L` 条附加行时，状态边界最多产生 `L` 个包。

## 10. 数据流与包顺序

宿主收到 Bedrock `SET_ENTITY_DATA`：

```text
Bedrock entity data batch
  -> Entity 存储整批 Bedrock 元数据
  -> EntityMetadataRewriter 生成玩家本体 Java 元数据
  -> MultilineNametagTracker 读取完整最新状态
  -> 计算附加行期望状态
  -> 仅向变化的盔甲架发送差异 SET_ENTITY_DATA
  -> 玩家本体 Java 元数据包发送到客户端
```

整批 Bedrock 元数据先存储再翻译，因此 `RESERVED_0` 和 `RESERVED_092` 同包变化时不会读取到半旧 ActorFlags。

附加行和玩家底行属于不同实体，不能放入一个原子包。它们在同一连接 event loop 中顺序发送。进入潜行或隐身时附加行先收敛到更隐蔽状态，不产生名字泄露窗口；退出时虽存在同一网络批次内的实体先后顺序，但客户端通常在下一渲染帧统一处理。

## 11. 名字和行数变化

- 名字没变化不能阻止状态对账。
- 潜行或隐身变化不重发名字，不重建盔甲架。
- 新增附加行的首个元数据包直接包含正确的名字、共享标志和可见性。
- 隐身期间允许更新文字和增减行；新增行保持隐藏。
- 删除行先更新乘客集合，再发送 `REMOVE_ENTITIES`。
- 名字恢复单行时沿用现有拆除路径。
- 名字恢复多行时按当时状态重新创建。
- `NAMETAG_ALWAYS_SHOW=false` 时沿用现有拆除路径。

玩家最下面一行不接收额外队伍更新。它继续由玩家本体共享标志、ViaBedrock 现有队伍和原版玩家渲染器处理。

## 12. 生命周期与恢复

### 12.1 玩家生成

实体和初始 Bedrock 元数据进入 `EntityTracker` 后再创建多行名牌。创建时一次性读取潜行、隐身、观察者模式和距离。当前客户端玩家自身继续跳过。

### 12.2 正常 tick

- `displays` 为空时立即返回。
- 只处理 `ArmorStandInfo`，跳过非玩家 `TextDisplayInfo`。
- 找到宿主后重新计算状态。
- 状态与缓存相同则不发送包。

### 12.3 玩家移除

先清除该功能注册的虚拟乘客，再删除所有虚拟盔甲架和反向索引。tick 不再访问该宿主。

### 12.4 维度切换

保留现有 `clearAll()`。Java 客户端会清空旧维度实体，因此不发送冗余删除包。新维度玩家重新生成时不复用旧缓存。

### 12.5 断开连接

连接关闭后 `StoredObject` 随连接销毁。tick task 检查 channel active 状态，不跨连接持久化名牌状态。

### 12.6 防御性清理

- 找不到宿主实体时，清理对应虚拟实体和索引。
- 宿主不再是 `PlayerEntity` 时按损坏状态清理。
- 本地观察者或位置尚未初始化时，本 tick 不改变状态，等待下一 tick。
- 所有修改在同一连接 event loop 内进行，不与包处理器并发修改 `displays`。

## 13. 原版例外与语义边界

- 本地观察者为旁观者时，隐身宿主对其可见，但仍应用宿主潜行的样式和 32 格距离。
- 观察者进入或退出旁观者模式后，最多下一 tick 收敛。
- ViaBedrock 当前为每名玩家创建独立 `vb_<entityId>` 队伍，观察者与目标不存在同队 `seeFriendlyInvisibles` 路径，本次不另建队伍系统。
- `HIDDEN_WHEN_INVISIBLE` 和 `RENDERS_WHEN_INVISIBLE` 不参与本次判断。玩家本体当前以 `ActorFlags.INVISIBLE` 转换 Java 隐身，附加行应跟随玩家本体的实际 Java 语义。
- 不增加 32 格滞回区间。临界点行为与原版一致，玩家在边界反复移动时允许按原版切换。

## 14. 性能

设当前连接内有 `D` 个玩家多行名牌，每名玩家平均有 `L` 条附加行。

稳定状态每 tick：

```text
时间复杂度：O(D)
协议包数量：0
```

状态发生变化：

```text
时间复杂度：O(L)
协议包数量：每条附加行最多 1 个 SET_ENTITY_DATA
```

实现不得：

- 每 tick 重发名字或实体元数据。
- 每 tick 扫描全部世界实体。
- 在状态变化时重建盔甲架。
- 重发未变化的乘客关系。
- 计算距离平方根。
- 在 tick 热路径创建临时状态对象。
- 添加逐 tick INFO/WARN 日志。

## 15. 错误处理

- 正常缺少显示对象、名字隐藏和状态不变不记录日志。
- 孤儿显示对象优先安全清理，不让它持续刷异常。
- tick task 捕获不可恢复异常并沿用现有实验性任务的 `BedrockProtocol.kickForIllegalState` 行为，保存完整异常并中止异常连接，避免错误状态持续刷包。
- 单个连接的错误不能停止其他连接的 tick。

## 16. 自动化测试

新增 `MultilineNametagTrackerTest`，直接测试纯状态函数，不引入 Mockito 或完整协议连接。

必须覆盖：

1. 普通状态在近距离和远距离均为 `VISIBLE_NORMAL`。
2. 潜行且距离平方小于 1024 为 `VISIBLE_SNEAKING`。
3. 潜行且距离平方等于 1024 仍为 `VISIBLE_SNEAKING`。
4. 潜行且距离平方大于 1024 为 `HIDDEN_SNEAKING`。
5. 隐身且观察者不是旁观者为 `HIDDEN_NORMAL`。
6. 潜行加隐身为 `HIDDEN_SNEAKING`。
7. 隐身且观察者为旁观者、宿主未潜行为 `VISIBLE_NORMAL`。
8. 隐身且观察者为旁观者、宿主潜行并在 32 格内为 `VISIBLE_SNEAKING`。
9. 隐身且观察者为旁观者、宿主潜行并超过 32 格为 `HIDDEN_SNEAKING`。
10. 解除隐身但保持潜行时直接恢复对应距离状态。

实现阶段还应静态检查：

- 状态相同不构造或发送更新包。
- 同时改变样式与可见性时，每条附加行只有一个元数据包。
- 新增行使用当前状态，而不是默认可见状态。
- `TextDisplayInfo` 不进入玩家状态对账。

## 17. 本地构建验证

遵循复合仓库约定，只从 `/home/ec/workspace/ViaProxyWorkspace` 根目录运行 Gradle，并先确认 Java 21：

```bash
cd /home/ec/workspace/ViaProxyWorkspace
./gradlew --version
./gradlew :ViaBedrock:test :ViaBedrock:compileJava --rerun-tasks --no-daemon
./gradlew build --no-daemon
git -C ViaBedrock diff --check
```

本地构建只证明源码和复合依赖正确；不复制运行 jar，不推送，不触发远端 CI，不部署或重启服务。

## 18. 游戏内验收矩阵

至少使用一个观察者和一个拥有多行名牌的目标玩家：

| 场景 | 预期 |
| --- | --- |
| 目标站立、31 格 | 全部行正常显示 |
| 目标站立、33 格 | 全部行正常显示 |
| 目标站立、隔墙 | 全部行可透墙显示 |
| 目标潜行、31 格 | 全部行半透明、不可透墙 |
| 目标潜行、32 格 | 全部行仍显示 |
| 目标潜行、33 格 | 全部行隐藏 |
| 目标在 33 格退出潜行 | 全部行恢复 |
| 目标隐身、近距离 | 全部行隐藏 |
| 清除隐身 | 全部行恢复 |
| 潜行和隐身同时存在 | 全部行隐藏 |
| 解除隐身但仍潜行、31 格 | 恢复潜行样式 |
| 解除隐身但仍潜行、33 格 | 继续隐藏 |
| 观察者切换旁观者 | 可看见隐身目标的完整多行名牌 |
| 观察者退出旁观者 | 隐身目标全部行重新隐藏 |
| 隐身期间修改名字或增减行 | 不闪现；恢复后显示最新内容 |
| 目标切维度或离线 | 不残留盔甲架或名字 |
| 名字变回单行 | 回到原有玩家单行路径 |
| 非玩家多行实体名 | 行为完全不变 |

另使用两个观察者同时验证：一个位于潜行目标 31 格内，一个位于 33 格外。每条 ViaBedrock 连接独立维护虚拟实体，两个观察者应分别得到正确结果。

稳定状态抓包或调试统计中，不应每 tick 出现盔甲架 `SET_ENTITY_DATA`。

## 19. 回归检查

- 原有多行间距和缩放不变。
- 真实 Bedrock 骑乘乘客不被覆盖。
- 名字前后空行裁剪不变。
- `NAMETAG_ALWAYS_SHOW` 行为不变。
- 单行玩家名字不进入新状态机。
- 非玩家 `TextDisplay` 不进入新状态机。
- 玩家底行与附加行同时进入和退出潜行/隐身表现。
- 状态恢复不需要改名或重新生成实体。

## 20. 完成条件

- 所有新增和既有 ViaBedrock 测试通过。
- ViaProxyWorkspace 完整 build 通过。
- 纯状态函数覆盖全部正常、潜行、隐身、旁观者和边界组合。
- 稳定状态不产生周期性盔甲架元数据包。
- 游戏内验收确认所有行作为一个整体遵循原版。
- 单行玩家和非玩家 `TextDisplay` 行为无回归。
- 没有推送、远端 CI、部署或服务操作。

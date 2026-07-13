# ViaBedrock pre-PLAY inventory bootstrap 设计

- 日期：2026-07-13
- 状态：设计已批准，待实现
- 分支：`fix/defer-preplay-inventory`
- 范围：ViaBedrock 通用协议修复；不修改 Nukkit、Nemisys、ViaProxy 核心或 `/stuck`

## 1. 结论

当前 Bedrock 后端可能在 `START_GAME` 完成前发送 `PLAYER_HOTBAR` 和 `INVENTORY_SLOT`。ViaBedrock 的 `BedrockProtocol.transform()` 会把所有不在 pre-PLAY 白名单中的包直接丢弃，因此这些快捷栏更新不会进入 `InventoryPackets`，也不会写入 `InventoryTracker`。玩家真实库存仍保存在后端，稍后执行 `/stuck` 触发完整 `sendContents()` 后才恢复显示。

修复应在 ViaBedrock 中增加定向的 `InventoryBootstrapQueue`：当 Bedrock 库存包的解码前提未满足时保存原始 payload；`START_GAME`、Java PLAY 状态和 `ITEM_REGISTRY` 均就绪后，再让这些包按原顺序经过现有翻译链。该机制只处理库存启动包，不泛化为所有 pre-PLAY 包队列。

## 2. 运行证据

2026-07-13 的 bbdev `je-bridge/viaproxy-easecation-0-0` 日志两次出现相同序列：

```text
10:33:22.777 PLAYER_HOTBAR outside PLAY state. Ignoring it.
10:33:22.777 INVENTORY_SLOT outside PLAY state. Ignoring it.
10:33:23.233 Configuration finished! Switching to PLAY state

11:03:04.929 PLAYER_HOTBAR outside PLAY state. Ignoring it.
11:03:04.929 INVENTORY_SLOT outside PLAY state. Ignoring it.
11:03:05.364 Configuration finished! Switching to PLAY state
```

`ResendCommands` 的 `/stuck` 最后调用 `player.getInventory().sendContents(player)`。该命令执行时连接已经进入 PLAY，完整库存包能被正常翻译，因此物品重新出现。这证明问题是协议阶段丢包，不是后端库存或持久化数据丢失。

## 3. 目标

- pre-PLAY 到达的 `INVENTORY_CONTENT`、`INVENTORY_SLOT` 和 `PLAYER_HOTBAR` 不再被静默丢弃。
- 等真实 Bedrock runtime item registry 可用后，库存包才进入 `InventoryPackets`。
- 保留后端原始包序，不制造重复、乱序、幽灵物品或错误手持槽。
- 初次登录、Transfer 重连、Java reconfigure 和正常登录共用一套通用机制。
- 不依赖服务器品牌、Nukkit API、Nemisys 行为或 EaseCation 特判，可作为上游补丁。
- 队列有明确的内存、数量和等待时间上限，所有 Netty buffer 都有可证明的释放路径。

## 4. 非目标

- 不修改后端发包时机，也不在后端自动执行 `sendContents()`。
- 不修改 `/stuck`。
- 不改变 `InventoryPackets` 的物品、容器、NBT 或点击翻译语义。
- 不替换现有 `JoinGate`。
- 不缓存全部 pre-PLAY 包；`LEVEL_EVENT`、实体移动、声音和粒子仍保持现有行为。
- 不在首版对库存包做槽位合并、快照压缩或去重。
- 不新增用户配置项。

## 5. 方案比较

### 5.1 定向原始包屏障（采用）

保存特定 Bedrock 包的原始 payload，在解码依赖就绪后通过正常协议管线重放。

优点：范围小、保留现有翻译逻辑、可设置硬上限、容易验证，不需要提前理解动态 item ID。

### 5.2 库存语义快照（不采用）

提前解析 container、slot 和 item，合并成最终 Java 全量库存。pre-PLAY 阶段缺少 runtime item registry，这会迫使实现复制 Bedrock item codec 和库存语义，容易与正常路径分叉。

### 5.3 全量 pre-PLAY 包队列（不采用）

缓存所有当前会被丢弃的包。它会积累大量可能过期的世界事件和实体状态，扩大内存与重放副作用，不适合作为库存问题的上游修复。

## 6. 两级启动屏障

```text
Bedrock backend
  -> InventoryBootstrapQueue
       等待 Bedrock 包可正确解码：PLAY + ITEM_REGISTRY
  -> InventoryPackets
       使用现有 item/container 翻译
  -> JoinGate
       等待 Java LOGIN、玩家区块和 PLAYER_LOADED
  -> Java client
```

两个屏障职责不得混合：

- `InventoryBootstrapQueue` 解决 Bedrock 原始包是否可以正确解码。
- `JoinGate` 解决转换后的 Java 包是否可以安全发给客户端。

重放后的库存包必须继续经过 `JoinGate`，不能绕过它直接写客户端 channel。

## 7. 组件设计

### 7.1 `InventoryBootstrapQueue`

新增连接级 `StoredObject`：

`src/main/java/net/raphimc/viabedrock/protocol/storage/InventoryBootstrapQueue.java`

保存：

- 一个可选的 early `ITEM_REGISTRY` 原始包。
- 一个库存变更 FIFO，元素为包类型、原始 payload 和入队时间。
- 当前包数和总 payload 字节数。
- `registryReady`、`flushScheduled`、`flushing` 状态。
- Registry 等待 timeout task。

主要操作：

- `deferIfNeeded(packet, wrapper)`：在正常 transform 前识别并保存目标包。
- `onPlayReady()`：协议进入 PLAY 后尝试先处理 early Registry，再处理库存 FIFO。
- `onItemRegistryReady()`：Registry handler 成功安装新 `ItemRewriter` 后标记并调度重放。
- `tryFlush()`：仅在同一 Netty event loop 中执行。
- `onRemove()`：取消 task 并释放所有 payload。

生产代码可使用 package-private replay seam，让单元测试注入记录器；公共 API 不暴露测试概念。

### 7.2 `BedrockProtocol`

在 `init()` 中注册 queue。

在 `transform()` 的现有 pre-PLAY 拒绝逻辑之前执行 `deferIfNeeded()`。如果包已保存，立即取消本次处理，避免后续打印 `outside PLAY state. Ignoring it`。

拦截集合：

- `INVENTORY_CONTENT`
- `INVENTORY_SLOT`
- `PLAYER_HOTBAR`
- 仅在尚未具备 START_GAME 解码环境时暂存的 `ITEM_REGISTRY`

其他包完全沿用现有状态检查。

### 7.3 `JoinPackets`

`ITEM_REGISTRY` handler 只有在完整解析并安装新的 `ItemRewriter` 后才调用 `onItemRegistryReady()`。失败的 Registry 不能把 queue 标记为 ready。

`sendJavaConfigurationOutputs()` 将 `serverState` 设置为 PLAY 后调用 `onPlayReady()`。该位置同时覆盖初次 START_GAME 和 Java reconfigure。

### 7.4 `InventoryPackets` 与 `JoinGate`

不修改正常 handler。重放必须重新进入 `BedrockProtocol`，从而复用：

- runtime item ID 与 Java item mapping。
- 自定义物品、NBT、组件和资源包处理。
- `InventoryTracker`、容器槽位映射和手持槽处理。
- JoinGate 的客户端侧队列。

## 8. 状态与数据流

| Bedrock server state | Registry 状态 | 目标包行为 |
| --- | --- | --- |
| LOGIN/CONFIGURATION | 未就绪 | 保存原始库存包 |
| LOGIN/CONFIGURATION | ITEM_REGISTRY 提前到达 | 单独保存 Registry |
| PLAY | 未就绪 | 继续保存库存包 |
| PLAY | 已就绪 | 立即走正常翻译 |
| 任意 | 连接关闭 | 释放全部暂存数据 |

初始登录顺序：

```text
early INVENTORY_SLOT / PLAYER_HOTBAR
  -> inventory FIFO

START_GAME
  -> BlockStateRewriter、占位 ItemRewriter、JoinGate
  -> Java configuration outputs
  -> serverState = PLAY

ITEM_REGISTRY
  -> 安装真实 ItemRewriter
  -> registryReady
  -> 下一次 event-loop 任务重放 inventory FIFO
```

如果 Registry 自身早于 START_GAME，PLAY ready 后先重放 Registry。Registry handler 完成后再调度库存 FIFO。Registry 是解码元数据，可以先于依赖它的库存变更处理；库存变更彼此之间仍严格 FIFO。

`PLAYER_HOTBAR` 即使不直接依赖 Registry，也和库存变更共用 FIFO，避免选中槽与槽内容跨越屏障后发生乱序。

## 9. 重放与重入控制

- 重放安排到当前 handler 返回后的下一次 channel event-loop task，不能在 Registry handler 内递归 flush。
- `flushScheduled` 保证同一时刻最多存在一个待执行 flush。
- `flushing` 防止重放包再次触发 flush 或形成自我入队循环。
- 重放使用包类型和复制后的原始 payload 重新进入正常 `BedrockProtocol` 管线。
- 只有 readiness 条件满足时目标包才绕过 queue；否则重放尝试仍应保持暂存状态。
- 任一重放包解析失败后停止重放，释放剩余 payload，并使用现有协议错误路径终止连接。

## 10. 安全上限与超时

首版使用固定实现常量：

- 最多 512 个延迟库存包。
- payload 总量最多 4 MiB。
- 进入 PLAY 且存在待处理库存后，等待 `ITEM_REGISTRY` 最多 10 秒。
- START_GAME 前最多保存一个 early Registry；重复 Registry 视为非法启动序列。

超过任何上限都必须记录汇总 warning，并使用状态适配的现有 disconnect helper 断开连接。不能丢最旧包、丢最新包或继续空库存，因为这些策略都会造成不可见的数据分歧。

不在没有待处理库存时启动 Registry timeout。

## 11. Buffer 所有权

- 入队时复制当前未转换的 payload，不持有原 `PacketWrapper` 或其 input buffer。
- queue 成为副本的唯一 owner。
- 成功重放后释放对应副本。
- 调度失败、解析失败、overflow、timeout、duplicate Registry 和 `onRemove()` 都必须释放。
- timeout task 在 Registry ready 或连接销毁时取消。
- 测试直接断言所有路径结束后的 `ByteBuf.refCnt() == 0`。

## 12. 日志

合法提前包不再逐包输出 warning。使用每连接汇总日志：

```text
Deferred 2 inventory bootstrap packets while waiting for START_GAME/ITEM_REGISTRY
Replayed 2 inventory bootstrap packets after 456ms
```

正常汇总使用 FINE 或单次 INFO；overflow、timeout、重复 Registry 和重放失败使用 WARNING。日志不记录物品 NBT、玩家敏感数据或逐槽内容。

## 13. 自动化测试

新增：

`src/test/java/net/raphimc/viabedrock/protocol/storage/InventoryBootstrapQueueTest.java`

至少覆盖：

1. CONFIGURATION 中的 `INVENTORY_SLOT` 被延迟。
2. PLAY 和 Registry ready 缺一时不重放。
3. 两个条件满足后只重放一次。
4. early Registry 先于库存 FIFO 重放。
5. live Registry 在 PLAY 中完成后触发重放。
6. `INVENTORY_CONTENT -> INVENTORY_SLOT -> PLAYER_HOTBAR` 顺序不变。
7. ready 后的新库存包直接通过。
8. reconfigure 期间暂存，重新进入 PLAY 后恢复。
9. 重复 flush 调度与重入不会重复发送。
10. 包数上限和字节上限触发明确失败。
11. Registry 重复和等待超时触发明确失败。
12. 成功、异常、overflow、timeout 和 `onRemove()` 后 buffer 均释放。

如 ViaVersion 测试基础允许，再增加最小协议回归序列：

```text
INVENTORY_SLOT
PLAYER_HOTBAR
START_GAME
ITEM_REGISTRY
```

断言两个库存包各进入现有 handler 一次，并保持原顺序。若构造完整 START_GAME/Registry fixture 的成本过高，首版以 queue 单元测试加 bbdev 真实链路验收为准，不引入大体积二进制 fixture。

## 14. 构建验证

从 `/home/ec/workspace/ViaProxyWorkspace` 或等价 composite root 使用 Java 21 运行：

```bash
./gradlew :ViaBedrock:test --no-daemon
./gradlew :ViaBedrock:build --no-daemon
git -C ViaBedrock diff --check
```

若 Gradle 错误地返回 `UP-TO-DATE`，对受影响任务使用 `--rerun-tasks`。最终检查 jar 中包含新 storage 和测试期望的生产字节码。

## 15. bbdev 验收

- 初次进入大厅时，音符盒、地图投票等快捷栏物品立即可见。
- 右键、切换槽位和受限丢弃行为正确，排除纯显示层幽灵物品。
- 连续至少 30 次 ViaProxy Transfer 重连，零次需要 `/stuck`。
- 覆盖同连接 world reload / cross-server re-spawn，现有 movement watchdog 不回归。
- 普通登录和没有 early inventory 包的服务器行为不变。
- 箱子、工作台、熔炉、2x2 合成和 client-authoritative inventory 不回归。
- `/stuck` 重发库存后客户端内容不发生变化。
- 不再出现 `INVENTORY_SLOT outside PLAY state` 或 `PLAYER_HOTBAR outside PLAY state`。
- 不出现 queue overflow、重放重复、解析异常或 Netty buffer leak。

## 16. 分支、发布与回滚

- 修复分支从当前 `origin/main` 创建：`fix/defer-preplay-inventory`。
- 使用独立 worktree，避免携带主工作树中并行的未提交修改。
- 先提交本设计文档；文档复核后再编写实施计划和生产代码。
- ViaBedrock 子模块测试通过后先提交子模块分支。
- 只有用户继续授权发布时，才更新 ViaProxyWorkspace gitlink、运行 CI 和部署 bbdev。
- 部署前记录旧镜像 digest、启动版本和 jar 校验和。
- 验收失败时恢复旧镜像；不通过 Nukkit 延迟 `sendContents()` 掩盖回归。
- 该修复不改变服务端口、访问地址或管理方式，不向 `SERVICE.md` 写临时发布流水。

## 17. 完成条件

- pre-PLAY 库存包不再被静默丢弃。
- 库存包只在 PLAY 和真实 Item Registry 均就绪后翻译。
- 自动化覆盖顺序、readiness、reconfigure、溢出、超时、重入和 buffer 生命周期。
- ViaBedrock 测试、构建和 diff check 通过。
- bbdev 连续 Transfer 验收通过，物品无需 `/stuck` 即可见且可正常交互。
- 正常登录、容器和现有 JoinGate/movement watchdog 没有行为回归。

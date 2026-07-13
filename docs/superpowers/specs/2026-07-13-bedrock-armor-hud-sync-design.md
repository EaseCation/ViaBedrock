# ViaBedrock Bedrock 盔甲 HUD 同步设计

日期：2026-07-13  
状态：设计已确认，等待实现  
范围：ViaBedrock 本地源码、测试与构建；不发布、不部署、不操作服务

## 1. 结论

Java 客户端物品栏上方的盔甲栏读取本地玩家的 `minecraft:armor` 实体属性。Bedrock 服务端主要通过装备物品的 `minecraft:armor.protection` 组件表达原生盔甲格，当前 ViaBedrock 只同步装备物品，没有为本地 Java 玩家合成对应属性，因此 HUD 保持为零或不能随换装刷新。

修复应由 ViaBedrock 根据 Bedrock 原始装备和物品定义计算原生 protection 总值，再向 Java 客户端发送 `UPDATE_ATTRIBUTES(minecraft:armor)`。转换后的 Java 物品材质、Java 默认护甲属性、MMOItems Defense、保护附魔及服务端实际减伤都不是本功能的数据源。

## 2. 目标

- Java 客户端盔甲栏与同一角色在原生 Bedrock 客户端看到的盔甲格一致。
- 支持当前 Bedrock 版本的全部原版玩家盔甲。
- 支持通过 Bedrock `ITEM_REGISTRY` 下发 `minecraft:armor.protection` 的 DataDriven/自定义盔甲。
- 装备、卸下、死亡、重生、切维度、跨服和重连后均能得到正确 HUD。
- 全量库存更新期间不发送四次中间值，不造成盔甲栏闪动。
- 非法或未知物品数据安全降级，不中断玩家连接。

## 3. 非目标

- 不让 HUD 表示 MMOItems、MythicLib 或其他插件的自定义防御属性。
- 不让 HUD 表示保护附魔、盔甲韧性或最终伤害减免。
- 不从转换后的 Java 物品 ID、材质或 attribute modifiers 反推 Bedrock protection。
- 不修改 ViaBedrockUtility、BedrockLoader、Nemisys、Nukkit、MMOItems 或资源包转换器。
- 不扩展 BedrockLoader custom mapping snapshot schema。
- 不处理 Bedrock `SET_HUD` 对 Armor 元素的隐藏语义。
- 不在本次改动中修复 Bedrock 入站 `UPDATE_ATTRIBUTES outside PLAY state`。
- 不推送分支、不运行远端 CI、不部署 bbdev 或生产环境、不重启服务。

## 4. 权威数据与优先级

### 4.1 原版物品

新增版本化资源文件：

`src/main/resources/assets/viabedrock/data/bedrock/armor_protection.json`

文件以 Bedrock 物品标识符为 key、原生 protection 为 value。它至少覆盖皮革、锁链、铜、金、铁、钻石、下界合金和海龟壳。鞘翅明确不提供 protection。

预期完整套装总值：

| 套装 | protection 总值 |
| --- | ---: |
| 皮革 | 7 |
| 铜 | 10 |
| 金 | 11 |
| 锁链 | 12 |
| 铁 | 15 |
| 钻石 | 20 |
| 下界合金 | 20 |

数据放在资源文件中而不是散落在 Java switch/map 常量里，以便 Bedrock 协议升级时独立审查和更新。

### 4.2 DataDriven/自定义物品

Bedrock `ITEM_REGISTRY` 是自定义 gameplay 组件的权威来源。`ItemDefinitions.addFromNetworkTag()` 解析：

`components -> minecraft:armor -> protection`

网络定义优先于同标识符的已有定义。客户端资源包中的图标、名称和贴图不能覆盖网络 Item Registry 的 armor protection。

### 4.3 降级规则

- 空物品、没有 armor 组件的物品和普通装饰头饰贡献 `0`。
- 明确存在 armor 组件但 protection 类型错误、为负数、非有限数或溢出时，该物品贡献 `0`。
- 未知自定义物品不使用 Java 映射材质兜底，贡献 `0`。
- 合法总值最终限制在 `0..20`，因为本功能只负责原生盔甲栏视觉。

## 5. 组件设计

### 5.1 `ItemDefinitions.ItemDefinition`

增加可空的 armor protection 字段以及只读访问器。空值表示物品没有 armor 组件；不能用 `0` 同时表达“明确为零”和“组件不存在”。

`addFromNetworkTag()` 负责解析并校验网络 NBT。格式损坏时保留一条按物品 ID 去重的警告，不把异常传播到协议线程。

### 5.2 `BedrockArmorProtectionRegistry`

职责：

- 加载和验证原版 `armor_protection.json`。
- 查询网络 `ItemDefinition` 中的自定义 protection。
- 按“网络定义优先、原版表其次、未知为零”的规则返回单件值。
- 对外只暴露 `protection(String bedrockIdentifier)` 一类窄接口。

它不读取 Java item raw ID，不依赖 CustomMappingSyncStorage，也不负责发送数据包。

### 5.3 `BedrockArmorValueResolver`

职责：

- 接收 ArmorContainer 的头、胸、腿、脚四个 `BedrockItem`。
- 使用 `ItemRewriter.bedrockIdentifier()` 获取 Bedrock 原始标识符。
- 调用 protection registry 得到每件值并求和。
- 将最终结果限制在 `0..20`。

该类保持纯计算，不访问实体状态或发送数据包，便于完整单元测试。

### 5.4 `PlayerArmorHudTracker`

每个 UserConnection 一份，保存：

- 当前计算值。
- 最后已发送值。
- 是否 dirty。
- 是否允许向 Java PLAY 会话发送。

主要操作：

- `markDirty()`：装备或定义变化后标记待同步。
- `syncIfReady()`：会话可发送时计算并在数值变化后发送。
- `forceSync()`：登录、重生、切维度或跨服后无视去重强制发送。
- `reset()`：会话清理时归零内部状态。

发送的 Java 属性包包含本地玩家 entity ID、`Attributes.ARMOR`、最终值作为 base value，以及零个 modifier。只处理本地玩家，不给其他实体生成 HUD 属性。

### 5.5 `ArmorContainer`

ArmorContainer 是装备变化的唯一通知入口：

- 单槽 `setItem()` 完成后通知 tracker。
- 全量 `setItems()` 在批处理标记下写入四槽，全部完成后只通知一次。
- 覆盖 `clearItems()`，清空后通知 tracker，避免死亡后残留旧盔甲值。

普通背包、快捷栏、副手和 HUD crafting 容器变化不触发盔甲计算。

## 6. 数据流

```text
Bedrock ITEM_REGISTRY
  -> ItemDefinitions / armor protection registry

Bedrock INVENTORY_CONTENT 或 INVENTORY_SLOT
  -> ArmorContainer 保存 BedrockItem
  -> PlayerArmorHudTracker 标记 dirty
  -> BedrockArmorValueResolver 读取四槽
  -> protection registry 查询原版/自定义值
  -> 求和并限制到 0..20
  -> Java UPDATE_ATTRIBUTES(minecraft:armor)
  -> Java 客户端刷新原版盔甲栏
```

## 7. 生命周期与顺序

### 7.1 初次登录

Item Registry 应先建立定义。ArmorContainer 如果在 Java Login 前收到内容，只更新状态并标记 dirty。`JoinPackets.sendJavaLoginAndInitialPackets()` 发送初始库存后调用 `forceSync()`；JoinGate 尚未开放时由现有客户端包队列保证顺序。

### 7.2 正常换装

单槽更新后立即重新计算。数值没有变化时不重复发送，例如在相同 protection 的两件物品之间替换。

### 7.3 死亡与重生

- `keepInventory=false`：ArmorContainer 清空后发送 `0`。
- `keepInventory=true`：保留四槽，重生完成后强制重发当前值。

无论 Respawn keep mask 是否保留属性，最终都以 ArmorContainer 当前内容校正。

### 7.4 切维度和跨服

切维度后强制重发当前值。跨服/会话重建清理旧装备时先归零，收到新 ArmorContainer 内容后再计算，不能让上一服务器的值残留。

### 7.5 断线

Tracker 随 UserConnection 销毁，不跨连接持久化任何 armor 状态。

## 8. 错误处理与日志

- 正常解析、普通非盔甲物品和未知装饰品不输出 INFO/WARN。
- 明确损坏的 armor 组件按物品 ID 每连接警告一次。
- 计算或发送异常不能断开玩家；保留上一次已发送值并等待下一次同步机会。
- 可保留 FINE 级日志记录四槽 protection 和最终值，默认运行环境不可见。
- 不添加逐槽、逐 tick 或逐包 INFO 日志。

## 9. 自动化测试

### 9.1 Item Registry 解析

- 正常 protection。
- 缺少 armor 组件。
- 错误 NBT 类型。
- 负数、NaN、Infinity 和溢出。
- 重复定义覆盖。
- 损坏定义安全降级并去重警告。

### 9.2 原版数据契约

- JSON 可解析、无重复、值合法。
- 所有当前玩家盔甲均有定义。
- 鞘翅不提供 protection。
- 七套原版套装总值正确。
- 协议更新出现新 armor tag 项但资源表未覆盖时测试失败。

### 9.3 Resolver

- 裸装、单件、混搭、完整套装。
- 自定义 protection。
- 非盔甲和未知物品。
- 附魔、耐久和染色不影响结果。
- 超过 20 后限制为 20。
- 单件非法不影响其他槽。

### 9.4 Tracker 与协议包

- 全量更新只发送一次。
- 单槽更新、去重、卸下、clearItems。
- Login 前 dirty、Login 后强制同步。
- 重生、切维度和跨服强制同步。
- 只处理本地玩家。
- entity ID、armor attribute ID、base value 和 modifier count 正确。

## 10. 本地验证与交付

先确认 Gradle 使用 Java 21，然后从 `/home/ec/workspace/ViaProxyWorkspace` 根目录运行：

```bash
./gradlew --version
./gradlew :ViaBedrock:test :ViaBedrock:compileJava --rerun-tasks --no-daemon
./gradlew build --no-daemon
git -C ViaBedrock diff --check
```

构建后：

- 用 `jar tf` 确认 armor protection JSON 进入产物。
- 用 `javap` 确认最终 ViaProxy jar 包含 `Attributes.ARMOR` 发包路径。
- 记录本地 ViaProxy jar 的绝对路径和 SHA256。
- 不复制到运行目录，不推送，不触发 CI，不部署或重启服务。

## 11. 手动验收清单

由用户后续自行使用本地产物或指定环境测试：

| 场景 | 预期 HUD |
| --- | ---: |
| 裸装 | 0 |
| 皮革头盔 | 1 |
| 全套皮革 | 7 |
| 全套铁甲 | 15 |
| 全套钻石甲 | 20 |
| 混搭 | 各件 protection 之和 |
| 有保护附魔 | 不额外增加 |
| 耐久受损 | 不减少 |
| 自定义 protection=5 | 增加 5 |
| 自定义总值大于 20 | 满格 |
| 普通自定义头饰 | 0 |
| 卸下全部盔甲 | 立即归零 |
| 快速移动换装 | 最终值正确且无中间闪动 |
| 不保留物品死亡 | 重生后为 0 |
| 保留物品死亡 | 重生后保持原值 |
| 切维度/跨服/重连 | 不丢失、不残留旧值 |

原生 Bedrock 与 Java 客户端使用同一装备分别截图，只比较盔甲图标数量，不比较实际受伤结果。

## 12. 完成条件

- 所有新增和既有 ViaBedrock 测试通过。
- 工作区完整 build 通过。
- 原版 protection 数据和网络组件解析均有自动化覆盖。
- 最终 ViaProxy jar 静态证明包含 armor 属性同步逻辑。
- 交付本地 jar 路径、SHA256、变更文件清单和手动验收步骤。
- 没有 push、远端 CI、bbdev/生产部署或服务操作。


# 自定义实体类型 ID 映射功能

## 功能说明

此功能允许通过配置文件将基岩版的自定义实体类型 ID 映射到 Java 版的自定义实体类型 ID，从而支持通过 Mod 注册的自定义实体。

## 配置文件

配置文件位置：`config/bedrock-loader/custom_entity_type_ids.json`

### 配置格式

```json
{
  "mappings": {
    "namespace:custom_entity_1": {
      "java_type_id": 150,
      "fallback_type": "minecraft:pig"
    },
    "namespace:custom_entity_2": {
      "java_type_id": 151,
      "fallback_type": "minecraft:zombie"
    }
  }
}
```

### 字段说明

- **mappings**: 映射对象，键为基岩版实体类型标识符
  - **java_type_id**: (必需) Java 版实体类型 ID（数字），由 Mod 注册时分配
  - **fallback_type**: (可选) 降级实体类型，当 Mod 未加载时使用的原版实体类型

## 使用流程

### 1. 获取 Java 版实体类型 ID

在你的 Mod 中注册自定义实体后，获取其类型 ID：

```java
// 示例代码（具体实现取决于你的 Mod 框架）
int entityTypeId = Registry.ENTITY_TYPE.getRawId(customEntityType);
```

### 2. 创建配置文件

在 ViaBedrock 的 `config/bedrock-loader/` 目录下创建 `custom_entity_type_ids.json` 文件，添加映射：

```json
{
  "mappings": {
    "mymod:custom_zombie": {
      "java_type_id": 150,
      "fallback_type": "minecraft:zombie"
    }
  }
}
```

### 3. 启动服务器

启动 ViaProxy，日志中会显示：

```
[INFO] Loaded 1 custom entity type ID mappings
```

### 4. 测试

- 连接到基岩版服务器
- 生成配置文件中定义的自定义实体
- Java 客户端应该能够正确渲染 Mod 注册的自定义实体

## 工作原理

1. **加载阶段**：ViaBedrock 启动时读取配置文件，建立基岩版实体类型到 Java 版类型 ID 的映射
2. **实体生成**：当基岩版服务器发送 ADD_ENTITY 包时：
   - 首先检查是否有自定义映射
   - 如果有，使用配置的 `java_type_id` 和 `fallback_type`
   - 如果没有，使用原有的映射逻辑
3. **类型 ID 传递**：发送给 Java 客户端时，使用 `entity.javaTypeId()` 方法获取实际的类型 ID（自定义 ID 或枚举 ID）

## 降级机制

`fallback_type` 字段提供了降级支持：

- 当 Java 客户端加载了 Mod 时，实体会使用 `java_type_id` 渲染为自定义实体
- 当 Java 客户端未加载 Mod 时，实体会使用 `fallback_type` 渲染为原版实体

这确保了即使在 Mod 未加载的情况下，游戏仍能正常运行。

## 注意事项

1. **ID 冲突**：确保 `java_type_id` 不与原版实体类型 ID 冲突
2. **命名空间**：实体类型标识符会自动规范化（添加 `minecraft:` 前缀如果缺失）
3. **Mod 兼容性**：此功能需要配合支持自定义实体的 Mod 使用
4. **配置验证**：如果 `fallback_type` 指定的实体类型不存在，会在日志中显示警告

## 示例配置

参考 `config/bedrock-loader/custom_entity_type_ids.json.example` 文件。

## 技术细节

### 修改的文件

- `BedrockMappingData.java`: 添加自定义映射数据结构和加载逻辑
- `Entity.java`: 添加 `customJavaTypeId` 字段和 `javaTypeId()` 方法
- `EntityTracker.java`: 添加支持自定义类型 ID 的重载方法
- `EntityPackets.java`: 修改 ADD_ENTITY 包处理逻辑
- `LivingEntity.java`, `MobEntity.java`, `AbstractHorseEntity.java`: 更新构造函数

### 向后兼容性

所有修改都保持了向后兼容：

- 原有的构造函数和方法仍然可用
- 未配置自定义映射时，行为与之前完全相同
- 配置文件是可选的，不存在时不影响正常功能

# API 概览

Rondo 提供完整的 Java/Kotlin API，供其他插件集成。

## 引入依赖

### Gradle (Kotlin DSL)

```kotlin
repositories {
    maven("https://repo.arcartx.com/repository/maven-public/")
}

dependencies {
    compileOnly("priv.seventeen.artist:Rondo:1.0.0")
}
```

### plugin.yml

```yaml
depend: [Rondo]
# 或
softdepend: [Rondo]
```

## 核心入口

所有 API 通过 `RondoAPI` 对象访问：

```kotlin
import priv.seventeen.artist.rondo.api.RondoAPI
```

## 快速示例

```kotlin
import priv.seventeen.artist.rondo.api.RondoAPI
import java.math.BigDecimal

// 查询余额
val balance = RondoAPI.getBalance(player.uniqueId, "dark_coin")

// 扣款
val success = RondoAPI.withdraw(player.uniqueId, "dark_coin", BigDecimal(100), "my_plugin:shop")

// 存入
RondoAPI.deposit(player.uniqueId, "dark_coin", BigDecimal(50), "my_plugin:reward")

// 转账
val result = RondoAPI.transfer(from.uniqueId, to.uniqueId, "dark_coin", BigDecimal(200))
if (result.success) {
    // 转账成功，税额: result.taxAmount
}
```

## 注意事项

- 所有金额使用 `BigDecimal` 类型，避免浮点精度问题
- `source` 参数用于标识操作来源，建议格式: `插件名:功能`
- API 方法支持在线和离线玩家
- 操作前会触发对应事件，其他插件可监听并取消
- 跨服模式下 API 行为完全一致，内部自动走 Redis 原子事务

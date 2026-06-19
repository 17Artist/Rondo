# 货币配置

Rondo 的核心设计是**配置驱动**。每种货币对应 `plugins/Rondo/currencies/` 目录下的一个 YAML 文件。

## 配置结构

```yaml
# 唯一标识（必须与文件名一致）
id: my_currency

# 显示名称
display-name: "我的货币"

# 符号（用于格式化显示）
symbol: "◆"

# 颜色（Minecraft 颜色名或 § 代码）
color: "GOLD"

# 描述
description: "这是一种自定义货币"

# === 精度与上限 ===

# 小数位数（0=整数货币，2=支持0.01精度）
decimal-places: 0

# 余额上限（-1=无上限）
max-balance: -1

# 新玩家初始余额
default-balance: 0

# 是否允许负数余额
negative-allowed: false

# === 行为 ===

# 是否可在交易市场流通
tradeable: true

# 是否可玩家间转账
transferable: true

# 转账税率（0.05 = 5%）
transfer-tax-rate: 0.05

# === 集成 ===

# 是否注册为 Vault 主货币（全局仅一个）
vault-primary: false

# 是否参与排行榜
ranking-enabled: true
```

::: tip 字段默认值
若省略某个字段，将使用其内置默认值。注意 `tradeable` 与 `transferable` 在省略时默认为 `false`（即默认不可交易、不可转账），如需开启请显式写明。
:::

## 字段说明

### id

货币的唯一标识符，用于命令、API 和数据库存储。建议使用小写字母和下划线。

### decimal-places

控制货币的精度：
- `0` — 整数货币（如金币、点券）
- `2` — 支持两位小数（如 99.99）
- `4` — 高精度（适用于汇率计算）

底层存储为 4 位小数精度（数据库 `DECIMAL(20,4)`、Redis 保留 4 位），因此 `decimal-places` 的最大有效值为 `4`。

### max-balance

余额上限。设为 `-1` 表示无上限。当存入操作会导致余额超过上限时，操作会被拒绝。

### transfer-tax-rate

转账时从发送方额外扣除的税率。例如转账 100，税率 5%，则发送方实际扣除 105，接收方收到 100。

税额对应的货币会被直接销毁（从经济系统中移除），起到回笼通货、抑制通货膨胀的作用。

### vault-primary

标记为 Vault 主货币后，其他通过 Vault API 操作经济的插件将使用这种货币。全局只能有一种货币设为 `true`。

## 添加新货币

1. 在 `currencies/` 目录下创建新的 `.yml` 文件
2. 填写配置内容
3. 执行 `/rondo reload`

## 删除货币

1. 删除对应的 `.yml` 文件
2. 执行 `/rondo reload`

::: warning 注意
删除货币配置不会删除数据库中已有的余额数据。如需清理，请手动操作数据库。
:::

## 可用颜色

| 名称           | 代码 | 效果 |
|--------------|----|----|
| BLACK        | §0 | 黑色 |
| DARK_BLUE    | §1 | 深蓝 |
| DARK_GREEN   | §2 | 深绿 |
| DARK_AQUA    | §3 | 深青 |
| DARK_RED     | §4 | 深红 |
| DARK_PURPLE  | §5 | 深紫 |
| GOLD         | §6 | 金色 |
| GRAY         | §7 | 灰色 |
| DARK_GRAY    | §8 | 深灰 |
| BLUE         | §9 | 蓝色 |
| GREEN        | §a | 绿色 |
| AQUA         | §b | 青色 |
| RED          | §c | 红色 |
| LIGHT_PURPLE | §d | 粉色 |
| YELLOW       | §e | 黄色 |
| WHITE        | §f | 白色 |

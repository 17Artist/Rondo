# PlaceholderAPI 集成

Rondo 提供丰富的 PlaceholderAPI 占位符，可用于计分板、Tab 列表、全息图等。

## 配置

1. 安装 [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/)
2. 在 `config.yml` 中确保 `features.papi-hook: true`

## 可用占位符

### 余额

| 占位符 | 说明 | 示例输出 |
|--------|------|----------|
| `%rondo_balance_<ID>%` | 余额数字 | `1500` |
| `%rondo_balance_formatted_<ID>%` | 格式化余额 | `§6G1500.00` |

### 统计

| 占位符 | 说明 | 示例输出 |
|--------|------|----------|
| `%rondo_total_earned_<ID>%` | 累计获得 | `25000` |
| `%rondo_total_spent_<ID>%` | 累计消耗 | `18000` |

### 排行榜

| 占位符 | 说明 | 示例输出 |
|--------|------|----------|
| `%rondo_top_<ID>_<排名>_name%` | 第N名玩家名 | `Steve` |
| `%rondo_top_<ID>_<排名>_balance%` | 第N名余额 | `99999` |
| `%rondo_rank_<ID>%` | 自己的排名 | `5` |

### 示例

```
# 显示金币余额
%rondo_balance_gold%

# 显示格式化的点券余额
%rondo_balance_formatted_points%

# 金币排行榜第1名
%rondo_top_gold_1_name%: %rondo_top_gold_1_balance%

# 自己的金币排名
%rondo_rank_gold%
```

## 注意事项

- `%rondo_balance_<ID>%` 会按货币的 `decimal-places` 输出小数位，例如两位小数货币显示为 `1500.00`。
- `%rondo_total_earned_<ID>%` 和 `%rondo_total_spent_<ID>%` 仅对**在线玩家**有效，玩家离线时返回 `0`。
- `%rondo_top_*%` 和 `%rondo_rank_<ID>%` 依赖排行榜缓存，只对设置了 `ranking-enabled: true` 的货币有效；对关闭排行榜的货币（如默认的点券）将返回空或未上榜。
- `%rondo_rank_<ID>%` 在玩家未进入排行榜缓存（前 `ranking-size` 名）时返回 `未上榜`。
- `%rondo_top_<ID>_<排名>_*%` 在该名次暂无数据时返回 `---`。

## 计分板示例

```yaml
# 使用 TAB 或其他计分板插件
lines:
  - "&6G 金币: &f%rondo_balance_gold%"
  - "&bP 点券: &f%rondo_balance_points%"
  - "&dH 荣誉: &f%rondo_balance_honor%"
  - ""
  - "&e排名: #%rondo_rank_gold%"
```

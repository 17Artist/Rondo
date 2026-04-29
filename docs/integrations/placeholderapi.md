# PlaceholderAPI 集成

Rondo 提供丰富的 PlaceholderAPI 占位符，可用于计分板、Tab 列表、全息图等。

## 配置

1. 安装 [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/)
2. 在 `config.yml` 中确保 `features.papi-hook: true`

## 可用占位符

### 余额

| 占位符                              | 说明    | 示例输出      |
|----------------------------------|-------|-----------|
| `%rondo_balance_<ID>%`           | 余额数字  | `1500`    |
| `%rondo_balance_formatted_<ID>%` | 格式化余额 | `§6◆1500` |

### 统计

| 占位符                         | 说明   | 示例输出    |
|-----------------------------|------|---------|
| `%rondo_total_earned_<ID>%` | 累计获得 | `25000` |
| `%rondo_total_spent_<ID>%`  | 累计消耗 | `18000` |

### 排行榜

| 占位符                             | 说明     | 示例输出    |
|---------------------------------|--------|---------|
| `%rondo_top_<ID>_<排名>_name%`    | 第N名玩家名 | `Steve` |
| `%rondo_top_<ID>_<排名>_balance%` | 第N名余额  | `99999` |
| `%rondo_rank_<ID>%`             | 自己的排名  | `5`     |

### 示例

```
# 显示暗币余额
%rondo_balance_dark_coin%

# 显示格式化的星元余额
%rondo_balance_formatted_star_yuan%

# 暗币排行榜第1名
%rondo_top_dark_coin_1_name%: %rondo_top_dark_coin_1_balance%

# 自己的暗币排名
%rondo_rank_dark_coin%
```

## 计分板示例

```yaml
# 使用 TAB 或其他计分板插件
lines:
  - "&6◆ 暗币: &f%rondo_balance_dark_coin%"
  - "&a◇ 锂核: &f%rondo_balance_lithium%"
  - "&b★ 星元: &f%rondo_balance_star_yuan%"
  - ""
  - "&e排名: #%rondo_rank_dark_coin%"
```

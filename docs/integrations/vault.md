# Vault 集成

Rondo 可以作为 Vault Economy Provider，让其他依赖 Vault 的插件使用 Rondo 的货币系统。

## 配置

1. 安装 [Vault](https://www.spigotmc.org/resources/vault.34315/) 插件
2. 在 `config.yml` 中确保 `features.vault-hook: true`
3. 在某个货币配置中设置 `vault-primary: true`

```yaml
# currencies/gold.yml
vault-primary: true
```

::: warning 注意
全局只能有一种货币设为 `vault-primary: true`。如果多个货币都设为 true，只有第一个被加载的会生效。
:::

## 工作原理

- Rondo 注册为 Vault 的 Economy Provider（最高优先级）
- 其他插件通过 Vault API 操作经济时，实际调用 Rondo 的内部逻辑
- 所有通过 Vault 的操作都会记录流水日志（source 为 `vault`）
- Vault 操作同样会触发 `EconomyTransactionEvent`

## 兼容性

以下类型的插件可以通过 Vault 与 Rondo 协作：

- 商店插件（ShopGUI+, EconomyShopGUI 等）
- 领地插件（Residence, GriefPrevention 等）
- 任务插件（BetonQuest, Quests 等）
- 其他经济相关插件

## 不使用 Vault

如果不需要 Vault 集成：

1. 设置 `features.vault-hook: false`
2. 所有货币的 `vault-primary` 设为 `false`

Rondo 的所有功能不依赖 Vault，可以完全独立运行。

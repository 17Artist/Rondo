# 权限列表

## 玩家权限

| 权限节点             | 默认   | 说明        |
|------------------|------|-----------|
| `rondo.use`      | true | 查看自己的余额   |
| `rondo.transfer` | true | 转账给其他玩家   |
| `rondo.exchange` | true | 使用货币兑换    |
| `rondo.log`      | true | 查看自己的交易记录 |
| `rondo.top`      | true | 查看排行榜     |

## 管理员权限

| 权限节点          | 默认 | 说明      |
|---------------|----|---------|
| `rondo.admin` | op | 所有管理员命令 |

## 权限组建议

### 普通玩家
```yaml
rondo.use: true
rondo.transfer: true
rondo.exchange: true
rondo.log: true
rondo.top: true
```

### VIP 玩家
与普通玩家相同，可通过其他系统给予额外的兑换额度。

### 管理员
```yaml
rondo.admin: true
```

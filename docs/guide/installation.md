# 安装部署

## 前置要求

### 必需
- Java 17 或更高版本
- Spigot 或 Paper 1.18.2+

### 可选
- [Vault](https://www.spigotmc.org/resources/vault.34315/) — 经济接口桥接
- [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) — 占位符支持

## 安装步骤

### 1. 安装 Rondo

1. 从 [Releases](https://github.com/17Artist/Rondo/releases) 下载 `Rondo-x.x.x.jar`
2. 放入 `plugins/` 目录
3. 启动服务器

### 2. 配置

首次启动后编辑配置文件，然后 `/rondo reload`。

## MySQL 部署

如果使用 MySQL 存储：

1. 创建数据库：
```sql
CREATE DATABASE rondo CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

2. 修改 `config.yml`：
```yaml
storage:
  type: mysql
  mysql:
    host: localhost
    port: 3306
    database: rondo
    username: your_user
    password: your_password
    pool-size: 10
```

3. 重启服务器或 `/rondo reload`

Rondo 会自动创建所需的表结构。

## 迁移

### 从 SQLite 迁移到 MySQL

目前需要手动导出/导入数据。后续版本将提供迁移命令。

## 故障排查

### 插件未加载
- 检查是否安装了 Blink
- 检查 Java 版本是否 >= 17
- 查看控制台错误日志

### 数据库连接失败
- 检查 MySQL 服务是否运行
- 检查用户名密码是否正确
- 检查数据库是否存在
- 检查网络连通性

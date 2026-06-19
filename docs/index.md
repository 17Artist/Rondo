---
layout: home

hero:
  name: Rondo
  text: 通用多货币经济系统
  tagline: 配置驱动、高性能、可扩展的 Minecraft 多货币经济插件
  image:
    src: /logo.svg
    alt: Rondo
  actions:
    - theme: brand
      text: 快速开始
      link: /guide/getting-started
    - theme: alt
      text: API 文档
      link: /api/
    - theme: alt
      text: GitHub
      link: https://github.com/17Artist/Rondo

features:
  - title: 无限货币类型
    details: 通过 YAML 配置文件自由定义任意数量的货币，无需修改代码。支持小数精度、余额上限、转账税率等属性。
  - title: 内置兑换系统
    details: 配置化的货币间兑换规则，支持比率设定、周期限购（日/周/月）、最小兑换数量等。
  - title: Redis 跨服同步
    details: 通过 Redis Lua 原子事务实现多服务端余额强一致性，Pub/Sub 实时通知，MySQL 持久化备份。
  - title: 排行榜系统
    details: 自动维护每种货币的排行榜并定时刷新，可通过 PlaceholderAPI 占位符展示。
  - title: 完整流水日志
    details: 异步记录每一笔交易，支持按货币、时间查询，自动清理过期日志。
  - title: 高性能设计
    details: 内存缓存与异步批量持久化结合，支持 SQLite 和 MySQL，并已集成 Vault、PlaceholderAPI。
---

import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'Rondo',
  description: '通用多经济系统插件',
  lang: 'zh-CN',
  base: '/Rondo/',

  head: [
    ['link', { rel: 'icon', href: '/Rondo/favicon.ico' }]
  ],

  themeConfig: {
    logo: '/logo.svg',
    nav: [
      { text: '指南', link: '/guide/getting-started' },
      { text: 'API', link: '/api/' },
      { text: '集成', link: '/integrations/vault' },
      {
        text: '1.0.0',
        items: [
          { text: '更新日志', link: '/changelog' }
        ]
      }
    ],

    sidebar: {
      '/guide/': [
        {
          text: '入门',
          items: [
            { text: '快速开始', link: '/guide/getting-started' },
            { text: '安装部署', link: '/guide/installation' }
          ]
        },
        {
          text: '配置',
          items: [
            { text: '主配置', link: '/guide/configuration' },
            { text: '货币配置', link: '/guide/currencies' },
            { text: '兑换规则', link: '/guide/exchange' },
            { text: '消息配置', link: '/guide/messages' }
          ]
        },
        {
          text: '使用',
          items: [
            { text: '命令参考', link: '/guide/commands' },
            { text: '权限列表', link: '/guide/permissions' }
          ]
        }
      ],
      '/api/': [
        {
          text: 'API 参考',
          items: [
            { text: '概览', link: '/api/' },
            { text: 'RondoAPI', link: '/api/rondo-api' },
            { text: '事件', link: '/api/events' }
          ]
        }
      ],
      '/integrations/': [
        {
          text: '集成',
          items: [
            { text: 'Vault', link: '/integrations/vault' },
            { text: 'PlaceholderAPI', link: '/integrations/placeholderapi' }
          ]
        }
      ]
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/17Artist/Rondo' }
    ],

    footer: {
      message: 'Released under the Apache 2.0 License.',
      copyright: 'Copyright 2024 17Artist'
    },

    search: {
      provider: 'local'
    },

    outline: {
      level: [2, 3],
      label: '目录'
    },

    docFooter: {
      prev: '上一页',
      next: '下一页'
    },

    lastUpdated: {
      text: '最后更新'
    }
  }
})

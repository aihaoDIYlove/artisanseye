# Artisan's Eye

[English](README_EN.md) | 简体中文

一个 **TerraFirmaCraft（群峦传说：次世代）** 附属 mod：锻造体验增强。零 mixin，纯客户端mod，单人即装即用。

## 功能

- **锻造条数值化** — 锻造条右端实时显示目标偏移与当前偏移。
- **步骤数值提示** — 悬停锻造步骤按钮显示具体偏移量：负值红色、正值绿色。
- **最短路径提示** — 实时求解到完工的最短敲击序列，推荐按钮呼吸灯高亮并显示剩余步数。

## 环境

| 依赖 | 版本 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | `[21.1.197,)`（开发基于 21.1.250） |
| TerraFirmaCraft | `[4.1.0, 4.3)`（开发基于 4.2.10） |

## 构建

```bash
./gradlew build      # Windows 下使用 gradlew.bat
./gradlew runClient  # 启动开发客户端（自动加载 TFC + Patchouli）
```

## 许可

MIT

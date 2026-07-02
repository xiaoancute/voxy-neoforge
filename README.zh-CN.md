# Voxy NeoForge 1.21.1

> Voxy 的非官方 NeoForge 1.21.1 移植版。

[English README](README.md)

## 说明

Voxy 是由 [MCRcortex](https://github.com/MCRcortex) 创作的 LOD 远景渲染模组。LOD 核心设计和原始实现属于原作者；这个 fork 的目标不是重写 Voxy，而是把它维护到 NeoForge 1.21.1 的现代客户端渲染栈上。

这个 fork 主要做这些事：

- 适配 NeoForge 1.21.1
- 跟进 Sodium 0.8.12 的渲染接口变化
- 适配 Iris 光影路径
- 对大型客户端整合包做兼容性降级和崩溃防护
- 用 GitHub Actions 跑构建和客户端 smoke 测试

原 Voxy 使用 All Rights Reserved 许可证。请尊重原作者授权；本仓库仅作为社区移植和个人使用。

## 当前状态

Alpha，但在当前测试栈上可以正常使用。

| 组件 | 测试版本 |
|------|----------|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.234 |
| Sodium | 0.6.13-neoforge 和 0.8.12-beta.2+mc1.21.1 |
| Iris | 1.8.14-beta.1+1.21.1-neoforge |
| Forgified Fabric API | 0.116.7+2.2.0+1.21.1 |

## 已支持

- 超出原版视距的 LOD 远景地形渲染
- Sodium 0.6.13 和 0.8.12 渲染挂钩
- Iris 光影包渲染路径

## 已知兼容性

| 模组/场景 | 状态 | 说明 |
|-----------|------|------|
| Sodium 0.6.13-neoforge 到 0.8.12-beta.2 | 必需，CI 测试 | 0.6.13 和 0.8.12 使用不同的渲染挂钩签名 |
| Iris 1.8.14-beta.1 | 已测试 | 光影可用；Voxy LOD 暂不写入 Iris 阴影贴图，以避免 shadow pass 崩溃 |
| Create / Create Aeronautics | 已测试 | 在大型机械动力/航空学客户端整合包中测试通过，仍按客户端兼容性处理 |
| Sable | 已测试 | 可与当前 Voxy 渲染路径共存 |
| Modern UI | 已知冲突 | 可能导致 Voxy 有缓存但不绘制；遇到 LOD 不显示优先关闭它 |
| 专用服务器 | 不支持，也不需要 | 这是客户端渲染模组，不要装到服务端 |

没有列出的模组不代表不兼容，只是没有作为主要目标确认。小型 UI、美化、辅助类模组不在这里逐个记录。

## 已知限制

- Iris 光影阴影不会包含 Voxy LOD 远景。
- Debug screen 集成暂时关闭。

## 安装

1. 安装 Minecraft 1.21.1 + NeoForge。
2. 安装 Sodium、Forgified Fabric API。
3. 如果需要光影，安装 Iris。
4. 构建本项目，把生成的 jar 放进客户端 `mods` 文件夹。
5. 不要把它放进服务器 `mods` 文件夹。

## 构建

```bash
git clone https://github.com/xiaoancute/voxy-neoforge.git
cd voxy-neoforge
./gradlew build
```

构建产物在 `build/libs/`。

## 链接

- 原版 Voxy: [github.com/MCRcortex/voxy](https://github.com/MCRcortex/voxy)

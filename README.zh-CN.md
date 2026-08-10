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
- 自动恢复 LOD：当 Sodium 区块同步短暂导致 Voxy 边界塌缩时，自动刷新渲染器
- 专用服务器 companion：异步采集新生成区块并维护服务端 LOD 缓存

## 客户端命令

| 命令 | 作用 |
|------|------|
| `/voxy refresh` | 重建客户端渲染器，并把 Sodium 区块段重新同步进 Voxy 边界 |
| `/voxy status` | 打印当前配置、存储路径、渲染器遥测、Sodium 同步状态和 LOD 恢复计数 |
| `/voxy reload` | 在配置或存储变更后重新创建 Voxy 客户端实例 |

自动恢复 LOD 默认开启。如果重进服务器或切换世界后远景区块突然变得过于简陋，Voxy 会尝试自动执行同类刷新，不需要手动关开模组。

## 专用服务器

同一个 jar 现在可以安装在 NeoForge 1.21.1 专用服务器上。服务端不加载 Sodium、Iris 或任何 OpenGL 渲染代码；完整区块的 palette 和光照会先制作快照，再在后台转换成 Voxy LOD，并保存到 `<世界目录>/voxy/server/`。

服务端配置位于世界的 `serverconfig/voxy-server.toml`：

- `ingestGeneratedChunks` 默认开启，只处理新生成区块。
- `ingestLoadedChunks` 默认关闭；开启后也会处理已有区块，CPU 和磁盘负载会增加。
- `serviceThreads` 控制后台线程数。
- `maxIngestQueue` 为区块快速生成时提供背压保护。
- `serveRemoteLod` 控制是否允许兼容的 Voxy 客户端下载缺失缓存。
- `maxRemoteRequestsPerSecond` 与 `maxRemoteResponseBytes` 限制每位玩家的请求速率和单包响应大小。

管理员可使用 `/voxy server status` 查看缓存路径、采集计数和网络统计。兼容客户端会先协商协议，再批量请求缺失 section，把服务端方块/生物群系映射转换成本地编号并写入自己的缓存。服务端 LOD 变化时会批量发送轻量失效通知，客户端删除旧条目，并按服务端公布的请求速率刷新本次会话用过的 section。客户端可关闭 `useServerLod`，服务端也可关闭 `serveRemoteLod`。

## 已知兼容性

| 模组/场景 | 状态 | 说明 |
|-----------|------|------|
| Sodium 0.6.13-neoforge 到 0.8.12-beta.2 | 必需，CI 测试 | 0.6.13 和 0.8.12 使用不同的渲染挂钩签名 |
| Iris 1.8.14-beta.1 | 已测试 | 光影可用；Voxy LOD 暂不写入 Iris 阴影贴图，以避免 shadow pass 崩溃 |
| C2ME 0.4.0 alpha (NeoForge) | 兼容，间接优化 | 只加速单人世界的服务端区块任务；Voxy 的客户端 LOD 首次加载采用逐帧预算，避免进世界时集中争抢资源。连接远程服务器时 C2ME 不会加速 Voxy |
| Create / Create Aeronautics | 已测试 | 在大型机械动力/航空学客户端整合包中测试通过，仍按客户端兼容性处理 |
| Sable | 已测试 | 可与当前 Voxy 渲染路径共存 |
| Modern UI | 已知冲突 | 可能导致 Voxy 有缓存但不绘制；遇到 LOD 不显示优先关闭它 |
| 专用服务器 | Companion 支持 | 可选安装；生成并按限额提供 LOD 缓存，不加载客户端渲染代码 |

没有列出的模组不代表不兼容，只是没有作为主要目标确认。小型 UI、美化、辅助类模组不在这里逐个记录。

## 已知限制

- Iris 光影阴影不会包含 Voxy LOD 远景。
- Debug screen 集成暂时关闭。
- 远程更新采用“失效通知 + 限速重拉”，不会立即推送完整 section 数据。

## 安装

1. 安装 Minecraft 1.21.1 + NeoForge。
2. 安装 Sodium、Forgified Fabric API。
3. 如果需要光影，安装 Iris。
4. 构建本项目，把生成的 jar 放进客户端 `mods` 文件夹。
5. 服务端 companion 是可选的；需要服务端预生成缓存时，把同一个 jar 放入服务器 `mods` 文件夹。

## 构建

```bash
git clone https://github.com/xiaoancute/voxy-neoforge.git
cd voxy-neoforge
./gradlew build
```

构建产物在 `build/libs/`。

## 链接

- 原版 Voxy: [github.com/MCRcortex/voxy](https://github.com/MCRcortex/voxy)

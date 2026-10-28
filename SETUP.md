# 安装

安装 Baritone 最简单的方式是把它作为 Forge/Neoforge/Fabric 模组安装，但如果你知道怎么做，也可以用自定义的 `version.json` 来使用它
（示例：[1.14.4](https://www.dropbox.com/s/rkml3hjokd3qv0m/1.14.4-Baritone.zip?dl=1)、[1.15.2](https://www.dropbox.com/s/8rx6f0kts9hvd4f/1.15.2-Baritone.zip?dl=1)、[1.16.5](https://www.dropbox.com/s/i6f292o2i7o9acp/1.16.5-Baritone.zip?dl=1)）。

Baritone 安装好之后，看[这里](USAGE.md)了解如何使用它。

## 预构建的官方发行版

发行版很少发布，而且并不总是跟得上最新的特性和缺陷修复。

发行版页面链接：[Releases](https://github.com/cabaletta/baritone/releases)

Minecraft 版本与 Baritone 主要版本的对应关系如下

| Minecraft 版本    | 1.12 | 1.13 | 1.14 | 1.15 | 1.16 | 1.17 | 1.18 | 1.19 | 1.20  | 1.21  | 1.21.4 | 1.21.5 |  1.21.6 - 1.21.8 |
|-------------------|------|------|------|------|------|------|------|------|-------|-------|--------|--------|------------------|
| Baritone 版本     | v1.2 | v1.3 | v1.4 | v1.5 | v1.6 | v1.7 | v1.8 | v1.9 | v1.10 | v1.11 | v1.13  | v1.14  | v1.15            |

任何官方发行版都会由 leijurv（44A3EA646EADAC6A）进行 GPG 签名。请验证你下载的文件的哈希值位于 `checksums.txt` 中，并且 `checksums_signed.asc` 是该公钥对 `checksums.txt` 的有效签名。

构建是完全确定且可复现的，你可以自己运行 `docker build --no-cache -t cabaletta/baritone .` 并对比 shasum 来验证这一点。这在 Travis、Mac 和 Linux 上表现完全一致（如果你在 Windows 上装了 docker，并且愿意告诉我它在那里是否也能正常工作，我会非常感激）。

## 构建产物

构建 Baritone 会在 ``dist`` 目录中生成最终产物。它们与 [releases](https://github.com/cabaletta/baritone/releases) 中创建的产物相同。

**Forge、NeoForge 和 Fabric 的发行版可以直接作为 Forge/Neoforge/Fabric 模组添加。**

如果你的其他某个模组带有 Baritone 集成，你需要的是 `baritone-api-*-VERSION.jar`。
如果你想报告缺陷并帮我们省点力气，你需要的是 `baritone-unoptimized-*-VERSION.jar`。
否则，你需要的是 `baritone-standalone-*-VERSION.jar`

下面是各种限定词的含义

- **API**：只有非 api 的包会被混淆。这应该用在其他模组想要使用 Baritone 特性的环境中。
- **Standalone**：所有内容都被混淆。其他模组无法使用 Baritone，但你能获得一点额外的性能。
- **Unoptimized**：什么都不混淆。这不应在生产环境中使用，但对崩溃报告非常有帮助。

- **无加载器（No loader）**：可通过自定义 `version.json`，作为针对原版 Minecraft 的 launchwrapper tweaker 加载。
- **Forge/Neoforge/Fabric**：可使用相应加载器作为标准模组加载。Fabric 构建在 Quilt 上可能能用，也可能不能用。

如果你从源码构建，还会在 `dist` 目录中找到映射文件。它们包含 ProGuard 所做的重命名，如果你想阅读混淆后的堆栈追踪，这些文件会很有用。

## 自行构建

- 克隆或下载 Baritone

  ![Image](https://i.imgur.com/kbqBtoN.png)
  - 如果你选择下载，请确保下载了正确的分支并解压 ZIP 压缩包。
- 按你的偏好，照下面其中一组说明操作

## 命令行

在 Mac OSX 和 Linux 上，使用 `./gradlew` 而不是 `gradlew`。

Minecraft 版本推荐的 Java 版本如下

| Minecraft 版本                | Java 版本     |
|-------------------------------|---------------|
| 1.12.2 - 1.16.5               | 8             |
| 1.17.1                        | 16            |
| 1.18.2 - 1.20.4               | 17            |
| 1.20.5 - 1.21.8               | 21            |

下载 java：<https://adoptium.net/>

要检查你正在使用的 java 版本，在命令提示符或终端中执行 `java -version`。

### 构建 Baritone

这些任务取决于 minecraft 版本，但（大体上）是构建模组的标准做法。

更多细节见你想构建的那个分支的[构建 CI action](/.github/workflows/gradle_build.yml)。

对大多数分支来说，`gradlew build` 应该会构建全部内容，但也有例外，并且本文件可能已经过时。

更具体地说，在较旧的分支上，过去的做法是 `gradlew build` 构建 tweaker jar，
而要改为构建 Forge/Fabric 版本则需要 `gradlew build -Pbaritone.forge_build` / `gradlew build -Pbaritone.fabric_build`。
并且你可能需要先运行 `setupDecompWorkspace`。

## IntelliJ

- 在 IntelliJ 中以 Gradle 项目方式打开该项目
- 刷新 Gradle 项目（或者为保险起见，直接重启 IntelliJ）
- 根据 minecraft 版本的不同，你可能需要运行 `setupDecompWorkspace` 或 `genIntellijRuns` 才能让一切正常工作

## Github Actions

大多数分支在 `.github/workflows/gradle_build.yml` 处都有一个 CI 工作流。如果你 fork 了本仓库并为你的 fork 启用了 actions，
你可以推一个空提交来触发它，让 GitHub 替你构建 Baritone。

如果你想构建的提交在 90 天以内，你也可以在
[这个列表](https://github.com/cabaletta/baritone/actions/workflows/gradle_build.yml)中找到对应的工作流运行记录，并从那里下载产物。

> English version: [SETUP.en.md](SETUP.en.md)

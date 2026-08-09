# Astrolabe Android Runtime

[English](README.md) | 简体中文

Astrolabe Android Runtime 在开发阶段向 Astrolabe Host 提供 Android View
层级和表现属性。应用只需添加一项 Debug 依赖；Runtime 会自动启动，并且不会进入
Release 构建。

当前版本：`2.1.0`。

## 环境要求

- Android API 23 或更高版本
- 使用 Android View 构建的界面
- 已安装 [Astrolabe Host](https://github.com/regulusleow/astrolabe/blob/main/README.zh-CN.md#安装)，
  并为受支持的 AI 客户端完成配置

当前暂不支持 Jetpack Compose 检查。

## 安装

启用 Maven Central，并且只在 Debug 变体中添加 Runtime：

```kotlin
dependencies {
    debugImplementation(
        "io.github.regulusleow:astrolabe-runtime-android:2.1.0"
    )
}
```

无需修改 Application、Activity、View、Manifest，也无需编写启动或注册代码。
不要使用 `implementation`，否则 Runtime 会进入 Release 构建。

## 功能

- 通过 ADB 发现模拟器和 USB 真机中的 Runtime。
- 抓取 Android View 层级，并提供进程内稳定的节点 ID。
- 获取逻辑坐标、可见性、无障碍、文本、字体、颜色、图片、控件状态、滚动和资源信息。
- 按需获取节点详情。
- 对白名单内的表现属性进行内存级临时修改、替换和回滚。
- 配合 Host 完成截图、冻结快照、Baseline 和 Visual Diff。

临时补丁不会修改源码、业务模型、二进制或持久化数据。Runtime 停止或 App
进程退出后，补丁自动失效。

## 架构

| 模块 | 职责 |
| --- | --- |
| `astrolabe-runtime-core` | 帧协议、传输、会话、请求路由、取消、节点注册和补丁协调。 |
| `astrolabe-runtime-view` | Android View root、遍历、坐标、语义属性、节点详情和白名单修改。 |
| `astrolabe-runtime` | 对外生命周期 Facade、应用信息和 Composition Root。 |
| `astrolabe-runtime-distribution` | 使用 AGP Fused Library 将内部模块发布为一个 AAR。 |

Runtime 实现 `astrolabe-protocol` 定义的平台无关 Wire Protocol。Android
生命周期、数据采集、映射、本地 Socket 传输和补丁执行均保留在本仓库。

只有 `dev.astrolabe.runtime.AstrolabeRuntime` Facade 属于受支持的公开源码
API。Core 和 View 包作为实现细节合并进 AAR，不构成兼容性承诺。

## 开发

先把 Protocol Kotlin 发布到 Maven Local，再执行 Runtime 检查：

```bash
cd ../astrolabe-protocol
./gradlew :AstrolabeProtocolKotlin:publishToMavenLocal

cd ../astrolabe-runtime-android
./gradlew \
  -PastrolabeUseMavenLocal=true \
  test \
  lint \
  verifyModuleBoundaries \
  :astrolabe-runtime-distribution:verifyDistributionArtifact
```

Instrumentation 测试需要模拟器或真机：

```bash
./gradlew \
  -PastrolabeUseMavenLocal=true \
  :astrolabe-runtime-view:connectedDebugAndroidTest
```

发布维护者请参阅 [docs/releasing.md](docs/releasing.md)。

## 安全

Runtime 通过 ADB 转发访问进程级 abstract local socket。它不提供任意方法调用或
业务操作，临时修改能力受 Runtime 公布的补丁白名单限制。

请使用 `debugImplementation`，并在发布前检查 Release 依赖图。

## 许可证

Astrolabe Android Runtime 使用
[Apache License 2.0](LICENSE)。

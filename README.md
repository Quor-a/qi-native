# 栖 (qi)

> 为灵魂找一个栖息处

「栖」是一款**本地优先**的 Android AI 陪伴聊天应用。应用通过您自行配置的第三方大模型接口（OpenAI 兼容）进行对话，本身不提供模型算力，所有聊天数据默认仅保存在本机。

本项目派生自 [ZorvAI / QuroAI](https://gitee.com/ZorvAI/ZorvAI)，在派生基础上进行了去品牌化与功能重构。

## 特性

- **多会话历史**：支持对话的新建、删除、清理与持久化。
- **灵魂卡 / 人格设定**：为 AI 配置长期人设与记忆。
- **语音交互**：语音输入、系统 TTS 播报、语音球悬浮窗常驻。
- **AI 发送文件**：AI 可通过工具把文本 / 代码 / 文档 / 图片保存为真实文件并发送到当前对话，气泡内**应用内自写预览**（图片 / SVG / HTML / 文本 / 代码），无需甩给系统查看器。
- **可扩展工具子系统**：在您授权后，AI 可调用设备能力（短信、电话、联系人、日历、定位、相机、存储、闹钟等）。危险权限仅在对应工具被实际调用时请求，被拒绝时返回友好提示。
- **模型配置**：自由填写任意 OpenAI 兼容接口地址与密钥。

## 构建

需安装 Android SDK 与 JDK 17+，使用仓库内 Gradle Wrapper（Gradle 8.13）：

```bash
./gradlew :app:assembleDebug      # Linux / macOS
gradlew.bat :app:assembleDebug    # Windows
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

## 权限说明

应用声明了较广泛的权限集合以支持工具子系统，但**绝大多数危险权限默认不授予**，仅在对应功能被实际触发时请求。详见应用内「关于 → 权限使用声明」。

## 开源协议

本项目以 **Apache License 2.0** 开源，完整许可证见仓库根目录 [`LICENSE`](LICENSE)。

致谢上游 [ZorvAI / QuroAI](https://gitee.com/ZorvAI/ZorvAI) 项目及所有贡献者。

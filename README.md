# NevolutionXposed — 微信通知内回复与媒体增强

基于 NevolutionXposed 的 Android Xposed 模块。本轮开发基于作者已修改的 `.fixed` 版本追加，保留通知内原生回复流程。

**当前发布：2.0.59-voice-notification（versionCode 61，测试版，2026-11-05）。**

[下载安装包](downloads/NevolutionXposed-2.0.59-voice-notification-debug.apk) · [完整更新报告与使用说明](docs/UPDATE-2.0.59.md) · [SHA-256](downloads/SHA256SUMS.txt)

## 本次累计更新

- 保持在通知里输入、发送消息，支持键盘“发送”；不弹出独立回复界面，不增加发送确认。
- 优化下拉、悬浮与横屏通知的回复入口，处理重复回复按钮。
- 展开通知显示“好友昵称聊天框”、双方头像和左右分开的消息；压缩底部多余留白。
- 通知回复提交微信后，调用微信原生会话已读流程，按对应账号和会话核对消息状态。
- 图片按精确消息身份关联，自动请求微信正常清晰中图；保留后续消息与自己的回复。
- 自定义表情图案及动图、微信内置表情图案显示。
- 新增语音气泡、时长与微信原生自动转文字，转写内容放在同一条语音下方。
- 接入微信原生语音播放，提供播放／停止状态。**通知气泡的点击播放／停止尚待完整真机验收，请按测试功能使用。**
- 限制历史、缓存、媒体解码和后台任务容量，增加账号、会话、消息身份校验及故障回退。

## 已验证范围

本轮重点适配 **微信 8.0.79（3200）**，真机为 Redmi / HyperOS 4、Android 17 / API 37、Vector 2.2。框架、系统或微信版本不同，需自行验证兼容性。

最低声明为 Android 8 / API 26；这不代表所有新增功能在全部 Android 版本均已验证。媒体交互依赖 Android 9+，部分通知尺寸接口依赖 Android 12+。内部接口适配包含精确版本限制，不能假定微信更新后仍兼容。

## 安装与使用

1. 下载上方 APK，核对 SHA-256 后安装。当前文件是与本轮真机测试一致的 **debug 签名测试包**。
2. 在 Vector / LSPosed 中启用模块，作用域勾选 **微信 `com.tencent.mm`** 和 **系统 UI `com.android.systemui`**。
3. 更新后需要重新加载这两个进程。普通用户可重启手机一次；熟悉 Root 操作的用户也可自行重启微信和 SystemUI。
4. 让好友发送一条**新消息**，使用新生成的通知测试；重启前的旧通知不用于验证新版本。
5. 点击通知上的“回复”，直接在通知内输入并发送；展开后可查看双方消息。
6. 照片等待微信完成正常图片下载后显示清晰预览；通知内支持图片缩放、拖动与消息历史滚动，完整手势／横屏回归仍需补充。
7. 自定义表情和动图在展开通知中显示，动画在不可见时停止。
8. 收到语音后显示时长，并自动调用**微信自身**的语音转文字流程；文字返回后显示在该条语音下。转写失败时保留语音入口，不自动播放声音。

若安装提示签名不一致，不要直接卸载覆盖，以免丢失设置；先确认旧包签名。项目未公开签名私钥，也不保证与其他渠道 APK 可直接互相覆盖。

## 构建与测试

需要 JDK 17+、Android SDK 34、Gradle Wrapper 所需依赖；本轮使用 JDK 21。

```sh
./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug
adb install -r build/outputs/apk/debug/*-debug.apk
adb install -r build/outputs/apk/androidTest/debug/*-androidTest.apk
adb shell am instrument -w com.oasisfeng.nevo.xposed.test/com.oasisfeng.nevo.xposed.StabilityInstrumentation
```

本轮最终构建、JVM 单元测试、lint 通过；Android 隔离测试 **19 组、156 个断言**通过。隔离测试不等同于所有 UI、设备与真实音频场景验收。诊断代码在 `src/debug`，测试入口不发送聊天消息，接收器受特权权限限制。

## 隐私与限制

- 不接入第三方语音转写 API；转写使用微信自身流程，是否联网及其数据处理取决于微信。
- 语音只在用户点击时播放，不在收到消息时自动出声。
- 图片、表情和语音按当前账号及对应消息处理，临时媒体缓存有容量与期限限制。
- 本次公开包不包含聊天记录、测试截图、用户账号、设备日志、微信反编译文件或本地路径配置。
- 已提交微信不等同于网络对端必然送达；网络失败仍由微信处理。

## 致谢

基于 [notxx/NevolutionXposed](https://github.com/notxx/NevolutionXposed)；通知回复设计参考 [shentam/galaxywatch-wechat](https://github.com/shentam/galaxywatch-wechat)。历史版本说明可在 Git 历史中查看。

# 红旗移植验证记录

验证时间：2026-10-08T23:28:13+08:00

版本：0.3.11-hongqi.1 / versionCode 112。

## 构建及测试

| 检查 | 结果 |
|---|---|
| `hongqi-feature:testDebugUnitTest` | 47 项通过；失败 0、错误 0、跳过 0 |
| `app:testGithubDebugUnitTest` | 650 项通过；失败 0、错误 0、跳过 0 |
| `app:assembleGithubDebug` | 通过，生成签名 Debug APK |
| `app:assembleGithubRelease` | 通过，包含 R8 混淆、资源压缩及原有 native 库；生成未签名 Release APK |
| 合并 Manifest | Debug/Release：minSdk 21、原 DiAuto 启动页、红旗平台识别 Ability、私有授权/功能页面和前台服务类型均核对通过 |
| APK 完整性 | 两个 APK 的 ZIP/CRC、Manifest、DEX 和 libusbhelper.so 存在性通过 |
| 同步脚本 | `bash -n scripts/sync-upstream.sh` 通过；Git 共同祖先检查见交付检查记录 |

## APK 构建记录

| 构建 | 文件名 | 字节数 | SHA-256 |
|---|---|---:|---|
| debug | `com.andrerinas.headunitrevived.bydhudtest_0.3.11-hongqi.1-hud-test_debug.apk` | 20058076 | `577928cb005792177b1ec0a6cbec545552f665429d8602a279b8cbacd0319a02` |
| release | `com.andrerinas.headunitrevived_0.3.11-hongqi.1.apk` | 12840138 | `54efb2be8ba976ff53c11adbef8d3518d94136482b31be3194840185d1eb9af3` |

交付包的 `APK` 目录包含对应 Debug 测试包及未签名 Release 包。Debug 包沿用调试豁免、不要求激活；Release 需要使用你自己的正式密钥签名。源项目未提供发布密钥，归档不会生成或携带你的发布密钥。

## 自动验证范围

保留并运行原项目 650 项测试；红旗模块运行 47 项测试，覆盖显示屏策略、资源发布/停止竞态、过期回调、授权状态/离线宽限、按键扫描码学习/重复消费、映射到宿主以及禁止旧投屏权限自动复用。

最终完整构建结果为 BUILD SUCCESSFUL。原项目已有 API 弃用及 Gradle 9 迁移警告；本交付使用 Gradle 8.13 与 AGP 8.13.2。

## 仍需实机确认

没有连接红旗车机或真实仪表显示屏，因此未执行实车飞屏、方向盘事件接收、窗口覆盖层级、熄屏唤醒及停止后原车导航恢复测试。授权后台未提供测试账号，也未发送测试设备注册或激活请求；客户端协议按附件保留。具体步骤见 HONGQI_INTEGRATION.md。

# DiAuto 完整视频飞屏验证（0.3.11-hongqi.3）

## 结果

Debug 与 Release 构建成功，Release 通过 R8 与 lintVital。共 709 项测试通过：宿主 655 项，红旗模块 54 项，0 失败、0 错误。保留并回归了首页横/竖屏入口检查；新增完整视频比例、飞屏页面启动、媒体进度/暂停、重复元数据、换歌清除封面与断开清空的测试。

验证命令：

```bash
./gradlew --no-daemon --max-workers=2 :app:assembleGithubDebug :app:assembleGithubRelease :app:testGithubDebugUnitTest :hongqi-feature:testDebugUnitTest
python scripts/check-home-layouts.py
git diff --check
```

APK 已检查 ZIP 完整性、版本和 Debug 签名；合并 Manifest 不含 MediaProjection 权限或服务类型，飞屏服务为 specialUse。版本号为 114，最低 API 21，目标 API 36。

## APK

| 构建 | 文件 | 字节数 | SHA-256 |
|---|---|---:|---|
| Release（未签名） | `com.andrerinas.headunitrevived_0.3.11-hongqi.3.apk` | 12823594 | `9fb99c952efc8c0af3ea87cc36ad4447cc0b9500781b8fdf3660a502a955923d` |
| Debug（已签名） | `com.andrerinas.headunitrevived.bydhudtest_0.3.11-hongqi.3-hud-test_debug.apk` | 20799299 | `5ddc43c51e88095aa3e2e1eab63772b65fd74db68bfddf446c2096f8ea8e3e8c` |

Debug 使用项目中的公开开发密钥，便于后续重建覆盖升级。其证书与之前的 .2 Debug 包不同，首次更新可能需要卸载旧 Debug 包（会清空该包的设置）。正式发布继续用自己的私有发布密钥；未签名 Release 不能直接安装。

## 设备验证范围

没有连接真实红旗仪表或用户的木木模拟器。自动测试没有验证 EGL/MediaCodec 在这些设备上的双屏输出、帧率、窗口覆盖层级或原车导航恢复，不能据此承诺实车稳定性。

建议实机验收：

1. 连接手机的 AA 会话，打开原车仪表导航投屏，再启动 DiAuto 飞屏。视频应完整显示、比例正确；不会弹录屏授权。
2. 退出红旗设置、回到中控投影、切换应用和旋转屏幕，确认仪表持续显示，中控触摸准确；主屏 HUD 镜像不改变仪表方向。
3. 导航开始/停止、左/右转、环岛与目的地，确认箭头、距离和道路正确；停止后清空提示。
4. 切歌、带/不带封面、暂停/恢复和拖动播放进度，确认独立音乐卡片更新，不继承旧封面和进度；关闭媒体会话同步设置后卡片仍能更新。
5. 反复启动/停止、手机断开/重连、仪表移除和熄屏，确认清理窗口、无旧回调重开，停止后原车导航恢复。
6. 木木需显式开启模拟器预览并连接手机，预览展示同一视频和卡片；可点“停止预览”。只有模拟器而没有 AA 视频输入时，飞屏会拒绝启动。

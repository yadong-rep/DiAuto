# DiAuto 红旗功能移植说明

本项目保留 DiAuto 原有 Android Auto 无线/USB连接功能，新增独立的 `hongqi-feature` Android Library。四项功能在同一个 APK 中运行，不需要同时安装旧 HongqiFeiPing。

## 功能入口

- DiAuto 首页：点击「红旗功能 · 飞屏 / 按键 / 侧边栏」。
- Android Auto 已连接：打开投影页面原有的退出/快捷操作菜单，选择同名入口。
- 红旗功能页：飞屏、按键映射、启动、侧边栏四个标签页；顶部「返回 DiAuto」返回主项目，已有 AA 连接可继续使用。

## 飞屏

保留附件中的实现：MediaProjection 采集主屏，OpenGL 合成最多三个区域，通过 VirtualDisplay 和 Presentation 在仪表显示；模拟预览保留录制 MP4 的路径。支持屏幕裁剪、比例/输出宽度、图片、音乐封面及歌词等现有配置。

本版本不包含 RTP/GWRtpService 控制，也没有新增 VHAL 操作。它沿用附件的 `PresentationScreen` 显示屏选择规则。实车必须先开启原车导航仪表投屏，系统能够提供这个非主屏显示设备；普通手机或没有该显示设备的车机不能据此判断实车功能是否正常。模拟预览用于检查合成结果。

每次新的飞屏会话均请求新的屏幕录制授权，不再把含 Binder 的录屏授权 Intent 写入磁盘。Android 14+ 请求完整默认显示屏，并在用户授权后才启动 mediaProjection 前台服务。投屏权限和应用激活授权是两种不同授权。

启动 OpenGL 的等待、停止渲染线程与录制线程的等待在独立工作线程执行。资源状态仍经过 IDLE → STARTING → RUNNING → STOPPING → IDLE；停止清理未完成时拒绝开始新会话，过期 token 不得重新发布资源。

## 按键映射

保留无障碍按键学习、keyCode/scancode 指纹、多目标音乐应用映射和投屏快捷键。原附件中的「双击投屏键」存储名称沿用，但原实现实际上是单击切换，移植未改变这一行为。

DiAuto 的主界面、AA投影界面、OEM按键广播、媒体会话和媒体键 Intent 均先交给映射处理；被映射/学习消费的事件不再交给原处理链。未匹配事件沿用 DiAuto 逻辑。

绑定 DiAuto 自身时，媒体动作通过宿主适配层发送给已连接的手机，不把动作重新派发到本应用媒体会话；没有 AA 连接则不报告派发成功。绑定其他应用仍通过活动媒体会话定向派发。

在系统设置中开启本应用的「按键映射服务」及「按键映射媒体服务」权限。方向盘原始事件能否到达普通应用取决于车机系统，不能通过普通应用代码强行保证。

## 侧边栏与启动

保留边缘滑动、左右位置、颜色、透明度、圆角、应用快捷入口、全局返回/桌面/最近任务、媒体控制、亮度/音量/网络设置及投屏开关。相机权限由手电筒操作引导申请；通知权限在开启侧边栏时申请。Android 14+ 使用 specialUse 前台服务维持用户开启的悬浮侧边栏，通知可返回设置。

需要开启「显示在其他应用上层」权限。全局动作需要无障碍服务；修改亮度/旋转等需要系统允许修改设置。新系统不允许普通应用直接开关的功能仍按附件实现打开系统设置。

开机启动其他应用的配置使用 `hongqi_autostart` 偏好文件，与 DiAuto 自启分开。红旗标签页的「开机启动本应用」默认关闭，避免与 DiAuto 的自启重复；可按需开启。系统禁止后台拉起页面的机型仍受系统限制。

## 应用激活授权

沿用附件的服务器地址、注册/校验接口、设备指纹和请求 HMAC 签名协议，无需改服务器协议。默认地址仍是附件配置的 `https://hongqi.ypyp.net`。ACTIVE 本地凭证保留七天离线宽限期；DISABLED/PENDING 拒绝运行，过期后进入联网校验。

统一的 ActivationActivity 负责授权界面。DiAuto 页面入口、AA后台服务、红旗飞屏服务、按键动作和自动启动入口使用相同守卫；授权成功返回此前请求的应用内部页面。控制器销毁后丢弃网络回调，防止旧 Activity 被重建。

附件的 Debug 调试豁免保持不变：Debug APK 不要求激活，Release APK 执行授权。不要把 Debug 构建当作需要授权的正式发布包。

Application ID 继续使用 DiAuto 的 `com.andrerinas.headunitrevived`；Debug 后缀沿用原项目。旧 HongqiFeiPing 的私有设置不会跨应用自动读取。服务端能否识别为同一设备还与签名证书、Android版本及设备指纹有关；重新注册/激活按现有后端处理，不伪造或自动复制旧授权。

## 模块边界

- `hongqi-feature`：所有红旗 Java 代码、资源、服务、授权页面、资源状态机和测试。
- `app/.../hongqi/HongqiBridge.kt`：唯一宿主适配实现，提供主页面 Intent 和向手机发送媒体动作。
- `FeatureNavigation.Host`：宿主接口。功能模块不引用 DiAuto 类，也不依赖 `:app`。
- `FeatureIntegration`：生命周期授权、功能入口及统一按键接入。

红旗资源统一使用 `hongqi_` 前缀；内置素材放在 `assets/hongqi/builtin`。宿主的图标、主题和启动 Activity 保持 DiAuto 设置。保留红旗平台应用识别元数据，Ability 由宿主 Manifest 显式覆盖为 DiAuto 主 Activity。原附件允许 HTTP 图片/配置地址，合并后的网络安全配置保持该兼容性。

## 构建

需要 JDK 17、Android SDK 36、Build Tools 36.0.0、NDK 29.0.14206865、CMake 3.22.1。最低 Android 版本为 5.0 / API 21（原红旗功能本就依赖 MediaProjection）。补回了附件缺失的 `contract` 模块和 Gradle wrapper；contract 与宿主编译 SDK 对齐。

解压后用 Android Studio 打开根目录，配置本机 SDK。归档不携带开发环境的 `local.properties`。保留原发布签名配置，使用你自己的原有签名密钥。

```bash
./gradlew :hongqi-feature:testDebugUnitTest :app:testGithubDebugUnitTest
./gradlew :app:assembleGithubDebug :app:assembleGithubRelease
```

Windows 使用 `gradlew.bat`。Release 未配置签名时输出未签名 APK；不能直接覆盖其他证书签名的已安装应用。请保留你的正式发布密钥，以便后续覆盖升级。

## 实机验收

1. Release 首次启动进入授权页，激活成功返回 DiAuto；禁用设备不能通过后台启动 AA 或红旗服务。
2. 无线及USB Android Auto 原有功能正常；已连接状态能从投影菜单进入红旗配置。
3. 开启原车导航仪表投屏，再在红旗页启动飞屏；检查主屏与仪表的裁剪、缩放和各区域显示。
4. 连续启动/停止、录屏授权取消、仪表显示移除、熄屏/唤醒：无界面卡住、旧 token 重启或残留覆盖层；停止后原车导航能够恢复。
5. 按键学习保留扫描码；同一按键只执行一次；未映射按键仍由 DiAuto 处理。分别测试本地音乐目标和 DiAuto/手机音乐目标。
6. 侧边栏开启/关闭、应用切换、权限取消及重启恢复；后台播放时控制正确目标应用。

编译和自动测试无法替代以上车机显示权限、OEM窗口层级及原导航恢复测试。实际构建与自动测试结果见 `HONGQI_VALIDATION.md`。

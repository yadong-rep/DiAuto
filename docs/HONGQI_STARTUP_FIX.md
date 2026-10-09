# 0.3.11-hongqi.2 启动闪退修复

用户在 MuMu Android 12 上提供的日志中，DiAuto Debug 进程多次出现相同异常：
`HomeFragment.onViewCreated(HomeFragment.kt:44)` 对空对象调用 `setOnClickListener`。

首页存在默认和 `layout-port` 两个布局。原移植只给默认布局加入
`hongqi_features_button`，竖屏加载时该控件不存在。

本次修复给竖屏布局补齐同一入口，保留横竖屏各自的布局。
版本更新为 `0.3.11-hongqi.2` / versionCode `113`。

新增 `HomeLayoutTest`，使用 Android 12 / API 31 的 Robolectric 分别加载横屏、
竖屏资源并检查首页使用的全部控件；启用单元测试 Android 资源加载。
测试清单沿用宿主的可选依赖及 Ability 元数据覆盖；布局测试与已有 Locale 测试
一致关闭桌面 JVM 上的 Android 专用 Conscrypt 加载，不涉及产品中的 TLS 配置。
`python3 scripts/check-home-layouts.py` 还可检查所有首页布局中的控件是否齐全。

这是日志已确认的首页启动崩溃修复，不代表已完成车机或 MuMu 本体的端到端实测。
之前交付的 `.1` APK 仍含该问题，应使用本次重新构建的 `.2` APK。

Debug 包的应用 ID 保持 `com.andrerinas.headunitrevived.bydhudtest`。
如果覆盖安装提示 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，说明不同构建环境使用了
不同的 Debug 签名证书。需先保存所需设置，再卸载旧 Debug 包后安装；卸载会删除
该 Debug 包的应用数据。Release 包仍需使用你自己的正式发布密钥签名。

## 本次验证记录（2026-10-09）

| 检查 | 结果 |
|---|---|
| `app:assembleGithubDebug` | 通过，versionCode 113 |
| `app:assembleGithubRelease` | 通过，未签名 Release APK；包含 R8 及资源压缩 |
| `app:testGithubDebugUnitTest` | 652 项通过，失败/错误/跳过均为 0，含横竖屏资源加载测试 |
| `hongqi-feature:testDebugUnitTest` | 47 项通过，失败/错误/跳过均为 0 |
| 所有首页布局控件检查 | 两种布局均包含 HomeFragment 引用的全部 6 个控件 |
| APK 资源检查 | Debug 横竖屏布局均打包了红旗功能入口；版本和签名核对通过 |
| APK 完整性 | Debug/Release ZIP CRC、DEX 及四种 ABI 的 USB JNI 库检查通过 |

本次合计 699 项测试通过，完整 Gradle 命令返回 `BUILD SUCCESSFUL`。

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| Debug APK | 20058152 | `eba4359dc1ec20584dbb19e5f4f194c4dbea598ad6462051071921c889532edf` |
| Release APK | 12840178 | `0b56afddc9c26d659ed4aca214d11004cb918c840651fc503805190b3c747e99` |

旧 `.1` 与新 `.2` Debug 包的签名证书已确认不同，因此这份新 Debug APK 需要
卸载旧 Debug 包后安装。当前环境未连接用户的 MuMu 或真实车机，未执行其上的
实际启动、飞屏、按键和停止后原车导航恢复测试。

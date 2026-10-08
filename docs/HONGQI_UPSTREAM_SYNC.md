# 保留红旗功能并同步 DiAuto 上游

交付目录保留了 Git 历史，当前分支为 `hongqi-integration`。实际共同祖先来自 `shihabal3amri/DiAuto` 的 `a2119f4`（0.3.11）。其后分别记录附件快照/缺失构建输入和红旗移植改动。

不是对 ZIP 执行 git init 后再合并无关历史；后续更新可以用正常 Git 三方合并。首次导入仅取了上游当时最新提交，历史更早的提交可通过 `git fetch --unshallow upstream` 获取。

## 配置你的 fork

已配置 `upstream` 指向 `https://github.com/shihabal3amri/DiAuto.git`。尚未设置或推送你的远程仓库，因为没有收到你的 fork 地址。

在项目根目录执行：

```bash
git remote add origin <你的fork仓库地址>
git push -u origin hongqi-integration
```

已有 `origin` 时先用 `git remote -v` 确认来源；需要更换才执行 `git remote set-url origin <你的fork仓库地址>`。不要向 `upstream` 推送个人移植。

## 同步更新

工作区干净后运行：

```bash
bash scripts/sync-upstream.sh
```

脚本获取上游，创建独立 `sync-upstream/时间` 分支，并合并 `upstream/main`。不覆盖现有工作，不自动推送或发布。Windows 可在 Git Bash 中运行。

存在冲突时在同步分支解决并提交；放弃本次同步可执行 `git merge --abort`。不要用“全部接受上游”覆盖功能接入代码。

同步成功后：

```bash
./gradlew :hongqi-feature:testDebugUnitTest :app:testGithubDebugUnitTest
./gradlew :app:assembleGithubDebug :app:assembleGithubRelease
```

在车机验证无线/USB连接、飞屏启动停止及按键操作后，回到 `hongqi-integration`，合并脚本输出的同步分支名，最后推送你的 `origin`。

## 合并时重点核对

- `settings.gradle.kts` 保留 `:hongqi-feature`。
- `app/build.gradle.kts` 保留模块依赖、minSdk 21、平台识别元数据覆盖。
- `App.kt` 保留 `HongqiBridge.install`；首页和投影操作菜单保留功能入口。
- `AapService.kt` 保留授权检查及未初始化时的安全退出。
- 主界面/投影界面、OEM按键接收器、媒体会话与 `AapBroadcastReceiver` 保留优先映射分发；`CommManager.sendKey` 保留授权门槛。
- `HongqiBridge.kt` 若上游改变媒体命令 API，仅在适配层更新。

红旗模块本身不修改上游同名资源，通常可以整体保留。上游若修改连接服务、按键路径、Manifest或构建工具，仍需要人工解决冲突及回归验证，不能保证零冲突。

发布版本使用 0.3.11-hongqi.1 / versionCode 112。以后合并新版本时保留上游版本含义，并保证你正式发布的 versionCode 高于已安装版本；不要直接用上游版本号替换个人发布序列。

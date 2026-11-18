# Jezail 现场部署流程

> 适用版本：`jezail-debug.apk`（versionName 2.0.0，versionCode 5，debug 签名）
> 包名：`com.azzahid.jezail`
> 本文档覆盖：PC 打包 → 现场安装 → root 方案 → 开机自启验证 → 功能验证 → 排错 → 回滚

多台设备同时在线时，所有 `adb` 命令加 `-s <设备IP>:5555` 指定目标。

---

## 1. 交付物清单

| 文件 | 说明 |
|---|---|
| `dist/jezail-debug.apk` | 主程序包，本目录即拿即装 |
| `dist/DEPLOY.md` | 本文档 |
| `../tools/su/`（仓库根目录下） | 仅 A40 类 eng 板需要：`su.c`、`sudaemon.c` 源码及编译产物，`su.stub.A40.bak` 为原厂 stub 备份 |

---

## 2. PC 端打包（改完代码后）

前置：JDK 17、Android SDK（含 platforms android-36、build-tools 36.0.0）。

```powershell
cd D:\app_project\npark\Code\jezail

# 首次需要：local.properties（已被 .gitignore 忽略）
# 内容：sdk.dir=C:/Users/<你的用户名>/AppData/Local/Android/Sdk

# 构建
.\gradlew.bat assembleDebug

# 拷贝产物到 dist（dist 即交付目录）
Copy-Item -LiteralPath '.\app\build\outputs\apk\debug\app-debug.apk' `
  -Destination '.\dist\jezail-debug.apk' -Force

# 校验签名
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify .\dist\jezail-debug.apk
# 应无报错输出
```

构建要点：

- `app/build.gradle.kts` 会在构建时从 GitHub 下载 Web UI（`web-assets.zip`）和 Refrida（`refrida.zip`）并打进包，构建机需要能访问 `github.com`。
- 无 `google-services.json` 时 Firebase 相关插件会自动跳过（日志里 `ERROR: google-services.json not found` 属正常现象，不影响构建）。

---

## 3. 现场安装（每台设备通用）

```bash
# 1. 如装过旧签名/旧版本的 jezail，先卸载（签名冲突装不上）
adb uninstall com.azzahid.jezail

# 2. 安装（-g 自动授予运行时权限）
adb install -g -r jezail-debug.apk

# 3. 手动打开一次 App（关键！装完没打开过的 App，系统不会投递 BOOT_COMPLETED）
#    进界面确认：没有红色 "Root required"/"Root access required" 横幅

# 4. 悬浮窗授权（可选；R107 等系统级关闭悬浮窗的 ROM 直接跳过本步）
#    用于开机时透明 BootActivity 短暂切前台（如系统允许）
#    - 通用 ROM：在系统设置 → 应用 → 特殊应用权限 → 显示在其他应用上层 → 允许 Jezail
#    - 实验室批量：adb shell appops set com.azzahid.jezail SYSTEM_ALERT_WINDOW allow（部分 ROM 重启后会被清回，注意复查）

# 5. 重启，验证开机自启
adb reboot
# 重启后约 1 分钟内执行：
curl http://<设备IP>:8080/api/status
# 期望返回 {"status":"ok",...}
```

验证通过标准：

- `http://<设备IP>:8080/` 能打开 Web UI
- `http://<设备IP>:8080/api/device/mirror` 点 Connect 能出画面（5 FPS 左右属正常，板子性能上限）
- `/api/device/screenshot` 能下载图片（200 + PNG 内容）
- Swagger：`http://<设备IP>:8080/api/swagger`（Android 12+）

---

### 3.5. 悬浮窗授权（已移除 App 内申请流程）

- App 内首次启动弹窗 + 权限列表 Grant 入口已移除（保留代码干净，按现场测试结论：R107 系定制 ROM 在**系统层面关闭了悬浮窗功能**，设置页显示“无法使用此功能”，且 `adb appops set` 的授权**重启后会被清回 deny**）。
- 在此类设备上悬浮窗路径不可用——属 ROM 限制，非 App 问题：照常用（手动打开 App 即全功能），或用系统自带开关（厂商自启动/后台管理，如有）解决开机自启。
- 如需恢复 App 内申请，见 git 历史（`PermissionManager.overlaySettingsIntent` + 首次启动弹窗实现）。

# 4. root 方案（按设备类型二选一）

### 4.1 正常 root 设备（Magisk / KernelSU / 自带可用 su，如 R107 板）

直接用，第 3 章装完、打开 App 授权 root 即可。本 APK 的改动点：

- 不再调用 `su -c`（部分 ROM 的 su 不支持 `-c`，投屏曾因此空白页），截屏/投屏统一走 libsu 交互式 shell + 临时文件
- 截屏文件 root 建完后自动 `chmod 644`，App 进程可读（ previously EACCES 闪退）

### 4.2 A40 类 eng 板（`/system/xbin/su` 是哑巴桩）

现象：`adb shell su -c id` 无任何输出；App 显示 Root required。

原因：

1. 自带 su 是残桩，不处理任何参数；
2. 即使换成正常 setuid su 也没用——Android App 进程自带 `NO_NEW_PRIVS` 标记，`setuid` 会被内核直接忽略（与 SELinux 无关）。App 想拿 root，只能走 **root 守护进程 + socket** 架构（Magisk/SuperSU 同理）。

部署步骤（`tools/su/` 下已有源码和编译好的二进制）：

```bash
# 0. 前提确认（/system 已是 rw；adb 本来就是 root）
adb shell id                              # 期望 uid=0(root)
adb shell mount | grep " /system "        # 期望 rw
adb remount                               # 期望 succeeded

# 1. 备份原厂 stub（已随 tools/su/su.stub.A40.bak 保存，再备一份到电脑）
adb pull /system/xbin/su su.stub.A40.bak

# 2. 推送守护进程 + socket 版 su 客户端
adb push sudaemon /system/xbin/sudaemon
adb push su /system/xbin/su
adb shell 'chmod 0755 /system/xbin/su /system/xbin/sudaemon; chown root:root /system/xbin/su /system/xbin/sudaemon'

# 3. 启动守护进程（输出 started 即成功；重启后需重新执行一次）
adb shell /system/xbin/sudaemon
adb shell 'cat /data/local/tmp/minsu.log'
# 期望：minsu: daemon started ...

# 4. 验证
adb shell 'su -c id'                      # 期望 uid=0(root)
adb shell 'su -mm -c id'                   # 期望 uid=0（libsu 传 -mm，客户端忽略即可）
```

App 侧验证：

```bash
adb shell am force-stop com.azzahid.jezail
# 打开 App（点图标或 monkey）
adb shell monkey -p com.azzahid.jezail -c android.intent.category.LAUNCHER 1
adb logcat -d -s Root | findstr granted
# 期望：Root access granted
curl http://<设备IP>:8080/api/status
```

**重要约束（必读）：**

- daemon 掉电/重启后就没了（内存进程），**每次重启板子后都要重新执行一次 `/system/xbin/sudaemon`**，再重开 App（或等 App 下次启动）。
- 这套 su 让**本机任意 App 都能拿 root**，仅限实验室测试机，**绝不能随出货固件发布**（出厂重刷会清空，正好）。
- 回滚（恢复原厂 stub）：
  ```bash
  adb shell 'pkill -f sudaemon; echo killed'
  adb push su.stub.A40.bak /system/xbin/su
  adb shell 'chmod 6755 /system/xbin/su; chown root:shell /system/xbin/su; ls -l /system/xbin/su'
  ```

---

## 5. 开机自启说明

- jezail 自带 `BootReceiver`：`BOOT_COMPLETED` / `QUICKBOOT_POWERON` → 启动 `HttpServerService`（8080）→ 如有悬浮窗权限再经透明 `BootActivity` 短暂前台兜底 → 退后台。
  - 注意：历史 bug——旧 Manifest 里开机广播的 intent-filter 带了 `<data android:scheme="package"/>`，导致 `BOOT_COMPLETED`（无 data）永远匹配不上，自启从未生效。**当前包已修复**（开机广播独立 filter，不带 data 限制）。
- 如与 npark 同机部署：npark 侧 `BootReceiver` 已加 60 秒延迟启动（npark 源码改动、自行编译打包），避免强前台竞争；jezail 先起，npark 60 秒后接管。
- Android 10 要求 `startForegroundService()` 后 5 秒内 `startForeground()`，当前包已把 `startForeground()` 提前，慢板子也不会被系统杀。

---

## 6. 故障排查速查

| 现象 | 查什么 | 处理 |
|---|---|---|
| 装完打开显示 Root required / 红条 | `adb shell su -c id` 有无输出；`logcat -s Root` 看 granted 还是 denied | 走第 4 章对应设备方案 |
| 重启后服务没起来 | 装完是否手动打开过一次（没打开不收 BOOT_COMPLETED）；`logcat -s BootReceiver` 有无 `boot received` | 打开一次再重启；仍无日志查厂商自启动白名单/锁屏 |
| 投屏点 Connect 后空白页 | WS 连上但无帧 = `captureScreenshotBytes` 返回 null；`curl /api/device` 看 `isRooted` | 有 root 仍空白 → 抓 `logcat -s MainViewModel HttpServerService` 发回分析 |
| 开机自启后网页能连但空白页（点 App 图标后正常） | 先确认通知栏是 URL 还是“已停止”；服务刚起（重试逻辑约 30-60 秒才拉起）+ Flutter 首屏要下载 ~3MB main.dart.js，**打开太早就是黑屏** | 重启后等 1 分钟再开页面；空白先 Ctrl+F5 硬刷新（Flutter 启动时 API 失败不会自动重试）；仍空白则确认打开的是哪个页（主控台/mirror/terminal）并抓当时 `logcat` |
| 截屏闪退 / SIG 9 | 已修复（chmod + 先读后删 + 读失败走 500）。若仍崩，贴 `logcat -b crash` 的 `FATAL EXCEPTION` 堆栈 | 按堆栈定位 |
| 服务起来了但部分接口 500 | root 相关接口需要 root；先确认 Root granted | 无 root 环境属预期，见 `isRooted` 字段 |
| daemon 突然不工作 | `ps \| grep sudaemon` 是否还在；`cat /data/local/tmp/minsu.log` | 重启 daemon（第 4.2 章步骤 3）；App 也要重开（旧 shell 已断） |
| `adb` 提示 waiting for device | 设备 IP 变了（DHCP）；`adb devices` 确认当前 IP | 用 `-s <新IP>:5555`；.107/.44 均为历史 IP，以现场 `adb devices` 为准 |

抓日志常用命令：

```bash
adb logcat -d -s Root MainViewModel HttpServerService BootReceiver BootActivity
adb logcat -d -b crash | findstr FATAL
adb shell dumpsys package com.azzahid.jezail | findstr -i boot
curl http://<设备IP>:8080/api/device | findstr isRooted
```

---

## 7. 版本记录

- 2.0.0（versionCode 5，debug 签名）：开机自启修复（Manifest filter / QUICKBOOT / FGS 5 秒 / START_STICKY null-intent 恢复 / 15s×3 重试 / 透明 BootActivity + 悬浮窗兜底）；截屏 EACCES 修复（chmod 644 + respondBytes）；投屏去 `su -c` 化（libsu 交互式 shell + 临时文件，兼容无 `-c` 的 su）；App 内悬浮窗申请（首次弹窗 + 权限列表 Grant）曾上线后移除——R107 系 ROM 系统级关闭该功能，见 3.5；`/api/package/install` 普通 APK 误判修复（APK 本是 zip 格式，原逻辑必进 splits 分支报 409，现按包内 `AndroidManifest.xml` 直装，坏包走 400）。
- `tools/su/`：A40 专用 minimal-su（socket client）+ minimal-sudaemon（framed stdio，setns 全局挂载），附带 `su.stub.A40.bak` 原厂备份。

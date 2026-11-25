# Blbl BTR

基于 [cat3399/blbl](https://github.com/cat3399/blbl) 的原生点播加速分支，参考 [MrTangLuyao/Bilibili-thread-ripper](https://github.com/MrTangLuyao/Bilibili-thread-ripper) 的并发 Range 下载、分块校验和 CDN 调度思路。

[项目仓库](https://github.com/NNNNNNAAAAAA/blbl-btr) · [下载 APK / Releases](https://github.com/NNNNNNAAAAAA/blbl-btr/releases) · [构建与更新记录](https://github.com/NNNNNNAAAAAA/blbl-btr/actions)

## 安装版本

| 版本 | 系统 / ABI | 包名 |
| --- | --- | --- |
| 电视 32 位 | ARMv7 / `armeabi-v7a` | `blbl.cat3399.btr` |
| 电视 64 位 | ARMv8 / `arm64-v8a` | `blbl.cat3399.btr` |
| 手机竖屏 | 64 位 Android / `arm64-v8a` | `blbl.cat3399.btr.phone` |

手机浏览、详情、设置和登录界面使用竖屏布局，视频、直播播放页支持随设备旋转。手机与电视包名独立，可与原版并存；首次安装各版本时需要重新登录。处理器支持 64 位但系统只有 32 位时，应安装电视 ARMv7 包。

## Android TV 快捷启动

电视版支持 `blbl.cat3399.btr://open`，直接打开应用首页。可在 Android TV 遥控器或盒子助手中把它设为应用快捷链接；手机版本对应 `blbl.cat3399.btr.phone://open`。普通桌面启动入口继续保留。后续覆盖更新也保留相同入口。

## 点播加速

- 仅对 **ExoPlayer 的视频、番剧点播**生效，不含直播加速、Ijk 加速或浏览器播放器接管、悬浮球等功能。
- **默认开启，并发上限 8**：设置 → 其他设置 → BTR 多线程加速 / BTR 并发连接数，可选 2、4、8；修改后重新打开视频。音视频共享最多 8 个下载连接，音频最多 2 个，不要求连接数始终占满。手机与两个电视架构采用相同默认值。
- 分块按顺序交给播放器，并校验 Range、正文长度及文件总长；断流重试可保留已验证的连续前缀。仅使用播放接口给出的完整媒体地址和备用 CDN，不绕过登录、会员或其他播放权限。

尚未在实体电视上实测。实际改善取决于设备、网络、CDN 和视频码率；遇到播放问题可降低连接数或关闭加速。

## 应用内更新

发布版从本仓库的 [GitHub Releases 更新清单](https://github.com/NNNNNNAAAAAA/blbl-btr/releases/latest/download/update.json)检查更新。开启“自动检查更新”后，应用在启动时检查；也可在设置 → 关于应用 → 检查更新中手动检查。

更新先匹配电视 / 手机 profile、包名和系统支持的 ABI，再比较版本；不会把电视包作为手机更新。下载后的完整性、包名、版本及签名检查通过后才交给 Android 安装界面，安装仍需用户确认。本分支不使用原版的 APK 直链。GitHub 连接失败或匹配 APK 缺失时保留当前版本，可到 Releases 手动下载同包名、同签名的安装包。

早期仅支持手动更新的 BTR 安装包，需要先手动覆盖安装一次支持此更新源的版本。调试签名与发布签名不能互相覆盖；持续更新必须复用同一发布密钥。

## 上游维护与发布

云端 BTR 自动发布工作流保持停用，签名私钥、别名和密码仅保存在本地。本次快捷入口更新在本地隔离工作副本构建、验证和签名，再发布到 GitHub Releases；应用仍通过上述 GitHub 更新清单检查更新。公开此次版本不代表启用云端定时发布，也不需要上传签名材料。

`.github/upstream-state.json` 区分 blbl 已合入提交、BTR 的 `observed_sha` 与 `native_reference_sha`。BTR 新提交先形成待适配报告，读取实际差异并完成适用逻辑的 Kotlin 移植与测试后，才能推进原生参考版本。仅观察到上游 JavaScript 变化不会重编发布“已升级内核”的 APK。本次原生参考仍为 `ea3e940c400ceac338f3eea00281249c96966d0c`，没有额外合并两个上游的新代码。

后续合并应保留加速、快捷入口、手机竖屏与横屏适配、更新包匹配，以及完整来源和许可说明。发生合并冲突或许可、作者说明变化时停止对应更新，记录原因并核对原文。

## 本地构建

需要 JDK 17 和 Android SDK（compileSdk 36，最低 Android 5.0 / API 21）。电视一次生成两个 ABI：

```sh
./gradlew :app:assembleDebug -PversionName=0.1.29-btr.20261107 -PversionCode=20261107
```

电视调试包位于 `app/build/outputs/apk/debug/app-{armeabi-v7a,arm64-v8a}-debug.apk`。手机仅构建 ARM64：

```sh
./gradlew :app:assembleDebug -PphonePortrait=true -PversionName=0.1.29-btr-phone.20261107 -PversionCode=20261107
```

手机调试包位于 `app/build/phone/outputs/apk/debug/app-arm64-v8a-debug.apk`。正式签名包把任务改为 `:app:assembleRelease`，须配置 `keystore/release.keystore` 和下方列出的四项签名参数；对应输出目录为 `outputs/apk/release`。不传 `-PphonePortrait=true` 时使用电视构建与布局。

本地已完成的电视版本验证：正式构建、APK v1/v2 签名与 ZIP 对齐检查通过；`testDebugUnitTest` 共 101 项，100 项通过，新增加速核心的 10 项全部通过。唯一失败是上游已有的 `PlayerAutoNextTitleTest.truncatesTitlesToTwelveCharactersBeforeEllipsis`（实现为 18 字、测试要求 12 字），已用未修改的上游代码独立复现；这条记录不代表后续手机构建、CI 或实体设备测试全部通过。每次发行以相应 Actions 运行和 Release 记录为准。

## 发布签名

本次使用 `-PunsignedRelease=true` 构建三个未签名 APK，构建过程不接收发布私钥或密码。仅由可信的本地辅助逻辑调用官方 Android SDK 工具完成签名，复用已有发布证书；私钥、密码、本机 SDK 配置和 `.local/` 工具链不得提交到仓库或打入附件。

发布前核对包名、profile、ABI、递增版本号、证书、哈希与 ZIP 对齐。APK、`update.json` 与 `SHA256SUMS` 就绪后一起发布，更新清单只指向实际存在的本仓库 Release 附件。用户仍通过 Android 安装界面确认覆盖安装。

原 API19 与 Ijk 内核工作流作为上游维护资料保留。本仓库保留原作者说明和第三方归属；上游 blbl 未提供 LICENSE，不将整个项目重新声明为 MIT，也不声称已经取得原作者的额外分发授权。详见 [LICENSING.md](LICENSING.md) 和 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。下方完整保留原版说明，其中原版更新源不适用于本分支。

---

# blbl-android（原版说明）

一个第三方哔哩哔哩安卓 App，支持触摸、遥控，以及安卓5，适用于平板、TV、车机等设备。


## 界面预览

**推荐页**
![推荐页](./example-pic/推荐页.png)

**分类页**
![分类页](./example-pic/分类页.png)

**动态页**
![动态页](./example-pic/动态页.png)

**直播页**
![直播页](./example-pic/直播页.png)

**我的页**
![我的页](./example-pic/我的页.png)

**搜索页**
![搜索页](./example-pic/搜索页.png)

**追番**
![追番](./example-pic/追番.png)

**视频播放页**
![视频播放页](./example-pic/视频播放页.png)

## 功能概览

- 侧边栏导航：搜索 / 推荐 / 分类 / 动态 / 直播 / 我的
- 扫码登录入口
- 视频播放：Media3(ExoPlayer)，支持分辨率/编码/倍速/字幕/弹幕等设置
- 设置页：播放与弹幕偏好等

## 技术栈

- Kotlin + AndroidX + ViewBinding
- Media3(ExoPlayer)/[Ijkplayer](https://github.com/cat3399/ijkplayer)
- OkHttp
- Protobuf-lite
- Material / RecyclerView / ViewPager2

## 构建

环境要求：JDK 17，Android SDK（compileSdk 36）。

调试包：

```
./gradlew assembleDebug
```

发布包（已开启 R8 混淆 + 资源压缩）：

```
./gradlew assembleRelease
```

可选版本参数（本地或 CI）：

```
./gradlew assembleRelease -PversionName=0.1.1 -PversionCode=2
```

## 临时更新方案
**目前在代码中内置了国内环境可直接访问的直链,用于在测试阶段方便的覆盖更新,待后续稳定之后将会移除**,介意者请从release中下载action编译的安装包

## GitHub Actions

仓库包含两套手动触发的工作流：

- Android Debug：手动输入 `version_name`
- Android Release：同上，额外需要签名 Secrets

需要在仓库 Secrets 中配置：

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

## 感谢

- https://github.com/SocialSisterYi/bilibili-API-collect B站API收集整理
- https://github.com/xiaye13579/BBLL 优秀的页面设计和操作逻辑，本项目绝大部分页面和操作逻辑都是抄袭BBLL🥰
- https://github.com/bggRGjQaUbCoE/PiliPlus 部分关键功能参考了Piliplus的逻辑
- https://github.com/debugly/ijkplayer 感谢debugly大佬移植的ijkplayer
- 开源第三方B站客户端
- 群友们的详细测试与反馈

## 免责声明

> 不得利用本项目进行任何非法活动。 不得干扰B站的正常运营。 不得传播恶意软件或病毒。 此外，为降低法律风险

1. 🚫禁止在官方平台（b站）及官方账号区域（如b站微博评论区）宣传本项目
2. 🚫禁止在微信公众号平台宣传本项目
3. 🚫禁止利用本项目牟利，本项目无任何盈利行为，第三方盈利与本项目无关

代码都是codex写的，如有问题请联系https://openai.com/ 😤

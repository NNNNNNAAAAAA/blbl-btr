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

## 点播加速

- 仅对 **ExoPlayer 的视频、番剧点播**生效，不含直播加速、Ijk 加速或浏览器播放器接管、悬浮球等功能。
- **默认开启，并发上限 8**：设置 → 其他设置 → BTR 多线程加速 / BTR 并发连接数，可选 2、4、8；修改后重新打开视频。音视频共享最多 8 个下载连接，音频最多 2 个，不要求连接数始终占满。手机与两个电视架构采用相同默认值。
- 分块按顺序交给播放器，并校验 Range、正文长度及文件总长；断流重试可保留已验证的连续前缀。仅使用播放接口给出的完整媒体地址和备用 CDN，不绕过登录、会员或其他播放权限。

尚未在实体电视上实测。实际改善取决于设备、网络、CDN 和视频码率；遇到播放问题可降低连接数或关闭加速。

## 应用内更新

发布版从本仓库的 [GitHub Releases 更新清单](https://github.com/NNNNNNAAAAAA/blbl-btr/releases/latest/download/update.json)检查更新。开启“自动检查更新”后，应用在启动时检查；也可在设置 → 关于应用 → 检查更新中手动检查。

更新先匹配电视 / 手机 profile、包名和系统支持的 ABI，再比较版本；不会把电视包作为手机更新。下载后的完整性、包名、版本及签名检查通过后才交给 Android 安装界面，安装仍需用户确认。本分支不使用原版的 APK 直链。GitHub 连接失败或匹配 APK 缺失时保留当前版本，可到 Releases 手动下载同包名、同签名的安装包。

早期仅支持手动更新的 BTR 安装包，需要先手动覆盖安装一次支持此更新源的版本。调试签名与发布签名不能互相覆盖；持续更新必须复用同一发布密钥。

## 两个上游的自动维护

`.github/workflows/btr-upstream-release.yml` 配置为每小时第 17 分钟检查上游，也可在 Actions 手动运行；首次发布或需要重建当前代码时使用 `workflow_dispatch` 的 `force` 选项。默认分支上的 `app/**` 或构建代码更新也会进入发布检查；仅提交观察状态或报告不会循环触发 APK 发布。

1. **blbl 更新**：尝试合入新的上游提交，同时保留本分支的加速、手机适配和自动化。合并冲突、许可 / 免责声明相关文件发生变化，或检查失败时停止自动发布，留下日志供维护者处理。
2. **BTR 更新**：先生成 `.github/btr-adaptation-report.md` 待适配报告、Actions 摘要及对应 artifact。**仅有 BTR 新提交时，不把旧 Kotlin 实现重编成“已升级内核”的新 APK。**维护者完成原生 Kotlin 适配、更新参考提交并通过测试后，再发布。独立的 blbl 更新可继续构建，发行说明须保留实际原生参考版本。
3. **构建与发布**：候选代码检查、三个 APK 构建、签名和产物校验全部成功才发布 Release；任一步失败就停发，不把失败构建作为应用更新。

`.github/upstream-state.json` 分别记录 `blbl.merged_sha`、BTR 的 `observed_sha` 与 `native_reference_sha`。`observed_sha` 只表示检测到了该提交；`native_reference_sha` 表示当前 Kotlin 实现实际参考的版本。两者不同，说明存在待评估 / 待移植变更。自动检查不能把浏览器 JavaScript 自动转换为原生 Kotlin，也不代表网页 BTR 的全部功能已经移植。

GitHub 定时任务可能排队或延迟，只在默认分支运行；公开仓库长期没有活动时也可能被 GitHub 停用，需要在 Actions 重新启用。详见 [GitHub schedule 说明](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)。

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

## 发布配置与密钥

持续发布需要配置四项仓库 Secrets：`RELEASE_KEYSTORE_BASE64`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`。复用现有发布密钥，不能每次自动生成新签名；私钥、密码、本机 SDK 配置和 `.local/` 工具链不得提交到仓库或打入源码包。

自动化把候选代码构建、可信签名和发布分开：未签名构建任务不接收发布私钥；签名任务不执行候选项目的 Gradle；发布任务只接收已校验产物，不接收签名私钥。参见 [GitHub Actions 安全说明](https://docs.github.com/en/actions/reference/security/secure-use)和 [Android 应用签名说明](https://developer.android.com/studio/publish/app-signing)。

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

# 许可范围与上游说明

本仓库是 [cat3399/blbl](https://github.com/cat3399/blbl) 的衍生版本，初始基准提交为
`cd09bd903e6d0c22f5986580c2105796d8df6471`。该上游提交未提供 LICENSE 文件。
本分支公开源码、APK 和自动发布流程的决定，不代表原作者已授予额外的修改或
分发许可；本仓库不对上游既有代码重新授权，也不声明整个应用均适用 MIT。

## 原作者说明

原作者 README 中的说明完整保留：不得利用项目牟利，不在 B 站官方平台、
官方账号区域或微信公众号宣传本项目，不得用于非法活动、传播恶意软件或
干扰平台运营。本分支不代表原作者维护或背书，也不提供播放权限绕过。

GitHub 的公开查看、Fork 权限与软件再分发许可不是一回事。署名、保留说明、
公开仓库或新增代码的 MIT 许可，都不能被表述为已经取得上游整项目许可。
参考 [GitHub 许可说明](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository)。

## 本分支新增部分

本次自主编写的原生加速实现（`app/src/main/java/blbl/cat3399/feature/player/accelerator/`）、
相应测试，以及本分支贡献者有权许可的集成、手机适配、自动化和文档新增部分，
采用 [MIT](LICENSE-BTR) 许可。复制或修改上游文件、布局到手机资源目录，并不会
把其既有内容重新变成 MIT；原有代码及依赖的权利、归属和许可范围保持不变。

加速实现参考 [Bilibili-thread-ripper](https://github.com/MrTangLuyao/Bilibili-thread-ripper)
的并发 HTTP Range、校验和调度思路。其完整 MIT 许可及原版权声明保留在
[third_party/Bilibili-thread-ripper-LICENSE](third_party/Bilibili-thread-ripper-LICENSE)，
并随 APK 打包；新增部分的许可也随 APK 保留。详细归属见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 自动跟踪与发布

自动维护记录分别区分上游 blbl 已合入提交、BTR 已观察提交和原生实现实际参考提交。
观察到新的 BTR JavaScript 提交不会自动授予其他代码许可，也不会自动完成 Kotlin 移植；
BTR-only 更新先形成待适配报告，完成原生适配与测试后再发布。

自动合入前若检测到 README、LICENSE、COPYING、NOTICE 等许可或作者说明相关文件变化，
流程停止自动合入 / 发布，保留日志供维护者重新核对；这项检查不是对现有上游许可状态
的肯定。后续合并应保留来源、版权声明、免责声明和适用的第三方许可，不得用自动化
覆盖这些说明或将全部源码统一改标为 MIT。

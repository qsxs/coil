# Coil 3.3.0 的 OHOS 独立构建

本目录迁移现有 P3 七模块适配，独立发布 `coil`、`coil-core`、`coil-compose`、
`coil-compose-core`、`coil-network-core`、`coil-network-ktor3` 和 `coil-svg`。
上游 [coil-kt/coil 3.3.0](https://github.com/coil-kt/coil/tree/3.3.0) Git 基线为
`1731511f0c12ef556db7822a91b6ea03fa3f95a1`，不改动上游 Android/iOS 源。

鸿蒙输入来自 CPF 已发布的 `3.3.0-1.1.0-02` 七个 sources 包：其原始源码 Git 提交和原构建
工程未知，不能视为上述上游 Git 提交。`source-inputs.json` 列明每个包 URL、SHA-256 和全部
145 份编译源码的逐文件身份；与上游 Git 对照为 135 份一致、6 份不同、4 份鸿蒙新增。
其中 134 份不需改动的实际编译输入直接从 Fork 当前提交的上游路径读取 Git blob；
`ohos/sources/` 中保留所有冻结副本以便追溯，只有 11 份鸿蒙差异文件作为实际构建覆盖。
后续合并上游时，如果输入字节与冻结清单不同，构建会要求先审查并更新映射，不会静默切换源码。
已批准的唯一额外源码差异是 `coil-core/nonJsCommonMain/coil3/imageLoaders.nonJsCommon.kt`
删除 `@file:JvmName` 首行；`source-patch.json` 及 `provenance/original/` 保留原始字节与差异。
上游 Apache-2.0 许可证保留在 `legal/LICENSE` 并随制品发布。

构建沿用 Kotlin/Native `2.4.20-0.1.0-08`、CMP `1.12.0-0.1.0-11`、Skiko
`0.150.1-0.1.0-10`、Poko `0.23.2` 和原 P3 的依赖锁定；仅 commonMain metadata 使用
同版本公共编译入口，其余 metadata 和双 ABI 继续使用标准 KGP。实际版本以配方文件为准。
group 为 `io.github.qsxs.cpf.coil`，预览版本带源码 commit 和 CI 运行身份，避免覆盖。

`Build.py` 要求干净源码提交、新的隔离工作目录、预先安装的 `.08` Native 工具目录和 JDK 17。
它校验来源、解析完整模块图和 Poko 插件，执行七模块 metadata、OHOS x64/arm64 编译，随后
校验 21 个 Maven 组件的元数据、源码、许可证和 Native KLIB，再输出到工作目录下的 `maven/`。
若 Gradle 的最终失败原因为连接/读取超时、连接重置或明确的临时 HTTP 错误，构建在同一工作目录
保留每次日志并重试，复用已下载内容；编译错误、缺少制品或摘要错误直接失败。
示例：

```sh
python3 -B ohos/Build.py --source-sha "$SOURCE_SHA" --run-id "$RUN_ID" \
  --run-attempt "$RUN_ATTEMPT" --work "$WORK_DIR" --native-home "$NATIVE_HOME"
```

独立编译不等同设备运行。原 P3 的 x64 运行结果属于原制品；新制品的链接、设备运行、
ARM64 真机和 Android/iOS 回归必须分别验证。迁仓不改变主工程当前依赖。

## CPF 源码对照和后续维护

[CPF 官方 main-3.3.0-OH](https://gitcode.com/CPF-KMP-CMP/coil/tree/main-3.3.0-OH) 的
固定提交 `bfa7e50a18875b75b66c3879408c84916aec9f3c` 与 CPF 发布源码包所选 145 份原始
Kotlin 文件的 Git blob 全部一致，逐项记录在 `provenance/cpf-source-comparison.json`。
这不证明当初的发布包由该提交构建，原始制品的构建提交仍记为 UNKNOWN。构建额外应用的
`@file:JvmName` 删除只作用于已批准的鸿蒙覆盖。

后续上游或 CPF 有更新时，先比较 `source-inputs.json` 与两个固定来源，审查鸿蒙覆盖和 API
差异，再更新源码映射、依赖锁及版本，重新构建和验收；不可把旧来源哈希或旧设备结果当成
新版本的证据。现有 Fork 保留历史源码快照，独立构建使用当前提交中的上游文件和明确覆盖。
可在克隆仓中将 `https://github.com/coil-kt/coil.git` 配为 `upstream`，将
`https://gitcode.com/CPF-KMP-CMP/coil.git` 配为 `cpf`；按需 fetch 对比，未经验证不要假定
两边提交历史相同，也不要把 CPF 当前分支 SHA 冒认为旧 Maven 包的构建提交。

## 独立预览制品

第四轮 [producer 40997275438/1](https://github.com/qsxs/cpf-port-tooling/actions/runs/40997275438/attempts/1)
针对 source `0e0e3189f9205c57f82efd96065211ceb333f205` 完成七模块 metadata 和双 ABI 编译、
发布及配方校验。独立审查核对 21 个发布组件、665 个文件、435 个源码包成员和 542 个 KLIB 成员；
云端编译器实际读取的所有物理输入未逐一独立复哈。临时 Maven 地址为
`https://qsxs.github.io/cpf-maven/`，预览版本为
`3.3.0-cpf11-preview-0e0e3189f920-40997275438-1`，七个模块使用该同一版本。

[匿名 consumer 41000005970/1](https://github.com/qsxs/cpf-port-tooling/actions/runs/41000005970/attempts/1)
使用 Gradle 9.5.0 和 Skia，只直接声明 compose、network-ktor3、svg 三个模块并传递解析全部七模块。
21 份公开 GMM 与 staging 一致，metadata、x64 和 ARM64 consumer 任务实际执行；两个 Native 编译器
各自读取的 7 个 KLIB 与公开制品摘要一致。证据 ZIP 不含 consumer 输出二进制，不可在本机独立复哈。

该验证不包含新制品的最终链接、设备运行、ARM64 真机、Gradle 8.11.1 consumer 或 Android/iOS
回归；旧 P3 的 x64 模拟器记录只针对旧制品。主工程引用尚未切换。

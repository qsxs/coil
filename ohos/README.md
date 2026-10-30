# Coil 3.3.0 的 OHOS 独立构建

本目录迁移现有 P3 七模块适配，独立发布 `coil`、`coil-core`、`coil-compose`、
`coil-compose-core`、`coil-network-core`、`coil-network-ktor3` 和 `coil-svg`。
上游 [coil-kt/coil 3.3.0](https://github.com/coil-kt/coil/tree/3.3.0) Git 基线为
`1731511f0c12ef556db7822a91b6ea03fa3f95a1`，不改动上游 Android/iOS 源。

鸿蒙输入来自 CPF 已发布的 `3.3.0-1.1.0-02` 七个 sources 包：其原始源码 Git 提交和原构建
工程未知，不能视为上述上游 Git 提交。`source-inputs.json` 列明每个包 URL、SHA-256 和全部
145 份编译源码的逐文件身份；与上游 Git 对照为 135 份一致、6 份不同、4 份鸿蒙新增。
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
示例：

```sh
python3 -B ohos/Build.py --source-sha "$SOURCE_SHA" --run-id "$RUN_ID" \
  --run-attempt "$RUN_ATTEMPT" --work "$WORK_DIR" --native-home "$NATIVE_HOME"
```

独立编译不等同设备运行。原 P3 的 x64 运行结果属于原制品；新制品的链接、设备运行、
ARM64 真机和 Android/iOS 回归必须分别验证。迁仓不改变主工程当前依赖。

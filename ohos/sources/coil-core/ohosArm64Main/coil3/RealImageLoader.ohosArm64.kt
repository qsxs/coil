package coil3

import coil3.decode.OhosNativeDmaDecoder

internal actual fun ComponentRegistry.Builder.addAppleComponents(
    options: RealImageLoader.Options,
): ComponentRegistry.Builder {
    return this
}

internal actual fun ComponentRegistry.Builder.addOhosComponents(
    options: RealImageLoader.Options,
): ComponentRegistry.Builder {
    // DMA 解码器始终注册；由 CMP 的 OhosImageLoadConfig.useNativeDmaDecodeWhenAvailable 控制是否生效。
    // 默认 false（原库行为）；应用设 true 后 Factory 创建解码器走 DMA 路径。
    // 用户使用方式与原库完全一致：SubcomposeAsyncImage(model = url)，无需额外调用。
    return this
        .add(OhosNativeDmaDecoder.Factory())
}

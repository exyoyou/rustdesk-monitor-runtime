package youyou.monitor.runtime

// 已停用：yoyo_runtime（src/main/cpp）依赖 AHardwareBuffer（API 26+），minSdk 改为 22 后不再编译。
// 录屏帧现在在 Kotlin 侧按 rowStride 逐行拷贝。代码保留作参考，需要时同时恢复 build.gradle 里的 externalNativeBuild。
//
// import android.hardware.HardwareBuffer
// import java.nio.ByteBuffer
//
// internal object RuntimeNativeLib {
//     /**
//      * 将 `hardwareBuffer` 的字节写入调用方传入的预分配 direct `ByteBuffer`。
//      * 返回 0 表示成功，负数表示错误（详见 native 实现）。
//      */
//     external fun nativeWriteHardwareBufferToBuffer(
//         hardwareBuffer: HardwareBuffer,
//         dest: ByteBuffer
//     ): Int
//
//     init {
//         System.loadLibrary("yoyo_runtime")
//     }
// }

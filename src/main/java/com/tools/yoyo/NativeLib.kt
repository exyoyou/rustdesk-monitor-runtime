package youyou.monitor.runtime

import android.hardware.HardwareBuffer
import java.nio.ByteBuffer

internal object RuntimeNativeLib {
    /**
     * 将 `hardwareBuffer` 的字节写入调用方传入的预分配 direct `ByteBuffer`。
     * 返回 0 表示成功，负数表示错误（详见 native 实现）。
     */
    external fun nativeWriteHardwareBufferToBuffer(
        hardwareBuffer: HardwareBuffer,
        dest: ByteBuffer
    ): Int

    init {
        System.loadLibrary("yoyo_runtime")
    }
}

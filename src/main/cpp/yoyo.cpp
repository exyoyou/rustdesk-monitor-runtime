// Minimal implementation: only JNI init/unload and nativeWriteImageToBuffer
#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <cstring>

#define LOG_TAG "ImageWriter"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM *g_jvm = nullptr;
static jclass g_imageClass = nullptr;
static jmethodID g_getHardwareBufferMethod = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass localImageClass = env->FindClass("android/media/Image");
    if (localImageClass) {
        g_imageClass = (jclass)env->NewGlobalRef(localImageClass);
        g_getHardwareBufferMethod = env->GetMethodID(g_imageClass, "getHardwareBuffer", "()Landroid/hardware/HardwareBuffer;");
        env->DeleteLocalRef(localImageClass);
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return;
    if (g_imageClass) {
        env->DeleteGlobalRef(g_imageClass);
        g_imageClass = nullptr;
    }
}

/**
 * 将 HardwareBuffer 的内容安全、正确地写入 Direct ByteBuffer
 * 解决了：1. Stride 导致的画面拉伸 2. FD 句柄释放同步问题
 */
extern "C" JNIEXPORT jint JNICALL
Java_youyou_monitor_runtime_RuntimeNativeLib_nativeWriteHardwareBufferToBuffer(
        JNIEnv *env, jobject thiz, jobject hwBufferObj, jobject destBuffer) {

    if (!hwBufferObj || !destBuffer) return -1;

    // 1. 获取目标 ByteBuffer 的地址和容量
    void *destAddr = env->GetDirectBufferAddress(destBuffer);
    jlong destCap = env->GetDirectBufferCapacity(destBuffer);
    if (!destAddr || destCap <= 0) return -2;

    // 2. 从 Java 对象获取 Native 句柄
    AHardwareBuffer *hwBuffer = AHardwareBuffer_fromHardwareBuffer(env, hwBufferObj);
    if (!hwBuffer) return -3;

    // 增加引用计数，确保拷贝期间 buffer 不会被销毁
    AHardwareBuffer_acquire(hwBuffer);

    // 3. 获取 HardwareBuffer 描述信息 (获取 Stride)
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(hwBuffer, &desc);

    // 4. 锁定内存以进行 CPU 读取
    void *src = nullptr;
    int lockResult = AHardwareBuffer_lock(hwBuffer, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY, -1, nullptr, &src);

    if (lockResult != 0 || !src) {
        AHardwareBuffer_release(hwBuffer);
        return -4;
    }

    // 5. 准备拷贝参数
    uint8_t *s = static_cast<uint8_t *>(src);
    uint8_t *d = static_cast<uint8_t *>(destAddr);

    uint32_t bpp = 4; // RGBA_8888 格式
    uint32_t activeRowBytes = desc.width * bpp; // 逻辑上一行的字节
    uint32_t strideBytes = desc.stride * bpp;   // 物理上一行的字节 (含填充)

    // 检查 ByteBuffer 容量是否足够存储整理后的数据
    if (destCap < (jlong)(activeRowBytes * desc.height)) {
        AHardwareBuffer_unlock(hwBuffer, nullptr);
        AHardwareBuffer_release(hwBuffer);
        return -5;
    }

    // 6. 核心逻辑：逐行拷贝，去除 Stride 产生的 Padding（解决画面拉伸）
    for (uint32_t y = 0; y < desc.height; ++y) {
        memcpy(d + (y * activeRowBytes), s + (y * strideBytes), activeRowBytes);
    }

    // 7. 释放资源
    AHardwareBuffer_unlock(hwBuffer, nullptr);
    AHardwareBuffer_release(hwBuffer);

    return 0; // 成功
}

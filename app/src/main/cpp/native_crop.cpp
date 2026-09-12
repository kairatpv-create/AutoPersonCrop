#include <jni.h>
#include <turbojpeg.h>
#include <algorithm>
#include <stdexcept>
#include <string>

static void throwJava(JNIEnv* env, const std::string& msg) {
    jclass ex = env->FindClass("java/lang/IllegalStateException");
    if (ex) env->ThrowNew(ex, msg.c_str());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_kz_autopersoncrop_jpeg_LosslessJpegTransformer_00024NativeBridge_losslessCrop(
        JNIEnv* env, jobject, jbyteArray jpeg, jint x, jint y, jint width, jint height) {
    if (!jpeg || width <= 0 || height <= 0 || x < 0 || y < 0) {
        throwJava(env, "Некорректная область JPEG crop");
        return nullptr;
    }

    const jsize srcSizeJ = env->GetArrayLength(jpeg);
    jbyte* srcJ = env->GetByteArrayElements(jpeg, nullptr);
    if (!srcJ) return nullptr;
    unsigned char* src = reinterpret_cast<unsigned char*>(srcJ);
    unsigned long srcSize = static_cast<unsigned long>(srcSizeJ);

    tjhandle handle = tjInitTransform();
    if (!handle) {
        env->ReleaseByteArrayElements(jpeg, srcJ, JNI_ABORT);
        throwJava(env, "Не удалось запустить libjpeg-turbo");
        return nullptr;
    }

    // tjDecompressHeader3() writes colorspace on success, so no legacy
    // TJCS_UNKNOWN sentinel is required (it is not present in TurboJPEG 3.x).
    int srcW = 0, srcH = 0, subsamp = TJSAMP_UNKNOWN, colorspace = 0;
    if (tjDecompressHeader3(handle, src, srcSize, &srcW, &srcH, &subsamp, &colorspace) < 0) {
        std::string err = tjGetErrorStr2(handle);
        tjDestroy(handle);
        env->ReleaseByteArrayElements(jpeg, srcJ, JNI_ABORT);
        throwJava(env, "JPEG header: " + err);
        return nullptr;
    }
    if (subsamp < 0 || subsamp >= TJ_NUMSAMP) {
        tjDestroy(handle);
        env->ReleaseByteArrayElements(jpeg, srcJ, JNI_ABORT);
        throwJava(env, "Неизвестный JPEG subsampling");
        return nullptr;
    }

    // Lossless coefficient cropping requires the upper-left corner to be MCU-aligned.
    // Align OUTWARD (left/up), never inward, so no requested person pixels are cut.
    const int mcuW = tjMCUWidth[subsamp];
    const int mcuH = tjMCUHeight[subsamp];
    const int desiredRight = std::min(srcW, static_cast<int>(x) + static_cast<int>(width));
    const int desiredBottom = std::min(srcH, static_cast<int>(y) + static_cast<int>(height));
    const int ax = (x / mcuW) * mcuW;
    const int ay = (y / mcuH) * mcuH;
    // Coefficient-domain crops operate on MCU blocks. Round the far edge OUTWARD too
    // (except at the natural JPEG boundary), so the requested safe crop is fully contained.
    const int aright = std::min(srcW, ((desiredRight + mcuW - 1) / mcuW) * mcuW);
    const int abottom = std::min(srcH, ((desiredBottom + mcuH - 1) / mcuH) * mcuH);
    const int aw = aright - ax;
    const int ah = abottom - ay;
    if (ax < 0 || ay < 0 || aw <= 0 || ah <= 0 || ax + aw > srcW || ay + ah > srcH) {
        tjDestroy(handle);
        env->ReleaseByteArrayElements(jpeg, srcJ, JNI_ABORT);
        throwJava(env, "Lossless crop вышел за границы JPEG");
        return nullptr;
    }

    tjtransform tr{};
    tr.r.x = ax;
    tr.r.y = ay;
    tr.r.w = aw;
    tr.r.h = ah;
    tr.op = TJXOP_NONE;
    tr.options = TJXOPT_CROP;

    unsigned char* dst = nullptr;
    unsigned long dstSize = 0;
    const int rc = tjTransform(handle, src, srcSize, 1, &dst, &dstSize, &tr, 0);
    if (rc < 0) {
        std::string err = tjGetErrorStr2(handle);
        if (dst) tjFree(dst);
        tjDestroy(handle);
        env->ReleaseByteArrayElements(jpeg, srcJ, JNI_ABORT);
        throwJava(env, "Lossless crop: " + err);
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(dstSize));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(dstSize), reinterpret_cast<jbyte*>(dst));

    tjFree(dst);
    tjDestroy(handle);
    env->ReleaseByteArrayElements(jpeg, srcJ, JNI_ABORT);
    return result;
}

// Ponte JNI para o WebPAnimEncoder da libwebp (figurinhas animadas).
#include <jni.h>
#include <stdlib.h>
#include <android/bitmap.h>
#include "webp/encode.h"
#include "webp/mux.h"

#define FN(name) Java_com_zapfig_app_media_WebPAnim_##name

JNIEXPORT jlong JNICALL FN(nativeNew)(JNIEnv *env, jclass clazz, jint width, jint height) {
    WebPAnimEncoderOptions opts;
    if (!WebPAnimEncoderOptionsInit(&opts)) return 0;
    opts.anim_params.loop_count = 0;   // loop infinito
    opts.minimize_size = 0;
    opts.allow_mixed = 0;
    return (jlong) (intptr_t) WebPAnimEncoderNew(width, height, &opts);
}

// Adiciona um frame (Bitmap ARGB_8888). Retorna 0 em caso de sucesso.
JNIEXPORT jint JNICALL FN(nativeAddFrame)(JNIEnv *env, jclass clazz, jlong handle, jobject bitmap,
                                          jint timestampMs, jfloat quality) {
    WebPAnimEncoder *enc = (WebPAnimEncoder *) (intptr_t) handle;
    if (!enc) return -1;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return -2;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return -3;
    void *pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return -4;

    WebPConfig config;
    WebPPicture pic;
    int ok = WebPConfigInit(&config) && WebPPictureInit(&pic);
    if (ok) {
        config.quality = quality;
        config.method = 4;
        config.lossless = 0;
        config.alpha_quality = 80;
        pic.width = (int) info.width;
        pic.height = (int) info.height;
        pic.use_argb = 1;
        ok = WebPPictureImportRGBA(&pic, (const uint8_t *) pixels, (int) info.stride);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    if (!ok) return -5;
    ok = WebPAnimEncoderAdd(enc, &pic, timestampMs, &config);
    WebPPictureFree(&pic);
    return ok ? 0 : -6;
}

JNIEXPORT jbyteArray JNICALL FN(nativeAssemble)(JNIEnv *env, jclass clazz, jlong handle, jint endTimestampMs) {
    WebPAnimEncoder *enc = (WebPAnimEncoder *) (intptr_t) handle;
    if (!enc) return NULL;
    if (!WebPAnimEncoderAdd(enc, NULL, endTimestampMs, NULL)) return NULL;
    WebPData data;
    WebPDataInit(&data);
    if (!WebPAnimEncoderAssemble(enc, &data)) return NULL;
    jbyteArray out = (*env)->NewByteArray(env, (jsize) data.size);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize) data.size, (const jbyte *) data.bytes);
    WebPDataClear(&data);
    return out;
}

JNIEXPORT void JNICALL FN(nativeFree)(JNIEnv *env, jclass clazz, jlong handle) {
    WebPAnimEncoder *enc = (WebPAnimEncoder *) (intptr_t) handle;
    if (enc) WebPAnimEncoderDelete(enc);
}

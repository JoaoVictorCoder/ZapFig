// Ponte JNI para o WebPAnimDecoder (figurinha animada -> frames).
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/bitmap.h>
#include "webp/demux.h"

#define FN(name) Java_com_zapfig_app_media_WebPAnimDecoder_##name

typedef struct {
    WebPAnimDecoder *dec;
    WebPAnimInfo info;
    uint8_t *data;   // o decoder guarda ponteiro para os bytes: precisamos manter a cópia
} Dec;

JNIEXPORT jlong JNICALL FN(nativeOpen)(JNIEnv *env, jclass clazz, jbyteArray bytes) {
    jsize n = (*env)->GetArrayLength(env, bytes);
    Dec *d = calloc(1, sizeof(Dec));
    if (!d) return 0;
    d->data = malloc((size_t) n);
    if (!d->data) { free(d); return 0; }
    (*env)->GetByteArrayRegion(env, bytes, 0, n, (jbyte *) d->data);

    WebPAnimDecoderOptions opts;
    if (!WebPAnimDecoderOptionsInit(&opts)) goto fail;
    opts.color_mode = MODE_rgbA;   // alpha pré-multiplicado, como o Bitmap do Android
    opts.use_threads = 1;
    WebPData wd = { d->data, (size_t) n };
    d->dec = WebPAnimDecoderNew(&wd, &opts);
    if (!d->dec || !WebPAnimDecoderGetInfo(d->dec, &d->info)) goto fail;
    return (jlong) (intptr_t) d;
fail:
    if (d->dec) WebPAnimDecoderDelete(d->dec);
    free(d->data);
    free(d);
    return 0;
}

// [largura, altura, quantidade de frames]
JNIEXPORT jintArray JNICALL FN(nativeInfo)(JNIEnv *env, jclass clazz, jlong handle) {
    Dec *d = (Dec *) (intptr_t) handle;
    jint v[3] = { (jint) d->info.canvas_width, (jint) d->info.canvas_height, (jint) d->info.frame_count };
    jintArray out = (*env)->NewIntArray(env, 3);
    (*env)->SetIntArrayRegion(env, out, 0, 3, v);
    return out;
}

// Copia o próximo frame para o Bitmap (ARGB_8888, mesmo tamanho do canvas).
// Retorna o timestamp de FIM do frame em ms, ou -1 quando acabar / erro.
JNIEXPORT jint JNICALL FN(nativeNext)(JNIEnv *env, jclass clazz, jlong handle, jobject bitmap) {
    Dec *d = (Dec *) (intptr_t) handle;
    if (!WebPAnimDecoderHasMoreFrames(d->dec)) return -1;
    uint8_t *buf; int ts;
    if (!WebPAnimDecoderGetNext(d->dec, &buf, &ts)) return -1;

    AndroidBitmapInfo info;
    void *pixels;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return -1;
    if (info.width != d->info.canvas_width || info.height != d->info.canvas_height ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return -1;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return -1;
    const size_t row = (size_t) info.width * 4;
    for (uint32_t y = 0; y < info.height; y++) {
        memcpy((uint8_t *) pixels + y * info.stride, buf + y * row, row);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return ts;
}

JNIEXPORT void JNICALL FN(nativeClose)(JNIEnv *env, jclass clazz, jlong handle) {
    Dec *d = (Dec *) (intptr_t) handle;
    if (!d) return;
    WebPAnimDecoderDelete(d->dec);
    free(d->data);
    free(d);
}

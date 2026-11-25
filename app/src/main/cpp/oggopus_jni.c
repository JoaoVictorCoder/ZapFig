// Ponte JNI: ShortArray (mono 48 kHz) -> bytes OGG Opus.
#include <jni.h>
#include <stdlib.h>
#include "oggopus.h"

JNIEXPORT jbyteArray JNICALL
Java_com_zapfig_app_media_OpusOgg_nativeEncode(JNIEnv *env, jclass clazz, jshortArray pcm, jint bitrate) {
    jsize n = (*env)->GetArrayLength(env, pcm);
    jshort *samples = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (!samples) return NULL;
    uint8_t *out = NULL;
    size_t len = 0;
    int r = oggopus_encode((const int16_t *) samples, (int) n, bitrate, &out, &len);
    (*env)->ReleaseShortArrayElements(env, pcm, samples, JNI_ABORT);
    if (r != 0 || !out) return NULL;
    jbyteArray arr = (*env)->NewByteArray(env, (jsize) len);
    if (arr) (*env)->SetByteArrayRegion(env, arr, 0, (jsize) len, (const jbyte *) out);
    free(out);
    return arr;
}

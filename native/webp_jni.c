#include <jni.h>
#include <stdint.h>
#include <limits.h>
#include <math.h>
#include <string.h>
#include <webp/encode.h>
#include <webp/decode.h>

static void fail(JNIEnv *env, const char *type, const char *message) {
    jclass cls = (*env)->FindClass(env, type);
    if (cls != NULL) (*env)->ThrowNew(env, cls, message);
}
#define INVALID(msg) fail(env, "java/lang/IllegalArgumentException", msg)
#define FAILED(msg) fail(env, "java/lang/IllegalStateException", msg)

JNIEXPORT jbyteArray JNICALL Java_io_github_shinyook_webp_WebP_00024Native_encode
(JNIEnv *env, jclass cls, jbyteArray input, jint w, jint h, jfloat quality, jboolean lossless) {
    (void)cls;
    if (!input || w <= 0 || h <= 0 || w > 16383 || h > 16383 ||
        (int64_t)w * h * 4 > INT_MAX || !isfinite(quality) || quality < 0 || quality > 100) {
        INVALID("Invalid image dimensions or quality"); return NULL;
    }
    if ((*env)->GetArrayLength(env, input) != (int64_t)w * h * 4) {
        INVALID("RGBA buffer length mismatch"); return NULL;
    }
    WebPConfig config;
    WebPPicture picture;
    if (!WebPConfigInit(&config) || !WebPPictureInit(&picture)) {
        FAILED("libwebp ABI mismatch"); return NULL;
    }
    config.lossless = lossless ? 1 : 0; config.quality = quality; config.exact = 1;
    picture.use_argb = 1; picture.width = w; picture.height = h;
    jbyte *rgba = (*env)->GetByteArrayElements(env, input, NULL);
    if (!rgba) return NULL;
    int imported = WebPPictureImportRGBA(&picture, (const uint8_t *)rgba, w * 4);
    (*env)->ReleaseByteArrayElements(env, input, rgba, JNI_ABORT);
    if (!imported) { WebPPictureFree(&picture); FAILED("Cannot import pixels"); return NULL; }
    WebPMemoryWriter writer; WebPMemoryWriterInit(&writer);
    picture.writer = WebPMemoryWrite; picture.custom_ptr = &writer;
    int ok = WebPEncode(&config, &picture);
    WebPPictureFree(&picture);
    jbyteArray result = NULL;
    if (!ok || writer.size > INT_MAX) { FAILED("WebP encoding failed or output exceeds Java array limit"); }
    else {
        result = (*env)->NewByteArray(env, (jsize)writer.size);
        if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)writer.size, (const jbyte *)writer.mem);
    }
    WebPMemoryWriterClear(&writer);
    return result;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_shinyook_webp_WebP_00024Native_decode
(JNIEnv *env, jclass cls, jbyteArray input, jint max_pixels) {
    (void)cls;
    if (!input || max_pixels <= 0) { INVALID("Invalid input or pixel limit"); return NULL; }
    jsize length = (*env)->GetArrayLength(env, input);
    jbyte *data = (*env)->GetByteArrayElements(env, input, NULL);
    if (!data) return NULL;
    WebPBitstreamFeatures features;
    VP8StatusCode status = WebPGetFeatures((const uint8_t *)data, (size_t)length, &features);
    if (status != VP8_STATUS_OK || features.has_animation) {
        (*env)->ReleaseByteArrayElements(env, input, data, JNI_ABORT);
        INVALID("Invalid, truncated or animated WebP input"); return NULL;
    }
    int w = features.width, h = features.height;
    int64_t size = (int64_t)w * h * 4;
    if (w <= 0 || h <= 0 || (int64_t)w * h > max_pixels || size > INT_MAX - 8) {
        (*env)->ReleaseByteArrayElements(env, input, data, JNI_ABORT);
        INVALID("Decoded image exceeds pixel limit"); return NULL;
    }
    uint8_t *rgba = WebPDecodeRGBA((const uint8_t *)data, (size_t)length, NULL, NULL);
    (*env)->ReleaseByteArrayElements(env, input, data, JNI_ABORT);
    if (!rgba) { INVALID("WebP decoding failed"); return NULL; }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)size + 8);
    if (result) {
        uint8_t header[8];
        for (int i = 0; i < 4; i++) { header[i] = (uint8_t)((uint32_t)w >> (24 - 8*i)); header[4+i] = (uint8_t)((uint32_t)h >> (24 - 8*i)); }
        (*env)->SetByteArrayRegion(env, result, 0, 8, (const jbyte *)header);
        if (!(*env)->ExceptionCheck(env)) (*env)->SetByteArrayRegion(env, result, 8, (jsize)size, (const jbyte *)rgba);
    }
    WebPFree(rgba);
    return result;
}

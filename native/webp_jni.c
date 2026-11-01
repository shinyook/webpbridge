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

JNIEXPORT jbyteArray JNICALL Java_io_github_prismpx_webpbridge_WebP_00024Native_encode
(JNIEnv *env, jclass cls, jintArray input, jint w, jint h, jboolean opaque, jfloat quality, jboolean lossless) {
    (void)cls;
    if (!input || w <= 0 || h <= 0 || w > 16383 || h > 16383 ||
        (int64_t)w * h * 4 > INT_MAX || !isfinite(quality) || quality < 0 || quality > 100) {
        INVALID("Invalid image dimensions or quality"); return NULL;
    }
    if ((*env)->GetArrayLength(env, input) != (int64_t)w * h) {
        INVALID("ARGB buffer length mismatch"); return NULL;
    }
    WebPConfig config;
    WebPPicture picture;
    if (!WebPConfigInit(&config) || !WebPPictureInit(&picture)) {
        FAILED("libwebp ABI mismatch"); return NULL;
    }
    config.lossless = lossless ? 1 : 0; config.quality = quality; config.exact = 1;
    picture.use_argb = 1; picture.width = w; picture.height = h;
    if (!WebPPictureAlloc(&picture)) { FAILED("Cannot allocate picture"); return NULL; }
    /* Java ints are packed 0xAARRGGBB, the same layout as WebPPicture.argb. */
    for (int y = 0; y < h; y++) {
        uint32_t *row = picture.argb + (size_t)y * (size_t)picture.argb_stride;
        (*env)->GetIntArrayRegion(env, input, (jsize)y * w, w, (jint *)row);
        if ((*env)->ExceptionCheck(env)) { WebPPictureFree(&picture); return NULL; }
        if (opaque) for (int x = 0; x < w; x++) row[x] |= 0xff000000u;
    }
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

static int little_endian(void) { const uint16_t probe = 1; return *(const uint8_t *)&probe == 1; }

JNIEXPORT jintArray JNICALL Java_io_github_prismpx_webpbridge_WebP_00024Native_decode
(JNIEnv *env, jclass cls, jbyteArray input, jint max_pixels, jintArray info) {
    (void)cls;
    if (!input || !info || max_pixels <= 0 || (*env)->GetArrayLength(env, info) < 3) {
        INVALID("Invalid input or pixel limit"); return NULL;
    }
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
    if (w <= 0 || h <= 0 || (int64_t)w * h > max_pixels || (int64_t)w * h * 4 > INT_MAX) {
        (*env)->ReleaseByteArrayElements(env, input, data, JNI_ABORT);
        INVALID("Decoded image exceeds pixel limit"); return NULL;
    }
    /* A Java int 0xAARRGGBB is stored as B,G,R,A on little-endian hosts. */
    int dw = 0, dh = 0;
    uint8_t *argb = little_endian()
        ? WebPDecodeBGRA((const uint8_t *)data, (size_t)length, &dw, &dh)
        : WebPDecodeARGB((const uint8_t *)data, (size_t)length, &dw, &dh);
    (*env)->ReleaseByteArrayElements(env, input, data, JNI_ABORT);
    if (!argb || dw != w || dh != h) { WebPFree(argb); INVALID("WebP decoding failed"); return NULL; }
    jsize count = (jsize)((int64_t)w * h);
    jintArray result = (*env)->NewIntArray(env, count);
    if (result) {
        (*env)->SetIntArrayRegion(env, result, 0, count, (const jint *)argb);
        jint dims[3] = { w, h, features.has_alpha ? 1 : 0 };
        if (!(*env)->ExceptionCheck(env)) (*env)->SetIntArrayRegion(env, info, 0, 3, dims);
        if ((*env)->ExceptionCheck(env)) result = NULL;
    }
    WebPFree(argb);
    return result;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_prismpx_webpbridge_WebP_00024Native_inspect
(JNIEnv *env, jclass cls, jbyteArray input) {
    (void)cls;
    if (!input) { INVALID("Input must not be null"); return NULL; }
    jsize length=(*env)->GetArrayLength(env,input);
    jbyte *bytes=(*env)->GetByteArrayElements(env,input,NULL);
    if (!bytes) return NULL;
    const uint8_t *data=(const uint8_t *)bytes;
    size_t size=(size_t)length;
    VP8StatusCode status=VP8_STATUS_BITSTREAM_ERROR;
    WebPBitstreamFeatures features;
    if (size>=4 && memcmp(data,"RIFF",4)==0) {
        /* Same container rules as decoding: the declared RIFF payload must be
         * present, and bytes after it are ignored. WebPGetFeatures alone does
         * not detect a truncated RIFF payload. */
        if (size>=12 && memcmp(data+8,"WEBP",4)==0) {
            uint32_t declared=(uint32_t)data[4] | ((uint32_t)data[5]<<8) | ((uint32_t)data[6]<<16) | ((uint32_t)data[7]<<24);
            if ((uint64_t)declared+8<=(uint64_t)size)
                status=WebPGetFeatures(data,(size_t)declared+8,&features);
        }
    } else {
        /* Raw VP8/VP8L bitstreams are accepted by libwebp decoding too. */
        status=WebPGetFeatures(data,size,&features);
    }
    (*env)->ReleaseByteArrayElements(env,input,bytes,JNI_ABORT);
    if (status!=VP8_STATUS_OK) { INVALID("Invalid or incomplete WebP header"); return NULL; }
    uint8_t header[12]={0};
    for (int i=0;i<4;i++) {
        header[i]=(uint8_t)((uint32_t)features.width>>(24-8*i));
        header[4+i]=(uint8_t)((uint32_t)features.height>>(24-8*i));
    }
    header[8]=(uint8_t)features.has_alpha; header[9]=(uint8_t)features.has_animation; header[10]=(uint8_t)features.format;
    jbyteArray result=(*env)->NewByteArray(env,12);
    if (result) (*env)->SetByteArrayRegion(env,result,0,12,(const jbyte *)header);
    return result;
}

JNIEXPORT jlong JNICALL Java_io_github_prismpx_webpbridge_WebP_00024Native_versions
(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return ((jlong)(uint32_t)WebPGetEncoderVersion()<<32) | (jlong)(uint32_t)WebPGetDecoderVersion();
}

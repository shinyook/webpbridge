/* Exercise the actual JNI entry points with a minimal array/exception adapter.
 * No JVM is present: sanitizers can account for native allocations without JVM
 * runtime noise. Actual JVM behavior is covered separately by Java tests. */
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include "../webp_jni.c"

typedef struct { jsize length; jbyte *bytes; } Array; /* length counts elements */
static int pending_exception;
static int fail_new_array;
static int fail_pin;
static int pins;
static Array *typed_array(jsize n, size_t element) {
    Array *a = (Array *)malloc(sizeof(*a)); assert(a);
    a->length = n; a->bytes = (jbyte *)calloc(n ? (size_t)n : 1, element); assert(a->bytes);
    return a;
}
static Array *array(jsize n) { return typed_array(n, 1); }
static Array *int_array(jsize n) { return typed_array(n, sizeof(jint)); }
static jint *ints(void *a) { return (jint *)((Array *)a)->bytes; }
static void dispose(jbyteArray a) {
    if (a) { free(((Array *)a)->bytes); free((Array *)a); }
}
static jclass JNICALL find_class(JNIEnv *env, const char *name) {
    (void)env; (void)name; return (jclass)(uintptr_t)1;
}
static jint JNICALL throw_new(JNIEnv *env, jclass cls, const char *message) {
    (void)env; (void)cls; (void)message; pending_exception=1; return 0;
}
static jboolean JNICALL exception_check(JNIEnv *env) { (void)env; return (jboolean)pending_exception; }
static jsize JNICALL length(JNIEnv *env, jarray a) { (void)env; return ((Array *)a)->length; }
static jbyte *JNICALL pin(JNIEnv *env, jbyteArray a, jboolean *copy) {
    (void)env; (void)copy;
    if (fail_pin) { pending_exception=1; return NULL; }
    Array *v=(Array *)a;
    jbyte *data=(jbyte *)malloc(v->length ? (size_t)v->length : 1); assert(data);
    memcpy(data,v->bytes,(size_t)v->length); pins++; return data;
}
static void JNICALL unpin(JNIEnv *env, jbyteArray a, jbyte *data, jint mode) {
    (void)env; (void)a; assert(mode==JNI_ABORT); free(data); pins--;
}
static jbyteArray JNICALL new_array(JNIEnv *env, jsize n) {
    (void)env;
    if(fail_new_array) { pending_exception=1; return NULL; }
    return (jbyteArray)array(n);
}
static void JNICALL set_region(JNIEnv *env, jbyteArray a, jsize start, jsize n, const jbyte *data) {
    (void)env; Array *v=(Array *)a;
    assert(start>=0 && n>=0 && (int64_t)start+n<=v->length);
    memcpy(v->bytes+start,data,(size_t)n);
}
static jintArray JNICALL new_int_array(JNIEnv *env, jsize n) {
    (void)env;
    if(fail_new_array) { pending_exception=1; return NULL; }
    return (jintArray)int_array(n);
}
static void JNICALL set_int_region(JNIEnv *env, jintArray a, jsize start, jsize n, const jint *data) {
    (void)env; Array *v=(Array *)a;
    assert(start>=0 && n>=0 && (int64_t)start+n<=v->length);
    memcpy(ints(v)+start,data,(size_t)n*sizeof(jint));
}
static void JNICALL get_int_region(JNIEnv *env, jintArray a, jsize start, jsize n, jint *out) {
    (void)env; Array *v=(Array *)a;
    /* fail_pin also simulates a pending exception while copying source rows. */
    if (fail_pin) { pending_exception=1; return; }
    assert(start>=0 && n>=0 && (int64_t)start+n<=v->length);
    memcpy(out,ints(v)+start,(size_t)n*sizeof(jint));
}
static const struct JNINativeInterface_ functions = {
    .FindClass=find_class, .ThrowNew=throw_new, .ExceptionCheck=exception_check,
    .GetArrayLength=length, .GetByteArrayElements=pin,
    .ReleaseByteArrayElements=unpin, .NewByteArray=new_array, .SetByteArrayRegion=set_region,
    .NewIntArray=new_int_array, .SetIntArrayRegion=set_int_region, .GetIntArrayRegion=get_int_region
};
#define ENCODE(a,w,h,q,l) Java_io_github_prismpx_webpbridge_WebP_00024Native_encode(&env,NULL,(jintArray)(a),w,h,JNI_FALSE,q,l)
#define INSPECT(a) Java_io_github_prismpx_webpbridge_WebP_00024Native_inspect(&env,NULL,a)
static Array *decode_info;
#define DECODE(a,limit) ((jbyteArray)Java_io_github_prismpx_webpbridge_WebP_00024Native_decode(&env,NULL,a,limit,(jintArray)decode_info))
#define REJECT(expr) do { pending_exception=0; jbyteArray rejected=(expr); assert(!rejected && pending_exception && pins==0); pending_exception=0; } while(0)
int main(void) {
    JNIEnv env=&functions;
    decode_info=int_array(3);
    Array *source=int_array(64*64);
    for(int i=0;i<source->length*4;i++) source->bytes[i]=(jbyte)((i*17+i/13)%256);
    for(int iteration=0;iteration<1000;iteration++) {
        for(int lossless=0;lossless<2;lossless++) {
            jbyteArray encoded=ENCODE((jbyteArray)source,64,64,80,(jboolean)lossless);
            assert(encoded && !pending_exception && pins==0);
            jbyteArray info=INSPECT(encoded);
            assert(info && ((Array *)info)->length==12 && pins==0 && !pending_exception);
            assert(((Array *)info)->bytes[10]==(lossless ? 2 : 1));
            dispose(info);
            jbyteArray decoded=DECODE(encoded,4096);
            assert(decoded && !pending_exception && pins==0);
            assert(((Array *)decoded)->length==source->length);
            assert(ints(decode_info)[0]==64 && ints(decode_info)[1]==64 && ints(decode_info)[2]==1);
            if(lossless) assert(memcmp(ints(decoded),ints(source),(size_t)source->length*sizeof(jint))==0);
            dispose(decoded);
            REJECT(DECODE(encoded,4095));
            Array *truncated=array(((Array *)encoded)->length/2);
            memcpy(truncated->bytes,((Array *)encoded)->bytes,(size_t)truncated->length);
            REJECT(DECODE((jbyteArray)truncated,4096));
            dispose((jbyteArray)truncated);
            fail_new_array=1;
            REJECT(INSPECT(encoded));
            REJECT(DECODE(encoded,4096));
            REJECT(ENCODE((jbyteArray)source,64,64,80,(jboolean)lossless));
            fail_new_array=0;
            fail_pin=1;
            REJECT(INSPECT(encoded));
            REJECT(DECODE(encoded,4096));
            REJECT(ENCODE((jbyteArray)source,64,64,80,(jboolean)lossless));
            fail_pin=0;
            dispose(encoded);
        }
    }
    jbyteArray seed=ENCODE((jbyteArray)source,64,64,80,1);
    assert(seed && !pending_exception);
    uint32_t state=20261101;
    for(int i=0;i<1000;i++) {
        Array *original=(Array *)seed;
        jsize n=(i<500) ? (jsize)((uint32_t)i%(uint32_t)original->length) : original->length;
        Array *mutated=array(n);
        memcpy(mutated->bytes,original->bytes,(size_t)n);
        if(i>=500) {
            state=state*1664525u+1013904223u;
            mutated->bytes[state%(uint32_t)n]^=(jbyte)(1u<<(state%8));
        }
        jbyteArray output=DECODE((jbyteArray)mutated,4096);
        assert(pins==0);
        if(output) { assert(!pending_exception); dispose(output); }
        else assert(pending_exception);
        pending_exception=0;
        output=INSPECT((jbyteArray)mutated);
        assert(pins==0);
        if(output) { assert(!pending_exception); dispose(output); }
        else assert(pending_exception);
        pending_exception=0;
        dispose((jbyteArray)mutated);
    }
    dispose(seed);
    REJECT(ENCODE((jbyteArray)source,0,64,80,0));
    REJECT(ENCODE((jbyteArray)source,16384,64,80,0));
    REJECT(ENCODE((jbyteArray)source,64,64,NAN,0));
    REJECT(ENCODE((jbyteArray)source,63,64,80,0));
    REJECT(ENCODE((jbyteArray)source,64,63,80,0));
    REJECT(DECODE(NULL,4096));
    Array *short_info=int_array(2);
    REJECT((jbyteArray)Java_io_github_prismpx_webpbridge_WebP_00024Native_decode(&env,NULL,(jbyteArray)source,4096,(jintArray)short_info));
    dispose((jbyteArray)short_info);
    /* Opaque flag forces alpha to 0xff; lossless round trip must then be fully opaque. */
    jbyteArray opaque=Java_io_github_prismpx_webpbridge_WebP_00024Native_encode(&env,NULL,(jintArray)source,64,64,JNI_TRUE,80,1);
    assert(opaque && !pending_exception);
    jbyteArray restored=DECODE(opaque,4096);
    assert(restored && ints(decode_info)[2]==0);
    for(int i=0;i<64*64;i++) assert(((uint32_t)ints(restored)[i]>>24)==0xffu && ((uint32_t)ints(restored)[i]&0xffffffu)==((uint32_t)ints(source)[i]&0xffffffu));
    dispose(restored); dispose(opaque);
    dispose((jbyteArray)decode_info);
    dispose((jbyteArray)source);
    assert(pins==0 && !pending_exception);
    puts("PASS: 2000 codec cycles, 1000 malformed cases, allocation/pin failure paths; no outstanding pins");
    return 0;
}

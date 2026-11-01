/* Exercise the actual JNI entry points with a minimal array/exception adapter.
 * No JVM is present: sanitizers can account for native allocations without JVM
 * runtime noise. Actual JVM behavior is covered separately by Java tests. */
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include "../webp_jni.c"

typedef struct { jsize length; jbyte *bytes; } Array;
static int pending_exception;
static int fail_new_array;
static int fail_pin;
static int pins;
static Array *array(jsize n) {
    Array *a = (Array *)malloc(sizeof(*a)); assert(a);
    a->length = n; a->bytes = (jbyte *)calloc(n ? (size_t)n : 1, 1); assert(a->bytes);
    return a;
}
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
static const struct JNINativeInterface_ functions = {
    .FindClass=find_class, .ThrowNew=throw_new, .ExceptionCheck=exception_check,
    .GetArrayLength=length, .GetByteArrayElements=pin,
    .ReleaseByteArrayElements=unpin, .NewByteArray=new_array, .SetByteArrayRegion=set_region
};
#define ENCODE(a,w,h,q,l) Java_io_github_shinyook_webp_WebP_00024Native_encode(&env,NULL,a,w,h,q,l)
#define DECODE(a,limit) Java_io_github_shinyook_webp_WebP_00024Native_decode(&env,NULL,a,limit)
#define REJECT(expr) do { pending_exception=0; jbyteArray rejected=(expr); assert(!rejected && pending_exception && pins==0); pending_exception=0; } while(0)
int main(void) {
    JNIEnv env=&functions;
    Array *source=array(64*64*4);
    for(int i=0;i<source->length;i++) source->bytes[i]=(jbyte)((i*17+i/13)%256);
    for(int iteration=0;iteration<1000;iteration++) {
        for(int lossless=0;lossless<2;lossless++) {
            jbyteArray encoded=ENCODE((jbyteArray)source,64,64,80,(jboolean)lossless);
            assert(encoded && !pending_exception && pins==0);
            jbyteArray decoded=DECODE(encoded,4096);
            assert(decoded && !pending_exception && pins==0);
            assert(((Array *)decoded)->length==source->length+8);
            if(lossless) assert(memcmp(((Array *)decoded)->bytes+8,source->bytes,(size_t)source->length)==0);
            dispose(decoded);
            REJECT(DECODE(encoded,4095));
            Array *truncated=array(((Array *)encoded)->length/2);
            memcpy(truncated->bytes,((Array *)encoded)->bytes,(size_t)truncated->length);
            REJECT(DECODE((jbyteArray)truncated,4096));
            dispose((jbyteArray)truncated);
            fail_new_array=1;
            REJECT(DECODE(encoded,4096));
            REJECT(ENCODE((jbyteArray)source,64,64,80,(jboolean)lossless));
            fail_new_array=0;
            fail_pin=1;
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
        dispose((jbyteArray)mutated);
    }
    dispose(seed);
    REJECT(ENCODE((jbyteArray)source,0,64,80,0));
    REJECT(ENCODE((jbyteArray)source,16384,64,80,0));
    REJECT(ENCODE((jbyteArray)source,64,64,NAN,0));
    REJECT(ENCODE((jbyteArray)source,63,64,80,0));
    REJECT(DECODE(NULL,4096));
    dispose((jbyteArray)source);
    assert(pins==0 && !pending_exception);
    puts("PASS: 2000 codec cycles, 1000 malformed cases, allocation/pin failure paths; no outstanding pins");
    return 0;
}

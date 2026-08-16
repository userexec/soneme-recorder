#include <jni.h>
#include <cstdint>
#include "lame.h"

static inline lame_t from_handle(jlong handle) {
    return reinterpret_cast<lame_t>(static_cast<intptr_t>(handle));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_userexec_soneme_recorder_LameEncoder_nativeCreate(
        JNIEnv *, jclass, jint sampleRate, jint bitrateKbps) {
    lame_t gfp = lame_init();
    if (!gfp) return 0;

    bool ok = true;
    ok &= lame_set_in_samplerate(gfp, sampleRate) == 0;
    ok &= lame_set_out_samplerate(gfp, sampleRate) == 0;
    ok &= lame_set_num_channels(gfp, 1) == 0;
    ok &= lame_set_mode(gfp, MONO) == 0;
    ok &= lame_set_VBR(gfp, vbr_off) == 0;
    ok &= lame_set_brate(gfp, bitrateKbps) == 0;
    ok &= lame_set_quality(gfp, 5) == 0;
    ok &= lame_set_bWriteVbrTag(gfp, 0) == 0;
    lame_set_write_id3tag_automatic(gfp, 0);
    if (!ok || lame_init_params(gfp) < 0) {
        lame_close(gfp);
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(gfp));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_userexec_soneme_recorder_LameEncoder_nativeEncode(
        JNIEnv *env, jclass, jlong handle, jshortArray samples_, jint count, jbyteArray output_) {
    lame_t gfp = from_handle(handle);
    if (!gfp || !samples_ || !output_ || count < 0) return -3;
    const jsize sampleArraySize = env->GetArrayLength(samples_);
    const jsize outputSize = env->GetArrayLength(output_);
    if (count > sampleArraySize) return -1;

    jshort *samples = env->GetShortArrayElements(samples_, nullptr);
    jbyte *output = env->GetByteArrayElements(output_, nullptr);
    if (!samples || !output) {
        if (samples) env->ReleaseShortArrayElements(samples_, samples, JNI_ABORT);
        if (output) env->ReleaseByteArrayElements(output_, output, 0);
        return -2;
    }
    const int result = lame_encode_buffer(
        gfp,
        reinterpret_cast<const short *>(samples),
        reinterpret_cast<const short *>(samples),
        count,
        reinterpret_cast<unsigned char *>(output),
        outputSize);
    env->ReleaseShortArrayElements(samples_, samples, JNI_ABORT);
    env->ReleaseByteArrayElements(output_, output, 0);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_userexec_soneme_recorder_LameEncoder_nativeFlush(
        JNIEnv *env, jclass, jlong handle, jbyteArray output_) {
    lame_t gfp = from_handle(handle);
    if (!gfp || !output_) return -3;
    const jsize outputSize = env->GetArrayLength(output_);
    jbyte *output = env->GetByteArrayElements(output_, nullptr);
    if (!output) return -2;
    const int result = lame_encode_flush(gfp, reinterpret_cast<unsigned char *>(output), outputSize);
    env->ReleaseByteArrayElements(output_, output, 0);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_userexec_soneme_recorder_LameEncoder_nativeClose(
        JNIEnv *, jclass, jlong handle) {
    lame_t gfp = from_handle(handle);
    if (gfp) lame_close(gfp);
}

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <mutex>
#include <vector>
#include <exception>
#include "needle.h"
#include "sat_smt_pddl_planner.h"
#include "trm_policy_mcts.h"
#include "micro_cot_gguf_engine.h"
#include "zero_copy_vision_accel.h"

#define LOG_TAG "NeedleJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::mutex g_needle_mutex;
static bool g_telemetry_disabled = false;

static void ensure_telemetry_disabled() {
    if (!g_telemetry_disabled) {
        setenv("NEEDLE_TELEMETRY", "0", 1);
        setenv("DO_NOT_TRACK", "1", 1);
        setenv("CI", "1", 1);
        g_telemetry_disabled = true;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_newandroidapp_ai_NeedleNativeBridge_nativeLoadFromAssets(
    JNIEnv* env,
    jobject /* thiz */,
    jobject asset_manager,
    jstring asset_name
) {
    std::lock_guard<std::mutex> lock(g_needle_mutex);
    ensure_telemetry_disabled();

    if (!asset_manager || !asset_name) {
        return -1;
    }

    try {
        AAssetManager* mgr = AAssetManager_fromJava(env, asset_manager);
        if (!mgr) {
            return -2;
        }

        const char* name_cstr = env->GetStringUTFChars(asset_name, nullptr);
        if (!name_cstr) return -2;
        AAsset* asset = AAssetManager_open(mgr, name_cstr, AASSET_MODE_BUFFER);
        env->ReleaseStringUTFChars(asset_name, name_cstr);

        if (!asset) {
            LOGE("Failed to open asset for Needle 3 weights");
            return -3;
        }

        const void* buf = AAsset_getBuffer(asset);
        off64_t len = AAsset_getLength64(asset);
        if (!buf || len <= 0) {
            AAsset_close(asset);
            return -4;
        }

        int rc = needle_load(reinterpret_cast<const unsigned char*>(buf), static_cast<unsigned long long>(len));
        AAsset_close(asset);

        if (rc < 0) {
            const char* err = needle_last_error();
            LOGE("needle_load failed: %s (rc=%d)", err ? err : "unknown", rc);
        } else {
            LOGI("needle_load succeeded: bytes=%lld, models=%d", static_cast<long long>(len), needle_models());
        }
        return rc;
    } catch (const std::exception& e) {
        LOGE("C++ exception in nativeLoadFromAssets: %s", e.what());
        return -99;
    } catch (...) {
        LOGE("Unknown C++ exception in nativeLoadFromAssets");
        return -100;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_newandroidapp_ai_NeedleNativeBridge_nativeModels(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    std::lock_guard<std::mutex> lock(g_needle_mutex);
    ensure_telemetry_disabled();
    try {
        return needle_models();
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_newandroidapp_ai_NeedleNativeBridge_nativeLastError(
    JNIEnv* env,
    jobject /* thiz */
) {
    std::lock_guard<std::mutex> lock(g_needle_mutex);
    try {
        const char* err = needle_last_error();
        return env->NewStringUTF(err ? err : "");
    } catch (...) {
        return env->NewStringUTF("native_exception");
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_newandroidapp_ai_NeedleNativeBridge_nativeInit(
    JNIEnv* env,
    jobject /* thiz */,
    jstring system_prompt,
    jstring tools_json
) {
    std::lock_guard<std::mutex> lock(g_needle_mutex);
    ensure_telemetry_disabled();

    try {
        const char* sys_cstr = system_prompt ? env->GetStringUTFChars(system_prompt, nullptr) : "";
        const char* tools_cstr = tools_json ? env->GetStringUTFChars(tools_json, nullptr) : "[]";

        int rc = needle_init(sys_cstr ? sys_cstr : "", tools_cstr ? tools_cstr : "[]", nullptr);

        if (system_prompt && sys_cstr) env->ReleaseStringUTFChars(system_prompt, sys_cstr);
        if (tools_json && tools_cstr) env->ReleaseStringUTFChars(tools_json, tools_cstr);

        return rc;
    } catch (...) {
        LOGE("Exception in nativeInit");
        return -1;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_newandroidapp_ai_NeedleNativeBridge_nativeReset(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    std::lock_guard<std::mutex> lock(g_needle_mutex);
    ensure_telemetry_disabled();
    try {
        needle_reset();
    } catch (...) {
        LOGE("Exception in nativeReset");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_newandroidapp_ai_NeedleNativeBridge_nativeComplete(
    JNIEnv* env,
    jobject /* thiz */,
    jstring input_text,
    jint max_new_tokens
) {
    std::lock_guard<std::mutex> lock(g_needle_mutex);
    ensure_telemetry_disabled();

    if (!input_text) return env->NewStringUTF("");
    try {
        const char* input_cstr = env->GetStringUTFChars(input_text, nullptr);
        if (!input_cstr) return env->NewStringUTF("");

        int safe_tokens = static_cast<int>(max_new_tokens);
        if (safe_tokens <= 0) safe_tokens = 32;
        if (safe_tokens > 512) safe_tokens = 512;

        std::vector<char> out_buf(32768, 0);
        int rc = needle_complete(
            input_cstr,
            nullptr,
            0,
            safe_tokens,
            out_buf.data(),
            static_cast<int>(out_buf.size() - 1)
        );

        env->ReleaseStringUTFChars(input_text, input_cstr);

        if (rc < 0) {
            return env->NewStringUTF("");
        }
        out_buf.back() = '\0';
        return env->NewStringUTF(out_buf.data());
    } catch (...) {
        LOGE("Exception in nativeComplete");
        return env->NewStringUTF("");
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_newandroidapp_ai_NeedleNativeBridge_nativeEmbed(
    JNIEnv* env,
    jobject /* thiz */,
    jstring input_text
) {
    std::lock_guard<std::mutex> lock(g_needle_mutex);
    ensure_telemetry_disabled();

    if (!input_text) return env->NewFloatArray(0);
    try {
        const char* input_cstr = env->GetStringUTFChars(input_text, nullptr);
        if (!input_cstr) return env->NewFloatArray(0);

        int dim = needle_embed(input_cstr, nullptr, 0, nullptr, 0);
        if (dim <= 0 || dim > 8192) {
            dim = 1024;
        }

        std::vector<float> vec(dim, 0.0f);
        int actual = needle_embed(input_cstr, nullptr, 0, vec.data(), dim);
        env->ReleaseStringUTFChars(input_text, input_cstr);

        if (actual <= 0 || actual > dim) {
            return env->NewFloatArray(0);
        }

        jfloatArray result = env->NewFloatArray(actual);
        if (result) {
            env->SetFloatArrayRegion(result, 0, actual, vec.data());
        }
        return result;
    } catch (...) {
        LOGE("Exception in nativeEmbed");
        return env->NewFloatArray(0);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_newandroidapp_ai_TriBrainCoordinatorEngine_nativeLoadTrmWeights(
    JNIEnv* env,
    jobject /* thiz */,
    jobject asset_manager,
    jstring asset_name
) {
    if (!asset_manager || !asset_name) return JNI_FALSE;
    try {
        AAssetManager* mgr = AAssetManager_fromJava(env, asset_manager);
        if (!mgr) return JNI_FALSE;
        const char* name_cstr = env->GetStringUTFChars(asset_name, nullptr);
        if (!name_cstr) return JNI_FALSE;
        AAsset* asset = AAssetManager_open(mgr, name_cstr, AASSET_MODE_BUFFER);
        env->ReleaseStringUTFChars(asset_name, name_cstr);
        if (!asset) return JNI_FALSE;
        const void* buf = AAsset_getBuffer(asset);
        off64_t len = AAsset_getLength64(asset);
        bool ok = false;
        if (buf && len > 0) {
            ok = tri_brain::TrmPolicyMctsEngine::loadWeightsFromBuffer(
                reinterpret_cast<const uint8_t*>(buf),
                static_cast<size_t>(len)
            );
        }
        AAsset_close(asset);
        return ok ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_newandroidapp_ai_TriBrainCoordinatorEngine_nativeSolvePddlPlan(
    JNIEnv* env,
    jobject /* thiz */,
    jint init_bits,
    jint goal_bits,
    jstring active_category
) {
    try {
        const char* cat_cstr = active_category ? env->GetStringUTFChars(active_category, nullptr) : nullptr;
        std::string cat = cat_cstr ? cat_cstr : "";
        if (cat_cstr) env->ReleaseStringUTFChars(active_category, cat_cstr);

        auto res = tri_brain::SatSmtPddlEngine::solvePddlPlan(
            static_cast<uint32_t>(init_bits),
            static_cast<uint32_t>(goal_bits),
            cat
        );
        std::string combined = res.immediate_operator_name + "|" + res.pddl_trace;
        return env->NewStringUTF(combined.c_str());
    } catch (...) {
        return env->NewStringUTF("TAP_DIRECT_TARGET|[PDDL Native Fallback]");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_newandroidapp_ai_TriBrainCoordinatorEngine_nativeEvaluateTrmMcts(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray feature_matrix,
    jbyteArray legal_mask,
    jint num_nodes,
    jint pddl_op_code,
    jint simulations
) {
    if (!feature_matrix || !legal_mask || num_nodes <= 0) {
        return env->NewStringUTF("-1|0.0|[TRM+MCTS Empty]");
    }
    try {
        std::vector<float> feats(num_nodes * tri_brain::kTrmFeatureDim, 0.0f);
        std::vector<uint8_t> mask(num_nodes, 0);
        jsize feat_len = env->GetArrayLength(feature_matrix);
        jsize mask_len = env->GetArrayLength(legal_mask);
        if (feat_len < num_nodes * tri_brain::kTrmFeatureDim || mask_len < num_nodes) {
            return env->NewStringUTF("-1|0.0|[TRM+MCTS SizeMismatch]");
        }
        env->GetFloatArrayRegion(feature_matrix, 0, num_nodes * tri_brain::kTrmFeatureDim, feats.data());
        env->GetByteArrayRegion(legal_mask, 0, num_nodes, reinterpret_cast<jbyte*>(mask.data()));

        auto dec = tri_brain::TrmPolicyMctsEngine::evaluateOptimalAction(
            feats.data(),
            mask.data(),
            num_nodes,
            pddl_op_code,
            simulations
        );
        char out_buf[384];
        std::snprintf(
            out_buf, sizeof(out_buf),
            "%d|%.4f|%s",
            dec.selected_node_index,
            dec.final_confidence,
            dec.trm_mcts_trace.c_str()
        );
        return env->NewStringUTF(out_buf);
    } catch (...) {
        return env->NewStringUTF("-1|0.0|[TRM+MCTS Exception]");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_newandroidapp_ai_TriBrainCoordinatorEngine_nativeGenerateMicroCoT(
    JNIEnv* env,
    jobject /* thiz */,
    jstring micro_inst,
    jstring visual_scene,
    jint total_blocks,
    jint cur_block,
    jstring app_hint,
    jstring pddl_op
) {
    try {
        auto getStr = [env](jstring js) -> std::string {
            if (!js) return "";
            const char* p = env->GetStringUTFChars(js, nullptr);
            if (!p) return "";
            std::string s(p);
            env->ReleaseStringUTFChars(js, p);
            return s;
        };
        std::string cot = tri_brain::MicroCotGgufEngine::generateCoTThoughtChain(
            getStr(micro_inst),
            getStr(visual_scene),
            total_blocks,
            cur_block,
            getStr(app_hint),
            getStr(pddl_op)
        );
        return env->NewStringUTF(cot.c_str());
    } catch (...) {
        return env->NewStringUTF("<cot>[Micro-Llama Q4 CoT]</cot>");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_newandroidapp_ai_TriBrainCoordinatorEngine_nativeEvaluateTrmMctsWithPrior(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray feature_matrix,
    jbyteArray legal_mask,
    jfloatArray prior_policy,
    jint num_nodes,
    jint pddl_op_code,
    jint simulations
) {
    if (!feature_matrix || !legal_mask || num_nodes <= 0) {
        return env->NewStringUTF("-1|0.0|[TRM+MCTS Empty]");
    }
    try {
        std::vector<float> feats(num_nodes * tri_brain::kTrmFeatureDim, 0.0f);
        std::vector<uint8_t> mask(num_nodes, 0);
        std::vector<float> priors(num_nodes, 0.0f);
        const float* prior_ptr = nullptr;

        jsize feat_len = env->GetArrayLength(feature_matrix);
        jsize mask_len = env->GetArrayLength(legal_mask);
        if (feat_len < num_nodes * tri_brain::kTrmFeatureDim || mask_len < num_nodes) {
            return env->NewStringUTF("-1|0.0|[TRM+MCTS SizeMismatch]");
        }
        env->GetFloatArrayRegion(feature_matrix, 0, num_nodes * tri_brain::kTrmFeatureDim, feats.data());
        env->GetByteArrayRegion(legal_mask, 0, num_nodes, reinterpret_cast<jbyte*>(mask.data()));

        if (prior_policy != nullptr && env->GetArrayLength(prior_policy) >= num_nodes) {
            env->GetFloatArrayRegion(prior_policy, 0, num_nodes, priors.data());
            prior_ptr = priors.data();
        }

        auto dec = tri_brain::TrmPolicyMctsEngine::evaluateOptimalAction(
            feats.data(),
            mask.data(),
            num_nodes,
            pddl_op_code,
            simulations,
            prior_ptr
        );
        char out_buf[384];
        std::snprintf(
            out_buf, sizeof(out_buf),
            "%d|%.4f|%s",
            dec.selected_node_index,
            dec.final_confidence,
            dec.trm_mcts_trace.c_str()
        );
        return env->NewStringUTF(out_buf);
    } catch (...) {
        return env->NewStringUTF("-1|0.0|[TRM+MCTS Exception]");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_newandroidapp_ai_NeedleNativeBridge_nativeGetHardwareAccelReport(
    JNIEnv* env,
    jobject /* thiz */
) {
    try {
        const auto& status = needle_vision::ZeroCopyVisionEngine::initAndGetHardwareAccelStatus();
        return env->NewStringUTF(status.active_backend_summary.c_str());
    } catch (...) {
        return env->NewStringUTF("ZeroCopyAccel[Fallback]");
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_newandroidapp_ai_NeedleNativeBridge_nativeProcessHardwareBufferZeroCopy(
    JNIEnv* env,
    jobject /* thiz */,
    jobject hardware_buffer,
    jintArray roi_rects_flat,
    jlongArray out_dhash_and_flags
) {
    if (!env || !hardware_buffer) return nullptr;
    try {
        std::vector<int32_t> rois;
        int num_rois = 0;
        if (roi_rects_flat != nullptr) {
            jsize rlen = env->GetArrayLength(roi_rects_flat);
            if (rlen >= 4) {
                num_rois = rlen / 4;
                rois.resize(static_cast<size_t>(num_rois) * 4);
                env->GetIntArrayRegion(roi_rects_flat, 0, num_rois * 4, rois.data());
            }
        }

        uint64_t dhash64 = 0ULL;
        bool is_secure_black = false;
        std::vector<float> res = needle_vision::ZeroCopyVisionEngine::processHardwareBufferZeroCopyJni(
            env,
            hardware_buffer,
            rois.empty() ? nullptr : rois.data(),
            num_rois,
            &dhash64,
            &is_secure_black
        );

        if (out_dhash_and_flags != nullptr && env->GetArrayLength(out_dhash_and_flags) >= 2) {
            jlong meta[2];
            meta[0] = static_cast<jlong>(dhash64);
            meta[1] = is_secure_black ? 1L : 0L;
            env->SetLongArrayRegion(out_dhash_and_flags, 0, 2, meta);
        }

        jfloatArray out_arr = env->NewFloatArray(static_cast<jsize>(res.size()));
        if (out_arr && !res.empty()) {
            env->SetFloatArrayRegion(out_arr, 0, static_cast<jsize>(res.size()), res.data());
        }
        return out_arr;
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_newandroidapp_ai_NeedleNativeBridge_nativeProcessAshmemZeroCopy(
    JNIEnv* env,
    jobject /* thiz */,
    jint fd,
    jint byte_length,
    jint width,
    jint height,
    jint stride_pixels,
    jintArray roi_rects_flat,
    jlongArray out_dhash_and_flags
) {
    if (!env || fd < 0 || byte_length <= 0) return nullptr;
    try {
        std::vector<int32_t> rois;
        int num_rois = 0;
        if (roi_rects_flat != nullptr) {
            jsize rlen = env->GetArrayLength(roi_rects_flat);
            if (rlen >= 4) {
                num_rois = rlen / 4;
                rois.resize(static_cast<size_t>(num_rois) * 4);
                env->GetIntArrayRegion(roi_rects_flat, 0, num_rois * 4, rois.data());
            }
        }

        uint64_t dhash64 = 0ULL;
        bool is_secure_black = false;
        std::vector<float> res = needle_vision::ZeroCopyVisionEngine::processAshmemFdZeroCopy(
            fd,
            static_cast<size_t>(byte_length),
            width,
            height,
            stride_pixels,
            rois.empty() ? nullptr : rois.data(),
            num_rois,
            &dhash64,
            &is_secure_black
        );

        if (out_dhash_and_flags != nullptr && env->GetArrayLength(out_dhash_and_flags) >= 2) {
            jlong meta[2];
            meta[0] = static_cast<jlong>(dhash64);
            meta[1] = is_secure_black ? 1L : 0L;
            env->SetLongArrayRegion(out_dhash_and_flags, 0, 2, meta);
        }

        jfloatArray out_arr = env->NewFloatArray(static_cast<jsize>(res.size()));
        if (out_arr && !res.empty()) {
            env->SetFloatArrayRegion(out_arr, 0, static_cast<jsize>(res.size()), res.data());
        }
        return out_arr;
    } catch (...) {
        return nullptr;
    }
}

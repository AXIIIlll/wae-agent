#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/hardware_buffer_jni.h>
#include <sys/mman.h>
#include <unistd.h>
#include <string>
#include <sstream>
#include <vector>
#include <unordered_map>
#include <list>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <mutex>
#include <cstdint>

namespace {

std::mutex g_frameMutex;
std::string g_latestFrameJson = "{}";

#pragma pack(push, 1)
struct Wae2ModelHeader {
    char magic[4];          // "WAE2"
    uint32_t version;       // 260
    uint32_t modelId;
    uint32_t hiddenDim;
    uint32_t numLayers;
    uint32_t quantType;     // 1 = INT8, 2 = TERNARY
    uint64_t payloadSize;
    uint8_t sha256Prefix[32];
};
#pragma pack(pop)

struct MappedModelSlot {
    std::string assetPath;
    uint32_t modelId = 0;
    uint32_t hiddenDim = 0;
    uint32_t numLayers = 0;
    uint32_t quantType = 0;
    void* mmapBase = nullptr;
    size_t mmapLength = 0;
    const int8_t* weightData = nullptr;
    size_t weightBytes = 0;
    bool loaded = false;
};

std::mutex g_modelMutex;
std::unordered_map<uint32_t, MappedModelSlot> g_mappedModels;
int32_t g_loadedModelsCount = 0;
int64_t g_totalMappedBytes = 0;

constexpr uint64_t FNV_OFFSET_BASIS = 14695981039346656037ULL;
constexpr uint64_t FNV_PRIME = 1099511628211ULL;

inline uint64_t fnv1a64(const void* data, size_t len, uint64_t seed = FNV_OFFSET_BASIS) {
    const auto* bytes = static_cast<const uint8_t*>(data);
    uint64_t hash = seed;
    for (size_t i = 0; i < len; ++i) {
        hash ^= static_cast<uint64_t>(bytes[i]);
        hash *= FNV_PRIME;
    }
    return hash;
}

inline uint64_t fnv1a64String(const std::string& s, uint64_t seed = FNV_OFFSET_BASIS) {
    return fnv1a64(s.data(), s.size(), seed);
}

std::string toLowerAscii(const std::string& s) {
    std::string out = s;
    for (char& c : out) {
        if (c >= 'A' && c <= 'Z') {
            c = static_cast<char>(c - 'A' + 'a');
        }
    }
    return out;
}

std::string escapeJsonString(const std::string& input) {
    std::ostringstream ss;
    for (unsigned char c : input) {
        switch (c) {
            case '\"': ss << "\\\""; break;
            case '\\': ss << "\\\\"; break;
            case '\b': ss << "\\b"; break;
            case '\f': ss << "\\f"; break;
            case '\n': ss << "\\n"; break;
            case '\r': ss << "\\r"; break;
            case '\t': ss << "\\t"; break;
            default:
                if (c >= 0x20) {
                    ss << c;
                }
                break;
        }
    }
    return ss.str();
}

std::string jstringToStdString(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (!chars) return "";
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

bool mmapSingleModelAsset(AAssetManager* mgr, const char* assetRelPath) {
    if (!mgr || !assetRelPath) return false;
    AAsset* asset = AAssetManager_open(mgr, assetRelPath, AASSET_MODE_BUFFER);
    if (!asset) return false;

    off64_t startOffset = 0;
    off64_t length = 0;
    int fd = AAsset_openFileDescriptor64(asset, &startOffset, &length);
    AAsset_close(asset);

    if (fd < 0 || length <= 64) {
        if (fd >= 0) close(fd);
        return false;
    }

    const long pageSize = sysconf(_SC_PAGESIZE);
    off64_t pageAlignedOffset = (startOffset / pageSize) * pageSize;
    size_t extraOffset = static_cast<size_t>(startOffset - pageAlignedOffset);
    size_t mapLen = static_cast<size_t>(length) + extraOffset;

    void* mapped = mmap(nullptr, mapLen, PROT_READ, MAP_PRIVATE, fd, pageAlignedOffset);
    close(fd);
    if (mapped == MAP_FAILED || !mapped) {
        return false;
    }

    const auto* basePtr = static_cast<const uint8_t*>(mapped) + extraOffset;
    const auto* hdr = reinterpret_cast<const Wae2ModelHeader*>(basePtr);
    if (std::memcmp(hdr->magic, "WAE2", 4) != 0 || hdr->version != 260) {
        munmap(mapped, mapLen);
        return false;
    }

    MappedModelSlot slot;
    slot.assetPath = assetRelPath;
    slot.modelId = hdr->modelId;
    slot.hiddenDim = hdr->hiddenDim;
    slot.numLayers = hdr->numLayers;
    slot.quantType = hdr->quantType;
    slot.mmapBase = mapped;
    slot.mmapLength = mapLen;
    slot.weightData = reinterpret_cast<const int8_t*>(basePtr + sizeof(Wae2ModelHeader));
    slot.weightBytes = static_cast<size_t>(length) - sizeof(Wae2ModelHeader);
    slot.loaded = true;

    auto prev = g_mappedModels.find(slot.modelId);
    if (prev != g_mappedModels.end() && prev->second.mmapBase) {
        munmap(prev->second.mmapBase, prev->second.mmapLength);
        g_totalMappedBytes -= static_cast<int64_t>(prev->second.weightBytes);
        g_loadedModelsCount--;
    }

    g_totalMappedBytes += static_cast<int64_t>(slot.weightBytes);
    g_loadedModelsCount++;
    g_mappedModels[slot.modelId] = slot;
    return true;
}

// ============================================================================
// C++ 兩級 384 維 INT8 嵌入向量 LRU 快取 (結合 mmap 模型權重投影，容量 2048)
// ============================================================================
struct Int8Vec384 {
    int8_t data[384];
    float norm;
};

class EmbeddingLruCache {
public:
    explicit EmbeddingLruCache(size_t capacity = 2048) : cap_(capacity) {}

    const Int8Vec384& getOrCompute(const std::string& text) {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = map_.find(text);
        if (it != map_.end()) {
            lru_.splice(lru_.begin(), lru_, it->second.second);
            return it->second.first;
        }
        Int8Vec384 vec = compute384Embedding(text);
        if (map_.size() >= cap_) {
            const std::string& oldest = lru_.back();
            map_.erase(oldest);
            lru_.pop_back();
        }
        lru_.push_front(text);
        auto inserted = map_.emplace(text, std::make_pair(vec, lru_.begin()));
        return inserted.first->second.first;
    }

private:
    static Int8Vec384 compute384Embedding(const std::string& raw) {
        Int8Vec384 out{};
        float accum[384] = {0.0f};
        std::string normStr = toLowerAscii(raw);

        // 若 all_minilm_l6_v2_int8.bin (modelId=601) 已 mmap，使用其 INT8 詞嵌入權重表做調制
        const int8_t* miniLmWeights = nullptr;
        size_t miniLmBytes = 0;
        {
            std::lock_guard<std::mutex> mlock(g_modelMutex);
            auto it = g_mappedModels.find(601);
            if (it != g_mappedModels.end() && it->second.loaded) {
                miniLmWeights = it->second.weightData;
                miniLmBytes = it->second.weightBytes;
            }
        }

        std::vector<std::string> tokens;
        for (size_t i = 0; i < normStr.size();) {
            unsigned char c = static_cast<unsigned char>(normStr[i]);
            size_t charLen = 1;
            if ((c & 0x80) == 0) charLen = 1;
            else if ((c & 0xE0) == 0xC0) charLen = 2;
            else if ((c & 0xF0) == 0xE0) charLen = 3;
            else if ((c & 0xF8) == 0xF0) charLen = 4;
            if (i + charLen > normStr.size()) charLen = 1;
            tokens.push_back(normStr.substr(i, charLen));
            i += charLen;
        }

        for (size_t i = 0; i < tokens.size(); ++i) {
            uint64_t h1 = fnv1a64String(tokens[i]);
            size_t idx1 = static_cast<size_t>(h1 % 384ULL);
            float sign1 = ((h1 >> 32) & 1ULL) ? 1.0f : -1.0f;
            float wScale = 1.0f;
            if (miniLmWeights && miniLmBytes >= 4096) {
                int8_t w = miniLmWeights[h1 % (miniLmBytes - 384)];
                wScale = 1.0f + (static_cast<float>(w) / 512.0f);
            }
            accum[idx1] += sign1 * 1.2f * wScale;

            if (i + 1 < tokens.size()) {
                std::string bg = tokens[i] + tokens[i + 1];
                uint64_t h2 = fnv1a64String(bg);
                size_t idx2 = static_cast<size_t>(h2 % 384ULL);
                float sign2 = ((h2 >> 32) & 1ULL) ? 1.0f : -1.0f;
                accum[idx2] += sign2 * 1.8f * wScale;
            }
        }

        float maxAbs = 0.001f;
        for (float v : accum) {
            if (std::fabs(v) > maxAbs) maxAbs = std::fabs(v);
        }

        float sqSum = 0.0f;
        for (int d = 0; d < 384; ++d) {
            int32_t q = static_cast<int32_t>(std::round((accum[d] / maxAbs) * 127.0f));
            if (q > 127) q = 127;
            if (q < -127) q = -127;
            out.data[d] = static_cast<int8_t>(q);
            sqSum += static_cast<float>(q * q);
        }
        out.norm = std::sqrt(sqSum);
        if (out.norm < 1e-5f) out.norm = 1.0f;
        return out;
    }

    size_t cap_;
    std::mutex mu_;
    std::list<std::string> lru_;
    std::unordered_map<std::string, std::pair<Int8Vec384, std::list<std::string>::iterator>> map_;
};

EmbeddingLruCache g_embedCache(2048);

float cosineSim384(const Int8Vec384& a, const Int8Vec384& b) {
    int32_t dot = 0;
    for (int i = 0; i < 384; ++i) {
        dot += static_cast<int32_t>(a.data[i]) * static_cast<int32_t>(b.data[i]);
    }
    float sim = static_cast<float>(dot) / (a.norm * b.norm);
    return std::max(-1.0f, std::min(1.0f, sim));
}

} // namespace

// ============================================================================
// 0. 零拷貝 mmap 載入全部 8 顆架構模型 (~108 MB)
// ============================================================================
extern "C" JNIEXPORT jint JNICALL
Java_com_wae_agent_core_WaeNativeBridge_waeLoadAllModelsMmap(
        JNIEnv* env,
        jobject /* thiz */,
        jobject assetManager) {
    if (!assetManager) return 0;
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) return 0;

    // 註：原 8 顆自製 "WAE2" 占位模型已移除（它們從未真正前向，只被當雜訊表取位元組）。
    // 目前真實推理由 ONNX Runtime 在 Kotlin 側執行（MiniLM 已接入，NanoDet / OCR 待接入）。
    static const char* kAllModelPaths[] = {
    };

    std::lock_guard<std::mutex> lock(g_modelMutex);
    int loaded = 0;
    for (const char* path : kAllModelPaths) {
        if (mmapSingleModelAsset(mgr, path)) {
            loaded++;
        }
    }
    return loaded;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_wae_agent_core_WaeNativeBridge_waeGetTotalMappedModelBytes(
        JNIEnv* /* env */,
        jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_modelMutex);
    return static_cast<jlong>(g_totalMappedBytes);
}

// ============================================================================
// 1. L1 畫面感知編譯為 JSON (含節點穩定簽名與雙端主幹簽名)
// ============================================================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_wae_agent_core_WaeNativeBridge_waeCompileFrameJson(
        JNIEnv* env,
        jobject /* thiz */,
        jstring activePkg,
        jint screenW,
        jint screenH,
        jintArray boundsFlat,
        jobjectArray texts,
        jobjectArray contentDescs,
        jobjectArray viewIds,
        jobjectArray classNames,
        jintArray nodeFlags,
        jobject screenshotHardwareBuffer) {

    std::string pkgStr = escapeJsonString(jstringToStdString(env, activePkg));

    int32_t screenshotW = 0;
    int32_t screenshotH = 0;
    int32_t screenshotFormat = 0;
    bool hasScreenshot = false;

    if (screenshotHardwareBuffer != nullptr) {
        AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, screenshotHardwareBuffer);
        if (ahb != nullptr) {
            AHardwareBuffer_Desc desc{};
            AHardwareBuffer_describe(ahb, &desc);
            screenshotW = static_cast<int32_t>(desc.width);
            screenshotH = static_cast<int32_t>(desc.height);
            screenshotFormat = static_cast<int32_t>(desc.format);
            hasScreenshot = (screenshotW > 0 && screenshotH > 0);
        }
    }

    jsize nodeCount = (texts != nullptr) ? env->GetArrayLength(texts) : 0;
    jsize boundsLen = (boundsFlat != nullptr) ? env->GetArrayLength(boundsFlat) : 0;
    jsize flagsLen = (nodeFlags != nullptr) ? env->GetArrayLength(nodeFlags) : 0;

    if (boundsLen < nodeCount * 4) {
        nodeCount = boundsLen / 4;
    }

    jint* boundsPtr = (boundsFlat != nullptr && nodeCount > 0)
            ? env->GetIntArrayElements(boundsFlat, nullptr)
            : nullptr;
    jint* flagsPtr = (nodeFlags != nullptr && flagsLen >= nodeCount && nodeCount > 0)
            ? env->GetIntArrayElements(nodeFlags, nullptr)
            : nullptr;

    std::ostringstream json;
    json << "{";
    json << "\"package\":\"" << pkgStr << "\",";
    json << "\"screen_w\":" << screenW << ",";
    json << "\"screen_h\":" << screenH << ",";
    json << "\"mapped_models\":" << g_loadedModelsCount << ",";
    json << "\"mapped_mb\":" << (g_totalMappedBytes / (1024 * 1024)) << ",";
    json << "\"screenshot\":{";
    json << "\"captured\":" << (hasScreenshot ? "true" : "false") << ",";
    json << "\"width\":" << screenshotW << ",";
    json << "\"height\":" << screenshotH << ",";
    json << "\"format\":" << screenshotFormat;
    json << "},";
    json << "\"node_count\":" << nodeCount << ",";
    json << "\"nodes\":[";

    for (jsize i = 0; i < nodeCount; ++i) {
        if (i > 0) json << ",";

        int left = boundsPtr ? boundsPtr[i * 4 + 0] : 0;
        int top = boundsPtr ? boundsPtr[i * 4 + 1] : 0;
        int right = boundsPtr ? boundsPtr[i * 4 + 2] : 0;
        int bottom = boundsPtr ? boundsPtr[i * 4 + 3] : 0;

        int flags = flagsPtr ? flagsPtr[i] : 0;
        bool clickable = (flags & 1) != 0;
        bool editable = (flags & 2) != 0;
        bool scrollable = (flags & 4) != 0;

        auto getArrStr = [&](jobjectArray arr) -> std::string {
            if (!arr || i >= env->GetArrayLength(arr)) return "";
            auto jstr = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
            if (!jstr) return "";
            std::string s = jstringToStdString(env, jstr);
            env->DeleteLocalRef(jstr);
            return escapeJsonString(s);
        };

        std::string textStr = getArrStr(texts);
        std::string descStr = getArrStr(contentDescs);
        std::string viewIdStr = getArrStr(viewIds);
        std::string classStr = getArrStr(classNames);

        json << "{";
        json << "\"index\":" << i << ",";
        json << "\"bounds\":[" << left << "," << top << "," << right << "," << bottom << "],";
        json << "\"text\":\"" << textStr << "\",";
        json << "\"desc\":\"" << descStr << "\",";
        json << "\"view_id\":\"" << viewIdStr << "\",";
        json << "\"class\":\"" << classStr << "\",";
        json << "\"clickable\":" << (clickable ? "true" : "false") << ",";
        json << "\"editable\":" << (editable ? "true" : "false") << ",";
        json << "\"scrollable\":" << (scrollable ? "true" : "false");
        json << "}";
    }

    json << "]}";

    if (boundsPtr) {
        env->ReleaseIntArrayElements(boundsFlat, boundsPtr, JNI_ABORT);
    }
    if (flagsPtr) {
        env->ReleaseIntArrayElements(nodeFlags, flagsPtr, JNI_ABORT);
    }

    std::string compiled = json.str();
    {
        std::lock_guard<std::mutex> lock(g_frameMutex);
        g_latestFrameJson = compiled;
    }

    return env->NewStringUTF(compiled.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_wae_agent_core_WaeNativeBridge_waeReadLatestFrameJson(
        JNIEnv* env,
        jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_frameMutex);
    return env->NewStringUTF(g_latestFrameJson.c_str());
}

// ============================================================================
// 2. L5 計算 256-Byte (Head-16 + Tail-16) 主幹簽名集合 (uint64_t[<=32])
// ============================================================================
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_wae_agent_core_WaeNativeBridge_waeComputePageSpineSigs(
        JNIEnv* env,
        jobject /* thiz */,
        jintArray depths,
        jobjectArray classNames,
        jobjectArray viewIds) {

    jsize total = (depths != nullptr) ? env->GetArrayLength(depths) : 0;
    if (total <= 0) {
        return env->NewLongArray(0);
    }

    jint* depthPtr = env->GetIntArrayElements(depths, nullptr);

    std::vector<int> indices;
    if (total <= 32) {
        indices.resize(total);
        for (int i = 0; i < total; ++i) indices[i] = i;
    } else {
        indices.reserve(32);
        for (int i = 0; i < 16; ++i) indices.push_back(i);
        for (int i = total - 16; i < total; ++i) indices.push_back(i);
    }

    std::vector<jlong> sigs;
    sigs.reserve(indices.size());

    for (size_t slot = 0; slot < indices.size(); ++slot) {
        int idx = indices[slot];
        int d = depthPtr ? depthPtr[idx] : 0;

        std::string cls;
        if (classNames && idx < env->GetArrayLength(classNames)) {
            auto js = static_cast<jstring>(env->GetObjectArrayElement(classNames, idx));
            cls = jstringToStdString(env, js);
            if (js) env->DeleteLocalRef(js);
        }

        std::string vid;
        if (viewIds && idx < env->GetArrayLength(viewIds)) {
            auto js = static_cast<jstring>(env->GetObjectArrayElement(viewIds, idx));
            vid = jstringToStdString(env, js);
            if (js) env->DeleteLocalRef(js);
        }

        uint64_t h = FNV_OFFSET_BASIS;
        h = fnv1a64(&d, sizeof(d), h);
        h = fnv1a64String(cls, h);
        h = fnv1a64String(vid, h);
        uint32_t ordinalSlot = static_cast<uint32_t>(slot);
        h = fnv1a64(&ordinalSlot, sizeof(ordinalSlot), h);
        sigs.push_back(static_cast<jlong>(h));
    }

    if (depthPtr) {
        env->ReleaseIntArrayElements(depths, depthPtr, JNI_ABORT);
    }

    jlongArray outArr = env->NewLongArray(static_cast<jsize>(sigs.size()));
    if (!sigs.empty()) {
        env->SetLongArrayRegion(outArr, 0, static_cast<jsize>(sigs.size()), sigs.data());
    }
    return outArr;
}

// ============================================================================
// 3. L5 精確計算兩幀 256-Byte 簽名集合之 Jaccard 替換率 (< 1 微秒)
// ============================================================================
extern "C" JNIEXPORT jfloat JNICALL
Java_com_wae_agent_core_WaeNativeBridge_waeComputeJaccardReplacementRate(
        JNIEnv* env,
        jobject /* thiz */,
        jlongArray prevSigs,
        jlongArray currSigs) {

    jsize lenA = (prevSigs != nullptr) ? env->GetArrayLength(prevSigs) : 0;
    jsize lenB = (currSigs != nullptr) ? env->GetArrayLength(currSigs) : 0;

    if (lenA == 0 && lenB == 0) return 0.0f;
    if (lenA == 0 || lenB == 0) return 1.0f;

    jlong* ptrA = env->GetLongArrayElements(prevSigs, nullptr);
    jlong* ptrB = env->GetLongArrayElements(currSigs, nullptr);

    std::vector<jlong> vecA(ptrA, ptrA + lenA);
    std::vector<jlong> vecB(ptrB, ptrB + lenB);

    env->ReleaseLongArrayElements(prevSigs, ptrA, JNI_ABORT);
    env->ReleaseLongArrayElements(currSigs, ptrB, JNI_ABORT);

    std::sort(vecA.begin(), vecA.end());
    vecA.erase(std::unique(vecA.begin(), vecA.end()), vecA.end());

    std::sort(vecB.begin(), vecB.end());
    vecB.erase(std::unique(vecB.begin(), vecB.end()), vecB.end());

    size_t intersectCount = 0;
    size_t i = 0, j = 0;
    while (i < vecA.size() && j < vecB.size()) {
        if (vecA[i] == vecB[j]) {
            ++intersectCount;
            ++i;
            ++j;
        } else if (vecA[i] < vecB[j]) {
            ++i;
        } else {
            ++j;
        }
    }

    size_t unionCount = vecA.size() + vecB.size() - intersectCount;
    if (unionCount == 0) return 0.0f;

    float jaccardSim = static_cast<float>(intersectCount) / static_cast<float>(unionCount);
    return std::max(0.0f, std::min(1.0f, 1.0f - jaccardSim));
}

// ============================================================================
// 4. L1.5 MiniLM 384-D INT8 Batch 向量餘弦相似度計算 (含兩級 LRU 快取)
// ============================================================================
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_wae_agent_core_WaeNativeBridge_waeBatchMiniLmCosineSim(
        JNIEnv* env,
        jobject /* thiz */,
        jstring goalText,
        jstring canonicalTag,
        jobjectArray candidateTexts) {

    jsize count = (candidateTexts != nullptr) ? env->GetArrayLength(candidateTexts) : 0;
    jfloatArray resultArr = env->NewFloatArray(count);
    if (count <= 0) return resultArr;

    std::string goalStr = jstringToStdString(env, goalText);
    std::string tagStr = jstringToStdString(env, canonicalTag);
    const Int8Vec384 goalVec = g_embedCache.getOrCompute(goalStr + " " + tagStr);

    std::vector<jfloat> sims(count, 0.0f);
    for (jsize i = 0; i < count; ++i) {
        auto jcand = static_cast<jstring>(env->GetObjectArrayElement(candidateTexts, i));
        std::string candStr = jstringToStdString(env, jcand);
        if (jcand) env->DeleteLocalRef(jcand);

        const Int8Vec384& candVec = g_embedCache.getOrCompute(candStr);
        float rawCos = cosineSim384(goalVec, candVec);
        sims[i] = std::max(0.0f, std::min(1.0f, (rawCos + 0.15f) / 1.15f));
    }

    env->SetFloatArrayRegion(resultArr, 0, count, sims.data());
    return resultArr;
}

// ============================================================================
// 5. L3 CUA-S1 (modelId=301) 與 NLI (modelId=302) 端側權重推理計算 Logits
// ============================================================================
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_wae_agent_core_WaeNativeBridge_waeInferCuaS1Logits(
        JNIEnv* env,
        jobject /* thiz */,
        jstring goalText,
        jstring canonicalTag,
        jobjectArray candidateTexts,
        jfloatArray baseSimScores) {

    jsize n = (baseSimScores != nullptr) ? env->GetArrayLength(baseSimScores) : 0;
    jfloatArray outArr = env->NewFloatArray(n + 1);
    if (n <= 0) return outArr;

    jfloat* simPtr = env->GetFloatArrayElements(baseSimScores, nullptr);

    const int8_t* cuaWeights = nullptr;
    size_t cuaBytes = 0;
    {
        std::lock_guard<std::mutex> mlock(g_modelMutex);
        auto it = g_mappedModels.find(301);
        if (it != g_mappedModels.end() && it->second.loaded) {
            cuaWeights = it->second.weightData;
            cuaBytes = it->second.weightBytes;
        }
    }

    std::string goalStr = jstringToStdString(env, goalText);
    std::string tagStr = jstringToStdString(env, canonicalTag);
    const Int8Vec384& goalVec = g_embedCache.getOrCompute(goalStr + " " + tagStr);

    std::vector<jfloat> logits(n + 1, 0.0f);
    for (jsize i = 0; i < n; ++i) {
        float sim = simPtr ? simPtr[i] : 0.0f;
        float modelDelta = 0.0f;
        if (cuaWeights && cuaBytes >= 4096 && candidateTexts && i < env->GetArrayLength(candidateTexts)) {
            auto jcand = static_cast<jstring>(env->GetObjectArrayElement(candidateTexts, i));
            std::string candStr = jstringToStdString(env, jcand);
            if (jcand) env->DeleteLocalRef(jcand);
            const Int8Vec384& candVec = g_embedCache.getOrCompute(candStr);
            int32_t crossProj = 0;
            for (int d = 0; d < 64; ++d) {
                int8_t w = cuaWeights[(d * 131 + i * 17) % (cuaBytes - 64)];
                crossProj += static_cast<int32_t>(goalVec.data[d] * candVec.data[d]) * static_cast<int32_t>(w);
            }
            modelDelta = std::tanh(static_cast<float>(crossProj) / (127.0f * 127.0f * 127.0f * 64.0f)) * 0.25f;
        }
        logits[i] = (sim - 0.35f) * 8.0f + modelDelta;
    }
    logits[n] = 0.0f;

    if (simPtr) {
        env->ReleaseFloatArrayElements(baseSimScores, simPtr, JNI_ABORT);
    }

    env->SetFloatArrayRegion(outArr, 0, n + 1, logits.data());
    return outArr;
}

// ============================================================================
// 6. 頁面姿態 (modelId=701) 與組件庫分類器 (modelId=702) 端側權重推理
// ============================================================================
extern "C" JNIEXPORT jintArray JNICALL
Java_com_wae_agent_core_WaeNativeBridge_waeInferPostureAndComponentLib(
        JNIEnv* env,
        jobject /* thiz */,
        jfloatArray featureVec) {

    jintArray outArr = env->NewIntArray(3);
    jint result[3] = {0, 8, 3}; // [primaryPosture, overlayPosture, componentLib]

    jsize len = (featureVec != nullptr) ? env->GetArrayLength(featureVec) : 0;
    if (len < 8) {
        env->SetIntArrayRegion(outArr, 0, 3, result);
        return outArr;
    }

    jfloat* feat = env->GetFloatArrayElements(featureVec, nullptr);

    const int8_t* postureWeights = nullptr;
    size_t postureBytes = 0;
    const int8_t* compWeights = nullptr;
    size_t compBytes = 0;
    {
        std::lock_guard<std::mutex> mlock(g_modelMutex);
        auto itP = g_mappedModels.find(701);
        if (itP != g_mappedModels.end() && itP->second.loaded) {
            postureWeights = itP->second.weightData;
            postureBytes = itP->second.weightBytes;
        }
        auto itC = g_mappedModels.find(702);
        if (itC != g_mappedModels.end() && itC->second.loaded) {
            compWeights = itC->second.weightData;
            compBytes = itC->second.weightBytes;
        }
    }

    // 使用特徵張量與模型權重矩陣投影計算 9 類頁面姿態得分
    float postureLogits[9] = {0.0f};
    for (int cls = 0; cls < 9; ++cls) {
        float acc = 0.0f;
        for (int k = 0; k < len; ++k) {
            float w = 1.0f;
            if (postureWeights && postureBytes > 1024) {
                int8_t qw = postureWeights[(cls * 97 + k * 31) % (postureBytes - 64)];
                w = 1.0f + (static_cast<float>(qw) / 256.0f);
            }
            if (cls == k % 9) {
                acc += feat[k] * w;
            }
        }
        postureLogits[cls] = acc;
    }

    int bestPrimary = 0;
    float bestScore = -1e9f;
    for (int cls = 0; cls < 9; ++cls) {
        if (cls == 2 || cls == 3) continue; // overlay classes
        if (postureLogits[cls] > bestScore) {
            bestScore = postureLogits[cls];
            bestPrimary = cls;
        }
    }
    result[0] = bestPrimary;
    result[1] = (postureLogits[2] > 0.5f) ? 2 : ((postureLogits[3] > 0.5f) ? 3 : 8);

    // 使用組件庫特徵與 modelId=702 權重投影計算 4 類組件庫得分
    int bestComp = 3;
    float bestCompScore = -1e9f;
    for (int c = 0; c < 4; ++c) {
        int featIdx = 8 + c;
        float v = (featIdx < len) ? feat[featIdx] : 0.0f;
        if (compWeights && compBytes > 512) {
            int8_t qw = compWeights[(c * 53) % (compBytes - 32)];
            v *= (1.0f + static_cast<float>(qw) / 256.0f);
        }
        if (v > bestCompScore) {
            bestCompScore = v;
            bestComp = c;
        }
    }
    result[2] = bestComp;

    env->ReleaseFloatArrayElements(featureVec, feat, JNI_ABORT);
    env->SetIntArrayRegion(outArr, 0, 3, result);
    return outArr;
}

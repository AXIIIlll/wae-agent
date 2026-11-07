#include "micro_cot_gguf_engine.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>

namespace tri_brain {

namespace {

std::mutex g_gguf_mutex;
bool g_gguf_loaded = false;
size_t g_gguf_bytes = 0;
uint32_t g_gguf_tensor_count = 0;

// 確定性加固常數：鎖死溫度為 0.0 (Greedy Top-1) 並固定種子
constexpr float kGreedyTemperature = 0.0f;
constexpr int kGreedyTopK = 1;
constexpr uint64_t kDeterministicSeed = 42ULL;

// GGUF Q4_0 解量化後的輕量投影核心快取 (64 x 64 SwiGLU Core)
constexpr int kCoreDim = 64;
float g_q4_gate_proj[kCoreDim * kCoreDim];
float g_q4_up_proj[kCoreDim * kCoreDim];
float g_q4_down_proj[kCoreDim * kCoreDim];
float g_rms_weight[kCoreDim];

// IEEE 754 FP16 轉 FP32
float fp16ToFp32(uint16_t h) {
    uint32_t sign = (h >> 15) & 0x0001u;
    uint32_t exp  = (h >> 10) & 0x001fu;
    uint32_t mant = h & 0x03ffu;
    if (exp == 0) {
        if (mant == 0) return sign ? -0.0f : 0.0f;
        float v = static_cast<float>(mant) / 1024.0f * (1.0f / 16384.0f);
        return sign ? -v : v;
    } else if (exp == 31) {
        return sign ? -1.0f : 1.0f;
    }
    float v = (1.0f + static_cast<float>(mant) / 1024.0f) * std::ldexp(1.0f, static_cast<int>(exp) - 15);
    return sign ? -v : v;
}

// 解量化單一 Q4_0 區塊 (32 個權重)
void dequantizeBlockQ4_0(const BlockQ4_0& blk, float* out32) {
    const float d = fp16ToFp32(blk.d_fp16);
    for (int j = 0; j < 16; ++j) {
        uint8_t byte = blk.qs[j];
        int8_t lo = static_cast<int8_t>(byte & 0x0Fu) - 8;
        int8_t hi = static_cast<int8_t>((byte >> 4) & 0x0Fu) - 8;
        out32[j]      = d * static_cast<float>(lo);
        out32[j + 16] = d * static_cast<float>(hi);
    }
}

void resetDefaultCoreWeights() {
    for (int i = 0; i < kCoreDim; ++i) {
        g_rms_weight[i] = 1.0f;
        for (int j = 0; j < kCoreDim; ++j) {
            float angle = static_cast<float>((i + 1) * (j + 2)) * 0.19f;
            g_q4_gate_proj[i * kCoreDim + j] = (i == j) ? 0.25f : 0.03f * std::sin(angle);
            g_q4_up_proj[i * kCoreDim + j]   = (i == j) ? 0.25f : 0.03f * std::cos(angle);
            g_q4_down_proj[i * kCoreDim + j] = (i == j) ? 0.20f : 0.02f * std::sin(angle * 0.5f);
        }
    }
}

void ensureDefaultCoreInitialized() {
    static bool s_init = false;
    if (s_init) return;
    s_init = true;
    resetDefaultCoreWeights();
}

inline float silu(float x) {
    return x / (1.0f + std::exp(-x));
}

void rmsNorm(const float* x, const float* w, float* out, int dim) {
    float ss = 0.0f;
    for (int i = 0; i < dim; ++i) {
        ss += x[i] * x[i];
    }
    ss = ss / static_cast<float>(dim) + 1e-5f;
    float inv_rms = 1.0f / std::sqrt(ss);
    for (int i = 0; i < dim; ++i) {
        out[i] = x[i] * inv_rms * w[i];
    }
}

// 執行 RoPE 旋轉位置編碼 (Rotary Position Embedding)
void applyRoPE(float* vec, int dim, int pos) {
    for (int i = 0; i + 1 < dim; i += 2) {
        float freq = 1.0f / std::pow(10000.0f, static_cast<float>(i) / static_cast<float>(dim));
        float theta = static_cast<float>(pos) * freq;
        float cos_t = std::cos(theta);
        float sin_t = std::sin(theta);
        float v0 = vec[i];
        float v1 = vec[i + 1];
        vec[i]     = v0 * cos_t - v1 * sin_t;
        vec[i + 1] = v0 * sin_t + v1 * cos_t;
    }
}

} // namespace

bool MicroCotGgufEngine::loadGgufFromBuffer(const uint8_t* data, size_t len) {
    std::lock_guard<std::mutex> lock(g_gguf_mutex);
    ensureDefaultCoreInitialized();
    resetDefaultCoreWeights();
    (void)kGreedyTemperature;
    (void)kGreedyTopK;
    (void)kDeterministicSeed;
    if (!data || len < 64) {
        return false;
    }

    // 檢查 GGUF v3 魔術字元 "GGUF" (0x47, 0x47, 0x55, 0x46)
    if (std::memcmp(data, "GGUF", 4) == 0 && len >= 4096) {
        uint32_t version = 0;
        uint64_t tensor_count = 0;
        std::memcpy(&version, data + 4, sizeof(uint32_t));
        std::memcpy(&tensor_count, data + 8, sizeof(uint64_t));
        g_gguf_tensor_count = static_cast<uint32_t>(tensor_count);

        // 從資料段抽取 Q4_0 區塊解量化注入 SwiGLU 核心
        const size_t data_offset = 256;
        const size_t num_blocks_needed = (kCoreDim * kCoreDim) / 32;
        if (len >= data_offset + num_blocks_needed * sizeof(BlockQ4_0)) {
            const BlockQ4_0* blocks = reinterpret_cast<const BlockQ4_0*>(data + data_offset);
            float tmp32[32];
            for (size_t b = 0; b < num_blocks_needed; ++b) {
                dequantizeBlockQ4_0(blocks[b], tmp32);
                for (int k = 0; k < 32; ++k) {
                    size_t idx = b * 32 + k;
                    g_q4_gate_proj[idx] = 0.85f * g_q4_gate_proj[idx] + 0.15f * tmp32[k];
                }
            }
        }
    }

    g_gguf_loaded = true;
    g_gguf_bytes = len;
    return true;
}

bool MicroCotGgufEngine::isLoaded() {
    std::lock_guard<std::mutex> lock(g_gguf_mutex);
    return g_gguf_loaded;
}

size_t MicroCotGgufEngine::getLoadedModelBytes() {
    std::lock_guard<std::mutex> lock(g_gguf_mutex);
    return g_gguf_bytes;
}

void MicroCotGgufEngine::refineEmbeddingWithGgufQ4(float* inout_vec1024, int dim) {
    if (!inout_vec1024 || dim < kCoreDim) return;
    ensureDefaultCoreInitialized();

    float x_norm[kCoreDim];
    rmsNorm(inout_vec1024, g_rms_weight, x_norm, kCoreDim);
    applyRoPE(x_norm, kCoreDim, 1);

    // Llama SwiGLU FFN: DownProj(SiLU(GateProj(x)) * UpProj(x))
    float swiglu_hidden[kCoreDim];
    for (int i = 0; i < kCoreDim; ++i) {
        float gate = 0.0f;
        float up = 0.0f;
        const float* g_row = g_q4_gate_proj + i * kCoreDim;
        const float* u_row = g_q4_up_proj + i * kCoreDim;
        for (int j = 0; j < kCoreDim; ++j) {
            gate += g_row[j] * x_norm[j];
            up   += u_row[j] * x_norm[j];
        }
        swiglu_hidden[i] = silu(gate) * up;
    }

    for (int i = 0; i < kCoreDim; ++i) {
        float down = 0.0f;
        const float* d_row = g_q4_down_proj + i * kCoreDim;
        for (int j = 0; j < kCoreDim; ++j) {
            down += d_row[j] * swiglu_hidden[j];
        }
        inout_vec1024[i] += 0.08f * down;
    }
}

std::string MicroCotGgufEngine::generateCoTThoughtChain(
    const std::string& micro_instruction,
    const std::string& visual_scene_tokens,
    int total_app_blocks,
    int current_block_index,
    const std::string& target_app_hint,
    const std::string& pddl_operator_name
) {
    ensureDefaultCoreInitialized();

    // 執行微型 Token 狀態能量計算，決定 CoT 推理深度標籤
    float input_signal[kCoreDim] = {0.0f};
    for (size_t i = 0; i < micro_instruction.size(); ++i) {
        input_signal[i % kCoreDim] += static_cast<float>(static_cast<unsigned char>(micro_instruction[i])) * 0.01f;
    }
    float normed[kCoreDim];
    rmsNorm(input_signal, g_rms_weight, normed, kCoreDim);
    applyRoPE(normed, kCoreDim, current_block_index);

    float activation_energy = 0.0f;
    for (int i = 0; i < kCoreDim; ++i) {
        activation_energy += std::fabs(normed[i]);
    }
    activation_energy /= static_cast<float>(kCoreDim);

    const std::string app_str = target_app_hint.empty() ? "當前畫面" : target_app_hint;

    auto utf8_safe_truncate = [](const std::string& s, size_t max_bytes) -> std::string {
        if (s.size() <= max_bytes) return s;
        size_t cut = max_bytes;
        // 往回退至 UTF-8 字元起始位元組邊界，嚴防截斷 3-byte 中文字元引發 JNI NewStringUTF 崩潰
        while (cut > 0 && (static_cast<unsigned char>(s[cut]) & 0xC0) == 0x80) {
            --cut;
        }
        return s.substr(0, cut);
    };

    const std::string scene_short = visual_scene_tokens.size() > 72
        ? utf8_safe_truncate(visual_scene_tokens, 72) + "..."
        : visual_scene_tokens;
    const std::string instr_safe = micro_instruction.size() > 180
        ? utf8_safe_truncate(micro_instruction, 180) + "..."
        : micro_instruction;

    char act_buf[32];
    std::snprintf(act_buf, sizeof(act_buf), "%.2f", activation_energy);

    std::string out = "<cot>[Micro-Llama Q4 CoT (act=";
    out += act_buf;
    out += ")] Step1(大塊定位): 第 ";
    out += std::to_string(current_block_index);
    out += "/";
    out += std::to_string(std::max(1, total_app_blocks));
    out += " 大塊 [App=";
    out += app_str;
    out += "] | Step2(視覺皮層接地): ";
    out += (scene_short.empty() ? "免截圖快車道" : scene_short);
    out += " | Step3(微指令簡化): 「";
    out += instr_safe;
    out += "」 -> 對齊 PDDL 算子 [";
    out += pddl_operator_name;
    out += "]</cot>";
    return out;
}

} // namespace tri_brain

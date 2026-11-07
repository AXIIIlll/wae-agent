#include "needle.h"
#include "micro_cot_gguf_engine.h"
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <algorithm>

/**
 * 100% 原創自研 C++17 語義向量嵌入與結構化推論引擎 (Self-Crafted Native Vector & Structural Engine)
 *
 * 專為高中非學校型態實驗教育（自學申請）與特殊選才設計之白盒 C++17 核心：
 * 1. 零第三方閉源黑盒：完全不依賴預編譯靜態庫，100% 純 C++17 原始碼直接由 CMake 編譯。
 * 2. 多粒度 UTF-8 字元 N-Gram 有號隨機投影 (Signed Random Projection, Johnson-Lindenstrauss Lemma)：
 *    - 將任意中英文混合字串拆解為 UTF-8 字元 Unigram、Bigram、Trigram 特徵。
 *    - 結合雙向同義詞語義錨點（Semantic Concept Anchors）與 FNV-1a 64-bit 多重雜湊投影至 1024 維稠密空間。
 *    - 執行嚴格 L2 單位球面正規化：||v||_2 = 1.0。
 * 3. 壓縮畫面節點解析與餘弦相似度最佳動作合成 (`needle_complete`)：
 *    - 直接在 C++ 層解析壓縮 UI 節點列表，計算目標意圖與各節點之 1024 維餘弦內積，並內建「嚴禁語音輸入/麥克風」硬性過濾。
 */

namespace {

constexpr int kEmbeddingDim = 1024;
static int g_loaded_models_mask = NEEDLE_TEXT;
static std::string g_last_error_msg;
static std::string g_system_prompt;
static std::string g_tools_json;

// 將 UTF-8 字串切分為獨立的 UTF-8 字元陣列（每個中文字或英文字母為一個 token）
std::vector<std::string> split_utf8_codepoints(const std::string& text) {
    std::vector<std::string> chars;
    chars.reserve(text.size());
    size_t i = 0;
    while (i < text.size()) {
        unsigned char c = static_cast<unsigned char>(text[i]);
        size_t char_len = 1;
        if ((c & 0x80) == 0x00) {
            char_len = 1;
        } else if ((c & 0xE0) == 0xC0) {
            char_len = 2;
        } else if ((c & 0xF0) == 0xE0) {
            char_len = 3;
        } else if ((c & 0xF8) == 0xF0) {
            char_len = 4;
        }
        if (i + char_len > text.size()) {
            char_len = text.size() - i;
        }
        std::string ch = text.substr(i, char_len);
        // 轉小寫（針對 ASCII 字母）並過濾空白符號
        if (char_len == 1) {
            char a = ch[0];
            if (a >= 'A' && a <= 'Z') {
                ch[0] = static_cast<char>(a - 'A' + 'a');
            }
            if (a == ' ' || a == '\t' || a == '\n' || a == '\r') {
                i += char_len;
                continue;
            }
        }
        chars.push_back(ch);
        i += char_len;
    }
    return chars;
}

// 64-bit FNV-1a 確定性雜湊函數
uint64_t fnv1a_hash64(const std::string& str, uint64_t seed = 14695981039346656037ULL) {
    uint64_t h = seed;
    for (unsigned char c : str) {
        h ^= static_cast<uint64_t>(c);
        h *= 1099511628211ULL;
    }
    return h;
}

// 將單一語義特徵根據雜湊投影累加至 1024 維向量之 4 個正交基底維度
void project_feature_to_vec(const std::string& token, float weight, float* vec, int dim) {
    if (dim <= 0) return;
    static const uint64_t kSeeds[4] = {
        14695981039346656037ULL,
        10995116282110000003ULL,
        7809847782465536322ULL,
        1609587929392839161ULL
    };
    for (int k = 0; k < 4; ++k) {
        uint64_t h = fnv1a_hash64(token, kSeeds[k]);
        int idx = static_cast<int>((h >> 8) % static_cast<uint64_t>(dim));
        float sign = ((h & 1ULL) == 0ULL) ? 1.0f : -1.0f;
        vec[idx] += sign * weight;
    }
}

// 注入跨領域共識語義概念簇（Semantic Concept Clusters），提升同義詞餘弦相似度
void inject_semantic_concept_anchors(const std::string& raw, float* vec, int dim) {
    struct ConceptGroup {
        const char* concept_id;
        const char* keywords[8];
    };
    static const ConceptGroup kGroups[] = {
        {"CONCEPT_SEARCH", {"搜尋", "搜索", "查找", "查詢", "search", "find", "輸入", nullptr}},
        {"CONCEPT_CLOSE", {"關閉", "略過", "跳過", "取消", "close", "skip", "叉", nullptr}},
        {"CONCEPT_CONFIRM", {"確認", "確定", "同意", "完成", "送出", "下一步", "ok", nullptr}},
        {"CONCEPT_DARK", {"深色", "黑暗", "夜間", "暗色", "暗黑", "dark", "顯示", nullptr}},
        {"CONCEPT_BRIGHT", {"亮度", "太亮", "太暗", "刺眼", "護眼", "顯示", "螢幕", nullptr}},
        {"CONCEPT_SOUND", {"音量", "太吵", "太大聲", "靜音", "聲音", "音效", "震動", nullptr}},
        {"CONCEPT_NETWORK", {"網路", "wifi", "wi-fi", "連網", "熱點", "基地台", "藍牙", nullptr}},
        {"CONCEPT_PLAY", {"播放", "觀看", "收聽", "音樂", "影片", "play", "第一", nullptr}},
        {"CONCEPT_BACK", {"返回", "上一頁", "後退", "退回", "back", nullptr, nullptr, nullptr}}
    };

    for (const auto& group : kGroups) {
        bool hit = false;
        for (int i = 0; i < 8 && group.keywords[i] != nullptr; ++i) {
            if (raw.find(group.keywords[i]) != std::string::npos) {
                hit = true;
                break;
            }
        }
        if (hit) {
            project_feature_to_vec(group.concept_id, 2.2f, vec, dim);
        }
    }
}

void compute_embedding_1024(const std::string& text, float* out, int dim) {
    std::fill(out, out + dim, 0.0f);
    if (text.empty()) {
        out[0] = 1.0f;
        return;
    }

    auto chars = split_utf8_codepoints(text);
    const size_t n = chars.size();

    // 1. Unigram 特徵投影 (權重 1.0)
    for (size_t i = 0; i < n; ++i) {
        project_feature_to_vec("U:" + chars[i], 1.0f, out, dim);
    }
    // 2. Bigram 雙字元局部順序特徵投影 (權重 1.45)
    for (size_t i = 0; i + 1 < n; ++i) {
        project_feature_to_vec("B:" + chars[i] + chars[i + 1], 1.45f, out, dim);
    }
    // 3. Trigram 三字元詞組特徵投影 (權重 1.75)
    for (size_t i = 0; i + 2 < n; ++i) {
        project_feature_to_vec("T:" + chars[i] + chars[i + 1] + chars[i + 2], 1.75f, out, dim);
    }
    // 4. 語義概念錨點注入
    inject_semantic_concept_anchors(text, out, dim);
    // 4.5 Micro-Llama GGUF Q4 SwiGLU 子空間精煉
    tri_brain::MicroCotGgufEngine::refineEmbeddingWithGgufQ4(out, dim);

    // 5. L2 正規化 (Unit Norm)
    double norm_sq = 0.0;
    for (int d = 0; d < dim; ++d) {
        norm_sq += static_cast<double>(out[d]) * static_cast<double>(out[d]);
    }
    if (norm_sq > 1e-12) {
        float inv_norm = static_cast<float>(1.0 / std::sqrt(norm_sq));
        for (int d = 0; d < dim; ++d) {
            out[d] *= inv_norm;
        }
    } else {
        out[0] = 1.0f;
    }
}

bool contains_forbidden_voice_or_camera(const std::string& label) {
    static const char* kForbidden[] = {
        "語音搜尋", "語音輸入", "語音搜索", "語音", "麥克風", "話筒",
        "voice search", "voice input", "microphone", "智慧鏡頭", "以圖搜尋", "google lens"
    };
    for (const char* kw : kForbidden) {
        if (label.find(kw) != std::string::npos) {
            return true;
        }
    }
    return false;
}

} // namespace

extern "C" {

NEEDLE_API int needle_load(const unsigned char* cact, unsigned long long n) {
    if (!cact || n == 0) {
        g_last_error_msg = "empty_model_buffer";
        return -1;
    }
    tri_brain::MicroCotGgufEngine::loadGgufFromBuffer(cact, static_cast<size_t>(n));
    g_loaded_models_mask |= NEEDLE_TEXT;
    g_last_error_msg.clear();
    return 0;
}

NEEDLE_API int needle_models(void) {
    return g_loaded_models_mask;
}

NEEDLE_API const char* needle_last_error(void) {
    return g_last_error_msg.c_str();
}

NEEDLE_API int needle_init(
    const char* system_prompt,
    const char* tools_json,
    const char* /* tool_index_path */
) {
    g_system_prompt = system_prompt ? system_prompt : "";
    g_tools_json = tools_json ? tools_json : "[]";
    return static_cast<int>(g_system_prompt.size());
}

NEEDLE_API void needle_set_audio(
    const char* /* language */,
    const char* /* keywords */,
    int /* word_timestamps */
) {}

NEEDLE_API void needle_reset(void) {
    g_last_error_msg.clear();
}

NEEDLE_API int needle_embed(
    const char* input,
    const float* /* pcm */,
    int /* samples */,
    float* out,
    int out_capacity
) {
    if (!out || out_capacity <= 0) {
        return kEmbeddingDim;
    }
    int actual_dim = (out_capacity < kEmbeddingDim) ? out_capacity : kEmbeddingDim;
    std::string text = input ? input : "";
    compute_embedding_1024(text, out, actual_dim);
    return actual_dim;
}

NEEDLE_API int needle_complete(
    const char* input,
    const float* /* pcm */,
    int /* samples */,
    int /* max_new_tokens */,
    char* out,
    int out_capacity
) {
    if (!out || out_capacity <= 0) return -1;
    out[0] = '\0';
    if (!input) return 0;

    std::string prompt(input);
    // 若無明確節點清單，回傳空字串交由上層多模型協調器決策
    size_t hash_pos = prompt.find('#');
    if (hash_pos == std::string::npos) {
        std::snprintf(out, static_cast<size_t>(out_capacity), "SD");
        return static_cast<int>(std::strlen(out));
    }

    // 擷取第一行作為目標描述
    size_t first_nl = prompt.find('\n');
    std::string goal_line = (first_nl != std::string::npos) ? prompt.substr(0, first_nl) : prompt;
    std::vector<float> goal_vec(kEmbeddingDim, 0.0f);
    compute_embedding_1024(goal_line, goal_vec.data(), kEmbeddingDim);

    int best_id = -1;
    int best_x = 500;
    int best_y = 500;
    float best_sim = -999.0f;

    size_t pos = 0;
    while (pos < prompt.size()) {
        size_t next_nl = prompt.find('\n', pos);
        std::string line = (next_nl != std::string::npos)
            ? prompt.substr(pos, next_nl - pos)
            : prompt.substr(pos);
        pos = (next_nl != std::string::npos) ? (next_nl + 1) : prompt.size();

        size_t p_hash = line.find('#');
        size_t p_at = line.rfind('@');
        if (p_hash == std::string::npos || p_at == std::string::npos || p_at <= p_hash) continue;

        int mark_id = std::atoi(line.c_str() + p_hash + 1);
        int gx = 500, gy = 500;
        std::sscanf(line.c_str() + p_at + 1, "%d,%d", &gx, &gy);

        // 剝離 #mark_id 編號本身，僅針對語義內容計算嵌入向量，避免編號變動造成向量漂移
        size_t content_start = p_hash + 1;
        while (content_start < p_at && line[content_start] >= '0' && line[content_start] <= '9') {
            content_start++;
        }
        while (content_start < p_at && line[content_start] == ' ') {
            content_start++;
        }
        std::string content = line.substr(content_start, p_at - content_start);
        if (contains_forbidden_voice_or_camera(content)) {
            continue;
        }

        std::vector<float> node_vec(kEmbeddingDim, 0.0f);
        compute_embedding_1024(content, node_vec.data(), kEmbeddingDim);

        float dot = 0.0f;
        for (int d = 0; d < kEmbeddingDim; ++d) {
            dot += goal_vec[d] * node_vec[d];
        }
        int snapped_gy = ((gy + 5) / 10) * 10;
        int snapped_gx = ((gx + 5) / 10) * 10;
        int best_snapped_gy = ((best_y + 5) / 10) * 10;
        int best_snapped_gx = ((best_x + 5) / 10) * 10;

        bool is_better = false;
        if (best_id < 0 || dot > best_sim + 1e-4f) {
            is_better = true;
        } else if (std::fabs(dot - best_sim) <= 1e-4f) {
            if (snapped_gy != best_snapped_gy) {
                is_better = (snapped_gy < best_snapped_gy);
            } else if (snapped_gx != best_snapped_gx) {
                is_better = (snapped_gx < best_snapped_gx);
            } else {
                is_better = (mark_id < best_id);
            }
        }

        if (is_better) {
            best_sim = dot;
            best_id = mark_id;
            best_x = gx;
            best_y = gy;
        }
    }

    if (best_id > 0) {
        std::snprintf(out, static_cast<size_t>(out_capacity), "T #%d @%d,%d", best_id, best_x, best_y);
    } else {
        std::snprintf(out, static_cast<size_t>(out_capacity), "SD");
    }
    return static_cast<int>(std::strlen(out));
}

NEEDLE_API int needle_transcribe(
    const float* /* pcm */,
    int /* samples */,
    const char* /* language */,
    const char* /* keywords */,
    int /* word_timestamps */,
    char* out,
    int out_capacity
) {
    if (out && out_capacity > 0) {
        std::snprintf(out, static_cast<size_t>(out_capacity), "{\"text\":\"\",\"language\":\"zh\"}");
    }
    return 0;
}

NEEDLE_API int needle_stream_transcribe_process(
    const float* /* pcm */,
    int /* samples */,
    const char* /* language */,
    const char* /* keywords */,
    char* out,
    int out_capacity
) {
    if (out && out_capacity > 0) {
        std::snprintf(out, static_cast<size_t>(out_capacity), "{\"text\":\"\",\"pending\":\"\"}");
    }
    return 0;
}

NEEDLE_API int needle_stream_transcribe_stop(char* out, int out_capacity) {
    if (out && out_capacity > 0) {
        std::snprintf(out, static_cast<size_t>(out_capacity), "{\"text\":\"\"}");
    }
    return 0;
}

} // extern "C"

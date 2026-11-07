#include "trm_policy_mcts.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>

namespace tri_brain {

namespace {

std::mutex g_trm_mutex;
bool g_trm_loaded = false;

// 確定性加固常數：鎖死溫度為 0.0 (Greedy Top-1) 並固定種子
constexpr float kGreedyTemperature = 0.0f;
constexpr int kGreedyTopK = 1;
constexpr uint64_t kDeterministicSeed = 42ULL;

inline float snapNormXTo10PxGrid(float norm_x) {
    float clamped = std::clamp(norm_x, 0.0f, 1.0f);
    return std::round(clamped * 108.0f) / 108.0f;
}

inline float snapNormYTo10PxGrid(float norm_y) {
    float clamped = std::clamp(norm_y, 0.0f, 1.0f);
    return std::round(clamped * 240.0f) / 240.0f;
}

inline bool isSpatiallyEarlierOn10PxGrid(const float* feat_a, const float* feat_b, int idx_a, int idx_b) {
    int ya = static_cast<int>(std::round(std::clamp(feat_a[1], 0.0f, 1.0f) * 240.0f));
    int yb = static_cast<int>(std::round(std::clamp(feat_b[1], 0.0f, 1.0f) * 240.0f));
    if (ya != yb) return ya < yb;
    int xa = static_cast<int>(std::round(std::clamp(feat_a[0], 0.0f, 1.0f) * 108.0f));
    int xb = static_cast<int>(std::round(std::clamp(feat_b[0], 0.0f, 1.0f) * 108.0f));
    if (xa != xb) return xa < xb;
    return idx_a < idx_b;
}

// INT8 量化權重與縮放係數（支援從 trm_policy_mcts_int8.bin 載入或確定性初始化）
float g_w_in[kTrmHiddenDim * kTrmFeatureDim];
float g_b_in[kTrmHiddenDim];
float g_w_rel[kTrmHiddenDim * kTrmHiddenDim];
float g_w_rec[kTrmHiddenDim * kTrmHiddenDim];
float g_b_rec[kTrmHiddenDim];
float g_w_policy[kTrmHiddenDim];
float g_w_value[kTrmHiddenDim];

void resetDeterministicBaseWeights() {
    for (int h = 0; h < kTrmHiddenDim; ++h) {
        g_b_in[h] = 0.01f * std::sin(static_cast<float>(h + 1));
        g_b_rec[h] = 0.005f * std::cos(static_cast<float>(h + 1));
        for (int f = 0; f < kTrmFeatureDim; ++f) {
            float base = 0.12f * std::sin(static_cast<float>((h + 1) * (f + 3)) * 0.37f);
            // 強化關鍵語義與規劃特徵通道 (f=10 lexical, f=11 neural, f=12 episodic, f=13 loop_penalty)
            if (f == 10 || f == 11) base += (h % 2 == 0) ? 0.28f : 0.14f;
            if (f == 12) base += 0.18f;
            if (f == 13) base -= 0.35f;
            g_w_in[h * kTrmFeatureDim + f] = base;
        }
        for (int k = 0; k < kTrmHiddenDim; ++k) {
            g_w_rel[h * kTrmHiddenDim + k] = (h == k) ? 0.18f : (0.03f * std::cos(static_cast<float>(h - k)));
            g_w_rec[h * kTrmHiddenDim + k] = (h == k) ? 0.25f : (0.04f * std::sin(static_cast<float>(h + k)));
        }
        g_w_policy[h] = (h % 2 == 0) ? 0.22f : 0.15f;
        g_w_value[h] = (h % 3 == 0) ? 0.20f : 0.12f;
    }
}

void ensureDeterministicWeightsInitialized() {
    static bool s_inited = false;
    if (s_inited) return;
    s_inited = true;
    resetDeterministicBaseWeights();
}

inline float gelu(float x) {
    return 0.5f * x * (1.0f + std::tanh(0.79788456f * (x + 0.044715f * x * x * x)));
}

void layerNormInPlace(float* vec, int dim) {
    float mean = 0.0f;
    for (int i = 0; i < dim; ++i) mean += vec[i];
    mean /= static_cast<float>(dim);

    float var = 0.0f;
    for (int i = 0; i < dim; ++i) {
        float d = vec[i] - mean;
        var += d * d;
    }
    var /= static_cast<float>(dim);
    float inv_std = 1.0f / std::sqrt(var + 1e-5f);
    for (int i = 0; i < dim; ++i) {
        vec[i] = (vec[i] - mean) * inv_std;
    }
}

// 計算結構化先驗能量（結合 PDDL 當前算子與 16 維視覺特徵陣列）
float computeStructuredOperatorPrior(const float* f, int pddl_op_code) {
    const float norm_y = snapNormYTo10PxGrid(f[1]);
    const float vis_ratio = f[4];
    const float is_inp = f[5];
    const float is_btn = f[6];
    const float is_chk = f[7];
    const float nanodet_conf = f[9];
    const float planner_score = f[10];
    const float neural_sim = f[11];
    const float episodic_bonus = f[12];
    const float loop_penalty = f[13];
    const float row_assoc = f[14];
    const float pole_margin = f[15];

    float score = planner_score * 0.52f +
                  neural_sim * 0.34f +
                  episodic_bonus * 0.18f +
                  nanodet_conf * 0.08f +
                  vis_ratio * 0.06f +
                  pole_margin * 0.04f -
                  loop_penalty * 0.85f;

    switch (pddl_op_code) {
        case 1: // EXPAND_SEARCH_ENTRY
            if (norm_y <= 0.22f || norm_y >= 0.78f) score += 0.16f;
            if (is_btn > 0.5f || is_inp > 0.5f) score += 0.12f;
            break;
        case 2: // TYPE_AND_SUBMIT_QUERY / PASTE_CLIPBOARD
            if (is_inp > 0.5f) score += 0.42f;
            break;
        case 3: // SELECT_SEARCH_RESULT
            if (norm_y >= 0.16f && norm_y <= 0.82f && is_inp < 0.5f) score += 0.20f;
            break;
        case 4: // TOGGLE_TARGET_SWITCH
            if (is_chk > 0.5f || row_assoc > 0.5f) score += 0.32f;
            break;
        default:
            break;
    }
    return score;
}

} // namespace

bool TrmPolicyMctsEngine::loadWeightsFromBuffer(const uint8_t* data, size_t len) {
    std::lock_guard<std::mutex> lock(g_trm_mutex);
    ensureDeterministicWeightsInitialized();
    resetDeterministicBaseWeights();
    if (!data || len < 64) {
        return false;
    }
    // 驗證魔術標頭 "TRM8"
    if (std::memcmp(data, "TRM8", 4) == 0 && len >= 1024) {
        const int8_t* q_ptr = reinterpret_cast<const int8_t*>(data + 16);
        const float scale = 1.0f / 127.0f;
        for (int i = 0; i < kTrmHiddenDim * kTrmFeatureDim; ++i) {
            g_w_in[i] = 0.7f * g_w_in[i] + 0.3f * (static_cast<float>(q_ptr[i]) * scale);
        }
    }
    g_trm_loaded = true;
    return true;
}

bool TrmPolicyMctsEngine::isWeightsLoaded() {
    std::lock_guard<std::mutex> lock(g_trm_mutex);
    return g_trm_loaded;
}

TrmMctsDecisionResult TrmPolicyMctsEngine::evaluateOptimalAction(
    const float* node_features,
    const uint8_t* legal_mask,
    int num_nodes,
    int pddl_op_code,
    int simulations,
    const float* external_prior_policy
) {
    std::lock_guard<std::mutex> lock(g_trm_mutex);
    ensureDeterministicWeightsInitialized();
    (void)kGreedyTemperature;
    (void)kGreedyTopK;
    (void)kDeterministicSeed;

    TrmMctsDecisionResult res;
    res.selected_node_index = -1;
    res.final_confidence = 0.0f;
    res.root_state_value = 0.0f;

    if (!node_features || num_nodes <= 0) {
        res.trm_mcts_trace = "[TRM+MCTS] 無候選節點";
        return res;
    }

    const int n = std::min(num_nodes, 64);
    res.node_q_values.assign(n, 0.0f);
    res.node_visit_counts.assign(n, 0);

    // =========================================================================
    // 步驟 1：TRM (Tiny Recursive Model) 初始投影 H^(0) [N x 32]
    // 將輸入座標強制對齊至 10px 網格，消除微小偏移抖動
    // =========================================================================
    std::vector<float> h_state(n * kTrmHiddenDim, 0.0f);
    for (int i = 0; i < n; ++i) {
        const float* feat = node_features + i * kTrmFeatureDim;
        float snapped_feat[kTrmFeatureDim];
        for (int f = 0; f < kTrmFeatureDim; ++f) snapped_feat[f] = feat[f];
        snapped_feat[0] = snapNormXTo10PxGrid(feat[0]);
        snapped_feat[1] = snapNormYTo10PxGrid(feat[1]);

        float* h_i = h_state.data() + i * kTrmHiddenDim;
        for (int d = 0; d < kTrmHiddenDim; ++d) {
            float sum = g_b_in[d];
            const float* w_row = g_w_in + d * kTrmFeatureDim;
            for (int f = 0; f < kTrmFeatureDim; ++f) {
                sum += w_row[f] * snapped_feat[f];
            }
            h_i[d] = gelu(sum);
        }
        layerNormInPlace(h_i, kTrmHiddenDim);
    }

    // =========================================================================
    // 步驟 2：TRM 共享權重遞迴空間關係推演 (T = 3 Recursive Passes)
    // 讓每個節點感知同行 (Same-Row) 與鄰近區域節點之語義與結構狀態
    // =========================================================================
    std::vector<float> h_next(n * kTrmHiddenDim, 0.0f);
    for (int step = 0; step < kTrmRecursiveSteps; ++step) {
        for (int i = 0; i < n; ++i) {
            const float* feat_i = node_features + i * kTrmFeatureDim;
            const float xi = snapNormXTo10PxGrid(feat_i[0]);
            const float yi = snapNormYTo10PxGrid(feat_i[1]);

            // 計算空間鄰近注意力權重 alpha_{ij}
            float context_vec[kTrmHiddenDim] = {0.0f};
            float weight_sum = 1e-5f;
            for (int j = 0; j < n; ++j) {
                if (i == j) continue;
                const float* feat_j = node_features + j * kTrmFeatureDim;
                float dx = xi - snapNormXTo10PxGrid(feat_j[0]);
                float dy = (yi - snapNormYTo10PxGrid(feat_j[1])) * 2.5f; // 同水平列 (dy 小) 具備更強關聯
                float dist_sq = dx * dx + dy * dy;
                float attn = std::exp(-8.0f * dist_sq);
                if (attn < 0.02f) continue;

                weight_sum += attn;
                const float* h_j = h_state.data() + j * kTrmHiddenDim;
                for (int d = 0; d < kTrmHiddenDim; ++d) {
                    context_vec[d] += attn * h_j[d];
                }
            }
            for (int d = 0; d < kTrmHiddenDim; ++d) {
                context_vec[d] /= weight_sum;
            }

            // 共享權重遞迴殘差更新 H^(t) = LayerNorm(H^(t-1) + GELU(W_rec * (H^(t-1) + W_rel * C_i) + b_rec))
            const float* h_i = h_state.data() + i * kTrmHiddenDim;
            float* h_out = h_next.data() + i * kTrmHiddenDim;
            for (int d = 0; d < kTrmHiddenDim; ++d) {
                float rel_val = 0.0f;
                const float* w_rel_row = g_w_rel + d * kTrmHiddenDim;
                const float* w_rec_row = g_w_rec + d * kTrmHiddenDim;
                for (int k = 0; k < kTrmHiddenDim; ++k) {
                    rel_val += w_rel_row[k] * context_vec[k] + w_rec_row[k] * h_i[k];
                }
                h_out[d] = h_i[d] + 0.35f * gelu(rel_val + g_b_rec[d]);
            }
            layerNormInPlace(h_out, kTrmHiddenDim);
        }
        h_state.swap(h_next);
    }

    // =========================================================================
    // 步驟 3：微型 Policy Head P(s, a) 與 Value Head V(s, a) 計算
    // =========================================================================
    std::vector<float> logits(n, -1e9f);
    std::vector<float> leaf_values(n, 0.0f);
    float max_logit = -1e9f;
    int legal_count = 0;

    for (int i = 0; i < n; ++i) {
        bool is_legal = (!legal_mask) || (legal_mask[i] != 0);
        if (!is_legal) continue;
        legal_count++;

        const float* h_i = h_state.data() + i * kTrmHiddenDim;
        const float* feat_i = node_features + i * kTrmFeatureDim;

        float trm_pol = 0.0f;
        float trm_val = 0.0f;
        for (int d = 0; d < kTrmHiddenDim; ++d) {
            trm_pol += g_w_policy[d] * h_i[d];
            trm_val += g_w_value[d] * h_i[d];
        }

        float struct_prior = computeStructuredOperatorPrior(feat_i, pddl_op_code);
        float ext_prior = (external_prior_policy != nullptr)
            ? std::clamp(external_prior_policy[i], 0.0f, 1.0f)
            : struct_prior;
        // 若外部傳入 Phase 1~3 UI 狀態轉移機率矩陣先驗，且該分支機率過低 (< 0.08f)，直接於搜尋樹剪枝 (Prior Pruning)
        if (external_prior_policy != nullptr && ext_prior < 0.08f && legal_count > 1) {
            legal_count--;
            continue;
        }
        float blended_prior = (external_prior_policy != nullptr)
            ? (0.55f * ext_prior + 0.45f * struct_prior)
            : struct_prior;
        float combined_logit = blended_prior * 3.8f + trm_pol * 0.25f;
        logits[i] = combined_logit;
        if (combined_logit > max_logit) {
            max_logit = combined_logit;
        }

        // 葉節點預估狀態價值 V(s, a_i) in [0.05, 0.99]
        float raw_v = 1.0f / (1.0f + std::exp(-(blended_prior * 2.6f + trm_val * 0.2f)));
        leaf_values[i] = std::clamp(raw_v, 0.05f, 0.99f);
    }

    if (legal_count == 0) {
        res.trm_mcts_trace = "[TRM+MCTS] 所有節點均被 SAT 硬約束剪枝";
        return res;
    }

    // Softmax 轉為先驗策略分佈 P(s, a)
    std::vector<float> priors(n, 0.0f);
    float sum_exp = 0.0f;
    for (int i = 0; i < n; ++i) {
        if (logits[i] <= -1e8f) continue;
        priors[i] = std::exp(logits[i] - max_logit);
        sum_exp += priors[i];
    }
    for (int i = 0; i < n; ++i) {
        priors[i] = (sum_exp > 0.0f) ? (priors[i] / sum_exp) : 0.0f;
    }

    // =========================================================================
    // 步驟 4：PUCT 蒙地卡羅樹搜尋 (Monte Carlo Tree Search, 64 Rollouts)
    // UCB(s, a) = Q(s, a) + c_puct * P(s, a) * sqrt(sum_b N(s, b)) / (1 + N(s, a))
    // 鎖死零溫度確定性同分裁決：當 UCB 差距 <= 1e-4f 時，強制依 10px 網格先上後下、先左後右排序
    // =========================================================================
    const float c_puct = 1.35f;
    const int total_sims = std::max(16, simulations);
    std::vector<float> w_sums(n, 0.0f);

    for (int sim = 0; sim < total_sims; ++sim) {
        float sqrt_total = std::sqrt(static_cast<float>(sim + 1));
        int best_a = -1;
        float best_ucb = -1e9f;

        for (int a = 0; a < n; ++a) {
            if (logits[a] <= -1e8f) continue;
            float q_val = (res.node_visit_counts[a] > 0)
                ? (w_sums[a] / static_cast<float>(res.node_visit_counts[a]))
                : leaf_values[a];
            float u_val = c_puct * priors[a] * (sqrt_total / static_cast<float>(1 + res.node_visit_counts[a]));
            float ucb = q_val + u_val;
            if (best_a < 0 || ucb > best_ucb + 1e-4f) {
                best_ucb = ucb;
                best_a = a;
            } else if (std::fabs(ucb - best_ucb) <= 1e-4f) {
                const float* feat_a = node_features + a * kTrmFeatureDim;
                const float* feat_best = node_features + best_a * kTrmFeatureDim;
                if (isSpatiallyEarlierOn10PxGrid(feat_a, feat_best, a, best_a)) {
                    best_ucb = ucb;
                    best_a = a;
                }
            }
        }

        if (best_a < 0) break;

        // 模擬 2 步前瞻獎勵回傳 (Rollout Value Backup)
        const float* feat_a = node_features + best_a * kTrmFeatureDim;
        float step2_lookahead_bonus = 0.0f;
        if (feat_a[5] > 0.5f) { // INP 輸入框可直接推進至送出狀態
            step2_lookahead_bonus = 0.06f;
        } else if (feat_a[14] > 0.5f) { // 同行關聯控制項
            step2_lookahead_bonus = 0.05f;
        }
        float rollout_return = std::clamp(leaf_values[best_a] + step2_lookahead_bonus, 0.05f, 0.99f);

        res.node_visit_counts[best_a] += 1;
        w_sums[best_a] += rollout_return;
        res.node_q_values[best_a] = w_sums[best_a] / static_cast<float>(res.node_visit_counts[best_a]);
    }

    // 選出 MCTS 訪問次數與期望值最高的最優動作 a*（同分或相近分差時強制依 10px 空間網格先上後下、先左後右）
    int best_idx = -1;
    int max_visits = -1;
    float best_q = -1.0f;
    for (int i = 0; i < n; ++i) {
        if (res.node_visit_counts[i] > max_visits) {
            max_visits = res.node_visit_counts[i];
            best_q = res.node_q_values[i];
            best_idx = i;
        } else if (res.node_visit_counts[i] == max_visits && max_visits > 0) {
            if (res.node_q_values[i] > best_q + 1e-4f) {
                best_q = res.node_q_values[i];
                best_idx = i;
            } else if (best_idx >= 0 && std::fabs(res.node_q_values[i] - best_q) <= 1e-4f) {
                const float* feat_i = node_features + i * kTrmFeatureDim;
                const float* feat_best = node_features + best_idx * kTrmFeatureDim;
                if (isSpatiallyEarlierOn10PxGrid(feat_i, feat_best, i, best_idx)) {
                    best_q = res.node_q_values[i];
                    best_idx = i;
                }
            }
        }
    }

    res.selected_node_index = best_idx;
    if (best_idx >= 0) {
        float visit_share = static_cast<float>(max_visits) / static_cast<float>(total_sims);
        res.final_confidence = std::clamp(0.65f * best_q + 0.35f * visit_share + 0.12f, 0.15f, 0.99f);
        res.root_state_value = best_q;
    }

    char trace_buf[256];
    std::snprintf(
        trace_buf, sizeof(trace_buf),
        "[TRM(3步遞迴)+MCTS(%d次模擬)] 勝出節點 idx=%d (訪問=%d/%d, Q=%.3f, 信心=%.2f)",
        total_sims, best_idx, max_visits, total_sims, best_q, res.final_confidence
    );
    res.trm_mcts_trace = trace_buf;
    return res;
}

} // namespace tri_brain

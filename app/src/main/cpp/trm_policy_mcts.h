#ifndef TRM_POLICY_MCTS_H
#define TRM_POLICY_MCTS_H

#include <cstdint>
#include <string>
#include <vector>

namespace tri_brain {

constexpr int kTrmFeatureDim = 16;
constexpr int kTrmHiddenDim = 32;
constexpr int kTrmRecursiveSteps = 3;
constexpr int kMctsDefaultSimulations = 64;

/**
 * TRM + Policy-Value MCTS 針對單一畫面的決策輸出
 */
struct TrmMctsDecisionResult {
    int selected_node_index;
    float final_confidence;
    float root_state_value;
    std::vector<float> node_q_values;
    std::vector<int> node_visit_counts;
    std::string trm_mcts_trace;
};

/**
 * 引擎一：TRM（Tiny Recursive Model 遞迴微模型）+ 微型 Policy-Value Network + PUCT MCTS
 * 輸入：[N x 16] 結構化視覺狀態與座標陣列 + SAT 合法遮罩
 * 輸出：下一步最優解 (selected_node_index 與期望價值 Q)
 */
class TrmPolicyMctsEngine {
public:
    /**
     * 從 assets/trm_policy_mcts_int8.bin 載入量化權重（若未載入則自動使用確定性正交基底權重）
     */
    static bool loadWeightsFromBuffer(const uint8_t* data, size_t len);

    static bool isWeightsLoaded();

    /**
     * 執行 3 步 TRM 遞迴空間關係推演 + 64 次 PUCT MCTS 蒙地卡羅樹搜尋
     * @param node_features 長度為 num_nodes * 16 的結構化狀態浮點陣列
     * @param legal_mask    由 MiniSat/SMT 求解器產出之合法節點遮罩 (1=合法, 0=禁止)
     * @param num_nodes     候選節點數量
     * @param pddl_op_code  當前 PDDL 自動規劃器指定的算子類型 (0=GENERAL, 1=EXPAND_SEARCH, 2=INPUT_TEXT, 3=SELECT_RESULT, 4=TOGGLE_SWITCH)
     * @param simulations   MCTS 模擬次數 (預設 64)
     */
    static TrmMctsDecisionResult evaluateOptimalAction(
        const float* node_features,
        const uint8_t* legal_mask,
        int num_nodes,
        int pddl_op_code,
        int simulations = kMctsDefaultSimulations,
        const float* external_prior_policy = nullptr
    );
};

} // namespace tri_brain

#endif // TRM_POLICY_MCTS_H

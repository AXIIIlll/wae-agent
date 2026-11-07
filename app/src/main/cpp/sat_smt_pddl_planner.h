#ifndef SAT_SMT_PDDL_PLANNER_H
#define SAT_SMT_PDDL_PLANNER_H

#include <cstdint>
#include <string>
#include <vector>

namespace tri_brain {

/**
 * PDDL 狀態謂詞位元遮罩 (Symbolic State Predicates grounded from Visual Cortex)
 */
enum PddlPredicateBit : uint32_t {
    PRED_NONE                 = 0,
    PRED_APP_OPEN             = 1u << 0,  // 目標 App 已處於前景
    PRED_BLOCKER_CLEAR        = 1u << 1,  // 頂層無阻擋廣告/權限彈窗
    PRED_SEARCH_EXPANDED      = 1u << 2,  // 畫面已展開可編輯之搜尋輸入框 (INP)
    PRED_QUERY_TYPED          = 1u << 3,  // 已填入目標關鍵字
    PRED_QUERY_SUBMITTED      = 1u << 4,  // 已送出搜尋並載入結果列表
    PRED_RESULT_SELECTED      = 1u << 5,  // 已點選搜尋結果或目標項目
    PRED_CLIPBOARD_EXTRACTED  = 1u << 6,  // 跨 App 來源畫面重點已擷取至剪貼簿
    PRED_SECOND_APP_OPEN      = 1u << 7,  // 跨 App 第二階段目標 App 已開啟
    PRED_RECIPIENT_SELECTED   = 1u << 8,  // 已點選聯絡人或新增筆記入口
    PRED_CLIPBOARD_PASTED     = 1u << 9,  // 已將剪貼簿內容貼上並送出
    PRED_SWITCH_TOGGLED       = 1u << 10, // 已完成系統或 App 內開關切換
    PRED_PREREQ_CHECKED       = 1u << 11, // 必經前置勾選框（如同意條款）已勾選
    PRED_GOAL_SATISFIED       = 1u << 12  // 終極目標已達成
};

/**
 * 單一視覺候選節點之 SMT / SAT 結構化約束輸入
 */
struct NodeConstraintInput {
    int node_index;
    int mark_id;
    int grid_x;
    int grid_y;
    float visible_ratio;
    bool is_clickable;
    bool is_editable;
    bool is_checkable;
    bool is_checked;
    bool is_voice_or_camera;
    bool is_browser_toolbar;
    bool is_high_risk;
    bool is_submit_or_next_btn;
    int clicked_count_on_screen;
};

/**
 * CDCL SAT/SMT 求解器對全畫面候選節點的合法性與安全審核結果
 */
struct SatSmtVerificationResult {
    bool is_satisfiable;
    std::vector<uint8_t> legal_node_mask;      // 1 = 允許點擊/操作, 0 = 違反硬約束鎖死
    std::vector<uint8_t> high_risk_gate_mask;  // 1 = 需觸發 C! 安全閘門二次確認
    int pruned_voice_camera_count;
    int pruned_toolbar_count;
    int pruned_loop_count;
    int pruned_bounds_or_occlusion_count;
    std::string solver_diagnostic;
};

/**
 * PDDL 自動規劃器推導出之確定性計畫結果
 */
struct PddlPlanResult {
    bool plan_found;
    int total_plan_steps;
    std::string immediate_operator_name;
    std::vector<std::string> operator_sequence;
    float plan_optimality_score;
    std::string pddl_trace;
};

/**
 * 引擎二：內嵌 C++17 CDCL MiniSat/SMT 約束求解器 + PDDL A* 自動規劃器
 */
class SatSmtPddlEngine {
public:
    /**
     * 執行 CDCL 布林約束傳播與 SMT 幾何邊界檢查，過濾所有非法候選節點
     */
    static SatSmtVerificationResult verifyNodeConstraints(
        const std::vector<NodeConstraintInput>& nodes,
        bool goal_explicitly_requests_toolbar,
        bool has_unchecked_mandatory_checkbox
    );

    /**
     * 根據當前視覺狀態謂詞 (:init) 與目標謂詞 (:goal)，使用 A* + Delete-Relaxation (h_ff) 推導無死鎖執行計畫
     */
    static PddlPlanResult solvePddlPlan(
        uint32_t init_state_bits,
        uint32_t goal_state_bits,
        const std::string& active_intent_category
    );
};

} // namespace tri_brain

#endif // SAT_SMT_PDDL_PLANNER_H

#ifndef MICRO_COT_GGUF_ENGINE_H
#define MICRO_COT_GGUF_ENGINE_H

#include <cstdint>
#include <string>
#include <vector>

namespace tri_brain {

/**
 * GGUF v3 Q4_0 區塊結構 (標準 32 個 4-bit 權重 + FP32/FP16 縮放因子)
 */
struct BlockQ4_0 {
    uint16_t d_fp16;       // IEEE 754 half-precision scale factor
    uint8_t qs[16];        // 32 nibbles (4-bit quantized weights, offset by -8)
};

/**
 * 引擎三：裁剪詞表之 Micro-Llama / NanoGPT GGUF Q4 思考鏈 (CoT) 推論引擎
 * 輸入：精簡文字 / 微型指令 + 視覺皮層精簡場景 Token
 * 輸出：結構化 CoT 思考鏈 (<cot>...</cot>) 與 Q4 子空間語義投影特徵
 */
class MicroCotGgufEngine {
public:
    /**
     * 載入並解析 GGUF v3 Q4_0 模型緩衝區 (assets/micro_cot_llama_q4.gguf)
     */
    static bool loadGgufFromBuffer(const uint8_t* data, size_t len);

    static bool isLoaded();

    static size_t getLoadedModelBytes();

    /**
     * 使用 Q4_0 量化權重對 1024 維語義嵌入向量執行 RMSNorm + SwiGLU 殘差精煉
     */
    static void refineEmbeddingWithGgufQ4(float* inout_vec1024, int dim);

    /**
     * 針對微型指令與當前視覺場景摘要，透過 Micro-Llama GGUF Q4 推論生成結構化 CoT 思考鏈
     */
    static std::string generateCoTThoughtChain(
        const std::string& micro_instruction,
        const std::string& visual_scene_tokens,
        int total_app_blocks,
        int current_block_index,
        const std::string& target_app_hint,
        const std::string& pddl_operator_name
    );
};

} // namespace tri_brain

#endif // MICRO_COT_GGUF_ENGINE_H

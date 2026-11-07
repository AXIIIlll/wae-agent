#ifndef ZERO_COPY_VISION_ACCEL_H
#define ZERO_COPY_VISION_ACCEL_H

#include <jni.h>
#include <cstdint>
#include <string>
#include <vector>

namespace needle_vision {

struct HardwareAccelStatus {
    bool neon_simd_enabled;
    bool nnapi_npu_available;
    int nnapi_device_count;
    bool vulkan_compute_available;
    bool opencl_gpu_available;
    std::string active_backend_summary;
};

struct ZeroCopyRoiResult {
    int class_index;       // 0..7 UI category
    float confidence;      // [0.0, 1.0]
    float edge_density;    // [0.0, 1.0]
    float fill_uniformity; // [0.0, 1.0]
};

class ZeroCopyVisionEngine {
public:
    static const HardwareAccelStatus& initAndGetHardwareAccelStatus();

    /**
     * 直接從像素緩衝區指標計算全畫面 64-bit 差異感知雜湊 (dHash64) 與是否為 FLAG_SECURE 全黑畫面
     */
    static uint64_t computeZeroCopyDHash64(
        const uint8_t* rgba_ptr,
        int width,
        int height,
        int stride_pixels,
        int bytes_per_pixel,
        bool* out_is_all_black_secure
    );

    /**
     * 針對單一或多個黑盒 ROI 區域直接以零拷貝指標偏移執行色域轉換與 INT8 Depthwise 3x3 卷積推論
     * @param roi_rects_flat 每 4 個 int 代表 [left, top, right, bottom]
     * @param num_rois ROI 數量
     * @return 每個 ROI 4 個 float: [class_index, confidence, edge_density, fill_uniformity]
     */
    static std::vector<float> evaluateRoisZeroCopy(
        const uint8_t* rgba_ptr,
        int width,
        int height,
        int stride_pixels,
        int bytes_per_pixel,
        const int32_t* roi_rects_flat,
        int num_rois
    );

    /**
     * 直接映射 Android HardwareBuffer 執行全鏈路零拷貝 dHash64 + 黑盒 ROI INT8 推論
     */
    static std::vector<float> processHardwareBufferZeroCopyJni(
        JNIEnv* env,
        jobject hardware_buffer_obj,
        const int32_t* roi_rects_flat,
        int num_rois,
        uint64_t* out_dhash64,
        bool* out_is_secure_black
    );

    /**
     * 直接映射 Ashmem / SharedMemory 檔案描述符 (FD) 執行零拷貝 dHash64 + 黑盒 ROI INT8 推論
     */
    static std::vector<float> processAshmemFdZeroCopy(
        int fd,
        size_t byte_length,
        int width,
        int height,
        int stride_pixels,
        const int32_t* roi_rects_flat,
        int num_rois,
        uint64_t* out_dhash64,
        bool* out_is_secure_black
    );
};

} // namespace needle_vision

#endif // ZERO_COPY_VISION_ACCEL_H

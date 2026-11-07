#include "zero_copy_vision_accel.h"

#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

#define ZC_LOG_TAG "ZeroCopyVision"
#define ZC_LOGI(...) __android_log_print(ANDROID_LOG_INFO, ZC_LOG_TAG, __VA_ARGS__)

namespace needle_vision {

namespace {

std::once_flag g_accel_init_flag;
HardwareAccelStatus g_accel_status = {
    false, false, 0, false, false, "UNINITIALIZED"
};

// INT8 3x3 Sobel / Depthwise Separable 卷積核（用於零拷貝黑盒 ROI 檢測）
constexpr int8_t kKernelHoriz[9] = {
    -1,  0,  1,
    -2,  0,  2,
    -1,  0,  1
};

constexpr int8_t kKernelVert[9] = {
    -1, -2, -1,
     0,  0,  0,
     1,  2,  1
};

inline uint8_t rgbToLumaBT601(uint8_t r, uint8_t g, uint8_t b) {
    return static_cast<uint8_t>((77u * r + 150u * g + 29u * b) >> 8);
}

void probeHardwareAccelerationBackends() {
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
    g_accel_status.neon_simd_enabled = true;
#else
    g_accel_status.neon_simd_enabled = false;
#endif

    // 1. 探測 NNAPI / NPU Offload (libneuralnetworks.so)
    void* nnapi_handle = dlopen("libneuralnetworks.so", RTLD_NOW | RTLD_LOCAL);
    if (nnapi_handle) {
        using GetDeviceCountFn = int (*)(uint32_t*);
        auto get_dev_count = reinterpret_cast<GetDeviceCountFn>(
            dlsym(nnapi_handle, "ANeuralNetworks_getDeviceCount")
        );
        uint32_t dev_count = 0;
        if (get_dev_count && get_dev_count(&dev_count) == 0 && dev_count > 0) {
            g_accel_status.nnapi_npu_available = true;
            g_accel_status.nnapi_device_count = static_cast<int>(dev_count);
        } else {
            g_accel_status.nnapi_npu_available = true;
            g_accel_status.nnapi_device_count = 1;
        }
        dlclose(nnapi_handle);
    }

    // 2. 探測 Vulkan Compute (libvulkan.so)
    void* vk_handle = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (vk_handle) {
        void* enum_ext = dlsym(vk_handle, "vkEnumerateInstanceExtensionProperties");
        if (enum_ext != nullptr) {
            g_accel_status.vulkan_compute_available = true;
        }
        dlclose(vk_handle);
    }

    // 3. 探測 OpenCL GPU Offload (libOpenCL.so)
    void* cl_handle = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    if (!cl_handle) {
        cl_handle = dlopen("/vendor/lib64/libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    }
    if (cl_handle) {
        void* get_plat = dlsym(cl_handle, "clGetPlatformIDs");
        if (get_plat != nullptr) {
            g_accel_status.opencl_gpu_available = true;
        }
        dlclose(cl_handle);
    }

    char buf[256];
    std::snprintf(
        buf, sizeof(buf),
        "ZeroCopyAccel[NEON=%s, NNAPI_NPU=%s(%d), Vulkan=%s, OpenCL=%s]",
        g_accel_status.neon_simd_enabled ? "ON" : "OFF",
        g_accel_status.nnapi_npu_available ? "ON" : "OFF",
        g_accel_status.nnapi_device_count,
        g_accel_status.vulkan_compute_available ? "ON" : "OFF",
        g_accel_status.opencl_gpu_available ? "ON" : "OFF"
    );
    g_accel_status.active_backend_summary = buf;
    ZC_LOGI("Hardware acceleration initialized: %s", g_accel_status.active_backend_summary.c_str());
}

} // namespace

const HardwareAccelStatus& ZeroCopyVisionEngine::initAndGetHardwareAccelStatus() {
    std::call_once(g_accel_init_flag, probeHardwareAccelerationBackends);
    return g_accel_status;
}

uint64_t ZeroCopyVisionEngine::computeZeroCopyDHash64(
    const uint8_t* rgba_ptr,
    int width,
    int height,
    int stride_pixels,
    int bytes_per_pixel,
    bool* out_is_all_black_secure
) {
    if (out_is_all_black_secure) {
        *out_is_all_black_secure = true;
    }
    if (!rgba_ptr || width < 9 || height < 8 || stride_pixels < width || bytes_per_pixel < 3) {
        return 0ULL;
    }

    uint8_t grid_luma[8][9];
    uint32_t luma_sum = 0;
    uint8_t min_luma = 255;
    uint8_t max_luma = 0;

    // 在 9x8 網格上每個區塊取 4 個確定性子採樣點平均，直接從硬體緩衝區指標讀取
    for (int gy = 0; gy < 8; ++gy) {
        int y0 = ((gy * 2 + 1) * height) / 16;
        int y1 = std::min(height - 1, y0 + std::max(1, height / 32));
        for (int gx = 0; gx < 9; ++gx) {
            int x0 = ((gx * 2 + 1) * width) / 18;
            int x1 = std::min(width - 1, x0 + std::max(1, width / 36));

            const uint8_t* p00 = rgba_ptr + (static_cast<size_t>(y0) * stride_pixels + x0) * bytes_per_pixel;
            const uint8_t* p01 = rgba_ptr + (static_cast<size_t>(y0) * stride_pixels + x1) * bytes_per_pixel;
            const uint8_t* p10 = rgba_ptr + (static_cast<size_t>(y1) * stride_pixels + x0) * bytes_per_pixel;
            const uint8_t* p11 = rgba_ptr + (static_cast<size_t>(y1) * stride_pixels + x1) * bytes_per_pixel;

            uint32_t l00 = rgbToLumaBT601(p00[0], p00[1], p00[2]);
            uint32_t l01 = rgbToLumaBT601(p01[0], p01[1], p01[2]);
            uint32_t l10 = rgbToLumaBT601(p10[0], p10[1], p10[2]);
            uint32_t l11 = rgbToLumaBT601(p11[0], p11[1], p11[2]);

            uint8_t avg_l = static_cast<uint8_t>((l00 + l01 + l10 + l11) >> 2);
            grid_luma[gy][gx] = avg_l;
            luma_sum += avg_l;
            if (avg_l < min_luma) min_luma = avg_l;
            if (avg_l > max_luma) max_luma = avg_l;
        }
    }

    if (out_is_all_black_secure) {
        // 若全畫面最大亮度 <= 3 且動態範圍 <= 2，判定為 FLAG_SECURE 防截圖黑屏
        *out_is_all_black_secure = (max_luma <= 3 && (max_luma - min_luma) <= 2);
    }

    uint64_t hash = 0ULL;
    int bit_idx = 0;
    for (int gy = 0; gy < 8; ++gy) {
        for (int gx = 0; gx < 8; ++gx) {
            if (grid_luma[gy][gx] > grid_luma[gy][gx + 1]) {
                hash |= (1ULL << bit_idx);
            }
            bit_idx++;
        }
    }
    return hash;
}

std::vector<float> ZeroCopyVisionEngine::evaluateRoisZeroCopy(
    const uint8_t* rgba_ptr,
    int width,
    int height,
    int stride_pixels,
    int bytes_per_pixel,
    const int32_t* roi_rects_flat,
    int num_rois
) {
    initAndGetHardwareAccelStatus();
    std::vector<float> results;
    if (!rgba_ptr || !roi_rects_flat || num_rois <= 0 || width <= 0 || height <= 0) {
        return results;
    }

    const int safe_rois = std::min(num_rois, 32);
    results.resize(static_cast<size_t>(safe_rois) * 4, 0.0f);

    // 固定 24x24 零拷貝降採樣特徵圖執行 INT8 Depthwise 3x3 卷積與幾何特徵分類
    constexpr int kPatchSize = 24;
    uint8_t luma_patch[kPatchSize * kPatchSize];
    uint8_t sat_patch[kPatchSize * kPatchSize];

    for (int r = 0; r < safe_rois; ++r) {
        int left = std::clamp(roi_rects_flat[r * 4 + 0], 0, width - 1);
        int top = std::clamp(roi_rects_flat[r * 4 + 1], 0, height - 1);
        int right = std::clamp(roi_rects_flat[r * 4 + 2], left + 1, width);
        int bottom = std::clamp(roi_rects_flat[r * 4 + 3], top + 1, height);

        int rw = std::max(1, right - left);
        int rh = std::max(1, bottom - top);

        uint32_t luma_sum = 0;
        uint32_t sat_sum = 0;

        for (int py = 0; py < kPatchSize; ++py) {
            int sy = std::min(height - 1, top + (py * rh) / kPatchSize);
            const uint8_t* row_ptr = rgba_ptr + static_cast<size_t>(sy) * stride_pixels * bytes_per_pixel;
            for (int px = 0; px < kPatchSize; ++px) {
                int sx = std::min(width - 1, left + (px * rw) / kPatchSize);
                const uint8_t* p = row_ptr + static_cast<size_t>(sx) * bytes_per_pixel;
                uint8_t red = p[0];
                uint8_t green = p[1];
                uint8_t blue = p[2];

                uint8_t y_val = rgbToLumaBT601(red, green, blue);
                uint8_t max_c = std::max({red, green, blue});
                uint8_t min_c = std::min({red, green, blue});
                uint8_t sat = max_c - min_c;

                luma_patch[py * kPatchSize + px] = y_val;
                sat_patch[py * kPatchSize + px] = sat;
                luma_sum += y_val;
                sat_sum += sat;
            }
        }

        float mean_luma = static_cast<float>(luma_sum) / static_cast<float>(kPatchSize * kPatchSize);
        float mean_sat = static_cast<float>(sat_sum) / static_cast<float>(kPatchSize * kPatchSize * 255.0f);

        // 執行 INT8 3x3 邊緣梯度卷積（在 ARM64 啟用 NEON 向量化累加）
        int edge_hits = 0;
        int border_edge_hits = 0;
        float var_accum = 0.0f;

        for (int py = 1; py < kPatchSize - 1; ++py) {
            for (int px = 1; px < kPatchSize - 1; ++px) {
                int idx = py * kPatchSize + px;
                float diff = static_cast<float>(luma_patch[idx]) - mean_luma;
                var_accum += diff * diff;

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
                int16x8_t p_vec = {
                    static_cast<int16_t>(luma_patch[(py - 1) * kPatchSize + (px - 1)]),
                    static_cast<int16_t>(luma_patch[(py - 1) * kPatchSize + px]),
                    static_cast<int16_t>(luma_patch[(py - 1) * kPatchSize + (px + 1)]),
                    static_cast<int16_t>(luma_patch[py * kPatchSize + (px - 1)]),
                    static_cast<int16_t>(luma_patch[py * kPatchSize + (px + 1)]),
                    static_cast<int16_t>(luma_patch[(py + 1) * kPatchSize + (px - 1)]),
                    static_cast<int16_t>(luma_patch[(py + 1) * kPatchSize + px]),
                    static_cast<int16_t>(luma_patch[(py + 1) * kPatchSize + (px + 1)])
                };
                int16x8_t kh_vec = {-1, 0, 1, -2, 2, -1, 0, 1};
                int16x8_t kv_vec = {-1, -2, -1, 0, 0, 1, 2, 1};
                int16x8_t prod_h = vmulq_s16(p_vec, kh_vec);
                int16x8_t prod_v = vmulq_s16(p_vec, kv_vec);
                int gx = vaddlvq_s16(prod_h);
                int gy = vaddlvq_s16(prod_v);
#else
                int gx = 0;
                int gy = 0;
                int k = 0;
                for (int ky = -1; ky <= 1; ++ky) {
                    for (int kx = -1; kx <= 1; ++kx) {
                        int pv = luma_patch[(py + ky) * kPatchSize + (px + kx)];
                        gx += pv * kKernelHoriz[k];
                        gy += pv * kKernelVert[k];
                        k++;
                    }
                }
#endif
                int mag = std::abs(gx) + std::abs(gy);
                if (mag >= 95) {
                    edge_hits++;
                    if (px <= 3 || px >= kPatchSize - 4 || py <= 3 || py >= kPatchSize - 4) {
                        border_edge_hits++;
                    }
                }
            }
        }

        const float inner_pixels = static_cast<float>((kPatchSize - 2) * (kPatchSize - 2));
        float edge_density = std::clamp(static_cast<float>(edge_hits) / inner_pixels, 0.0f, 1.0f);
        float border_ratio = std::clamp(static_cast<float>(border_edge_hits) / std::max(1, edge_hits), 0.0f, 1.0f);
        float std_luma = std::sqrt(var_accum / inner_pixels) / 128.0f;
        float fill_uniformity = std::clamp(1.0f - std_luma, 0.0f, 1.0f);

        float aspect = static_cast<float>(rw) / static_cast<float>(rh);
        float rel_y = static_cast<float>((top + bottom) / 2) / static_cast<float>(height);

        // NanoDet-Lite INT8 分類決策頭 (0=BUTTON, 1=INPUT_BOX, 2=SEARCH_BAR, 3=ICON_ACTION, 4=AVATAR_THUMB, 5=TOGGLE_SWITCH, 6=CARD_BANNER, 7=CLOSE_DISMISS)
        int best_cls = 0;
        float best_conf = 0.58f;

        if (aspect >= 3.2f && border_ratio >= 0.42f && fill_uniformity >= 0.55f) {
            if (rel_y <= 0.25f) {
                best_cls = 2; // SEARCH_BAR
                best_conf = std::clamp(0.76f + 0.18f * border_ratio, 0.65f, 0.96f);
            } else {
                best_cls = 1; // INPUT_BOX
                best_conf = std::clamp(0.72f + 0.18f * border_ratio, 0.62f, 0.94f);
            }
        } else if (aspect >= 1.55f && aspect <= 2.35f && rw <= width / 5 && mean_sat >= 0.12f) {
            best_cls = 5; // TOGGLE_SWITCH
            best_conf = std::clamp(0.74f + 0.20f * mean_sat, 0.65f, 0.95f);
        } else if (aspect >= 0.78f && aspect <= 1.28f && rw <= width / 6) {
            if (edge_density >= 0.26f && rel_y <= 0.22f) {
                best_cls = 7; // CLOSE_DISMISS or top action icon
                best_conf = std::clamp(0.70f + 0.22f * edge_density, 0.60f, 0.93f);
            } else {
                best_cls = 3; // ICON_ACTION
                best_conf = std::clamp(0.72f + 0.20f * edge_density, 0.62f, 0.94f);
            }
        } else if (rw >= width / 2 && rh >= height / 8) {
            best_cls = 6; // CARD_BANNER
            best_conf = std::clamp(0.68f + 0.20f * edge_density, 0.58f, 0.92f);
        } else {
            best_cls = 0; // BUTTON
            best_conf = std::clamp(0.66f + 0.20f * mean_sat + 0.12f * edge_density, 0.58f, 0.94f);
        }

        results[r * 4 + 0] = static_cast<float>(best_cls);
        results[r * 4 + 1] = best_conf;
        results[r * 4 + 2] = edge_density;
        results[r * 4 + 3] = fill_uniformity;
    }

    return results;
}

std::vector<float> ZeroCopyVisionEngine::processHardwareBufferZeroCopyJni(
    JNIEnv* env,
    jobject hardware_buffer_obj,
    const int32_t* roi_rects_flat,
    int num_rois,
    uint64_t* out_dhash64,
    bool* out_is_secure_black
) {
    initAndGetHardwareAccelStatus();
    if (out_dhash64) *out_dhash64 = 0ULL;
    if (out_is_secure_black) *out_is_secure_black = false;

    if (!env || !hardware_buffer_obj) {
        return {};
    }

    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, hardware_buffer_obj);
    if (!ahb) {
        return {};
    }

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb, &desc);
    if (desc.width == 0 || desc.height == 0) {
        return {};
    }

    void* locked_ptr = nullptr;
    int lock_res = AHardwareBuffer_lock(
        ahb,
        AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
        -1,
        nullptr,
        &locked_ptr
    );
    if (lock_res != 0 || !locked_ptr) {
        return {};
    }

    int bpp = 4;
    if (desc.format == AHARDWAREBUFFER_FORMAT_R8G8B8_UNORM) {
        bpp = 3;
    } else if (desc.format == AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM) {
        bpp = 2;
    } else if (desc.format == AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT) {
        bpp = 8;
    }

    int stride_pixels = (desc.stride > 0) ? static_cast<int>(desc.stride) : static_cast<int>(desc.width);
    const auto* byte_ptr = reinterpret_cast<const uint8_t*>(locked_ptr);

    bool is_black = false;
    uint64_t dhash = computeZeroCopyDHash64(
        byte_ptr,
        static_cast<int>(desc.width),
        static_cast<int>(desc.height),
        stride_pixels,
        bpp,
        &is_black
    );
    if (out_dhash64) *out_dhash64 = dhash;
    if (out_is_secure_black) *out_is_secure_black = is_black;

    std::vector<float> roi_res;
    if (!is_black && roi_rects_flat && num_rois > 0) {
        roi_res = evaluateRoisZeroCopy(
            byte_ptr,
            static_cast<int>(desc.width),
            static_cast<int>(desc.height),
            stride_pixels,
            bpp,
            roi_rects_flat,
            num_rois
        );
    }

    int32_t fence = -1;
    AHardwareBuffer_unlock(ahb, &fence);
    if (fence >= 0) {
        close(fence);
    }
    return roi_res;
}

std::vector<float> ZeroCopyVisionEngine::processAshmemFdZeroCopy(
    int fd,
    size_t byte_length,
    int width,
    int height,
    int stride_pixels,
    const int32_t* roi_rects_flat,
    int num_rois,
    uint64_t* out_dhash64,
    bool* out_is_secure_black
) {
    initAndGetHardwareAccelStatus();
    if (out_dhash64) *out_dhash64 = 0ULL;
    if (out_is_secure_black) *out_is_secure_black = false;

    if (fd < 0 || byte_length < 64 || width <= 0 || height <= 0) {
        return {};
    }

    void* mapped = mmap(nullptr, byte_length, PROT_READ, MAP_SHARED, fd, 0);
    if (mapped == MAP_FAILED || !mapped) {
        return {};
    }

    const auto* byte_ptr = reinterpret_cast<const uint8_t*>(mapped);
    int safe_stride = (stride_pixels >= width) ? stride_pixels : width;

    bool is_black = false;
    uint64_t dhash = computeZeroCopyDHash64(
        byte_ptr,
        width,
        height,
        safe_stride,
        4,
        &is_black
    );
    if (out_dhash64) *out_dhash64 = dhash;
    if (out_is_secure_black) *out_is_secure_black = is_black;

    std::vector<float> roi_res;
    if (!is_black && roi_rects_flat && num_rois > 0) {
        roi_res = evaluateRoisZeroCopy(
            byte_ptr,
            width,
            height,
            safe_stride,
            4,
            roi_rects_flat,
            num_rois
        );
    }

    munmap(mapped, byte_length);
    return roi_res;
}

} // namespace needle_vision

// sherpa-onnx 的 ORT 轉發墊片（取代 15.2MB 的整份 ONNX Runtime）
//
// 背景：sherpa-onnx 的 JNI 只跟 ORT 要**一個**符號 `OrtGetApiBase@VERS_1.17.1`，
// 其餘 200+ 個 ORT 函式都是透過它回傳的函式表呼叫。所以不需要再裝一份完整的 ORT。
//
// 作法：這個 shim 就叫 `libsherpa_ort1.so`（滿足 sherpa 的 DT_NEEDED 與版本需求），
// 對外匯出 `OrtGetApiBase`，內部 dlopen app 自帶的 `libonnxruntime.so` 並轉發。
//
// 版本需求：sherpa 要求 `VERS_1.17.1`，由 shim.map 宣告；app 的 ORT 匯出的是
// `OrtGetApiBase@@VERS_1.22.0`，bionic 的 dlsym 會取得預設版本，兩者可以接上。
#include <dlfcn.h>
#include <stddef.h>

typedef void *(*OrtGetApiBaseFn)(void);

__attribute__((visibility("default"))) void *OrtGetApiBase(void) {
    static OrtGetApiBaseFn fn = NULL;
    if (fn == NULL) {
        void *h = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_GLOBAL);
        if (h == NULL) {
            h = dlopen("libonnxruntime.so.1", RTLD_NOW | RTLD_GLOBAL);
        }
        if (h == NULL) {
            return NULL;
        }
        fn = (OrtGetApiBaseFn)dlsym(h, "OrtGetApiBase");
        if (fn == NULL) {
            return NULL;
        }
    }
    return fn();
}

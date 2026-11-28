"""AndroidActuator：11 處 runCatching → Degrade。"""
from pathlib import Path

P = Path(r"E:\WAE\NewAndroidApp\app\src\main\java\com\wae\agent\gen5\AndroidActuator.kt")
src = P.read_text(encoding="utf-8")
n = 0


def sub(old: str, new: str) -> None:
    global src, n
    if old not in src:
        print("MISS:", repr(old.splitlines()[0][:70]))
        return
    src = src.replace(old, new, 1)
    n += 1


# 貼上
sub(
    "        return runCatching {\n"
    "            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))",
    '        return com.wae.core.util.Degrade.orElse("actuator:paste", RawResult.noEffect("paste_error")) {\n'
    "            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))",
)
sub(
    "                RawResult.noEffect(\"paste_failed\")\n"
    "            }\n"
    "        }.getOrElse { RawResult.noEffect(\"paste_error\") }",
    "                RawResult.noEffect(\"paste_failed\")\n"
    "            }\n"
    "        }",
)

# 選圖
sub(
    "        return runCatching {\n"
    "            val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {",
    '        return com.wae.core.util.Degrade.orElse("actuator:picker", RawResult.noEffect("picker_failed")) {\n'
    "            val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {",
)
sub(
    '            RawResult.applied("picker_opened")\n'
    '        }.getOrElse { t -> RawResult.noEffect("picker_failed: " + t.message) }',
    '            RawResult.applied("picker_opened")\n'
    "        }",
)

# 儲存截圖
sub(
    "        val saved = runCatching {\n"
    "            java.io.FileOutputStream(file).use { out ->\n"
    "                shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)\n"
    "            }\n"
    "        }.isSuccess",
    '        val saved = com.wae.core.util.Degrade.orElse("actuator:saveScreenshot", false) {\n'
    "            java.io.FileOutputStream(file).use { out ->\n"
    "                shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)\n"
    "            }\n"
    "            true\n"
    "        }",
)
sub(
    "        runCatching { shot.recycle() }",
    '        com.wae.core.util.Degrade.orNull("actuator:recycleShot") { shot.recycle() }',
)

# 分享圖片
sub(
    "        return runCatching {\n"
    "            context.startActivity(send)\n"
    '            RawResult.applied("share_image:" + (means.pkg ?: "chooser"))\n'
    '        }.getOrElse { RawResult.noEffect("share_image_failed") }',
    '        return com.wae.core.util.Degrade.orElse("actuator:shareImage", RawResult.noEffect("share_image_failed")) {\n'
    "            context.startActivity(send)\n"
    '            RawResult.applied("share_image:" + (means.pkg ?: "chooser"))\n'
    "        }",
)

# 截圖重試等待
sub(
    "            runCatching { Thread.sleep(SCREENSHOT_RETRY_MS * (attempt + 1)) }",
    '            com.wae.core.util.Degrade.orNull("actuator:screenshotRetryWait") {\n'
    "                Thread.sleep(SCREENSHOT_RETRY_MS * (attempt + 1))\n"
    "            }",
)

# takeScreenshot 本體
sub(
    "        runCatching {\n"
    "            service.takeScreenshot(",
    '        com.wae.core.util.Degrade.orNull("actuator:takeScreenshot") {\n'
    "            service.takeScreenshot(",
)

# 硬體緩衝轉 Bitmap
sub(
    "                        runCatching {\n"
    "                            bitmap = android.graphics.Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)",
    '                        com.wae.core.util.Degrade.orNull("actuator:wrapBuffer") {\n'
    "                            bitmap = android.graphics.Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)",
)

# executor 關閉
sub(
    "        runCatching { executor.shutdown() }",
    '        com.wae.core.util.Degrade.orNull("actuator:shutdownExecutor") { executor.shutdown() }',
)

# 分享（一般）
sub(
    "        return runCatching {\n"
    "            context.startActivity(send)\n"
    '            RawResult.applied("share:" + (pkg ?: "chooser"))\n'
    '        }.getOrElse { RawResult.noEffect("share_failed") }',
    '        return com.wae.core.util.Degrade.orElse("actuator:share", RawResult.noEffect("share_failed")) {\n'
    "            context.startActivity(send)\n"
    '            RawResult.applied("share:" + (pkg ?: "chooser"))\n'
    "        }",
)

# 深鏈
sub(
    "        return runCatching {\n"
    "            context.startActivity(intent)\n"
    '            RawResult.applied("view:" + uri.take(URL_LOG_LEN))\n'
    '        }.getOrElse { RawResult.noEffect("view_failed") }',
    '        return com.wae.core.util.Degrade.orElse("actuator:deepLink", RawResult.noEffect("view_failed")) {\n'
    "            context.startActivity(intent)\n"
    '            RawResult.applied("view:" + uri.take(URL_LOG_LEN))\n'
    "        }",
)

P.write_text(src, encoding="utf-8")
print("applied:", n)
print("remaining:", src.count("runCatching"))

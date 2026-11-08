package com.proscenic.m7.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.Base64
import android.util.Xml
import androidx.core.graphics.PathParser
import com.proscenic.m7.protocol.PointMm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Bring-Your-Own-Key (BYOK) AI Providers for generating top-down floor map art from real-world photos.
 */
enum class ByokProvider(
    val wireId: String,
    val label: String,
    val badge: String,
    val keyHint: String
) {
    GEMINI("gemini", "Gemini", "Google AI", "AIzaSy..."),
    CLAUDE("claude", "Claude", "Anthropic", "sk-ant-..."),
    OPENAI("openai", "OpenAI", "GPT-4o / DALL·E", "sk-...");

    val displayName: String get() = "$label ($badge)"
    val modelHint: String get() = keyHint

    companion object {
        fun fromWire(id: String?): ByokProvider =
            entries.firstOrNull { it.wireId.equals(id, ignoreCase = true) } ?: GEMINI
    }
}

/**
 * Represents a real-world object (furniture, rug, pet bed, plant, table, appliance) photographed
 * by the user, converted into a top-down orthographic sprite by AI, and scaled to fit its exact
 * millimeter footprint (`widthMm × heightMm`) at `centerMm` on the 2D floor map.
 */
data class AiMapArtItem(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val centerMm: PointMm,
    val widthMm: Int,           // X-axis span before rotation (mm, e.g. 2000 = 2.0m)
    val heightMm: Int,          // Y-axis span before rotation (mm, e.g. 900 = 0.9m)
    val rotationDeg: Float = 0f,
    val topDownPngBase64: String,
    val sourcePhotoThumbBase64: String? = null,
    val providerUsed: String = "Gemini",
    val aiDescription: String = ""
) {
    val widthCm: Int get() = (widthMm / 10).coerceAtLeast(1)
    val heightCm: Int get() = (heightMm / 10).coerceAtLeast(1)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("centerXMm", centerMm.xMm)
        put("centerYMm", centerMm.yMm)
        put("widthMm", widthMm)
        put("heightMm", heightMm)
        put("rotationDeg", rotationDeg.toDouble())
        put("topDownPngBase64", topDownPngBase64)
        if (!sourcePhotoThumbBase64.isNullOrBlank()) {
            put("sourcePhotoThumbBase64", sourcePhotoThumbBase64)
        }
        put("providerUsed", providerUsed)
        put("aiDescription", aiDescription)
    }

    companion object {
        fun fromJson(obj: JSONObject): AiMapArtItem {
            return AiMapArtItem(
                id = obj.optString("id", UUID.randomUUID().toString()),
                label = obj.optString("label", "Object"),
                centerMm = PointMm(
                    obj.optInt("centerXMm", 0),
                    obj.optInt("centerYMm", 0)
                ),
                widthMm = obj.optInt("widthMm", 1000).coerceIn(150, 10000),
                heightMm = obj.optInt("heightMm", 1000).coerceIn(150, 10000),
                rotationDeg = obj.optDouble("rotationDeg", 0.0).toFloat(),
                topDownPngBase64 = obj.optString("topDownPngBase64", ""),
                sourcePhotoThumbBase64 = obj.optString("sourcePhotoThumbBase64", "").ifBlank { null },
                providerUsed = obj.optString("providerUsed", "Gemini"),
                aiDescription = obj.optString("aiDescription", "")
            )
        }
    }
}

data class GeneratedArtResult(
    val topDownBitmap: Bitmap,
    val topDownPngBase64: String,
    val detectedLabel: String,
    val description: String,
    val providerSummary: String
)

/**
 * Multi-provider BYOK AI engine (Gemini, Claude, OpenAI) that takes a real-world photo of an object
 * plus its map footprint (`widthCm × depthCm`) and synthesizes a scaled, transparent-background
 * 90° top-down orthographic view to overlay on the 2D LiDAR floor map.
 */
object AiMapArtEngine {

    suspend fun generateTopDownObjectArt(
        provider: ByokProvider,
        apiKey: String,
        photoBitmap: Bitmap?,
        objectLabel: String,
        widthCm: Int,
        depthCm: Int,
        useOpenAiRasterImage: Boolean = true
    ): Result<GeneratedArtResult> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("Please enter your ${provider.label} API Key first.")
            )
        }

        val cleanLabel = objectLabel.trim().ifBlank { "Household Object / Furniture" }
        val safeW = widthCm.coerceIn(15, 1000)
        val safeD = depthCm.coerceIn(15, 1000)
        val photoJpegBase64 = photoBitmap?.let { encodeBitmapToJpegBase64(it, maxDim = 768, quality = 84) }

        return@withContext try {
            when (provider) {
                ByokProvider.GEMINI -> generateWithGemini(cleanKey, photoJpegBase64, cleanLabel, safeW, safeD)
                ByokProvider.CLAUDE -> generateWithClaude(cleanKey, photoJpegBase64, cleanLabel, safeW, safeD)
                ByokProvider.OPENAI -> generateWithOpenAi(cleanKey, photoJpegBase64, cleanLabel, safeW, safeD, useOpenAiRasterImage)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // =========================================================================
    // 1. Google Gemini (Multimodal Native Image Generation + Vision SVG Fallback)
    // =========================================================================

    private fun generateWithGemini(
        apiKey: String,
        photoJpegBase64: String?,
        objectLabel: String,
        widthCm: Int,
        depthCm: Int
    ): Result<GeneratedArtResult> {
        // Step A: Try Gemini Multimodal Image Generation (`gemini-2.5-flash-image` / `gemini-2.0-flash-exp-image-generation`)
        val imageModels = listOf("gemini-2.5-flash-image", "gemini-2.0-flash-exp-image-generation")
        for (modelId in imageModels) {
            val rasterAttempt = runCatching {
                callGeminiImageGenModel(apiKey, modelId, photoJpegBase64, objectLabel, widthCm, depthCm)
            }.getOrNull()
            if (rasterAttempt != null) {
                return Result.success(rasterAttempt)
            }
        }

        // Step B: Fallback to Gemini Vision (`gemini-2.5-flash` / `gemini-2.0-flash`) -> Bespoke Top-Down SVG
        val svgModels = listOf("gemini-2.5-flash", "gemini-2.0-flash")
        var lastError: Exception? = null
        for (modelId in svgModels) {
            try {
                return Result.success(
                    callGeminiVisionSvgModel(apiKey, modelId, photoJpegBase64, objectLabel, widthCm, depthCm)
                )
            } catch (e: Exception) {
                lastError = e
            }
        }
        return Result.failure(lastError ?: IllegalStateException("Gemini API request failed"))
    }

    private fun callGeminiImageGenModel(
        apiKey: String,
        modelId: String,
        photoJpegBase64: String?,
        objectLabel: String,
        widthCm: Int,
        depthCm: Int
    ): GeneratedArtResult? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent?key=$apiKey"
        val prompt = buildString {
            append("Create a strict 90-degree overhead TOP-DOWN orthographic architectural floor-plan sprite of this real-world object: '$objectLabel'. ")
            append("Real-world footprint dimensions: ${widthCm}cm wide by ${depthCm}cm deep (aspect ratio ${widthCm}:${depthCm}). ")
            if (photoJpegBase64 != null) {
                append("Match the exact colors, materials, textures, cushion/surface divisions, and shape from the attached photo, viewed strictly from directly above (bird's-eye 90° top-down view, zero perspective tilt). ")
            }
            append("IMPORTANT: Isolate the object on a pure, flat, solid white (#FFFFFF) background with no floor, no room walls, and no cast shadow outside the object's footprint so it can be cleanly cut out and scaled onto a 2D map.")
        }

        val parts = JSONArray().apply {
            if (photoJpegBase64 != null) {
                put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", "image/jpeg")
                        put("data", photoJpegBase64)
                    })
                })
            }
            put(JSONObject().put("text", prompt))
        }

        val reqBody = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
            })
        }

        val respJson = postJson(url, reqBody.toString(), emptyMap())
        val candidates = respJson.optJSONArray("candidates") ?: return null
        val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return null
        val respParts = content.optJSONArray("parts") ?: return null

        var imageBase64: String? = null
        var textNote = ""
        for (i in 0 until respParts.length()) {
            val p = respParts.optJSONObject(i) ?: continue
            val inline = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data")
            if (inline != null && inline.optString("data").isNotBlank()) {
                imageBase64 = inline.getString("data")
            } else if (p.optString("text").isNotBlank()) {
                textNote = p.optString("text").trim()
            }
        }

        val rawB64 = imageBase64 ?: return null
        val rawBytes = Base64.decode(rawB64, Base64.DEFAULT)
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
        val cutOut = AutoBackgroundRemover.stripBackgroundAndFitAspect(decoded, widthCm, depthCm)
        val finalB64 = encodeBitmapToPngBase64(cutOut)

        return GeneratedArtResult(
            topDownBitmap = cutOut,
            topDownPngBase64 = finalB64,
            detectedLabel = objectLabel,
            description = textNote.ifBlank { "Top-down orthographic sprite generated from photo (${widthCm}×${depthCm} cm)" },
            providerSummary = "Gemini ($modelId)"
        )
    }

    private fun callGeminiVisionSvgModel(
        apiKey: String,
        modelId: String,
        photoJpegBase64: String?,
        objectLabel: String,
        widthCm: Int,
        depthCm: Int
    ): GeneratedArtResult {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent?key=$apiKey"
        val svgW = (widthCm * 4).coerceIn(240, 1024)
        val svgH = (depthCm * 4).coerceIn(240, 1024)
        val prompt = buildSvgPrompt(objectLabel, widthCm, depthCm, svgW, svgH, hasPhoto = photoJpegBase64 != null)

        val parts = JSONArray().apply {
            if (photoJpegBase64 != null) {
                put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", "image/jpeg")
                        put("data", photoJpegBase64)
                    })
                })
            }
            put(JSONObject().put("text", prompt))
        }

        val reqBody = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.25)
            })
        }

        val respJson = postJson(url, reqBody.toString(), emptyMap())
        val text = respJson.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            .orEmpty()

        val bitmap = TopDownSvgRenderer.renderSvgToBitmap(text, svgW, svgH, objectLabel)
        val pngB64 = encodeBitmapToPngBase64(bitmap)
        return GeneratedArtResult(
            topDownBitmap = bitmap,
            topDownPngBase64 = pngB64,
            detectedLabel = extractTitleOrDefault(text, objectLabel),
            description = "Top-down architectural vector sprite (${widthCm}×${depthCm} cm)",
            providerSummary = "Gemini ($modelId)"
        )
    }

    // =========================================================================
    // 2. Anthropic Claude (Vision Analysis -> Rich Top-Down Orthographic SVG)
    // =========================================================================

    private fun generateWithClaude(
        apiKey: String,
        photoJpegBase64: String?,
        objectLabel: String,
        widthCm: Int,
        depthCm: Int
    ): Result<GeneratedArtResult> {
        val models = listOf(
            "claude-sonnet-4-20250514",
            "claude-3-7-sonnet-latest",
            "claude-3-5-sonnet-latest"
        )
        val svgW = (widthCm * 4).coerceIn(240, 1024)
        val svgH = (depthCm * 4).coerceIn(240, 1024)
        val prompt = buildSvgPrompt(objectLabel, widthCm, depthCm, svgW, svgH, hasPhoto = photoJpegBase64 != null)

        val contentArray = JSONArray().apply {
            if (photoJpegBase64 != null) {
                put(JSONObject().apply {
                    put("type", "image")
                    put("source", JSONObject().apply {
                        put("type", "base64")
                        put("media_type", "image/jpeg")
                        put("data", photoJpegBase64)
                    })
                })
            }
            put(JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            })
        }

        var lastError: Exception? = null
        for (modelId in models) {
            try {
                val reqBody = JSONObject().apply {
                    put("model", modelId)
                    put("max_tokens", 4096)
                    put("messages", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("content", contentArray)
                    }))
                }
                val headers = mapOf(
                    "x-api-key" to apiKey,
                    "anthropic-version" to "2023-06-01"
                )
                val respJson = postJson("https://api.anthropic.com/v1/messages", reqBody.toString(), headers)
                val contentBlocks = respJson.optJSONArray("content") ?: JSONArray()
                var fullText = ""
                for (i in 0 until contentBlocks.length()) {
                    val block = contentBlocks.optJSONObject(i) ?: continue
                    if (block.optString("type") == "text") {
                        fullText += block.optString("text")
                    }
                }
                if (fullText.isBlank()) throw IllegalStateException("Empty response from Claude ($modelId)")

                val bitmap = TopDownSvgRenderer.renderSvgToBitmap(fullText, svgW, svgH, objectLabel)
                val pngB64 = encodeBitmapToPngBase64(bitmap)
                return Result.success(
                    GeneratedArtResult(
                        topDownBitmap = bitmap,
                        topDownPngBase64 = pngB64,
                        detectedLabel = extractTitleOrDefault(fullText, objectLabel),
                        description = "Claude Vision top-down orthographic art (${widthCm}×${depthCm} cm)",
                        providerSummary = "Claude ($modelId)"
                    )
                )
            } catch (e: Exception) {
                lastError = e
            }
        }
        return Result.failure(lastError ?: IllegalStateException("Claude API call failed"))
    }

    // =========================================================================
    // 3. OpenAI (GPT-4o Vision Analysis + DALL·E 3 Raster or Top-Down SVG)
    // =========================================================================

    private fun generateWithOpenAi(
        apiKey: String,
        photoJpegBase64: String?,
        objectLabel: String,
        widthCm: Int,
        depthCm: Int,
        useRasterImage: Boolean
    ): Result<GeneratedArtResult> {
        val headers = mapOf("Authorization" to "Bearer $apiKey")
        val svgW = (widthCm * 4).coerceIn(240, 1024)
        val svgH = (depthCm * 4).coerceIn(240, 1024)

        // Step 1: Use GPT-4o Vision to inspect the user's real-world photo and produce both
        // a precise visual description (colors, materials, geometry) AND a bespoke top-down SVG.
        val visionContent = JSONArray().apply {
            if (photoJpegBase64 != null) {
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$photoJpegBase64"))
                })
            }
            put(JSONObject().apply {
                put("type", "text")
                put("text", buildSvgPrompt(objectLabel, widthCm, depthCm, svgW, svgH, hasPhoto = photoJpegBase64 != null))
            })
        }

        val chatReq = JSONObject().apply {
            put("model", "gpt-4o")
            put("temperature", 0.25)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", visionContent)
            }))
        }

        val chatResp = postJson("https://api.openai.com/v1/chat/completions", chatReq.toString(), headers)
        val visionSvgText = chatResp.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()

        val detectedTitle = extractTitleOrDefault(visionSvgText, objectLabel)

        // Step 2: If raster generation is enabled, try DALL-E 3 (`dall-e-3`) using the vision analysis
        if (useRasterImage) {
            val dalleAttempt = runCatching {
                val dallePrompt = buildString {
                    append("Strict 90-degree overhead TOP-DOWN orthographic architectural floor-plan sprite of '$detectedTitle' ")
                    append("(footprint aspect ratio ${widthCm}cm wide by ${depthCm}cm deep). ")
                    append("Isolated on a pure solid flat white (#FFFFFF) background with no room walls and no external floor. ")
                    append("Match the exact color palette and texture details of the object.")
                }
                val imgReq = JSONObject().apply {
                    put("model", "dall-e-3")
                    put("prompt", dallePrompt)
                    put("n", 1)
                    put("size", "1024x1024")
                    put("response_format", "b64_json")
                }
                val imgResp = postJson("https://api.openai.com/v1/images/generations", imgReq.toString(), headers)
                val b64 = imgResp.optJSONArray("data")?.optJSONObject(0)?.optString("b64_json").orEmpty()
                if (b64.isNotBlank()) {
                    val rawBytes = Base64.decode(b64, Base64.DEFAULT)
                    val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                    if (decoded != null) {
                        val cutOut = AutoBackgroundRemover.stripBackgroundAndFitAspect(decoded, widthCm, depthCm)
                        val finalB64 = encodeBitmapToPngBase64(cutOut)
                        GeneratedArtResult(
                            topDownBitmap = cutOut,
                            topDownPngBase64 = finalB64,
                            detectedLabel = detectedTitle,
                            description = "GPT-4o Vision + DALL·E 3 top-down sprite (${widthCm}×${depthCm} cm)",
                            providerSummary = "OpenAI (GPT-4o + DALL·E 3)"
                        )
                    } else null
                } else null
            }.getOrNull()

            if (dalleAttempt != null) {
                return Result.success(dalleAttempt)
            }
        }

        // Fallback / Fast Vector Mode: Render the GPT-4o Vision SVG directly
        val bitmap = TopDownSvgRenderer.renderSvgToBitmap(visionSvgText, svgW, svgH, detectedTitle)
        val pngB64 = encodeBitmapToPngBase64(bitmap)
        return Result.success(
            GeneratedArtResult(
                topDownBitmap = bitmap,
                topDownPngBase64 = pngB64,
                detectedLabel = detectedTitle,
                description = "GPT-4o Vision top-down architectural sprite (${widthCm}×${depthCm} cm)",
                providerSummary = "OpenAI (GPT-4o Vision)"
            )
        )
    }

    // =========================================================================
    // Prompt Builder & HTTP Helper
    // =========================================================================

    private fun buildSvgPrompt(
        objectLabel: String,
        widthCm: Int,
        depthCm: Int,
        svgW: Int,
        svgH: Int,
        hasPhoto: Boolean
    ): String = """
        You are an expert architectural floor-plan illustrator.
        Task: Create a rich, detailed, 90-degree overhead TOP-DOWN orthographic view of this real-world object: "$objectLabel".
        Real-world footprint dimensions on the floor map: ${widthCm} cm (width, X-axis) by ${depthCm} cm (depth, Y-axis).
        ${if (hasPhoto) "Carefully inspect the attached photo of the real object: match its exact primary/secondary hex colors, material texture (wood grain, fabric cushions, rug pattern, foliage, metallic accents), cushion/section count, and shape!" else ""}

        Output requirements:
        1. Output a valid, self-contained `<svg viewBox="0 0 $svgW $svgH" width="$svgW" height="$svgH" xmlns="http://www.w3.org/2000/svg">...</svg>` block.
        2. Fill the entire `$svgW x $svgH` viewBox with the top-down footprint of the object (leave only a tiny 4px margin so the object scales to its exact ${widthCm}cm x ${depthCm}cm box on the floor map).
        3. Keep the background outside the object silhouette completely transparent (do NOT draw a solid background rectangle covering the whole canvas unless the object itself is a rectangular table/rug/bed/sofa).
        4. Include `<title>Short Object Name</title>` as the first element inside `<svg>`.
        5. Use ONLY standard SVG elements (`<defs>`, `<linearGradient>`, `<radialGradient>`, `<stop>`, `<g>`, `<rect>`, `<circle>`, `<ellipse>`, `<line>`, `<polyline>`, `<polygon>`, `<path>`) with explicit hex colors (`#RRGGBB`), `fill`, `stroke`, `stroke-width`, `opacity`, `rx`, `ry`. Use at least 8–20 layered shapes (base frame, cushions/panels, armrests/edges, subtle highlights, texture lines/details) so it looks like a polished top-down architectural illustration!
    """.trimIndent()

    private fun extractTitleOrDefault(svgResponse: String, fallback: String): String {
        val m = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(svgResponse)
        val raw = m?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return if (raw.isNotBlank() && raw.length <= 36) raw else fallback
    }

    private fun postJson(
        urlStr: String,
        jsonBody: String,
        extraHeaders: Map<String, String>
    ): JSONObject {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 25000
            readTimeout = 45000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            for ((k, v) in extraHeaders) {
                setRequestProperty(k, v)
            }
        }
        conn.outputStream.use { out ->
            out.write(jsonBody.toByteArray(Charsets.UTF_8))
            out.flush()
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            val errObj = runCatching { JSONObject(text) }.getOrNull()
            val msg = errObj?.optJSONObject("error")?.optString("message")
                ?: text.take(220)
            throw IllegalStateException("HTTP $code: $msg")
        }
        return JSONObject(text)
    }

    // =========================================================================
    // Bitmap <-> Base64 Utilities
    // =========================================================================

    fun encodeBitmapToJpegBase64(bitmap: Bitmap, maxDim: Int = 768, quality: Int = 84): String {
        val scaled = scaleDownIfNeeded(bitmap, maxDim)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun encodeBitmapToPngBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun decodeBase64ToBitmap(base64: String?): Bitmap? {
        if (base64.isNullOrBlank()) return null
        return runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    fun scaleDownIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / max(w, h).toFloat()
        val nw = (w * ratio).roundToInt().coerceAtLeast(1)
        val nh = (h * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, nw, nh, true)
    }
}

/**
 * Strips solid/studio backgrounds from AI-generated top-down raster images using corner-seeded
 * flood-fill chroma keying and crops the foreground object to its tight bounding box, then scales
 * it to match the real-world `widthCm : depthCm` aspect ratio.
 */
object AutoBackgroundRemover {

    fun stripBackgroundAndFitAspect(
        source: Bitmap,
        widthCm: Int,
        depthCm: Int
    ): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        // Sample the 4 corners to identify the studio background color
        val cornerIndices = listOf(
            0,
            w - 1,
            (h - 1) * w,
            (h - 1) * w + (w - 1)
        )
        var bgR = 0
        var bgG = 0
        var bgB = 0
        for (idx in cornerIndices) {
            val c = pixels[idx]
            bgR += (c shr 16) and 0xFF
            bgG += (c shr 8) and 0xFF
            bgB += c and 0xFF
        }
        bgR /= 4
        bgG /= 4
        bgB /= 4

        val visited = BooleanArray(w * h)
        val queue = ArrayDeque<Int>(w * 4)

        fun colorDistSq(px: Int): Int {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val dr = r - bgR
            val dg = g - bgG
            val db = b - bgB
            return dr * dr + dg * dg + db * db
        }

        val thresholdSq = 42 * 42
        // Seed flood-fill from all 4 border edges
        for (x in 0 until w) {
            val top = x
            val bot = (h - 1) * w + x
            if (colorDistSq(pixels[top]) <= thresholdSq) {
                visited[top] = true
                queue.add(top)
            }
            if (colorDistSq(pixels[bot]) <= thresholdSq) {
                visited[bot] = true
                queue.add(bot)
            }
        }
        for (y in 0 until h) {
            val left = y * w
            val right = y * w + (w - 1)
            if (!visited[left] && colorDistSq(pixels[left]) <= thresholdSq) {
                visited[left] = true
                queue.add(left)
            }
            if (!visited[right] && colorDistSq(pixels[right]) <= thresholdSq) {
                visited[right] = true
                queue.add(right)
            }
        }

        while (!queue.isEmpty()) {
            val curr = queue.removeFirst()
            pixels[curr] = 0x00000000 // Transparent
            val cx = curr % w
            val cy = curr / w
            if (cx > 0) {
                val n = curr - 1
                if (!visited[n] && colorDistSq(pixels[n]) <= thresholdSq) {
                    visited[n] = true
                    queue.add(n)
                }
            }
            if (cx + 1 < w) {
                val n = curr + 1
                if (!visited[n] && colorDistSq(pixels[n]) <= thresholdSq) {
                    visited[n] = true
                    queue.add(n)
                }
            }
            if (cy > 0) {
                val n = curr - w
                if (!visited[n] && colorDistSq(pixels[n]) <= thresholdSq) {
                    visited[n] = true
                    queue.add(n)
                }
            }
            if (cy + 1 < h) {
                val n = curr + w
                if (!visited[n] && colorDistSq(pixels[n]) <= thresholdSq) {
                    visited[n] = true
                    queue.add(n)
                }
            }
        }

        // Find tight bounding box of non-transparent foreground pixels
        var minX = w
        var maxX = 0
        var minY = h
        var maxY = 0
        for (y in 0 until h) {
            val rowOff = y * w
            for (x in 0 until w) {
                val alpha = (pixels[rowOff + x] ushr 24) and 0xFF
                if (alpha > 16) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        val outBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outBitmap.setPixels(pixels, 0, w, 0, 0, w, h)

        val cropped = if (minX < maxX && minY < maxY) {
            Bitmap.createBitmap(outBitmap, minX, minY, (maxX - minX + 1), (maxY - minY + 1))
        } else {
            outBitmap
        }

        // Scale to target aspect ratio (max 512px on longest side for fast map rendering & storage)
        val maxSide = 420f
        val aspect = widthCm.toFloat() / depthCm.coerceAtLeast(1).toFloat()
        val targetW: Int
        val targetH: Int
        if (aspect >= 1f) {
            targetW = maxSide.roundToInt()
            targetH = (maxSide / aspect).roundToInt().coerceIn(48, 512)
        } else {
            targetH = maxSide.roundToInt()
            targetW = (maxSide * aspect).roundToInt().coerceIn(48, 512)
        }
        return Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
    }
}

/**
 * Built-in Android Canvas SVG renderer that converts AI-generated `<svg>` top-down architectural
 * illustrations (from Claude, Gemini, or OpenAI) into a transparent-background `Bitmap`.
 */
object TopDownSvgRenderer {

    private data class SvgGradientDef(
        val id: String,
        val isRadial: Boolean,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val cx: Float,
        val cy: Float,
        val r: Float,
        val colors: MutableList<Int> = mutableListOf(),
        val positions: MutableList<Float> = mutableListOf()
    )

    fun renderSvgToBitmap(
        rawResponse: String,
        targetWidthPx: Int,
        targetHeightPx: Int,
        fallbackLabel: String
    ): Bitmap {
        val outW = targetWidthPx.coerceIn(120, 768)
        val outH = targetHeightPx.coerceIn(120, 768)
        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val svgXml = extractSvgXml(rawResponse)
        if (svgXml == null) {
            drawProceduralFallbackSprite(canvas, outW.toFloat(), outH.toFloat(), fallbackLabel)
            return bitmap
        }

        val renderedAny = runCatching {
            parseAndDrawSvg(svgXml, canvas, outW.toFloat(), outH.toFloat())
        }.getOrDefault(false)

        if (!renderedAny) {
            drawProceduralFallbackSprite(canvas, outW.toFloat(), outH.toFloat(), fallbackLabel)
        }
        return bitmap
    }

    private fun extractSvgXml(text: String): String? {
        val start = text.indexOf("<svg", ignoreCase = true)
        val end = text.lastIndexOf("</svg>", ignoreCase = true)
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 6)
    }

    private fun parseAndDrawSvg(
        svgXml: String,
        canvas: Canvas,
        outW: Float,
        outH: Float
    ): Boolean {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(svgXml))

        var viewBoxW = outW
        var viewBoxH = outH
        var viewBoxMinX = 0f
        var viewBoxMinY = 0f
        var shapesDrawn = 0

        val gradients = mutableMapOf<String, SvgGradientDef>()
        var activeGrad: SvgGradientDef? = null
        val groupTransformCountStack = ArrayDeque<Int>()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase()
                    when (tag) {
                        "svg" -> {
                            val vb = parser.getAttributeValue(null, "viewBox")
                                ?: parser.getAttributeValue(null, "viewbox")
                            if (!vb.isNullOrBlank()) {
                                val nums = vb.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
                                if (nums.size == 4 && nums[2] > 0f && nums[3] > 0f) {
                                    viewBoxMinX = nums[0]
                                    viewBoxMinY = nums[1]
                                    viewBoxW = nums[2]
                                    viewBoxH = nums[3]
                                }
                            } else {
                                val wAttr = parseDim(parser.getAttributeValue(null, "width"), outW)
                                val hAttr = parseDim(parser.getAttributeValue(null, "height"), outH)
                                if (wAttr > 0f) viewBoxW = wAttr
                                if (hAttr > 0f) viewBoxH = hAttr
                            }
                            canvas.save()
                            canvas.scale(outW / viewBoxW, outH / viewBoxH)
                            canvas.translate(-viewBoxMinX, -viewBoxMinY)
                        }

                        "lineargradient" -> {
                            val id = parser.getAttributeValue(null, "id").orEmpty()
                            activeGrad = SvgGradientDef(
                                id = id,
                                isRadial = false,
                                x1 = parsePercentOrCoord(parser.getAttributeValue(null, "x1"), 0f, viewBoxW),
                                y1 = parsePercentOrCoord(parser.getAttributeValue(null, "y1"), 0f, viewBoxH),
                                x2 = parsePercentOrCoord(parser.getAttributeValue(null, "x2"), viewBoxW, viewBoxW),
                                y2 = parsePercentOrCoord(parser.getAttributeValue(null, "y2"), 0f, viewBoxH),
                                cx = 0f,
                                cy = 0f,
                                r = 0f
                            )
                        }

                        "radialgradient" -> {
                            val id = parser.getAttributeValue(null, "id").orEmpty()
                            activeGrad = SvgGradientDef(
                                id = id,
                                isRadial = true,
                                x1 = 0f,
                                y1 = 0f,
                                x2 = 0f,
                                y2 = 0f,
                                cx = parsePercentOrCoord(parser.getAttributeValue(null, "cx"), viewBoxW * 0.5f, viewBoxW),
                                cy = parsePercentOrCoord(parser.getAttributeValue(null, "cy"), viewBoxH * 0.5f, viewBoxH),
                                r = parsePercentOrCoord(parser.getAttributeValue(null, "r"), max(viewBoxW, viewBoxH) * 0.5f, max(viewBoxW, viewBoxH))
                            )
                        }

                        "stop" -> {
                            activeGrad?.let { grad ->
                                val offsetRaw = parser.getAttributeValue(null, "offset") ?: "0"
                                val offset = if (offsetRaw.endsWith("%")) {
                                    (offsetRaw.removeSuffix("%").toFloatOrNull() ?: 0f) / 100f
                                } else {
                                    offsetRaw.toFloatOrNull() ?: 0f
                                }
                                val styleAttr = parser.getAttributeValue(null, "style").orEmpty()
                                val stopColorStr = parser.getAttributeValue(null, "stop-color")
                                    ?: extractStyleProp(styleAttr, "stop-color")
                                    ?: "#888888"
                                val stopOpacity = (parser.getAttributeValue(null, "stop-opacity")
                                    ?: extractStyleProp(styleAttr, "stop-opacity"))?.toFloatOrNull() ?: 1f
                                val c = parseSvgColor(stopColorStr, stopOpacity) ?: android.graphics.Color.GRAY
                                grad.colors.add(c)
                                grad.positions.add(offset.coerceIn(0f, 1f))
                            }
                        }

                        "g" -> {
                            val tf = parser.getAttributeValue(null, "transform")
                            if (!tf.isNullOrBlank()) {
                                canvas.save()
                                applySvgTransform(canvas, tf)
                                groupTransformCountStack.addLast(1)
                            } else {
                                groupTransformCountStack.addLast(0)
                            }
                        }

                        "rect", "circle", "ellipse", "line", "polygon", "polyline", "path" -> {
                            val tf = parser.getAttributeValue(null, "transform")
                            if (!tf.isNullOrBlank()) {
                                canvas.save()
                                applySvgTransform(canvas, tf)
                            }
                            if (drawSvgShapeElement(tag, parser, canvas, gradients)) {
                                shapesDrawn++
                            }
                            if (!tf.isNullOrBlank()) {
                                canvas.restore()
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    when (parser.name.lowercase()) {
                        "lineargradient", "radialgradient" -> {
                            activeGrad?.let { if (it.id.isNotBlank() && it.colors.size >= 2) gradients[it.id] = it }
                            activeGrad = null
                        }
                        "g" -> {
                            val popped = if (groupTransformCountStack.isNotEmpty()) groupTransformCountStack.removeLast() else 0
                            if (popped > 0) canvas.restore()
                        }
                        "svg" -> {
                            runCatching { canvas.restore() }
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return shapesDrawn > 0
    }

    private fun drawSvgShapeElement(
        tag: String,
        parser: XmlPullParser,
        canvas: Canvas,
        gradients: Map<String, SvgGradientDef>
    ): Boolean {
        val styleAttr = parser.getAttributeValue(null, "style").orEmpty()
        val opacity = (parser.getAttributeValue(null, "opacity") ?: extractStyleProp(styleAttr, "opacity"))?.toFloatOrNull() ?: 1f
        val fillOpacity = ((parser.getAttributeValue(null, "fill-opacity") ?: extractStyleProp(styleAttr, "fill-opacity"))?.toFloatOrNull() ?: 1f) * opacity
        val strokeOpacity = ((parser.getAttributeValue(null, "stroke-opacity") ?: extractStyleProp(styleAttr, "stroke-opacity"))?.toFloatOrNull() ?: 1f) * opacity

        val fillStr = parser.getAttributeValue(null, "fill") ?: extractStyleProp(styleAttr, "fill") ?: if (tag == "line") "none" else "#94A3B8"
        val strokeStr = parser.getAttributeValue(null, "stroke") ?: extractStyleProp(styleAttr, "stroke") ?: "none"
        val strokeWidth = (parser.getAttributeValue(null, "stroke-width") ?: extractStyleProp(styleAttr, "stroke-width"))?.removeSuffix("px")?.toFloatOrNull() ?: 2f

        val fillPaint = buildPaint(fillStr, fillOpacity, Paint.Style.FILL, 0f, gradients)
        val strokePaint = buildPaint(strokeStr, strokeOpacity, Paint.Style.STROKE, strokeWidth, gradients)

        if (fillPaint == null && strokePaint == null) return false

        when (tag) {
            "rect" -> {
                val x = parseDim(parser.getAttributeValue(null, "x"), 0f)
                val y = parseDim(parser.getAttributeValue(null, "y"), 0f)
                val w = parseDim(parser.getAttributeValue(null, "width"), 0f)
                val h = parseDim(parser.getAttributeValue(null, "height"), 0f)
                if (w <= 0f || h <= 0f) return false
                val rx = parseDim(parser.getAttributeValue(null, "rx"), 0f)
                val ry = parseDim(parser.getAttributeValue(null, "ry"), rx)
                val rect = RectF(x, y, x + w, y + h)
                fillPaint?.let { canvas.drawRoundRect(rect, rx, ry, it) }
                strokePaint?.let { canvas.drawRoundRect(rect, rx, ry, it) }
            }
            "circle" -> {
                val cx = parseDim(parser.getAttributeValue(null, "cx"), 0f)
                val cy = parseDim(parser.getAttributeValue(null, "cy"), 0f)
                val r = parseDim(parser.getAttributeValue(null, "r"), 0f)
                if (r <= 0f) return false
                fillPaint?.let { canvas.drawCircle(cx, cy, r, it) }
                strokePaint?.let { canvas.drawCircle(cx, cy, r, it) }
            }
            "ellipse" -> {
                val cx = parseDim(parser.getAttributeValue(null, "cx"), 0f)
                val cy = parseDim(parser.getAttributeValue(null, "cy"), 0f)
                val rx = parseDim(parser.getAttributeValue(null, "rx"), 0f)
                val ry = parseDim(parser.getAttributeValue(null, "ry"), 0f)
                if (rx <= 0f || ry <= 0f) return false
                val rect = RectF(cx - rx, cy - ry, cx + rx, cy + ry)
                fillPaint?.let { canvas.drawOval(rect, it) }
                strokePaint?.let { canvas.drawOval(rect, it) }
            }
            "line" -> {
                val x1 = parseDim(parser.getAttributeValue(null, "x1"), 0f)
                val y1 = parseDim(parser.getAttributeValue(null, "y1"), 0f)
                val x2 = parseDim(parser.getAttributeValue(null, "x2"), 0f)
                val y2 = parseDim(parser.getAttributeValue(null, "y2"), 0f)
                strokePaint?.let { canvas.drawLine(x1, y1, x2, y2, it) }
            }
            "polygon", "polyline" -> {
                val ptsRaw = parser.getAttributeValue(null, "points").orEmpty().trim()
                val nums = ptsRaw.split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
                if (nums.size < 4) return false
                val path = android.graphics.Path()
                path.moveTo(nums[0], nums[1])
                var i = 2
                while (i + 1 < nums.size) {
                    path.lineTo(nums[i], nums[i + 1])
                    i += 2
                }
                if (tag == "polygon") path.close()
                if (tag == "polygon") fillPaint?.let { canvas.drawPath(path, it) }
                strokePaint?.let { canvas.drawPath(path, it) }
            }
            "path" -> {
                val d = parser.getAttributeValue(null, "d").orEmpty()
                if (d.isBlank()) return false
                val path = runCatching { PathParser.createPathFromPathData(d) }.getOrNull() ?: return false
                fillPaint?.let { canvas.drawPath(path, it) }
                strokePaint?.let { canvas.drawPath(path, it) }
            }
        }
        return true
    }

    private fun buildPaint(
        colorOrUrl: String,
        opacity: Float,
        style: Paint.Style,
        strokeWidth: Float,
        gradients: Map<String, SvgGradientDef>
    ): Paint? {
        val trimmed = colorOrUrl.trim()
        if (trimmed.isEmpty() || trimmed.equals("none", ignoreCase = true) || trimmed.equals("transparent", ignoreCase = true)) {
            return null
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = style
            this.strokeWidth = strokeWidth
            this.strokeCap = Paint.Cap.ROUND
            this.strokeJoin = Paint.Join.ROUND
        }
        if (trimmed.startsWith("url(#", ignoreCase = true)) {
            val id = trimmed.substringAfter("url(#").substringBefore(")").trim()
            val grad = gradients[id] ?: return null
            val shader: Shader = if (grad.isRadial) {
                RadialGradient(
                    grad.cx,
                    grad.cy,
                    grad.r.coerceAtLeast(1f),
                    grad.colors.toIntArray(),
                    grad.positions.toFloatArray(),
                    Shader.TileMode.CLAMP
                )
            } else {
                LinearGradient(
                    grad.x1,
                    grad.y1,
                    grad.x2,
                    grad.y2,
                    grad.colors.toIntArray(),
                    grad.positions.toFloatArray(),
                    Shader.TileMode.CLAMP
                )
            }
            paint.shader = shader
            paint.alpha = (opacity.coerceIn(0f, 1f) * 255).roundToInt()
            return paint
        }

        val parsedColor = parseSvgColor(trimmed, opacity) ?: return null
        paint.color = parsedColor
        return paint
    }

    private fun parseSvgColor(raw: String, opacity: Float): Int? {
        val s = raw.trim().lowercase()
        if (s == "none" || s == "transparent") return null
        val baseColor = when {
            s.startsWith("#") -> {
                val hex = s.removePrefix("#")
                val expanded = if (hex.length == 3) {
                    "${hex[0]}${hex[0]}${hex[1]}${hex[1]}${hex[2]}${hex[2]}"
                } else hex
                runCatching { android.graphics.Color.parseColor("#$expanded") }.getOrNull()
            }
            s.startsWith("rgb(") -> {
                val nums = s.removePrefix("rgb(").removeSuffix(")").split(",").mapNotNull { it.trim().toIntOrNull() }
                if (nums.size >= 3) android.graphics.Color.rgb(nums[0].coerceIn(0, 255), nums[1].coerceIn(0, 255), nums[2].coerceIn(0, 255))
                else null
            }
            s.startsWith("rgba(") -> {
                val parts = s.removePrefix("rgba(").removeSuffix(")").split(",").map { it.trim() }
                if (parts.size >= 4) {
                    val r = parts[0].toIntOrNull()?.coerceIn(0, 255) ?: 128
                    val g = parts[1].toIntOrNull()?.coerceIn(0, 255) ?: 128
                    val b = parts[2].toIntOrNull()?.coerceIn(0, 255) ?: 128
                    val a = ((parts[3].toFloatOrNull() ?: 1f) * 255).roundToInt().coerceIn(0, 255)
                    android.graphics.Color.argb(a, r, g, b)
                } else null
            }
            else -> namedColors[s] ?: runCatching { android.graphics.Color.parseColor(s) }.getOrNull()
        } ?: return null

        val existingAlpha = (baseColor ushr 24) and 0xFF
        val combinedAlpha = (existingAlpha * opacity.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        return (baseColor and 0x00FFFFFF) or (combinedAlpha shl 24)
    }

    private fun applySvgTransform(canvas: Canvas, transform: String) {
        val regex = Regex("([a-zA-Z]+)\\s*\\(([^)]*)\\)")
        for (match in regex.findAll(transform)) {
            val fn = match.groupValues[1].lowercase()
            val args = match.groupValues[2].trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
            when (fn) {
                "translate" -> {
                    val tx = args.getOrElse(0) { 0f }
                    val ty = args.getOrElse(1) { 0f }
                    canvas.translate(tx, ty)
                }
                "rotate" -> {
                    val deg = args.getOrElse(0) { 0f }
                    if (args.size >= 3) {
                        canvas.rotate(deg, args[1], args[2])
                    } else {
                        canvas.rotate(deg)
                    }
                }
                "scale" -> {
                    val sx = args.getOrElse(0) { 1f }
                    val sy = args.getOrElse(1) { sx }
                    canvas.scale(sx, sy)
                }
            }
        }
    }

    private fun extractStyleProp(style: String, prop: String): String? {
        for (part in style.split(";")) {
            val kv = part.split(":")
            if (kv.size == 2 && kv[0].trim().equals(prop, ignoreCase = true)) {
                return kv[1].trim()
            }
        }
        return null
    }

    private fun parseDim(raw: String?, defaultVal: Float): Float {
        if (raw.isNullOrBlank()) return defaultVal
        return raw.trim().removeSuffix("px").toFloatOrNull() ?: defaultVal
    }

    private fun parsePercentOrCoord(raw: String?, defaultVal: Float, span: Float): Float {
        if (raw.isNullOrBlank()) return defaultVal
        val s = raw.trim()
        return if (s.endsWith("%")) {
            ((s.removeSuffix("%").toFloatOrNull() ?: 0f) / 100f) * span
        } else {
            val v = s.removeSuffix("px").toFloatOrNull() ?: defaultVal
            if (v in 0f..1f && s.contains(".")) v * span else v
        }
    }

    private fun drawProceduralFallbackSprite(canvas: Canvas, w: Float, h: Float, label: String) {
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#334155")
            style = Paint.Style.FILL
        }
        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#64748B")
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#38BDF8")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val rect = RectF(6f, 6f, w - 6f, h - 6f)
        canvas.drawRoundRect(rect, 18f, 18f, basePaint)
        canvas.drawRoundRect(RectF(18f, 18f, w - 18f, h - 18f), 12f, 12f, innerPaint)
        canvas.drawRoundRect(rect, 18f, 18f, strokePaint)
    }

    private val namedColors = mapOf(
        "white" to android.graphics.Color.WHITE,
        "black" to android.graphics.Color.BLACK,
        "gray" to android.graphics.Color.GRAY,
        "grey" to android.graphics.Color.GRAY,
        "silver" to android.graphics.Color.LTGRAY,
        "red" to android.graphics.Color.parseColor("#EF4444"),
        "blue" to android.graphics.Color.parseColor("#3B82F6"),
        "green" to android.graphics.Color.parseColor("#22C55E"),
        "yellow" to android.graphics.Color.parseColor("#EAB308"),
        "orange" to android.graphics.Color.parseColor("#F97316"),
        "brown" to android.graphics.Color.parseColor("#8B5A2B"),
        "tan" to android.graphics.Color.parseColor("#D2B48C"),
        "beige" to android.graphics.Color.parseColor("#F5F5DC"),
        "cream" to android.graphics.Color.parseColor("#FFFDD0"),
        "ivory" to android.graphics.Color.parseColor("#FFFFF0"),
        "navy" to android.graphics.Color.parseColor("#1E3A8A"),
        "teal" to android.graphics.Color.parseColor("#14B8A6"),
        "gold" to android.graphics.Color.parseColor("#F59E0B")
    )
}

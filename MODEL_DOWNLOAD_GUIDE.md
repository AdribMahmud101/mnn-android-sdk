# Model Download Guide

## Issue: 401 Unauthorized Error

The default model URLs in the code are placeholders and will return a **401 Unauthorized** error when used. You need to provide your own valid model URLs.

## Solution: Use Custom URL Feature

The app now includes a **"📎 Enter Custom URL..."** option in the download dialog that allows you to download models from any publicly accessible URL.

### How to Use:

1. **Open the app** and tap the menu button (⋮)
2. **Select "Download Model"**
3. **Choose "📎 Enter Custom URL..."**
4. **Enter a direct download URL** to an `.mnn` model file
5. **Tap "Download"**

---

## Where to Get MNN Models

### Option 1: ModelScope (Alibaba's Model Platform)

ModelScope hosts official MNN-converted models:

- **Website:** https://www.modelscope.cn/models?name=MNN
- **Example Models:**
  - Qwen2.5-0.5B: https://www.modelscope.cn/models/MNN/Qwen2.5-0.5B-MNN
  - Qwen2.5-1.5B: https://www.modelscope.cn/models/MNN/Qwen2.5-1.5B-MNN

**To get download URL:**
1. Visit the model page
2. Click on "Files and versions" tab
3. Find the `.mnn` file
4. Right-click and copy the direct download link

### Option 2: HuggingFace

Some models are available on HuggingFace:

- **Search:** https://huggingface.co/models?other=mnn
- Look for models with `.mnn` files in their repository

**To get download URL:**
1. Browse to the model repository
2. Click on "Files and versions"
3. Find the `.mnn` file
4. Click the download icon or right-click → "Copy link"

### Option 3: Convert Your Own Models

Use MNN's official conversion tools to convert models from other formats:

1. **Install MNN Tools:**
   ```bash
   pip install MNN
   ```

2. **Convert a model:**
   ```bash
   mnn_converter -f ONNX --modelFile model.onnx --MNNModel output.mnn
   ```

3. **Host the file:**
   - Upload to your own server
   - Use cloud storage with public access (S3, Google Drive, etc.)
   - Use GitHub releases

---

## Updating Default URLs in Code

If you have specific models you use frequently, update the URLs directly in the code:

**File:** `sample/src/main/kotlin/com/mnn/sample/ModelDownloader.kt`

```kotlin
val AVAILABLE_MODELS = mapOf(
    "qwen-0.5b" to ModelInfo(
        name = "Qwen-0.5B",
        url = "YOUR_ACTUAL_MODEL_URL_HERE",  // <-- Change this
        size = 512_000_000,
        description = "Small but capable LLM"
    )
)
```

Replace `YOUR_ACTUAL_MODEL_URL_HERE` with a valid, publicly accessible download URL.

---

## Testing Download

To verify your URL works:

1. **Test in browser:** Paste the URL in a browser - it should start downloading the file
2. **Check file format:** The URL should end with `.mnn` or at least download an `.mnn` file
3. **Verify accessibility:** Make sure the URL doesn't require authentication

---

## Example Valid URL Pattern

✅ **Good:** 
```
https://www.modelscope.cn/models/MNN/Qwen2.5-0.5B-MNN/resolve/master/qwen2.5-0.5b-mnn.mnn
```

❌ **Bad (requires auth):**
```
https://huggingface.co/private-repo/model.mnn  # Private repo
```

❌ **Bad (not direct download):**
```
https://example.com/models/page.html  # HTML page, not file
```

---

## Need Help?

If you continue to get 401 errors:

1. **Verify the URL** is publicly accessible (test in browser)
2. **Check if authentication is required** on the hosting platform
3. **Try a different model source** (ModelScope vs HuggingFace)
4. **Host your own model** on a server you control

The app will now display a detailed error message explaining:
- What went wrong
- Where to get valid models
- How to fix the issue

---

## Official MNN Resources

- **MNN GitHub:** https://github.com/alibaba/MNN
- **MNN Documentation:** https://www.yuque.com/mnn/en
- **Model Zoo:** https://github.com/alibaba/MNN/tree/master/transformers/llm

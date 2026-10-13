# Model Download Guide

> **Status: current.** This guide describes `AdvancedModelDownloader`, which is what the
> sample app actually uses. (An earlier draft documented a legacy single-URL
> downloader with hardcoded placeholder URLs; that class has been removed.)

## How the sample app gets models

`AdvancedModelDownloader` fetches MNN's official model catalogue and resolves each
model's exact file set automatically — you do **not** need to supply URLs.

1. Tap **⋮ → Download Model**.
2. Pick a model from the list (quick picks for LFM2-350M, Qwen2.5-0.5B, Qwen3.5-0.8B,
   Qwen3.5-2B, plus the full catalogue).
3. Optionally choose a source: **HuggingFace**, **ModelScope** or **Modelers**.
4. Download. Each file's progress, speed and remaining time are shown; optional
   files that fail are skipped rather than failing the whole download.

The catalogue is cached for 24 hours, so the list still renders offline once loaded.

### Why models need several files

An MNN LLM is not a single `.mnn` file. `AdvancedModelDownloader` reads
`llm_config.json` first and builds a `ModelProfile` from it, then fetches:

| File | Required | Purpose |
|---|---|---|
| `llm.mnn` | yes | network graph |
| `llm.mnn.weight` | yes | weights |
| `llm_config.json` | yes | runtime config |
| `tokenizer.txt` | yes | tokenizer |
| `embeddings_bf16.bin` | when `tie_embeddings` | external embedding table |
| `visual.mnn` / `visual.mnn.weight` / `vit_config.json` | when `is_visual` | vision encoder (VLMs) |

Models are stored under the app's private storage:

```
filesDir/models/<ModelName>/{llm.mnn, llm.mnn.weight, llm_config.json, tokenizer.txt, ...}
```

## Automatic repair

If a download is interrupted, the next launch detects the incomplete model and
re-downloads **only the missing files**. It infers the source repo from the saved
`source.json`, or probes the known `taobao-mnn` / `alibaba-mnn` layouts.

You can also trigger it manually: **⋮ → Manage Models**.

If a VLM's `visual.mnn` is missing, the config is patched to set `is_visual=false`
so the model still loads as a text-only LLM instead of failing outright.

## Where to get MNN models

### Option 1: HuggingFace (used by default)

Official MNN-converted models live under the [`taobao-mnn`](https://huggingface.co/taobao-mnn)
organisation, e.g. `taobao-mnn/Qwen3-0.6B-MNN`, `taobao-mnn/Qwen2.5-0.5B-Instruct-MNN`.

Browse models filtered by the `.mnn` format:
<https://huggingface.co/models?other=mnn>

### Option 2: ModelScope

<https://www.modelscope.cn/models?name=MNN> — e.g. `MNN/Qwen2.5-0.5B-MNN`.
Get direct file links from the **Files and versions** tab.

### Option 3: Convert your own

```bash
pip install MNN
mnnconvert -f ONNX --modelFile model.onnx --MNNModel output.mnn
```

For LLM support, export from an HF/ModelScope model directory rather than a plain
ONNX file; see the [MNN LLM export docs](https://github.com/alibaba/MNN/tree/master/transformers/llm/export).

## Troubleshooting

**"Enter Custom URL" is not implemented.** The menu entry currently shows a
"coming soon" toast — the downloader resolves catalogue URLs itself.

**Download fails immediately.** Check connectivity, then try the same model from a
different source (HF ↔ ModelScope). Private/gated HuggingFace repos return 401.

**Model fails to load.** Confirm *all* required files for that model are present —
use **Manage Models → Repair**. A lone `.mnn` file is never sufficient.

**Out of memory.** MNN needs roughly `model_size × 1.5` free RAM. Use the
0.5B/0.6B models on low-memory devices. For the non-LLM interpreter path,
`MNNConfig(memory = MemoryMode.LOW)` trades speed for a smaller footprint.

**Inference stalls mid-answer.** A stalled generation (no progress, flat CPU) has
been observed on a device with very little free RAM under heavy system load. Free up
memory or move to a larger model class only if the device can afford it.

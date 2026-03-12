// Minimal LLM header for JNI bridge.
// Virtual method ORDER must match the libllm.so vtable (verified via readelf).
// Vtable slot order (vptr[N]):
//   0: ~Llm() D2  1: ~Llm() D0  2: load  3: gen_attention_mask  4: gen_position_ids
//   5: embedding  6: sample  7: forwardRaw  8: response(vector<int>&,...)
//   9: generate_init  10: tokenizer_encode(string)  11: tokenizer_encode(MultimodalPrompt)
//  12: setWavformCallback  13: generateWavform

#pragma once

#include <vector>
#include <memory>
#include <string>
#include <ostream>
#include <functional>
#include <MNN/expr/Expr.hpp>

#ifndef MNN_PUBLIC
#define MNN_PUBLIC __attribute__((visibility("default")))
#endif

namespace MNN {
namespace Transformer {

// Forward-declare internal types we don't need fully defined
class LlmConfig;
struct MultimodalPrompt;

class MNN_PUBLIC Llm {
public:
    // Factory / lifecycle
    static Llm* createLLM(const std::string& config_path);
    static void destroy(Llm* llm);

    // Virtual destructor adds two vtable slots (D2 @ vptr[0], D0 @ vptr[1])
    virtual ~Llm();

    // ---- virtual methods – ORDER IS CRITICAL ----
    virtual bool load();                                                              // vptr[2]
    virtual MNN::Express::VARP gen_attention_mask(int seq_len);                       // vptr[3]
    virtual MNN::Express::VARP gen_position_ids(int seq_len);                         // vptr[4]
    virtual MNN::Express::VARP embedding(const std::vector<int>& input_ids);          // vptr[5]
    virtual int                sample(MNN::Express::VARP logits, int offset, int sz); // vptr[6]
    virtual std::vector<MNN::Express::VARP> forwardRaw(                               // vptr[7]
        MNN::Express::VARP h, MNN::Express::VARP mask, MNN::Express::VARP pos,
        std::vector<MNN::Express::VARP> extra = {});
    virtual void response(const std::vector<int>& input_ids,                          // vptr[8]
                          std::ostream* os, const char* end_with, int max_new_tokens);
    virtual void generate_init(std::ostream* os, const char* end_with);               // vptr[9]
    virtual std::vector<int> tokenizer_encode(const std::string& query);              // vptr[10]
    virtual std::vector<int> tokenizer_encode(const MultimodalPrompt& mp);            // vptr[11]
    virtual void setWavformCallback(                                                   // vptr[12]
        std::function<bool(const float*, size_t, bool)> cb);
    virtual void generateWavform();                                                    // vptr[13]

    // ---- non-virtual methods (direct symbol calls) ----
    void response(const std::string& user_content,
                  std::ostream* os,
                  const char* end_with,
                  int max_new_tokens);
    void reset();
    bool stoped();
    // Merge a JSON config string into the model config (e.g. jinja context for thinking mode).
    bool set_config(const std::string& content);
};

} // namespace Transformer
} // namespace MNN

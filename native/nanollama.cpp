// НаноПеро: тонкая JNI-обвязка над llama.cpp для моделей GGUF.
#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <atomic>
#include <string>
#include <vector>
#include <mutex>
#include "llama.h"

#define TAG "NanoLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::string tmpl;                 // шаблон чата из файла модели
    std::vector<llama_token> cached;  // что уже лежит в KV-кэше (для повторного использования начала диалога)
    int n_ctx = 4096;
    int n_batch = 512;
};

static std::atomic<bool> g_stop{false};
static std::once_flag g_init;

static void log_cb(ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

static std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return "";
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

// Длина самого длинного корректного UTF-8 префикса (токены могут резать многобайтовые символы)
static size_t valid_utf8_prefix(const std::string & s) {
    size_t i = 0, n = s.size(), last_ok = 0;
    while (i < n) {
        unsigned char c = (unsigned char) s[i];
        size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
        if (i + len > n) break;
        i += len; last_ok = i;
    }
    return last_ok;
}

// Java-строка из UTF-8 без проблем с «модифицированным UTF-8» (эмодзи)
static jstring to_jstring(JNIEnv * env, const std::string & s) {
    jbyteArray bytes = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) s.size(), (const jbyte *) s.data());
    jclass strCls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(strCls, "<init>", "([BLjava/lang/String;)V");
    jstring enc = env->NewStringUTF("UTF-8");
    auto r = (jstring) env->NewObject(strCls, ctor, bytes, enc);
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(enc); env->DeleteLocalRef(strCls);
    return r;
}

static std::string from_jstring(JNIEnv * env, jstring s) {
    if (!s) return "";
    jclass strCls = env->GetObjectClass(s);
    jmethodID getBytes = env->GetMethodID(strCls, "getBytes", "(Ljava/lang/String;)[B");
    jstring enc = env->NewStringUTF("UTF-8");
    auto arr = (jbyteArray) env->CallObjectMethod(s, getBytes, enc);
    jsize len = env->GetArrayLength(arr);
    std::string r(len, '\0');
    env->GetByteArrayRegion(arr, 0, len, (jbyte *) r.data());
    env->DeleteLocalRef(arr); env->DeleteLocalRef(enc); env->DeleteLocalRef(strCls);
    return r;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nanopero_app_LlamaNative_load(JNIEnv * env, jclass, jstring jpath, jint n_ctx, jint n_threads) {
    std::call_once(g_init, [] { llama_log_set(log_cb, nullptr); llama_backend_init(); });
    std::string path = jstr(env, jpath);
    auto mp = llama_model_default_params();
    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) { LOGE("load failed: %s", path.c_str()); return 0; }

    auto cp = llama_context_default_params();
    int train = llama_model_n_ctx_train(model);
    cp.n_ctx = (uint32_t) (train > 0 && n_ctx > train ? train : n_ctx);
    cp.n_batch = 512; cp.n_ubatch = 512;
    int cores = (int) sysconf(_SC_NPROCESSORS_ONLN);
    int th = n_threads > 0 ? n_threads : std::max(2, std::min(4, cores - 2));
    cp.n_threads = th; cp.n_threads_batch = th;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); LOGE("context failed"); return 0; }

    auto * s = new Session();
    s->model = model; s->ctx = ctx; s->vocab = llama_model_get_vocab(model);
    s->n_ctx = (int) llama_n_ctx(ctx); s->n_batch = (int) cp.n_batch;
    const char * t = llama_model_chat_template(model, nullptr);
    s->tmpl = t ? t : "";
    LOGI("loaded %s ctx=%d threads=%d", path.c_str(), s->n_ctx, th);
    return (jlong) s;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nanopero_app_LlamaNative_free(JNIEnv *, jclass, jlong h) {
    auto * s = (Session *) h;
    if (!s) return;
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nanopero_app_LlamaNative_stop(JNIEnv *, jclass) { g_stop = true; }

extern "C" JNIEXPORT jint JNICALL
Java_com_nanopero_app_LlamaNative_contextSize(JNIEnv *, jclass, jlong h) { return h ? ((Session *) h)->n_ctx : 0; }

static std::string apply_template(Session * s, const std::vector<std::string> & roles, const std::vector<std::string> & contents) {
    std::vector<llama_chat_message> msgs;
    for (size_t i = 0; i < roles.size(); i++) msgs.push_back({roles[i].c_str(), contents[i].c_str()});
    size_t total = 0; for (auto & c : contents) total += c.size();
    std::vector<char> buf(total * 2 + 4096);
    const char * tmpl = s->tmpl.empty() ? nullptr : s->tmpl.c_str();
    int n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int) buf.size());
    if (n < 0) {
        // Шаблон из файла не распознан — используем ChatML (понимают Qwen и большинство моделей)
        n = llama_chat_apply_template("chatml", msgs.data(), msgs.size(), true, buf.data(), (int) buf.size());
        if (n < 0) return "";
    }
    if (n > (int) buf.size()) {
        buf.resize(n + 1);
        n = llama_chat_apply_template(tmpl ? tmpl : "chatml", msgs.data(), msgs.size(), true, buf.data(), (int) buf.size());
    }
    return std::string(buf.data(), n);
}

static bool decode_range(Session * s, llama_batch_ext * batch, const std::vector<llama_token> & toks, int from, bool last_logits) {
    for (int i = from; i < (int) toks.size(); i += s->n_batch) {
        int end = std::min((int) toks.size(), i + s->n_batch);
        llama_batch_ext_clear(batch);
        for (int j = i; j < end; j++) {
            int idx = llama_batch_ext_add_token(batch, 0, toks[j]);
            llama_pos pos = j;
            llama_batch_ext_set_pos(batch, idx, &pos);
            if (last_logits && j == (int) toks.size() - 1) llama_batch_ext_set_output_logits(batch, idx, true);
        }
        if (g_stop) return false;
        if (llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) return false;
    }
    return true;
}

// Генерация ответа. roles/contents — весь диалог. sink.onToken(String): Boolean — false = остановить.
// Возвращает: 0 — ок, 1 — ошибка шаблона, 2 — слишком длинный текст, 3 — ошибка вычисления, 4 — остановлено.
extern "C" JNIEXPORT jint JNICALL
Java_com_nanopero_app_LlamaNative_generate(JNIEnv * env, jclass, jlong h, jobjectArray jroles, jobjectArray jcontents,
                                          jfloat temp, jint max_out, jfloat repeat_penalty, jboolean think, jobject sink) {
    auto * s = (Session *) h;
    if (!s) return 3;
    g_stop = false;
    std::vector<std::string> roles, contents;
    jsize n = env->GetArrayLength(jroles);
    for (jsize i = 0; i < n; i++) {
        auto r = (jstring) env->GetObjectArrayElement(jroles, i);
        auto c = (jstring) env->GetObjectArrayElement(jcontents, i);
        roles.push_back(jstr(env, r)); contents.push_back(from_jstring(env, c));
        env->DeleteLocalRef(r); env->DeleteLocalRef(c);
    }
    std::string prompt = apply_template(s, roles, contents);
    if (prompt.empty()) return 1;
    // Модели с режимом «размышлений» (Qwen3/3.5): без него сразу закрываем пустой блок <think>,
    // с ним — открываем блок сами (и сообщаем об этом приложению), чтобы рассуждения точно были в тегах
    const bool has_think = s->tmpl.find("enable_thinking") != std::string::npos;
    if (has_think) prompt += think ? "<think>\n" : "<think>\n\n</think>\n\n";

    int cap = (int) prompt.size() + 16;
    std::vector<llama_token> toks(cap);
    int nt = llama_tokenize(s->vocab, prompt.c_str(), (int) prompt.size(), toks.data(), cap, true, true);
    if (nt < 0) { toks.resize(-nt); nt = llama_tokenize(s->vocab, prompt.c_str(), (int) prompt.size(), toks.data(), -nt, true, true); }
    toks.resize(std::max(0, nt));
    if (toks.empty()) return 1;
    if ((int) toks.size() + 16 >= s->n_ctx) return 2;
    int budget = std::min((int) max_out, s->n_ctx - (int) toks.size() - 4);

    // Общее начало с прошлым запросом остаётся в кэше — пересчитываем только новое
    int keep = 0;
    while (keep < (int) s->cached.size() && keep < (int) toks.size() - 1 && s->cached[keep] == toks[keep]) keep++;
    llama_memory_t mem = llama_get_memory(s->ctx);
    if (!llama_memory_seq_rm(mem, 0, keep, -1)) { llama_memory_clear(mem, false); keep = 0; }
    s->cached.assign(toks.begin(), toks.begin() + keep);

    llama_batch_ext * batch = llama_batch_ext_init(s->ctx);
    if (!decode_range(s, batch, toks, keep, true)) {
        llama_batch_ext_free(batch); llama_memory_clear(mem, false); s->cached.clear();
        return g_stop ? 4 : 3;
    }
    s->cached = toks;

    auto sp = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(s->vocab), 64, repeat_penalty, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist((uint32_t) (time(nullptr) & 0xffffffff)));

    jclass sinkCls = env->GetObjectClass(sink);
    jmethodID onToken = env->GetMethodID(sinkCls, "onToken", "(Ljava/lang/String;)Z");
    std::string pending = (has_think && think) ? "<think>\n" : "";
    int rc = 0;
    char piece[256];
    for (int i = 0; i < budget; i++) {
        if (g_stop) { rc = 4; break; }
        llama_token t = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, t)) break;
        int len = llama_token_to_piece(s->vocab, t, piece, sizeof(piece), 0, false);
        if (len > 0) pending.append(piece, len);
        size_t ok = valid_utf8_prefix(pending);
        if (ok > 0) {
            jstring js = to_jstring(env, pending.substr(0, ok));
            pending.erase(0, ok);
            jboolean cont = env->CallBooleanMethod(sink, onToken, js);
            env->DeleteLocalRef(js);
            if (env->ExceptionCheck() || !cont) { env->ExceptionClear(); rc = 4; break; }
        }
        // Следующий шаг
        llama_batch_ext_clear(batch);
        int idx = llama_batch_ext_add_token(batch, 0, t);
        llama_pos pos = (llama_pos) s->cached.size();
        llama_batch_ext_set_pos(batch, idx, &pos);
        llama_batch_ext_set_output_logits(batch, idx, true);
        if (llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) { rc = 3; break; }
        s->cached.push_back(t);
    }
    llama_sampler_free(smpl);
    llama_batch_ext_free(batch);
    env->DeleteLocalRef(sinkCls);
    return rc;
}

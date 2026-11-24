package com.nanopero.app

/** Мост к llama.cpp (libnanollama.so) для моделей в формате GGUF. */
object LlamaNative {
    fun interface TokenSink { fun onToken(piece: String): Boolean }

    val available: Boolean by lazy { runCatching { System.loadLibrary("nanollama") }.isSuccess }

    @JvmStatic external fun load(path: String, nCtx: Int, nThreads: Int): Long
    @JvmStatic external fun free(handle: Long)
    @JvmStatic external fun stop()
    @JvmStatic external fun contextSize(handle: Long): Int
    /** 0 — ок, 1 — шаблон, 2 — слишком длинно, 3 — ошибка, 4 — остановлено. */
    @JvmStatic external fun generate(handle: Long, roles: Array<String>, contents: Array<String>,
                                     temp: Float, maxOut: Int, repeatPenalty: Float, think: Boolean, sink: TokenSink): Int
}

package com.nanopero.app

import org.junit.Assume
import org.junit.Test
import java.io.File

/** Проверка JNI-моста llama.cpp на компьютере (запускается, только если есть хост-библиотека и модель). */
class LlamaNativeTest {
    @Test fun generate() {
        val lib = System.getenv("NANOLLAMA_HOST") ?: ""
        val model = System.getenv("NANOLLAMA_MODEL") ?: ""
        Assume.assumeTrue(File(lib).exists() && File(model).exists())
        System.load(lib)
        val t0 = System.currentTimeMillis()
        val h = LlamaNative.load(model, 2048, 2)
        check(h != 0L) { "load failed" }
        println("LLAMA load ms=${System.currentTimeMillis() - t0} ctx=${LlamaNative.contextSize(h)}")

        fun ask(roles: List<String>, msgs: List<String>, max: Int = 120, stopAfter: Int = Int.MAX_VALUE, think: Boolean = false): Pair<Int, String> {
            val sb = StringBuilder(); var n = 0
            val t = System.currentTimeMillis()
            val rc = LlamaNative.generate(h, roles.toTypedArray(), msgs.toTypedArray(), 0.3f, max, 1.1f, think) { p -> sb.append(p); ++n < stopAfter }
            println("LLAMA rc=$rc pieces=$n ms=${System.currentTimeMillis() - t} :: ${sb.toString().replace('\n', ' ').take(300)}")
            return rc to sb.toString()
        }
        val sys = "Ты полезный помощник. Отвечай кратко по-русски."
        val (rc1, a1) = ask(listOf("system", "user"), listOf(sys, "Как называется столица Франции? Ответь одним словом."))
        check(rc1 == 0 && a1.isNotBlank())
        // Второй ход: начало диалога должно взяться из кэша
        val (rc2, a2) = ask(listOf("system", "user", "assistant", "user"), listOf(sys, "Как называется столица Франции? Ответь одним словом.", a1, "А Германии?"))
        check(rc2 == 0 && a2.isNotBlank())
        // Остановка по запросу из колбэка
        val (rc3, _) = ask(listOf("system", "user"), listOf(sys, "Напиши длинный рассказ про кота."), max = 200, stopAfter = 5)
        check(rc3 == 4) { "stop rc=$rc3" }
        // Эмодзи и украинский
        val (rc4, a4) = ask(listOf("system", "user"), listOf(sys, "Напиши одно предложение українською з емодзі 🐱"), max = 60)
        check(rc4 == 0)
        // Слишком длинный текст
        val (rc5, _) = ask(listOf("system", "user"), listOf(sys, "слово ".repeat(5000)), max = 10)
        check(rc5 == 2) { "long rc=$rc5" }
        // Режим размышлений: рассуждения в <think>…</think>, затем ответ
        val (rc6, a6) = ask(listOf("system", "user"), listOf(sys, "Сколько будет 17 * 23? Дай только число."), max = 700, think = true)
        check(rc6 == 0 && a6.startsWith("<think>")) { "think rc=$rc6" }
        println("LLAMA think visible=[${afterThink(a6).trim()}] thinkLen=${a6.length} clean=[${Clean.output(a6, null).trim()}]")
        LlamaNative.free(h)
        println("LLAMA ok: ${a4.take(80)}")
    }
}

package uz.sovoz.app

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Gap-gap sintez: matn gaplarga bo'linadi, har bir gapning ovozi alohida
 * yuklanadi (parallel), o'qish esa birinchi gap tayyor bo'lishi bilan boshlanadi.
 * Shu tufayli birinchi tovushgacha kutish qisqa bo'ladi.
 */
class Speaker(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val pool = Executors.newFixedThreadPool(3)

    private val cacheDir: File by lazy {
        File(context.cacheDir, "tts").apply { mkdirs() }
    }

    @Volatile private var stopped = false
    @Volatile private var generation = 0
    @Volatile private var playLatch: CountDownLatch? = null

    private var player: MediaPlayer? = null
    private var systemTts: TextToSpeech? = null

    fun speak(raw: String, lang: String, onStatus: (String) -> Unit) {
        stop()
        val gen = ++generation
        stopped = false

        val sentences = split(raw)
        if (sentences.isEmpty()) {
            onStatus("Matn bo'sh")
            return
        }

        onStatus("Tayyorlanmoqda…")

        val futures: List<Future<File?>> = sentences.map { s ->
            pool.submit(Callable { download(s, lang) })
        }

        Thread {
            var online = true
            for (i in sentences.indices) {
                if (stopped || gen != generation) return@Thread
                val file = try {
                    futures[i].get()
                } catch (e: Exception) {
                    null
                }
                if (stopped || gen != generation) return@Thread

                if (file != null) {
                    emit(onStatus, "O'qilmoqda…")
                    playFile(file, gen)
                } else {
                    online = false
                    emit(onStatus, "Oflayn rejim")
                    speakSystem(sentences[i], lang)
                }
            }
            if (!stopped && gen == generation) {
                emit(onStatus, if (online) "Tugadi" else "Tugadi (oflayn)")
            }
        }.start()
    }

    fun stop() {
        stopped = true
        generation++
        playLatch?.countDown()
        main.post {
            try {
                player?.stop()
            } catch (e: Exception) {
                // allaqachon to'xtagan
            }
            try {
                player?.release()
            } catch (e: Exception) {
                // allaqachon bo'shatilgan
            }
            player = null
        }
        try {
            systemTts?.stop()
        } catch (e: Exception) {
            // tizim sintezatori mavjud emas
        }
    }

    fun shutdown() {
        stop()
        pool.shutdownNow()
        try {
            systemTts?.shutdown()
        } catch (e: Exception) {
            // hech narsa
        }
        systemTts = null
    }

    private fun emit(onStatus: (String) -> Unit, text: String) {
        main.post { onStatus(text) }
    }

    private fun playFile(file: File, gen: Int) {
        val latch = CountDownLatch(1)
        playLatch = latch
        main.post {
            if (stopped || gen != generation) {
                latch.countDown()
                return@post
            }
            try {
                val mp = MediaPlayer()
                player = mp
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener { latch.countDown() }
                mp.setOnErrorListener { _, _, _ ->
                    latch.countDown()
                    true
                }
                mp.prepare()
                mp.start()
            } catch (e: Exception) {
                latch.countDown()
            }
        }
        try {
            latch.await(90, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        main.post {
            try {
                player?.release()
            } catch (e: Exception) {
                // bo'shatilgan
            }
            player = null
        }
    }

    private fun download(text: String, lang: String): File? {
        val target = File(cacheDir, key(text, lang) + ".mp3")
        if (target.exists() && target.length() > 0L) return target

        val url = buildString {
            append("https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob")
            append("&tl=").append(lang)
            append("&q=").append(URLEncoder.encode(text, "UTF-8"))
        }

        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
            )
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                val tmp = File(cacheDir, key(text, lang) + ".part")
                body.byteStream().use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                if (tmp.length() == 0L) {
                    tmp.delete()
                    return null
                }
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    return null
                }
                target
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun speakSystem(text: String, lang: String) {
        val tts = ensureSystemTts()
        if (tts == null) {
            main.post { }
            return
        }
        val latch = CountDownLatch(1)
        playLatch = latch
        val utteranceId = "sovoz-" + System.nanoTime()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}

            override fun onDone(id: String?) {
                latch.countDown()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                latch.countDown()
            }
        })
        try {
            tts.language = Locale.forLanguageTag(lang)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } catch (e: Exception) {
            latch.countDown()
        }
        try {
            latch.await(60, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun ensureSystemTts(): TextToSpeech? {
        if (systemTts == null) {
            val latch = CountDownLatch(1)
            var ok = false
            systemTts = TextToSpeech(context) { status ->
                ok = status == TextToSpeech.SUCCESS
                latch.countDown()
            }
            try {
                latch.await(5, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (!ok) {
                systemTts = null
                return null
            }
        }
        return systemTts
    }

    private fun split(text: String): List<String> {
        val parts = text
            .split(Regex("(?<=[.!?…;:])\\s+"))
            .flatMap { it.split("\n") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val out = ArrayList<String>()
        for (part in parts) {
            if (part.length <= 180) out.add(part) else out.addAll(chunk(part))
        }
        return out
    }

    private fun chunk(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (word in text.split(" ")) {
            if (word.isEmpty()) continue
            if (sb.isNotEmpty() && sb.length + word.length + 1 > 180) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(word)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun key(text: String, lang: String): String {
        val digest = MessageDigest.getInstance("MD5").digest((lang + "|" + text).toByteArray())
        val sb = StringBuilder()
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}

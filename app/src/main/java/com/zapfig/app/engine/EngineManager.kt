package com.zapfig.app.engine

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.zapfig.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.zip.GZIPInputStream

/**
 * O motor do bot (Go/whatsmeow) não vem dentro do APK: ele é baixado dos
 * releases "engine-v*" do GitHub e pode ser atualizado sem um app novo.
 *
 * Segurança: cada release traz um manifesto (engine.json) assinado com a MESMA
 * chave que assina o APK. O app confere a assinatura com o próprio certificado
 * e o SHA-256 da biblioteca antes de carregar. Sem isso, quem trocasse o arquivo
 * no GitHub controlaria o WhatsApp do usuário.
 */
object EngineManager {

    data class Remote(val version: String, val tag: String, val asset: String, val sha256: String, val size: Long)

    private const val API = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=40"
    private const val DL = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/download"

    /** Versão carregada neste processo (null = nenhuma). Só dá pra carregar uma vez por processo. */
    @Volatile var loadedVersion: String? = null
        private set

    /** Versão baixada esperando reinício para entrar em uso. */
    fun pendingVersion(ctx: Context): String? =
        current(ctx)?.optString("version")?.takeIf { it.isNotEmpty() && it != loadedVersion && loadedVersion != null }

    val isLoaded get() = loadedVersion != null

    private fun root(ctx: Context) = File(ctx.filesDir, "engine")
    private fun currentFile(ctx: Context) = File(root(ctx), "current.json")

    private fun current(ctx: Context): JSONObject? = try {
        currentFile(ctx).takeIf { it.exists() }?.readText()?.let { JSONObject(it) }
    } catch (e: Exception) { null }

    /** Carrega o motor instalado, se houver um compatível com este app. */
    @Synchronized
    fun loadInstalled(ctx: Context): Boolean {
        if (isLoaded) return true
        val cur = current(ctx) ?: return false
        if (cur.optString("api") != BuildConfig.ENGINE_API) return false
        val lib = File(cur.optString("path"))
        if (!lib.exists() || sha256(lib) != cur.optString("sha256")) return false
        // Android 14+: código carregado dinamicamente precisa estar somente leitura.
        lib.setReadOnly()
        System.load(lib.absolutePath)          // motor real (Go)
        System.loadLibrary("gojni")            // stub vazio que as classes do gomobile procuram
        loadedVersion = cur.optString("version")
        return true
    }

    /** Procura o motor mais novo compatível com este app (mesma API). Bloqueante. */
    fun findLatest(ctx: Context): Remote? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" }
            ?: throw IllegalStateException("processador não suportado (${Build.SUPPORTED_ABIS.joinToString()})")
        val releases = JSONArray(httpGet(API).decodeToString())
        val candidates = (0 until releases.length()).map { releases.getJSONObject(it) }
            .filter { !it.optBoolean("draft") && it.optString("tag_name").startsWith("engine-v") }
            .sortedWith { a, b -> compareVersions(b.optString("tag_name"), a.optString("tag_name")) }
        for (rel in candidates) {
            val tag = rel.getString("tag_name")
            val manifest = try { httpGet("$DL/$tag/engine.json") } catch (e: Exception) { continue }
            val sig = try { httpGet("$DL/$tag/engine.json.sig") } catch (e: Exception) { continue }
            if (!verifySignature(ctx, manifest, sig)) continue      // ignora releases não assinados por você
            val m = JSONObject(manifest.decodeToString())
            if (m.optString("api") != BuildConfig.ENGINE_API) continue
            val f = m.optJSONObject("files")?.optJSONObject(abi) ?: continue
            return Remote(m.getString("version"), tag, f.getString("asset"), f.getString("sha256"), f.optLong("size"))
        }
        return null
    }

    /** Já está instalada (carregada ou pendente) a versão [r]? */
    fun isInstalled(ctx: Context, r: Remote) = current(ctx)?.optString("sha256") == r.sha256

    /** Baixa, confere e instala. Entra em uso agora (se nada carregado) ou no próximo início. */
    @Synchronized
    fun install(ctx: Context, r: Remote, onProgress: (Int) -> Unit) {
        val dir = File(root(ctx), r.version).apply { deleteRecursively(); mkdirs() }
        val lib = File(dir, "libzapengine.so")
        val tmp = File(dir, "download.tmp")
        download("$DL/${r.tag}/${r.asset}", tmp, onProgress)
        val md = MessageDigest.getInstance("SHA-256")
        GZIPInputStream(tmp.inputStream()).use { input ->
            lib.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n); out.write(buf, 0, n)
                }
            }
        }
        tmp.delete()
        val hash = md.digest().toHex()
        if (hash != r.sha256) {
            dir.deleteRecursively()
            throw SecurityException("o arquivo do bot não confere (hash diferente); download cancelado")
        }
        lib.setReadOnly()
        val cur = JSONObject().put("version", r.version).put("api", BuildConfig.ENGINE_API)
            .put("path", lib.absolutePath).put("sha256", hash)
        val tmpCur = File(root(ctx), "current.tmp")
        tmpCur.writeText(cur.toString())
        tmpCur.renameTo(currentFile(ctx))
        // apaga versões antigas que não estão em uso
        root(ctx).listFiles()?.filter { it.isDirectory && it.name != r.version && it.name != loadedVersion }
            ?.forEach { it.deleteRecursively() }
    }

    // ---------- verificação ----------

    private fun verifySignature(ctx: Context, data: ByteArray, sig: ByteArray): Boolean = try {
        val pm = ctx.packageManager
        val signers = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val cf = CertificateFactory.getInstance("X.509")
        signers.orEmpty().any { s ->
            val cert = cf.generateCertificate(s.toByteArray().inputStream())
            Signature.getInstance("SHA256withRSA").run {
                initVerify(cert.publicKey); update(data); verify(sig)
            }
        }
    } catch (e: Exception) { false }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    /** "engine-v1.10.0" > "engine-v1.9.2" */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.substringAfter("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    // ---------- rede ----------

    private fun open(url: String): HttpURLConnection {
        var u = URL(url)
        repeat(6) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000; readTimeout = 30_000
                setRequestProperty("User-Agent", "ZapFig-Android")
                setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
            }
            val code = c.responseCode
            if (code in 300..399) { u = URL(u, c.getHeaderField("Location")); c.disconnect(); return@repeat }
            if (code != 200) { c.disconnect(); throw IllegalStateException("HTTP $code em $url") }
            return c
        }
        throw IllegalStateException("redirecionamentos demais em $url")
    }

    private fun httpGet(url: String): ByteArray = open(url).let { c ->
        try { c.inputStream.readBytes() } finally { c.disconnect() }
    }

    private fun download(url: String, to: File, onProgress: (Int) -> Unit) {
        val c = open(url)
        try {
            val total = c.contentLengthLong
            var done = 0L
            var last = -1
            c.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
            }
        } finally { c.disconnect() }
    }
}

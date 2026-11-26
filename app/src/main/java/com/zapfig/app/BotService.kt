package com.zapfig.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.service.quicksettings.TileService
import com.zapfig.app.media.AudioExtractor
import com.zapfig.app.media.CropMode
import com.zapfig.app.media.Downloader
import com.zapfig.app.media.MediaCache
import com.zapfig.app.media.GifMaker
import com.zapfig.app.media.StickerMaker
import com.zapfig.app.ui.MainActivity
import com.zapfig.engine.bot.Bot
import com.zapfig.engine.bot.Callback
import kotlin.concurrent.thread

/** Serviço em primeiro plano que mantém o motor do bot rodando. */
class BotService : Service() {

    companion object {
        const val ACTION_START = "com.zapfig.START"
        const val ACTION_STOP = "com.zapfig.STOP"
        const val ACTION_RECONFIGURE = "com.zapfig.RECONFIGURE"
        private const val EXTRA_PHONE = "phone"
        private const val CHANNEL = "bot"
        private const val NOTIF_ID = 1

        fun start(ctx: Context, phone: String = "") {
            val i = Intent(ctx, BotService::class.java).setAction(ACTION_START).putExtra(EXTRA_PHONE, phone)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            if (!BotState.running) return
            ctx.startService(Intent(ctx, BotService::class.java).setAction(ACTION_STOP))
        }

        /** Reenvia pacote/autor/auto-conversão ao motor. Funciona ligado ou desligado. */
        fun pushConfig(ctx: Context) {
            val p = Prefs(ctx)
            Bot.configure(p.pack, p.author, p.autoImage, p.autoGif, p.maxSeconds.toLong())
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var maker: StickerMaker
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())

    private val callback = object : Callback {
        override fun onLog(msg: String) { BotState.log(msg) }

        override fun onStatus(status: String) {
            BotState.onStatus(status)
            updateNotification(status)
            if (BotState.ui.value.phase == Phase.ERROR) main.post { shutdown(keepStatus = true) }
            if (BotState.ui.value.phase == Phase.ON) refreshPaired()
        }

        override fun onPairCode(code: String) {
            BotState.setPairCode(code)
            BotState.log("Código de pareamento: $code")
            updateNotification("Código de pareamento: ${formatCode(code)}")
        }

        override fun onStickerSent(animated: Boolean) {
            prefs.totalStickers += 1
            if (animated) prefs.animatedStickers += 1
            BotState.setStats(prefs.totalStickers, prefs.animatedStickers)
        }

        override fun onConfigChanged(pack: String, author: String) {
            prefs.pack = pack
            prefs.author = author
        }

        override fun fetchMedia(url: String, audioOnly: Boolean): String =
            Downloader.fetch(this@BotService, url, audioOnly) { BotState.log(it) }

        override fun convert(op: String, data: ByteArray?, arg: String): ByteArray {
            // arg "stretch" = /s2: quadrado perfeito, ignora o enquadramento dos Ajustes
            val crop = if (arg == "stretch") CropMode.STRETCH else prefs.crop
            val opt = StickerMaker.Options(crop, prefs.fps, prefs.maxSeconds)
            return when (op) {
                "image" -> maker.image(data!!, opt)
                // Trechos de um vídeo guardado no cache (pack de figurinhas)
                "prepare" -> MediaCache.put(this@BotService, data!!).toByteArray()
                "duration" -> maker.durationMs(MediaCache.get(this@BotService, arg)).toString().toByteArray()
                "release" -> { MediaCache.remove(this@BotService, arg); ByteArray(0) }
                "segment" -> MediaCache.args(arg).let { a ->
                    maker.videoFile(MediaCache.get(this@BotService, a["id"]!!), a["start"]!!.toLong(), a["len"]!!.toLong(),
                        opt.copy(crop = if (a["fit"] == "stretch") CropMode.STRETCH else opt.crop))
                }
                "segaudio" -> MediaCache.args(arg).let { a ->
                    AudioExtractor.fileToOggOpus(MediaCache.get(this@BotService, a["id"]!!), a["start"]!!.toLong(), a["len"]!!.toLong())
                }
                "video" -> maker.video(data!!, opt)
                "text" -> maker.text(arg)
                "toimage" -> maker.toImage(data!!)
                "togif" -> GifMaker.fromAnimatedWebP(data!!, cacheDir)
                "audio" -> AudioExtractor.toOggOpus(data!!, arg.toIntOrNull() ?: prefs.maxSeconds, cacheDir)
                else -> throw IllegalArgumentException("operação desconhecida: $op")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        maker = StickerMaker(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.wantRunning = false
                shutdown(keepStatus = false)
                return START_NOT_STICKY
            }
            ACTION_RECONFIGURE -> { pushConfig(this); return START_STICKY }
        }
        // ACTION_START ou reinício do sistema (intent nula)
        goForeground("Ligando…")
        if (!BotState.running || !Bot.isRunning()) {
            BotState.setPhase(Phase.STARTING, "Ligando…")
            prefs.wantRunning = true
            pushConfig(this)
            val phone = intent?.getStringExtra(EXTRA_PHONE).orEmpty().ifEmpty { prefs.phone }
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zapfig:bot").apply { acquire() }
            if (prefs.checkUpdates) thread(name = "zapfig-update") {
                        try {
                            val r = Updater.check(this)
                            if (r != null && r.version != prefs.skippedVersion) {
                                BotState.setUpdate(r)
                                BotState.log("Atualização disponível: ${r.version}")
                                if (!BotState.uiVisible) Updater.notifyAvailable(this, r)
                            }
                        } catch (e: Exception) {
                            BotState.log("Não consegui verificar atualizações: ${e.message}")
                        }
                    }
            thread(name = "zapfig-ytdlp") {
                // yt-dlp só é preparado no primeiro /baixar (ver Downloader.fetch)
                runCatching { Downloader.cleanup(this) }
                runCatching { MediaCache.clear(this) }
            }
            thread(name = "zapfig-start") {
                try {
                    Bot.start(filesDir.absolutePath, phone, callback)
                } catch (e: Throwable) {
                    BotState.setPhase(Phase.ERROR, e.message ?: "Erro ao ligar")
                    BotState.log("Erro: ${e.message}")
                    main.post { shutdown(keepStatus = true) }
                }
            }
        }
        updateTile()
        return START_STICKY
    }

    private fun refreshPaired() = thread {
        BotState.setPaired(true, Bot.pairedNumber(filesDir.absolutePath))
    }

    private fun shutdown(keepStatus: Boolean) {
        thread(name = "zapfig-stop") { Bot.stop() }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (keepStatus) BotState.setPhase(Phase.ERROR) else BotState.setPhase(Phase.OFF, "Desligado")
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
        updateTile()
    }

    override fun onDestroy() {
        if (Bot.isRunning()) {
            thread { Bot.stop() }
            BotState.setPhase(Phase.OFF, "Desligado")
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        updateTile()
        super.onDestroy()
    }

    private fun updateTile() {
        TileService.requestListeningState(this, ComponentName(this, ToggleTileService::class.java))
    }

    private fun formatCode(c: String) = if (c.length == 8) c.substring(0, 4) + "-" + c.substring(4) else c

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bot ligado", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Aparece enquanto o ZapFig está ligado"
                setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, BotService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
                else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("ZapFig")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentIntent(open)
            .setOngoing(true)
            .setColor(0xFF25D366.toInt())
            .addAction(Notification.Action.Builder(null, "Desligar", stop).build())
            .build()
    }

    private fun goForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    private fun updateNotification(text: String) {
        if (!BotState.running) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}

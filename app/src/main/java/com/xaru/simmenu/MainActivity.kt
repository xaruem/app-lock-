package com.xaru.simmenu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max

private const val TAPS_REQUIRED = 8   // сколько раз нажать на шапку SIM-экрана

enum class Screen { SIM, VAULT }

class MainActivity : ComponentActivity() {

    private var screen by mutableStateOf(Screen.SIM)
    private var hasKey by mutableStateOf(false)
    private var playing by mutableStateOf<File?>(null)

    // операции, которые идут при открытом приложении
    private var busy by mutableStateOf(false)
    private var progress by mutableFloatStateOf(0f)      // < 0 = неопределённый
    private var status by mutableStateOf("")
    private var cancellable by mutableStateOf(false)

    // экран настройки сжатия
    private var pending by mutableStateOf<List<VideoInfo>>(emptyList())
    private val trims = mutableStateMapOf<String, Trim>()
    private var settings by mutableStateOf(Settings())
    private var chargingOnly by mutableStateOf(false)

    private var summary by mutableStateOf<String?>(null)
    private var savedTotal by mutableLongStateOf(0L)
    private var deleteCount by mutableIntStateOf(0)
    private val files = mutableStateListOf<File>()
    private var restoreUri by mutableStateOf<Uri?>(null)
    private var showChangePass by mutableStateOf(false)

    private var keepOpen = false
    private var job: Job? = null
    private var afterPerm: (() -> Unit)? = null

    // ---------- системные окна ----------
    private val docPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach {
                try { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            }
            analyze(uris)
        }
    }
    private val backupCreate = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) runBackup(uri)
    }
    private val restorePick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreUri = uri
    }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterPerm?.invoke(); afterPerm = null
    }
    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == RESULT_OK) Stats.clearDeletes(this)
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // запрет скриншотов и превью в «недавних»
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        hasKey = VaultCrypto.hasKey(this)
        Temp.clean(this)
        QueueState.flow.update { it.copy(pending = JobStore.read(this).size) }
        refresh()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Yellow, onPrimary = Color.Black, background = Bg, surface = CardC)) {
                Surface(Modifier.fillMaxSize(), color = Bg) {
                    val queue by QueueState.flow.collectAsState()
                    LaunchedEffect(queue.tick, queue.running) { refresh() }

                    when (screen) {
                        Screen.SIM -> SimScreen(
                            hasKey = hasKey,
                            onUnlock = { pass -> tryUnlock(pass) },
                            onSetup = { pass -> setupVault(pass) },
                            onRestore = { keepOpen = true; restorePick.launch(arrayOf("*/*")) },
                            onUnlocked = { screen = Screen.VAULT },
                            tapsRequired = TAPS_REQUIRED
                        )
                        Screen.VAULT -> {
                            val p = playing
                            when {
                                pending.isNotEmpty() -> SetupScreen(
                                    infos = pending, trims = trims,
                                    settings = settings, onSettings = { settings = it },
                                    chargingOnly = chargingOnly, onCharging = { chargingOnly = it },
                                    onTrim = { info, t -> if (t == null) trims.remove(info.key) else trims[info.key] = t },
                                    onCancel = { pending = emptyList() },
                                    onStart = { startFromSetup() }
                                )
                                p != null -> PlayerScreen(
                                    file = p,
                                    onBack = { playing = null },
                                    onShare = { share(p) },
                                    onRecompress = { openRecompress(p) },
                                    onDelete = { p.delete(); playing = null; refresh() }
                                )
                                else -> VaultScreen(
                                    files = files, savedTotal = savedTotal, queue = queue,
                                    onAdd = { keepOpen = true; docPicker.launch(arrayOf("video/*")) },
                                    onOpen = { playing = it },
                                    onLock = { lock() },
                                    onBackup = {
                                        if (files.isEmpty()) Toast.makeText(this@MainActivity, "Сейф пуст", Toast.LENGTH_SHORT).show()
                                        else { keepOpen = true; backupCreate.launch("simmenu_backup_${System.currentTimeMillis() / 1000}.smb") }
                                    },
                                    onRestore = { keepOpen = true; restorePick.launch(arrayOf("*/*")) },
                                    onChangePass = { showChangePass = true },
                                    onRunQueue = { runQueueNow() },
                                    onClearQueue = { QueueRunner.clear(this@MainActivity) }
                                )
                            }
                        }
                    }

                    // ----- окна поверх экранов -----
                    if (busy) ProgressDialog(status, progress, if (cancellable) ({ job?.cancel(); busy = false }) else null)

                    restoreUri?.let { uri ->
                        RestorePasswordDialog(
                            onConfirm = { pass -> restoreUri = null; runRestore(uri, pass) },
                            onDismiss = { restoreUri = null }
                        )
                    }

                    if (showChangePass) ChangePasswordDialog(
                        onConfirm = { old, new ->
                            withContext(Dispatchers.Default) {
                                VaultCrypto.changePassword(this@MainActivity, old.toCharArray(), new.toCharArray())
                            }
                        },
                        onDismiss = { showChangePass = false }
                    )

                    summary?.let { s ->
                        AlertDialog(
                            containerColor = CardC,
                            onDismissRequest = { summary = null },
                            title = { Text("Готово") },
                            text = { Text(s) },
                            confirmButton = { SmallDialogButton("OK", { summary = null }) }
                        )
                    }

                    if (screen == Screen.VAULT && deleteCount > 0 && !busy && summary == null && pending.isEmpty()) {
                        AlertDialog(
                            containerColor = CardC,
                            onDismissRequest = {},
                            title = { Text("Удалить оригиналы?") },
                            text = { Text("Сжато видео: $deleteCount. Удалить оригиналы из галереи, чтобы освободить место?") },
                            confirmButton = { SmallDialogButton("Удалить", { requestDeleteOriginals() }) },
                            dismissButton = { SmallDialogButton("Оставить", { Stats.clearDeletes(this@MainActivity); refresh() }, accent = false) }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); keepOpen = false; Temp.clean(this) }

    // при сворачивании сейф закрывается (если не идёт выбор файла или длинная операция)
    override fun onStop() {
        super.onStop()
        if (!keepOpen && !busy) lock()
    }

    private fun lock() {
        Session.priv = null
        playing = null
        pending = emptyList()
        screen = Screen.SIM
    }

    private fun refresh() {
        files.clear()
        files.addAll(VaultCrypto.list(this))
        savedTotal = Stats.saved(this)
        deleteCount = Stats.deletes(this).size
    }

    // ---------- пароль ----------
    private suspend fun tryUnlock(pass: String): Boolean {
        val priv = withContext(Dispatchers.Default) { VaultCrypto.unlock(this@MainActivity, pass.toCharArray()) } ?: return false
        Session.priv = priv
        refresh()
        return true
    }

    private suspend fun setupVault(pass: String) {
        val priv = withContext(Dispatchers.Default) { VaultCrypto.create(this@MainActivity, pass.toCharArray()) }
        Session.priv = priv
        hasKey = true
        refresh()
    }

    // ---------- добавление видео ----------
    private fun analyze(uris: List<Uri>) {
        busy = true; cancellable = true; status = "Анализирую видео…"; progress = -1f
        job = lifecycleScope.launch {
            val infos = withContext(Dispatchers.IO) { uris.mapNotNull { Compressor.probe(this@MainActivity, it) } }
            busy = false
            if (infos.isEmpty()) Toast.makeText(this@MainActivity, "Не удалось прочитать видео", Toast.LENGTH_LONG).show()
            openSetup(infos)
        }
    }

    private fun openSetup(infos: List<VideoInfo>) {
        trims.clear()
        settings = Settings()
        chargingOnly = false
        pending = infos
    }

    private fun openRecompress(file: File) {
        val priv = Session.priv ?: return
        busy = true; cancellable = false; status = "Анализирую видео…"; progress = -1f
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { Compressor.probeVault(this@MainActivity, file, priv) }
            busy = false
            if (info == null) Toast.makeText(this@MainActivity, "Не удалось прочитать видео", Toast.LENGTH_LONG).show()
            else { playing = null; openSetup(listOf(info)) }
        }
    }

    private fun startFromSetup() {
        val infos = pending
        if (infos.isEmpty()) return
        if (infos.first().vaultName != null) { recompress(infos.first()); return }

        val s = settings
        val charging = chargingOnly
        val jobs = infos.map { QJob(UUID.randomUUID().toString(), it.uri.toString(), null, s, trims[it.key] ?: Trim()) }
        val go = {
            pending = emptyList()
            lifecycleScope.launch(Dispatchers.IO) {
                JobStore.add(this@MainActivity, jobs)
                QueueState.flow.update { it.copy(pending = JobStore.read(this@MainActivity).size) }
                QueueRunner.kick(this@MainActivity, charging)
            }
            Toast.makeText(
                this, if (charging) "В очереди: ${jobs.size}. Начнётся на зарядке" else "Сжатие запущено в фоне",
                Toast.LENGTH_LONG
            ).show()
        }
        if (Build.VERSION.SDK_INT >= 33 && !Stats.askedNotif(this) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Stats.setAskedNotif(this)
            afterPerm = go
            keepOpen = true
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else go()
    }

    private fun runQueueNow() {
        lifecycleScope.launch(Dispatchers.IO) { QueueRunner.kick(this@MainActivity, false) }
    }

    /** Пересжатие видео из сейфа: нужен открытый сейф, идёт при открытом приложении. */
    private fun recompress(info: VideoInfo) {
        val qj = QJob(UUID.randomUUID().toString(), null, info.vaultName, settings, trims[info.key] ?: Trim())
        pending = emptyList()
        busy = true; cancellable = true; status = "Пересжатие…"; progress = 0f
        job = lifecycleScope.launch {
            val res = try {
                Pipeline.run(this@MainActivity, qj) { progress = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RunResult(false, 0, 0, e.message)
            }
            busy = false
            refresh()
            val f = { b: Long -> Formatter.formatFileSize(this@MainActivity, b) }
            summary = if (res.ok && res.note == null) {
                val saved = max(0L, res.origSize - res.newSize)
                Stats.addSaved(this@MainActivity, saved)
                refresh()
                "Было ${f(res.origSize)} → стало ${f(res.newSize)}\nСэкономлено: ${f(saved)}"
            } else res.note ?: "Не удалось пересжать"
        }
    }

    // ---------- отправка ----------
    private fun share(file: File) {
        val priv = Session.priv ?: return
        busy = true; cancellable = false; status = "Готовлю файл…"; progress = -1f
        lifecycleScope.launch {
            val out = withContext(Dispatchers.IO) {
                val f = File(Temp.shareDir(this@MainActivity), "video_${System.currentTimeMillis()}.mp4")
                VaultCrypto.decryptTo(file, priv, f)
                f
            }
            busy = false
            keepOpen = true
            shareFile(this@MainActivity, out)
        }
    }

    // ---------- резервная копия ----------
    private fun runBackup(uri: Uri) {
        busy = true; cancellable = false; status = "Создаю резервную копию…"; progress = 0f
        job = lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri, "wt")!!.use { os ->
                        VaultCrypto.backup(this@MainActivity, os) { d, t -> progress = if (t > 0) d.toFloat() / t else 0f }
                    }
                    true
                } catch (_: Exception) { false }
            }
            busy = false
            summary = if (ok) "Резервная копия сохранена. Для восстановления нужен пароль от сейфа." else "Не удалось сохранить копию."
        }
    }

    private fun runRestore(uri: Uri, pass: String) {
        busy = true; cancellable = false; status = "Восстанавливаю сейф…"; progress = -1f
        job = lifecycleScope.launch {
            val priv = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)!!.use { VaultCrypto.restore(this@MainActivity, it, pass.toCharArray()) }
                } catch (_: Exception) { null }
            }
            busy = false
            if (priv == null) {
                summary = "Неверный пароль или файл повреждён. Сейф не изменён."
            } else {
                Session.priv = priv
                hasKey = true
                refresh()
                screen = Screen.VAULT
                summary = "Сейф восстановлен."
            }
        }
    }

    // ---------- удаление оригиналов ----------
    private fun toMediaUri(u: Uri): Uri? {
        if (u.authority == MediaStore.AUTHORITY) return u
        return if (Build.VERSION.SDK_INT >= 31) try { MediaStore.getMediaUri(this, u) } catch (_: Exception) { null } else null
    }

    private fun requestDeleteOriginals() {
        val uris = Stats.deletes(this).map { Uri.parse(it) }
        val media = uris.mapNotNull { toMediaUri(it) }
        if (Build.VERSION.SDK_INT >= 30 && media.isNotEmpty()) {
            try {
                val pi = MediaStore.createDeleteRequest(contentResolver, media)
                keepOpen = true
                deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                return
            } catch (_: Exception) { }
        }
        Toast.makeText(this, "Не удалось удалить автоматически. Удали оригиналы в галерее вручную.", Toast.LENGTH_LONG).show()
        Stats.clearDeletes(this)
        refresh()
    }
}

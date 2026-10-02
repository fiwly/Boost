package com.fiwly.aniimobooster

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList

class MainActivity : AppCompatActivity() {
    companion object {
        private const val ANIIMO = "com.x.aniimos"
        private const val SHIZUKU_REQUEST = 100
        private const val ORIGINAL_OVERLAY = "original_overlay"
        private const val USER_SERVICE_VERSION = 2
    }

    private lateinit var status: TextView
    private lateinit var thermal: TextView
    private lateinit var result: TextView
    private lateinit var scale: Spinner
    private lateinit var fps: Spinner
    private lateinit var apply: Button

    private var aniimoInstalled = false
    private var lastDetectionDetail = "Not checked yet"
    private var remoteService: IBoostService? = null
    private var serviceBinding = false
    private val pendingCommands = CopyOnWriteArrayList<(IBoostService) -> Unit>()

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, BoostUserService::class.java))
            .daemon(false)
            .version(USER_SERVICE_VERSION)
            .tag("aniimo-booster-command-service")
            .processNameSuffix("boost")
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceBinding = false
            remoteService = IBoostService.Stub.asInterface(binder)
            val service = remoteService
            if (service == null) {
                failPending("Shizuku UserService returned no binder")
                updateStatus()
                return
            }
            val jobs = pendingCommands.toList()
            pendingCommands.clear()
            jobs.forEach { it(service) }
            updateStatus()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBinding = false
            remoteService = null
            failPending("Shizuku UserService disconnected")
            runOnUiThread { updateStatus() }
        }
        override fun onBindingDied(name: ComponentName?) {
            serviceBinding = false
            remoteService = null
            failPending("Shizuku UserService binding died")
            runOnUiThread { updateStatus() }
        }
        override fun onNullBinding(name: ComponentName?) {
            serviceBinding = false
            remoteService = null
            failPending("Shizuku UserService returned a null binding")
            runOnUiThread { updateStatus() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        thermal = findViewById(R.id.thermal)
        result = findViewById(R.id.result)
        scale = findViewById(R.id.scale)
        fps = findViewById(R.id.fps)
        apply = findViewById(R.id.apply)

        scale.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("0.70x - Max FPS", "0.75x - Balanced", "0.80x - Cooler", "1.00x - Native"))
        fps.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("60 FPS", "45 FPS", "40 FPS", "30 FPS"))

        findViewById<Button>(R.id.max).setOnClickListener { setProfile(0, 0) }
        findViewById<Button>(R.id.balanced).setOnClickListener { setProfile(1, 0) }
        findViewById<Button>(R.id.cooler).setOnClickListener { setProfile(2, 1) }
        apply.setOnClickListener { applyProfile() }
        findViewById<Button>(R.id.performance).setOnClickListener {
            withAniimo { runCommand("cmd game mode performance $ANIIMO", "Performance mode enabled") }
        }
        findViewById<Button>(R.id.standard).setOnClickListener { restoreDefault() }
        findViewById<Button>(R.id.launch).setOnClickListener { launchAniimo() }
        findViewById<Button>(R.id.refresh).setOnClickListener { detectAniimo { updateStatus() } }

        Shizuku.addBinderReceivedListenerSticky {
            updateStatus()
            requestShizukuIfNeeded()
            detectAniimo { updateStatus() }
        }
        Shizuku.addBinderDeadListener {
            remoteService = null
            serviceBinding = false
            failPending("Shizuku binder disconnected")
            aniimoInstalled = packageManagerHasAniimo()
            updateStatus()
        }
        Shizuku.addRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == SHIZUKU_REQUEST) {
                updateStatus()
                detectAniimo { updateStatus() }
            }
        }

        updateStatus()
        requestShizukuIfNeeded()
        detectAniimo { updateStatus() }
    }

    private fun packageManagerHasAniimo(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(ANIIMO, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(ANIIMO, 0)
            }
            true
        } catch (_: Throwable) {
            try { packageManager.getLaunchIntentForPackage(ANIIMO) != null } catch (_: Throwable) { false }
        }
    }

    private fun detectAniimo(done: () -> Unit) {
        if (packageManagerHasAniimo()) {
            aniimoInstalled = true
            lastDetectionDetail = "PackageManager: package is visible"
            done()
            return
        }
        if (!shizukuPermissionGranted()) {
            aniimoInstalled = false
            lastDetectionDetail = if (shizukuBinderAlive()) {
                "PackageManager: not visible; Shizuku authorization required"
            } else {
                "PackageManager: not visible; Shizuku unavailable"
            }
            done()
            return
        }
        runCommand("pm path $ANIIMO") { output, code ->
            val found = code == 0 && output.lineSequence().any {
                it.trim().startsWith("package:") && it.contains(".apk")
            }
            aniimoInstalled = found
            lastDetectionDetail = if (found) {
                "Shizuku pm path: package found"
            } else {
                "Shizuku pm path: exit $code; package path not found"
            }
            done()
        }
    }

    private fun withAniimo(action: () -> Unit) {
        detectAniimo {
            if (!aniimoInstalled) {
                toast("Aniimo was not detected")
                result.text = "✕ Aniimo not detected\n$lastDetectionDetail"
                return@detectAniimo
            }
            if (!shizukuBinderAlive()) {
                toast("Shizuku binder is not connected")
                return@detectAniimo
            }
            if (!shizukuPermissionGranted()) {
                try { Shizuku.requestPermission(SHIZUKU_REQUEST) }
                catch (_: Throwable) { toast("Open Shizuku and authorize Aniimo Boost") }
                return@detectAniimo
            }
            action()
        }
    }

    private fun setProfile(scalePosition: Int, fpsPosition: Int) {
        scale.setSelection(scalePosition)
        fps.setSelection(fpsPosition)
        applyProfile()
    }

    private fun applyProfile() {
        withAniimo {
            val s = when (scale.selectedItemPosition) {
                0 -> "0.70"; 1 -> "0.75"; 2 -> "0.80"; else -> "1.0"
            }
            val f = when (fps.selectedItemPosition) {
                0 -> "60"; 1 -> "45"; 2 -> "40"; else -> "30"
            }
            setBusy(true)
            runCommand("device_config get game_overlay $ANIIMO") { output, code ->
                if (code != 0) {
                    finishApply(s, f, "Could not read the current Game Overlay (exit $code).")
                    return@runCommand
                }
                val old = output.trim()
                if (!getPreferences(MODE_PRIVATE).contains(ORIGINAL_OVERLAY)) {
                    getPreferences(MODE_PRIVATE).edit()
                        .putString(ORIGINAL_OVERLAY, if (old == "null" || old.isBlank()) "__NULL__" else old)
                        .apply()
                }
                val config = "mode=2,fps=$f,downscaleFactor=$s"
                runCommand("device_config put game_overlay $ANIIMO '$config'") { _, putCode ->
                    if (putCode != 0) {
                        finishApply(s, f, "Game Overlay write failed (exit $putCode).")
                        return@runCommand
                    }
                    runCommand("cmd game mode performance $ANIIMO") { modeOutput, modeCode ->
                        if (modeCode != 0) {
                            finishApply(s, f, "Performance mode failed (exit $modeCode). $modeOutput")
                        } else {
                            finishApply(s, f, "Applied successfully. Fully restart Aniimo before testing.")
                        }
                    }
                }
            }
        }
    }

    private fun finishApply(s: String, f: String, message: String) {
        runOnUiThread {
            setBusy(false)
            result.text = "✓ $message\nTarget: $f FPS  •  Render: $s x"
            updateStatus()
        }
    }

    private fun restoreDefault() {
        withAniimo {
            setBusy(true)
            val saved = getPreferences(MODE_PRIVATE).getString(ORIGINAL_OVERLAY, null)
            val command = if (saved == null || saved == "__NULL__") {
                "device_config delete game_overlay $ANIIMO"
            } else {
                "device_config put game_overlay $ANIIMO '$saved'"
            }
            runCommand(command) { _, code ->
                if (code != 0) {
                    runOnUiThread {
                        setBusy(false)
                        result.text = "✕ Restore failed (exit $code)."
                    }
                    return@runCommand
                }
                runCommand("cmd game mode standard $ANIIMO") { _, modeCode ->
                    getPreferences(MODE_PRIVATE).edit().remove(ORIGINAL_OVERLAY).apply()
                    runOnUiThread {
                        setBusy(false)
                        result.text = if (modeCode == 0) {
                            "✓ Restored Aniimo default settings."
                        } else {
                            "✓ Overlay restored. Standard mode returned exit $modeCode."
                        }
                        updateStatus()
                    }
                }
            }
        }
    }

    private fun launchAniimo() {
        detectAniimo {
            if (!aniimoInstalled) {
                toast("Aniimo is not installed")
                result.text = "✕ Aniimo not detected\n$lastDetectionDetail"
                return@detectAniimo
            }
            try {
                val intent = packageManager.getLaunchIntentForPackage(ANIIMO)
                if (intent != null) startActivity(intent)
                else runCommand("monkey -p $ANIIMO 1", "Aniimo launch command sent")
            } catch (_: Throwable) {
                runCommand("monkey -p $ANIIMO 1", "Aniimo launch command sent")
            }
        }
    }

    private fun shizukuBinderAlive(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    private fun shizukuPermissionGranted(): Boolean = try {
        shizukuBinderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    private fun requestShizukuIfNeeded() {
        if (!shizukuBinderAlive()) {
            result.text = "Shizuku binder: NOT CONNECTED"
            return
        }
        if (!shizukuPermissionGranted()) {
            try { Shizuku.requestPermission(SHIZUKU_REQUEST) }
            catch (_: Throwable) { result.text = "Shizuku is running, but Aniimo Boost is not authorized." }
        }
    }

    private fun connectUserService() {
        if (!shizukuPermissionGranted()) {
            failPending("Shizuku permission is not granted")
            return
        }
        if (remoteService?.asBinder()?.isBinderAlive == true || serviceBinding) return
        serviceBinding = true
        try {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        } catch (t: Throwable) {
            serviceBinding = false
            failPending("Unable to bind Shizuku UserService: ${t.message ?: "unknown error"}")
        }
    }

    private fun runCommand(command: String, successMessage: String? = null, done: ((String, Int) -> Unit)? = null) {
        val job: (IBoostService) -> Unit = { service ->
            Thread {
                try {
                    val raw = service.execute(command)
                    val firstNewline = raw.indexOf('\n')
                    val header = if (firstNewline >= 0) raw.substring(0, firstNewline) else raw
                    val output = if (firstNewline >= 0) raw.substring(firstNewline + 1) else ""
                    val code = header.removePrefix("exit=").toIntOrNull() ?: -1
                    runOnUiThread {
                        if (done != null) done(output, code)
                        else {
                            setBusy(false)
                            result.text = if (code == 0) {
                                "✓ ${successMessage ?: "Command completed"}"
                            } else {
                                "✕ Command failed (exit $code)\n$output"
                            }
                            updateStatus()
                        }
                    }
                } catch (t: Throwable) {
                    runOnUiThread {
                        if (done != null) done("", -1)
                        else {
                            setBusy(false)
                            result.text = "✕ Command failed: ${t.message ?: "remote service error"}"
                            updateStatus()
                        }
                    }
                }
            }.start()
        }
        if (remoteService?.asBinder()?.isBinderAlive == true) {
            job(remoteService!!)
        } else {
            pendingCommands.add(job)
            connectUserService()
        }
    }

    private fun failPending(message: String) {
        val jobs = pendingCommands.toList()
        pendingCommands.clear()
        if (jobs.isNotEmpty()) {
            runOnUiThread {
                setBusy(false)
                result.text = "✕ $message"
            }
        }
    }

    private fun updateStatus() {
        val pm = getSystemService(PowerManager::class.java)
        val binder = shizukuBinderAlive()
        val permission = shizukuPermissionGranted()
        val userService = remoteService?.asBinder()?.isBinderAlive == true
        val shizukuState = when {
            !binder -> "NOT RUNNING / BINDER NOT CONNECTED"
            permission -> "RUNNING + AUTHORIZED"
            else -> "RUNNING / AUTHORIZATION NEEDED"
        }
        status.text = "ANIIMO  •  " + if (aniimoInstalled) "INSTALLED" else "NOT DETECTED" +
            "\nPackage: $ANIIMO" +
            "\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
            "\nShizuku: $shizukuState" +
            "\nBinder: " + if (binder) "CONNECTED" else "NOT CONNECTED" +
            "\nPermission: " + if (permission) "GRANTED" else "NOT GRANTED" +
            "\nCommand Service: " + if (userService) "READY" else "NOT READY"
        thermal.text = if (Build.VERSION.SDK_INT >= 29) {
            "Thermal: ${thermalName(pm.currentThermalStatus)}"
        } else "Thermal: unavailable"

        if (getPreferences(MODE_PRIVATE).contains(ORIGINAL_OVERLAY) &&
            !result.text.toString().startsWith("✓ Applied")) {
            result.text = "A backup of the previous Game Overlay is saved."
        }
    }

    private fun setBusy(busy: Boolean) {
        apply.isEnabled = !busy
        listOf(R.id.max, R.id.balanced, R.id.cooler, R.id.performance, R.id.standard, R.id.launch, R.id.refresh)
            .forEach { findViewById<Button>(it).isEnabled = !busy }
    }

    private fun thermalName(v: Int) = when (v) {
        PowerManager.THERMAL_STATUS_NONE -> "NORMAL"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN ($v)"
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        try { Shizuku.unbindUserService(userServiceArgs, userServiceConnection, false) } catch (_: Throwable) {}
        remoteService = null
        serviceBinding = false
        super.onDestroy()
    }
}

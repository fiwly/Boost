package com.fiwly.aniimobooster

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {
    companion object {
        private const val ANIIMO = "com.x.aniimos"
        private const val SHIZUKU_REQUEST = 100
        private const val ORIGINAL_OVERLAY = "original_overlay"
    }
    private lateinit var status: TextView
    private lateinit var thermal: TextView
    private lateinit var result: TextView
    private lateinit var scale: Spinner
    private lateinit var fps: Spinner
    private lateinit var apply: Button

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
            if (checkReady()) runCommand("cmd game mode performance " + ANIIMO, "Performance mode enabled")
        }
        findViewById<Button>(R.id.standard).setOnClickListener { restoreDefault() }
        findViewById<Button>(R.id.launch).setOnClickListener { launchAniimo() }
        findViewById<Button>(R.id.refresh).setOnClickListener { updateStatus() }

        Shizuku.addBinderReceivedListenerSticky {
            runOnUiThread { updateStatus() }
        }
        Shizuku.addBinderDeadListener {
            runOnUiThread { updateStatus() }
        }
        Shizuku.addRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == SHIZUKU_REQUEST) {
                runOnUiThread { updateStatus() }
            }
        }

        updateStatus()
        requestShizukuIfNeeded()
        window.decorView.postDelayed({ updateStatus() }, 500)
        window.decorView.postDelayed({ updateStatus() }, 1500)
    }

    private fun setProfile(scalePosition: Int, fpsPosition: Int) {
        scale.setSelection(scalePosition)
        fps.setSelection(fpsPosition)
        applyProfile()
    }

    private fun isAniimoInstalled(): Boolean =
        try { packageManager.getPackageInfo(ANIIMO, 0); true }
        catch (_: PackageManager.NameNotFoundException) { false }

    private fun shizukuBinderAlive(): Boolean {
        return try {
            val binder = Shizuku.getBinder()
            binder != null && binder.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    private fun shizukuPermissionGranted(): Boolean {
        return try {
            shizukuBinderAlive() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    private fun checkReady(): Boolean {
        if (!isAniimoInstalled()) { toast("Aniimo is not installed"); return false }
        if (!shizukuBinderAlive()) { toast("Shizuku binder is not connected"); return false }
        if (!shizukuPermissionGranted()) {
            try {
                Shizuku.requestPermission(SHIZUKU_REQUEST)
            } catch (_: Throwable) {
                toast("Open Shizuku and authorize Aniimo Boost")
            }
            return false
        }
        return true
    }

    private fun requestShizukuIfNeeded() {
        if (!shizukuBinderAlive()) {
            result.text = "Shizuku binder: NOT CONNECTED"
            return
        }
        if (!shizukuPermissionGranted()) {
            try {
                Shizuku.requestPermission(SHIZUKU_REQUEST)
            } catch (_: Throwable) {
                result.text = "Shizuku is running, but Aniimo Boost is not authorized."
            }
        }
    }

    private fun applyProfile() {
        if (!checkReady()) return
        val s = when (scale.selectedItemPosition) {
            0 -> "0.70"; 1 -> "0.75"; 2 -> "0.80"; else -> "1.0"
        }
        val f = when (fps.selectedItemPosition) {
            0 -> "60"; 1 -> "45"; 2 -> "40"; else -> "30"
        }
        setBusy(true)
        runCommand("device_config get game_overlay " + ANIIMO) { output, code ->
            if (code != 0) {
                finishApply(s, f, "Could not read the current Game Overlay.")
                return@runCommand
            }
            val old = output.trim()
            if (!getPreferences(MODE_PRIVATE).contains(ORIGINAL_OVERLAY)) {
                getPreferences(MODE_PRIVATE).edit()
                    .putString(ORIGINAL_OVERLAY, if (old == "null" || old.isBlank()) "__NULL__" else old)
                    .apply()
            }
            val config = "mode=2,fps=" + f + ",downscaleFactor=" + s
            runCommand("device_config put game_overlay " + ANIIMO + " '" + config + "'") { _, putCode ->
                if (putCode != 0) {
                    finishApply(s, f, "Game Overlay write failed.")
                    return@runCommand
                }
                runCommand("cmd game mode performance " + ANIIMO) { _, modeCode ->
                    if (modeCode != 0)
                        finishApply(s, f, "Performance mode could not be enabled.")
                    else
                        finishApply(s, f, "Applied successfully. Fully restart Aniimo before testing.")
                }
            }
        }
    }

    private fun finishApply(s: String, f: String, message: String) {
        runOnUiThread {
            setBusy(false)
            result.text = "✓ " + message + "\nTarget: " + f + " FPS  •  Render: " + s + "x"
            updateStatus()
        }
    }

    private fun restoreDefault() {
        if (!checkReady()) return
        setBusy(true)
        val saved = getPreferences(MODE_PRIVATE).getString(ORIGINAL_OVERLAY, null)
        val command = if (saved == null || saved == "__NULL__")
            "device_config delete game_overlay " + ANIIMO
        else "device_config put game_overlay " + ANIIMO + " '" + saved + "'"

        runCommand(command) { _, code ->
            if (code != 0) {
                runOnUiThread { setBusy(false); result.text = "✕ Restore failed (exit " + code + ")." }
                return@runCommand
            }
            runCommand("cmd game mode standard " + ANIIMO) { _, modeCode ->
                getPreferences(MODE_PRIVATE).edit().remove(ORIGINAL_OVERLAY).apply()
                runOnUiThread {
                    setBusy(false)
                    result.text = if (modeCode == 0)
                        "✓ Restored Aniimo default settings."
                    else "✓ Overlay restored. Standard mode returned exit " + modeCode + "."
                    updateStatus()
                }
            }
        }
    }

    private fun launchAniimo() {
        if (!isAniimoInstalled()) { toast("Aniimo is not installed"); return }
        packageManager.getLaunchIntentForPackage(ANIIMO)?.let(::startActivity)
            ?: toast("Aniimo launch activity not found")
    }

    private fun runCommand(command: String, successMessage: String? = null, done: ((String, Int) -> Unit)? = null) {
        if (!shizukuPermissionGranted()) {
            toast("Shizuku permission is not ready")
            return
        }
        Thread {
            try {
                val method = Shizuku::class.java.getDeclaredMethod(
                    "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                method.isAccessible = true
                val process = method.invoke(null, arrayOf("sh", "-c", command), null, null)
                    as rikka.shizuku.ShizukuRemoteProcess
                val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
                val error = BufferedReader(InputStreamReader(process.errorStream)).readText()
                val code = process.waitFor()
                process.destroy()
                val combined = (output + error).trim()
                if (done != null) runOnUiThread { done(combined, code) }
                else runOnUiThread {
                    setBusy(false)
                    result.text = if (code == 0) "✓ " + (successMessage ?: "Command completed")
                    else "✕ Command failed (exit " + code + ")\n" + combined
                    updateStatus()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    result.text = "✕ Failed: " + (e.message ?: "unknown error")
                }
            }
        }.start()
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
        else -> "UNKNOWN (" + v + ")"
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}    private fun updateStatus() {
        val pm = getSystemService(PowerManager::class.java)
        val binder = shizukuBinderAlive()
        val permission = shizukuPermissionGranted()
        val shizukuState = when {
            !binder -> "NOT RUNNING / BINDER NOT CONNECTED"
            permission -> "RUNNING + AUTHORIZED"
            else -> "RUNNING / AUTHORIZATION NEEDED"
        }
        val gameState = if (isAniimoInstalled()) "INSTALLED" else "NOT INSTALLED"
        status.text = "ANIIMO  •  " + gameState + "\nAndroid " + Build.VERSION.RELEASE +
            " (API " + Build.VERSION.SDK_INT + ")\nShizuku: " + shizukuState +
            "\nBinder: " + if (binder) "CONNECTED" else "NOT CONNECTED" +
            "\nPermission: " + if (permission) "GRANTED" else "NOT GRANTED"
        thermal.text = if (Build.VERSION.SDK_INT >= 29)
            "Thermal: " + thermalName(pm.currentThermalStatus) else "Thermal: unavailable"
        result.text = if (getPreferences(MODE_PRIVATE).contains(ORIGINAL_OVERLAY))
            "A backup of the previous Game Overlay is saved."
        else "Ready. No custom profile backup yet."
    }

    private fun applyProfile() {
        if (!checkReady()) return
        val s = when (scale.selectedItemPosition) {
            0 -> "0.70"; 1 -> "0.75"; 2 -> "0.80"; else -> "1.0"
        }
        val f = when (fps.selectedItemPosition) {
            0 -> "60"; 1 -> "45"; 2 -> "40"; else -> "30"
        }
        setBusy(true)
        runCommand("device_config get game_overlay " + ANIIMO) { output, code ->
            if (code != 0) {
                finishApply(s, f, "Could not read the current Game Overlay.")
                return@runCommand
            }
            val old = output.trim()
            if (!getPreferences(MODE_PRIVATE).contains(ORIGINAL_OVERLAY)) {
                getPreferences(MODE_PRIVATE).edit()
                    .putString(ORIGINAL_OVERLAY, if (old == "null" || old.isBlank()) "__NULL__" else old)
                    .apply()
            }
            val config = "mode=2,fps=" + f + ",downscaleFactor=" + s
            runCommand("device_config put game_overlay " + ANIIMO + " '" + config + "'") { _, putCode ->
                if (putCode != 0) {
                    finishApply(s, f, "Game Overlay write failed.")
                    return@runCommand
                }
                runCommand("cmd game mode performance " + ANIIMO) { _, modeCode ->
                    if (modeCode != 0)
                        finishApply(s, f, "Performance mode could not be enabled.")
                    else
                        finishApply(s, f, "Applied successfully. Fully restart Aniimo before testing.")
                }
            }
        }
    }

    private fun finishApply(s: String, f: String, message: String) {
        runOnUiThread {
            setBusy(false)
            result.text = "✓ " + message + "\nTarget: " + f + " FPS  •  Render: " + s + "x"
            updateStatus()
        }
    }

    private fun restoreDefault() {
        if (!checkReady()) return
        setBusy(true)
        val saved = getPreferences(MODE_PRIVATE).getString(ORIGINAL_OVERLAY, null)
        val command = if (saved == null || saved == "__NULL__")
            "device_config delete game_overlay " + ANIIMO
        else "device_config put game_overlay " + ANIIMO + " '" + saved + "'"

        runCommand(command) { _, code ->
            if (code != 0) {
                runOnUiThread { setBusy(false); result.text = "✕ Restore failed (exit " + code + ")." }
                return@runCommand
            }
            runCommand("cmd game mode standard " + ANIIMO) { _, modeCode ->
                getPreferences(MODE_PRIVATE).edit().remove(ORIGINAL_OVERLAY).apply()
                runOnUiThread {
                    setBusy(false)
                    result.text = if (modeCode == 0)
                        "✓ Restored Aniimo default settings."
                    else "✓ Overlay restored. Standard mode returned exit " + modeCode + "."
                    updateStatus()
                }
            }
        }
    }

    private fun launchAniimo() {
        if (!isAniimoInstalled()) { toast("Aniimo is not installed"); return }
        packageManager.getLaunchIntentForPackage(ANIIMO)?.let(::startActivity)
            ?: toast("Aniimo launch activity not found")
    }

    private fun runCommand(command: String, successMessage: String? = null, done: ((String, Int) -> Unit)? = null) {
        if (!Shizuku.pingBinder() ||
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            toast("Shizuku permission is not ready")
            return
        }
        Thread {
            try {
                val method = Shizuku::class.java.getDeclaredMethod(
                    "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                method.isAccessible = true
                val process = method.invoke(null, arrayOf("sh", "-c", command), null, null)
                    as rikka.shizuku.ShizukuRemoteProcess
                val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
                val error = BufferedReader(InputStreamReader(process.errorStream)).readText()
                val code = process.waitFor()
                process.destroy()
                val combined = (output + error).trim()
                if (done != null) runOnUiThread { done(combined, code) }
                else runOnUiThread {
                    setBusy(false)
                    result.text = if (code == 0) "✓ " + (successMessage ?: "Command completed")
                    else "✕ Command failed (exit " + code + ")\n" + combined
                    updateStatus()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    result.text = "✕ Failed: " + (e.message ?: "unknown error")
                }
            }
        }.start()
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
        else -> "UNKNOWN (" + v + ")"
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

package com.fiwly.aniimobooster

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
    }
    private lateinit var status: TextView
    private lateinit var thermal: TextView
    private lateinit var scale: Spinner
    private lateinit var fps: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        thermal = findViewById(R.id.thermal)
        scale = findViewById(R.id.scale)
        fps = findViewById(R.id.fps)

        scale.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("0.70x - Max FPS", "0.75x - Balanced", "0.80x - Cooler", "1.00x - Native"))
        fps.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("60 FPS", "45 FPS", "40 FPS", "30 FPS"))

        findViewById<Button>(R.id.performance).setOnClickListener {
            runPrivileged("cmd game mode performance " + ANIIMO)
        }
        findViewById<Button>(R.id.standard).setOnClickListener {
            runPrivileged("cmd game mode standard " + ANIIMO)
        }
        findViewById<Button>(R.id.apply).setOnClickListener { applyProfile() }
        findViewById<Button>(R.id.launch).setOnClickListener {
            packageManager.getLaunchIntentForPackage(ANIIMO)?.let(::startActivity) ?: toast("Aniimo not found")
        }
        updateStatus()
        requestShizukuIfNeeded()
    }

    private fun requestShizukuIfNeeded() {
        if (!Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SHIZUKU_REQUEST) updateStatus()
    }

    private fun updateStatus() {
        val pm = getSystemService(PowerManager::class.java)
        status.text = "Aniimo: " + ANIIMO + "\nAndroid " + Build.VERSION.RELEASE +
                " (API " + Build.VERSION.SDK_INT + ")\nPower saver: " +
                if (pm.isPowerSaveMode) "ON" else "OFF" +
                "\nShizuku: " + if (Shizuku.pingBinder()) "connected" else "not connected"
        if (Build.VERSION.SDK_INT >= 29) {
            thermal.text = "Thermal status: " + thermalName(pm.currentThermalStatus)
        }
    }

    private fun thermalName(v: Int) = when (v) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN (" + v + ")"
    }

    private fun applyProfile() {
        if (!Shizuku.pingBinder() ||
            Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("Start Shizuku and grant permission first")
            return
        }
        val s = when (scale.selectedItemPosition) {
            0 -> "0.70"; 1 -> "0.75"; 2 -> "0.80"; else -> "1.0"
        }
        val f = when (fps.selectedItemPosition) {
            0 -> "60"; 1 -> "45"; 2 -> "40"; else -> "30"
        }
        val config = "mode=2,fps=" + f + ",downscaleFactor=" + s
        runPrivileged("device_config put game_overlay " + ANIIMO + " '" + config + "'") {
            runPrivileged("cmd game mode performance " + ANIIMO) {
                toast("Applied. Fully restart Aniimo before testing.")
            }
        }
    }

    private fun runPrivileged(command: String, done: (() -> Unit)? = null) {
        if (!Shizuku.pingBinder() ||
            Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("Shizuku permission is not ready")
            return
        }
        Thread {
            try {
                val p = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
                val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
                val err = BufferedReader(InputStreamReader(p.errorStream)).readText()
                val code = p.waitFor()
                runOnUiThread {
                    status.text = "exit=" + code + "\n" + (out + err).trim()
                    done?.invoke()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Failed: " + e.message) }
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
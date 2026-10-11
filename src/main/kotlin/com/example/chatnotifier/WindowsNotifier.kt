package com.example.chatnotifier

import java.awt.Color
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

object WindowsNotifier {
    private val logger = LoggerFactory.getLogger("chat_notifier")
    private const val TITLE = "Minecraft chat alert"

    fun show(message: String, onError: (String) -> Unit) {
        try {
            Thread {
                try {
                    if (showWindowsToast(message)) return@Thread
                    if (showWindowsBalloon(message)) return@Thread
                    if (showTrayNotification(message)) return@Thread
                    onError("Windows notifications are unavailable")
                } catch (exception: Exception) {
                    logger.warn("Unable to show Windows notification", exception)
                    onError(exception.message?.takeIf { it.isNotBlank() } ?: "Windows notification failed")
                }
            }.apply {
                name = "chat-notifier-windows-notification"
                isDaemon = true
                start()
            }
        } catch (exception: Exception) {
            logger.warn("Unable to start Windows notification", exception)
            onError(exception.message?.takeIf { it.isNotBlank() } ?: "Unable to start notification")
        }
    }

    private fun showWindowsToast(message: String): Boolean {
        val script = """
            [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
            [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null
            ${'$'}xml = New-Object Windows.Data.Xml.Dom.XmlDocument
            ${'$'}xml.LoadXml("<toast><visual><binding template='ToastGeneric'><text>${escapeXml(TITLE)}</text><text>${escapeXml(message)}</text></binding></visual></toast>")
            ${'$'}toast = [Windows.UI.Notifications.ToastNotification]::new(${'$'}xml)
            [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier().Show(${'$'}toast)
        """.trimIndent()

        return runPowerShell(script, "Native Windows toast notification")
    }

    private fun showWindowsBalloon(message: String): Boolean {
        val script = """
            Add-Type -AssemblyName System.Windows.Forms
            Add-Type -AssemblyName System.Drawing
            ${'$'}icon = New-Object System.Windows.Forms.NotifyIcon
            ${'$'}icon.Icon = [System.Drawing.SystemIcons]::Information
            ${'$'}icon.Visible = ${'$'}true
            ${'$'}icon.ShowBalloonTip(8000, '${escapePowerShell(TITLE)}', '${escapePowerShell(message)}', [System.Windows.Forms.ToolTipIcon]::Info)
            Start-Sleep -Seconds 8
            ${'$'}icon.Dispose()
        """.trimIndent()

        return runPowerShell(script, "Windows balloon notification")
    }

    private fun runPowerShell(script: String, description: String): Boolean {
        return try {
            val encodedScript = Base64.getEncoder().encodeToString(
                script.toByteArray(StandardCharsets.UTF_16LE)
            )
            val process = ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-WindowStyle",
                "Hidden",
                "-ExecutionPolicy",
                "Bypass",
                "-EncodedCommand",
                encodedScript
            ).redirectErrorStream(true).start()
            val completed = process.waitFor(10, TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader().readText().trim()
            if (!completed || process.exitValue() != 0) {
                logger.warn("$description failed: ${output.ifBlank { "PowerShell exited with an error" }}")
                false
            } else {
                true
            }
        } catch (exception: Exception) {
            logger.warn("$description is unavailable", exception)
            false
        }
    }

    private fun showTrayNotification(message: String): Boolean {
        if (!SystemTray.isSupported()) {
            logger.warn("Windows tray notification unavailable because the Java system tray is unsupported")
            return false
        }

        var trayIcon: TrayIcon? = null
        return try {
                val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                graphics.color = Color(0x5B, 0x8F, 0xD9)
                graphics.fillRect(0, 0, image.width, image.height)
                graphics.dispose()

                val icon = TrayIcon(image, TITLE).apply {
                    isImageAutoSize = true
                }
                trayIcon = icon
                SystemTray.getSystemTray().add(icon)
                icon.displayMessage(TITLE, message, TrayIcon.MessageType.INFO)
                Thread.sleep(8_000)
                true
            } catch (exception: Exception) {
                logger.warn("Unable to show Windows tray notification", exception)
                false
            } finally {
                trayIcon?.let {
                    try {
                        SystemTray.getSystemTray().remove(it)
                    } catch (exception: Exception) {
                        logger.warn("Unable to remove the Windows notification tray icon", exception)
                    }
                }
            }
    }

    private fun escapeXml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun escapePowerShell(value: String): String =
        value.replace("'", "''")
}

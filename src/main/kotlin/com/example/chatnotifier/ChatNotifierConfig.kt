package com.example.chatnotifier

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory

data class NotificationRule(
    var triggerRegex: String,
    var displayText: String,
    var cooldownSeconds: Long
) {
    @Transient
    var lastNotificationAt: Long = 0L
    @Transient
    var triggerPattern: Pattern? = compilePattern(triggerRegex)
    @Transient
    var plainTriggerPattern: Pattern? = compilePlainPattern(triggerRegex)

    fun updateTriggerRegex(value: String): Boolean {
        if (value.isBlank()) return false
        val pattern = try {
            Pattern.compile(value)
        } catch (_: PatternSyntaxException) {
            return false
        }

        triggerRegex = value
        triggerPattern = pattern
        plainTriggerPattern = compilePlainPattern(value)
        return true
    }

    companion object {
        private fun compilePattern(regex: String): Pattern? =
            try {
                if (regex.isBlank()) null else Pattern.compile(regex)
            } catch (_: PatternSyntaxException) {
                null
            }

        private fun compilePlainPattern(regex: String): Pattern? =
            compilePattern(LEGACY_FORMATTING_PATTERN.matcher(regex).replaceAll(""))
    }
}

class ChatNotifierConfig private constructor(
    val rules: MutableList<NotificationRule>
) {
    private val logger = LoggerFactory.getLogger(LOGGER_NAME)

    fun notifyIfMatching(message: String, onError: (String) -> Unit) {
        val now = System.currentTimeMillis()
        rules.forEachIndexed { index, rule ->
            try {
                val matchesFormatted = rule.triggerPattern?.matcher(message)?.find() == true
                val matchesPlain = rule.plainTriggerPattern?.matcher(message)?.find() == true
                if (!matchesFormatted && !matchesPlain) return@forEachIndexed
                if (now - rule.lastNotificationAt < rule.cooldownSeconds.coerceAtLeast(0) * 1_000) {
                    return@forEachIndexed
                }
                val notificationText = LEGACY_FORMATTING_PATTERN.matcher(rule.displayText).replaceAll("")
                if (notificationText.isBlank()) {
                    logger.warn("Chat matched notification rule {}, but its display text is blank", index + 1)
                    onError("Notification ${index + 1} has blank display text")
                    return@forEachIndexed
                }
                rule.lastNotificationAt = now
                recordNotification(index, message, notificationText)
                WindowsNotifier.show(notificationText) { error ->
                    onError("Notification ${index + 1}: $error")
                }
            } catch (exception: Exception) {
                logger.warn("Unable to process notification rule {}", index + 1, exception)
                onError("Notification ${index + 1} failed: ${exception.userMessage()}")
            }
        }
    }

    private fun recordNotification(ruleIndex: Int, message: String, notificationText: String) {
        try {
            notificationLogPath.parent.createDirectories()
            Files.writeString(
                notificationLogPath,
                "${LocalDateTime.now().format(notificationTimeFormat)} [rule ${ruleIndex + 1}] ${logValue(notificationText)} - ${logValue(message)}${System.lineSeparator()}",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        } catch (exception: IOException) {
            logger.warn("Unable to record notification time", exception)
        }
    }

    private fun logValue(value: String): String =
        value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n")

    private fun Exception.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    fun save() {
        configPath.parent.createDirectories()
        val properties = Properties()
        properties["notification.count"] = rules.size.toString()
        rules.forEachIndexed { index, rule ->
            properties["notification.$index.trigger.regex"] = rule.triggerRegex
            properties["notification.$index.display.text"] = rule.displayText
            properties["notification.$index.cooldown.seconds"] = rule.cooldownSeconds.coerceAtLeast(0).toString()
        }
        Files.newOutputStream(
            configPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        ).use { properties.store(it, "Chat Notifier configuration") }
    }

    companion object {
        private const val LOGGER_NAME = "chat_notifier"
        private val configDirectory = FabricLoader.getInstance().gameDir.resolve("config")
        private val configPath = configDirectory.resolve("chat-notifier.properties")
        private val notificationLogPath = configDirectory.resolve("chat-notifier-notifications.txt")
        private val notificationTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun load(): ChatNotifierConfig {
            configPath.parent.createDirectories()
            val properties = Properties()
            if (configPath.exists()) {
                Files.newInputStream(configPath).use(properties::load)
            } else {
                properties["trigger.regex"] = ""
                properties["display.text"] = NotificationDefaults.displayText
                properties["notification.cooldown.seconds"] = NotificationDefaults.cooldownSeconds.toString()
                Files.newOutputStream(
                    configPath,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
                ).use { properties.store(it, "Chat Notifier configuration") }
            }

            val count = properties.getProperty("notification.count")?.toIntOrNull()
            val rules = if (count != null && count > 0) {
                (0 until count).mapNotNull { index -> ruleFromProperties(properties, index) }
            } else {
                listOf(legacyRule(properties))
            }.toMutableList()
            return ChatNotifierConfig(
                if (rules.isEmpty()) {
                    mutableListOf(NotificationRule("", NotificationDefaults.displayText, NotificationDefaults.cooldownSeconds))
                } else {
                    rules
                }
            )
        }

        private fun ruleFromProperties(properties: Properties, index: Int): NotificationRule? {
            val prefix = "notification.$index."
            val trigger = properties.getProperty("${prefix}trigger.regex") ?: return null
            return NotificationRule(
                trigger,
                properties.getProperty("${prefix}display.text", NotificationDefaults.displayText).trim(),
                properties.getProperty("${prefix}cooldown.seconds", NotificationDefaults.cooldownSeconds.toString()).toLongOrNull()?.coerceAtLeast(0) ?: NotificationDefaults.cooldownSeconds
            )
        }

        private fun legacyRule(properties: Properties): NotificationRule =
            NotificationRule(
                properties.getProperty("trigger.regex", properties.getProperty("trigger.message", "")).trim(),
                properties.getProperty(
                    "display.text",
                    properties.getProperty("notification.title", NotificationDefaults.displayText)
                ).trim(),
                properties.getProperty(
                    "notification.cooldown.seconds",
                    NotificationDefaults.cooldownSeconds.toString()
                ).toLongOrNull()?.coerceAtLeast(0) ?: NotificationDefaults.cooldownSeconds
            )

        fun default(): ChatNotifierConfig =
            ChatNotifierConfig(mutableListOf(NotificationRule("", NotificationDefaults.displayText, NotificationDefaults.cooldownSeconds)))
    }
}

internal object NotificationDefaults {
    const val displayText = "Minecraft chat alert"
    const val cooldownSeconds = 10L
}

private val LEGACY_FORMATTING_PATTERN = Pattern.compile("§.")

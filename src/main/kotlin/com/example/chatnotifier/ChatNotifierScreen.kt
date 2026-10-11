package com.example.chatnotifier

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class ChatNotifierScreen(
    private val parent: Screen?,
    private val config: ChatNotifierConfig,
    originalRules: List<NotificationRule>? = null,
    draftRules: List<NotificationRule>? = null
) : Screen(Component.literal("Chat Notifier")) {
    private val fields = mutableListOf<RuleFields>()
    private val originalRules = (originalRules ?: config.rules).map { it.copy() }
    private val workingRules = (draftRules ?: config.rules).map { it.copy() }.toMutableList()
    private var scrollOffset = 0

    override fun init() {
        val center = width / 2
        fields.clear()
        addRenderableOnly(StringWidget(width / 2 - 55, 10, 110, 16, title, font))
        workingRules.forEachIndexed { index, rule ->
            val y = ruleY(index)
            val number = StringWidget(
                center - FIELD_OFFSET - RULE_NUMBER_WIDTH - RULE_NUMBER_GAP,
                y + TRIGGER_INPUT_OFFSET,
                RULE_NUMBER_WIDTH,
                16,
                Component.literal("Rule ${index + 1}:"),
                font
            )
            val triggerLabel = StringWidget(center - FIELD_OFFSET, y, TRIGGER_WIDTH, 12, Component.literal("Trigger message"), font)
            val displayLabel = StringWidget(center - FIELD_OFFSET, y + DISPLAY_LABEL_OFFSET, TRIGGER_WIDTH, 16, Component.literal("Display text"), font)
            val cooldownLabel = StringWidget(center + COOLDOWN_X_OFFSET, y, COOLDOWN_WIDTH, 12, Component.literal("Cooldown"), font)
            addRenderableOnly(number)
            addRenderableOnly(triggerLabel)
            addRenderableOnly(displayLabel)
            addRenderableOnly(cooldownLabel)
            val trigger = createField(center - FIELD_OFFSET, y + TRIGGER_INPUT_OFFSET, TRIGGER_WIDTH, "Trigger message", rule.triggerRegex, MAX_TRIGGER_LENGTH)
            val display = createField(center - FIELD_OFFSET, y + DISPLAY_INPUT_OFFSET, TRIGGER_WIDTH, "Display text", rule.displayText, MAX_DISPLAY_LENGTH)
            val cooldown = createField(center + COOLDOWN_X_OFFSET, y + TRIGGER_INPUT_OFFSET, COOLDOWN_WIDTH, "Seconds", rule.cooldownSeconds.toString(), MAX_COOLDOWN_LENGTH)
            val remove = Button.builder(Component.literal("Remove")) {
                if (workingRules.size > 1) {
                    syncFieldsToWorkingRules()
                    val draftRules = workingRules.map { it.copy() }.toMutableList()
                    draftRules.removeAt(index)
                    reopen(draftRules)
                }
            }.bounds(center + COOLDOWN_X_OFFSET, y + DISPLAY_INPUT_OFFSET, COOLDOWN_WIDTH, 20).build()
            addRenderableWidget(remove)
            fields += RuleFields(
                rule,
                trigger,
                display,
                cooldown,
                remove,
                number,
                triggerLabel,
                displayLabel,
                cooldownLabel
            )
        }

        val addButton = Button.builder(Component.literal("Add notification")) {
            if (workingRules.size < MAX_RULES) {
                syncFieldsToWorkingRules()
                val draftRules = workingRules.map { it.copy() }.toMutableList()
                draftRules += NotificationRule(
                    "",
                    NotificationDefaults.displayText,
                    NotificationDefaults.cooldownSeconds
                )
                reopen(draftRules)
            }
        }.bounds(center - BUTTON_OFFSET, height - BUTTONS_BOTTOM, BUTTON_WIDTH, 20).build()
        addRenderableWidget(addButton)
        val saveButton = Button.builder(Component.literal("Save"), ::saveAndClose)
            .bounds(center + BUTTON_GAP, height - BUTTONS_BOTTOM, BUTTON_WIDTH, 20)
            .build()
        addRenderableWidget(saveButton)
        updateScroll()
        fields.firstOrNull()?.trigger?.let(::setInitialFocus)
    }

    private fun createField(x: Int, y: Int, width: Int, hint: String, value: String, maxLength: Int): EditBox {
        val field = EditBox(font, x, y, width, 20, Component.literal(hint))
        field.value = value
        field.setMaxLength(maxLength)
        addRenderableWidget(field)
        return field
    }

    private fun ruleY(index: Int): Int =
        FIRST_RULE_Y + index * RULE_HEIGHT - scrollOffset

    private fun updateScroll() {
        val maxScroll = ((workingRules.size - 1) * RULE_HEIGHT + RULE_BOTTOM - contentBottom).coerceAtLeast(0)
        scrollOffset = scrollOffset.coerceIn(0, maxScroll)
        fields.forEachIndexed { index, input ->
            val y = ruleY(index)
            input.trigger.y = y + TRIGGER_INPUT_OFFSET
            input.display.y = y + DISPLAY_INPUT_OFFSET
            input.cooldown.y = y + TRIGGER_INPUT_OFFSET
            input.remove.y = y + DISPLAY_INPUT_OFFSET
            input.number.setX(width / 2 - FIELD_OFFSET - RULE_NUMBER_WIDTH - RULE_NUMBER_GAP)
            input.number.setY(y + TRIGGER_INPUT_OFFSET)
            input.triggerLabel.setY(y)
            input.displayLabel.setY(y + DISPLAY_LABEL_OFFSET)
            input.cooldownLabel.setY(y)
            val visible = y >= CONTENT_TOP && y + RULE_BOTTOM <= contentBottom
            input.trigger.visible = visible
            input.display.visible = visible
            input.cooldown.visible = visible
            input.remove.visible = visible
            input.number.visible = visible
            input.triggerLabel.visible = visible
            input.displayLabel.visible = visible
            input.cooldownLabel.visible = visible
        }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (mouseY < CONTENT_TOP || mouseY > contentBottom) return false
        val previous = scrollOffset
        scrollOffset -= (scrollY * SCROLL_STEP).toInt()
        updateScroll()
        return previous != scrollOffset
    }

    private fun saveAndClose(button: Button) {
        syncFieldsToWorkingRules()
        val updatedRules = mutableListOf<NotificationRule>()
        workingRules.forEachIndexed { index, draft ->
            val input = fields[index]
            val trigger = draft.triggerRegex.trim()
            if (trigger.isBlank()) {
                input.trigger.setSuggestion("Enter a trigger")
                return
            }
            val cooldown = input.cooldown.value.toLongOrNull()
            if (cooldown == null || cooldown < 0) {
                input.cooldown.setSuggestion("Enter a number")
                return
            }
            val updatedRule = draft.copy()
            if (!updatedRule.updateTriggerRegex(trigger)) {
                input.trigger.setSuggestion("Invalid regular expression")
                return
            }
            updatedRule.displayText = draft.displayText.trim()
            updatedRule.cooldownSeconds = cooldown
            updatedRules += updatedRule
        }
        try {
            config.rules.clear()
            config.rules += updatedRules
            config.save()
            minecraft.gui.setScreen(parent)
        } catch (exception: Exception) {
            logger.warn("Unable to save Chat Notifier settings", exception)
            config.rules.clear()
            config.rules += originalRules.map { it.copy() }
            minecraft.gui.hud.setOverlayMessage(
                Component.literal("Chat Notifier error: unable to save settings: ${exception.userMessage()}"),
                false
            )
        }
    }

    override fun onClose() {
        restoreOriginalRules()
        minecraft.gui.setScreen(parent)
    }

    private fun reopen(draftRules: List<NotificationRule>) {
        minecraft.gui.setScreen(ChatNotifierScreen(parent, config, originalRules, draftRules))
    }

    private fun syncFieldsToWorkingRules() {
        fields.forEachIndexed { index, input ->
            val rule = workingRules[index]
            rule.triggerRegex = input.trigger.value
            rule.displayText = input.display.value
            input.cooldown.value.toLongOrNull()?.takeIf { it >= 0 }?.let {
                rule.cooldownSeconds = it
            }
            rule.updateTriggerRegex(rule.triggerRegex)
        }
    }

    private fun restoreOriginalRules() {
        config.rules.clear()
        config.rules += originalRules.map { it.copy() }
    }

    private fun Exception.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        delta: Float
    ) {
        super.extractRenderState(graphics, mouseX, mouseY, delta)
        if (workingRules.size > VISIBLE_RULES) {
            val trackX = width / 2 + SCROLLBAR_X_OFFSET
            val trackHeight = contentBottom - CONTENT_TOP
            val contentHeight = workingRules.size * RULE_HEIGHT
            val thumbHeight = (trackHeight * trackHeight / contentHeight).coerceAtLeast(SCROLLBAR_MIN_THUMB)
            val maxScroll = (contentHeight - trackHeight).coerceAtLeast(1)
            val thumbY = CONTENT_TOP + (trackHeight - thumbHeight) * scrollOffset / maxScroll
            graphics.fill(trackX, CONTENT_TOP, trackX + SCROLLBAR_WIDTH, contentBottom, 0x66000000)
            graphics.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbHeight, 0xFFFFFFFF.toInt())
        }
    }

    private data class RuleFields(
        val rule: NotificationRule,
        val trigger: EditBox,
        val display: EditBox,
        val cooldown: EditBox,
        val remove: Button,
        val number: StringWidget,
        val triggerLabel: StringWidget,
        val displayLabel: StringWidget,
        val cooldownLabel: StringWidget
    )

    companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger("chat_notifier")
        private const val MAX_RULES = 50
        private const val FIRST_RULE_Y = 45
        private const val RULE_HEIGHT = 90
        private const val BUTTONS_BOTTOM = 25
        private const val BUTTON_HEIGHT = 20
        private const val CONTENT_BOTTOM_MARGIN = 10
        private const val CONTENT_TOP = 30
        private const val RULE_BOTTOM = 84
        private const val SCROLL_STEP = 20
        private const val RULE_NUMBER_WIDTH = 55
        private const val RULE_NUMBER_GAP = 12
        private const val DISPLAY_LABEL_OFFSET = 37
        private const val DISPLAY_INPUT_OFFSET = 49
        private const val SCROLLBAR_X_OFFSET = 160
        private const val SCROLLBAR_WIDTH = 4
        private const val SCROLLBAR_MIN_THUMB = 12
        private const val VISIBLE_RULES = 5
        private const val TRIGGER_INPUT_OFFSET = 12
        private const val FIELD_OFFSET = 150
        private const val COOLDOWN_X_OFFSET = 70
        private const val COOLDOWN_WIDTH = 80
        private const val TRIGGER_WIDTH = 210
        private const val BUTTON_OFFSET = 154
        private const val BUTTON_GAP = 4
        private const val BUTTON_WIDTH = 150
        private const val MAX_TRIGGER_LENGTH = 512
        private const val MAX_DISPLAY_LENGTH = 256
        private const val MAX_COOLDOWN_LENGTH = 6
    }

    private val contentBottom: Int
        get() = height - BUTTONS_BOTTOM - BUTTON_HEIGHT - CONTENT_BOTTOM_MARGIN
}

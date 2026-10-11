package com.example.chatnotifier

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping.Category
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory

class ChatNotifierClient : ClientModInitializer {
    override fun onInitializeClient() {
        val config = try {
            ChatNotifierConfig.load()
        } catch (exception: Exception) {
            ChatNotifierConfig.default().also {
                logger.warn("Unable to load Chat Notifier configuration", exception)
            }
        }
        val category = Category.register(Identifier.fromNamespaceAndPath("chat_notifier", "main"))
        val openConfigKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.chat_notifier.open_config",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_N,
                category
            )
        )
        ClientReceiveMessageEvents.CHAT.register { message: Component, _, _, _, _ ->
            safelyNotify(config, message.string)
        }
        ClientReceiveMessageEvents.GAME.register { message: Component, _ ->
            safelyNotify(config, message.string)
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (openConfigKey.consumeClick()) {
                client.gui.setScreen(ChatNotifierScreen(client.gui.screen(), config))
            }
        }
    }

    private fun safelyNotify(config: ChatNotifierConfig, message: String) {
        try {
            config.notifyIfMatching(message) { error -> showError(error) }
        } catch (exception: Exception) {
            logger.warn("Chat Notifier failed while processing a message", exception)
            showError("Chat Notifier failed: ${exception.message ?: exception.javaClass.simpleName}")
        }
    }

    private fun showError(message: String) {
        Minecraft.getInstance().execute {
            Minecraft.getInstance().gui.hud.setOverlayMessage(
                Component.literal("Chat Notifier error: $message"),
                false
            )
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger("chat_notifier")
    }
}

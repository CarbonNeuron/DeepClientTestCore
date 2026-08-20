package dev.deepclient;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DeepClientMod implements ClientModInitializer {
    public static final String MOD_ID = "deepclient";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private DeepClientController controller;
    private DeepClientHttpServer server;

    @Override
    public void onInitializeClient() {
        DeepClientConfig config = DeepClientConfig.fromEnvironment();
        controller = new DeepClientController(config);
        server = new DeepClientHttpServer(config, controller);
        server.start();

        ClientTickEvents.END_CLIENT_TICK.register(controller::onEndTick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
                controller.recordMessage("game", message.getString(), overlay, null, null));
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receivedAt) ->
                controller.recordMessage("chat", message.getString(), false,
                        sender == null ? null : sender.getName(),
                        sender == null ? null : sender.getId().toString()));
        ClientLifecycleEvents.CLIENT_STOPPING.register(this::stop);
        LOGGER.info("Deep Client API listening on http://{}:{}", config.bindAddress(), config.port());
    }

    private void stop(MinecraftClient ignored) {
        if (controller != null) controller.releaseAll();
        if (server != null) server.stop();
    }
}

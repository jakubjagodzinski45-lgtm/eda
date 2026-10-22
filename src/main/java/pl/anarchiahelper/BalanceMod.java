package pl.anarchiahelper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import org.lwjgl.glfw.GLFW;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BalanceMod implements ClientModInitializer {

    // ================= USTAWIENIA (zmien pod serwer) =================

    /** Komenda sprawdzajaca stan konta, BEZ ukosnika. %s = nick gracza. */
    static final String COMMAND = "bal %s";

    /** Regex wyciagajacy kwote z odpowiedzi serwera (pierwsza grupa). */
    static final Pattern BALANCE_PATTERN = Pattern.compile("(\\d[\\d\\s.,]*\\s?[kKmMbB]?)");

    /** Czy ukrywac odpowiedz serwera na czacie. */
    static final boolean HIDE_REPLY = true;

    /** Minimalny odstep miedzy zapytaniami (ms) - ochrona przed spamem komendami. */
    static final long COOLDOWN_MS = 2000;

    /** Ile ms czekamy na odpowiedz serwera. */
    static final long REPLY_TIMEOUT_MS = 3000;

    /** Jak dlugo wynik wisi na ekranie (ms). */
    static final long DISPLAY_MS = 5000;

    // =================================================================

    private boolean middleWasDown = false;
    private long lastRequest = 0;

    private String pendingNick = null;
    private long pendingUntil = 0;

    private String resultText = null;
    private long resultUntil = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientReceiveMessageEvents.ALLOW_GAME.register(this::onGameMessage);
        HudRenderCallback.EVENT.register(this::onHud);
    }

    private void onTick(MinecraftClient client) {
        if (client.player == null || client.currentScreen != null) {
            middleWasDown = false;
            return;
        }
        boolean down = GLFW.glfwGetMouseButton(
                client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
        if (down && !middleWasDown) {
            onMiddleClick(client);
        }
        middleWasDown = down;
    }

    private void onMiddleClick(MinecraftClient client) {
        if (!(client.crosshairTarget instanceof EntityHitResult hit)) return;
        if (!(hit.getEntity() instanceof PlayerEntity target)) return;

        long now = System.currentTimeMillis();
        if (now - lastRequest < COOLDOWN_MS) return;
        lastRequest = now;

        String nick = target.getName().getString();
        pendingNick = nick;
        pendingUntil = now + REPLY_TIMEOUT_MS;
        client.player.networkHandler.sendChatCommand(String.format(COMMAND, nick));
    }

    /** Zwraca false = wiadomosc nie pojawi sie na czacie. */
    private boolean onGameMessage(net.minecraft.text.Text message, boolean overlay) {
        if (overlay || pendingNick == null) return true;

        long now = System.currentTimeMillis();
        if (now > pendingUntil) {
            pendingNick = null;
            return true;
        }

        String text = message.getString();
        Matcher m = BALANCE_PATTERN.matcher(text);
        if (!m.find()) return true; // to nie byla odpowiedz z kwota

        resultText = pendingNick + ": " + m.group(1).trim();
        resultUntil = now + DISPLAY_MS;
        pendingNick = null;
        return !HIDE_REPLY;
    }

    private void onHud(net.minecraft.client.gui.DrawContext ctx,
                       net.minecraft.client.render.RenderTickCounter tickCounter) {
        if (resultText == null) return;
        if (System.currentTimeMillis() > resultUntil) {
            resultText = null;
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        int x = mc.getWindow().getScaledWidth() / 2;
        ctx.drawCenteredTextWithShadow(mc.textRenderer, resultText, x, 20, 0xFFFFAA00);
    }
}

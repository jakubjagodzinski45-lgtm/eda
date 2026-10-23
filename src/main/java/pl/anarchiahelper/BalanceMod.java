package pl.anarchiahelper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BalanceMod implements ClientModInitializer {

    // ================= USTAWIENIA (zmien pod serwer) =================

    /** Komenda BEZ ukosnika. %s = nick gracza. */
    static final String COMMAND = "stats %s";

    /** Zasieg celowania (w blokach). Mod widzi tylko graczy zaladowanych u Ciebie w grze. */
    static final double MAX_DISTANCE = 256;

    /** Promien "trafienia" wokol srodka postaci (w blokach). Pokrywa cale cialo, od stop po glowe. */
    static final double BODY_RADIUS = 1.1;

    /** Dodatkowa tolerancja na kazdy blok odleglosci - ulatwia trafianie w dalekich graczy. */
    static final double AIM_TOLERANCE_PER_BLOCK = 0.02;

    /** Linia z GUI /stats, ktora uznajemy za stan konta (sprawdzane bez wielkosci liter). */
    static final Pattern MONEY_LINE = Pattern.compile(
            "(pieni|got[oó]wk|saldo|stan konta|balance|money|monet|\\$|zł)",
            Pattern.CASE_INSENSITIVE);

    /** Wyciaga sama kwote z linii typu "Stan konta: 46.67k$". */
    static final Pattern MONEY_VALUE = Pattern.compile("stan konta:?\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    /** true = wypisz na Twoim czacie (tylko u Ciebie) wszystkie linie z GUI /stats. */
    static final boolean DEBUG = false;

    static final long COOLDOWN_MS = 2000;      // odstep miedzy zapytaniami
    static final long REPLY_TIMEOUT_MS = 3000; // ile czekamy az GUI sie otworzy
    static final long DISPLAY_MS = 8000;       // jak dlugo wynik wisi na ekranie

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
        HudRenderCallback.EVENT.register(this::onHud);
    }

    private void onTick(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            middleWasDown = false;
            pendingNick = null;
            return;
        }

        // 1) czekamy na GUI z /stats, czytamy je i od razu zamykamy
        if (pendingNick != null) {
            if (System.currentTimeMillis() > pendingUntil) {
                pendingNick = null;
            } else if (client.currentScreen instanceof HandledScreen<?> screen) {
                handleStatsScreen(client, screen);
            }
        }

        // 2) wykrywanie klikniecia scrolla (tylko gdy nic nie jest otwarte)
        if (client.currentScreen != null) {
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
        if (pendingNick != null) return;

        PlayerEntity target = findTarget(client);
        if (target == null) return;

        long now = System.currentTimeMillis();
        if (now - lastRequest < COOLDOWN_MS) return;
        lastRequest = now;

        String nick = target.getName().getString();
        pendingNick = nick;
        pendingUntil = now + REPLY_TIMEOUT_MS;
        client.player.networkHandler.sendChatCommand(String.format(COMMAND, nick));
    }

    /** Celowanie w gracza: trafienie w DOWOLNA czesc ciala, z duzej odleglosci. */
    private PlayerEntity findTarget(MinecraftClient client) {
        Entity cam = client.getCameraEntity();
        if (cam == null) return null;

        Vec3d start = cam.getCameraPosVec(1.0f);
        Vec3d dir = cam.getRotationVec(1.0f).normalize();

        PlayerEntity best = null;
        double bestOffset = Double.MAX_VALUE;
        for (PlayerEntity p : client.world.getPlayers()) {
            if (p == client.player) continue;

            Vec3d toCenter = p.getBoundingBox().getCenter().subtract(start);
            double along = toCenter.dotProduct(dir);           // jak daleko przed Toba
            if (along <= 0 || along > MAX_DISTANCE) continue;

            double offset = toCenter.subtract(dir.multiply(along)).length(); // jak daleko od linii celowania
            double allowed = BODY_RADIUS + AIM_TOLERANCE_PER_BLOCK * along;
            if (offset <= allowed && offset < bestOffset) {
                bestOffset = offset;
                best = p;
            }
        }
        return best;
    }

    private void handleStatsScreen(MinecraftClient client, HandledScreen<?> screen) {
        List<String> lines = new ArrayList<>();
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory == client.player.getInventory()) continue; // pomijamy Twoj ekwipunek
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            lines.add(stack.getName().getString());
            LoreComponent lore = stack.get(DataComponentTypes.LORE);
            if (lore != null) {
                for (Text t : lore.lines()) lines.add(t.getString());
            }
        }
        if (lines.isEmpty()) return; // przedmioty jeszcze sie nie zaladowaly - czekamy

        String found = null;
        for (String line : lines) {
            if (MONEY_LINE.matcher(line).find()) {
                found = line.trim();
                break;
            }
        }

        if (DEBUG) {
            client.inGameHud.getChatHud().addMessage(Text.literal("[BalancePeek] linie z /stats " + pendingNick + ":"));
            for (String line : lines) {
                client.inGameHud.getChatHud().addMessage(Text.literal("  " + line));
            }
        }

        String shown = found;
        if (found != null) {
            Matcher mv = MONEY_VALUE.matcher(found);
            if (mv.find()) shown = mv.group(1).trim();
        }
        resultText = pendingNick + " | " + (shown != null ? "Stan konta: " + shown : "brak danych o koncie");
        resultUntil = System.currentTimeMillis() + DISPLAY_MS;
        pendingNick = null;
        client.player.closeHandledScreen();
    }

    /** Rysuje pasek w stylu boss bara u gory ekranu. */
    private void onHud(DrawContext ctx, RenderTickCounter tickCounter) {
        if (resultText == null) return;
        if (System.currentTimeMillis() > resultUntil) {
            resultText = null;
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        int cx = mc.getWindow().getScaledWidth() / 2;
        ctx.drawCenteredTextWithShadow(mc.textRenderer, resultText, cx, 30, 0xFFFFFFFF);
        ctx.fill(cx - 91, 41, cx + 91, 46, 0xFFAA00AA);
    }
}

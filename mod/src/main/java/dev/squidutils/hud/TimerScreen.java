package dev.squidutils.hud;

import dev.squidutils.SquidUtils;
import dev.squidutils.tracker.CustomTimer;
import dev.squidutils.tracker.CustomTimers;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Add, view and remove custom timers - the GUI counterpart to {@code
 * /squidtimer}, opened from the "Manage Timers" button on the Custom Timers
 * settings page. Styled like {@link MultiStepScreen}: one centred, bordered
 * panel sized to its own content.
 *
 * <p>No vanilla {@code EditBox} widgets - nothing else in this mod's own
 * screens uses them either (every one hand-draws its own rows and hit-tests
 * clicks against plain records, {@code HudEditorScreen} and {@code
 * MultiStepScreen} included), and this is the first screen in the mod that
 * needs actual typed text at all, so a small hand-rolled focused-field
 * buffer here matches the established pattern rather than introducing a
 * second, untested one.
 */
public class TimerScreen extends Screen {

    private static final int PAD = 20;
    private static final int FIELD_W = 160;

    private record Row(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    private final List<Row> deleteButtons = new ArrayList<>();
    private Row nameField;
    private Row durationField;
    private Row loopTimeField;
    private Row loopQuantityField;
    private Row addButton;

    private final StringBuilder nameBuffer = new StringBuilder();
    private final StringBuilder durationBuffer = new StringBuilder();
    /** Both optional - blank {@link #loopTimeBuffer} means a plain one-shot
     *  timer, same as before these fields existed. A blank {@link
     *  #loopQuantityBuffer} with a loop time set means "no limit", so the
     *  common case (repeat forever) does not need typing "inf" every time. */
    private final StringBuilder loopTimeBuffer = new StringBuilder();
    private final StringBuilder loopQuantityBuffer = new StringBuilder();
    /** 0 none, 1 name, 2 duration, 3 loop time, 4 loop quantity. */
    private int focusedField = 0;

    private String flash;
    private long flashUntil;

    public TimerScreen() {
        super(Component.literal("Manage timers"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0x90000000);
        deleteButtons.clear();

        var timers = CustomTimers.timers();
        long now = System.currentTimeMillis();
        int lineH = font.lineHeight + 3;

        String title = "Manage timers";
        String helpText = "Loop fields are optional  ·  blank quantity loops forever  ·  Esc to close";
        String addText = "[ Add Timer ]";

        int contentW = Math.max(font.width(title), font.width(helpText));
        contentW = Math.max(contentW, font.width("Loop quantity:") + 6 + FIELD_W);
        for (var t : timers) {
            String line = timerLine(t, now);
            contentW = Math.max(contentW, font.width(line) + 20);
        }
        int panelW = Math.min(contentW + PAD * 2, width - 40);
        int panelX = (width - panelW) / 2;
        int contentX = panelX + PAD;

        int lineCount = 1 + 1 + Math.max(1, timers.size()) + 1 + 5;
        int panelH = Math.min(lineCount * lineH + 60, height - 40);
        int panelY = 20;

        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, Draw.BG);
        g.outline(panelX, panelY, panelW, panelH, Draw.TITLE);

        int y = panelY + PAD;
        drawCentered(g, title, panelX, panelW, y, Draw.TITLE);
        y += lineH + 8;

        if (timers.isEmpty()) {
            g.text(font, "No timers set", contentX, y, Draw.DIM);
            y += lineH;
        } else {
            for (var t : timers) {
                String line = timerLine(t, now);
                g.text(font, line, contentX, y, 0xFF7FD4FF);
                int delX = contentX + font.width(line) + 6;
                g.text(font, "-", delX, y, 0xFFFF6666);
                String id = t.id;
                deleteButtons.add(new Row(delX - 2, y - 1, font.width("-") + 4, font.lineHeight + 2,
                        () -> CustomTimers.removeById(id)));
                y += lineH;
            }
        }
        y += 6;

        // Every label field lines up on the widest one ("Loop quantity:")
        // rather than each hugging its own text, so the four fields form one
        // clean column instead of a ragged edge.
        int labelW = font.width("Loop quantity:") + 6;
        int fieldX = contentX + labelW;

        g.text(font, "Name:", contentX, y, Draw.DIM);
        drawField(g, fieldX, y, nameBuffer.toString(), focusedField == 1);
        nameField = new Row(fieldX - 2, y - 1, FIELD_W + 4, font.lineHeight + 2, () -> {});
        y += lineH;

        g.text(font, "Duration:", contentX, y, Draw.DIM);
        drawField(g, fieldX, y, durationBuffer.toString(), focusedField == 2);
        durationField = new Row(fieldX - 2, y - 1, FIELD_W + 4, font.lineHeight + 2, () -> {});
        y += lineH;

        g.text(font, "Loop every:", contentX, y, Draw.DIM);
        drawField(g, fieldX, y, loopTimeBuffer.toString(), focusedField == 3);
        loopTimeField = new Row(fieldX - 2, y - 1, FIELD_W + 4, font.lineHeight + 2, () -> {});
        y += lineH;

        g.text(font, "Loop quantity:", contentX, y, Draw.DIM);
        drawField(g, fieldX, y, loopQuantityBuffer.toString(), focusedField == 4);
        loopQuantityField = new Row(fieldX - 2, y - 1, FIELD_W + 4, font.lineHeight + 2, () -> {});
        y += lineH + 4;

        boolean flashing = flash != null && System.currentTimeMillis() < flashUntil;
        g.text(font, addText, contentX, y, flashing ? 0xFFFFB020 : 0xFF55FF55);
        addButton = new Row(contentX - 2, y - 1, font.width(addText) + 4, font.lineHeight + 2, this::submit);
        y += lineH;

        if (flashing) {
            g.text(font, flash, contentX, y, 0xFFFFB020);
            y += lineH;
        }

        drawCentered(g, helpText, panelX, panelW, panelY + panelH - lineH - 8, Draw.DIM);
    }

    private static String timerLine(CustomTimer t, long now) {
        String line = t.name + "  -  " + CustomTimers.formatDuration(t.endAtMillis - now) + " left";
        if (t.loopMillis > 0) {
            line += "  (every " + CustomTimers.formatDuration(t.loopMillis)
                    + (t.loopQuantity < 0 ? "" : ", " + (t.loopQuantity - t.firedCount) + " left") + ")";
        }
        return line;
    }

    private void drawField(GuiGraphicsExtractor g, int x, int y, String text, boolean focused) {
        g.fill(x - 2, y - 1, x - 2 + FIELD_W, y - 1 + font.lineHeight + 2, 0x50000000);
        g.outline(x - 2, y - 1, FIELD_W, font.lineHeight + 2, focused ? Draw.TITLE : Draw.BORDER);
        String shown = focused && (System.currentTimeMillis() / 500) % 2 == 0 ? text + "|" : text;
        g.text(font, shown, x, y, 0xFFFFFFFF);
    }

    private void drawCentered(GuiGraphicsExtractor g, String text, int panelX, int panelW, int y, int colour) {
        g.text(font, text, panelX + (panelW - font.width(text)) / 2, y, colour);
    }

    private void submit() {
        String name = nameBuffer.toString().trim();
        String durationText = durationBuffer.toString().trim();
        String loopTimeText = loopTimeBuffer.toString().trim();
        String loopQuantityText = loopQuantityBuffer.toString().trim();
        if (name.isEmpty()) {
            flashMsg("Type a name first");
            return;
        }
        Long millis = CustomTimers.parseDuration(durationText);
        if (millis == null || millis <= 0) {
            flashMsg("Duration like 5m, 1h30m, or 90s");
            return;
        }

        long loopMillis = 0;
        int loopQuantity = -1;
        if (!loopTimeText.isEmpty()) {
            Long parsedLoop = CustomTimers.parseDuration(loopTimeText);
            if (parsedLoop == null || parsedLoop <= 0) {
                flashMsg("Loop every: like 5m, 1h30m, or 90s");
                return;
            }
            loopMillis = parsedLoop;
            if (!loopQuantityText.isEmpty()) {
                Integer parsedQuantity = CustomTimers.parseLoopQuantity(loopQuantityText);
                if (parsedQuantity == null) {
                    flashMsg("Loop quantity: a whole number, or blank for forever");
                    return;
                }
                loopQuantity = parsedQuantity;
            }
        }

        CustomTimers.add(name, millis, loopMillis, loopQuantity);
        nameBuffer.setLength(0);
        durationBuffer.setLength(0);
        loopTimeBuffer.setLength(0);
        loopQuantityBuffer.setLength(0);
        focusedField = 0;
        flashMsg("Added \"" + name + "\"");
    }

    private void flashMsg(String message) {
        flash = message;
        flashUntil = System.currentTimeMillis() + 2000;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (Row r : deleteButtons) {
            if (r.contains(event.x(), event.y())) {
                r.action().run();
                return true;
            }
        }
        if (nameField != null && nameField.contains(event.x(), event.y())) {
            focusedField = 1;
            return true;
        }
        if (durationField != null && durationField.contains(event.x(), event.y())) {
            focusedField = 2;
            return true;
        }
        if (loopTimeField != null && loopTimeField.contains(event.x(), event.y())) {
            focusedField = 3;
            return true;
        }
        if (loopQuantityField != null && loopQuantityField.contains(event.x(), event.y())) {
            focusedField = 4;
            return true;
        }
        if (addButton != null && addButton.contains(event.x(), event.y())) {
            submit();
            return true;
        }
        focusedField = 0;
        return super.mouseClicked(event, doubleClick);
    }

    private StringBuilder currentBuffer() {
        return switch (focusedField) {
            case 1 -> nameBuffer;
            case 2 -> durationBuffer;
            case 3 -> loopTimeBuffer;
            case 4 -> loopQuantityBuffer;
            default -> null;
        };
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (focusedField == 0 || !event.isAllowedChatCharacter()) return super.charTyped(event);
        StringBuilder buf = currentBuffer();
        if (buf.length() < 64) buf.append(event.codepointAsString());
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (focusedField != 0) {
            StringBuilder buf = currentBuffer();
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
                if (!buf.isEmpty()) buf.setLength(buf.length() - 1);
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_TAB) {
                focusedField = focusedField == 4 ? 1 : focusedField + 1;
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                submit();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    /** Same reasoning as {@code HudEditorScreen}'s own override: opened from
     *  a settings button, so closing it should land back there rather than
     *  in the game. */
    @Override
    public void onClose() {
        var managed = SquidUtils.managedConfig();
        if (managed != null) {
            managed.openConfigGui();
        } else {
            super.onClose();
        }
    }
}

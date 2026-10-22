/* 职责：以原生 GuiGraphics 渲染模型分发的一级提示页（条件说明 + 责任分离 + 带宽告知）。 */
package com.shiroha.mmdskin.ui.selector;

import com.shiroha.mmdskin.distrib.ModelDistributionClient;
import com.shiroha.mmdskin.distrib.ModelDistributionManifest;
import com.shiroha.mmdskin.ui.chrome.TranslucentTrayChrome;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件职责：模型分发的一级提示页。
 *
 * <p>房主端没有 UI（改配置文件），所以提示页放在接收方：
 * 谁要装这个模型，谁就要先看清楚条件与责任归属。
 */
public class DistributionNoticeScreen extends Screen {

    private static final int WINDOW_MARGIN = 10;
    private static final int MIN_WINDOW_WIDTH = 180;
    private static final int MAX_WINDOW_WIDTH = 260;
    private static final int MIN_WINDOW_HEIGHT = 150;

    private static final int HEADER_HEIGHT = 30;
    private static final int BUTTON_HEIGHT = 16;
    private static final int BUTTON_GAP = 4;
    private static final int LIST_PADDING = 6;
    private static final int LINE_HEIGHT = 10;

    private final Screen parentScreen;
    private final List<ModelDistributionManifest> models = new ArrayList<>();

    private int rateLimitKbPerSec;
    private float targetScroll;
    private float animatedScroll;
    private ButtonTarget hoveredButton = ButtonTarget.NONE;
    private Layout layout = Layout.empty();

    private enum ButtonTarget {
        NONE,
        ACCEPT,
        CANCEL
    }

    /** 自动下载模式：点"继续"表示接受自动下载的责任条款，而不是去选单个模型。 */
    private final boolean autoMode;

    public DistributionNoticeScreen(Screen parentScreen, List<ModelDistributionManifest> models, int rateLimitKbPerSec) {
        this(parentScreen, models, rateLimitKbPerSec, false);
    }

    public DistributionNoticeScreen(Screen parentScreen, List<ModelDistributionManifest> models,
                                    int rateLimitKbPerSec, boolean autoMode) {
        super(Component.translatable("gui.mmdskin.distribution.notice.title"));
        this.parentScreen = parentScreen;
        if (models != null) {
            this.models.addAll(models);
        }
        this.rateLimitKbPerSec = Math.max(0, rateLimitKbPerSec);
        this.autoMode = autoMode;
    }

    @Override
    protected void init() {
        super.init();
        updateLayout();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            updateLayout();
            updateHoverState(mouseX, mouseY);
            updateScrollAnimation();
            renderContent(guiGraphics);
            flushPendingActions(minecraft);
        } catch (Throwable throwable) {
            closeAfterFailure(throwable);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        // 用真实坐标判定，别用上一帧的 hover 状态
        if (layout.acceptButton.contains(mouseX, mouseY)) {
            if (autoMode) {
                // 自动下载：接受条款后回到游戏，之后 nearby 玩家用什么就自动下什么
                ModelDistributionClient.acceptAutoDownload();
                minecraft.setScreen(parentScreen);
            } else {
                minecraft.setScreen(new DistributionConfirmScreen(parentScreen, models));
            }
            return true;
        }
        if (layout.cancelButton.contains(mouseX, mouseY)) {
            minecraft.setScreen(parentScreen);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        targetScroll = Mth_clamp(targetScroll - (float) delta * 12.0f, 0.0f, maxScroll());
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 必须能 ESC 出去
        if (keyCode == 256) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parentScreen);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static float Mth_clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static int clampInt(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    private void updateHoverState(int mouseX, int mouseY) {
        hoveredButton = ButtonTarget.NONE;
        if (layout.acceptButton.contains(mouseX, mouseY)) {
            hoveredButton = ButtonTarget.ACCEPT;
        } else if (layout.cancelButton.contains(mouseX, mouseY)) {
            hoveredButton = ButtonTarget.CANCEL;
        }
    }

    private void updateScrollAnimation() {
        targetScroll = Mth_clamp(targetScroll, 0.0f, maxScroll());
        animatedScroll = animatedScroll + (targetScroll - animatedScroll) * 0.24f;
        if (Math.abs(targetScroll - animatedScroll) < 0.25f) {
            animatedScroll = targetScroll;
        }
    }

    private float maxScroll() {
        float contentHeight = contentLines().size() * LINE_HEIGHT + LIST_PADDING * 2.0f;
        return Math.max(0.0f, contentHeight - layout.listBox.h);
    }

    /** 正文行：条件说明 + 责任分离 + 带宽告知 + 好心提醒。 */
    private List<Component> contentLines() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("gui.mmdskin.distribution.notice.what").withStyle(ChatFormatting.BOLD));
        lines.addAll(wrap(Component.translatable("gui.mmdskin.distribution.notice.what.desc")));
        lines.add(Component.empty());
        lines.add(Component.translatable("gui.mmdskin.distribution.notice.bandwidth").withStyle(ChatFormatting.BOLD));
        if (rateLimitKbPerSec > 0) {
            lines.addAll(wrap(Component.translatable("gui.mmdskin.distribution.notice.bandwidth.rate", rateLimitKbPerSec)));
        } else {
            lines.addAll(wrap(Component.translatable("gui.mmdskin.distribution.notice.bandwidth.unlimited")));
        }
        lines.addAll(wrap(Component.translatable("gui.mmdskin.distribution.notice.bandwidth.disk")));
        lines.addAll(wrap(Component.translatable("gui.mmdskin.distribution.notice.bandwidth.oss")));
        lines.add(Component.empty());
        lines.add(Component.translatable("gui.mmdskin.distribution.notice.liability").withStyle(ChatFormatting.BOLD));
        lines.addAll(wrap(Component.translatable("gui.mmdskin.distribution.notice.liability.source")));
        lines.addAll(wrap(Component.translatable("gui.mmdskin.distribution.notice.liability.copyright")));
        lines.addAll(wrap(Component.translatable("gui.mmdskin.distribution.notice.liability.mod")));
        lines.add(Component.empty());
        lines.addAll(wrap(Component.translatable("gui.mmdskin.distribution.notice.kind")));
        return lines;
    }

    /** 逐字换行：中文没有空格，按空格折行会整段溢出。 */
    private List<Component> wrap(Component text) {
        List<Component> result = new ArrayList<>();
        String value = text.getString();
        int maxWidth = Math.max(20, layout.listBox.w - LIST_PADDING * 2);
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n') {
                result.add(Component.literal(line.toString()));
                line.setLength(0);
                continue;
            }
            line.append(c);
            if (this.font.width(line.toString()) > maxWidth && line.length() > 1) {
                line.deleteCharAt(line.length() - 1);
                result.add(Component.literal(line.toString()));
                line.setLength(0);
                line.append(c);
            }
        }
        if (line.length() > 0) {
            result.add(Component.literal(line.toString()));
        }
        return result;
    }

    private void renderContent(GuiGraphics guiGraphics) {
        TranslucentTrayChrome.drawOverlay(guiGraphics, this.width, this.height);
        TranslucentTrayChrome.drawPanel(guiGraphics, layout.panel.x, layout.panel.y, layout.panel.w, layout.panel.h);
        guiGraphics.drawString(this.font, this.title.getString(), layout.header.x, layout.header.y + 1,
                TranslucentTrayChrome.TITLE_TEXT, false);
        guiGraphics.drawString(this.font, Component.translatable("gui.mmdskin.distribution.notice.subtitle").getString(),
                layout.header.x, layout.header.y + 11, TranslucentTrayChrome.SUBTITLE_TEXT, false);

        TranslucentTrayChrome.fillListArea(guiGraphics, layout.listBox.x, layout.listBox.y, layout.listBox.w, layout.listBox.h);
        List<Component> lines = contentLines();
        int y = Math.round(layout.listBox.y + LIST_PADDING - animatedScroll);
        for (Component line : lines) {
            if (y + LINE_HEIGHT < layout.listBox.y) {
                y += LINE_HEIGHT;
                continue;
            }
            if (y > layout.listBox.y + layout.listBox.h) {
                break;
            }
            guiGraphics.drawString(this.font, line, layout.listBox.x + LIST_PADDING, y, TranslucentTrayChrome.BODY_TEXT, false);
            y += LINE_HEIGHT;
        }
        float max = maxScroll();
        if (max > 0.0f) {
            TranslucentTrayChrome.drawScrollbar(guiGraphics, layout.listBox.x + layout.listBox.w - 3,
                    layout.listBox.y, layout.listBox.y + layout.listBox.h, animatedScroll, max);
        }

        TranslucentTrayChrome.drawButton(guiGraphics, this.font, layout.acceptButton.x, layout.acceptButton.y,
                layout.acceptButton.w, layout.acceptButton.h,
                Component.translatable("gui.mmdskin.distribution.notice.accept"), hoveredButton == ButtonTarget.ACCEPT, true);
        TranslucentTrayChrome.drawButton(guiGraphics, this.font, layout.cancelButton.x, layout.cancelButton.y,
                layout.cancelButton.w, layout.cancelButton.h,
                Component.translatable("gui.mmdskin.distribution.notice.cancel"), hoveredButton == ButtonTarget.CANCEL, true);
    }

    private void updateLayout() {
        int panelWidth = clampInt(this.width - WINDOW_MARGIN * 2, MIN_WINDOW_WIDTH, MAX_WINDOW_WIDTH);
        // 屏幕比最小高度还矮时（高 GUI 缩放），面板不能超出屏幕，否则按钮会被挤出可视区
        int panelHeight = Math.min(this.height, Math.max(MIN_WINDOW_HEIGHT, this.height - WINDOW_MARGIN * 2));
        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;

        int headerY = panelY + 4;
        int listY = headerY + HEADER_HEIGHT;
        int buttonY = panelY + panelHeight - BUTTON_HEIGHT - 4;
        int listHeight = Math.max(40, buttonY - listY - BUTTON_GAP - 4);

        int buttonWidth = (panelWidth - BUTTON_GAP * 3) / 2;
        layout = new Layout(
                new UiRect(panelX, panelY, panelWidth, panelHeight),
                new UiRect(panelX + 6, headerY, panelWidth - 12, HEADER_HEIGHT - 4),
                new UiRect(panelX + 4, listY, panelWidth - 8, listHeight),
                new UiRect(panelX + 6 + buttonWidth + BUTTON_GAP, buttonY, buttonWidth, BUTTON_HEIGHT),
                new UiRect(panelX + 6, buttonY, buttonWidth, BUTTON_HEIGHT));
    }

    private void flushPendingActions(Minecraft minecraft) {
        // 本页没有异步动作，保留钩子以与其它界面保持一致
    }

    private void closeAfterFailure(Throwable throwable) {
        LOGGER.error("模型分发提示页渲染失败", throwable);
        minecraft.setScreen(parentScreen);
    }

    private static final Logger LOGGER = LogManager.getLogger();

    record UiRect(int x, int y, int w, int h) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        }

        int centerX() {
            return x + w / 2;
        }
    }

    private record Layout(UiRect panel, UiRect header, UiRect listBox, UiRect acceptButton, UiRect cancelButton) {
        static Layout empty() {
            return new Layout(new UiRect(0, 0, 0, 0), new UiRect(0, 0, 0, 0), new UiRect(0, 0, 0, 0),
                    new UiRect(0, 0, 0, 0), new UiRect(0, 0, 0, 0));
        }
    }
}

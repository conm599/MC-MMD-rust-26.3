/* 职责：以原生 GuiGraphics 渲染模型分发的二级确认页（选择模型 + 责任确认 + 下载）。 */
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
 * 文件职责：模型分发的二级确认页。
 *
 * <p>玩家在这里挑选模型、看清体积与传输方式，并必须勾选"我确认拥有该模型的分发权"
 * 才能点下载 —— 这是接收方的责任分离。
 */
public class DistributionConfirmScreen extends Screen {

    private static final int WINDOW_MARGIN = 10;
    private static final int MIN_WINDOW_WIDTH = 180;
    private static final int MAX_WINDOW_WIDTH = 250;
    private static final int MIN_WINDOW_HEIGHT = 140;

    private static final int HEADER_HEIGHT = 28;
    private static final int BUTTON_HEIGHT = 16;
    private static final int BUTTON_GAP = 4;
    private static final int LIST_PADDING = 5;
    private static final int CARD_HEIGHT = 14;
    private static final int CARD_GAP = 4;
    private static final int CHECKBOX_SIZE = 8;

    private final Screen parentScreen;
    private final List<ModelDistributionManifest> models = new ArrayList<>();

    private int selectedIndex = -1;
    private boolean acknowledged;
    private float targetScroll;
    private float animatedScroll;
    private int hoveredCard = -1;
    private ButtonTarget hoveredButton = ButtonTarget.NONE;
    private boolean checkboxHovered;
    private Layout layout = Layout.empty();

    private enum ButtonTarget {
        NONE,
        DOWNLOAD,
        CANCEL
    }

    public DistributionConfirmScreen(Screen parentScreen, List<ModelDistributionManifest> models) {
        super(Component.translatable("gui.mmdskin.distribution.confirm.title"));
        this.parentScreen = parentScreen;
        if (models != null) {
            this.models.addAll(models);
        }
    }

    @Override
    protected void init() {
        super.init();
        updateLayout();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        try {
            updateLayout();
            updateHoverState(mouseX, mouseY);
            updateScrollAnimation();
            renderContent(guiGraphics);
        } catch (Throwable throwable) {
            LOGGER.error("模型分发确认页渲染失败", throwable);
            minecraft.setScreen(parentScreen);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (layout.checkbox.contains(mouseX, mouseY)) {
            acknowledged = !acknowledged;
            return true;
        }
        // 用真实坐标判定，别用上一帧的 hover 状态（点之前光标可能刚挪过）
        if (layout.downloadButton.contains(mouseX, mouseY)) {
            ModelDistributionManifest selected = selectedModel();
            // 必须勾了"我确认拥有分发权"才允许下载 —— 这一条是责任分离的底线
            if (selected != null && acknowledged) {
                ModelDistributionClient.requestModel(selected.modelName);
                minecraft.setScreen(parentScreen);
            }
            return true;
        }
        if (layout.cancelButton.contains(mouseX, mouseY)) {
            minecraft.setScreen(parentScreen);
            return true;
        }
        int index = cardIndexAt(mouseX, mouseY);
        if (index >= 0) {
            selectedIndex = index;
            acknowledged = false;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        targetScroll = clamp(targetScroll - (float) delta * 12.0f, 0.0f, maxScroll());
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 必须能 ESC 出去：这个流程是模态的，出不去就等于把玩家锁死在下载流程里
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

    /** 能不能下载：选中了模型、并且勾了责任确认框。 */
    private boolean canDownload() {
        return selectedModel() != null && acknowledged;
    }

    private ModelDistributionManifest selectedModel() {
        if (selectedIndex < 0 || selectedIndex >= models.size()) {
            return null;
        }
        return models.get(selectedIndex);
    }

    private int cardIndexAt(double mouseX, double mouseY) {
        if (!layout.listBox.contains(mouseX, mouseY)) {
            return -1;
        }
        int y = Math.round(layout.listBox.y + LIST_PADDING - animatedScroll);
        for (int i = 0; i < models.size(); i++) {
            if (mouseY >= y && mouseY < y + CARD_HEIGHT
                    && mouseX >= layout.listBox.x + 4 && mouseX < layout.listBox.x + layout.listBox.w - 4) {
                return i;
            }
            y += CARD_HEIGHT + CARD_GAP;
        }
        return -1;
    }

    private void updateHoverState(int mouseX, int mouseY) {
        hoveredButton = ButtonTarget.NONE;
        if (layout.downloadButton.contains(mouseX, mouseY) && canDownload()) {
            hoveredButton = ButtonTarget.DOWNLOAD;
        } else if (layout.cancelButton.contains(mouseX, mouseY)) {
            hoveredButton = ButtonTarget.CANCEL;
        }
        checkboxHovered = layout.checkbox.contains(mouseX, mouseY);
        hoveredCard = cardIndexAt(mouseX, mouseY);
    }

    private void updateScrollAnimation() {
        targetScroll = clamp(targetScroll, 0.0f, maxScroll());
        animatedScroll = animatedScroll + (targetScroll - animatedScroll) * 0.24f;
        if (Math.abs(targetScroll - animatedScroll) < 0.25f) {
            animatedScroll = targetScroll;
        }
    }

    private float maxScroll() {
        float contentHeight = LIST_PADDING * 2.0f + models.size() * CARD_HEIGHT
                + Math.max(0, models.size() - 1) * CARD_GAP;
        return Math.max(0.0f, contentHeight - layout.listBox.h);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static int clampInt(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    private void renderContent(GuiGraphics guiGraphics) {
        TranslucentTrayChrome.drawOverlay(guiGraphics, this.width, this.height);
        TranslucentTrayChrome.drawPanel(guiGraphics, layout.panel.x, layout.panel.y, layout.panel.w, layout.panel.h);
        guiGraphics.drawString(this.font, this.title.getString(), layout.header.x, layout.header.y + 1,
                TranslucentTrayChrome.TITLE_TEXT, false);

        TranslucentTrayChrome.fillListArea(guiGraphics, layout.listBox.x, layout.listBox.y, layout.listBox.w, layout.listBox.h);
        if (models.isEmpty()) {
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("gui.mmdskin.distribution.empty").getString(),
                    layout.listBox.centerX(), layout.listBox.centerY() - 4, TranslucentTrayChrome.BODY_TEXT);
        } else {
            int y = Math.round(layout.listBox.y + LIST_PADDING - animatedScroll);
            for (int i = 0; i < models.size(); i++) {
                if (y + CARD_HEIGHT < layout.listBox.y) {
                    y += CARD_HEIGHT + CARD_GAP;
                    continue;
                }
                if (y > layout.listBox.y + layout.listBox.h) {
                    break;
                }
                boolean selected = i == selectedIndex;
                boolean hovered = i == hoveredCard;
                int bg = TranslucentTrayChrome.cardBackground(selected, hovered);
                guiGraphics.fill(layout.listBox.x + 4, y, layout.listBox.x + layout.listBox.w - 4, y + CARD_HEIGHT, bg);
                guiGraphics.drawString(this.font, buildCardLabel(models.get(i)),
                        layout.listBox.x + 7, y + 3, TranslucentTrayChrome.BODY_TEXT, false);
                y += CARD_HEIGHT + CARD_GAP;
            }
            float max = maxScroll();
            if (max > 0.0f) {
                TranslucentTrayChrome.drawScrollbar(guiGraphics, layout.listBox.x + layout.listBox.w - 3,
                        layout.listBox.y, layout.listBox.y + layout.listBox.h, animatedScroll, max);
            }
        }

        // 选中模型的详情 + 责任确认
        ModelDistributionManifest selected = selectedModel();
        int detailX = layout.detail.x;
        int detailY = layout.detail.y;
        if (selected != null) {
            guiGraphics.drawString(this.font, Component.translatable("gui.mmdskin.distribution.confirm.detail",
                    selected.modelName,
                    selected.fileCount > 0 ? selected.fileCount : 1,
                    String.format("%.1f", selected.totalSize / 1024.0 / 1024.0)).getString(),
                    detailX, detailY, TranslucentTrayChrome.DETAIL_TEXT, false);
            guiGraphics.drawString(this.font, Component.translatable("gui.mmdskin.distribution.confirm.source",
                    selected.ossVerified
                            ? Component.translatable("gui.mmdskin.distribution.confirm.source.oss").getString()
                            : Component.translatable("gui.mmdskin.distribution.confirm.source.server").getString())
                    .getString(), detailX, detailY + 10, TranslucentTrayChrome.MUTED_TEXT, false);
            guiGraphics.drawString(this.font, Component.translatable("gui.mmdskin.distribution.confirm.liability").getString(),
                    detailX, detailY + 20, TranslucentTrayChrome.MUTED_TEXT, false);
        }

        // 必须勾选的责任确认框
        int boxX = layout.checkbox.x;
        int boxY = layout.checkbox.y;
        int boxColor = acknowledged ? TranslucentTrayChrome.ACCENT : (checkboxHovered ? TranslucentTrayChrome.CARD_HOVER : TranslucentTrayChrome.CARD_BACKGROUND);
        guiGraphics.fill(boxX, boxY, boxX + CHECKBOX_SIZE, boxY + CHECKBOX_SIZE, boxColor);
        if (acknowledged) {
            guiGraphics.drawString(this.font, "×", boxX + 2, boxY - 1, TranslucentTrayChrome.TITLE_TEXT, false);
        }
        guiGraphics.drawString(this.font, Component.translatable("gui.mmdskin.distribution.confirm.checkbox").getString(),
                boxX + CHECKBOX_SIZE + 4, boxY, TranslucentTrayChrome.BODY_TEXT, false);

        boolean canDownload = canDownload();
        TranslucentTrayChrome.drawButton(guiGraphics, this.font, layout.downloadButton.x, layout.downloadButton.y,
                layout.downloadButton.w, layout.downloadButton.h,
                Component.translatable("gui.mmdskin.distribution.confirm.download"),
                hoveredButton == ButtonTarget.DOWNLOAD, canDownload);
        TranslucentTrayChrome.drawButton(guiGraphics, this.font, layout.cancelButton.x, layout.cancelButton.y,
                layout.cancelButton.w, layout.cancelButton.h,
                Component.translatable("gui.mmdskin.distribution.confirm.cancel"),
                hoveredButton == ButtonTarget.CANCEL, true);
    }

    private String buildCardLabel(ModelDistributionManifest manifest) {
        int count = manifest.fileCount > 0 ? manifest.fileCount : 1;
        return Component.translatable("gui.mmdskin.distribution.card", manifest.modelName, count,
                String.format("%.1f", manifest.totalSize / 1024.0 / 1024.0)).getString();
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
        int detailY = buttonY - 36;
        int checkboxY = detailY + 30;
        int listHeight = Math.max(30, checkboxY - listY - 6);

        int buttonWidth = (panelWidth - BUTTON_GAP * 3) / 2;
        layout = new Layout(
                new UiRect(panelX, panelY, panelWidth, panelHeight),
                new UiRect(panelX + 6, headerY, panelWidth - 12, HEADER_HEIGHT - 4),
                new UiRect(panelX + 4, listY, panelWidth - 8, listHeight),
                new UiRect(panelX + 6, detailY, panelWidth - 12, 32),
                new UiRect(panelX + 6, checkboxY, CHECKBOX_SIZE, CHECKBOX_SIZE),
                new UiRect(panelX + 6 + buttonWidth + BUTTON_GAP, buttonY, buttonWidth, BUTTON_HEIGHT),
                new UiRect(panelX + 6, buttonY, buttonWidth, BUTTON_HEIGHT));
    }

    private static final Logger LOGGER = LogManager.getLogger();

    record UiRect(int x, int y, int w, int h) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        }

        int centerX() {
            return x + w / 2;
        }

        int centerY() {
            return y + h / 2;
        }
    }

    private record Layout(UiRect panel, UiRect header, UiRect listBox, UiRect detail, UiRect checkbox,
                          UiRect downloadButton, UiRect cancelButton) {
        static Layout empty() {
            return new Layout(new UiRect(0, 0, 0, 0), new UiRect(0, 0, 0, 0), new UiRect(0, 0, 0, 0),
                    new UiRect(0, 0, 0, 0), new UiRect(0, 0, 0, 0), new UiRect(0, 0, 0, 0), new UiRect(0, 0, 0, 0));
        }
    }
}

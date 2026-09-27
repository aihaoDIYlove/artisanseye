package icu.dreamripples.eye.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;


// 准星HUD

final class HudStrip
{
    static final float SCALE = 0.7f;
    static final float ICON_SIZE = 16f * SCALE;
    static final float FONT_SIZE = 9f * SCALE;
    /** 图标横向间距（行内）/ 行距（矩阵行间）——两者相等时 3×3 矩阵为正方形 */
    static final float ICON_GAP = 2f;
    static final float ROW_GAP = 2f;
    static final float TEXT_GAP = 4f;
    private static final int CARD_OFFSET_X = 14;
    private static final int SCREEN_MARGIN = 2;
    private static final int HOTBAR_CLEARANCE = 26;

    private HudStrip() {}

    record Anchor(float x, float y) {}

    static Anchor anchor(Minecraft mc, float width, float height)
    {
        float x = mc.getWindow().getGuiScaledWidth() / 2f + CARD_OFFSET_X;
        if (x + width > mc.getWindow().getGuiScaledWidth() - SCREEN_MARGIN)
        {
            x = mc.getWindow().getGuiScaledWidth() / 2f - CARD_OFFSET_X - width;
        }
        x = Math.max(SCREEN_MARGIN, x);
        final int guiHeight = mc.getWindow().getGuiScaledHeight();
        final float y = Math.max(SCREEN_MARGIN,
            Math.min(guiHeight / 2f - height / 2f, guiHeight - HOTBAR_CLEARANCE - height));
        return new Anchor(x, y);
    }

    static void drawIcon(GuiGraphics gui, Font font, ItemStack stack, float x, float y)
    {
        if (stack.isEmpty())
        {
            return;
        }
        gui.pose().pushPose();
        gui.pose().translate(x, y, 0);
        gui.pose().scale(SCALE, SCALE, 1f);
        gui.renderItem(stack, 0, 0);
        gui.renderItemDecorations(font, stack, 0, 0);
        gui.pose().popPose();
    }

    static void drawIconRow(GuiGraphics gui, Font font, List<ItemStack> stacks, float x, float y)
    {
        for (int i = 0; i < stacks.size(); i++)
        {
            drawIcon(gui, font, stacks.get(i), x + i * (ICON_SIZE + ICON_GAP), y);
        }
    }

    static void drawScaledText(GuiGraphics gui, Font font, Component text, float x, float y, int color)
    {
        gui.pose().pushPose();
        gui.pose().translate(x, y, 0);
        gui.pose().scale(SCALE, SCALE, 1f);
        gui.drawString(font, text, 0, 0, color, true);
        gui.pose().popPose();
    }
}

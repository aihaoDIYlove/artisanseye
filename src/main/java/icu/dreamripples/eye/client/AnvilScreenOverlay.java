package icu.dreamripples.eye.client;

import icu.dreamripples.eye.ArtisansEye;

import com.mojang.blaze3d.vertex.PoseStack;
import net.dries007.tfc.client.screen.AnvilScreen;
import net.dries007.tfc.common.component.forge.ForgeStep;
import net.dries007.tfc.common.component.forge.Forging;
import net.dries007.tfc.common.recipes.AnvilRecipe;
import net.dries007.tfc.config.TFCConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * 砧子工作界面叠加层：
 * 1. 锻造条右端固定槽位显示目标值（金，与目标指针行对齐）与当前值（白，与进度箭头行对齐）；
 * 2. 悬停八个锻造步骤按钮时，在 GUI 右缘信息列显示该操作的具体偏移量（负值红色、正值绿色）。
 * <p>
 * 布局约束：TFC 砧子 GUI 纵向极密（规则图标 13-35 / 步骤记录 34-50 / 按钮两行 56-90 /
 * 锻造条 94-109 / 物品栏标签 110+），且网格右侧被计划按钮 (137,56)、锤子槽 (138,76)、
 * JEI 提示箭头 (141,40) 占据——因此所有叠加内容统一右对齐到 GUI 右缘（xSize-2），
 * 放置在经像素级排查确认无元素的区域。
 * <p>
 * 全部数据来自锻造数据组件（随物品自动同步客户端），无 mixin、无自定义包。
 */
@EventBusSubscriber(modid = ArtisansEye.MODID, value = Dist.CLIENT)
public final class AnvilScreenOverlay
{
    /** 锻造条像素布局（对照 AnvilScreen.renderBg）：条带 y=94-99，目标指针行 y=98，进度箭头行 y=104，图标均 5px 高 */
    private static final int TARGET_ROW_Y = 94;
    private static final int WORK_ROW_Y = 108;
    /** 悬停提示行：JEI 提示箭头（至 x=150）与计划按钮（y=56 起）之间的空白带 */
    private static final int HINT_ROW_Y = 115;
    /** 数字整体缩小到 0.7 倍（阴影随之同步缩小），避免在密集布局里显得过大 */
    private static final float TEXT_SCALE = 0.7F;
    private static final int CHIP_PADDING = 3;
    private static final int CHIP_BACKGROUND = 0x90000000;
    private static final int WORK_COLOR = 0xFFFFFF;
    private static final int ACCENT_COLOR = 0xFFAA00;
    /** 步骤偏移量的语义色：负值向左敲、正值向右敲，沿用 MC 聊天红/绿 */
    private static final int SIGN_NEG_COLOR = 0xFF5555;
    private static final int SIGN_POS_COLOR = 0x55FF55;

    private AnvilScreenOverlay() {}

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event)
    {
        if (!(event.getScreen() instanceof AnvilScreen screen))
        {
            return;
        }

        final GuiGraphics gui = event.getGuiGraphics();
        final Font font = Minecraft.getInstance().font;
        final Forging forging = screen.getMenu().getBlockEntity().getMainInputForging();
        final int left = screen.getGuiLeft(), top = screen.getGuiTop();
        final int right = left + screen.getXSize() - 2;

        drawStepValueHint(gui, font, event.getMouseX(), event.getMouseY(), left, right, top);

        if (forging.getRecipe() == null)
        {
            return; // 无配方时 TFC 本身不绘制锻造条，叠加层同步隐藏
        }

        drawChip(gui, font, String.valueOf(forging.target()), right, top + TARGET_ROW_Y, ACCENT_COLOR);
        drawChip(gui, font, String.valueOf(forging.work()), right, top + WORK_ROW_Y, WORK_COLOR);
    }

    /** 八个步骤按钮区域 = (guiLeft + buttonX, guiTop + buttonY) 16x16，与 AnvilStepButton 的构造一致 */
    private static void drawStepValueHint(GuiGraphics gui, Font font, int mouseX, int mouseY, int left, int right, int top)
    {
        for (ForgeStep step : ForgeStep.VALUES)
        {
            final int bx = left + step.buttonX(), by = top + step.buttonY();
            if (mouseX >= bx && mouseX < bx + 16 && mouseY >= by && mouseY < by + 16)
            {
                drawChip(gui, font, withSign(step.step()), right, top + HINT_ROW_Y,
                    step.step() < 0 ? SIGN_NEG_COLOR : SIGN_POS_COLOR);
                return;
            }
        }
    }

    /** 右对齐小底块：0.7 倍文字 + 半透明底，y 为文字在屏幕坐标下的起始行 */
    private static void drawChip(GuiGraphics gui, Font font, String text, int right, int y, int color)
    {
        final PoseStack pose = gui.pose();
        pose.pushPose();
        pose.scale(TEXT_SCALE, TEXT_SCALE, 1F);
        // 姿态缩放后空间坐标需除回缩放系数才是屏幕像素
        final int width = font.width(text);
        final int sx = Math.round((right - 2) / TEXT_SCALE) - width;
        final int sy = Math.round(y / TEXT_SCALE);
        gui.fill(sx - CHIP_PADDING, sy - 1, sx + width + 2, sy + 8, CHIP_BACKGROUND);
        gui.drawString(font, text, sx, sy, color, true);
        pose.popPose();
    }

    private static String withSign(int value)
    {
        return value > 0 ? "+" + value : String.valueOf(value);
    }
}

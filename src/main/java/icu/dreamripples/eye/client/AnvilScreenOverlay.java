package icu.dreamripples.eye.client;

import java.util.List;

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
import org.jetbrains.annotations.Nullable;

/**
 * 砧子工作界面叠加层：
 * 1. 锻造条右端固定槽位显示目标值（金，与目标指针行对齐）与当前值（白，与进度箭头行对齐）；
 * 2. 悬停八个锻造步骤按钮时，在 GUI 右缘信息列显示该操作的具体偏移量（负值红色、正值绿色）；
 * 3. 最短路径提示：BFS 求解从当前状态到完成的最短敲击序列（见 {@link AnvilPathSolver}），
 *    推荐按钮边框闪烁（方向语义配色），信息列显示剩余步数，无解时如实提示。
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
    /** 悬停提示行：物品栏标签行（y=110+，x≤44）右端空白区 */
    private static final int HINT_ROW_Y = 115;
    /** 剩余步数行：JEI 提示箭头（至 x=150）与计划按钮（y=56 起）之间的空白带 */
    private static final int REMAIN_ROW_Y = 44;
    /** 数字整体缩小到 0.7 倍（阴影随之同步缩小），避免在密集布局里显得过大 */
    private static final float TEXT_SCALE = 0.7F;
    private static final int CHIP_PADDING = 3;
    private static final int CHIP_BACKGROUND = 0x90000000;
    private static final int WORK_COLOR = 0xFFFFFF;
    private static final int ACCENT_COLOR = 0xFFAA00;
    /** 步骤偏移量的语义色：负值向左敲、正值向右敲，沿用 MC 聊天红/绿 */
    private static final int SIGN_NEG_COLOR = 0xFF5555;
    private static final int SIGN_POS_COLOR = 0x55FF55;
    /** 推荐按钮高亮：呼吸色在方向色（绿/红）与纯白之间平滑过渡，0.8s 周期 */
    private static final int HINT_PULSE_MS = 800;
    private static final int HINT_ALPHA = 0xC0;

    // 求解缓存：指纹（配方+work+窗口+neverWorked）不匹配即重算，BFS 亚毫秒级
    private static AnvilRecipe cachedRecipe;
    private static int cachedWork, cachedTarget;
    private static List<ForgeStep> cachedWindow = List.of();
    private static boolean cachedNeverWorked;
    @Nullable private static List<ForgeStep> cachedPath; // null = 无解；空 = 已完成

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

        final List<ForgeStep> path = solveIfChanged(forging);
        final int remaining = path == null ? -1 : path.size();

        // 剩余步数 / 无解提示（右缘信息列顶部）
        if (remaining > 0)
        {
            drawChip(gui, font, Component.translatable("label.artisanseye.remaining", remaining).getString(),
                right, top + REMAIN_ROW_Y, ACCENT_COLOR);
        }
        else if (remaining < 0)
        {
            drawChip(gui, font, Component.translatable("label.artisanseye.unsolvable").getString(),
                right, top + REMAIN_ROW_Y, SIGN_NEG_COLOR);
        }

        drawChip(gui, font, String.valueOf(forging.target()), right, top + TARGET_ROW_Y, ACCENT_COLOR);
        drawChip(gui, font, String.valueOf(forging.work()), right, top + WORK_ROW_Y, WORK_COLOR);

        // 推荐下一步：按钮边框闪烁，颜色与偏移方向语义一致（左移红 / 右移绿）
        if (remaining > 0)
        {
            drawButtonHint(gui, path.get(0), left, top);
        }
    }

    /** 指纹 = 配方引用 + work + 目标 + 最近三步窗口 + 是否全新工件；任一变化即重跑 BFS */
    private static List<ForgeStep> solveIfChanged(Forging forging)
    {
        final AnvilRecipe recipe = forging.getRecipe();
        final List<ForgeStep> window = forging.lastSteps();
        final boolean neverWorked = !forging.isWorked();
        if (recipe != cachedRecipe || forging.work() != cachedWork || forging.target() != cachedTarget
            || neverWorked != cachedNeverWorked || !window.equals(cachedWindow))
        {
            cachedRecipe = recipe;
            cachedWork = forging.work();
            cachedTarget = forging.target();
            cachedWindow = List.copyOf(window);
            cachedNeverWorked = neverWorked;
            cachedPath = AnvilPathSolver.solve(recipe.getRules(), cachedTarget,
                TFCConfig.SERVER.anvilAcceptableWorkRange.get(), cachedWork, cachedWindow, neverWorked);
        }
        return cachedPath;
    }

    /** 在步骤按钮 16x16 区域画闪烁描边 */
    private static void drawButtonHint(GuiGraphics gui, ForgeStep step, int left, int top)
    {
        final int x = left + step.buttonX(), y = top + step.buttonY();
        final long t = System.currentTimeMillis() % HINT_PULSE_MS;
        final float phase = 0.5F - 0.5F * (float) Math.cos(Math.PI * 2 * t / HINT_PULSE_MS); // 0→1→0 平滑循环
        // 呼吸色：方向色（波谷）↔ 纯白（波峰）逐通道插值
        final int base = step.step() < 0 ? SIGN_NEG_COLOR : SIGN_POS_COLOR;
        final int r = Math.round(((base >> 16) & 0xFF) + (255 - ((base >> 16) & 0xFF)) * phase);
        final int g = Math.round(((base >> 8) & 0xFF) + (255 - ((base >> 8) & 0xFF)) * phase);
        final int b = Math.round((base & 0xFF) + (255 - (base & 0xFF)) * phase);
        final int color = HINT_ALPHA << 24 | r << 16 | g << 8 | b;
        gui.fill(x, y, x + 16, y + 1, color);
        gui.fill(x, y + 15, x + 16, y + 16, color);
        gui.fill(x, y + 1, x + 1, y + 15, color);
        gui.fill(x + 15, y + 1, x + 16, y + 15, color);
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

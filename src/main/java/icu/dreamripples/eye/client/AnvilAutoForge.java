package icu.dreamripples.eye.client;

import java.util.List;

import icu.dreamripples.eye.ArtisansEye;

import net.dries007.tfc.client.screen.AnvilScreen;
import net.dries007.tfc.common.TFCTags;
import net.dries007.tfc.common.blockentities.AnvilBlockEntity;
import net.dries007.tfc.common.component.heat.HeatCapability;
import net.dries007.tfc.common.component.heat.IHeat;
import net.dries007.tfc.common.component.forge.ForgeStep;
import net.dries007.tfc.common.component.forge.Forging;
import net.dries007.tfc.common.recipes.AnvilRecipe;
import net.dries007.tfc.config.TFCConfig;
import net.dries007.tfc.network.ScreenButtonPacket;
import net.dries007.tfc.util.Helpers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

@EventBusSubscriber(modid = ArtisansEye.MODID, value = Dist.CLIENT)
public final class AnvilAutoForge
{
    /** 敲击节奏 tick */
    private static final int PRESS_INTERVAL_TICKS = 2;
    /** 连续 MAX_STALLS 个周期未等到上一击的同步确认即停（服务端拒绝/严重延迟兜底） */
    private static final int MAX_STALLS = 2;
    private static final int BUTTON_HEIGHT = 12;
    private static final int BUTTON_MARGIN = 3;
    private static final int BG_INACTIVE = 0x90000000;
    private static final int BG_ACTIVE = 0xA0204D14;
    private static final int BORDER_IDLE = 0xFF555555;
    private static final int BORDER_HOVER = 0xFFF0F0F0;
    private static final int BORDER_ACTIVE = 0xFF55FF55;
    private static final int TEXT_COLOR = 0xFFFFFF;
    private static final String LABEL_AUTO = "button.artisanseye.anvil_auto";
    private static final String LABEL_STOP = "button.artisanseye.anvil_auto_stop";

    private static boolean autoActive;
    private static int tickCounter;
    private static int stallCount;
    /** 上次敲击时的状态指纹（work|最近三步窗口），用于检测服务端空操作 */
    @Nullable private static String lastFingerprint;

    private AnvilAutoForge() {}

    // ---------- 按钮（手绘） ----------

    private static int buttonWidth(AnvilScreen screen, Font font)
    {
        return font.width(label()) + 8;
    }

    private static int buttonX(AnvilScreen screen, Font font)
    {
        return screen.getGuiLeft() + screen.getXSize() - BUTTON_MARGIN - buttonWidth(screen, font);
    }

    private static int buttonY(AnvilScreen screen)
    {
        return screen.getGuiTop() + 4;
    }

    private static Component label()
    {
        return Component.translatable(autoActive ? LABEL_STOP : LABEL_AUTO);
    }

    private static boolean hovered(double mouseX, double mouseY, AnvilScreen screen, Font font)
    {
        final int x = buttonX(screen, font), y = buttonY(screen);
        return mouseX >= x && mouseX < x + buttonWidth(screen, font) && mouseY >= y && mouseY < y + BUTTON_HEIGHT;
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event)
    {
        if (!(event.getScreen() instanceof AnvilScreen screen))
        {
            return;
        }
        final Font font = Minecraft.getInstance().font;
        final int x = buttonX(screen, font), y = buttonY(screen);
        final int w = buttonWidth(screen, font);
        final boolean hover = hovered(event.getMouseX(), event.getMouseY(), screen, font);
        final Component text = label();

        final GuiGraphics gui = event.getGuiGraphics();
        gui.fill(x, y, x + w, y + BUTTON_HEIGHT, autoActive ? BG_ACTIVE : BG_INACTIVE);
        // 1px 描边：激活=绿色，悬停=亮白，静止=暗灰
        final int border = autoActive ? BORDER_ACTIVE : hover ? BORDER_HOVER : BORDER_IDLE;
        gui.fill(x, y, x + w, y + 1, border);
        gui.fill(x, y + BUTTON_HEIGHT - 1, x + w, y + BUTTON_HEIGHT, border);
        gui.fill(x, y + 1, x + 1, y + BUTTON_HEIGHT - 1, border);
        gui.fill(x + w - 1, y + 1, x + w, y + BUTTON_HEIGHT - 1, border);
        gui.drawString(font, text, x + (w - font.width(text)) / 2, y + (BUTTON_HEIGHT - 8) / 2, TEXT_COLOR, true);
    }

    @SubscribeEvent
    public static void onMousePress(ScreenEvent.MouseButtonPressed.Pre event)
    {
        if (!(event.getScreen() instanceof AnvilScreen screen))
        {
            return;
        }
        if (hovered(event.getMouseX(), event.getMouseY(), screen, Minecraft.getInstance().font))
        {
            if (autoActive)
            {
                stop();
            }
            else
            {
                autoActive = true;
                stallCount = 0;
                lastFingerprint = null;
                tickCounter = PRESS_INTERVAL_TICKS; // 立即开始第一击
            }
            event.setCanceled(true); // 按钮区域无 TFC 控件，吞掉点击避免误触
        }
    }

    // ---------- 自动敲击循环 ----------

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event)
    {
        if (!autoActive)
        {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof AnvilScreen screen) || mc.player == null || mc.level == null)
        {
            stop();
            return;
        }
        if (++tickCounter % PRESS_INTERVAL_TICKS != 0)
        {
            return;
        }

        final AnvilBlockEntity anvil = screen.getMenu().getBlockEntity();
        final Forging forging = anvil.getMainInputForging();
        final AnvilRecipe recipe = forging.getRecipe();
        if (recipe == null)
        {
            stop(); // 已完成（服务端把成品放回输入槽，组件重置）或工件被取走
            return;
        }
        if (!hasHammer(mc.player, anvil) || !canWork(anvil))
        {
            stop(); // 服务端会空操作并刷提示，预判停止
            return;
        }

        // 确认门：上一击必须已被服务端确认（状态回写）才敲下一击。
        // 2t 节奏可能快于方块同步（1-2t），按陈旧状态求解会漏算上一击的偏移，
        // 可能给出对真实状态越界的步（越界 = 销毁工件）；等服务端确认后重算即安全自节流。
        final String fingerprint = forging.work() + "|" + forging.lastSteps();
        if (lastFingerprint != null && fingerprint.equals(lastFingerprint))
        {
            if (++stallCount > MAX_STALLS)
            {
                stop(); // 迟迟未确认 = 服务端在拒绝（提示已刷动作栏），止损
            }
            return;
        }
        stallCount = 0;

        // 每击前从当前同步状态重算最短路径，永远与服务端对齐
        final List<ForgeStep> path = AnvilPathSolver.solve(recipe.getRules(), forging.target(),
            TFCConfig.SERVER.anvilAcceptableWorkRange.get(), forging.work(), forging.lastSteps(), !forging.isWorked());
        if (path == null || path.isEmpty())
        {
            stop(); // 无解 / 已处于完成态（等待服务端收尾）
            return;
        }
        lastFingerprint = fingerprint;

        PacketDistributor.sendToServer(new ScreenButtonPacket(path.get(0).ordinal()));
        if (path.size() == 1)
        {
            // 完成一击：敲完立即停。服务端会把成品放回输入槽，可再锻的成品会被
            // TFC 立刻匹配上新配方——追打下去会把锻好的东西变成不想要的物品
            stop();
        }
    }

    private static void stop()
    {
        autoActive = false;
        stallCount = 0;
        lastFingerprint = null;
    }

    /** 与 AnvilBlockEntity.work 相同的锤子判定：砧子锤子槽 → 主手 → 副手 */
    private static boolean hasHammer(Player player, AnvilBlockEntity anvil)
    {
        return Helpers.isItem(anvil.getInventory().getStackInSlot(AnvilBlockEntity.SLOT_HAMMER), TFCTags.Items.TOOLS_HAMMER)
            || Helpers.isItem(player.getMainHandItem(), TFCTags.Items.TOOLS_HAMMER)
            || Helpers.isItem(player.getOffhandItem(), TFCTags.Items.TOOLS_HAMMER);
    }

    /** 与 AnvilBlockEntity.work 相同的温度判定，温度不足时服务端会空操作 */
    private static boolean canWork(AnvilBlockEntity anvil)
    {
        final IHeat heat = HeatCapability.get(anvil.getInventory().getStackInSlot(AnvilBlockEntity.SLOT_INPUT_MAIN));
        return heat == null || heat.canWork();
    }
}

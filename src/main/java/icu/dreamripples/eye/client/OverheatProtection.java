package icu.dreamripples.eye.client;

import java.util.ArrayList;
import java.util.List;

import icu.dreamripples.eye.ArtisansEye;

import net.dries007.tfc.client.screen.CharcoalForgeScreen;
import net.dries007.tfc.client.screen.CrucibleScreen;
import net.dries007.tfc.common.blockentities.CharcoalForgeBlockEntity;
import net.dries007.tfc.common.blockentities.CrucibleBlockEntity;
import net.dries007.tfc.common.component.heat.HeatCapability;
import net.dries007.tfc.common.component.heat.IHeat;
import net.dries007.tfc.common.container.slot.CallbackSlot;
import net.dries007.tfc.common.recipes.HeatingRecipe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.jetbrains.annotations.Nullable;

// 过温保护

@EventBusSubscriber(modid = ArtisansEye.MODID, value = Dist.CLIENT)
public final class OverheatProtection
{
    private static final int BUTTON_HEIGHT = 12;
    /** 按钮右缘与 UI 面板左缘的间距（"贴着边缘"） */
    private static final int BUTTON_GAP = 2;
    private static final int BG_INACTIVE = 0x90000000;
    private static final int BG_ACTIVE = 0xA0204D14;
    private static final int BORDER_IDLE = 0xFF555555;
    private static final int BORDER_HOVER = 0xFFF0F0F0;
    private static final int BORDER_ACTIVE = 0xFF55FF55;
    private static final int TEXT_COLOR = 0xFFFFFF;
    private static final String LABEL_ON = "button.artisanseye.overheat_on";
    private static final String LABEL_OFF = "button.artisanseye.overheat_off";
    /** 连续 MAX_STALLS 轮扫描危险槽位集合毫无变化 = 取出失败（背包满），改为丢弃 */
    private static final int MAX_STALLS = 2;

    private static boolean enabled;
    private static int unchangedSweeps;
    @Nullable private static String lastSweepFingerprint;

    private OverheatProtection() {}

    // ---------- 按钮（手绘，与 AnvilAutoForge 同款样式，挂在 UI 外左侧） ----------

    private static int buttonWidth(Font font)
    {
        return font.width(label()) + 8;
    }

    private static int buttonX(AbstractContainerScreen<?> screen, Font font)
    {
        return screen.getGuiLeft() - BUTTON_GAP - buttonWidth(font);
    }

    private static int buttonY(AbstractContainerScreen<?> screen)
    {
        return screen.getGuiTop() + 4;
    }

    private static Component label()
    {
        return Component.translatable(enabled ? LABEL_ON : LABEL_OFF);
    }

    private static boolean hovered(AbstractContainerScreen<?> screen, double mouseX, double mouseY)
    {
        final Font font = Minecraft.getInstance().font;
        final int x = buttonX(screen, font), y = buttonY(screen);
        return mouseX >= x && mouseX < x + buttonWidth(font) && mouseY >= y && mouseY < y + BUTTON_HEIGHT;
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event)
    {
        if (!(event.getScreen() instanceof CrucibleScreen) && !(event.getScreen() instanceof CharcoalForgeScreen))
        {
            return;
        }
        final AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) event.getScreen();
        final Font font = Minecraft.getInstance().font;
        final int x = buttonX(screen, font), y = buttonY(screen);
        final int w = buttonWidth(font);
        final boolean hover = hovered(screen, event.getMouseX(), event.getMouseY());
        final Component text = label();

        final GuiGraphics gui = event.getGuiGraphics();
        gui.fill(x, y, x + w, y + BUTTON_HEIGHT, enabled ? BG_ACTIVE : BG_INACTIVE);
        // 1px 描边：开启=绿色，悬停=亮白，静止=暗灰
        final int border = enabled ? BORDER_ACTIVE : hover ? BORDER_HOVER : BORDER_IDLE;
        gui.fill(x, y, x + w, y + 1, border);
        gui.fill(x, y + BUTTON_HEIGHT - 1, x + w, y + BUTTON_HEIGHT, border);
        gui.fill(x, y + 1, x + 1, y + BUTTON_HEIGHT - 1, border);
        gui.fill(x + w - 1, y + 1, x + w, y + BUTTON_HEIGHT - 1, border);
        gui.drawString(font, text, x + (w - font.width(text)) / 2, y + (BUTTON_HEIGHT - 8) / 2, TEXT_COLOR, true);
    }

    @SubscribeEvent
    public static void onMousePress(ScreenEvent.MouseButtonPressed.Pre event)
    {
        if (!(event.getScreen() instanceof CrucibleScreen) && !(event.getScreen() instanceof CharcoalForgeScreen))
        {
            return;
        }
        final AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) event.getScreen();
        if (hovered(screen, event.getMouseX(), event.getMouseY()))
        {
            enabled = !enabled;
            lastSweepFingerprint = null;
            unchangedSweeps = 0;
            event.setCanceled(true); // 按钮区域无 TFC 控件，吞掉点击避免误触
        }
    }

    // ---------- 过温扫描与取出 ----------

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event)
    {
        final Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.player == null || mc.level == null || mc.gameMode == null)
        {
            return;
        }
        // 会熔毁的槽位范围：木炭炉输入 5-9（服务端只对这些槽加热+熔毁），坩埚输入 0-8（输出槽只收流体）
        final AbstractContainerMenu menu;
        final int minSlot, maxSlot;
        if (mc.screen instanceof CharcoalForgeScreen forge)
        {
            menu = forge.getMenu();
            minSlot = CharcoalForgeBlockEntity.SLOT_INPUT_MIN;
            maxSlot = CharcoalForgeBlockEntity.SLOT_INPUT_MAX;
        }
        else if (mc.screen instanceof CrucibleScreen crucible)
        {
            menu = crucible.getMenu();
            minSlot = CrucibleBlockEntity.SLOT_INPUT_START;
            maxSlot = CrucibleBlockEntity.SLOT_INPUT_END;
        }
        else
        {
            return;
        }

        // 收集危险槽位（菜单槽下标 == BE 槽位下标，容器段一一对应）+ 状态指纹
        final List<Integer> dangerSlots = new ArrayList<>();
        final StringBuilder fingerprint = new StringBuilder();
        for (int beSlot = minSlot; beSlot <= maxSlot && beSlot < menu.slots.size(); beSlot++)
        {
            final Slot slot = menu.slots.get(beSlot);
            // 容器段的槽位是 CallbackSlot 且槽位号与 BE 一致，防止 TFC 改序后扫错槽
            if (!(slot instanceof CallbackSlot) || slot.getContainerSlot() != beSlot)
            {
                continue;
            }
            final ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !isDanger(stack))
            {
                continue;
            }
            dangerSlots.add(beSlot);
            fingerprint.append(beSlot).append(':').append(stack.getItem()).append(':').append(stack.getCount()).append('|');
        }

        if (dangerSlots.isEmpty())
        {
            lastSweepFingerprint = null;
            unchangedSweeps = 0;
            return;
        }

        if (lastSweepFingerprint != null && fingerprint.toString().equals(lastSweepFingerprint))
        {
            // 上一轮取出毫无效果（本地预测即时失败回滚 = 背包满），连续两轮 → 按 Q 语义丢在地上
            if (++unchangedSweeps >= MAX_STALLS)
            {
                for (final int menuSlot : dangerSlots)
                {
                    mc.gameMode.handleInventoryMouseClick(menu.containerId, menuSlot, 0, ClickType.THROW, mc.player);
                }
                lastSweepFingerprint = null;
                unchangedSweeps = 0;
            }
            return;
        }
        lastSweepFingerprint = fingerprint.toString();
        unchangedSweeps = 0;

        // 模拟 shift+左键：全部危险槽位同一轮取出（坩埚里同种物品同一刻集体熔毁，串行取会救不过来）
        for (final int menuSlot : dangerSlots)
        {
            mc.gameMode.handleInventoryMouseClick(menu.containerId, menuSlot, 0, ClickType.QUICK_MOVE, mc.player);
        }
    }

    /**
     * 与 TFC tooltip 的"危险"完全同款判定（IHeatView#addTooltipInfo）：
     * 有加热配方、温度已过其 90%、且配方的物品产物为空（= 继续加热会烧没）。
     */
    private static boolean isDanger(ItemStack stack)
    {
        final IHeat heat = HeatCapability.get(stack);
        if (heat == null)
        {
            return false;
        }
        final float temperature = heat.getTemperature();
        if (temperature <= 0)
        {
            return false;
        }
        final HeatingRecipe recipe = HeatingRecipe.getRecipe(stack);
        return recipe != null && temperature > 0.9f * recipe.getTemperature() && recipe.assembleItem(stack).isEmpty();
    }
}

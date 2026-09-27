package icu.dreamripples.eye.client;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import icu.dreamripples.eye.ArtisansEye;

import net.dries007.tfc.common.blockentities.CharcoalForgeBlockEntity;
import net.dries007.tfc.config.TFCConfig;
import net.dries007.tfc.util.calendar.Calendars;
import net.dries007.tfc.util.data.Fuel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.jetbrains.annotations.Nullable;

// 木炭炉速览：上行加工槽图标（温度走 TFC 温度条），中行燃料槽 + 炉温文字，下行剩余燃料（用户定稿 2026-09-24），画法共用 HudStrip。
// 剩余燃料 = burnTicks + Σ 燃料格 Fuel.duration，除以消耗速率（1/tick，鼓风×2）折算；burnTicks/airTicks 为 TFC 私有字段
// （AT 对依赖 jar 编译期无效，见 docs），反射读取、失败则该行静默。雨天服务端同样 ×2 消耗，HUD 不追雨况，雨天略高估。
@EventBusSubscriber(modid = ArtisansEye.MODID, value = Dist.CLIENT)
public final class ForgeHudOverlay
{
    private static final int TEXT_COLOR = 0xFFFFFF;
    private static final String FUEL_LEFT_KEY = "label.artisanseye.fuel_left";

    private static final @Nullable Field BURN_TICKS = field("burnTicks");
    private static final @Nullable Field AIR_TICKS = field("airTicks");

    private ForgeHudOverlay() {}

    @SubscribeEvent
    public static void onHudRender(RenderGuiEvent.Post event)
    {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui)
        {
            return;
        }
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK)
        {
            return;
        }
        if (!(mc.level.getBlockEntity(hit.getBlockPos()) instanceof CharcoalForgeBlockEntity forge))
        {
            return;
        }

        final Font font = mc.font;
        // 只画非空槽（炉子会把燃料归拢到前几格；加工物品少时同样不占位）
        final List<ItemStack> fuels = collect(forge, CharcoalForgeBlockEntity.SLOT_FUEL_MIN, CharcoalForgeBlockEntity.SLOT_FUEL_MAX);
        final List<ItemStack> inputs = collect(forge, CharcoalForgeBlockEntity.SLOT_INPUT_MIN, CharcoalForgeBlockEntity.SLOT_INPUT_MAX);
        final @Nullable Component heat = TFCConfig.CLIENT.heatTooltipStyle.get().formatColored(forge.getTemperature());
        final @Nullable Component fuelLeft = fuelLeftText(mc, forge);
        if (fuels.isEmpty() && inputs.isEmpty() && heat == null && fuelLeft == null)
        {
            return;
        }

        final float unit = HudStrip.ICON_SIZE + HudStrip.ICON_GAP;
        final float fuelsWidth = fuels.isEmpty() ? 0f : fuels.size() * unit - HudStrip.ICON_GAP;
        final float inputsWidth = inputs.isEmpty() ? 0f : inputs.size() * unit - HudStrip.ICON_GAP;
        final float heatWidth = heat != null ? font.width(heat) * HudStrip.SCALE : 0f;
        final float fuelsRowWidth = fuelsWidth + (!fuels.isEmpty() && heat != null ? HudStrip.TEXT_GAP : 0f) + heatWidth;
        final float fuelLeftWidth = fuelLeft != null ? font.width(fuelLeft) * HudStrip.SCALE : 0f;

        // 三行：加工槽 / 燃料+炉温 / 剩余燃料，缺失的行自动收缩
        final boolean hasInputs = !inputs.isEmpty();
        final boolean hasFuelsRow = !fuels.isEmpty() || heat != null;
        final float width = Math.max(Math.max(hasInputs ? inputsWidth : 0f, hasFuelsRow ? fuelsRowWidth : 0f), fuelLeftWidth);
        final int rows = (hasInputs ? 1 : 0) + (hasFuelsRow ? 1 : 0) + (fuelLeft != null ? 1 : 0);
        final float height = rows * HudStrip.ICON_SIZE + (rows - 1) * HudStrip.ROW_GAP;

        final HudStrip.Anchor at = HudStrip.anchor(mc, width, height);
        final GuiGraphics gui = event.getGuiGraphics();
        float y = at.y();
        if (hasInputs)
        {
            HudStrip.drawIconRow(gui, font, inputs, at.x(), y);
            y += HudStrip.ICON_SIZE + HudStrip.ROW_GAP;
        }
        if (hasFuelsRow)
        {
            HudStrip.drawIconRow(gui, font, fuels, at.x(), y);
            if (heat != null)
            {
                // 炉温跟在燃料行末尾（燃料行空时独占该行）
                HudStrip.drawScaledText(gui, font, heat, at.x() + (fuels.isEmpty() ? 0f : fuelsWidth + HudStrip.TEXT_GAP),
                    y + (HudStrip.ICON_SIZE - HudStrip.FONT_SIZE) / 2f, TEXT_COLOR);
            }
            y += HudStrip.ICON_SIZE + HudStrip.ROW_GAP;
        }
        if (fuelLeft != null)
        {
            HudStrip.drawScaledText(gui, font, fuelLeft, at.x(), y + (HudStrip.ICON_SIZE - HudStrip.FONT_SIZE) / 2f, TEXT_COLOR);
        }
    }

    private static @Nullable Field field(String name)
    {
        try
        {
            final Field f = CharcoalForgeBlockEntity.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        }
        catch (ReflectiveOperationException e)
        {
            return null;
        }
    }

    /** 剩余燃料：burnTicks + Σ 燃料格 Fuel.duration，除以消耗速率折算真实时间；未点燃或反射失败时不显示 */
    private static @Nullable Component fuelLeftText(Minecraft mc, CharcoalForgeBlockEntity forge)
    {
        if (BURN_TICKS == null || AIR_TICKS == null)
        {
            return null;
        }
        final int burnTicks;
        final int airTicks;
        try
        {
            burnTicks = BURN_TICKS.getInt(forge);
            airTicks = AIR_TICKS.getInt(forge);
        }
        catch (IllegalAccessException e)
        {
            return null;
        }
        if (burnTicks <= 0)
        {
            return null;
        }
        long total = burnTicks;
        for (int slot = CharcoalForgeBlockEntity.SLOT_FUEL_MIN; slot <= CharcoalForgeBlockEntity.SLOT_FUEL_MAX; slot++)
        {
            final ItemStack stack = forge.getInventory().getStackInSlot(slot);
            if (stack.isEmpty())
            {
                continue;
            }
            final Fuel fuel = Fuel.get(stack);
            if (fuel != null)
            {
                total += fuel.duration();
            }
        }
        final int rate = airTicks > 0 ? 2 : 1;
        return Component.translatable(FUEL_LEFT_KEY, Calendars.get(mc.level).getTimeDelta(total / rate));
    }

    private static List<ItemStack> collect(CharcoalForgeBlockEntity forge, int minSlot, int maxSlot)
    {
        final List<ItemStack> stacks = new ArrayList<>();
        for (int slot = minSlot; slot <= maxSlot; slot++)
        {
            final ItemStack stack = forge.getInventory().getStackInSlot(slot);
            if (!stack.isEmpty())
            {
                stacks.add(stack);
            }
        }
        return stacks;
    }
}

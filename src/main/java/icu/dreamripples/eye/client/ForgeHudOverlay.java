package icu.dreamripples.eye.client;

import java.util.ArrayList;
import java.util.List;

import icu.dreamripples.eye.ArtisansEye;

import net.dries007.tfc.common.blockentities.CharcoalForgeBlockEntity;
import net.dries007.tfc.config.TFCConfig;
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

// 木炭炉速览条：上行加工槽图标（温度走 TFC 温度条），下行燃料槽 + 炉温文字，画法共用 HudStrip
@EventBusSubscriber(modid = ArtisansEye.MODID, value = Dist.CLIENT)
public final class ForgeHudOverlay
{
    private static final int TEXT_COLOR = 0xFFFFFF;

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
        if (fuels.isEmpty() && inputs.isEmpty() && heat == null)
        {
            return;
        }

        final float unit = HudStrip.ICON_SIZE + HudStrip.ICON_GAP;
        final float fuelsWidth = fuels.isEmpty() ? 0f : fuels.size() * unit - HudStrip.ICON_GAP;
        final float inputsWidth = inputs.isEmpty() ? 0f : inputs.size() * unit - HudStrip.ICON_GAP;
        final float heatWidth = heat != null ? font.width(heat) * HudStrip.SCALE : 0f;
        final float fuelsRowWidth = fuelsWidth + (!fuels.isEmpty() && heat != null ? HudStrip.TEXT_GAP : 0f) + heatWidth;
        final boolean twoRows = !inputs.isEmpty() && (!fuels.isEmpty() || heat != null);
        final float width = Math.max(inputsWidth, fuelsRowWidth);
        final float height = twoRows ? HudStrip.ICON_SIZE + HudStrip.ROW_GAP + HudStrip.ICON_SIZE : HudStrip.ICON_SIZE;

        final HudStrip.Anchor at = HudStrip.anchor(mc, width, height);
        final GuiGraphics gui = event.getGuiGraphics();
        HudStrip.drawIconRow(gui, font, inputs, at.x(), at.y());
        final float fuelsY = twoRows ? at.y() + HudStrip.ICON_SIZE + HudStrip.ROW_GAP : at.y();
        HudStrip.drawIconRow(gui, font, fuels, at.x(), fuelsY);
        if (heat != null)
        {
            // 炉温跟在燃料行末尾（燃料行空时独占该行）
            final float tx = at.x() + (fuels.isEmpty() ? 0f : fuelsWidth + HudStrip.TEXT_GAP);
            HudStrip.drawScaledText(gui, font, heat, tx, fuelsY + (HudStrip.ICON_SIZE - HudStrip.FONT_SIZE) / 2f, TEXT_COLOR);
        }
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

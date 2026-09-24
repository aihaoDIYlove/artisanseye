package icu.dreamripples.eye.client;

import icu.dreamripples.eye.ArtisansEye;

import net.dries007.tfc.common.blockentities.CrucibleBlockEntity;
import net.dries007.tfc.config.TFCConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.jetbrains.annotations.Nullable;

// 坩埚速览：3×3 加工矩阵（0-8 格按真实位置画、空格留白）+ 最右侧坩埚温度文字，画法共用 HudStrip。
// 注意：坩埚纯加热固体阶段（尚无熔液）不逐 tick 同步，此时温度/温度条是上次同步的快照；有熔液后每 tick 同步即实时。
@EventBusSubscriber(modid = ArtisansEye.MODID, value = Dist.CLIENT)
public final class CrucibleHudOverlay
{
    private static final int TEXT_COLOR = 0xFFFFFF;

    private CrucibleHudOverlay() {}

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
        if (!(mc.level.getBlockEntity(hit.getBlockPos()) instanceof CrucibleBlockEntity crucible))
        {
            return;
        }

        final Font font = mc.font;
        final @Nullable Component heat = TFCConfig.CLIENT.heatTooltipStyle.get().formatColored(crucible.getTemperature());

        boolean hasItems = false;
        for (int slot = CrucibleBlockEntity.SLOT_INPUT_START; slot <= CrucibleBlockEntity.SLOT_INPUT_END; slot++)
        {
            if (!crucible.getInventory().getStackInSlot(slot).isEmpty())
            {
                hasItems = true;
                break;
            }
        }
        if (!hasItems && heat == null)
        {
            return;
        }

        final float heatWidth = heat != null ? font.width(heat) * HudStrip.SCALE : 0f;
        final float matrixWidth = 3 * HudStrip.ICON_SIZE + 2 * HudStrip.ICON_GAP;
        final float matrixHeight = 3 * HudStrip.ICON_SIZE + 2 * HudStrip.ROW_GAP;
        final float width = hasItems ? matrixWidth + (heat != null ? HudStrip.TEXT_GAP + heatWidth : 0f) : heatWidth;
        final float height = hasItems ? matrixHeight : HudStrip.FONT_SIZE;

        final HudStrip.Anchor at = HudStrip.anchor(mc, width, height);
        final GuiGraphics gui = event.getGuiGraphics();
        if (hasItems)
        {
            // 槽位在 GUI 里按行优先排布（slot 0 = 左上），照此画以保证与坩埚界面方位一致；输出槽 9 不展示
            for (int slot = CrucibleBlockEntity.SLOT_INPUT_START; slot <= CrucibleBlockEntity.SLOT_INPUT_END; slot++)
            {
                final int row = slot / 3;
                final int col = slot % 3;
                HudStrip.drawIcon(gui, font, crucible.getInventory().getStackInSlot(slot),
                    at.x() + col * (HudStrip.ICON_SIZE + HudStrip.ICON_GAP),
                    at.y() + row * (HudStrip.ICON_SIZE + HudStrip.ROW_GAP));
            }
        }
        if (heat != null)
        {
            final float tx = hasItems ? at.x() + matrixWidth + HudStrip.TEXT_GAP : at.x();
            final float ty = hasItems ? at.y() + (matrixHeight - HudStrip.FONT_SIZE) / 2f : at.y();
            HudStrip.drawScaledText(gui, font, heat, tx, ty, TEXT_COLOR);
        }
    }
}

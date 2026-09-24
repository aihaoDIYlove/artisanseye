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

// 木炭炉速览条
@EventBusSubscriber(modid = ArtisansEye.MODID, value = Dist.CLIENT)
public final class ForgeHudOverlay
{
    private static final float SCALE = 0.7f;
    private static final float ICON_SIZE = 16f * SCALE;
    private static final float FONT_SIZE = 9f * SCALE;
    /** 图标间距 / 行距 / 图标与炉温文字间距（均为缩放后的最终像素） */
    private static final float ICON_GAP = 1f;
    private static final float ROW_GAP = 2f;
    private static final float TEXT_GAP = 4f;
    private static final int TEXT_COLOR = 0xFFFFFF;
    /** 水平锚点：准星右侧 14px，右侧放不下翻到准星左侧等距处；垂直居中，底边避开快捷栏 */
    private static final int CARD_OFFSET_X = 14;
    private static final int SCREEN_MARGIN = 2;
    private static final int HOTBAR_CLEARANCE = 26;

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

        final float unit = ICON_SIZE + ICON_GAP;
        final float fuelsWidth = fuels.isEmpty() ? 0f : fuels.size() * unit - ICON_GAP;
        final float inputsWidth = inputs.isEmpty() ? 0f : inputs.size() * unit - ICON_GAP;
        final float heatWidth = heat != null ? font.width(heat) * SCALE : 0f;
        final float fuelsRowWidth = fuelsWidth + (!fuels.isEmpty() && heat != null ? TEXT_GAP : 0f) + heatWidth;
        final boolean twoRows = !inputs.isEmpty() && (!fuels.isEmpty() || heat != null);
        final float width = Math.max(inputsWidth, fuelsRowWidth);
        final float height = twoRows ? ICON_SIZE + ROW_GAP + ICON_SIZE : ICON_SIZE;

        // 布局同 PlacedItemOverlay：右侧越界翻到准星左侧，再夹到屏幕内；垂直居中、底边避开快捷栏
        float x = mc.getWindow().getGuiScaledWidth() / 2f + CARD_OFFSET_X;
        if (x + width > mc.getWindow().getGuiScaledWidth() - SCREEN_MARGIN)
        {
            x = mc.getWindow().getGuiScaledWidth() / 2f - CARD_OFFSET_X - width;
        }
        x = Math.max(SCREEN_MARGIN, x);
        final int guiHeight = mc.getWindow().getGuiScaledHeight();
        final float y = Math.max(SCREEN_MARGIN,
            Math.min(guiHeight / 2f - height / 2f, guiHeight - HOTBAR_CLEARANCE - height));

        final GuiGraphics gui = event.getGuiGraphics();
        drawRow(gui, font, inputs, x, y);
        final float fuelsY = twoRows ? y + ICON_SIZE + ROW_GAP : y;
        drawRow(gui, font, fuels, x, fuelsY);
        if (heat != null)
        {
            // 炉温跟在燃料行末尾（燃料行空时独占该行），随整体 ×0.7 缩放
            final float tx = x + (fuels.isEmpty() ? 0f : fuelsWidth + TEXT_GAP);
            final float ty = fuelsY + (ICON_SIZE - FONT_SIZE) / 2f;
            gui.pose().pushPose();
            gui.pose().translate(tx, ty, 0);
            gui.pose().scale(SCALE, SCALE, 1f);
            gui.drawString(font, heat, 0, 0, TEXT_COLOR, true);
            gui.pose().popPose();
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

    /**
     * 缩放画法：pose 缩放后以 (0,0) 为锚绘制——renderItem 内部平移 (8,8,150)，
     * 合成后图标恰落在 [0, ICON_SIZE]²；温度条/数量角标经 renderItemDecorations 随 pose 同缩放。
     */
    private static void drawRow(GuiGraphics gui, Font font, List<ItemStack> stacks, float x, float y)
    {
        for (int i = 0; i < stacks.size(); i++)
        {
            final float ix = x + i * (ICON_SIZE + ICON_GAP);
            gui.pose().pushPose();
            gui.pose().translate(ix, y, 0);
            gui.pose().scale(SCALE, SCALE, 1f);
            gui.renderItem(stacks.get(i), 0, 0);
            gui.renderItemDecorations(font, stacks.get(i), 0, 0);
            gui.pose().popPose();
        }
    }
}

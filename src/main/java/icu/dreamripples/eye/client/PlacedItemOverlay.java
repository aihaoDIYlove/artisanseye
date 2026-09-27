package icu.dreamripples.eye.client;
import java.util.ArrayList;
import java.util.List;
import icu.dreamripples.eye.ArtisansEye;
import net.dries007.tfc.common.blockentities.PlacedItemBlockEntity;
import net.dries007.tfc.common.component.heat.HeatCapability;
import net.dries007.tfc.common.component.mold.Vessel;
import net.dries007.tfc.config.TFCConfig;
import net.dries007.tfc.util.tooltip.Tooltips;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;


// 地面放置物速览

@EventBusSubscriber(modid = ArtisansEye.MODID, value = Dist.CLIENT)
public final class PlacedItemOverlay
{
    /** 图标与文字的统一缩放（先按 0.7 试效果） */
    private static final float SCALE = 0.7f;
    private static final float ICON_SIZE = 16f * SCALE;
    private static final float FONT_SIZE = 9f * SCALE;
    /** 图标间距 / 图标与文字间距（均为缩放后的最终像素） */
    private static final float ICON_GAP = 1f;
    private static final float TEXT_GAP = 5f;
    private static final int TEXT_COLOR = 0xFFFFFF;
    private static final String EMPTY_KEY = "label.artisanseye.placed_vessel_empty";
    /** 水平锚点：准星右侧 14px，右侧放不下翻到准星左侧等距处；垂直居中，底边避开快捷栏 */
    private static final int CARD_OFFSET_X = 14;
    private static final int SCREEN_MARGIN = 2;
    private static final int HOTBAR_CLEARANCE = 26;

    private PlacedItemOverlay() {}

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
        if (!(mc.level.getBlockEntity(hit.getBlockPos()) instanceof PlacedItemBlockEntity placed))
        {
            return;
        }

        // 只看指针指的那一格（大物品固定中央槽）
        final int slot = placed.holdingLargeItem()
            ? PlacedItemBlockEntity.SLOT_LARGE_ITEM
            : PlacedItemBlockEntity.getSlotSelected(hit);
        final ItemStack stack = placed.getInventory().getStackInSlot(slot);
        if (stack.isEmpty())
        {
            return;
        }

        // 组装：内容物图标（仅小缸）+ 金属液行 + 温度；全空则静默
        final Vessel vessel = Vessel.get(stack);
        final List<ItemStack> icons = new ArrayList<>();
        if (vessel != null)
        {
            for (ItemStack content : vessel.contents())
            {
                if (!content.isEmpty())
                {
                    icons.add(content);
                }
            }
        }
        final FluidStack fluid = vessel != null ? vessel.getFluidInTank(0) : FluidStack.EMPTY;
        final float temperature = HeatCapability.getTemperature(stack);
        final @Nullable Component heat = temperature > 0
            ? TFCConfig.CLIENT.heatTooltipStyle.get().formatColored(temperature)
            : null;

        MutableComponent text = null;
        if (!fluid.isEmpty())
        {
            text = Tooltips.fluidUnitsOf(fluid);
        }
        if (heat != null)
        {
            // 金属液与温度同行并存（此前误写成互斥分支，有液时温度消失）
            text = text == null ? heat.copy() : text.append("  ").append(heat);
        }
        // 无文字段时：真正空的小缸明示"空"；有内容物的冷小缸只画图标；非小缸冷物下方自然什么都不画
        if (text == null && vessel != null && icons.isEmpty())
        {
            text = Component.translatable(EMPTY_KEY);
        }

        final Font font = mc.font;
        final float iconsWidth = icons.isEmpty()
            ? 0f
            : (icons.size() - 1) * (ICON_SIZE + ICON_GAP) + ICON_SIZE;
        final float textWidth = text != null ? font.width(text) * SCALE : 0f;
        final float width = iconsWidth + (!icons.isEmpty() && text != null ? TEXT_GAP : 0f) + textWidth;

        // 布局同 ForgeHudOverlay：右侧越界翻到准星左侧，再夹到屏幕内；垂直居中、底边避开快捷栏
        float x = mc.getWindow().getGuiScaledWidth() / 2f + CARD_OFFSET_X;
        if (x + width > mc.getWindow().getGuiScaledWidth() - SCREEN_MARGIN)
        {
            x = mc.getWindow().getGuiScaledWidth() / 2f - CARD_OFFSET_X - width;
        }
        x = Math.max(SCREEN_MARGIN, x);
        final int guiHeight = mc.getWindow().getGuiScaledHeight();
        final int y = Math.max(SCREEN_MARGIN,
            Math.min((int) (guiHeight / 2f - ICON_SIZE / 2f), guiHeight - HOTBAR_CLEARANCE - (int) ICON_SIZE));

        final GuiGraphics gui = event.getGuiGraphics();
        // 缩放画法：pose 缩放后以 (0,0) 为锚绘制——renderItem 内部再平移 (8,8,150)，
        // 合成后图标恰好落在 [0, ICON_SIZE]²，装饰（数量角标/耐久条）随之同缩放
        for (int i = 0; i < icons.size(); i++)
        {
            final float fx = x + i * (ICON_SIZE + ICON_GAP);
            gui.pose().pushPose();
            gui.pose().translate(fx, y, 0);
            gui.pose().scale(SCALE, SCALE, 1f);
            gui.renderItem(icons.get(i), 0, 0);
            gui.renderItemDecorations(font, icons.get(i), 0, 0);
            gui.pose().popPose();
        }
        if (text != null)
        {
            final float tx = x + (iconsWidth > 0f ? iconsWidth + TEXT_GAP : 0f);
            final float ty = y + (ICON_SIZE - FONT_SIZE) / 2f;
            gui.pose().pushPose();
            gui.pose().translate(tx, ty, 0);
            gui.pose().scale(SCALE, SCALE, 1f);
            gui.drawString(font, text, 0, 0, TEXT_COLOR, true);
            gui.pose().popPose();
        }
    }
}

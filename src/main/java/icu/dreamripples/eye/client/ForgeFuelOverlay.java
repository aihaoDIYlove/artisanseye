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

/**
 * 木炭炉速览条（用户定稿：无底色——图标与温度自带对比度，避免色块挡视野）：
 * 准星指向木炭炉时，仅显示一行——燃料槽（0-4）图标 + 当前炉温。
 * 温度样式复用 TFC 组件（尊重玩家 TFC 客户端配置：颜色/摄氏/华氏…），冷炉不显示温度段。
 * 小缸等加工详情由 {@link VesselTooltipEnhancer} 附加到物品悬停提示里。
 * <p>
 * 数据链路（已对照 TFC 源码验证）：炉子烧热期间 serverTick 每 tick markForSync()，
 * update tag 带全部 14 格库存与炉温，纯客户端读取方块实体即可，无 mixin、无自定义包。
 */
@EventBusSubscriber(modid = ArtisansEye.MODID, value = Dist.CLIENT)
public final class ForgeFuelOverlay
{
    private static final int SLOT_SIZE = 16;
    private static final int FONT_HEIGHT = 9;
    private static final int TITLE_COLOR = 0xFFFFFF;
    /** 水平锚点：准星右侧 14px，右侧放不下翻到准星左侧等距处；垂直居中，底边避开快捷栏 */
    private static final int CARD_OFFSET_X = 14;
    private static final int SCREEN_MARGIN = 2;
    private static final int HOTBAR_CLEARANCE = 26;

    private ForgeFuelOverlay() {}

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
        final @Nullable Component heat = TFCConfig.CLIENT.heatTooltipStyle.get().formatColored(forge.getTemperature());

        // 只画非空燃料槽（空槽不占位；炉子自身会把燃料归拢到前几格，不会中间断档）
        final List<ItemStack> fuels = new ArrayList<>();
        for (int slot = CharcoalForgeBlockEntity.SLOT_FUEL_MIN; slot <= CharcoalForgeBlockEntity.SLOT_FUEL_MAX; slot++)
        {
            final ItemStack fuel = forge.getInventory().getStackInSlot(slot);
            if (!fuel.isEmpty())
            {
                fuels.add(fuel);
            }
        }
        if (fuels.isEmpty() && heat == null)
        {
            return; // 无炭可画、无温度可写
        }
        final int iconsWidth = fuels.isEmpty() ? 0 : fuels.size() * (SLOT_SIZE + 1) - 1;
        final int width = iconsWidth + (heat != null ? 4 + font.width(heat) : 0);
        final int height = SLOT_SIZE;

        // 布局：右侧越界翻到准星左侧，再夹到屏幕内；垂直居中、底边避开快捷栏
        int x = mc.getWindow().getGuiScaledWidth() / 2 + CARD_OFFSET_X;
        if (x + width > mc.getWindow().getGuiScaledWidth() - SCREEN_MARGIN)
        {
            x = mc.getWindow().getGuiScaledWidth() / 2 - CARD_OFFSET_X - width;
        }
        x = Math.max(SCREEN_MARGIN, x);
        final int guiHeight = mc.getWindow().getGuiScaledHeight();
        final int y = Math.max(SCREEN_MARGIN,
            Math.min(guiHeight / 2 - height / 2, guiHeight - HOTBAR_CLEARANCE - height));

        final GuiGraphics gui = event.getGuiGraphics();
        for (int i = 0; i < fuels.size(); i++)
        {
            final int ix = x + i * (SLOT_SIZE + 1);
            gui.renderItem(fuels.get(i), ix, y);
            gui.renderItemDecorations(font, fuels.get(i), ix, y);
        }
        if (heat != null)
        {
            gui.drawString(font, heat, x + iconsWidth + 4, y + (SLOT_SIZE - FONT_HEIGHT) / 2, TITLE_COLOR, true);
        }
    }
}

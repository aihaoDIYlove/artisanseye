package icu.dreamripples.eye.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import icu.dreamripples.eye.ArtisansEye;

import net.dries007.tfc.common.blockentities.BloomeryBlockEntity;
import net.dries007.tfc.common.blocks.devices.BloomeryBlock;
import net.dries007.tfc.util.calendar.Calendars;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.jetbrains.annotations.Nullable;

// 锻铁炉速览：内容物按物品种类聚合成"图标×数量"（数量经 copyWithCount 由 vanilla 数量角标画出）
// + 右侧状态文字（燃烧 = 剩余时间，复用 TFC 的 tfc.jade.time_left；未点燃 = 待点燃 N 件），画法共用 HudStrip。
// 锻铁炉自身非热体，无温度可显示；燃烧期间内容物不变也不同步，客户端快照不会过时。
@EventBusSubscriber(modid = ArtisansEye.MODID, value = Dist.CLIENT)
public final class BloomeryHudOverlay
{
    private static final int TEXT_COLOR = 0xFFFFFF;
    private static final String UNLIT_KEY = "label.artisanseye.bloomery_unlit";

    private BloomeryHudOverlay() {}

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
        if (!(mc.level.getBlockEntity(hit.getBlockPos()) instanceof BloomeryBlockEntity bloomery))
        {
            return;
        }

        final Font font = mc.font;
        // inputStacks 每项 1 件、最多 48 件：按物品聚合（保持首次出现顺序），展示栈不共享 BE 内引用
        final Map<Item, ItemStack> grouped = new LinkedHashMap<>();
        for (ItemStack stack : bloomery.getInputStacks())
        {
            if (stack.isEmpty())
            {
                continue;
            }
            final ItemStack shown = grouped.get(stack.getItem());
            grouped.put(stack.getItem(), shown == null ? stack.copyWithCount(1) : shown.copyWithCount(shown.getCount() + 1));
        }
        if (grouped.isEmpty())
        {
            return;
        }

        final List<ItemStack> icons = new ArrayList<>(grouped.values());
        @Nullable Component text = null;
        if (bloomery.getBlockState().getValue(BloomeryBlock.LIT))
        {
            // 剩余时间客户端可精确算：litTick 随 update tag 同步 + 日历客户端同步 + 配方客户端可查
            final long ticksLeft = bloomery.getRemainingTicks();
            if (ticksLeft > 0)
            {
                text = Component.translatable("tfc.jade.time_left", Calendars.get(mc.level).getTimeDelta(ticksLeft));
            }
        }
        else
        {
            text = Component.translatable(UNLIT_KEY, bloomery.getInputCount());
        }

        final float unit = HudStrip.ICON_SIZE + HudStrip.ICON_GAP;
        final float iconsWidth = icons.size() * unit - HudStrip.ICON_GAP;
        final float textWidth = text != null ? font.width(text) * HudStrip.SCALE : 0f;
        final float width = iconsWidth + (text != null ? HudStrip.TEXT_GAP + textWidth : 0f);

        final HudStrip.Anchor at = HudStrip.anchor(mc, width, HudStrip.ICON_SIZE);
        final GuiGraphics gui = event.getGuiGraphics();
        HudStrip.drawIconRow(gui, font, icons, at.x(), at.y());
        if (text != null)
        {
            HudStrip.drawScaledText(gui, font, text, at.x() + iconsWidth + HudStrip.TEXT_GAP,
                at.y() + (HudStrip.ICON_SIZE - HudStrip.FONT_SIZE) / 2f, TEXT_COLOR);
        }
    }
}

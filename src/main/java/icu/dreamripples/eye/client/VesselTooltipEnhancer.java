package icu.dreamripples.eye.client;

import java.util.ArrayList;
import java.util.List;

import icu.dreamripples.eye.ArtisansEye;

import net.dries007.tfc.common.component.mold.Vessel;
import net.dries007.tfc.util.Helpers;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * 小缸悬停提示增强（ItemTooltipEvent，零 mixin）：
 * TFC 原生 tooltip 只在"储物态"（冷、无液体）列出小缸内容物；一旦加热进入模具态，
 * 就只剩金属液行和一句"还有未熔物"，不再说明剩的是什么。此处对模具态且仍有固体的小缸，
 * 把剩余固体的文字列表**插在"还有未熔物"一行正下方**——按翻译键定位该行
 * （匹配 {@link TranslatableContents}，不受样式影响），条目格式复用
 * {@link Helpers#addInventoryTooltipInfo}，与官方储物态列表一致。
 * 不加自己的"内容物："标题：TFC 模具态开头已有同名行管金属液，再打一遍会重复。
 * 金属液行/熔化凝固状态/温度由 TFC 原生提供，不重复。注：2x2 图片网格走的是原版
 * Item.getTooltipImage()，无事件口子，如需给模具态上图片必须 mixin（暂不做）。
 */
@EventBusSubscriber(modid = ArtisansEye.MODID, value = Dist.CLIENT)
public final class VesselTooltipEnhancer
{
    private static final String UNMELTED_KEY = "tfc.tooltip.small_vessel.still_has_unmelted_items";

    private VesselTooltipEnhancer() {}

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event)
    {
        final Vessel vessel = Vessel.get(event.getItemStack());
        // 仅增强模具态（加热过或有液体）；储物态 TFC 已列出内容物，不重复
        if (vessel == null || vessel.isInventory() || Helpers.isEmpty(vessel.contents()))
        {
            return;
        }
        final List<Component> entries = new ArrayList<>();
        Helpers.addInventoryTooltipInfo(vessel.contents(), entries);

        // 该警告行与"有剩固体"同条件出现，正常必然命中；兜底追加到末尾
        final List<Component> tooltip = event.getToolTip();
        int insertAt = tooltip.size();
        for (int i = 0; i < tooltip.size(); i++)
        {
            if (tooltip.get(i).getContents() instanceof TranslatableContents contents
                && UNMELTED_KEY.equals(contents.getKey()))
            {
                insertAt = i + 1;
                break;
            }
        }
        tooltip.addAll(insertAt, entries);
    }
}

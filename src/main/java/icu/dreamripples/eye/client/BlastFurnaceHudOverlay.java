package icu.dreamripples.eye.client;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import icu.dreamripples.eye.ArtisansEye;

import net.dries007.tfc.common.blockentities.BlastFurnaceBlockEntity;
import net.dries007.tfc.common.blocks.devices.BlastFurnaceBlock;
import net.dries007.tfc.config.TFCConfig;
import net.dries007.tfc.util.calendar.Calendars;
import net.dries007.tfc.util.data.Fuel;
import net.dries007.tfc.util.tooltip.Tooltips;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

// 高炉速览：上行内容物聚合图标（原料/催化剂/燃料 各按物品聚合 + 末尾吹管），下行 温度·剩余燃料·铁水，画法共用 HudStrip。
// 内容物三堆与 burnTicks 是 TFC 私有字段：ModDevGradle 的 AT 只作用于游戏 jar、对 TFC 依赖 jar 编译期不生效（2026-09-24
// 实测 5 个 private 访问错误），故走反射 + 失败降级为纯数量文字（复用 tfc.jade.* 键）。字段名对照 TFC 4.2.10，升级需 re-verify。
// 剩余燃料 = burnTicks + Σ Fuel.duration，按消耗倍率（鼓风×2）折算为真实时间。
@EventBusSubscriber(modid = ArtisansEye.MODID, value = Dist.CLIENT)
public final class BlastFurnaceHudOverlay
{
    private static final int TEXT_COLOR = 0xFFFFFF;
    private static final String FUEL_LEFT_KEY = "label.artisanseye.blast_furnace_fuel_left";

    private static final @Nullable Field INPUT_STACKS = field("inputStacks");
    private static final @Nullable Field CATALYST_STACKS = field("catalystStacks");
    private static final @Nullable Field FUEL_STACKS = field("fuelStacks");
    private static final @Nullable Field BURN_TICKS = field("burnTicks");

    private BlastFurnaceHudOverlay() {}

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
        if (!(mc.level.getBlockEntity(hit.getBlockPos()) instanceof BlastFurnaceBlockEntity furnace))
        {
            return;
        }

        final Font font = mc.font;
        final List<List<ItemStack>> groups = collectGroups(furnace);
        final List<Component> lines = collectLines(mc, furnace);
        if (groups.isEmpty() && lines.isEmpty())
        {
            return;
        }

        final float unit = HudStrip.ICON_SIZE + HudStrip.ICON_GAP;
        float iconsWidth = 0f;
        for (int i = 0; i < groups.size(); i++)
        {
            final List<ItemStack> group = groups.get(i);
            iconsWidth += group.size() * unit - HudStrip.ICON_GAP;
            if (i < groups.size() - 1)
            {
                iconsWidth += HudStrip.TEXT_GAP;
            }
        }
        float width = groups.isEmpty() ? 0f : iconsWidth;
        for (Component line : lines)
        {
            width = Math.max(width, font.width(line) * HudStrip.SCALE);
        }

        // 行高统一 ICON_SIZE、行距 ROW_GAP：图标行在首行，文字行随后（铁水独占一行，用户定稿）
        final int rowCount = (groups.isEmpty() ? 0 : 1) + lines.size();
        final float height = rowCount * HudStrip.ICON_SIZE + (rowCount - 1) * HudStrip.ROW_GAP;

        final HudStrip.Anchor at = HudStrip.anchor(mc, width, height);
        final GuiGraphics gui = event.getGuiGraphics();
        float y = at.y();
        if (!groups.isEmpty())
        {
            float x = at.x();
            for (int i = 0; i < groups.size(); i++)
            {
                final List<ItemStack> group = groups.get(i);
                final float groupWidth = group.size() * unit - HudStrip.ICON_GAP;
                HudStrip.drawIconRow(gui, font, group, x, y);
                x += groupWidth + (i < groups.size() - 1 ? HudStrip.TEXT_GAP : 0f);
            }
            y += HudStrip.ICON_SIZE + HudStrip.ROW_GAP;
        }
        for (Component line : lines)
        {
            HudStrip.drawScaledText(gui, font, line, at.x(), y + (HudStrip.ICON_SIZE - HudStrip.FONT_SIZE) / 2f, TEXT_COLOR);
            y += HudStrip.ICON_SIZE + HudStrip.ROW_GAP;
        }
    }

    private static @Nullable Field field(String name)
    {
        try
        {
            final Field f = BlastFurnaceBlockEntity.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        }
        catch (ReflectiveOperationException e)
        {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static @Nullable List<ItemStack> stacks(BlastFurnaceBlockEntity furnace, @Nullable Field field)
    {
        if (field == null)
        {
            return null;
        }
        try
        {
            return (List<ItemStack>) field.get(furnace);
        }
        catch (IllegalAccessException e)
        {
            return null;
        }
    }

    /** 三堆内容物各聚合成"图标×数量"组（每项 1 件，最多 5 层×4=20 件/堆），末尾追加吹管（空则跳过）；反射失败时尽量降级 */
    private static List<List<ItemStack>> collectGroups(BlastFurnaceBlockEntity furnace)
    {
        final List<List<ItemStack>> groups = new ArrayList<>();
        final List<ItemStack> inputs = stacks(furnace, INPUT_STACKS);
        if (inputs != null)
        {
            groups.add(aggregate(inputs));
        }
        final List<ItemStack> catalysts = stacks(furnace, CATALYST_STACKS);
        if (catalysts != null)
        {
            groups.add(aggregate(catalysts));
        }
        else if (!furnace.getInventory().getCatalyst().isEmpty() && furnace.getCatalystCount() > 0)
        {
            // 公开 API 兜底：催化剂样本 ×数量（催化剂与原料 1:1，样本即类型）
            groups.add(List.of(furnace.getInventory().getCatalyst().copyWithCount(furnace.getCatalystCount())));
        }
        final List<ItemStack> fuels = stacks(furnace, FUEL_STACKS);
        if (fuels != null)
        {
            groups.add(aggregate(fuels));
        }
        final ItemStack tuyere = furnace.getInventory().getStackInSlot(0);
        if (!tuyere.isEmpty())
        {
            groups.add(List.of(tuyere));
        }
        groups.removeIf(List::isEmpty);
        return groups;
    }

    private static List<ItemStack> aggregate(List<ItemStack> stacks)
    {
        final Map<Item, ItemStack> grouped = new LinkedHashMap<>();
        for (ItemStack stack : stacks)
        {
            if (stack.isEmpty())
            {
                continue;
            }
            final ItemStack shown = grouped.get(stack.getItem());
            grouped.put(stack.getItem(), shown == null ? stack.copyWithCount(1) : shown.copyWithCount(shown.getCount() + 1));
        }
        return new ArrayList<>(grouped.values());
    }

    /** 文字行：温度（冷则无）· [反射失败时的数量兜底] · 剩余燃料（点燃且反射可用，按消耗倍率折算）合成一行；铁水（产出罐非空时）独占一行 */
    private static List<Component> collectLines(Minecraft mc, BlastFurnaceBlockEntity furnace)
    {
        final List<Component> lines = new ArrayList<>();
        MutableComponent line = null;
        final @Nullable Component temp = TFCConfig.CLIENT.heatTooltipStyle.get().formatColored(furnace.getTemperature());
        if (temp != null)
        {
            line = temp.copy();
        }
        if (INPUT_STACKS == null)
        {
            final Component seg = Component.translatable("tfc.jade.input_stacks", furnace.getInputCount());
            line = line == null ? seg.copy() : line.append("  ").append(seg);
        }
        if (FUEL_STACKS == null)
        {
            final Component seg = Component.translatable("tfc.jade.fuel_stacks", furnace.getFuelCount());
            line = line == null ? seg.copy() : line.append("  ").append(seg);
        }
        if (furnace.getBlockState().getValue(BlastFurnaceBlock.LIT) && BURN_TICKS != null && FUEL_STACKS != null)
        {
            long total = 0;
            try
            {
                total = BURN_TICKS.getInt(furnace);
            }
            catch (IllegalAccessException ignored)
            {
            }
            final List<ItemStack> fuels = stacks(furnace, FUEL_STACKS);
            if (fuels != null)
            {
                for (ItemStack fuelStack : fuels)
                {
                    final Fuel fuel = Fuel.get(fuelStack);
                    if (fuel != null)
                    {
                        total += fuel.duration();
                    }
                }
            }
            final int rate = TFCConfig.SERVER.blastFurnaceFuelConsumptionMultiplier.get() * (furnace.getAirTicks() > 0 ? 2 : 1);
            if (total > 0 && rate > 0)
            {
                final Component seg = Component.translatable(FUEL_LEFT_KEY, Calendars.get(mc.level).getTimeDelta(total / rate));
                line = line == null ? seg.copy() : line.append("  ").append(seg);
            }
        }
        if (line != null)
        {
            lines.add(line);
        }
        final FluidStack fluid = furnace.getInventory().getFluidHandler().getFluidInTank(0);
        if (!fluid.isEmpty())
        {
            lines.add(Tooltips.fluidUnitsOf(fluid));
        }
        return lines;
    }
}

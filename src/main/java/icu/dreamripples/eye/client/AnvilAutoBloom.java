package icu.dreamripples.eye.client;

import java.util.List;

import icu.dreamripples.eye.ArtisansEye;

import net.dries007.tfc.client.screen.AnvilScreen;
import net.dries007.tfc.common.TFCTags;
import net.dries007.tfc.common.blockentities.AnvilBlockEntity;
import net.dries007.tfc.common.component.forge.ForgeStep;
import net.dries007.tfc.common.component.forge.Forging;
import net.dries007.tfc.common.component.heat.HeatCapability;
import net.dries007.tfc.common.component.heat.IHeat;
import net.dries007.tfc.common.items.TFCItems;
import net.dries007.tfc.common.recipes.AnvilRecipe;
import net.dries007.tfc.config.TFCConfig;
import net.dries007.tfc.network.ScreenButtonPacket;
import net.dries007.tfc.util.Helpers;
import net.dries007.tfc.util.Metal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * 自动锻铁：把生铁方坯全自动敲成锻铁锭，成品收回背包（满则按 Q 语义丢弃），再自动填充下一块，
 * 直至背包没有温度合适的铁坯。
 */
@EventBusSubscriber(modid = ArtisansEye.MODID, value = Dist.CLIENT)
public final class AnvilAutoBloom
{
    private static final int BUTTON_HEIGHT = 12;
    private static final int BUTTON_MARGIN = 3;
    /** 与"自动/停止"按钮的垂直间距（自动按钮占 guiTop+4..16） */
    private static final int VERTICAL_GAP = 2;
    private static final int BG_INACTIVE = 0x90000000;
    private static final int BG_ACTIVE = 0xA0204D14;
    private static final int BORDER_IDLE = 0xFF555555;
    private static final int BORDER_HOVER = 0xFFF0F0F0;
    private static final int BORDER_ACTIVE = 0xFF55FF55;
    private static final int TEXT_COLOR = 0xFFFFFF;
    private static final String LABEL_AUTO = "button.artisanseye.anvil_auto_bloom";
    private static final String LABEL_STOP = "button.artisanseye.anvil_auto_bloom_stop";
    /** 连续 MAX_STALLS 个周期未等到上一动作的同步确认即停 */
    private static final int MAX_STALLS = 2;

    /** 上一个已发送、尚未在同步状态确认的动作 */
    private enum Action { NONE, PRESS, FILL, COLLECT_QUICK, COLLECT_THROW }

    private static boolean autoActive;
    private static int tickCounter;
    private static int stallCount;
    private static int collectTries;
    /** "方坯在槽但配方还没同步"的连续等待周期数（超时 = 铁砧等级不够） */
    private static int recipeWaitCycles;
    private static Action lastAction = Action.NONE;
    @Nullable private static String lastFingerprint;

    private AnvilAutoBloom() {}

    // ---------- 按钮（手绘，与 AnvilAutoForge 同款样式，排其下方） ----------

    private static int buttonWidth(AnvilScreen screen, Font font)
    {
        return font.width(label()) + 8;
    }

    private static int buttonX(AnvilScreen screen, Font font)
    {
        return screen.getGuiLeft() + screen.getXSize() - BUTTON_MARGIN - buttonWidth(screen, font);
    }

    private static int buttonY(AnvilScreen screen)
    {
        return screen.getGuiTop() + 4 + BUTTON_HEIGHT + VERTICAL_GAP;
    }

    private static Component label()
    {
        return Component.translatable(autoActive ? LABEL_STOP : LABEL_AUTO);
    }

    private static boolean hovered(double mouseX, double mouseY, AnvilScreen screen, Font font)
    {
        final int x = buttonX(screen, font), y = buttonY(screen);
        return mouseX >= x && mouseX < x + buttonWidth(screen, font) && mouseY >= y && mouseY < y + BUTTON_HEIGHT;
    }

    /** 按钮可见性：门控放行且（运行中 或 背包里有温度合适的铁坯） */
    private static boolean visible(AnvilScreen screen)
    {
        if (!ArtisansEyeConfig.autoForgeEnabled())
        {
            return false;
        }
        final Player player = Minecraft.getInstance().player;
        return autoActive || (player != null && findHotBloom(player) >= 0);
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event)
    {
        if (!(event.getScreen() instanceof AnvilScreen screen) || !visible(screen))
        {
            return;
        }
        final Font font = Minecraft.getInstance().font;
        final int x = buttonX(screen, font), y = buttonY(screen);
        final int w = buttonWidth(screen, font);
        final boolean hover = hovered(event.getMouseX(), event.getMouseY(), screen, font);
        final Component text = label();

        final GuiGraphics gui = event.getGuiGraphics();
        gui.fill(x, y, x + w, y + BUTTON_HEIGHT, autoActive ? BG_ACTIVE : BG_INACTIVE);
        // 1px 描边：激活=绿色，悬停=亮白，静止=暗灰
        final int border = autoActive ? BORDER_ACTIVE : hover ? BORDER_HOVER : BORDER_IDLE;
        gui.fill(x, y, x + w, y + 1, border);
        gui.fill(x, y + BUTTON_HEIGHT - 1, x + w, y + BUTTON_HEIGHT, border);
        gui.fill(x, y + 1, x + 1, y + BUTTON_HEIGHT - 1, border);
        gui.fill(x + w - 1, y + 1, x + w, y + BUTTON_HEIGHT - 1, border);
        gui.drawString(font, text, x + (w - font.width(text)) / 2, y + (BUTTON_HEIGHT - 8) / 2, TEXT_COLOR, true);
    }

    @SubscribeEvent
    public static void onMousePress(ScreenEvent.MouseButtonPressed.Pre event)
    {
        if (!(event.getScreen() instanceof AnvilScreen screen) || !visible(screen))
        {
            return;
        }
        if (hovered(event.getMouseX(), event.getMouseY(), screen, Minecraft.getInstance().font))
        {
            if (autoActive)
            {
                stop();
            }
            else
            {
                if (!screen.getMenu().getCarried().isEmpty())
                {
                    return; // 光标拿着物品时启动会让后续点击语义不可控，不响应
                }
                AnvilAutoForge.cancel(); // 与普通自动锻造互斥
                autoActive = true;
                stallCount = 0;
                collectTries = 0;
                recipeWaitCycles = 0;
                lastAction = Action.NONE;
                lastFingerprint = null;
                tickCounter = ArtisansEyeConfig.pressInterval(); // 下一周期立即开始
            }
            event.setCanceled(true); // 按钮区域无 TFC 控件，吞掉点击避免误触
        }
    }

    // ---------- 自动锻铁循环 ----------

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event)
    {
        if (autoActive && !ArtisansEyeConfig.autoForgeEnabled())
        {
            stop(); // 配置被中途关闭（理论路径），残留运行态立即停
        }
        if (!autoActive)
        {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof AnvilScreen screen) || mc.player == null || mc.level == null || mc.gameMode == null)
        {
            stop();
            return;
        }
        if (!screen.getMenu().getCarried().isEmpty())
        {
            stop(); // 玩家在 GUI 里拿起了物品，继续点击会污染光标语义
            return;
        }
        if (++tickCounter % ArtisansEyeConfig.pressInterval() != 0)
        {
            return;
        }

        final AnvilBlockEntity anvil = screen.getMenu().getBlockEntity();
        final ItemStack input = anvil.getInventory().getStackInSlot(AnvilBlockEntity.SLOT_INPUT_MAIN);
        final Forging forging = anvil.getMainInputForging();
        final AnvilRecipe recipe = forging.getRecipe();

        // 指纹：主输入物品 + 数量 + 锻打进度/窗口 —— 任何动作（敲击/填充/收回/丢弃）生效都会改变它
        final String fingerprint = input.getItem() + ":" + input.getCount()
            + "|" + forging.work() + "|" + forging.lastSteps();

        if (lastAction != Action.NONE)
        {
            if (fingerprint.equals(lastFingerprint))
            {
                // 上一个动作尚未反映到同步状态
                if (lastAction == Action.COLLECT_QUICK && ++collectTries >= 2)
                {
                    // shift-click 两个周期都没搬走 = 背包满了 → 按 Q 语义丢在地上
                    collectTries = 0;
                    stallCount = 0;
                    lastAction = Action.COLLECT_THROW;
                    mc.gameMode.handleInventoryMouseClick(screen.getMenu().containerId,
                        AnvilBlockEntity.SLOT_INPUT_MAIN, 0, ClickType.THROW, mc.player);
                    return;
                }
                if (++stallCount > MAX_STALLS)
                {
                    stop(); // 服务端拒绝/严重延迟，止损（拒绝原因已由动作前预判过滤）
                }
                return;
            }
            // 动作已确认
            lastAction = Action.NONE;
            lastFingerprint = null;
            stallCount = 0;
            collectTries = 0;
        }

        // 完成态优先：锻铁锭 → 模拟 shift+左键收回背包（满则经确认门降级为模拟 Q 丢弃）。
        // 必须放在配方分支之前：铁砧 setAndUpdateSlots 会用记住的上次配方（lastRecipe，持久化在 NBT）
        // 给刚完成的新锭兜底选中一个砧子配方（如锭→板），getRecipe() 非空不代表"玩家在锻别的东西"。
        if (isWroughtIronIngot(input))
        {
            recipeWaitCycles = 0;
            lastFingerprint = fingerprint;
            lastAction = Action.COLLECT_QUICK;
            mc.gameMode.handleInventoryMouseClick(screen.getMenu().containerId,
                AnvilBlockEntity.SLOT_INPUT_MAIN, 0, ClickType.QUICK_MOVE, mc.player);
            return;
        }

        if (recipe != null)
        {
            // 敲打阶段 —— 判定与 AnvilAutoForge 相同，但不做"完成一击即停"（要连续推进两段配方）
            if (!isBloom(input))
            {
                stop(); // 主输入不是铁坯（玩家在锻别的东西），绝不代敲
                return;
            }
            if (!hasHammer(mc.player, anvil) || !canWork(anvil))
            {
                stop(); // 服务端会空操作并刷提示，预判停止
                return;
            }
            recipeWaitCycles = 0;
            final List<ForgeStep> path = AnvilPathSolver.solve(recipe.getRules(), forging.target(),
                TFCConfig.SERVER.anvilAcceptableWorkRange.get(), forging.work(), forging.lastSteps(), !forging.isWorked());
            if (path == null || path.isEmpty())
            {
                stop(); // 无解（不应发生：求解空间覆盖所有规则组合）
                return;
            }
            lastFingerprint = fingerprint;
            lastAction = Action.PRESS;
            PacketDistributor.sendToServer(new ScreenButtonPacket(path.get(0).ordinal()));
            return;
        }

        if (!input.isEmpty())
        {
            if (isBloom(input))
            {
                // 刚填充的方坯等 1-2t 才能拿到服务端自动选中的配方；超时 = 铁砧等级不够
                if (++recipeWaitCycles > MAX_STALLS)
                {
                    stop();
                }
                return;
            }
            stop(); // 未知物品且无配方，不动它（锭已在上方优先处理）
            return;
        }

        // 主输入槽空 → 填充下一块温度合适的铁坯
        recipeWaitCycles = 0;
        final int invSlot = findHotBloom(mc.player);
        if (invSlot < 0)
        {
            stop(); // 背包里没有温度合适的生铁方坯了，收工
            return;
        }
        final int menuSlot = menuSlotForInventoryItem(screen.getMenu(), mc.player.getInventory(), invSlot);
        if (menuSlot < 0)
        {
            stop();
            return;
        }
        lastFingerprint = fingerprint;
        lastAction = Action.FILL;
        mc.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, menuSlot, 0, ClickType.QUICK_MOVE, mc.player);
    }

    private static void stop()
    {
        autoActive = false;
        stallCount = 0;
        collectTries = 0;
        recipeWaitCycles = 0;
        lastAction = Action.NONE;
        lastFingerprint = null;
    }

    /** 供 AnvilAutoForge 启动时互斥调用 */
    static void cancel()
    {
        stop();
    }

    // ---------- 物品判定 ----------

    /** 生铁/锻铁方坯（锻打链的两种中间态） */
    private static boolean isBloom(ItemStack stack)
    {
        return stack.is(TFCItems.RAW_IRON_BLOOM.get()) || stack.is(TFCItems.REFINED_IRON_BLOOM.get());
    }

    private static boolean isWroughtIronIngot(ItemStack stack)
    {
        return stack.is(TFCItems.METAL_ITEMS.get(Metal.WROUGHT_IRON).get(Metal.ItemType.INGOT).get());
    }

    /**
     * 背包扫描：第一块温度合适的铁坯（Inventory.items 下标，未找到返回 -1）。
     * canWork = 温度 ≥ 锻造温度（item_heat 数据，方坯 921°）；客户端读同步栈拿到的已是当前真实温度。
     */
    private static int findHotBloom(Player player)
    {
        final List<ItemStack> items = player.getInventory().items;
        for (int i = 0; i < items.size(); i++)
        {
            final ItemStack stack = items.get(i);
            if (isBloom(stack))
            {
                final IHeat heat = HeatCapability.get(stack);
                if (heat != null && heat.canWork())
                {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Inventory.items 下标 → 菜单槽位下标（菜单槽直接绑定玩家背包对象，按 container+containerSlot 匹配） */
    private static int menuSlotForInventoryItem(AbstractContainerMenu menu, Inventory inv, int invSlotIndex)
    {
        for (int i = 0; i < menu.slots.size(); i++)
        {
            final Slot slot = menu.slots.get(i);
            if (slot.container == inv && slot.getContainerSlot() == invSlotIndex)
            {
                return i;
            }
        }
        return -1;
    }

    /** 与 AnvilBlockEntity.work 相同的锤子判定：砧子锤子槽 → 主手 → 副手 */
    private static boolean hasHammer(Player player, AnvilBlockEntity anvil)
    {
        return Helpers.isItem(anvil.getInventory().getStackInSlot(AnvilBlockEntity.SLOT_HAMMER), TFCTags.Items.TOOLS_HAMMER)
            || Helpers.isItem(player.getMainHandItem(), TFCTags.Items.TOOLS_HAMMER)
            || Helpers.isItem(player.getOffhandItem(), TFCTags.Items.TOOLS_HAMMER);
    }

    /** 与 AnvilBlockEntity.work 相同的温度判定，温度不足时服务端会空操作 */
    private static boolean canWork(AnvilBlockEntity anvil)
    {
        final IHeat heat = HeatCapability.get(anvil.getInventory().getStackInSlot(AnvilBlockEntity.SLOT_INPUT_MAIN));
        return heat == null || heat.canWork();
    }
}

package icu.dreamripples.eye.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import icu.dreamripples.eye.ArtisansEye;

import net.dries007.tfc.common.blocks.TFCBlocks;
import net.dries007.tfc.common.items.PropickItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 高岭土接近提示：手持勘矿镐（TFC 勘矿镐或 Precision Prospecting 三种勘矿镐）时，
 * 左下角持续显示 128 格内最近高岭土的距离与罗盘表盘（复用原版指南针 32 帧精灵图，
 * 相对玩家视角选帧，红针=目标方向，玩家跟着红针转向即可，无需分辨东南西北），
 * 多区域时附加"另有 N 处"；少于 {@link #MIN_BLOCKS_PER_REGION} 块的簇不提示（排除玩家搬运摆放的零星高岭土）。
 * 未发现则什么都不画（保持画面干净）。文字放左下角是因为左上常有 FPS/mod 指示、右上常被小地图占用。
 * <p>
 * "区域" = 贪心聚类（合并半径 {@link #CLUSTER_MERGE_RADIUS}）：单个矿脉盘 footprint ≤ ±18 ⇒ 直径 ≤37，
 * 必落同簇；相邻相接的两个盘会并成一簇——视觉上本就是一个区域。距离/方位始终指向最近的有效方块。
 * <p>
 * 生成事实（TFC 4.2.10 源码核实）：高岭土 = {@link net.dries007.tfc.world.feature.vein.KaolinDiscVeinFeature}
 * 圆盘矿脉（1/50 区块、温暖 ≥18°C 且地下水 ≥300、内陆高地系 biome），只输出 4 种方块：
 * 白/粉/红高岭土 + 高岭土草方块；地表指示植物为火球花。
 * <p>
 * 纯客户端约束 ⇒ 只能扫已同步区块（多人下服务器不发种子，无法预计算矿脉）：
 * ClientTickEvent.Post 每 4 区块/tick 增量扫描玩家 ±8 区块（section 先 {@code PalettedContainer.maybeHas}
 * 预筛，命中才遍历 16³），缓存命中坐标；卸载即弃，并按 {@link #STALE_TICKS} 过期重扫
 * （玩家后期放置/挖除的高岭土最多 5s 反映到提示——曾因缓存永不失效导致手摆测试不显示）。局限：视距不足 8 区块时 128 格覆盖不全。
 * <p>
 * 1.21.1 API 备忘（javap 补丁源核实）：{@code ClientChunkCache} 没有 getChunkNow，用
 * {@code Level.getChunk(cx, cz, ChunkStatus.FULL, false)}；{@code LevelChunkSection.getStates()}
 * （不是 getBlocks）返回 {@code PalettedContainer<BlockState>}。
 */
@EventBusSubscriber(modid = ArtisansEye.MODID, value = Dist.CLIENT)
public final class KaolinHint
{
    /** 提示半径（格，对玩家位置的欧氏距离） */
    private static final int SCAN_RANGE = 128;
    /** 扫描圈 = 玩家区块 ±CHUNK_RADIUS；圆边角略超出部分由精确距离判定兜底 */
    private static final int CHUNK_RADIUS = SCAN_RANGE >> 4;
    /** 簇合并半径：单个盘直径 ≤37（footprint ±18），40 保证整盘归入同簇 */
    private static final int CLUSTER_MERGE_RADIUS = 40;
    private static final long CLUSTER_MERGE_RADIUS_SQ = (long) CLUSTER_MERGE_RADIUS * CLUSTER_MERGE_RADIUS;
    /** 区域块数阈值：少于该块数的簇不提示（排除玩家搬运摆放的零星高岭土） */
    private static final int MIN_BLOCKS_PER_REGION = 32;
    /** 调度周期：每 20 tick 重排待扫区块队列并清理失效缓存 */
    private static final int SCHEDULE_INTERVAL_TICKS = 20;
    /** 每 tick 最多扫描的区块数，摊平首轮扫描（289 块 ≈ 4s）的瞬时开销 */
    private static final int CHUNKS_PER_TICK = 4;
    /** 缓存过期重扫周期（5s）：玩家放置/挖除高岭土后最多 5s 反映到提示 */
    private static final int STALE_TICKS = 100;
    private static final int FONT_HEIGHT = 9;
    private static final int MARGIN = 4;
    private static final int TEXT_COLOR = 0xFFFFFF;
    /** 原版指南针表盘（compass_00..31 帧）绘制尺寸：必须 16 = 原尺寸 1:1，非整数倍缩小会糊色发黄 */
    private static final int NEEDLE_SIZE = 16;
    /** 表盘 16px 高于文本行 9px，垂直居中需要的上移量 = (16-9)/2 */
    private static final int NEEDLE_RAISE = 3;
    private static final int NEEDLE_GAP = 5;

    /** {@link KaolinDiscVeinFeature} 的全部输出方块；懒初始化（首次进世界时注册必已完成） */
    private static volatile @Nullable Set<Block> targets;

    /** 区块键 -> 扫描结果（命中坐标 + 扫描时刻）；空集 = 已扫描、无命中；过期重扫 */
    private static final Long2ObjectOpenHashMap<ChunkScan> cache = new Long2ObjectOpenHashMap<>();
    private static final ArrayDeque<Long> scanQueue = new ArrayDeque<>();
    /** 已过期待重扫的区块键：重扫完成前旧数据仍在缓存里供显示，避免成批过期导致的"明灭闪烁" */
    private static final LongOpenHashSet refresh = new LongOpenHashSet();
    /** 每轮调度重建一次的区域列表（已过 32 块阈值过滤）；仅 tick 线程读写 */
    private static List<Region> regions = List.of();
    private static ResourceKey<Level> dimension;
    /** 客户端自走 tick 时钟（不依赖 gameTime，避免服务器时间同步回跳） */
    private static long tickCounter;

    /** 一个区块的扫描结果 */
    private record ChunkScan(LongOpenHashSet blocks, long scannedAt) {}

    /** 手持勘矿镐且范围内有高岭土时的左下角显示：文本 +（可选）罗盘针角度（视角相对，0 = 正前方） */
    private record Hint(Component line, float needleAngleDeg, boolean hasNeedle) {}
    private static volatile @Nullable Hint hint;

    private KaolinHint() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event)
    {
        final Minecraft mc = Minecraft.getInstance();
        hint = null;
        if (mc.level == null || mc.player == null)
        {
            cache.clear();
            scanQueue.clear();
            refresh.clear();
            dimension = null;
            return;
        }
        final ClientLevel level = mc.level;
        if (dimension != level.dimension())
        {
            cache.clear();
            scanQueue.clear();
            refresh.clear();
            dimension = level.dimension();
        }
        if (level.dimension() != Level.OVERWORLD)
        {
            return; // 高岭土只在主世界生成
        }

        tickCounter++;
        if (tickCounter % SCHEDULE_INTERVAL_TICKS == 0)
        {
            schedule(level, new ChunkPos(mc.player.blockPosition()));
        }
        for (int i = 0; i < CHUNKS_PER_TICK && !scanQueue.isEmpty(); i++)
        {
            final long key = scanQueue.poll();
            if (refresh.remove(key) || !cache.containsKey(key))
            {
                scanChunk(level, key);
            }
        }

        // 前提：手持勘矿镐（主手或副手都算"手持"）
        if (isProspector(mc.player.getMainHandItem()) || isProspector(mc.player.getOffhandItem()))
        {
            hint = buildHint(mc.player.position(), mc.player.blockPosition(), mc.player.getYRot());
        }
    }

    @SubscribeEvent
    public static void onHudRender(RenderGuiEvent.Post event)
    {
        final Hint current = hint;
        if (current == null)
        {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui)
        {
            return;
        }
        final Font font = mc.font;
        final int y = mc.getWindow().getGuiScaledHeight() - FONT_HEIGHT - MARGIN;
        final GuiGraphics gui = event.getGuiGraphics();
        gui.drawString(font, current.line(), MARGIN, y, TEXT_COLOR, true);
        if (current.hasNeedle())
        {
            drawCompass(gui, MARGIN + font.width(current.line()) + NEEDLE_GAP, y - NEEDLE_RAISE, current.needleAngleDeg());
        }
    }

    /**
     * 原版指南针表盘：compass_00..31 共 32 帧（每帧 11.25°，帧号沿顺时针），compass_16 红针朝上。
     * 这些帧在物品图集（blocks atlas）里；GuiGraphics.blitSprite 查的是专用 gui 图集会 miss
     * （渲染成粉黑格），必须走 Minecraft.getTextureAtlas(BLOCK_ATLAS) 直取后用 blit(x,y,z,w,h,sprite)。
     * angleDeg = 目标方位相对玩家视角的角度（0 = 正前方、正值顺时针），直接换算帧号绘制。
     */
    private static void drawCompass(GuiGraphics gui, int x, int y, float angleDeg)
    {
        final float norm = (angleDeg % 360f + 360f) % 360f;
        final int frame = (Math.round(norm * (32f / 360f)) + 16) & 31;
        final String name = "item/compass_" + (frame < 10 ? "0" : "") + frame;
        final TextureAtlasSprite sprite = Minecraft.getInstance()
            .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
            .apply(ResourceLocation.withDefaultNamespace(name));
        gui.blit(x, y, 0, NEEDLE_SIZE, NEEDLE_SIZE, sprite);
    }

    /**
     * 重排扫描圈：清理过期（5s）/已卸载/出圈的缓存并重扫，把圈内的未扫区块入队（重复入队无害，消费端以缓存去重）。
     */
    private static void schedule(ClientLevel level, ChunkPos center)
    {
        final LongIterator it = cache.keySet().iterator();
        while (it.hasNext())
        {
            final long key = it.nextLong();
            final ChunkScan scan = cache.get(key);
            if (!isInScanRange(center, key)
                || level.getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), ChunkStatus.FULL, false) == null)
            {
                it.remove();
                refresh.remove(key);
            }
            else if (tickCounter - scan.scannedAt() >= STALE_TICKS)
            {
                // 过期不删：旧数据继续供显示，标记重扫、扫完原地覆盖
                // （否则世界加载时的成批同刻扫描会同时过期，空窗到重扫完成，HUD 一明一灭）
                refresh.add(key);
                scanQueue.add(key);
            }
        }
        for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++)
        {
            for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++)
            {
                final long key = ChunkPos.asLong(center.x + dx, center.z + dz);
                if (!cache.containsKey(key))
                {
                    scanQueue.add(key);
                }
            }
        }
        rebuildRegions();
    }

    private static boolean isInScanRange(ChunkPos center, long key)
    {
        return Math.max(Math.abs(ChunkPos.getX(key) - center.x), Math.abs(ChunkPos.getZ(key) - center.z)) <= CHUNK_RADIUS;
    }

    /**
     * 扫描单个已同步区块。只查不加载：未同步区块直接跳过，等下轮调度重试。
     */
    private static void scanChunk(ClientLevel level, long key)
    {
        if (!(level.getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), ChunkStatus.FULL, false) instanceof LevelChunk chunk))
        {
            return;
        }
        final Set<Block> targets = targets();
        final LongOpenHashSet found = new LongOpenHashSet();
        final LevelChunkSection[] sections = chunk.getSections();
        final int baseY = chunk.getMinBuildHeight();
        final int minX = chunk.getPos().getMinBlockX();
        final int minZ = chunk.getPos().getMinBlockZ();
        for (int si = 0; si < sections.length; si++)
        {
            final LevelChunkSection section = sections[si];
            if (section.hasOnlyAir())
            {
                continue;
            }
            // O(palette) 的 section 预筛：绝大多数 section 在这里直接跳过
            final PalettedContainer<BlockState> states = section.getStates();
            if (!states.maybeHas(state -> targets.contains(state.getBlock())))
            {
                continue;
            }
            final int worldY = baseY + (si << 4);
            for (int y = 0; y < 16; y++)
            {
                for (int z = 0; z < 16; z++)
                {
                    for (int x = 0; x < 16; x++)
                    {
                        if (targets.contains(section.getBlockState(x, y, z).getBlock()))
                        {
                            found.add(BlockPos.asLong(minX + x, worldY + y, minZ + z));
                        }
                    }
                }
            }
        }
        cache.put(key, new ChunkScan(found, tickCounter));
    }

    /** 把缓存里全部坐标按 40 格贪心聚成区域，并过滤掉少于 32 块的簇；每轮调度（1s）重建一次 */
    private static void rebuildRegions()
    {
        final List<Region> built = new ArrayList<>();
        for (ChunkScan scan : cache.values())
        {
            final LongIterator it = scan.blocks().iterator();
            while (it.hasNext())
            {
                final long packed = it.nextLong();
                Region target = null;
                for (int i = 0; i < built.size(); i++)
                {
                    final Region region = built.get(i);
                    if (withinMergeRadius(packed, region.blocks.getLong(0)))
                    {
                        target = region;
                        break;
                    }
                }
                if (target == null)
                {
                    target = new Region();
                    built.add(target);
                }
                target.blocks.add(packed);
            }
        }
        built.removeIf(region -> region.blocks.size() < MIN_BLOCKS_PER_REGION);
        regions = built;
    }

    private static boolean withinMergeRadius(long a, long b)
    {
        final long dx = BlockPos.getX(a) - BlockPos.getX(b);
        final long dy = BlockPos.getY(a) - BlockPos.getY(b);
        final long dz = BlockPos.getZ(a) - BlockPos.getZ(b);
        return dx * dx + dy * dy + dz * dz <= CLUSTER_MERGE_RADIUS_SQ;
    }

    /**
     * 区域 = ≥32 块的簇。"在范围内"按精确方块距离判定（簇内任一块 ≤128 即算）；
     * 显示的距离/罗盘角度指向最近的块，"另有 N 处" = 范围内区域数 − 1。
     */
    private static @Nullable Hint buildHint(Vec3 player, BlockPos playerPos, float yaw)
    {
        final double rangeSq = (double) SCAN_RANGE * SCAN_RANGE;
        double bestSq = rangeSq;
        long best = 0L;
        boolean found = false;
        int regionsInRange = 0;
        for (Region region : regions)
        {
            boolean hit = false;
            final LongIterator it = region.blocks.iterator();
            while (it.hasNext())
            {
                final long packed = it.nextLong();
                final double dx = BlockPos.getX(packed) + 0.5 - player.x;
                final double dy = BlockPos.getY(packed) + 0.5 - player.y;
                final double dz = BlockPos.getZ(packed) + 0.5 - player.z;
                final double sq = dx * dx + dy * dy + dz * dz;
                if (sq < bestSq)
                {
                    bestSq = sq;
                    best = packed;
                    found = true;
                }
                if (sq <= rangeSq)
                {
                    hit = true;
                }
            }
            if (hit)
            {
                regionsInRange++;
            }
        }
        if (!found)
        {
            return null;
        }
        final int dx = BlockPos.getX(best) - playerPos.getX();
        final int dz = BlockPos.getZ(best) - playerPos.getZ();
        if (dx == 0 && dz == 0)
        {
            Component here = Component.translatable("hint.artisanseye.kaolin.here");
            if (regionsInRange > 1)
            {
                here = here.copy().append(Component.translatable("hint.artisanseye.kaolin.more", regionsInRange - 1));
            }
            return new Hint(here, 0.0f, false);
        }
        // 罗盘角度：相对玩家视角，0 = 正前方（红针朝上），正值顺时针（目标在右）。
        // 我的世界 yaw：0=+Z(南)、90=−X(西)，yaw 增大 = 向右转；面朝目标的 yaw = atan2(−dx, dz)
        final double bearing = Math.toDegrees(Math.atan2(-dx, dz));
        final float rel = Mth.wrapDegrees((float) (bearing - yaw));
        Component text = Component.translatable("hint.artisanseye.kaolin", (int) Math.round(Math.sqrt(bestSq)));
        if (regionsInRange > 1)
        {
            text = text.copy().append(Component.translatable("hint.artisanseye.kaolin.more", regionsInRange - 1));
        }
        return new Hint(text, rel, true);
    }

    /** 一个"区域"：一次贪心聚类得到的方块集合（首个坐标即簇锚点） */
    private static final class Region
    {
        final LongArrayList blocks = new LongArrayList();
    }

    private static boolean isProspector(ItemStack stack)
    {
        final Item item = stack.getItem();
        return item instanceof PropickItem || PrecProsCompat.isProspector(item);
    }

    private static Set<Block> targets()
    {
        Set<Block> set = targets;
        if (set == null)
        {
            synchronized (KaolinHint.class)
            {
                set = targets;
                if (set == null)
                {
                    set = Set.of(
                        TFCBlocks.WHITE_KAOLIN_CLAY.get(),
                        TFCBlocks.PINK_KAOLIN_CLAY.get(),
                        TFCBlocks.RED_KAOLIN_CLAY.get(),
                        TFCBlocks.KAOLIN_CLAY_GRASS.get());
                    targets = set;
                }
            }
        }
        return set;
    }
}

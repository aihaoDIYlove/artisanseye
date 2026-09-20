package icu.dreamripples.eye.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import icu.dreamripples.eye.ArtisansEye;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.dries007.tfc.common.LevelTier;
import net.dries007.tfc.common.TFCTags;
import net.dries007.tfc.common.items.PropickItem;
import net.dries007.tfc.util.Helpers;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.tags.TagKey;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import org.joml.Matrix4f;

/**
 * 勘矿镐透视高亮：右键勘探时，把扫描范围（TFC 同款 ±12 盒）内的可勘探方块以半透明外壳
 * 透墙高亮数秒，随后线性淡出。纯客户端、零 mixin。
 * <p>
 * 数据链路（均已对照源码核实）：
 * <ul>
 * <li><b>触发</b>：TFC 与 Precision Prospecting（可选前置，见
 * {@link PrecProsCompat}）的勘探逻辑全部在服务端分支执行，客户端只能自行识别。监听
 * RightClickBlock 后不立即渲染——NeoForge 的 performUseItemOn 中该事件早于冷却检查
 * 与方块自身交互（后者会吞掉点击），故先做冷却预检防连点误触发，再等冷却实际生效
 * （服务端 addCooldown 自动同步到客户端）作为"勘探确实发生了"的确认信号，约数 tick 内
 * 未确认则丢弃。</li>
 * <li><b>扫描复刻</b>：按工具类型在客户端区块上复刻其扫描（getRepresentative 归并矿品位阶 +
 * 各自的 PROSPECTABLE tag），并按各自盐值（Helpers.hash(盐, pos)）复刻假阴性 roll——假阴性时
 * 不高亮，与原版行动栏文案永远一致。两个 roll 跨 JVM 精确；仅"报告哪种矿"的选取在
 * 专用服上不保序，而本特性高亮全部矿脉，不受影响。</li>
 * <li><b>渲染</b>：AFTER_TRIPWIRE_BLOCKS 阶段（AFTER_TRANSLUCENT 对半透明排序不友好，见
 * Stage javadoc）。提交管线照搬 TC-VeinGlow（MIT，同 MC 1.21.1/NeoForge）验证过的路径：
 * 自建空 PoseStack 只做 translate(−相机)，顶点用 {@code addVertex(pose, 世界坐标)} 把矩阵
 * 烘进顶点，Tesselator + 手动 GL 状态 + {@code BufferUploader.drawWithShader} 直绘。
 * 此阶段 RenderSystem modelview 为纯相机旋转（renderLevel 开头乘入、结尾弹出，drawWithShader
 * 取其作为 shader 的 ModelViewMat），旋转由此提供。切勿用裸 {@code addVertex(x,y,z)}——
 * 它不应用任何矩阵（见 emitCube 注释）。Frustum.isVisible 吃世界坐标（内部自减相机位置）。
 * 两层：半透明外壳填充 + 纯色线框（GL LINES），均透墙（depth ALWAYS）。</li>
 * </ul>
 * 已知限制：反 x-ray 服务器客户端区块无矿石，无从高亮（与 mod 描述一致）。
 */
@EventBusSubscriber(modid = ArtisansEye.MODID, value = Dist.CLIENT)
public final class ProspectHighlighter
{
    /** 高亮总时长（tick），整段线性淡出 */
    private static final int DURATION_TICKS = 80;
    /** 点击后等待冷却生效的确认窗口（tick）；超时视为点击被方块交互等吞掉 */
    private static final int CONFIRM_TIMEOUT_TICKS = 6;
    /** 单次高亮的方块数上限（超出保留离点击点最近的） */
    private static final int MAX_BOXES = 500;
    /** 满亮度时的基础不透明度（0–255）；太淡会把矿色冲成"白蒙蒙"，100 是形状与色彩可辨的平衡点 */
    private static final int BASE_ALPHA = 100;
    /** 线框不透明度（0–255）；线框是矿种颜色的主要标识 */
    private static final int EDGE_ALPHA = 230;
    /** 线框线宽（GL）；桌面驱动普遍支持，不支持时自动回落 1px 仅影响观感 */
    private static final float EDGE_WIDTH = 2.0f;
    /** 未知矿种的兜底色：中性灰 */
    private static final int FALLBACK_COLOR = 0x9C9C9C;

    /** TFC 自身有 TFC Propspective 假阴性公式，这里镜像之：0.3 − clamp(等级,0,5) × 0.06 */
    private static final float FALSE_NEGATIVE_BASE = 0.3f;
    private static final float FALSE_NEGATIVE_PER_LEVEL = 0.3f / 5f;
    /** TFC PropickItem 里的固定盐值，用于确定性假阴性 roll */
    private static final long FALSE_NEGATIVE_SALT = 19827384739241223L;

    /**
     * 矿种配色（键 = 注册路径 ore/<grade>_&lt;矿&gt;/&lt;岩石&gt; 中的矿 id）。
     * 配色刻意把常见相邻矿种沿色相/明度拉开（铜青绿、镍草绿、孔雀石翠绿三绿分家；
     * 金亮黄、硫纯黄、黄铁矿铜黄、硝石卡其四黄分家；赤铁砖红与朱砂鲜红分家；
     * 蓝系深蓝/亮蓝/天蓝/青分家）。白色系矿（银/石膏/盐/蛋白石）本身同族，
     * 靠明度与冷暖差区分。填充与线框都直接用表色，不做提亮洗白。
     */
    private static final Map<String, Integer> ORE_COLORS = Map.ofEntries(
        Map.entry("native_copper", 0x2FB898),
        Map.entry("native_gold", 0xFFD028),
        Map.entry("hematite", 0xC03828),
        Map.entry("native_silver", 0xE8ECF2),
        Map.entry("cassiterite", 0x9AA0A8),
        Map.entry("bismuthinite", 0xC8A0E8),
        Map.entry("garnierite", 0x50E050),
        Map.entry("malachite", 0x00B060),
        Map.entry("magnetite", 0x50505C),
        Map.entry("limonite", 0xC88028),
        Map.entry("sphalerite", 0x9C7030),
        Map.entry("tetrahedrite", 0xB87840),
        Map.entry("gypsum", 0xF4F4F0),
        Map.entry("cinnabar", 0xFF4848),
        Map.entry("cryolite", 0x30C0C0),
        Map.entry("borax", 0x70A8F0),
        Map.entry("graphite", 0x282830),
        Map.entry("saltpeter", 0xD8C880),
        Map.entry("sulfur", 0xF0F040),
        Map.entry("sylvite", 0xF0A0C0),
        Map.entry("amethyst", 0xA050E0),
        Map.entry("diamond", 0x70E0F0),
        Map.entry("emerald", 0x20C860),
        Map.entry("lapis_lazuli", 0x2040D0),
        Map.entry("opal", 0xE8D8FF),
        Map.entry("pyrite", 0xD8B830),
        Map.entry("ruby", 0xF02858),
        Map.entry("sapphire", 0x3868F0),
        Map.entry("topaz", 0xF0A830),
        Map.entry("bituminous_coal", 0x383840),
        Map.entry("lignite", 0x584838),
        Map.entry("halite", 0xC0D8F0)
    );

    /** 6 面（顺序 = Direction.values()：DOWN,UP,NORTH,SOUTH,WEST,EAST）各 4 顶点的单位立方体角点 */
    private static final float[][] FACE_CORNERS = {
        {0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1}, // DOWN
        {0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0}, // UP
        {0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0}, // NORTH
        {1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1}, // SOUTH
        {0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0}, // WEST
        {1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1}, // EAST
    };
    /** 各面明暗（同上顺序），给纯色外壳一点立体感 */
    private static final float[] FACE_SHADE = {0.55f, 1.0f, 0.75f, 0.75f, 0.85f, 0.85f};

    /** 单位立方体 8 角点（索引位：bit0=x, bit1=y, bit2=z），线框用 */
    private static final float[][] CUBE_CORNERS = {
        {0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {1, 1, 0},
        {0, 0, 1}, {1, 0, 1}, {0, 1, 1}, {1, 1, 1},
    };
    /** 12 条棱（GL LINES 按顶点对消费） */
    private static final int[][] CUBE_EDGES = {
        {0, 1}, {2, 3}, {4, 5}, {6, 7}, // x 向
        {0, 2}, {1, 3}, {4, 6}, {5, 7}, // y 向
        {0, 4}, {1, 5}, {2, 6}, {3, 7}, // z 向
    };

    private ProspectHighlighter() {}

    /** 等待确认的一次右键（propick 实例只用于查冷却；face 供 PP 位移盒计算勘探方向） */
    private record PendingUse(Item propick, BlockPos pos, Direction face, long deadline) {}

    /** 一次已确认勘探的高亮缓存（不可变快照，按帧只读遍历） */
    private record Scan(long startTime, Map<BlockPos, Integer> colors) {}

    private static PendingUse pending;
    private static Scan scan;

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event)
    {
        if (!event.getLevel().isClientSide() || !(event.getEntity() instanceof LocalPlayer player))
        {
            return;
        }
        final Item item = event.getItemStack().getItem();
        if (!(item instanceof PropickItem) && !PrecProsCompat.isProspector(item))
        {
            return;
        }
        // 冷却中的点击到不了 useOn（冷却检查在事件之后），直接无视，防连点误触发
        if (player.getCooldowns().isOnCooldown(item))
        {
            return;
        }
        pending = new PendingUse(item, event.getPos(), event.getFace(),
            player.level().getGameTime() + CONFIRM_TIMEOUT_TICKS);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event)
    {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
        {
            pending = null;
            scan = null;
            return;
        }
        final long now = mc.level.getGameTime();
        if (pending != null)
        {
            if (mc.player.getCooldowns().isOnCooldown(pending.propick()))
            {
                startScan(mc.level, pending.propick(), pending.pos(), pending.face());
                pending = null;
            }
            else if (now > pending.deadline())
            {
                pending = null; // 冷却未出现 ⇒ 点击被方块自身交互等吞掉，勘探没有发生
            }
        }
        if (scan != null && now - scan.startTime() > DURATION_TICKS)
        {
            scan = null;
        }
    }

    /**
     * 按工具类型复刻其扫描 + 假阴性判定，生成高亮快照（propick = 触发本次勘探的那支，可能持有于副手）。
     * TFC PropickItem：以点击点为中心 ±RADIUS 立方盒、PROSPECTABLE tag、盐 19827384739241223；
     * Precision Prospecting：主半径盒 + 点击面反面方向的位移次级盒、私有 tag、盐 1564454769121215456
     * （参数逐项见 PrecProsCompat）。
     */
    private static void startScan(ClientLevel level, Item propick, BlockPos center, Direction face)
    {
        final TagKey<Block> tag;
        final float falseNegativeChance;
        final long salt;
        int x1, y1, z1, x2, y2, z2;
        if (propick instanceof PropickItem)
        {
            tag = TFCTags.Blocks.PROSPECTABLE;
            falseNegativeChance = falseNegativeChance(propick);
            salt = FALSE_NEGATIVE_SALT;
            final int radius = PropickItem.RADIUS;
            x1 = center.getX() - radius;
            y1 = center.getY() - radius;
            z1 = center.getZ() - radius;
            x2 = center.getX() + radius;
            y2 = center.getY() + radius;
            z2 = center.getZ() + radius;
        }
        else if (PrecProsCompat.isProspector(propick))
        {
            tag = PrecProsCompat.prospectTag(propick);
            falseNegativeChance = PrecProsCompat.falseNegativeChance(propick);
            salt = PrecProsCompat.FALSE_NEGATIVE_SALT;
            final int primary = PrecProsCompat.primaryRadius(propick);
            final int secondary = PrecProsCompat.secondaryRadius(propick);
            final int displacement = PrecProsCompat.displacement(propick);
            x1 = center.getX() - primary;
            y1 = center.getY() - primary;
            z1 = center.getZ() - primary;
            x2 = center.getX() + primary;
            y2 = center.getY() + primary;
            z2 = center.getZ() + primary;
            // 勘探方向 = 点击面的反面；该轴换成位移后的次级盒（ProspectorItem.useOn 的 switch 语义）
            final Direction dir = face.getOpposite();
            final int axisOffset = dir.getAxisDirection().getStep() * displacement;
            switch (dir.getAxis())
            {
                case X -> { x1 = center.getX() + axisOffset - secondary; x2 = center.getX() + axisOffset + secondary; }
                case Y -> { y1 = center.getY() + axisOffset - secondary; y2 = center.getY() + axisOffset + secondary; }
                case Z -> { z1 = center.getZ() + axisOffset - secondary; z2 = center.getZ() + axisOffset + secondary; }
            }
        }
        else
        {
            return;
        }

        // 假阴性判定与两个 mod 的 useOn 逐行对应：点击处本身即可勘探则不 roll；否则按概率"未见矿脉"→ 不高亮
        final BlockState clicked = level.getBlockState(center);
        if (!Helpers.isBlock(clicked, tag))
        {
            final Random random = new Random();
            random.setSeed(Helpers.hash(salt, center));
            if (random.nextFloat() < falseNegativeChance)
            {
                return;
            }
        }

        final Map<BlockPos, Integer> colors = new HashMap<>();
        for (BlockPos cursor : BlockPos.betweenClosed(x1, y1, z1, x2, y2, z2))
        {
            final Block block = PropickItem.getRepresentative(level.getBlockState(cursor).getBlock());
            if (Helpers.isBlock(block, tag))
            {
                colors.put(cursor.immutable(), oreColor(block));
            }
        }

        if (colors.size() > MAX_BOXES)
        {
            final List<BlockPos> keys = new ArrayList<>(colors.keySet());
            keys.sort(Comparator.comparingDouble(key -> key.distSqr(center)));
            final Map<BlockPos, Integer> trimmed = new HashMap<>(MAX_BOXES);
            for (BlockPos key : keys.subList(0, MAX_BOXES))
            {
                trimmed.put(key, colors.get(key));
            }
            scan = new Scan(level.getGameTime(), trimmed);
            return;
        }
        scan = colors.isEmpty() ? null : new Scan(level.getGameTime(), colors);
    }

    /** 镜像 PropickItem 构造器公式；经 TieredItem.getTier() 取 TFC 的 LevelTier（兼容窗口内字节码不变） */
    private static float falseNegativeChance(Item propick)
    {
        if (!(propick instanceof TieredItem tiered) || !(tiered.getTier() instanceof LevelTier tier))
        {
            return FALSE_NEGATIVE_BASE;
        }
        return FALSE_NEGATIVE_BASE - Mth.clamp(tier.level(), 0, 5) * FALSE_NEGATIVE_PER_LEVEL;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event)
    {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS || scan == null)
        {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
        {
            return;
        }

        // 线性淡出（partialTick 令渐隐平滑）；过期即弃，避免每帧空遍历
        final float partial = event.getPartialTick().getGameTimeDeltaPartialTick(!mc.isPaused());
        final float age = (mc.level.getGameTime() - scan.startTime()) + partial;
        final float fade = 1.0f - Mth.clamp(age / DURATION_TICKS, 0.0f, 1.0f);
        if (fade <= 0.0f)
        {
            scan = null;
            return;
        }

        final Vec3 cam = event.getCamera().getPosition();
        final Frustum frustum = event.getFrustum();
        final Map<BlockPos, Integer> colors = scan.colors();

        // 远→近排序：半透明叠加的正确绘制顺序
        final List<BlockPos> order = new ArrayList<>(colors.keySet());
        order.sort(Comparator.comparingDouble((BlockPos pos) -> pos.distToCenterSqr(cam)).reversed());

        // 渲染契约（TC-VeinGlow 同款验证路径）：此阶段 RenderSystem modelview = 纯相机旋转，
        // 顶点经 addVertex(pose, 世界坐标) 烘成「世界 − 相机」，旋转交给 shader 的 ModelViewMat
        final PoseStack poses = new PoseStack();
        poses.pushPose();
        poses.translate(-cam.x, -cam.y, -cam.z);
        final Matrix4f mat = poses.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(519); // GL_ALWAYS：透墙
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        final BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        final int alpha = (int) (BASE_ALPHA * fade);
        final int edgeAlpha = (int) (EDGE_ALPHA * fade);

        // 第一层：矿脉外壳半透明填充。Frustum.isVisible 吃【世界坐标】——它内部自减相机位置，
        // 传相机相对坐标等于减两次，盒子永远落在视锥外
        final BufferBuilder fill = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (BlockPos pos : order)
        {
            if (frustum.isVisible(new AABB(pos)))
            {
                emitCube(fill, mat, pos, neighbor, colors.get(pos), alpha, colors);
            }
        }
        final MeshData fillMesh = fill.build();
        if (fillMesh != null)
        {
            BufferUploader.drawWithShader(fillMesh);
        }

        // 第二层：透墙线框（矿种颜色主要靠这一层辨认，直接用纯矿色不洗白）。
        // 一个 BufferBuilder 只承载一种顶点格式，GL LINES 走第二份顶点流
        RenderSystem.lineWidth(EDGE_WIDTH);
        final BufferBuilder edges = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR);
        for (BlockPos pos : order)
        {
            if (frustum.isVisible(new AABB(pos)))
            {
                emitEdges(edges, mat, pos, colors.get(pos), edgeAlpha);
            }
        }
        final MeshData edgeMesh = edges.build();
        if (edgeMesh != null)
        {
            BufferUploader.drawWithShader(edgeMesh);
        }

        // 恢复 GL 状态（与 TC-VeinGlow 相同的还原序列）
        RenderSystem.lineWidth(1.0f);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.depthFunc(515); // GL_LEQUAL

        poses.popPose();
    }

    /**
     * 画单个方块的外壳面：内部共享面（相邻块也在高亮集内）跳过，矿脉呈整体轮廓。
     * <p>
     * 顶点必须带矩阵：{@code addVertex(pose, x,y,z)} 把 pose 变换烘进顶点后写原始坐标；
     * 而 {@code addVertex(x,y,z)} 是裸坐标，PoseStack 上的 translate 对它不生效——
     * 若只 translate 再发 0..1 局部角点，所有盒子会叠在相机处的单位立方体上，
     * 经近平面裁剪成"随相机移动的灰色多边形"（v1/v3 错位灰片的根因）。
     */
    private static void emitCube(BufferBuilder buffer, Matrix4f mat, BlockPos pos,
                                 BlockPos.MutableBlockPos neighbor, int color, int alpha, Map<BlockPos, Integer> colors)
    {
        final int r = FastColor.ARGB32.red(color);
        final int g = FastColor.ARGB32.green(color);
        final int b = FastColor.ARGB32.blue(color);

        for (Direction face : Direction.values())
        {
            neighbor.setWithOffset(pos, face);
            if (colors.containsKey(neighbor))
            {
                continue;
            }
            final int fi = face.ordinal();
            final float shade = FACE_SHADE[fi];
            final int fr = (int) (r * shade);
            final int fg = (int) (g * shade);
            final int fb = (int) (b * shade);
            final float[] corners = FACE_CORNERS[fi];
            for (int v = 0; v < 4; v++)
            {
                buffer.addVertex(mat, pos.getX() + corners[v * 3], pos.getY() + corners[v * 3 + 1], pos.getZ() + corners[v * 3 + 2])
                    .setColor(fr, fg, fb, alpha);
            }
        }
    }

    /** 透墙线框：纯矿色，GL LINES 两顶点一段 */
    private static void emitEdges(BufferBuilder buffer, Matrix4f mat, BlockPos pos, int color, int alpha)
    {
        final int r = FastColor.ARGB32.red(color);
        final int g = FastColor.ARGB32.green(color);
        final int b = FastColor.ARGB32.blue(color);
        for (int[] edge : CUBE_EDGES)
        {
            for (int ci : edge)
            {
                final float[] c = CUBE_CORNERS[ci];
                buffer.addVertex(mat, pos.getX() + c[0], pos.getY() + c[1], pos.getZ() + c[2])
                    .setColor(r, g, b, alpha);
            }
        }
    }

    /** 由注册路径（ore/&lt;grade&gt;_&lt;矿&gt;/&lt;岩石&gt; 或 ore/&lt;矿&gt;/&lt;岩石&gt;）取矿 id 并查配色表 */
    private static int oreColor(Block block)
    {
        final String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        if (path.startsWith("ore/"))
        {
            String ore = path.substring(4);
            final int slash = ore.indexOf('/');
            if (slash >= 0)
            {
                ore = ore.substring(0, slash);
            }
            for (String grade : new String[] {"normal_", "rich_", "poor_"})
            {
                if (ore.startsWith(grade))
                {
                    ore = ore.substring(grade.length());
                    break;
                }
            }
            final Integer color = ORE_COLORS.get(ore);
            if (color != null)
            {
                return color;
            }
        }
        return FALLBACK_COLOR;
    }
}

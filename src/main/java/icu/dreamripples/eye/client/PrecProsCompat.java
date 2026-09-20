package icu.dreamripples.eye.client;

import java.lang.reflect.Field;
import java.util.function.IntSupplier;

import icu.dreamripples.eye.ArtisansEye;

import net.dries007.tfc.common.TFCTags;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;

/**
 * Precision Prospecting（TFC 附属，可选前置）勘矿镐兼容桥，全程反射、零编译依赖。
 * <p>
 * 该 mod 的 {@code ProspectorItem} 继承 ToolItem 而【非】TFC 的 PropickItem
 * （源码自称 "modified copy"），故 instanceof PropickItem 识别不到。其勘探逻辑与
 * TFC 同构但有三处差异，均已对照其 2.1 源码（Materials/precision-prospecting）核实：
 * <ul>
 * <li>扫描盒位移：以点击面的反面为勘探方向，主半径盒（±primaryRadius）之外的该轴
 * 改为「位移 displacement 后的次级盒（±secondaryRadius）」——钻头由此能探深处；
 * primaryRadius/secondaryRadius/displacement 是 public final IntSupplier 字段。</li>
 * <li>假阴性盐值不同：{@code Helpers.hash(1564454769121215456L, pos)}（TFC 是
 * 19827384739241223L），结构相同——首个 nextFloat() 即假阴性 roll，跨 JVM 精确。</li>
 * <li>可勘探 tag 是私有字段（矿物探矿仪用 precisionprospecting:prospectable_mineral，
 * 前两种锤/钻用 TFC PROSPECTABLE），falseNegativeChance 数值也是私有字段——
 * 经反射读取（ModLauncher 对 mod 模块全开包，setAccessible 可行）。
 * 反射失败时按 TFC 参数兜底，仅影响高亮与文案的一致性，不影响游戏本体。</li>
 * </ul>
 * 冷却机制与 TFC 相同（player.getCooldowns().addCooldown，15/30/40 tick），
 * 「冷却确认」触发无需改动。未装 PP 时所有反射入口经 {@link #isLoaded()}
 * 短路，不会触碰 PP 类。
 */
final class PrecProsCompat
{
    static final String MOD_ID = "precisionprospecting";
    /** ProspectorItem.useOn 里的固定盐值（源码硬编码常量，与 TFC 的不同） */
    static final long FALSE_NEGATIVE_SALT = 1564454769121215456L;
    private static final String PROSPECTOR_CLASS = "io.github.notenoughmail.precisionprospecting.items.ProspectorItem";

    private static volatile Class<?> prospectorClass;
    private static volatile Field primaryRadiusField;
    private static volatile Field secondaryRadiusField;
    private static volatile Field displacementField;
    private static volatile Field fnChanceField;
    private static volatile Field prospectTagField;
    private static volatile boolean resolved;

    private PrecProsCompat() {}

    static boolean isLoaded()
    {
        return ModList.get().isLoaded(MOD_ID);
    }

    static boolean isProspector(Item item)
    {
        return resolve() && prospectorClass.isInstance(item);
    }

    static int primaryRadius(Item item)
    {
        return intSupplierValue(primaryRadiusField, item);
    }

    static int secondaryRadius(Item item)
    {
        return intSupplierValue(secondaryRadiusField, item);
    }

    static int displacement(Item item)
    {
        return intSupplierValue(displacementField, item);
    }

    /** 私有 float 字段 falseNegativeChance；反射失败按 TFC 基础值兜底 */
    static float falseNegativeChance(Item item)
    {
        final Field field = fnChanceField;
        if (field != null)
        {
            try
            {
                return field.getFloat(item);
            }
            catch (IllegalAccessException ignored)
            {
            }
        }
        return 0.3f;
    }

    /** 私有 TagKey 字段 prospectTag；反射失败按 TFC PROSPECTABLE 兜底 */
    static TagKey<Block> prospectTag(Item item)
    {
        final Field field = prospectTagField;
        if (field != null)
        {
            try
            {
                @SuppressWarnings("unchecked")
                final TagKey<Block> tag = (TagKey<Block>) field.get(item);
                return tag;
            }
            catch (IllegalAccessException ignored)
            {
            }
        }
        return TFCTags.Blocks.PROSPECTABLE;
    }

    private static int intSupplierValue(Field field, Item item)
    {
        if (field != null)
        {
            try
            {
                return ((IntSupplier) field.get(item)).getAsInt();
            }
            catch (IllegalAccessException ignored)
            {
            }
        }
        return 0;
    }

    /**
     * 懒解析 PP 类与其全部字段；仅在 ModList 确认装有 PP 后才首次 Class.forName。
     * 任一环节失败记警告并返回 false（isProspector 恒 false，功能退回仅支持 TFC）。
     */
    private static boolean resolve()
    {
        if (resolved)
        {
            return prospectorClass != null;
        }
        synchronized (PrecProsCompat.class)
        {
            if (resolved)
            {
                return prospectorClass != null;
            }
            try
            {
                prospectorClass = Class.forName(PROSPECTOR_CLASS);
                primaryRadiusField = field(prospectorClass, "primaryRadius");
                secondaryRadiusField = field(prospectorClass, "secondaryRadius");
                displacementField = field(prospectorClass, "displacement");
                fnChanceField = field(prospectorClass, "falseNegativeChance");
                prospectTagField = field(prospectorClass, "prospectTag");
            }
            catch (ReflectiveOperationException e)
            {
                prospectorClass = null;
                ArtisansEye.LOGGER.warn("[artisanseye] Precision Prospecting 兼容初始化失败，其勘矿镐不支持高亮", e);
            }
            resolved = true;
            return prospectorClass != null;
        }
    }

    private static Field field(Class<?> clazz, String name) throws ReflectiveOperationException
    {
        final Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}

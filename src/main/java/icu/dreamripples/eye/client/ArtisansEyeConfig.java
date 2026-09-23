package icu.dreamripples.eye.client;

import net.minecraft.util.Mth;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 客户端配置（CLIENT 类型，{@code client/ArtisansEyeClient} 构造时注册）。
 * 配置屏（{@link ArtisansEyeConfigScreen}）改动经 {@code set + SPEC.save()} 即时落盘，
 * 读取方全部实时 {@code get}（值缓存由 set/save/reload 自维护），无事件监听。
 */
public final class ArtisansEyeConfig
{
    /** 自动锻造：铁砧界面显示"自动"按钮并允许模组代为敲击。默认关闭（会替玩家真实下击）。 */
    public static final ModConfigSpec.BooleanValue AUTO_FORGE;

    /** 自动敲击的间隔（游戏刻），范围 2–20。 */
    public static final ModConfigSpec.IntValue PRESS_INTERVAL;

    public static final ModConfigSpec SPEC;

    static
    {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        AUTO_FORGE = builder
            .comment("是否启用自动锻造：开启后铁砧界面出现\"自动\"按钮，可由模组代为完成锻造敲击")
            .translation("artisanseye.config.auto_forge")
            .define("autoForge", false);
        PRESS_INTERVAL = builder
            .comment("自动敲击的间隔（游戏刻），最低 2，最高 20")
            .translation("artisanseye.config.press_interval")
            .defineInRange("pressInterval", 2, 2, 20);
        SPEC = builder.build();
    }

    private ArtisansEyeConfig() {}

    /** 配置加载前的理论窗口期按默认值（关）处理 */
    public static boolean autoForgeEnabled()
    {
        return SPEC.isLoaded() && AUTO_FORGE.getAsBoolean();
    }

    public static int pressInterval()
    {
        return SPEC.isLoaded() ? PRESS_INTERVAL.get() : 2;
    }

    /** 配置屏写入用：set() 不做范围校验（只有加载时 correct 会钳位），必须在此钳位；落盘由调用方统一 SPEC.save() */
    static void setPressInterval(int value)
    {
        PRESS_INTERVAL.set(Mth.clamp(value, 2, 20));
    }
}

package icu.dreamripples.eye.client;

import java.util.Locale;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 客户端配置（CLIENT 类型，{@code client/ArtisansEyeClient} 构造时注册）。
 * 配置屏（{@link ArtisansEyeConfigScreen}）改动经 {@code set + SPEC.save()} 即时落盘，
 * 读取方全部实时 {@code get}（值缓存由 set/save/reload 自维护），无事件监听。
 */
public final class ArtisansEyeConfig
{
    // 测试账号白名单
    private static final Set<String> DEV_ACCOUNTS = Set.of("dev", "dreamripples", "lihua273");

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

    /**
     * 生效判定 = 配置值 或 测试账号命中。配置未加载的理论窗口期一律按关处理。
     */
    public static boolean autoForgeEnabled()
    {
        return SPEC.isLoaded() && (AUTO_FORGE.getAsBoolean() || isDevAccount());
    }

    /** 供配置屏提示用；客户端未完全启动（理论窗口期）时按非测试账号处理 */
    static boolean isDevAccount()
    {
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getUser() == null)
        {
            return false;
        }
        return DEV_ACCOUNTS.contains(mc.getUser().getName().toLowerCase(Locale.ROOT));
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

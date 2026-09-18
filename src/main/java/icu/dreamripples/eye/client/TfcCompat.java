package icu.dreamripples.eye.client;

import net.neoforged.fml.ModList;

/**
 * 运行时区间 [4.1.0,4.3) 内的 TFC 版本守卫。
 * 目前唯一已知的分歧：4.2.0 起砧子界面锻造条 x 偏移由 13 改为 11（4 处 blit 调用）。
 * 默认取最新布局（11），仅在检测到 <4.2 时回落到 13。
 */
final class TfcCompat
{
    static final int WORK_BAR_X_OFFSET = resolveWorkBarXOffset();

    private TfcCompat() {}

    private static int resolveWorkBarXOffset()
    {
        return ModList.get().getModContainerById("tfc")
            .map(container -> container.getModInfo().getVersion())
            .filter(version -> version.getMajorVersion() < 4
                || (version.getMajorVersion() == 4 && version.getMinorVersion() < 2))
            .map(version -> 13)
            .orElse(11);
    }
}

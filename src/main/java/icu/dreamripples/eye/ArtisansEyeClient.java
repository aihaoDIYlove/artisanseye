package icu.dreamripples.eye;

import icu.dreamripples.eye.client.ArtisansEyeConfig;
import icu.dreamripples.eye.client.ArtisansEyeConfigScreen;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * 纯客户端副入口：本模组的全部功能（锻造叠加层、探矿渲染等）只存在于客户端。
 * 事件监听通过 @EventBusSubscriber(Dist.CLIENT) 注册，这里只作为客户端侧生命周期锚点。
 * 本类只在客户端构造，因此注册引用了 vanilla Screen 的配置屏扩展点是安全的
 * （专用服永远不会加载此类，无 dist-clean 类校验问题）。
 */
@Mod(value = ArtisansEye.MODID, dist = Dist.CLIENT)
public class ArtisansEyeClient
{
    public ArtisansEyeClient(IEventBus modEventBus, ModContainer modContainer)
    {
        modContainer.registerConfig(ModConfig.Type.CLIENT, ArtisansEyeConfig.SPEC);
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
            (container, modListScreen) -> new ArtisansEyeConfigScreen(modListScreen));
    }
}

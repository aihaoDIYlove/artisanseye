package icu.dreamripples.eye;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * 纯客户端副入口：本模组的全部功能（锻造叠加层、探矿渲染等）只存在于客户端。
 * 事件监听通过 @EventBusSubscriber(Dist.CLIENT) 注册，这里只作为客户端侧生命周期锚点。
 */
@Mod(value = ArtisansEye.MODID, dist = Dist.CLIENT)
public class ArtisansEyeClient
{
    public ArtisansEyeClient(IEventBus modEventBus, ModContainer modContainer)
    {
    }
}

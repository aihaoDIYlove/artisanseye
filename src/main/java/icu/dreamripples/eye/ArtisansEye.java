package icu.dreamripples.eye;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;

@Mod(ArtisansEye.MODID)
public class ArtisansEye {
    public static final String MODID = "artisanseye";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ArtisansEye(IEventBus modEventBus, ModContainer modContainer) {
    }
}

package dev.mangotcdragon.villagernews;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(VillagerNews.MOD_ID)
public class VillagerNews {

    public static final String MOD_ID = "villagernews";

    private static final Logger LOGGER = LogUtils.getLogger();

    public VillagerNews() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModSounds.register(modBus);

        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info("Villager News loaded");
    }
}

package genandnic.walljump.proxy;

import genandnic.walljump.Config;
import genandnic.walljump.client.DoubleJumpLogic;
import genandnic.walljump.client.FallingSound;
import genandnic.walljump.client.SpeedBoostLogic;
import genandnic.walljump.client.WallJumpLogic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.stream.StreamSupport;
@Mod.EventBusSubscriber(modid = "walljump", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientProxy extends CommonProxy {

    public static final KeyMapping KEY_WALLJUMP = new KeyMapping("key.walljump.walljump", GLFW.GLFW_KEY_LEFT_SHIFT, "key.categories.movement");
    private static final Minecraft minecraft = Minecraft.getInstance();
    private static FallingSound FALLING_SOUND;

    @Override
    public void setupClient() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(KEY_WALLJUMP);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        LocalPlayer player = minecraft.player;

        if (event.phase != TickEvent.Phase.END || player == null) return;

        WallJumpLogic.doWallJump(player);
        DoubleJumpLogic.doDoubleJump(player);
        SpeedBoostLogic.doSpeedBoost(player);

        if (player.horizontalCollision && Config.COMMON.stepAssist.get() && player.getDeltaMovement().y > -0.2 && player.getDeltaMovement().y < 0.01) {
            if (!ClientProxy.collidesWithBlock(player.getCommandSenderWorld(), player.getBoundingBoxForCulling().inflate(0.01, -getMaxUpStep(player) + 0.02, 0.01))) {
                player.setOnGround(true);
            }
        }

        if (player.isSprinting() && player.getDeltaMovement().length() > 0.08)
            player.horizontalCollision = false;

        if (player.fallDistance > 1.5 && !player.isFallFlying()) {
            if (Config.COMMON.playFallSound.get() && (FALLING_SOUND == null || FALLING_SOUND.isStopped())) {
                FALLING_SOUND = new FallingSound(player);
                minecraft.getSoundManager().play(FALLING_SOUND);
            }
        }
    }

    @SubscribeEvent
    public void onJoinWorld(EntityJoinLevelEvent event) {
        if (event.getEntity() == minecraft.player && Config.COMMON.playFallSound.get()) {
            FALLING_SOUND = new FallingSound(minecraft.player);
            minecraft.getSoundManager().play(FALLING_SOUND);
        }
    }

    public static boolean collidesWithBlock(Level level, AABB box) {
        return StreamSupport.stream(level.getBlockCollisions(null, box).spliterator(), false).findAny().isPresent();
    }

    private static double getMaxUpStep(LocalPlayer player) {
        try {
            Field maxUpStepField = LocalPlayer.class.getSuperclass().getDeclaredField("maxUpStep");
            maxUpStepField.setAccessible(true);
            return (double) maxUpStepField.get(player);
        } catch (Exception e) {
            e.printStackTrace();
            return 0.6; // Default value if maxUpStep is not accessible
        }
    }
}

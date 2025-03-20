package genandnic.walljump.client;

import genandnic.walljump.Config;
import genandnic.walljump.network.PacketHandler;
import genandnic.walljump.network.message.MessageFallDistance;
import genandnic.walljump.network.message.MessageWallJump;
import genandnic.walljump.proxy.ClientProxy;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraft.network.chat.Component;

import java.awt.*;
import java.util.HashSet;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
public class WallJumpLogic {

    public static int ticksWallClinged;
    private static int ticksKeyDown;
    private static double clingX, clingZ;
    private static double lastJumpY = Double.MAX_VALUE;
    private static long nextJumpCooldown = 0;

    // Add these variables for jump count and cooldown tracking
    private static int jumpCount = 0;
    private static long lastJumpTime = 0;
    private static final int MAX_JUMPS = 5;
    private static final long COOLDOWN_DURATION = 15 * 1000; // 30 seconds cooldown in milliseconds

    public static void doWallJump(LocalPlayer pl) {
        if (!WallJumpLogic.canWallJump(pl))
            return;

        long currentTime = System.currentTimeMillis();

        // Check if the player needs to wait for the cooldown
        if (jumpCount >= MAX_JUMPS) {
            long cooldownRemaining = COOLDOWN_DURATION - (currentTime - lastJumpTime);
            if (cooldownRemaining > 0) {
                pl.displayClientMessage(Component.nullToEmpty("Cooldown active! " + (cooldownRemaining / 1000) + " seconds left."), true);
                return; // Player cannot jump due to cooldown
            } else {
                // Cooldown expired, reset jump count
                jumpCount = 0;
            }
        }

        if (pl.isOnGround() || pl.getAbilities().flying || pl.isInWater()) {
            // Reset when on the ground or in water
            ticksWallClinged = 0;
            clingX = Double.NaN;
            clingZ = Double.NaN;
            lastJumpY = Double.MAX_VALUE;
            staleWalls.clear();

            // Reset jump count if player lands
            if (pl.isOnGround()) {
                jumpCount = 0;
                lastJumpTime = 0; // Reset cooldown timer if on ground
            }

            return;
        }

        WallJumpLogic.updateWalls(pl);
        ticksKeyDown = ClientProxy.KEY_WALLJUMP.isDown() ? ticksKeyDown + 1 : 0;

        if (ticksWallClinged < 1) {
            if (ticksKeyDown > 0 && ticksKeyDown < 4 && !walls.isEmpty() && canWallCling(pl)) {
                if (Config.COMMON.autoRotation.get())
                    pl.setYRot(getClingDirection().getOpposite().toYRot());

                ticksWallClinged = 1;
                clingX = pl.position().x;
                clingZ = pl.position().z;

                playHitSound(pl, getWallPos(pl));
                spawnWallParticle(pl, getWallPos(pl));
            }

            return;
        }

        if (!ClientProxy.KEY_WALLJUMP.isDown() || pl.isOnGround() || pl.isInWater() || walls.isEmpty() || pl.getFoodData().getFoodLevel() < 1) {
            ticksWallClinged = 0;

            if ((pl.input.forwardImpulse != 0 || pl.input.leftImpulse != 0) && !pl.isOnGround() && !walls.isEmpty()) {
                pl.fallDistance = 0.0F;
                PacketHandler.INSTANCE.sendToServer(new MessageWallJump());

                wallJump(pl, Config.COMMON.wallJumpHeight.get().floatValue());
                staleWalls = new HashSet<>(walls);

                // Increase jump count and set last jump time
                jumpCount++;
                lastJumpTime = currentTime;

                // Check if jump limit is reached
                if (jumpCount >= MAX_JUMPS) {

                    pl.displayClientMessage(Component.nullToEmpty("Max jumps reached! Cooldown active."), true);
                }

            }

            return;
        }

        pl.setPos(clingX, pl.position().y, clingZ);

        double motionY = pl.getDeltaMovement().y;
        if (motionY > 0.0) {
            motionY = 0.0;
        } else if (motionY < -0.6) {
            motionY = motionY + 0.2;
            spawnWallParticle(pl, getWallPos(pl));
        } else if (ticksWallClinged++ > Config.COMMON.wallSlideDelay.get()) {
            motionY = -0.1;
            spawnWallParticle(pl, getWallPos(pl));
        } else {
            motionY = 0.0;
        }

        if (pl.fallDistance > 2) {
            pl.fallDistance = 0;
            PacketHandler.INSTANCE.sendToServer(new MessageFallDistance((float) (motionY * motionY * 8)));
        }

        pl.setDeltaMovement(0.0, motionY, 0.0);
    }

    private static boolean canWallJump(LocalPlayer pl) {
        return Config.COMMON.useWallJump.get();
    }

    private static boolean canWallCling(LocalPlayer pl) {
        if (pl.onClimbable() || pl.getDeltaMovement().y > 0.1 || pl.getFoodData().getFoodLevel() < 1)
            return false;

        return true;
    }

    private static Set<Direction> walls = new HashSet<>();
    private static Set<Direction> staleWalls = new HashSet<>();

    private static void updateWalls(LocalPlayer pl) {

        Vec3 pos = pl.position();
        AABB playerBox = pl.getBoundingBoxForCulling();
        double playerWidth = playerBox.getXsize();
        double playerHeight = playerBox.getYsize();

        AABB box = new AABB(pos.x - playerWidth / 2, pos.y - (playerHeight * 0.5), pos.z - playerWidth / 2,
                pos.x + playerWidth / 2, pos.y + playerHeight, pos.z + playerWidth / 2);

        double dist = (playerWidth / 2) + (ticksWallClinged > 0 ? 0.1 : 0.06);
        AABB[] axes = {
                box.expandTowards(0, 0, dist),
                box.expandTowards(-dist, 0, 0),
                box.expandTowards(0, 0, -dist),
                box.expandTowards(dist, 0, 0)
        };

        int i = 0;
        Direction direction;
        WallJumpLogic.walls = new HashSet<>();
        for (AABB axis : axes) {
            direction = Direction.from2DDataValue(i++);
            if (ClientProxy.collidesWithBlock(pl.getCommandSenderWorld(), axis)) {
                walls.add(direction);
                pl.horizontalCollision = true;
            }
        }
    }

    private static Direction getClingDirection() {
        return walls.isEmpty() ? Direction.UP : walls.iterator().next();
    }

    private static BlockPos getWallPos(LocalPlayer player) {

        BlockPos pos = player.getOnPos().relative(getClingDirection(), 1);
        BlockState blockState = player.getCommandSenderWorld().getBlockState(pos);
        return blockState.isSolidRender(player.getCommandSenderWorld(), pos) ? pos : pos.relative(Direction.UP, 1);
    }

    private static void wallJump(LocalPlayer pl, float up) {

        float strafe = Math.signum(pl.input.leftImpulse) * up * up;
        float forward = Math.signum(pl.input.forwardImpulse) * up * up;

        float f = (float) (1.0F / Math.sqrt(strafe * strafe + up * up + forward * forward));
        strafe = strafe * f;
        forward = forward * f;

        float f1 = (float) (Math.sin(pl.getYRot() * 0.017453292F) * 0.45f);
        float f2 = (float) (Math.cos(pl.getYRot() * 0.017453292F) * 0.45f);

        int jumpBoostLevel = 0;
        MobEffectInstance jumpBoostEffect = pl.getEffect(MobEffect.byId(8));
        if (jumpBoostEffect != null) jumpBoostLevel = jumpBoostEffect.getAmplifier() + 1;

        Vec3 motion = pl.getDeltaMovement();
        pl.setDeltaMovement(motion.x + (strafe * f2 - forward * f1), up + (jumpBoostLevel * .125), motion.z + (forward * f2 + strafe * f1));

        lastJumpY = pl.position().y;
        playBreakSound(pl, getWallPos(pl));
        spawnWallParticle(pl, getWallPos(pl));

    }

    private static void playHitSound(Entity entity, BlockPos pos) {

        BlockState state = entity.getCommandSenderWorld().getBlockState(pos);
        SoundType soundtype = state.getBlock().getSoundType(state, entity.getCommandSenderWorld(), pos, entity);
        entity.playSound(soundtype.getHitSound(), soundtype.getVolume() * 0.25F, soundtype.getPitch());

    }

    private static void playBreakSound(Entity entity, BlockPos pos) {

        BlockState state = entity.getCommandSenderWorld().getBlockState(pos);
        SoundType soundtype = state.getBlock().getSoundType(state, entity.getCommandSenderWorld(), pos, entity);
        entity.playSound(soundtype.getFallSound(), soundtype.getVolume() * 0.5F, soundtype.getPitch());

    }

    private static void spawnWallParticle(Entity entity, BlockPos blockPos) {

        BlockState state = entity.getCommandSenderWorld().getBlockState(blockPos);
        if (state.getRenderShape() != RenderShape.INVISIBLE) {

            Vec3 pos = entity.position();
            Vec3i motion = getClingDirection().getNormal();

            entity.getCommandSenderWorld().addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state).setPos(blockPos), pos.x, pos.y,
                    pos.z, motion.getX() * -1.0D, -1.0D, motion.getZ() * -1.0D);

        }

    }

}

package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventManager;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.*;
import myau.management.RotationState;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.Module;
import myau.property.properties.*;
import myau.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.DataWatcher.WatchableObject;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.EntitySilverfish;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityBat;
import net.minecraft.entity.passive.EntitySquid;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S1CPacketEntityMetadata;
import net.minecraft.util.*;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.world.WorldSettings.GameType;

import java.awt.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public class KillAura extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final DecimalFormat df = new DecimalFormat("+0.0;-0.0", new DecimalFormatSymbols(Locale.US));
    private final TimerUtil timer = new TimerUtil();
    private AttackData target = null;
    private int switchTick = 0;
    private boolean hitRegistered = false;
    private boolean blockingState = false;
    private boolean isBlocking = false;
    private boolean fakeBlockState = false;
    private boolean blinkReset = false;
    private long attackDelayMS = 0L;
    private int blockTick = 0;
    private int lastTickProcessed;
    /** Server-side blocking state shared with other modules (Sprint/Speed/Fly), mirrors Rise KillAura.blocking. */
    public static boolean blocking = false;
    /** Watchdog AutoBlock block-cycle stage (blockStage in Rise). */
    private int watchdogStage = 0;
    /** Watchdog AutoBlock: whether the last unblock was faked via slot flick (fakeBlocked in Rise). */
    private boolean fakeBlocked = false;
    /** Watchdog AutoBlock: short 2-stage block cycle instead of the 3-stage one (shortBlockCycle in Rise). */
    private boolean shortBlockCycle = false;
    /** Watchdog AutoBlock: last tick a slot flick was performed (lastSlotFlickTick in Rise). */
    private int lastSlotFlickTick = -1;
    /** Watchdog AutoBlock: whether a block/use-item packet was sent since the last movement packet (blockPacketSent in Rise). */
    private boolean blockPacketSent = false;
    /** Watchdog AutoBlock: whether an attack is allowed this tick (canAttack in Rise). */
    private boolean watchdogCanAttack = true;
    public final ModeProperty mode;
    public final ModeProperty sort;
    public final ModeProperty autoBlock;
    public final BooleanProperty autoBlockRequirePress;
    public final BooleanProperty rightClickOnly;
    public final BooleanProperty preventServersideBlocking;
    public final BooleanProperty blockSlowdown;
    public final BooleanProperty watchdogNew;
    public final FloatProperty autoBlockMinCPS;
    public final FloatProperty autoBlockMaxCPS;
    public final FloatProperty autoBlockRange;
    public final FloatProperty swingRange;
    public final FloatProperty attackRange;
    public final IntProperty fov;
    public final IntProperty minCPS;
    public final IntProperty maxCPS;
    public final IntProperty switchDelay;
    public final ModeProperty rotations;
    public final ModeProperty moveFix;
    public final PercentProperty smoothing;
    public final IntProperty angleStep;
    public final BooleanProperty throughWalls;
    public final BooleanProperty requirePress;
    public final BooleanProperty allowMining;
    public final BooleanProperty weaponsOnly;
    public final BooleanProperty allowTools;
    public final BooleanProperty inventoryCheck;
    public final BooleanProperty botCheck;
    public final BooleanProperty players;
    public final BooleanProperty bosses;
    public final BooleanProperty mobs;
    public final BooleanProperty animals;
    public final BooleanProperty golems;
    public final BooleanProperty silverfish;
    public final BooleanProperty teams;
    public final ModeProperty showTarget;
    public final ModeProperty debugLog;

    private long getAttackDelay() {
        if (this.autoBlock.getValue() == 9) {
            // Watchdog AutoBlock paces attacks through the block-stage machine, not CPS.
            return 0L;
        }
        return this.isBlocking ? (long) (1000.0F / RandomUtil.nextLong(this.autoBlockMinCPS.getValue().longValue(), this.autoBlockMaxCPS.getValue().longValue())) : 1000L / RandomUtil.nextLong(this.minCPS.getValue(), this.maxCPS.getValue());
    }

    private boolean performAttack(float yaw, float pitch) {
        if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
            if (this.isPlayerBlocking() && this.autoBlock.getValue() != 1 && this.autoBlock.getValue() != 9) {
                // VANILLA attacks while blocking; WATCHDOG's cycle manages the block state itself
                return false;
            } else if (this.attackDelayMS > 0L) {
                return false;
            } else {
                this.attackDelayMS = this.attackDelayMS + this.getAttackDelay();
                mc.thePlayer.swingItem();
                if ((this.rotations.getValue() != 0 || !this.isBoxInAttackRange(this.target.getBox()))
                        && RotationUtil.rayTrace(this.target.getBox(), yaw, pitch, this.attackRange.getValue()) == null) {
                    return false;
                } else {
                    AttackEvent event = new AttackEvent(this.target.getEntity());
                    EventManager.call(event);
                    ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
                    PacketUtil.sendPacket(new C02PacketUseEntity(this.target.getEntity(), Action.ATTACK));
                    // Watchdog AutoBlock: after the attack, interact with the target so the
                    // server registers the use (Rise attack() usesWatchdogBlock branch).
                    // Rise's getHitVec falls back to (0, eyeHeight*0.5, 0) when the ray trace
                    // misses, so the interact packets are always sent — matching that keeps
                    // the block/attack packet ordering intact for Watchdog.
                    if (this.autoBlock.getValue() == 9 && this.eX()) {
                        MovingObjectPosition mop = RotationUtil.rayTrace(this.target.getBox(), yaw, pitch, 8.0);
                        double hitX;
                        double hitY;
                        double hitZ;
                        if (mop != null && mop.hitVec != null) {
                            hitX = mop.hitVec.xCoord - this.target.getX();
                            hitY = mop.hitVec.yCoord - this.target.getY();
                            hitZ = mop.hitVec.zCoord - this.target.getZ();
                        } else {
                            hitX = 0.0;
                            hitY = this.target.getEntity().getEyeHeight() * 0.5;
                            hitZ = 0.0;
                        }
                        PacketUtil.sendPacket(
                                new C02PacketUseEntity(this.target.getEntity(), new Vec3(hitX, hitY, hitZ))
                        );
                        PacketUtil.sendPacket(new C02PacketUseEntity(this.target.getEntity(), Action.INTERACT));
                    }
                    if (mc.playerController.getCurrentGameType() != GameType.SPECTATOR) {
                        PlayerUtil.attackEntity(this.target.getEntity());
                    }
                    this.hitRegistered = true;
                    return true;
                }
            }
        } else {
            return false;
        }
    }

    private void sendUseItem() {
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        this.startBlock(mc.thePlayer.getHeldItem());
    }

    private void startBlock(ItemStack itemStack) {
        PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(itemStack));
        mc.thePlayer.setItemInUse(itemStack, itemStack.getMaxItemUseDuration());
        this.blockingState = true;
    }

    private void stopBlock() {
        PacketUtil.sendPacket(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
        mc.thePlayer.stopUsingItem();
        this.blockingState = false;
    }

    private void interactAttack(float yaw, float pitch) {
        if (this.target != null) {
            MovingObjectPosition mop = RotationUtil.rayTrace(this.target.getBox(), yaw, pitch, 8.0);
            if (mop != null) {
                ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
                PacketUtil.sendPacket(
                        new C02PacketUseEntity(
                                this.target.getEntity(),
                                new Vec3(mop.hitVec.xCoord - this.target.getX(), mop.hitVec.yCoord - this.target.getY(), mop.hitVec.zCoord - this.target.getZ())
                        )
                );
                PacketUtil.sendPacket(new C02PacketUseEntity(this.target.getEntity(), Action.INTERACT));
                PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
                mc.thePlayer.setItemInUse(mc.thePlayer.getHeldItem(), mc.thePlayer.getHeldItem().getMaxItemUseDuration());
                this.blockingState = true;
            }
        }
    }

    private boolean canAttack() {
        if (this.inventoryCheck.getValue() && mc.currentScreen instanceof GuiContainer) {
            return false;
        } else if (!(Boolean) this.weaponsOnly.getValue()
                || ItemUtil.hasRawUnbreakingEnchant()
                || this.allowTools.getValue() && ItemUtil.isHoldingTool()) {
            if (((IAccessorPlayerControllerMP) mc.playerController).getIsHittingBlock()) {
                return false;
            } else if ((ItemUtil.isEating() || ItemUtil.isUsingBow()) && PlayerUtil.isUsingItem()) {
                return false;
            } else {
                AutoHeal autoHeal = (AutoHeal) Myau.moduleManager.modules.get(AutoHeal.class);
                if (autoHeal.isEnabled() && autoHeal.isSwitching()) {
                    return false;
                } else {
                    BedNuker bedNuker = (BedNuker) Myau.moduleManager.modules.get(BedNuker.class);
                    AutoBlockIn autoBlockIn = (AutoBlockIn) Myau.moduleManager.modules.get(AutoBlockIn.class);
                    if (bedNuker.isEnabled() && bedNuker.isReady()) {
                        return false;
                    } else if (Myau.moduleManager.modules.get(Scaffold.class).isEnabled()) {
                        return false;
                    } else if (autoBlockIn.isEnabled()) {
                        return false;
                    } else if (this.requirePress.getValue()) {
                        return PlayerUtil.isAttacking();
                    } else {
                        return !this.allowMining.getValue() || !mc.objectMouseOver.typeOfHit.equals(MovingObjectType.BLOCK) || !PlayerUtil.isAttacking();
                    }
                }
            }
        } else {
            return false;
        }
    }

    private boolean canAutoBlock() {
        if (!ItemUtil.isHoldingSword()) {
            return false;
        } else if (this.autoBlock.getValue() == 9) {
            // Watchdog AutoBlock: right-click gate instead of the generic require-press option
            return !this.rightClickOnly.getValue() || PlayerUtil.isUsingItem();
        } else {
            return !this.autoBlockRequirePress.getValue() || PlayerUtil.isUsingItem();
        }
    }

    /** Rise KillAura.eX(): the right-click gate + sword requirement used by the Watchdog block cycle. */
    private boolean eX() {
        if (this.rightClickOnly.getValue() && !PlayerUtil.isUsingItem()) {
            return false;
        }
        return ItemUtil.isHoldingSword();
    }

    /** Sends a silent slot switch (Rise SlotComponent.setSlot). */
    private void setSlot(int slot) {
        PacketUtil.sendPacket(new C09PacketHeldItemChange(slot));
    }

    /**
     * Silent slot flick without the blink dispatch / unblock side effects of
     * {@link #flickSlot()}. Mirrors Rise's {@code SlotComponent.setSlot(random)}
     * followed by {@code SlotComponent.setSlot(current)} as used directly inside
     * the Watchdog {@code updateAutoBlock} stage 2/3 (not the standalone
     * {@code flickSlot()} which is only used on release/disable).
     */
    private void flickSlotSilent() {
        if (mc.thePlayer == null) {
            return;
        }
        int current = mc.thePlayer.inventory.currentItem;
        int random = ThreadLocalRandom.current().nextInt(9);
        while (random == current) {
            random = ThreadLocalRandom.current().nextInt(9);
        }
        PacketUtil.sendPacket(new C09PacketHeldItemChange(random));
        PacketUtil.sendPacket(new C09PacketHeldItemChange(current));
    }

    /** Sends the use-item packet that makes the server think we are blocking (Rise KillAura.block()). */
    private void block() {
        if (!KillAura.blocking) {
            ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
            PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
            KillAura.blocking = true;
            this.blockingState = true;
        }
    }

    /**
     * Sends the release-use-item packet (Rise KillAura.unblock()).
     * When force is true the release is only sent if no block/swing packet has been sent
     * since the last movement packet, so the slot flick handles the unblock instead.
     */
    private void unblock(boolean force) {
        if (KillAura.blocking && (!force || !this.blockPacketSent)) {
            PacketUtil.sendPacket(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
            KillAura.blocking = false;
            this.blockingState = false;
        }
    }

    /** Starts holding outgoing packets (Rise BlinkComponent.blink()). */
    private void startBlink() {
        Myau.blinkManager.setBlinkState(true, BlinkModules.AUTO_BLOCK);
    }

    /**
     * Dispatches held packets and stops blinking (Rise BlinkComponent.bf()/dispatch()).
     * The next {@link #startBlink()} reopens the channel.
     */
    private void flushBlink() {
        Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
    }

    /**
     * Mirrors Rise {@code BlinkComponent.bf()}: flush the packets held by the
     * current blink session and immediately reopen the channel so the next
     * round of packets is held again. Used by the Watchdog AutoBlock cycle
     * between stages (Rise flushes the old ticket and opens a new one in the
     * same call, keeping movement packets hidden across the whole block cycle).
     */
    private void bfBlink() {
        Myau.blinkManager.flushAndRestartBlink(BlinkModules.AUTO_BLOCK);
    }

    /**
     * Fakes a block release by switching the held slot away and back (Rise KillAura.flickSlot()).
     * Only sends the C07 release when no block packet was sent since the last movement packet.
     */
    private boolean flickSlot() {
        if (mc.thePlayer == null) {
            return false;
        }
        if (this.lastSlotFlickTick == mc.thePlayer.ticksExisted) {
            return false;
        }
        int current = mc.thePlayer.inventory.currentItem;
        int random = ThreadLocalRandom.current().nextInt(9);
        while (random == current) {
            random = ThreadLocalRandom.current().nextInt(9);
        }
        this.flushBlink();
        if (ServerUtil.isHypixel()) {
            if (!this.blockSlowdown.getValue()) {
                PacketUtil.sendPacket(new C09PacketHeldItemChange(random));
                PacketUtil.sendPacket(new C09PacketHeldItemChange(current));
            }
            this.unblock(true);
        } else {
            this.unblock(true);
            if (!this.blockSlowdown.getValue()) {
                PacketUtil.sendPacket(new C09PacketHeldItemChange(random));
                PacketUtil.sendPacket(new C09PacketHeldItemChange(current));
            }
        }
        this.lastSlotFlickTick = mc.thePlayer.ticksExisted;
        return true;
    }

    /**
     * Watchdog AutoBlock: whether an attack is allowed this tick (Rise KillAura canAttack +
     * adjustAttackDelay "Watchdog" case). Attacks are only allowed on the stage of the cycle
     * that precedes the block, so the block packet follows the attack packet naturally.
     */
    private boolean watchdogAttackAllowed() {
        if (!ItemUtil.isHoldingSword()) {
            return this.target != null;
        }
        if (this.shortBlockCycle) {
            if (this.watchdogStage == 2) {
                return false;
            }
        } else if (this.watchdogStage == 3) {
            return false;
        }
        boolean rightHeld = PlayerUtil.isUsingItem();
        return (this.watchdogStage == 1 && Math.random() > 0.2)
                || this.watchdogStage == 2
                || (this.rightClickOnly.getValue() && !rightHeld);
    }

    /**
     * Watchdog AutoBlock block-cycle state machine (Rise KillAura.updateAutoBlock() "Watchdog" case).
     * Runs every tick while holding a sword with a target and the right-click gate satisfied.
     */
    private void updateWatchdogBlock() {
        if (((IAccessorPlayerControllerMP) mc.playerController).getCurBlockDamageMP() != 0.0F
                && mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK) {
            this.watchdogStage = 0;
        }
        this.watchdogStage++;
        if (this.watchdogStage >= (this.shortBlockCycle ? 3 : 4)) {
            this.watchdogStage = 1;
        }
        switch (this.watchdogStage) {
            case 1:
                this.shortBlockCycle = !this.watchdogNew.getValue();
                if (ServerUtil.isHypixel() && Math.random() > 0.5 && !this.blockSlowdown.getValue() && !this.shortBlockCycle) {
                    int current = mc.thePlayer.inventory.currentItem;
                    int random = ThreadLocalRandom.current().nextInt(9);
                    while (random == current) {
                        random = ThreadLocalRandom.current().nextInt(9);
                    }
                    PacketUtil.sendPacket(new C09PacketHeldItemChange(random));
                    PacketUtil.sendPacket(new C09PacketHeldItemChange(current));
                    this.unblock(false);
                }
                this.startBlink();
                break;
            case 2:
                if (!ServerUtil.isHypixel()) {
                    this.unblock(false);
                }
                if (this.shortBlockCycle) {
                    this.bfBlink();
                    if (!this.blockSlowdown.getValue()) {
                        this.flickSlotSilent();
                    }
                    this.unblock(true);
                    this.fakeBlocked = true;
                }
                break;
            case 3:
                if (!this.shortBlockCycle) {
                    this.bfBlink();
                    if (ServerUtil.isHypixel()) {
                        if (!this.blockSlowdown.getValue()) {
                            this.flickSlotSilent();
                        }
                        this.unblock(true);
                    } else {
                        this.unblock(true);
                        if (!this.blockSlowdown.getValue()) {
                            this.flickSlotSilent();
                        }
                    }
                    this.fakeBlocked = true;
                }
                break;
            default:
                break;
        }
        this.watchdogCanAttack = this.watchdogAttackAllowed();
    }

    /**
     * Sends the block use-item packet right after an attack at the correct stage of the
     * Watchdog block cycle (Rise KillAura.postAttackBlock() "Watchdog" case).
     */
    private void watchdogPostAttackBlock() {
        if (!this.shortBlockCycle) {
            if (this.watchdogStage == 2) {
                this.watchdogCanAttack = false;
                this.block();
                this.startBlink();
                this.fakeBlocked = false;
            }
        } else if (this.watchdogStage == 1) {
            this.watchdogCanAttack = false;
            this.block();
            this.startBlink();
            this.fakeBlocked = false;
        }
    }

    /** Releases the Watchdog block state when the target is lost or the gate fails. */
    private void watchdogRelease() {
        if (KillAura.blocking && !this.fakeBlocked) {
            if (this.flickSlot()) {
                this.fakeBlocked = true;
            }
        } else if (KillAura.blocking) {
            this.unblock(false);
        }
    }

    private boolean hasValidTarget() {
        return mc.theWorld
                .loadedEntityList
                .stream()
                .anyMatch(
                        entity -> entity instanceof EntityLivingBase
                                && this.isValidTarget((EntityLivingBase) entity)
                                && this.isInBlockRange((EntityLivingBase) entity)
                );
    }

    private boolean isValidTarget(EntityLivingBase entityLivingBase) {
        if (!mc.theWorld.loadedEntityList.contains(entityLivingBase)) {
            return false;
        } else if (entityLivingBase != mc.thePlayer && entityLivingBase != mc.thePlayer.ridingEntity) {
            if (entityLivingBase == mc.getRenderViewEntity() || entityLivingBase == mc.getRenderViewEntity().ridingEntity) {
                return false;
            } else if (entityLivingBase.deathTime > 0) {
                return false;
            } else if (RotationUtil.angleToEntity(entityLivingBase) > this.fov.getValue().floatValue()) {
                return false;
            } else if (!this.throughWalls.getValue() && RotationUtil.rayTrace(entityLivingBase) != null) {
                return false;
            } else if (entityLivingBase instanceof EntityOtherPlayerMP) {
                if (!this.players.getValue()) {
                    return false;
                } else if (TeamUtil.isFriend((EntityPlayer) entityLivingBase)) {
                    return false;
                } else {
                    return (!this.teams.getValue() || !TeamUtil.isSameTeam((EntityPlayer) entityLivingBase)) && (!this.botCheck.getValue() || !TeamUtil.isBot((EntityPlayer) entityLivingBase));
                }
            } else if (entityLivingBase instanceof EntityDragon || entityLivingBase instanceof EntityWither) {
                return this.bosses.getValue();
            } else if (!(entityLivingBase instanceof EntityMob) && !(entityLivingBase instanceof EntitySlime)) {
                if (entityLivingBase instanceof EntityAnimal
                        || entityLivingBase instanceof EntityBat
                        || entityLivingBase instanceof EntitySquid
                        || entityLivingBase instanceof EntityVillager) {
                    return this.animals.getValue();
                } else if (!(entityLivingBase instanceof EntityIronGolem)) {
                    return false;
                } else {
                    return this.golems.getValue() && (!this.teams.getValue() || !TeamUtil.hasTeamColor(entityLivingBase));
                }
            } else if (!(entityLivingBase instanceof EntitySilverfish)) {
                return this.mobs.getValue();
            } else {
                return this.silverfish.getValue() && (!this.teams.getValue() || !TeamUtil.hasTeamColor(entityLivingBase));
            }
        } else {
            return false;
        }
    }

    private boolean isInRange(EntityLivingBase entityLivingBase) {
        return this.isInBlockRange(entityLivingBase) || this.isInSwingRange(entityLivingBase) || this.isInAttackRange(entityLivingBase);
    }

    private boolean isInBlockRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.autoBlockRange.getValue();
    }

    private boolean isInSwingRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.swingRange.getValue();
    }

    private boolean isBoxInSwingRange(AxisAlignedBB axisAlignedBB) {
        return RotationUtil.distanceToBox(axisAlignedBB) <= (double) this.swingRange.getValue();
    }

    private boolean isInAttackRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.attackRange.getValue();
    }

    private boolean isBoxInAttackRange(AxisAlignedBB axisAlignedBB) {
        return RotationUtil.distanceToBox(axisAlignedBB) <= (double) this.attackRange.getValue();
    }

    private boolean isPlayerTarget(EntityLivingBase entityLivingBase) {
        return entityLivingBase instanceof EntityPlayer && TeamUtil.isTarget((EntityPlayer) entityLivingBase);
    }

    private int findEmptySlot(int currentSlot) {
        for (int i = 0; i < 9; i++) {
            if (i != currentSlot && mc.thePlayer.inventory.getStackInSlot(i) == null) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (i != currentSlot) {
                ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
                if (stack != null && !stack.hasDisplayName()) {
                    return i;
                }
            }
        }
        return Math.floorMod(currentSlot - 1, 9);
    }

    private int findSwordSlot(int currentSlot) {
        for (int i = 0; i < 9; i++) {
            if (i != currentSlot) {
                ItemStack item = mc.thePlayer.inventory.getStackInSlot(i);
                if (item != null && item.getItem() instanceof ItemSword) {
                    return i;
                }
            }
        }
        return -1;
    }

    public KillAura() {
        super("KillAura", false);
        this.lastTickProcessed = 0;
        this.mode = new ModeProperty("mode", 0, new String[]{"SINGLE", "SWITCH"});
        this.sort = new ModeProperty("sort", 0, new String[]{"DISTANCE", "HEALTH", "HURT_TIME", "FOV"});
        this.autoBlock = new ModeProperty(
                "auto-block", 2, new String[]{"NONE", "VANILLA", "SPOOF", "HYPIXEL", "BLINK", "INTERACT", "SWAP", "LEGIT", "FAKE", "WATCHDOG"}
        );
        this.autoBlockRequirePress = new BooleanProperty("auto-block-require-press", false);
        this.rightClickOnly = new BooleanProperty("right-click-only", false, () -> this.autoBlock.getValue() != 0 && this.autoBlock.getValue() != 8);
        this.preventServersideBlocking = new BooleanProperty("prevent-serverside-blocking", false, () -> this.autoBlock.getValue() == 0 || this.autoBlock.getValue() == 8);
        this.blockSlowdown = new BooleanProperty("block-slowdown", false, () -> this.autoBlock.getValue() != 0 && this.autoBlock.getValue() != 8);
        this.watchdogNew = new BooleanProperty("watchdog-new", false, () -> this.autoBlock.getValue() == 9);
        this.autoBlockMinCPS = new FloatProperty("auto-block-min-aps", 8.0F, 1.0F, 20.0F);
        this.autoBlockMaxCPS = new FloatProperty("auto-block-max-aps", 10.0F, 1.0F, 20.0F);
        this.autoBlockRange = new FloatProperty("auto-block-range", 6.0F, 3.0F, 8.0F);
        this.swingRange = new FloatProperty("swing-range", 3.5F, 3.0F, 6.0F);
        this.attackRange = new FloatProperty("attack-range", 3.0F, 3.0F, 6.0F);
        this.fov = new IntProperty("fov", 360, 30, 360);
        this.minCPS = new IntProperty("min-aps", 14, 1, 20);
        this.maxCPS = new IntProperty("max-aps", 14, 1, 20);
        this.switchDelay = new IntProperty("switch-delay", 150, 0, 1000);
        this.rotations = new ModeProperty("rotations", 2, new String[]{"NONE", "LEGIT", "SILENT", "LOCK_VIEW"});
        this.moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "STRICT"});
        this.smoothing = new PercentProperty("smoothing", 0);
        this.angleStep = new IntProperty("angle-step", 90, 30, 180);
        this.throughWalls = new BooleanProperty("through-walls", true);
        this.requirePress = new BooleanProperty("require-press", false);
        this.allowMining = new BooleanProperty("allow-mining", true);
        this.weaponsOnly = new BooleanProperty("weapons-only", true);
        this.allowTools = new BooleanProperty("allow-tools", false, this.weaponsOnly::getValue);
        this.inventoryCheck = new BooleanProperty("inventory-check", true);
        this.botCheck = new BooleanProperty("bot-check", true);
        this.players = new BooleanProperty("players", true);
        this.bosses = new BooleanProperty("bosses", false);
        this.mobs = new BooleanProperty("mobs", false);
        this.animals = new BooleanProperty("animals", false);
        this.golems = new BooleanProperty("golems", false);
        this.silverfish = new BooleanProperty("silverfish", false);
        this.teams = new BooleanProperty("teams", true);
        this.showTarget = new ModeProperty("show-target", 0, new String[]{"NONE", "DEFAULT", "HUD"});
        this.debugLog = new ModeProperty("debug-log", 0, new String[]{"NONE", "HEALTH"});
    }

    public EntityLivingBase getTarget() {
        return this.target != null ? this.target.getEntity() : null;
    }

    public boolean isAttackAllowed() {
        Scaffold scaffold = (Scaffold) Myau.moduleManager.modules.get(Scaffold.class);
        if (scaffold.isEnabled()) {
            return false;
        } else if (!this.weaponsOnly.getValue()
                || ItemUtil.hasRawUnbreakingEnchant()
                || this.allowTools.getValue() && ItemUtil.isHoldingTool()) {
            return !this.requirePress.getValue() || KeyBindUtil.isKeyDown(mc.gameSettings.keyBindAttack.getKeyCode());
        } else {
            return false;
        }
    }

    public boolean shouldAutoBlock() {
        if (this.isPlayerBlocking() && this.isBlocking) {
            return !mc.thePlayer.isInWater() && !mc.thePlayer.isInLava() && (this.autoBlock.getValue() == 3  // HYPIXEL
                    || this.autoBlock.getValue() == 4 // BLINK
                    || this.autoBlock.getValue() == 5 // INTERACT
                    || this.autoBlock.getValue() == 6 // SWAP
                    || this.autoBlock.getValue() == 7 // LEGIT
                    || this.autoBlock.getValue() == 9); // WATCHDOG
        } else {
            return false;
        }
    }

    public boolean isBlocking() {
        return this.fakeBlockState && ItemUtil.isHoldingSword();
    }

    public boolean isPlayerBlocking() {
        return (mc.thePlayer.isUsingItem() || this.blockingState) && ItemUtil.isHoldingSword();
    }

    @EventTarget(Priority.LOW)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() == EventType.POST && this.blinkReset) {
            this.blinkReset = false;
            Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
            Myau.blinkManager.setBlinkState(true, BlinkModules.AUTO_BLOCK);
        }
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            if (this.attackDelayMS > 0L) {
                this.attackDelayMS -= 50L;
            }
            boolean watchdog = this.autoBlock.getValue() == 9;
            boolean attack = this.target != null && this.canAttack();
            boolean block = attack && this.canAutoBlock();
            if (!block && !watchdog) {
                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                this.isBlocking = false;
                this.fakeBlockState = false;
                this.blockTick = 0;
            }
            if (watchdog) {
                if (this.target != null && this.eX()) {
                    this.updateWatchdogBlock();
                    attack = attack && this.watchdogCanAttack;
                    this.isBlocking = true;
                    this.fakeBlockState = true;
                } else if (this.target == null) {
                    // target lost (Rise onPreMotionEvent): fake the release with a slot flick,
                    // otherwise release normally
                    this.watchdogRelease();
                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                    this.isBlocking = false;
                    this.fakeBlockState = false;
                    this.watchdogStage = 0;
                    this.watchdogCanAttack = true;
                }
                // target still exists but the gate failed (right click released with
                // rightClickOnly): Rise updateBlockForMode() does nothing for Watchdog,
                // so the current block state is kept and attacks continue.
            }
            if (attack) {
                boolean swap = false;
                boolean blocked = false;
                if (block && !watchdog) {
                    switch (this.autoBlock.getValue()) {
                        case 0: // NONE
                            if (PlayerUtil.isUsingItem()) {
                                this.isBlocking = true;
                                if (!this.isPlayerBlocking() && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    swap = true;
                                }
                            } else {
                                this.isBlocking = false;
                                if (this.isPlayerBlocking() && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    this.stopBlock();
                                }
                            }
                            Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                            this.fakeBlockState = false;
                            break;
                        case 1: // VANILLA
                            if (this.hasValidTarget()) {
                                if (!this.isPlayerBlocking() && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    swap = true;
                                }
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = true;
                                this.fakeBlockState = false;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 2: // SPOOF
                            if (this.hasValidTarget()) {
                                int item = ((IAccessorPlayerControllerMP) mc.playerController).getCurrentPlayerItem();
                                if (Myau.playerStateManager.digging
                                        || Myau.playerStateManager.placing
                                        || mc.thePlayer.inventory.currentItem != item
                                        || this.isPlayerBlocking() && this.blockTick != 0
                                        || this.attackDelayMS > 0L && this.attackDelayMS <= 50L) {
                                    this.blockTick = 0;
                                } else {
                                    int slot = this.findEmptySlot(item);
                                    PacketUtil.sendPacket(new C09PacketHeldItemChange(slot));
                                    PacketUtil.sendPacket(new C09PacketHeldItemChange(item));
                                    swap = true;
                                    this.blockTick = 1;
                                }
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = true;
                                this.fakeBlockState = false;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 3: // HYPIXEL
                            if (this.hasValidTarget()) {
                                if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            }
                                            blocked = true;
                                            this.blockTick = 1;
                                            break;
                                        case 1:
                                            if (this.isPlayerBlocking()) {
                                                if(Myau.moduleManager.modules.get(NoSlow.class).isEnabled()){
                                                    int randomSlot = new Random().nextInt(9);
                                                    while (randomSlot == mc.thePlayer.inventory.currentItem) {
                                                        randomSlot = new Random().nextInt(9);
                                                    }
                                                    PacketUtil.sendPacket(new C09PacketHeldItemChange(randomSlot));
                                                    PacketUtil.sendPacket(new C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem));
                                                }
                                                this.stopBlock();
                                                attack = false;
                                            }
                                            if (this.attackDelayMS <= 50L) {
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                }
                                this.isBlocking = true;
                                this.fakeBlockState = true;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 4: // BLINK
                            if (this.hasValidTarget()) {
                                if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            }
                                            this.blinkReset = true;
                                            this.blockTick = 1;
                                            break;
                                        case 1:
                                            if (this.isPlayerBlocking()) {
                                                this.stopBlock();
                                                attack = false;
                                            }
                                            if (this.attackDelayMS <= 50L) {
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                }
                                this.isBlocking = true;
                                this.fakeBlockState = true;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 5: // INTERACT
                            if (this.hasValidTarget()) {
                                int item = ((IAccessorPlayerControllerMP) mc.playerController).getCurrentPlayerItem();
                                if (mc.thePlayer.inventory.currentItem == item && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            }
                                            this.blinkReset = true;
                                            this.blockTick = 1;
                                            break;
                                        case 1:
                                            if (this.isPlayerBlocking()) {
                                                int slot = this.findEmptySlot(item);
                                                PacketUtil.sendPacket(new C09PacketHeldItemChange(slot));
                                                ((IAccessorPlayerControllerMP) mc.playerController).setCurrentPlayerItem(slot);
                                                attack = false;
                                            }
                                            if (this.attackDelayMS <= 50L) {
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                }
                                this.isBlocking = true;
                                this.fakeBlockState = true;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 6: // SWAP
                            if (this.hasValidTarget()) {
                                int item = ((IAccessorPlayerControllerMP) mc.playerController).getCurrentPlayerItem();
                                if (mc.thePlayer.inventory.currentItem == item && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            int slot = this.findSwordSlot(item);
                                            if (slot != -1) {
                                                if (!this.isPlayerBlocking()) {
                                                    swap = true;
                                                }
                                                this.blockTick = 1;
                                            }
                                            break;
                                        case 1:
                                            int swordsSlot = this.findSwordSlot(item);
                                            if (swordsSlot == -1) {
                                                this.blockTick = 0;
                                            } else if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            } else if (this.attackDelayMS <= 50L) {
                                                PacketUtil.sendPacket(new C09PacketHeldItemChange(swordsSlot));
                                                ((IAccessorPlayerControllerMP) mc.playerController).setCurrentPlayerItem(swordsSlot);
                                                this.startBlock(mc.thePlayer.inventory.getStackInSlot(swordsSlot));
                                                attack = false;
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = true;
                                    this.fakeBlockState = true;
                                    break;
                                }
                            }
                            Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                            this.isBlocking = false;
                            this.fakeBlockState = false;
                            break;
                        case 7: // LEGIT
                            if (this.hasValidTarget()) {
                                if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            }
                                            this.blockTick = 1;
                                            break;
                                        case 1:
                                            if (this.isPlayerBlocking()) {
                                                this.stopBlock();
                                                attack = false;
                                            }
                                            if (this.attackDelayMS <= 50L) {
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                }
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = true;
                                this.fakeBlockState = false;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 8: // FAKE
                            Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                            this.isBlocking = false;
                            this.fakeBlockState = this.hasValidTarget();
                            if (PlayerUtil.isUsingItem()
                                    && !this.isPlayerBlocking()
                                    && !Myau.playerStateManager.digging
                                    && !Myau.playerStateManager.placing) {
                                swap = true;
                            }
                    }
                }
                boolean attacked = false;
                if (this.isBoxInSwingRange(this.target.getBox())) {
                    if (this.rotations.getValue() == 2 || this.rotations.getValue() == 3) {
                        float[] rotations = RotationUtil.getRotationsToBox(
                                this.target.getBox(),
                                event.getYaw(),
                                event.getPitch(),
                                (float) this.angleStep.getValue() + RandomUtil.nextFloat(-5.0F, 5.0F),
                                (float) this.smoothing.getValue() / 100.0F
                        );
                        event.setRotation(rotations[0], rotations[1], 1);
                        if (this.rotations.getValue() == 3) {
                            Myau.rotationManager.setRotation(rotations[0], rotations[1], 1, true);
                        }
                        if (this.moveFix.getValue() != 0 || this.rotations.getValue() == 3) {
                            event.setPervRotation(rotations[0], 1);
                        }
                    }
                    if (attack) {
                        attacked = this.performAttack(event.getNewYaw(), event.getNewPitch());
                    }
                }
                if (swap) {
                    if (attacked) {
                        this.interactAttack(event.getNewYaw(), event.getNewPitch());
                    } else {
                        this.sendUseItem();
                    }
                }
                if (blocked) {
                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                    Myau.blinkManager.setBlinkState(true, BlinkModules.AUTO_BLOCK);
                }
            }
            // Watchdog AutoBlock sends the block use-item packet after the attack, at the stage
            // of the block cycle that precedes the release (Rise postAttackBlock "Watchdog" case).
            if (watchdog && this.eX() && this.target != null) {
                this.watchdogPostAttackBlock();
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (this.isEnabled()) {
            switch (event.getType()) {
                case PRE:
                    if (this.target == null
                            || !this.isValidTarget(this.target.getEntity())
                            || !this.isBoxInAttackRange(this.target.getBox())
                            || !this.isBoxInSwingRange(this.target.getBox())
                            || this.timer.hasTimeElapsed(this.switchDelay.getValue().longValue())) {
                        this.timer.reset();
                        ArrayList<EntityLivingBase> targets = new ArrayList<>();
                        for (Entity entity : mc.theWorld.loadedEntityList) {
                            if (entity instanceof EntityLivingBase
                                    && this.isValidTarget((EntityLivingBase) entity)
                                    && this.isInRange((EntityLivingBase) entity)) {
                                targets.add((EntityLivingBase) entity);
                            }
                        }
                        if (targets.isEmpty()) {
                            this.target = null;
                        } else {
                            if (targets.stream().anyMatch(this::isInSwingRange)) {
                                targets.removeIf(entityLivingBase -> !this.isInSwingRange(entityLivingBase));
                            }
                            if (targets.stream().anyMatch(this::isInAttackRange)) {
                                targets.removeIf(entityLivingBase -> !this.isInAttackRange(entityLivingBase));
                            }
                            if (targets.stream().anyMatch(this::isPlayerTarget)) {
                                targets.removeIf(entityLivingBase -> !this.isPlayerTarget(entityLivingBase));
                            }
                            targets.sort(
                                    (entityLivingBase1, entityLivingBase2) -> {
                                        int sortBase = 0;
                                        switch (this.sort.getValue()) {
                                            case 1:
                                                sortBase = Float.compare(TeamUtil.getHealthScore(entityLivingBase1), TeamUtil.getHealthScore(entityLivingBase2));
                                                break;
                                            case 2:
                                                sortBase = Integer.compare(entityLivingBase1.hurtResistantTime, entityLivingBase2.hurtResistantTime);
                                                break;
                                            case 3:
                                                sortBase = Float.compare(
                                                        RotationUtil.angleToEntity(entityLivingBase1),
                                                        RotationUtil.angleToEntity(entityLivingBase2)
                                                );
                                        }
                                        return sortBase != 0
                                                ? sortBase
                                                : Double.compare(RotationUtil.distanceToEntity(entityLivingBase1), RotationUtil.distanceToEntity(entityLivingBase2));
                                    }
                            );
                            if (this.mode.getValue() == 1 && this.hitRegistered) {
                                this.hitRegistered = false;
                                this.switchTick++;
                            }
                            if (this.mode.getValue() == 0 || this.switchTick >= targets.size()) {
                                this.switchTick = 0;
                            }
                            this.target = new AttackData(targets.get(this.switchTick));
                        }
                    }
                    if (this.target != null) {
                        this.target = new AttackData(this.target.getEntity());
                    }
                    break;
                case POST:
                    // Watchdog AutoBlock blocks via packets only (the render is faked by the
                    // renderer), so never force a real client-side item-in-use for it.
                    if (this.autoBlock.getValue() != 9 && this.isPlayerBlocking() && !mc.thePlayer.isBlocking()) {
                        mc.thePlayer.setItemInUse(mc.thePlayer.getHeldItem(), mc.thePlayer.getHeldItem().getMaxItemUseDuration());
                    }
            }
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onPacket(PacketEvent event) {
        if (this.isEnabled() && !event.isCancelled() && mc.thePlayer != null && mc.theWorld != null) {
            if (event.getPacket() instanceof C07PacketPlayerDigging) {
                C07PacketPlayerDigging packet = (C07PacketPlayerDigging) event.getPacket();
                if (packet.getStatus() == C07PacketPlayerDigging.Action.RELEASE_USE_ITEM) {
                    this.blockingState = false;
                }
            }
            if (event.getPacket() instanceof C09PacketHeldItemChange) {
                this.blockingState = false;
                if (this.isBlocking) {
                    mc.thePlayer.stopUsingItem();
                }
            }
            // Watchdog AutoBlock: track whether an attack/swing packet was sent since the last
            // movement packet, so the release is faked with a slot flick instead (Rise blockPacketSent).
            if (event.getPacket() instanceof C0APacketAnimation) {
                this.blockPacketSent = true;
            } else if (event.getPacket() instanceof C03PacketPlayer) {
                this.blockPacketSent = false;
            }
            if (this.debugLog.getValue() == 1 && this.isAttackAllowed()) {
                if (event.getPacket() instanceof S06PacketUpdateHealth) {
                    float packet = ((S06PacketUpdateHealth) event.getPacket()).getHealth() - mc.thePlayer.getHealth();
                    if (packet != 0.0F && this.lastTickProcessed != mc.thePlayer.ticksExisted) {
                        this.lastTickProcessed = mc.thePlayer.ticksExisted;
                        ChatUtil.sendFormatted(
                                String.format(
                                        "%sHealth: %s&l%s&r (&otick: %d&r)&r",
                                        Myau.clientName,
                                        packet > 0.0F ? "&a" : "&c",
                                        df.format(packet),
                                        mc.thePlayer.ticksExisted
                                )
                        );
                    }
                }
                if (event.getPacket() instanceof S1CPacketEntityMetadata) {
                    S1CPacketEntityMetadata packet = (S1CPacketEntityMetadata) event.getPacket();
                    if (packet.getEntityId() == mc.thePlayer.getEntityId()) {
                        for (WatchableObject watchableObject : packet.func_149376_c()) {
                            if (watchableObject.getDataValueId() == 6) {
                                float diff = (Float) watchableObject.getObject() - mc.thePlayer.getHealth();
                                if (diff != 0.0F && this.lastTickProcessed != mc.thePlayer.ticksExisted) {
                                    this.lastTickProcessed = mc.thePlayer.ticksExisted;
                                    ChatUtil.sendFormatted(
                                            String.format(
                                                    "%sHealth: %s&l%s&r (&otick: %d&r)&r",
                                                    Myau.clientName,
                                                    diff > 0.0F ? "&a" : "&c",
                                                    df.format(diff),
                                                    mc.thePlayer.ticksExisted
                                            )
                                    );
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @EventTarget
    public void onMove(MoveInputEvent event) {
        if (this.isEnabled()) {
            if (this.moveFix.getValue() == 1
                    && this.rotations.getValue() != 3
                    && RotationState.isActived()
                    && RotationState.getPriority() == 1.0F
                    && MoveUtil.isForwardPressed()) {
                MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
            }
            if (this.shouldAutoBlock()) {
                mc.thePlayer.movementInput.jump = false;
            }
            // Rise KillAura.onSlowDown(): "Block Slowdown" keeps the vanilla block slowdown
            // while server-side blocking (SlowDownEvent 0.2f multipliers). The Watchdog cycle
            // never sets a real client-side item-in-use, so the slowdown has to be applied here.
            if (this.blockSlowdown.getValue() && KillAura.blocking && this.eX()) {
                mc.thePlayer.movementInput.moveStrafe *= 0.2F;
                mc.thePlayer.movementInput.moveForward *= 0.2F;
            }
        }
    }

    @EventTarget
    public void onRender(Render3DEvent event) {
        if (this.isEnabled() && target != null) {
            if (this.showTarget.getValue() != 0
                    && TeamUtil.isEntityLoaded(this.target.getEntity())
                    && this.isAttackAllowed()) {
                Color color = new Color(-1);
                switch (this.showTarget.getValue()) {
                    case 1:
                        if (this.target.getEntity().hurtTime > 0) {
                            color = new Color(16733525);
                        } else {
                            color = new Color(5635925);
                        }
                        break;
                    case 2:
                        color = ((HUD) Myau.moduleManager.modules.get(HUD.class)).getColor(System.currentTimeMillis());
                }
                RenderUtil.enableRenderState();
                RenderUtil.drawEntityBox(this.target.getEntity(), color.getRed(), color.getGreen(), color.getBlue());
                RenderUtil.disableRenderState();
            }
        }
    }

    @EventTarget
    public void onLeftClick(LeftClickMouseEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        } else {
            if (this.isEnabled() && this.target != null && this.canAttack()) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        } else if (this.isEnabled() && this.target != null && this.canAttack()) {
            event.setCancelled(true);
        } else if (this.isEnabled() && this.target != null && ItemUtil.isHoldingSword()
                && (this.autoBlock.getValue() == 9 // WATCHDOG: the cycle owns the block packets
                || (this.preventServersideBlocking.getValue() && (this.autoBlock.getValue() == 0 || this.autoBlock.getValue() == 8)))) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onHitBlock(HitBlockEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        } else {
            if (this.isEnabled() && this.target != null && this.canAttack()) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget
    public void onCancelUse(CancelUseEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        }
    }

    @Override
    public void onEnabled() {
        this.target = null;
        this.switchTick = 0;
        this.hitRegistered = false;
        this.attackDelayMS = 0L;
        this.blockTick = 0;
        this.watchdogStage = 0;
        this.fakeBlocked = false;
        this.shortBlockCycle = false;
        this.lastSlotFlickTick = -1;
        this.blockPacketSent = false;
        this.watchdogCanAttack = true;
        KillAura.blocking = false;
    }

    @Override
    public void onDisabled() {
        Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
        if (this.autoBlock.getValue() == 9) {
            // Rise KillAura.onDisable() "Watchdog" branch: fake the release with a slot flick
            // when the server still thinks we are blocking, otherwise release normally.
            if (KillAura.blocking && !this.fakeBlocked && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                this.flickSlot();
            } else if (KillAura.blocking) {
                this.unblock(false);
            }
        }
        KillAura.blocking = false;
        this.blockingState = false;
        this.isBlocking = false;
        this.fakeBlockState = false;
        this.watchdogStage = 0;
        this.fakeBlocked = false;
        this.shortBlockCycle = false;
        this.lastSlotFlickTick = -1;
        this.blockPacketSent = false;
        this.watchdogCanAttack = true;
    }

    @Override
    public void verifyValue(String value) {
        boolean badCps = this.autoBlock.getValue() == 2
                || this.autoBlock.getValue() == 3
                || this.autoBlock.getValue() == 4
                || this.autoBlock.getValue() == 5
                || this.autoBlock.getValue() == 6
                || this.autoBlock.getValue() == 7
                || this.autoBlock.getValue() == 9;
        if (!this.autoBlock.getName().equals(value)) {
            if (this.swingRange.getName().equals(value)) {
                if (this.swingRange.getValue() < this.attackRange.getValue()) {
                    this.attackRange.setValue(this.swingRange.getValue());
                }
            } else if (this.attackRange.getName().equals(value)) {
                if (this.swingRange.getValue() < this.attackRange.getValue()) {
                    this.swingRange.setValue(this.attackRange.getValue());
                }
            } else if (this.minCPS.getName().equals(value)) {
                if (this.minCPS.getValue() > this.maxCPS.getValue()) {
                    this.maxCPS.setValue(this.minCPS.getValue());
                }
            } else if (this.autoBlockMinCPS.getName().equals(value)) {
                if (this.autoBlockMinCPS.getValue() > this.autoBlockMaxCPS.getValue()) {
                    this.autoBlockMaxCPS.setValue(this.autoBlockMinCPS.getValue());
                }
                if(autoBlockMinCPS.getValue() > 10.0F && badCps){
                    autoBlockMinCPS.setValue(10.0F);
                }
            } else if (this.autoBlockMaxCPS.getName().equals(value)) {
                if (this.autoBlockMinCPS.getValue() > this.autoBlockMaxCPS.getValue()) {
                    this.autoBlockMinCPS.setValue(this.autoBlockMaxCPS.getValue());
                }
                if(autoBlockMaxCPS.getValue() > 10.0F && badCps){
                    autoBlockMaxCPS.setValue(10.0F);
                }
            } else {
                if (this.maxCPS.getName().equals(value) && this.minCPS.getValue() > this.maxCPS.getValue()) {
                    this.minCPS.setValue(this.maxCPS.getValue());
                }
            }
        } else {
            if (badCps && (this.autoBlockMinCPS.getValue() > 10.0F || this.autoBlockMaxCPS.getValue() > 10.0F)) {
                this.autoBlockMinCPS.setValue(8.0F);
                this.autoBlockMaxCPS.setValue(10.0F);
            }
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }

    public static class AttackData {
        private final EntityLivingBase entity;
        private final AxisAlignedBB box;
        private final double x;
        private final double y;
        private final double z;

        public AttackData(EntityLivingBase entityLivingBase) {
            this.entity = entityLivingBase;
            double collisionBorderSize = entityLivingBase.getCollisionBorderSize();
            this.box = entityLivingBase.getEntityBoundingBox().expand(collisionBorderSize, collisionBorderSize, collisionBorderSize);
            this.x = entityLivingBase.posX;
            this.y = entityLivingBase.posY;
            this.z = entityLivingBase.posZ;
        }

        public EntityLivingBase getEntity() {
            return this.entity;
        }

        public AxisAlignedBB getBox() {
            return this.box;
        }

        public double getX() {
            return this.x;
        }

        public double getY() {
            return this.y;
        }

        public double getZ() {
            return this.z;
        }
    }
}

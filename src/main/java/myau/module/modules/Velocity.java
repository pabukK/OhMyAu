package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.enums.DelayModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.*;
import myau.mixin.IAccessorEntity;
import myau.mixin.IAccessorMinecraft;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.util.ChatUtil;
import myau.util.KeyBindUtil;
import myau.util.MoveUtil;
import myau.util.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.entity.Entity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.MovingObjectPosition;

public class Velocity extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private int chanceCounter = 0;
    private int delayChanceCounter = 0;
    private boolean pendingExplosion = false;
    private boolean allowNext = true;
    private boolean jumpFlag = false;
    private boolean reverseFlag = false;
    private boolean delayActive = false;

    private boolean shouldJump = false;
    private int jumpCooldown = 0;

    // Jump Reset state
    private boolean setJump = false;
    private boolean ignoreNext = false;
    private boolean aiming = false;
    private int lastHurtTime = 0;
    private double lastFallDistance = 0;
    private int db = 0;

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"VANILLA", "JUMP_RESET", "DELAY", "REVERSE", "LEGIT_TEST"});
    public final IntProperty delayTicks = new IntProperty("delay-ticks", 3, 1, 20, () -> this.mode.getValue() == 2);
    public final PercentProperty delayChance = new PercentProperty("delay-chance", 100, () -> this.mode.getValue() == 2);
    public final PercentProperty chance = new PercentProperty("chance", 100);
    public final PercentProperty horizontal = new PercentProperty("horizontal", 0);
    public final PercentProperty vertical = new PercentProperty("vertical", 100);
    public final PercentProperty explosionHorizontal = new PercentProperty("explosions-horizontal", 100);
    public final PercentProperty explosionVertical = new PercentProperty("explosions-vertical", 100);
    public final BooleanProperty fakeCheck = new BooleanProperty("fake-check", true);
    public final BooleanProperty debugLog = new BooleanProperty("debug-log", false);

    // Jump Reset properties
    public final BooleanProperty requireMouseDown = new BooleanProperty("require-mouse-down", false, () -> this.mode.getValue() == 1);
    public final BooleanProperty requireMovingForward = new BooleanProperty("require-moving-forward", false, () -> this.mode.getValue() == 1);
    public final BooleanProperty requireAim = new BooleanProperty("require-aim", false, () -> this.mode.getValue() == 1);
    public final BooleanProperty delay = new BooleanProperty("delay", false, () -> this.mode.getValue() == 1);
    public final BooleanProperty disableLobby = new BooleanProperty("disable-lobby", false, () -> this.mode.getValue() == 1);

    private boolean isInLiquidOrWeb() {
        return mc.thePlayer.isInWater() || mc.thePlayer.isInLava() || ((IAccessorEntity) mc.thePlayer).getIsInWeb();
    }

    private boolean canDelay() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        return mc.thePlayer.onGround && (!killAura.isEnabled() || !killAura.shouldAutoBlock());
    }

    public Velocity() {
        super("Velocity", false);
    }

    @EventTarget
    public void onKnockback(KnockbackEvent event) {
        if (!this.isEnabled() || event.isCancelled()) {
            this.pendingExplosion = false;
            this.allowNext = true;
        } else if (!this.allowNext || !(Boolean) this.fakeCheck.getValue()) {
            this.allowNext = true;
            if (this.pendingExplosion) {
                this.pendingExplosion = false;
                if (this.explosionHorizontal.getValue() > 0) {
                    event.setX(event.getX() * (double) this.explosionHorizontal.getValue() / 100.0);
                    event.setZ(event.getZ() * (double) this.explosionHorizontal.getValue() / 100.0);
                } else {
                    event.setX(mc.thePlayer.motionX);
                    event.setZ(mc.thePlayer.motionZ);
                }
                if (this.explosionVertical.getValue() > 0) {
                    event.setY(event.getY() * (double) this.explosionVertical.getValue() / 100.0);
                } else {
                    event.setY(mc.thePlayer.motionY);
                }
            } else {
                this.chanceCounter = this.chanceCounter % 100 + this.chance.getValue();
                if (this.chanceCounter >= 100) {
                    // JUMP_RESET does not modify knockback motion here; it handles it via jump timing.
                    this.jumpFlag = (this.mode.getValue() == 2) && event.getY() > 0.0;
                    this.delayActive = this.mode.getValue() == 3;
                    if (this.mode.getValue() != 1) {
                        if (this.horizontal.getValue() > 0) {
                            event.setX(event.getX() * (double) this.horizontal.getValue() / 100.0);
                            event.setZ(event.getZ() * (double) this.horizontal.getValue() / 100.0);
                        } else {
                            event.setX(mc.thePlayer.motionX);
                            event.setZ(mc.thePlayer.motionZ);
                        }
                        if (this.vertical.getValue() > 0) {
                            event.setY(event.getY() * (double) this.vertical.getValue() / 100.0);
                        } else {
                            event.setY(mc.thePlayer.motionY);
                        }
                    }
                }
            }
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() == EventType.PRE) {
            // Delay / Reverse release handling
            if (this.reverseFlag
                    && (
                    this.canDelay()
                            || this.isInLiquidOrWeb()
                            || Myau.delayManager.getDelay() >= (long) this.delayTicks.getValue()
            )) {
                Myau.delayManager.setDelayState(false, DelayModules.VELOCITY);
                this.reverseFlag = false;
            }
            if (this.delayActive) {
                MoveUtil.setSpeed(MoveUtil.getSpeed(), MoveUtil.getMoveYaw());
                this.delayActive = false;
            }

            // --- Jump Reset (mode == 1) ported from ExampleMod ---
            if (this.mode.getValue() == 1) {
                // Delay blink release (replaces ExampleMod's delayTicks countdown)
                if (Myau.delayManager.getDelayModule() == DelayModules.VELOCITY
                        && Myau.delayManager.getDelay() >= (long) this.delayTicks.getValue()
                        && this.delay.getValue()) {
                    Myau.delayManager.setDelayState(false, DelayModules.VELOCITY);
                }

                if (this.db > 0) {
                    this.db--;
                }

                int hurtTime = mc.thePlayer.hurtTime;
                boolean onGround = mc.thePlayer.onGround;

                // High-fall guard: ignore knockback right after a long fall (matches ExampleMod).
                if (onGround && this.lastFallDistance > 3 && !mc.thePlayer.capabilities.allowFlying) {
                    this.ignoreNext = true;
                }

                if (hurtTime > this.lastHurtTime) {
                    boolean mouseDown = org.lwjgl.input.Mouse.isButtonDown(0) || !this.requireMouseDown.getValue();
                    boolean aimingAt = this.aiming || !this.requireAim.getValue();
                    boolean forward = mc.gameSettings.keyBindForward.isKeyDown() || !this.requireMovingForward.getValue();

                    this.handleJumpReset(onGround, aimingAt, forward, mouseDown);
                    this.ignoreNext = false;
                }

                this.lastHurtTime = hurtTime;
                this.lastFallDistance = mc.thePlayer.fallDistance;
            }

            // LEGIT_TEST (mode == 4) — kept from prior implementation.
            if (this.mode.getValue() == 4) {
                int hurtTime = mc.thePlayer.hurtTime;

                if (hurtTime >= 8) {
                    if (jumpCooldown <= 0) {
                        shouldJump = true;
                        jumpCooldown = 2;
                    }
                } else if (hurtTime <= 1) {
                    shouldJump = false;
                    jumpCooldown = 0;
                }

                if (shouldJump && mc.thePlayer.onGround && jumpCooldown <= 0) {
                    mc.thePlayer.jump();
                    shouldJump = false;
                }

                if (jumpCooldown > 0) {
                    jumpCooldown--;
                }
            }
        } else if (event.getType() == EventType.POST) {
            // Release the forced jump once the player stops pressing jump (matches ExampleMod's onPostMotion).
            if (this.mode.getValue() == 1) {
                if (this.setJump && !KeyBindUtil.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode())) {
                    KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
                    this.setJump = false;
                    if (this.debugLog.getValue()) {
                        ChatUtil.sendFormatted(String.format("%sVelocity &7(jump reset: released)&r", Myau.clientName));
                    }
                }
            }
        }
    }

    private void handleJumpReset(boolean onGround, boolean aimingAt, boolean forward, boolean mouseDown) {
        if (!this.isEnabled() || this.mode.getValue() != 1) {
            return;
        }
        if (this.disableLobby.getValue() && this.isInLobby()) {
            return;
        }
        if (this.db > 0) {
            return;
        }
        if (!this.ignoreNext
                && onGround
                && aimingAt
                && forward
                && mouseDown
                && Math.random() * 100.0 < (double) this.chance.getValue()
                && !this.hasBadEffect()) {
            if (this.delay.getValue()) {
                // Packet-level blink: incoming S12 is held by DelayManager until delay-ticks elapse.
                Myau.delayManager.delay(DelayModules.VELOCITY);
            }
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), this.setJump = true);
            KeyBindUtil.pressKeyOnce(mc.gameSettings.keyBindJump.getKeyCode());
            if (this.debugLog.getValue()) {
                ChatUtil.sendFormatted(String.format("%sVelocity &7(jump reset: jumping enabled)&r", Myau.clientName));
            }
        }
    }

    private boolean hasBadEffect() {
        for (PotionEffect potionEffect : mc.thePlayer.getActivePotionEffects()) {
            String name = potionEffect.getEffectName();
            if (name.equals("potion.jump") || name.equals("potion.poison") || name.equals("potion.wither")) {
                return true;
            }
        }
        return false;
    }

    private boolean isInLobby() {
        // Best-effort lobby detection: world name heuristics. Returns false if undeterminable.
        try {
            if (mc.theWorld == null || mc.thePlayer == null) {
                return false;
            }
            if (mc.getNetHandler() == null || mc.getNetHandler().getGameProfile() == null) {
                return false;
            }
            // No dedicated lobby API available; return false to mirror ExampleMod's behavior when isLobby() is false.
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private void checkAim(float yaw, float pitch) {
        MovingObjectPosition result = RotationUtil.rayTrace(
                yaw,
                pitch,
                5.0,
                ((IAccessorMinecraft) mc).getTimer().renderPartialTicks
        );
        this.aiming = result != null
                && result.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
                && result.entityHit instanceof EntityOtherPlayerMP;
    }

    @EventTarget
    public void onLivingUpdate(LivingUpdateEvent event) {
        if (this.jumpFlag) {
            this.jumpFlag = false;
            if (mc.thePlayer.onGround && mc.thePlayer.isSprinting() && !mc.thePlayer.isPotionActive(Potion.jump) && !this.isInLiquidOrWeb()) {
                mc.thePlayer.movementInput.jump = true;
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        // Track aim from outgoing look packets (mirrors ExampleMod's onSendPacket).
        if (event.getType() == EventType.SEND) {
            if (event.getPacket() instanceof C03PacketPlayer.C05PacketPlayerLook) {
                C03PacketPlayer.C05PacketPlayerLook p = (C03PacketPlayer.C05PacketPlayerLook) event.getPacket();
                this.checkAim(p.getYaw(), p.getPitch());
            } else if (event.getPacket() instanceof C03PacketPlayer.C06PacketPlayerPosLook) {
                C03PacketPlayer.C06PacketPlayerPosLook p = (C03PacketPlayer.C06PacketPlayerPosLook) event.getPacket();
                this.checkAim(p.getYaw(), p.getPitch());
            }
        }

        if (event.getType() == EventType.RECEIVE && !event.isCancelled()) {
            if (event.getPacket() instanceof S12PacketEntityVelocity) {
                S12PacketEntityVelocity packet = (S12PacketEntityVelocity) event.getPacket();
                if (packet.getEntityID() == mc.thePlayer.getEntityId()) {
                    // Suppress jump-reset for a short window after an explosion (mirrors ExampleMod's db).
                    if (this.mode.getValue() == 1 && this.db > 0) {
                        event.setCancelled(true);
                        return;
                    }
                    LongJump longJump = (LongJump) Myau.moduleManager.modules.get(LongJump.class);
                    if (this.mode.getValue() == 2
                            && !this.reverseFlag
                            && !this.canDelay()
                            && !this.isInLiquidOrWeb()
                            && !this.pendingExplosion
                            && (!this.allowNext || !(Boolean) this.fakeCheck.getValue())
                            && (!longJump.isEnabled() || !longJump.canStartJump())) {
                        this.delayChanceCounter = this.delayChanceCounter % 100 + this.delayChance.getValue();
                        if (this.delayChanceCounter >= 100) {
                            Myau.delayManager.setDelayState(true, DelayModules.VELOCITY);
                            Myau.delayManager.delayedPacket.offer(packet);
                            event.setCancelled(true);
                            this.reverseFlag = true;
                            return;
                        }
                    }
                    // Jump Reset: delay (blink) the incoming velocity packet when enabled.
                    if (this.mode.getValue() == 1
                            && this.delay.getValue()
                            && Myau.delayManager.getDelayModule() != DelayModules.VELOCITY) {
                        Myau.delayManager.setDelayState(true, DelayModules.VELOCITY);
                        Myau.delayManager.delayedPacket.offer(packet);
                        event.setCancelled(true);
                        return;
                    }
                    if (this.debugLog.getValue()) {
                        ChatUtil.sendFormatted(
                                String.format(
                                        "%sVelocity (&otick: %d, x: %.2f, y: %.2f, z: %.2f&r)&r",
                                        Myau.clientName,
                                        mc.thePlayer.ticksExisted,
                                        (double) packet.getMotionX() / 8000.0,
                                        (double) packet.getMotionY() / 8000.0,
                                        (double) packet.getMotionZ() / 8000.0
                                )
                        );
                    }
                }
            } else if (!(event.getPacket() instanceof S27PacketExplosion)) {
                if (event.getPacket() instanceof S19PacketEntityStatus) {
                    S19PacketEntityStatus packet = (S19PacketEntityStatus) event.getPacket();
                    Entity entity = packet.getEntity(mc.theWorld);
                    if (entity != null && entity.equals(mc.thePlayer) && packet.getOpCode() == 2) {
                        this.allowNext = false;
                    }
                }
            } else {
                S27PacketExplosion packet = (S27PacketExplosion) event.getPacket();
                if (packet.func_149149_c() != 0.0F || packet.func_149144_d() != 0.0F || packet.func_149147_e() != 0.0F) {
                    // Mark explosion-suppression window for Jump Reset (mirrors ExampleMod's db = 10).
                    if (this.mode.getValue() == 1) {
                        this.db = 10;
                    }
                    this.pendingExplosion = true;
                    if (this.explosionHorizontal.getValue() == 0 || this.explosionVertical.getValue() == 0) {
                        event.setCancelled(true);
                    }
                    if (this.debugLog.getValue()) {
                        ChatUtil.sendFormatted(
                                String.format(
                                        "%sExplosion (&otick: %d, x: %.2f, y: %.2f, z: %.2f&r)&r",
                                        Myau.clientName,
                                        mc.thePlayer.ticksExisted,
                                        mc.thePlayer.motionX + (double) packet.func_149149_c(),
                                        mc.thePlayer.motionY + (double) packet.func_149144_d(),
                                        mc.thePlayer.motionZ + (double) packet.func_149147_e()
                                )
                        );
                    }
                }
            }
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.onDisabled();
    }

    @Override
    public void onDisabled() {
        // Release any forced jump key to avoid leaving it stuck.
        if (this.setJump) {
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
        }
        this.pendingExplosion = false;
        this.allowNext = true;
        this.shouldJump = false;
        this.jumpCooldown = 0;
        this.setJump = false;
        this.ignoreNext = false;
        this.aiming = false;
        this.lastHurtTime = 0;
        this.lastFallDistance = 0;
        this.db = 0;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }
}

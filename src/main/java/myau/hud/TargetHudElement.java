package myau.hud;

import myau.module.modules.TargetHUD;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

/**
 * The TargetHUD element. While the HUD editor is active (mouse cursor visible)
 * it renders the original TargetHUD with the player themselves as the target,
 * so it looks exactly like the real thing and can be dragged to the desired
 * spot even when no enemy is currently locked.
 */
public class TargetHudElement extends HudElement {
    private final TargetHUD targetHud;

    public TargetHudElement(TargetHUD targetHud) {
        super(targetHud, 2.0F, (float) new ScaledResolution(Minecraft.getMinecraft()).getScaledHeight() / 2.0F + 26.5F);
        this.targetHud = targetHud;
        this.width = 96.0F;
        this.height = 27.0F;
    }

    @Override
    public void render(float partialTicks, boolean editing) {
        float barWidth = this.targetHud.renderAt(this.x, this.y, partialTicks, editing);
        if (barWidth > 0.0F) {
            float scale = this.targetHud.scale.getValue();
            this.width = barWidth * scale;
            this.height = 27.0F * scale;
        }
    }
}

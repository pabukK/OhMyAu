package myau.hud;

import myau.Myau;
import myau.enums.ChatColors;
import myau.module.Module;
import myau.module.modules.HUD;
import myau.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;

import java.awt.*;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The module list rendered by the HUD module. The list always grows downwards
 * from the element's top-left anchor; the old position properties
 * (position-x / position-y / offset-x / offset-y) were replaced by the
 * drag-to-position feature of the HUD editor.
 */
public class ModuleListElement extends HudElement {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final HUD hud;

    public ModuleListElement(HUD hud) {
        super(hud, 2.0F, 2.0F);
        this.hud = hud;
    }

    @Override
    public void render(float partialTicks, boolean editing) {
        if (!editing && (!this.hud.isEnabled() || mc.gameSettings.showDebugInfo)) {
            return;
        }
        List<Module> modules = Myau.moduleManager.modules.values().stream()
                .filter(module -> module.isEnabled() && !module.isHidden())
                .sorted(Comparator.comparingInt(this::moduleWidth).reversed())
                .collect(Collectors.toList());

        float scale = this.hud.scale.getValue();
        float fontHeight = (float) mc.fontRendererObj.FONT_HEIGHT - 1.0F;
        float lineHeight = fontHeight + (this.hud.shadow.getValue() ? 1.0F : 0.0F);
        float maxWidth = 0.0F;
        for (Module module : modules) {
            maxWidth = Math.max(maxWidth, (float) this.moduleWidth(module));
        }
        if (modules.isEmpty()) {
            maxWidth = (float) mc.fontRendererObj.getStringWidth("ModuleList");
        }
        this.width = (maxWidth + 4.0F) * scale;
        this.height = (modules.isEmpty() ? 1 : modules.size()) * lineHeight * scale;

        GlStateManager.pushMatrix();
        GlStateManager.translate(this.x, this.y, 0.0F);
        GlStateManager.scale(scale, scale, 0.0F);

        if (modules.isEmpty()) {
            if (editing) {
                int color = this.hud.getColor(System.currentTimeMillis()).getRGB();
                RenderUtil.enableRenderState();
                if (this.hud.background.getValue() > 0) {
                    RenderUtil.drawRect(
                            -1.0F,
                            -1.0F,
                            maxWidth + 1.0F,
                            fontHeight + 1.0F,
                            new Color(0.0F, 0.0F, 0.0F, this.hud.background.getValue().floatValue() / 100.0F).getRGB()
                    );
                }
                RenderUtil.disableRenderState();
                GlStateManager.disableDepth();
                mc.fontRendererObj.drawStringWithShadow("ModuleList", 0.0F, 0.0F, color);
                GlStateManager.enableDepth();
            }
        } else {
            long time = System.currentTimeMillis();
            long offset = 0L;
            float localY = 0.0F;
            for (Module module : modules) {
                String moduleName = this.hud.getModuleName(module);
                String[] moduleSuffix = this.hud.getModuleSuffix(module);
                float totalWidth = (float) (this.hud.calculateStringWidth(moduleName, moduleSuffix) - (this.hud.shadow.getValue() ? 0 : 1));
                int color = this.hud.getColor(time, offset).getRGB();
                RenderUtil.enableRenderState();
                if (this.hud.background.getValue() > 0) {
                    RenderUtil.drawRect(
                            -1.0F,
                            localY - (offset == 0L ? 1.0F : 0.0F),
                            1.0F + totalWidth,
                            localY + fontHeight + (this.hud.shadow.getValue() ? 1.0F : 0.0F),
                            new Color(0.0F, 0.0F, 0.0F, this.hud.background.getValue().floatValue() / 100.0F).getRGB()
                    );
                }
                if (this.hud.showBar.getValue()) {
                    if (this.hud.shadow.getValue()) {
                        RenderUtil.drawRect(-3.0F, localY - (offset == 0L ? 1.0F : 0.0F), -2.0F, localY + fontHeight + 1.0F, color);
                        RenderUtil.drawRect(
                                -2.0F,
                                localY - (offset == 0L ? 1.0F : 0.0F),
                                -1.0F,
                                localY + fontHeight + 1.0F,
                                (color & 16579836) >> 2 | color & 0xFF000000
                        );
                    } else {
                        RenderUtil.drawRect(
                                -2.0F,
                                localY - (offset == 0L ? 1.0F : 0.0F),
                                -1.0F,
                                localY + fontHeight + (offset == 0L ? 1.0F : 0.0F),
                                color
                        );
                    }
                }
                RenderUtil.disableRenderState();
                GlStateManager.disableDepth();
                if (this.hud.shadow.getValue()) {
                    mc.fontRendererObj.drawStringWithShadow(moduleName, 0.0F, localY, color);
                } else {
                    mc.fontRendererObj.drawString(moduleName, 0.0F, localY, color, false);
                }
                if (this.hud.suffixes.getValue() && moduleSuffix.length > 0) {
                    float suffixX = (float) mc.fontRendererObj.getStringWidth(moduleName) + 3.0F;
                    for (String string : moduleSuffix) {
                        if (this.hud.shadow.getValue()) {
                            mc.fontRendererObj.drawStringWithShadow(string, suffixX, localY, ChatColors.GRAY.toAwtColor());
                        } else {
                            mc.fontRendererObj.drawString(string, suffixX, localY, ChatColors.GRAY.toAwtColor(), false);
                        }
                        suffixX += (float) mc.fontRendererObj.getStringWidth(string) + (this.hud.shadow.getValue() ? 3.0F : 2.0F);
                    }
                }
                GlStateManager.enableDepth();
                localY += lineHeight;
                offset++;
            }
        }
        GlStateManager.popMatrix();
    }

    private int moduleWidth(Module module) {
        return this.hud.calculateStringWidth(this.hud.getModuleName(module), this.hud.getModuleSuffix(module));
    }
}

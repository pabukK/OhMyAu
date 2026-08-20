package myau.hud;

import myau.Myau;
import myau.config.Config;
import myau.event.EventTarget;
import myau.events.Render2DEvent;
import myau.module.modules.HUD;
import myau.module.modules.TargetHUD;
import myau.ui.ClickGui;
import myau.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages every draggable HUD element.
 *
 * <p>Whenever a screen with a visible mouse cursor is open (chat, inventory,
 * ...) the elements are rendered with a drag frame and can be repositioned by
 * holding the left mouse button. Positions are stored per element and written
 * to the config file as soon as a drag ends.</p>
 */
public class HudManager {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final List<HudElement> elements = new ArrayList<>();
    private HudElement draggingElement = null;
    private float dragOffsetX = 0.0F;
    private float dragOffsetY = 0.0F;
    private boolean mouseDown = false;

    public HudManager() {
        this.elements.add(new ModuleListElement((HUD) Myau.moduleManager.modules.get(HUD.class)));
        this.elements.add(new TargetHudElement((TargetHUD) Myau.moduleManager.modules.get(TargetHUD.class)));
    }

    public List<HudElement> getElements() {
        return this.elements;
    }

    public HudElement getElement(Class<?> moduleClass) {
        for (HudElement element : this.elements) {
            if (element.getModule().getClass().equals(moduleClass)) {
                return element;
            }
        }
        return null;
    }

    /**
     * The HUD editor is active while a screen is open (mouse cursor visible)
     * and the player is in-game. The click GUI is excluded so it keeps full
     * control over its own drag interactions.
     */
    public boolean isEditing(GuiScreen screen) {
        return screen != null && mc.thePlayer != null && mc.theWorld != null && screen instanceof GuiChat;
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (mc.thePlayer == null || mc.currentScreen != null) {
            return;
        }
        for (HudElement element : this.elements) {
            element.render(event.getPartialTicks(), false);
        }
    }

    /**
     * Called from the ForgeHooksClient.drawScreen mixin after every screen was
     * drawn. Polls the mouse for drag input and renders all elements on top of
     * the screen with their drag frames.
     */
    public void renderEditor(GuiScreen screen, int mouseX, int mouseY, float partialTicks) {
        if (!this.isEditing(screen)) {
            return;
        }

        boolean down = Mouse.isButtonDown(0);
        if (down != this.mouseDown) {
            this.mouseDown = down;
            if (down) {
                if (this.draggingElement == null) {
                    HudElement hit = this.hitTest(mouseX, mouseY);
                    if (hit != null) {
                        this.draggingElement = hit;
                        hit.setDragging(true);
                        this.dragOffsetX = mouseX - hit.getX();
                        this.dragOffsetY = mouseY - hit.getY();
                    }
                }
            } else if (this.draggingElement != null) {
                this.draggingElement.setDragging(false);
                this.draggingElement = null;
                this.savePositions();
            }
        } else if (down && this.draggingElement != null) {
            ScaledResolution scaledResolution = new ScaledResolution(mc);
            float newX = mouseX - this.dragOffsetX;
            float newY = mouseY - this.dragOffsetY;
            newX = Math.max(0.0F, Math.min(newX, (float) scaledResolution.getScaledWidth() - this.draggingElement.getWidth()));
            newY = Math.max(0.0F, Math.min(newY, (float) scaledResolution.getScaledHeight() - this.draggingElement.getHeight()));
            this.draggingElement.setX(newX);
            this.draggingElement.setY(newY);
        }

        for (HudElement element : this.elements) {
            element.render(partialTicks, true);
            this.drawFrame(element);
        }

        if (screen instanceof GuiChat) {
            String hint = "Drag the HUD elements to reposition them (release to save)";
            float hintX = ((float) new ScaledResolution(mc).getScaledWidth() - (float) mc.fontRendererObj.getStringWidth(hint)) / 2.0F;
            mc.fontRendererObj.drawStringWithShadow(hint, hintX, 3.0F, 0xFF55FFFF);
        }
    }

    private HudElement hitTest(int mouseX, int mouseY) {
        for (int i = this.elements.size() - 1; i >= 0; i--) {
            HudElement element = this.elements.get(i);
            if (element.contains(mouseX, mouseY)) {
                return element;
            }
        }
        return null;
    }

    private void drawFrame(HudElement element) {
        float x = element.getX();
        float y = element.getY();
        float w = element.getWidth();
        float h = element.getHeight();
        int border = element.isDragging() ? 0xFF55FF55 : 0xFFFFFFFF;
        RenderUtil.enableRenderState();
        RenderUtil.drawLine(x - 1.0F, y - 1.0F, x + w + 1.0F, y - 1.0F, 1.0F, border);
        RenderUtil.drawLine(x + w + 1.0F, y - 1.0F, x + w + 1.0F, y + h + 1.0F, 1.0F, border);
        RenderUtil.drawLine(x + w + 1.0F, y + h + 1.0F, x - 1.0F, y + h + 1.0F, 1.0F, border);
        RenderUtil.drawLine(x - 1.0F, y + h + 1.0F, x - 1.0F, y - 1.0F, 1.0F, border);
        RenderUtil.disableRenderState();
        float labelY = y >= 10.0F ? y - 9.0F : y + h + 1.0F;
        mc.fontRendererObj.drawStringWithShadow(element.getName(), x, labelY, border);
    }

    private void savePositions() {
        try {
            new Config(Config.lastConfig, false).save(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

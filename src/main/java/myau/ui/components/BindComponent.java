package myau.ui.components;

import myau.Myau;
import myau.module.modules.GuiModule;
import myau.module.modules.HUD;
import myau.ui.Component;
import myau.ui.dataset.BindStage;
import myau.util.KeyBindUtil;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class BindComponent implements Component {
    private static final List<BindComponent> instances = new ArrayList<>();

    public static boolean isAnyBinding() {
        for (BindComponent component : instances) {
            if (component.isBinding) {
                return true;
            }
        }
        return false;
    }

    public static void stopAllBinding() {
        for (BindComponent component : instances) {
            component.isBinding = false;
        }
    }

    private boolean isBinding;
    private final ModuleComponent parentModule;
    private int offsetY;
    private int x;
    private int y;

    public BindComponent(ModuleComponent b, int offsetY) {
        this.parentModule = b;
        this.x = b.category.getX() + b.category.getWidth();
        this.y = b.category.getY() + b.offsetY;
        this.offsetY = offsetY;
        instances.add(this);
    }

    private void clearBind() {
        if (this.parentModule.mod instanceof GuiModule) {
            this.parentModule.mod.setKey(54);
        } else {
            this.parentModule.mod.setKey(0);
        }
        this.isBinding = false;
    }

    public void draw(AtomicInteger offset) {
        GL11.glPushMatrix();
        GL11.glScaled(0.5D, 0.5D, 0.5D);
        String displayText = this.isBinding ? BindStage.binding : BindStage.bind + ": " + KeyBindUtil.getKeyName(this.parentModule.mod.getKey());
        this.renderText(displayText, ((HUD) Myau.moduleManager.modules.get(HUD.class)).getColor(System.currentTimeMillis(), offset.get()).getRGB());
        GL11.glPopMatrix();
    }

    @Override
    public void update(int mousePosX, int mousePosY) {
        boolean h = this.isHovered(mousePosX, mousePosY);
        this.y = this.parentModule.category.getY() + this.offsetY;
        this.x = this.parentModule.category.getX();
    }

    public void mouseDown(int x, int y, int button) {
        if (this.isHovered(x, y) && button == 0 && this.parentModule.panelExpand) {
            this.isBinding = !this.isBinding;
        } else if (this.isBinding && this.parentModule.panelExpand) {
            int keyIndex = button - 100;
            
            if (button == 0) {
                this.isBinding = false;
                return;
            }
            
            this.parentModule.mod.setKey(keyIndex);
            this.isBinding = false;
        }
    }

    @Override
    public void mouseReleased(int x, int y, int button) {

    }

    @Override
    public void keyTyped(char chatTyped, int keyCode) {
        if (this.isBinding) {
            if (keyCode == 1) {
                // ESC while binding: clear the bind and stop listening, do NOT close the GUI
                this.clearBind();
                return;
            }

            if (keyCode == 11) {
                this.clearBind();
            } else {
                this.parentModule.mod.setKey(keyCode);
            }

            this.isBinding = false;
        }
    }

    @Override
    public void setComponentStartAt(int newOffsetY) {
        this.offsetY = newOffsetY;
    }

    public boolean isHovered(int x, int y) {
        return x > this.x && x < this.x + this.parentModule.category.getWidth() && y > this.y - 1 && y < this.y + 12;
    }

    public int getHeight() {
        return 12;
    }

    @Override
    public boolean isVisible() {
        return true;
    }

    private void renderText(String s, int color) {
        Minecraft.getMinecraft().fontRendererObj.drawStringWithShadow(s, (float) ((this.parentModule.category.getX() + 4) * 2), (float) ((this.parentModule.category.getY() + this.offsetY + 3) * 2), color);
    }
}

package myau.hud;

import myau.module.Module;

/**
 * A screen element (module list, target HUD, ...) that can be freely dragged
 * around while the mouse cursor is visible (e.g. while the chat screen is open).
 *
 * <p>The (x, y) position is stored in scaled resolution pixels and is persisted
 * to the config file. The width/height fields are refreshed on every render
 * pass and are used for mouse hit-testing while dragging.</p>
 */
public abstract class HudElement {
    protected final Module module;
    protected float x;
    protected float y;
    protected float width;
    protected float height;
    protected boolean dragging;

    public HudElement(Module module, float x, float y) {
        this.module = module;
        this.x = x;
        this.y = y;
        this.width = 0.0F;
        this.height = 0.0F;
    }

    public Module getModule() {
        return this.module;
    }

    public String getName() {
        return this.module.getName();
    }

    public float getX() {
        return this.x;
    }

    public void setX(float x) {
        this.x = x;
    }

    public float getY() {
        return this.y;
    }

    public void setY(float y) {
        this.y = y;
    }

    public float getWidth() {
        return this.width;
    }

    public float getHeight() {
        return this.height;
    }

    public boolean isDragging() {
        return this.dragging;
    }

    public void setDragging(boolean dragging) {
        this.dragging = dragging;
    }

    public boolean contains(float mouseX, float mouseY) {
        return mouseX >= this.x && mouseX <= this.x + this.width
                && mouseY >= this.y && mouseY <= this.y + this.height;
    }

    /**
     * Renders the element at its stored position.
     *
     * @param editing whether the HUD editor is currently active (a screen with a
     *                visible mouse cursor is open); elements may render a sample
     *                or preview in this mode so they can be dragged around.
     */
    public abstract void render(float partialTicks, boolean editing);
}

package myau.mixin;

import myau.Myau;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.ForgeHooksClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = {ForgeHooksClient.class}, remap = false)
public abstract class MixinForgeHooksClient {
    @Inject(
            method = {"drawScreen"},
            at = {@At("RETURN")},
            remap = false
    )
    private static void onDrawScreen(GuiScreen screen, int mouseX, int mouseY, float partialTicks, CallbackInfo callbackInfo) {
        if (Myau.hudManager != null) {
            Myau.hudManager.renderEditor(screen, mouseX, mouseY, partialTicks);
        }
    }
}

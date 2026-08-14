package dev.squidutils;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import io.github.notenoughupdates.moulconfig.gui.GuiContext;
import io.github.notenoughupdates.moulconfig.gui.GuiElementComponent;
import io.github.notenoughupdates.moulconfig.platform.MoulConfigScreenComponent;
import net.minecraft.network.chat.Component;

/**
 * Puts the config button next to ShardFuse in Mod Menu's list.
 *
 * <p>Mod Menu is a compile-only dependency, and this entrypoint is simply not
 * invoked when it is absent, so the mod loads fine without it.
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            var config = SquidUtils.managedConfig();
            if (config == null) return null;
            return new MoulConfigScreenComponent(
                    Component.literal("squidutils"),
                    // MoulConfigEditor is a GuiElement; GuiContext wants a
                    // GuiComponent, and GuiElementComponent bridges the two.
                    new GuiContext(new GuiElementComponent(config.getEditor())),
                    parent);
        };
    }
}

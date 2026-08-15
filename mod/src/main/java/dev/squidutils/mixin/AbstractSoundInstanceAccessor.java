package dev.squidutils.mixin;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the raw {@code volume}/{@code pitch} fields directly.
 *
 * <p>{@link AbstractSoundInstance#getVolume()} and {@code getPitch()} both
 * read through the instance's {@code sound} field (confirmed by
 * disassembling this exact game version), which {@link SoundEngineMixin}
 * observes before {@code SoundEngine.play} has resolved it - calling either
 * getter that early throws. The plain fields underneath are already set by
 * the time {@code play} is called, so reading them directly sidesteps the
 * unresolved {@code sound} entirely.
 */
@Mixin(AbstractSoundInstance.class)
public interface AbstractSoundInstanceAccessor {

    @Accessor("volume")
    float squidutils$rawVolume();

    @Accessor("pitch")
    float squidutils$rawPitch();
}

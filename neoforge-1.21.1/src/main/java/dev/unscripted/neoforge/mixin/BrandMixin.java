package dev.unscripted.neoforge.mixin;

import com.mojang.authlib.GameProfile;
import dev.unscripted.neoforge.Bedrock;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// vanilla descarta la marca del cliente
@Mixin(ServerCommonPacketListenerImpl.class)
abstract class BrandMixin {
    @Shadow
    protected abstract GameProfile playerProfile();

    @Inject(method = "handleCustomPayload", at = @At("HEAD"))
    private void unscripted$brand(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        if (packet.payload() instanceof BrandPayload brand) {
            Bedrock.brand(playerProfile().getId(), brand.brand());
        }
    }
}

package dev.engine_room.flywheel.api.event;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.client.multiplayer.ClientLevel;

@FunctionalInterface
public interface ReloadLevelRendererCallback {
	Event<ReloadLevelRendererCallback> EVENT =
			EventFactory.createArrayBacked(ReloadLevelRendererCallback.class, callbacks -> level -> {
				for (ReloadLevelRendererCallback callback : callbacks) {
					callback.onReloadLevelRenderer(level);
				}
			});

	void onReloadLevelRenderer(ClientLevel level);
}

package dev.engine_room.flywheel.api;

import net.minecraft.util.ResourceLocation;

public final class Flywheel {
	public static final String ID = "flywheel";

	private Flywheel() {
	}

	public static ResourceLocation resourcelocation(String path) {
		return new ResourceLocation(ID, path);
	}
}

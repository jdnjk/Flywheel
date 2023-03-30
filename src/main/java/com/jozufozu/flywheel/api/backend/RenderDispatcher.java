package com.jozufozu.flywheel.api.backend;

import com.jozufozu.flywheel.api.event.RenderContext;
import com.jozufozu.flywheel.api.event.RenderStage;
import com.jozufozu.flywheel.api.task.TaskExecutor;

import net.minecraft.client.Camera;

public interface RenderDispatcher {

	void beginFrame(TaskExecutor executor, RenderContext context);

	void renderStage(TaskExecutor executor, RenderContext context, RenderStage stage);

	/**
	 * Maintain the integer origin coordinate to be within a certain distance from the camera in all directions,
	 * preventing floating point precision issues at high coordinates.
	 * @return {@code true} if the origin coordinate was changed, {@code false} otherwise.
	 */
	boolean maintainOriginCoordinate(Camera camera);

	void delete();
}
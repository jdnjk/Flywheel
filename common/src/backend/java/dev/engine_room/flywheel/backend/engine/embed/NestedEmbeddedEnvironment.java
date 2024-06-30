package dev.engine_room.flywheel.backend.engine.embed;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import dev.engine_room.flywheel.api.event.RenderStage;
import dev.engine_room.flywheel.backend.engine.EngineImpl;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import it.unimi.dsi.fastutil.longs.LongSet;

public class NestedEmbeddedEnvironment extends AbstractEmbeddedEnvironment {
	private final AbstractEmbeddedEnvironment parent;

	public NestedEmbeddedEnvironment(AbstractEmbeddedEnvironment parent, EngineImpl engine, RenderStage renderStage) {
		super(engine, renderStage);
		this.parent = parent;
	}

	@Override
	public void lightChunks(LongSet chunks) {
		// noop
	}

	@Override
	public void setupLight(GlProgram program) {
		parent.setupLight(program);
	}

	@Override
	public void composeMatrices(Matrix4f pose, Matrix3f normal) {
		parent.composeMatrices(pose, normal);
		pose.mul(this.pose);
		normal.mul(this.normal);
	}
}
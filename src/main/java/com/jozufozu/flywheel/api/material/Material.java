package com.jozufozu.flywheel.api.material;

import com.jozufozu.flywheel.api.RenderStage;
import com.jozufozu.flywheel.api.vertex.MutableVertexList;
import com.jozufozu.flywheel.core.source.FileResolution;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;

public interface Material {
	RenderStage getRenderStage();

	FileResolution getVertexShader();

	FileResolution getFragmentShader();

	void setup();

	void clear();

	RenderType getBatchingRenderType();

	VertexTransformer getVertexTransformer();

	public interface VertexTransformer {
		void transform(MutableVertexList vertexList, ClientLevel level);
	}
}

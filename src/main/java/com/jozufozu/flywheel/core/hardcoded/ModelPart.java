package com.jozufozu.flywheel.core.hardcoded;

import java.util.List;

import org.joml.Vector4f;
import org.joml.Vector4fc;

import com.jozufozu.flywheel.api.vertex.MutableVertexList;
import com.jozufozu.flywheel.api.vertex.ReusableVertexList;
import com.jozufozu.flywheel.backend.memory.MemoryBlock;
import com.jozufozu.flywheel.core.model.Mesh;
import com.jozufozu.flywheel.core.model.ModelUtil;
import com.jozufozu.flywheel.core.vertex.Formats;
import com.jozufozu.flywheel.core.vertex.PosTexNormalVertex;

public class ModelPart implements Mesh {
	private final int vertexCount;
	private final MemoryBlock contents;
	private final ReusableVertexList vertexList;
	private final Vector4f boundingSphere;
	private final String name;

	public ModelPart(List<PartBuilder.CuboidBuilder> cuboids, String name) {
		this.name = name;

		this.vertexCount = countVertices(cuboids);

		contents = MemoryBlock.malloc(size());
		long ptr = contents.ptr();
		VertexWriter writer = new VertexWriterImpl(ptr);
		for (PartBuilder.CuboidBuilder cuboid : cuboids) {
			cuboid.write(writer);
		}

		vertexList = getVertexType().createVertexList();
		vertexList.ptr(ptr);
		vertexList.vertexCount(vertexCount);

		boundingSphere = ModelUtil.computeBoundingSphere(vertexList);
	}

	public static PartBuilder builder(String name, int sizeU, int sizeV) {
		return new PartBuilder(name, sizeU, sizeV);
	}

	@Override
	public PosTexNormalVertex getVertexType() {
		return Formats.POS_TEX_NORMAL;
	}

	@Override
	public int getVertexCount() {
		return vertexCount;
	}

	@Override
	public void write(long ptr) {
		contents.copyTo(ptr);
	}

	@Override
	public void write(MutableVertexList dst) {
		vertexList.writeAll(dst);
	}

	@Override
	public Vector4fc getBoundingSphere() {
		return boundingSphere;
	}

	@Override
	public void delete() {
		contents.free();
	}

	@Override
	public String name() {
		return name;
	}

	private static int countVertices(List<PartBuilder.CuboidBuilder> cuboids) {
		int vertices = 0;
		for (PartBuilder.CuboidBuilder cuboid : cuboids) {
			vertices += cuboid.vertices();
		}
		return vertices;
	}
}

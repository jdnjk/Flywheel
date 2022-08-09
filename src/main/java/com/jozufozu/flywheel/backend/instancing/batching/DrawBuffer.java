package com.jozufozu.flywheel.backend.instancing.batching;

import java.nio.ByteBuffer;

import org.lwjgl.system.MemoryUtil;

import com.jozufozu.flywheel.api.vertex.ReusableVertexList;
import com.jozufozu.flywheel.api.vertex.VertexListProvider;
import com.jozufozu.flywheel.core.vertex.VertexListProviderRegistry;
import com.mojang.blaze3d.platform.MemoryTracker;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.RenderType;

/**
 * A byte buffer that can be used to draw vertices through multiple {@link ReusableVertexList}s.
 *
 * The number of vertices needs to be known ahead of time.
 */
public class DrawBuffer {
	private final RenderType parent;
	private final VertexFormat format;
	private final int stride;
	private final VertexListProvider provider;

	private ByteBuffer backingBuffer;
	private int expectedVertices;
	private long ptr;

	public DrawBuffer(RenderType parent) {
		this.parent = parent;
		format = parent.format();
		stride = format.getVertexSize();
		provider = VertexListProviderRegistry.getOrInfer(format);
	}

	/**
	 * Prepares this buffer by initializing a block of memory.
	 * @param vertexCount The number of vertices to reserve memory for.
	 * @throws IllegalStateException If the buffer is already in use.
	 */
	public void prepare(int vertexCount) {
		if (expectedVertices != 0) {
			throw new IllegalStateException("Already drawing");
		}

		this.expectedVertices = vertexCount;

		// Add one extra vertex to uphold the vanilla assumption that BufferBuilders have at least
		// enough buffer space for one more vertex. Rubidium checks for this extra space when popNextBuffer
		// is called and reallocates the buffer if there is not space for one more vertex.
		int byteSize = stride * (vertexCount + 1);

		if (backingBuffer == null) {
			backingBuffer = MemoryTracker.create(byteSize);
		} else if (byteSize > backingBuffer.capacity()) {
			backingBuffer = MemoryTracker.resize(backingBuffer, byteSize);
		}

		backingBuffer.clear();
		ptr = MemoryUtil.memAddress(backingBuffer);
		MemoryUtil.memSet(ptr, 0, byteSize);
	}

	public ReusableVertexList slice(int startVertex, int vertexCount) {
		ReusableVertexList vertexList = provider.createVertexList();
		vertexList.ptr(ptr + startVertex * stride);
		vertexList.setVertexCount(vertexCount);
		return vertexList;
	}

	/**
	 * Injects the backing buffer into the given builder and prepares it for rendering.
	 * @param bufferBuilder The buffer builder to inject into.
	 */
	public void inject(BufferBuilderExtension bufferBuilder) {
		bufferBuilder.flywheel$injectForRender(backingBuffer, format, expectedVertices);
	}

	public int getVertexCount() {
		return expectedVertices;
	}

	/**
	 * @return {@code true} if the buffer has any vertices.
	 */
	public boolean hasVertices() {
		return expectedVertices > 0;
	}

	/**
	 * Reset the draw buffer to have no vertices.
	 *
	 * Does not clear the backing buffer.
	 */
	public void reset() {
		this.expectedVertices = 0;
	}
}

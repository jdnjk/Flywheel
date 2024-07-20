package dev.engine_room.flywheel.backend.engine.indirect;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL30.glBindBufferRange;
import static org.lwjgl.opengl.GL40.glDrawElementsIndirect;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualType;
import dev.engine_room.flywheel.backend.Samplers;
import dev.engine_room.flywheel.backend.compile.ContextShader;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.engine.CommonCrumbling;
import dev.engine_room.flywheel.backend.engine.DrawManager;
import dev.engine_room.flywheel.backend.engine.GroupKey;
import dev.engine_room.flywheel.backend.engine.InstancerKey;
import dev.engine_room.flywheel.backend.engine.LightStorage;
import dev.engine_room.flywheel.backend.engine.MaterialRenderState;
import dev.engine_room.flywheel.backend.engine.MeshPool;
import dev.engine_room.flywheel.backend.engine.TextureBinder;
import dev.engine_room.flywheel.backend.engine.uniform.Uniforms;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.backend.gl.array.GlVertexArray;
import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferType;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import net.minecraft.client.resources.model.ModelBakery;

public class IndirectDrawManager extends DrawManager<IndirectInstancer<?>> {
	private final IndirectPrograms programs;
	private final StagingBuffer stagingBuffer;
	private final MeshPool meshPool;
	private final GlVertexArray vertexArray;
	private final Map<GroupKey<?>, IndirectCullingGroup<?>> cullingGroups = new HashMap<>();
	private final GlBuffer crumblingDrawBuffer = new GlBuffer();
	private final LightBuffers lightBuffers;

	public IndirectDrawManager(IndirectPrograms programs) {
		this.programs = programs;
		programs.acquire();

		stagingBuffer = new StagingBuffer(this.programs);
		meshPool = new MeshPool();
		vertexArray = GlVertexArray.create();
		meshPool.bind(vertexArray);
		lightBuffers = new LightBuffers();
	}

	@Override
	protected <I extends Instance> IndirectInstancer<?> create(InstancerKey<I> key) {
		return new IndirectInstancer<>(key.type(), key.environment(), key.model());
	}

	@SuppressWarnings("unchecked")
	@Override
	protected <I extends Instance> void initialize(InstancerKey<I> key, IndirectInstancer<?> instancer) {
		var groupKey = new GroupKey<>(key.type(), key.environment());
		var group = (IndirectCullingGroup<I>) cullingGroups.computeIfAbsent(groupKey, t -> new IndirectCullingGroup<>(t.instanceType(), t.environment(), programs));
		group.add((IndirectInstancer<I>) instancer, key.model(), key.visualType(), meshPool);
	}

	public boolean hasVisualType(VisualType visualType) {
		for (var group : cullingGroups.values()) {
			if (group.hasVisualType(visualType)) {
				return true;
			}
		}
		return false;
	}

	public void render(VisualType visualType) {
		if (!hasVisualType(visualType)) {
			return;
		}

		try (var state = GlStateTracker.getRestoreState()) {
			TextureBinder.bindLightAndOverlay();

			vertexArray.bindForDraw();
			lightBuffers.bind();
			Uniforms.bindAll();

			for (var group : cullingGroups.values()) {
				group.submit(visualType);
			}

			MaterialRenderState.reset();
			TextureBinder.resetLightAndOverlay();
		}
	}

	@Override
	public void flush(LightStorage lightStorage) {
		super.flush(lightStorage);

		for (var group : cullingGroups.values()) {
			group.flushInstancers();
		}

		cullingGroups.values()
				.removeIf(IndirectCullingGroup::checkEmptyAndDelete);

		instancers.values()
				.removeIf(instancer -> instancer.instanceCount() == 0);

		meshPool.flush();

		stagingBuffer.reclaim();

		lightBuffers.flush(stagingBuffer, lightStorage);

		for (var group : cullingGroups.values()) {
			group.upload(stagingBuffer);
		}

		stagingBuffer.flush();

		for (var group : cullingGroups.values()) {
			group.dispatchCull();
		}

		for (var group : cullingGroups.values()) {
			group.dispatchApply();
		}
	}

	@Override
	public void delete() {
		super.delete();

		cullingGroups.values()
				.forEach(IndirectCullingGroup::delete);
		cullingGroups.clear();

		stagingBuffer.delete();

		meshPool.delete();

		crumblingDrawBuffer.delete();

		programs.release();
	}

	public void renderCrumbling(List<Engine.CrumblingBlock> crumblingBlocks) {
		var byType = doCrumblingSort(IndirectInstancer.class, crumblingBlocks);

		if (byType.isEmpty()) {
			return;
		}

		try (var state = GlStateTracker.getRestoreState()) {
			TextureBinder.bindLightAndOverlay();

			vertexArray.bindForDraw();
			Uniforms.bindAll();

			var crumblingMaterial = SimpleMaterial.builder();

			// Scratch memory for writing draw commands.
			var block = MemoryBlock.malloc(IndirectBuffers.DRAW_COMMAND_STRIDE);

			GlBufferType.DRAW_INDIRECT_BUFFER.bind(crumblingDrawBuffer.handle());
			glBindBufferRange(GL_SHADER_STORAGE_BUFFER, BufferBindings.DRAW, crumblingDrawBuffer.handle(), 0, IndirectBuffers.DRAW_COMMAND_STRIDE);

			for (var groupEntry : byType.entrySet()) {
				var byProgress = groupEntry.getValue();

				// Set up the crumbling program buffers. Nothing changes here between draws.
				cullingGroups.get(groupEntry.getKey())
						.bindWithContextShader(ContextShader.CRUMBLING);

				for (var progressEntry : byProgress.int2ObjectEntrySet()) {
					Samplers.CRUMBLING.makeActive();
					TextureBinder.bind(ModelBakery.BREAKING_LOCATIONS.get(progressEntry.getIntKey()));

					for (var instanceHandlePair : progressEntry.getValue()) {
						IndirectInstancer<?> instancer = instanceHandlePair.first();
						int instanceIndex = instanceHandlePair.second().index;

						for (IndirectDraw draw : instancer.draws()) {
							// Transform the material to be suited for crumbling.
							CommonCrumbling.applyCrumblingProperties(crumblingMaterial, draw.material());

							MaterialRenderState.setup(crumblingMaterial);

							// Upload the draw command.
							draw.writeWithOverrides(block.ptr(), instanceIndex, crumblingMaterial);
							crumblingDrawBuffer.upload(block);

							// Submit! Everything is already bound by here.
							glDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, 0);
						}
					}

				}
			}

			MaterialRenderState.reset();
			TextureBinder.resetLightAndOverlay();

			block.free();
		}
	}
}

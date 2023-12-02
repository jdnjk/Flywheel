package com.jozufozu.flywheel.backend.engine.instancing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.jozufozu.flywheel.api.backend.Engine;
import com.jozufozu.flywheel.api.instance.Instance;
import com.jozufozu.flywheel.api.material.Material;
import com.jozufozu.flywheel.api.material.Transparency;
import com.jozufozu.flywheel.api.material.WriteMask;
import com.jozufozu.flywheel.backend.MaterialUtil;
import com.jozufozu.flywheel.backend.compile.InstancingPrograms;
import com.jozufozu.flywheel.backend.engine.InstanceHandleImpl;
import com.jozufozu.flywheel.backend.engine.UniformBuffer;
import com.jozufozu.flywheel.gl.GlStateTracker;
import com.jozufozu.flywheel.gl.GlTextureUnit;
import com.jozufozu.flywheel.lib.context.Contexts;
import com.jozufozu.flywheel.lib.material.CutoutShaders;
import com.jozufozu.flywheel.lib.material.SimpleMaterial;
import com.mojang.blaze3d.systems.RenderSystem;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.ModelBakery;

public class InstancedCrumbling {
	public static void render(List<Engine.CrumblingBlock> crumblingBlocks) {
		// Sort draw calls into buckets, so we don't have to do as many shader binds.
		var byShaderState = doCrumblingSort(crumblingBlocks);

		if (byShaderState.isEmpty()) {
			return;
		}

		try (var state = GlStateTracker.getRestoreState()) {
			for (var shaderStateEntry : byShaderState.entrySet()) {
				var byProgress = shaderStateEntry.getValue();

				if (byProgress.isEmpty()) {
					continue;
				}

				ShaderState shader = shaderStateEntry.getKey();

				var baseMaterial = shader.material();
				int diffuseTexture = getDiffuseTexture(baseMaterial);

				var crumblingMaterial = SimpleMaterial.from(baseMaterial)
						.transparency(Transparency.CRUMBLING)
						.writeMask(WriteMask.COLOR)
						.polygonOffset(true)
						.cutout(CutoutShaders.OFF);

				var program = InstancingPrograms.get()
						.get(shader.vertexType(), shader.instanceType(), Contexts.CRUMBLING);
				UniformBuffer.syncAndBind(program);

				InstancingEngine.uploadMaterialIDUniform(program, crumblingMaterial);

				for (Int2ObjectMap.Entry<List<Runnable>> progressEntry : byProgress.int2ObjectEntrySet()) {
					var drawCalls = progressEntry.getValue();

					if (drawCalls.isEmpty()) {
						continue;
					}

					crumblingMaterial.baseTexture(ModelBakery.BREAKING_LOCATIONS.get(progressEntry.getIntKey()));

					MaterialUtil.setup(crumblingMaterial);

					RenderSystem.setShaderTexture(1, diffuseTexture);
					GlTextureUnit.T1.makeActive();
					RenderSystem.bindTexture(diffuseTexture);

					drawCalls.forEach(Runnable::run);
				}
			}

			MaterialUtil.reset();
		}
	}

	@NotNull
	private static Map<ShaderState, Int2ObjectMap<List<Runnable>>> doCrumblingSort(List<Engine.CrumblingBlock> instances) {
		Map<ShaderState, Int2ObjectMap<List<Runnable>>> out = new HashMap<>();

		for (Engine.CrumblingBlock triple : instances) {
			int progress = triple.progress();

			if (progress < 0 || progress >= ModelBakery.DESTROY_TYPES.size()) {
				continue;
			}

			for (Instance instance : triple.instances()) {
				// Filter out instances that weren't created by this engine.
				// If all is well, we probably shouldn't take the `continue`
				// branches but better to do checked casts.
				if (!(instance.handle() instanceof InstanceHandleImpl impl)) {
					continue;
				}
				if (!(impl.instancer instanceof InstancedInstancer<?> instancer)) {
					continue;
				}

				List<DrawCall> draws = instancer.drawCalls();

				draws.removeIf(DrawCall::isInvalid);

				for (DrawCall draw : draws) {
					out.computeIfAbsent(draw.shaderState, $ -> new Int2ObjectArrayMap<>())
							.computeIfAbsent(progress, $ -> new ArrayList<>())
							.add(() -> draw.renderOne(impl));
				}
			}
		}
		return out;
	}

	private static int getDiffuseTexture(Material material) {
		return Minecraft.getInstance()
				.getTextureManager()
				.getTexture(material.baseTexture())
				.getId();
	}
}

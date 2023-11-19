package com.jozufozu.flywheel.impl.visualization;

import org.jetbrains.annotations.Nullable;
import org.joml.FrustumIntersection;

import com.jozufozu.flywheel.api.backend.BackendManager;
import com.jozufozu.flywheel.api.backend.Engine;
import com.jozufozu.flywheel.api.event.RenderContext;
import com.jozufozu.flywheel.api.event.RenderStage;
import com.jozufozu.flywheel.api.task.Flag;
import com.jozufozu.flywheel.api.task.Plan;
import com.jozufozu.flywheel.api.task.TaskExecutor;
import com.jozufozu.flywheel.api.visual.DynamicVisual;
import com.jozufozu.flywheel.api.visual.Effect;
import com.jozufozu.flywheel.api.visual.TickableVisual;
import com.jozufozu.flywheel.api.visualization.VisualManager;
import com.jozufozu.flywheel.api.visualization.VisualizationLevel;
import com.jozufozu.flywheel.api.visualization.VisualizationManager;
import com.jozufozu.flywheel.extension.ClientLevelExtension;
import com.jozufozu.flywheel.impl.task.FlwTaskExecutor;
import com.jozufozu.flywheel.impl.task.ParallelTaskExecutor;
import com.jozufozu.flywheel.impl.visualization.manager.BlockEntityVisualManager;
import com.jozufozu.flywheel.impl.visualization.manager.EffectVisualManager;
import com.jozufozu.flywheel.impl.visualization.manager.EntityVisualManager;
import com.jozufozu.flywheel.lib.math.MatrixUtil;
import com.jozufozu.flywheel.lib.task.NamedFlag;
import com.jozufozu.flywheel.lib.task.NestedPlan;
import com.jozufozu.flywheel.lib.task.RaisePlan;
import com.jozufozu.flywheel.lib.task.SimplyComposedPlan;
import com.jozufozu.flywheel.lib.util.LevelAttached;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * A manager class for a single world where visualization is supported.
 */
public class VisualizationManagerImpl implements VisualizationManager {
	private static final LevelAttached<VisualizationManagerImpl> MANAGERS = new LevelAttached<>(VisualizationManagerImpl::new, VisualizationManagerImpl::delete);

	private final Engine engine;
	private final ParallelTaskExecutor taskExecutor;

	private final BlockEntityVisualManager blockEntities;
	private final EntityVisualManager entities;
	private final EffectVisualManager effects;

	private final Plan<TickContext> tickPlan;
	private final Plan<RenderContext> framePlan;

	private final Flag tickFlag = new NamedFlag("tick");
	private final Flag frameFlag = new NamedFlag("frame");

	private VisualizationManagerImpl(LevelAccessor level) {
		engine = BackendManager.getBackend()
				.createEngine(level);
		// FIXME: All VisualizationManagerImpls use the same executor so calls like syncPoint and discardAndAwait could adversely impact other active VisualizationManagerImpls
		taskExecutor = FlwTaskExecutor.get();

		blockEntities = new BlockEntityVisualManager(engine);
		entities = new EntityVisualManager(engine);
		effects = new EffectVisualManager(engine);

		tickPlan = blockEntities.createTickPlan()
				.and(entities.createTickPlan())
				.and(effects.createTickPlan())
				.then(RaisePlan.raise(tickFlag))
				.simplify();
		framePlan = new FramePlan().then(RaisePlan.raise(frameFlag));
	}

	public static boolean supportsVisualization(@Nullable LevelAccessor level) {
		if (!BackendManager.isOn()) {
			return false;
		}

		if (level == null) {
			return false;
		}

		if (!level.isClientSide()) {
			return false;
		}

		if (level instanceof VisualizationLevel flywheelLevel && flywheelLevel.supportsVisualization()) {
			return true;
		}

		return level == Minecraft.getInstance().level;
	}

	@Nullable
	public static VisualizationManagerImpl get(@Nullable LevelAccessor level) {
		if (!supportsVisualization(level)) {
			return null;
		}

		return MANAGERS.get(level);
	}

	public static VisualizationManagerImpl getOrThrow(@Nullable LevelAccessor level) {
		if (!supportsVisualization(level)) {
			throw new IllegalStateException("Cannot retrieve visualization manager when visualization is not supported by level '" + level + "'!");
		}

		return MANAGERS.get(level);
	}

	// TODO: Consider making this reset action reuse the existing added game objects instead of readding them, potentially by keeping the same VisualizationManagerImpl and not fully deleting it
	// TODO: Consider changing parameter type to Level since it is also possible to get all entities from it
	public static void reset(ClientLevel level) {
		MANAGERS.remove(level);
		VisualizationManagerImpl manager = get(level);
		if (manager == null) {
			return;
		}

		// Block entities are loaded while chunks are baked.
		// Entities are loaded with the level, so when chunks are reloaded they need to be re-added.
		ClientLevelExtension.getAllLoadedEntities(level)
				.forEach(manager.getEntities()::queueAdd);
	}

	@Override
	public Vec3i getRenderOrigin() {
		return engine.renderOrigin();
	}

	@Override
	public VisualManager<BlockEntity> getBlockEntities() {
		return blockEntities;
	}

	@Override
	public VisualManager<Entity> getEntities() {
		return entities;
	}

	@Override
	public VisualManager<Effect> getEffects() {
		return effects;
	}

	/**
	 * Tick the visuals after the game has ticked:
	 * <p>
	 * Call {@link TickableVisual#tick} on all visuals in this world.
	 * </p>
	 */
	public void tick(double cameraX, double cameraY, double cameraZ) {
		// Make sure we're done with any prior frame or tick to avoid racing.
		taskExecutor.syncTo(frameFlag);
		taskExecutor.lower(frameFlag);

		taskExecutor.syncTo(tickFlag);
		taskExecutor.lower(tickFlag);

		tickPlan.execute(taskExecutor, new TickContext(cameraX, cameraY, cameraZ));
	}

	/**
	 * Get ready to render a frame.
	 * <p>
	 *     Check and update the render origin.
	 *     <br>
	 *     Call {@link DynamicVisual#beginFrame} on all visuals in this world.
	 * </p>
	 */
	public void beginFrame(RenderContext context) {
		// Make sure we're done with the last tick.
		// Note we don't lower here because many frames may happen per tick.
		taskExecutor.syncTo(tickFlag);

		framePlan.execute(taskExecutor, context);
	}

	/**
	 * Draw all visuals for the given stage.
	 */
	public void renderStage(RenderContext context, RenderStage stage) {
		engine.renderStage(taskExecutor, context, stage);
	}

	/**
	 * Free all acquired resources and delete this manager.
	 */
	public void delete() {
		// Just finish everything. This may include the work of others but that's okay.
		taskExecutor.syncPoint();

		// Now clean up.
		blockEntities.invalidate();
		entities.invalidate();
		effects.invalidate();
		engine.delete();
	}

	private class FramePlan implements SimplyComposedPlan<RenderContext> {
		private final Plan<Float> recreationPlan = NestedPlan.of(blockEntities.createRecreationPlan(), entities.createRecreationPlan(), effects.createRecreationPlan());
		private final Plan<FrameContext> normalPlan = blockEntities.createFramePlan()
				.and(entities.createFramePlan())
				.and(effects.createFramePlan());

		private final Plan<RenderContext> enginePlan = engine.createFramePlan();

		@Override
		public void execute(TaskExecutor taskExecutor, RenderContext context, Runnable onCompletion) {
			Runnable then = () -> enginePlan.execute(taskExecutor, context, onCompletion);
			float partialTick = context.partialTick();

			if (engine.updateRenderOrigin(context.camera())) {
				recreationPlan.execute(taskExecutor, partialTick, then);
			} else {
				Vec3i renderOrigin = engine.renderOrigin();
				var cameraPos = context.camera()
						.getPosition();
				double cameraX = cameraPos.x;
				double cameraY = cameraPos.y;
				double cameraZ = cameraPos.z;

				org.joml.Matrix4f proj = MatrixUtil.toJoml(context.viewProjection());
				proj.translate((float) (renderOrigin.getX() - cameraX), (float) (renderOrigin.getY() - cameraY), (float) (renderOrigin.getZ() - cameraZ));
				FrustumIntersection frustum = new FrustumIntersection(proj);

				var frameContext = new FrameContext(cameraX, cameraY, cameraZ, frustum, partialTick);

				normalPlan.execute(taskExecutor, frameContext, then);
			}
		}
	}
}

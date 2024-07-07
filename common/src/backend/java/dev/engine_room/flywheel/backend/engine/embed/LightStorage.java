package dev.engine_room.flywheel.backend.engine.embed;

import java.util.BitSet;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import dev.engine_room.flywheel.api.event.RenderContext;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.backend.engine.indirect.StagingBuffer;
import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import dev.engine_room.flywheel.lib.task.SimplePlan;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LightLayer;

/**
 * TODO: AO data
 * A managed arena of light sections for uploading to the GPU.
 *
 * <p>Each section represents an 18x18x18 block volume of light data.
 * The "edges" are taken from the neighboring sections, so that each
 * shader invocation only needs to access a single section of data.
 * Even still, neighboring shader invocations may need to access other sections.
 *
 * <p>Sections are logically stored as a 9x9x9 array of longs,
 * where each long holds a 2x2x2 array of light data.
 * <br>Both the greater array and the longs are packed in x, z, y order.
 *
 * <p>Thus, each section occupies 5832 bytes.
 */
public class LightStorage {
	public static final long SECTION_SIZE_BYTES = 9 * 9 * 9 * 8;
	private static final int DEFAULT_ARENA_CAPACITY_SECTIONS = 64;
	private static final int INVALID_SECTION = -1;

	private final LevelAccessor level;

	private final Arena arena;
	private final Long2IntMap section2ArenaIndex = new Long2IntOpenHashMap();
	{
		section2ArenaIndex.defaultReturnValue(INVALID_SECTION);
	}

	private final BitSet changed = new BitSet();
	private boolean needsLutRebuild = false;

	@Nullable
	private LongSet requestedSections;

	public LightStorage(LevelAccessor level) {
		this.level = level;

		arena = new Arena(SECTION_SIZE_BYTES, DEFAULT_ARENA_CAPACITY_SECTIONS);
	}

	public void sections(LongSet sections) {
		requestedSections = sections;
	}

	public Plan<RenderContext> createFramePlan() {
		return SimplePlan.of(() -> {
			if (requestedSections == null) {
				return;
			}

			removeUnusedSections(requestedSections);

			var knownSections = section2ArenaIndex.keySet();

			var updatedSections = LightUpdateHolder.get(level)
					.getUpdatedSections();

			// Only add the new sections.
			requestedSections.removeAll(knownSections);

			for (long updatedSection : updatedSections) {
				for (int x = -1; x <= 1; x++) {
					for (int y = -1; y <= 1; y++) {
						for (int z = -1; z <= 1; z++) {
							long section = SectionPos.offset(updatedSection, x, y, z);
							if (knownSections.contains(section)) {
								requestedSections.add(section);
							}
						}
					}
				}
			}

			for (long section : requestedSections) {
				addSection(section);
			}
		});
	}

	private void removeUnusedSections(LongSet allLightSections) {
		var entries = section2ArenaIndex.long2IntEntrySet();
		var it = entries.iterator();
		while (it.hasNext()) {
			var entry = it.next();
			var section = entry.getLongKey();

			if (!allLightSections.contains(section)) {
				arena.free(entry.getIntValue());
				needsLutRebuild = true;
				it.remove();
			}
		}
	}

	public int capacity() {
		return arena.capacity();
	}

	public void addSection(long section) {
		var lightEngine = level.getLightEngine();

		var blockLight = lightEngine.getLayerListener(LightLayer.BLOCK);
		var skyLight = lightEngine.getLayerListener(LightLayer.SKY);

		var blockPos = new BlockPos.MutableBlockPos();

		int xMin = SectionPos.sectionToBlockCoord(SectionPos.x(section));
		int yMin = SectionPos.sectionToBlockCoord(SectionPos.y(section));
		int zMin = SectionPos.sectionToBlockCoord(SectionPos.z(section));

		var sectionPos = SectionPos.of(section);
		var blockData = blockLight.getDataLayerData(sectionPos);
		var skyData = skyLight.getDataLayerData(sectionPos);

		int index = indexForSection(section);

		changed.set(index);

		long ptr = arena.indexToPointer(index);

		// Not sure of a way to iterate over the surface of a cube, so branch in the inner loop to take the fast path.
		// Adding the fast path is about 8x faster than having only the slow path.
		// There's still room for optimization, as the slow path takes about 3x the cpu time as the fast path despite
		// being called an order of magnitude less.
		for (int y = -1; y < 17; y++) {
			for (int z = -1; z < 17; z++) {
				for (int x = -1; x < 17; x++) {
					if (x == -1 || y == -1 || z == -1 || x == 16 || y == 16 || z == 16) {
						// Slow path, collect the surface of our section.
						blockPos.set(xMin + x, yMin + y, zMin + z);
						var block = blockLight.getLightValue(blockPos);
						var sky = skyLight.getLightValue(blockPos);

						write(ptr, x, y, z, block, sky);
					} else {
						// Fast path, read directly from the data layer for the main section.
						// Would be nice to move the null check elsewhere.
						var block = blockData == null ? 0 : blockData.get(x, y, z);
						var sky = skyData == null ? 0 : skyData.get(x, y, z);

						write(ptr, x, y, z, block, sky);
					}
				}
			}
		}
	}

	/**
	 * Write to the given section.
	 * @param ptr Pointer to the base of a section's data.
	 * @param x X coordinate in the section, from [-1, 16].
	 * @param y Y coordinate in the section, from [-1, 16].
	 * @param z Z coordinate in the section, from [-1, 16].
	 * @param block The block light level, from [0, 15].
	 * @param sky The sky light level, from [0, 15].
	 */
	private void write(long ptr, int x, int y, int z, int block, int sky) {
		int x1 = x + 1;
		int y1 = y + 1;
		int z1 = z + 1;

		int offset = x1 + z1 * 18 + y1 * 18 * 18;

		long packedByte = (block & 0xF) | ((sky & 0xF) << 4);

		MemoryUtil.memPutByte(ptr + offset, (byte) packedByte);
	}

	/**
	 * Get a pointer to the base of the given section.
	 * <p> If the section is not yet reserved, allocate a chunk in the arena.
	 * @param section The section to write to.
	 * @return A raw pointer to the base of the section.
	 */
	private long ptrForSection(long section) {
		return arena.indexToPointer(indexForSection(section));
	}

	private int indexForSection(long section) {
		int out = section2ArenaIndex.get(section);

		// Need to allocate.
		if (out == INVALID_SECTION) {
			out = arena.alloc();
			section2ArenaIndex.put(section, out);
			needsLutRebuild = true;
		}
		return out;
	}

	public void delete() {
		arena.delete();
	}

	public boolean checkNeedsLutRebuildAndClear() {
		var out = needsLutRebuild;
		needsLutRebuild = false;
		return out;
	}

	public void uploadChangedSections(StagingBuffer staging, int dstVbo) {
		for (int i = changed.nextSetBit(0); i >= 0; i = changed.nextSetBit(i + 1)) {
			staging.enqueueCopy(arena.indexToPointer(i), SECTION_SIZE_BYTES, dstVbo, i * SECTION_SIZE_BYTES);
		}
		changed.clear();
	}

	public void upload(GlBuffer buffer) {
		if (changed.isEmpty()) {
			return;
		}

		buffer.upload(arena.indexToPointer(0), arena.capacity() * SECTION_SIZE_BYTES);
		changed.clear();
	}

	public IntArrayList createLut() {
		// TODO: incremental lut updates
		return LightLut.buildLut(section2ArenaIndex);
	}
}

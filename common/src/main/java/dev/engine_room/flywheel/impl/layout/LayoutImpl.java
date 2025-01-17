package dev.engine_room.flywheel.impl.layout;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Unmodifiable;

import dev.engine_room.flywheel.api.layout.ElementType;
import dev.engine_room.flywheel.api.layout.Layout;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

final class LayoutImpl implements Layout {
	@Unmodifiable
	private final List<Element> elements;
	@Unmodifiable
	private final Map<String, Element> map;
	private final int byteSize;
	private final int byteAlignment;

	LayoutImpl(@Unmodifiable List<Element> elements, int byteSize, int byteAlignment) {
		this.elements = elements;

		Object2ObjectOpenHashMap<String, Element> map = new Object2ObjectOpenHashMap<>();
		for (Element element : this.elements) {
			map.put(element.name(), element);
		}
		map.trim();

		this.map = Collections.unmodifiableMap(map);
		this.byteSize = byteSize;
		this.byteAlignment = byteAlignment;
	}

	@Override
	@Unmodifiable
	public List<Element> elements() {
		return elements;
	}

	@Override
	@Unmodifiable
	public Map<String, Element> asMap() {
		return map;
	}

	@Override
	public int byteSize() {
		return byteSize;
	}

	@Override
	public int byteAlignment() {
		return byteAlignment;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + elements.hashCode();
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		LayoutImpl other = (LayoutImpl) obj;
		return elements.equals(other.elements);
	}

	record ElementImpl(String name, ElementType type, int byteOffset, int paddedByteSize, int paddingByteSize) implements Element {
	}
}

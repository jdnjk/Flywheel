package com.jozufozu.flywheel.core.structs;

import com.jozufozu.flywheel.api.struct.StructType;
import com.jozufozu.flywheel.core.ComponentRegistry;
import com.jozufozu.flywheel.core.structs.oriented.OrientedPart;
import com.jozufozu.flywheel.core.structs.oriented.OrientedType;
import com.jozufozu.flywheel.core.structs.transformed.TransformedPart;
import com.jozufozu.flywheel.core.structs.transformed.TransformedType;

public class StructTypes {
	public static final StructType<TransformedPart> TRANSFORMED = ComponentRegistry.register(new TransformedType());
	public static final StructType<OrientedPart> ORIENTED = ComponentRegistry.register(new OrientedType());

	public static void init() {
		// noop
	}
}
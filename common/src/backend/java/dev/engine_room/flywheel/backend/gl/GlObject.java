package dev.engine_room.flywheel.backend.gl;

// Utility class for safely dealing with gl object handles.
public abstract class GlObject {
	private static final int INVALID_HANDLE = Integer.MIN_VALUE;

	private int handle = INVALID_HANDLE;

	protected final void handle(int handle) {
		this.handle = handle;
	}

	public final int handle() {
		checkHandle();

		return this.handle;
	}

	protected final void checkHandle() {
		if (isInvalid()) {
			throw new IllegalStateException("handle is not valid.");
		}
	}

	protected final boolean isInvalid() {
		return this.handle == INVALID_HANDLE;
	}

	protected final void invalidateHandle() {
		this.handle = INVALID_HANDLE;
	}

	public void delete() {
		if (isInvalid()) {
			throw new IllegalStateException("handle already deleted.");
		}

		deleteInternal(handle);
		invalidateHandle();
	}

	protected abstract void deleteInternal(int handle);
}

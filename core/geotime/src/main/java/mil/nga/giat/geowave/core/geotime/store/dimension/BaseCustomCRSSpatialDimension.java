package mil.nga.giat.geowave.core.geotime.store.dimension;

class BaseCustomCRSSpatialDimension
{
	protected byte axis;

	protected BaseCustomCRSSpatialDimension() {}

	protected BaseCustomCRSSpatialDimension(
			byte axis ) {
		this.axis = axis;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result + axis;
		return result;
	}

	@Override
	public boolean equals(
			Object obj ) {
		if (this == obj) return true;
		if (!super.equals(obj)) return false;
		if (getClass() != obj.getClass()) return false;
		BaseCustomCRSSpatialDimension other = (BaseCustomCRSSpatialDimension) obj;
		if (axis != other.axis) return false;
		return true;
	}

	public byte[] addAxisToBinary(
			byte[] parentBinary ) {

		// TODO future issue to investigate performance improvements associated
		// with excessive array/object allocations
		// serialize axis
		byte[] retVal = new byte[parentBinary.length + 1];
		System.arraycopy(
				parentBinary,
				0,
				retVal,
				0,
				parentBinary.length);
		retVal[parentBinary.length] = axis;
		return retVal;
	}

	public byte[] getAxisFromBinaryAndRemove(
			byte[] bytes ) {
		// TODO future issue to investigate performance improvements associated
		// with excessive array/object allocations
		// deserialize axis
		byte[] parentBinary = new byte[bytes.length - 1];
		System.arraycopy(
				bytes,
				0,
				parentBinary,
				0,
				parentBinary.length);
		axis = bytes[parentBinary.length];
		return parentBinary;
	}

	public byte getAxis() {
		return axis;
	}

}

/*******************************************************************************
 * Copyright (c) 2013-2017 Contributors to the Eclipse Foundation
 * 
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License,
 * Version 2.0 which accompanies this distribution and is available at
 * http://www.apache.org/licenses/LICENSE-2.0.txt
 ******************************************************************************/
package mil.nga.giat.geowave.core.geotime.ingest;

import org.geotools.referencing.CRS;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.cs.CoordinateSystem;
import org.opengis.referencing.cs.CoordinateSystemAxis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mil.nga.giat.geowave.core.geotime.GeometryUtils;
import mil.nga.giat.geowave.core.geotime.index.dimension.LatitudeDefinition;
import mil.nga.giat.geowave.core.geotime.index.dimension.LongitudeDefinition;
import mil.nga.giat.geowave.core.geotime.index.dimension.TemporalBinningStrategy.Unit;
import mil.nga.giat.geowave.core.geotime.store.dimension.CustomCRSBoundedSpatialDimension;
import mil.nga.giat.geowave.core.geotime.store.dimension.CustomCRSSpatialField;
import mil.nga.giat.geowave.core.geotime.store.dimension.CustomCRSUnboundedSpatialDimension;
import mil.nga.giat.geowave.core.geotime.store.dimension.CustomCRSUnboundedSpatialDimensionX;
import mil.nga.giat.geowave.core.geotime.store.dimension.CustomCRSUnboundedSpatialDimensionY;
import mil.nga.giat.geowave.core.geotime.store.dimension.CustomCrsIndexModel;
import mil.nga.giat.geowave.core.geotime.store.dimension.GeometryWrapper;
import mil.nga.giat.geowave.core.geotime.store.dimension.LatitudeField;
import mil.nga.giat.geowave.core.geotime.store.dimension.LongitudeField;
import mil.nga.giat.geowave.core.geotime.store.dimension.TimeField;
import mil.nga.giat.geowave.core.index.ByteArrayId;
import mil.nga.giat.geowave.core.index.NumericIndexStrategy;
import mil.nga.giat.geowave.core.index.dimension.NumericDimensionDefinition;
import mil.nga.giat.geowave.core.index.sfc.SFCFactory.SFCType;
import mil.nga.giat.geowave.core.index.sfc.xz.XZHierarchicalIndexFactory;
import mil.nga.giat.geowave.core.store.cli.remote.options.IndexPluginOptions.BaseIndexBuilder;
import mil.nga.giat.geowave.core.store.dimension.NumericDimensionField;
import mil.nga.giat.geowave.core.store.index.BasicIndexModel;
import mil.nga.giat.geowave.core.store.index.CommonIndexValue;
import mil.nga.giat.geowave.core.store.index.CustomIdIndex;
import mil.nga.giat.geowave.core.store.index.PrimaryIndex;
import mil.nga.giat.geowave.core.store.spi.DimensionalityTypeProviderSpi;

public class SpatialDimensionalityTypeProvider implements
		DimensionalityTypeProviderSpi<SpatialOptions>
{
	private final static Logger LOGGER = LoggerFactory.getLogger(SpatialDimensionalityTypeProvider.class);
	private static final String DEFAULT_SPATIAL_ID = "SPATIAL_IDX";
	public static final int LONGITUDE_BITS = 31;
	public static final int LATITUDE_BITS = 31;
	private static final double INTERVAL = 500000;

	public static final NumericDimensionDefinition[] SPATIAL_DIMENSIONS = new NumericDimensionDefinition[] {
		new LongitudeDefinition(),
		new LatitudeDefinition(
				true)
	// just use the same range for latitude to make square sfc values in
	// decimal degrees (EPSG:4326)
	};
	public static final NumericDimensionField[] SPATIAL_FIELDS = new NumericDimensionField[] {
		new LongitudeField(),
		new LatitudeField(
				true)
	// just use the same range for latitude to make square sfc values in
	// decimal degrees (EPSG:4326)
	};
	public static final NumericDimensionField[] SPATIAL_TEMPORAL_FIELDS = new NumericDimensionField[] {
		new LongitudeField(),
		new LatitudeField(
				true),
		new TimeField(
				Unit.YEAR)
	};

	public SpatialDimensionalityTypeProvider() {}

	@Override
	public String getDimensionalityTypeName() {
		return "spatial";
	}

	@Override
	public String getDimensionalityTypeDescription() {
		return "This dimensionality type matches all indices that only require Geometry.";
	}

	@Override
	public int getPriority() {
		// arbitrary - just higher than spatial temporal so that the default
		// will be spatial over spatial-temporal
		return 10;
	}

	@Override
	public SpatialOptions createOptions() {
		return new SpatialOptions();
	}

	@Override
	public PrimaryIndex createPrimaryIndex(
			SpatialOptions options ) {
		return internalCreatePrimaryIndex(options);
	}

	private static PrimaryIndex internalCreatePrimaryIndex(
			final SpatialOptions options ) {
		NumericDimensionDefinition[] dimensions;
		boolean isDefaultCRS;
		String crsCode = null;
		NumericDimensionField<?>[] fields = null;
		NumericDimensionField<?>[] fields_temporal = null;

		if (options.crs == null || options.crs.isEmpty() || options.crs.equalsIgnoreCase(GeometryUtils.DEFAULT_CRS_STR)) {
			dimensions = SPATIAL_DIMENSIONS;
			fields = SPATIAL_FIELDS;
			isDefaultCRS = true;
			crsCode = "EPSG:4326";
		}
		else {
			decodeCRS(options.crs);
			CoordinateSystem cs = decodeCRS(
					options.crs).getCoordinateSystem();
			isDefaultCRS = false;
			crsCode = options.crs;
			dimensions = new NumericDimensionDefinition[cs.getDimension()];
			if (options.storeTime) {
				fields_temporal = new NumericDimensionField[dimensions.length + 1];
				for (int d = 0; d < dimensions.length; d++) {
					CoordinateSystemAxis csa = cs.getAxis(d);
					if (!isUnbounded(csa)) {
						dimensions[d] = new CustomCRSBoundedSpatialDimension(
								(byte) d,
								csa.getMinimumValue(),
								csa.getMaximumValue());
						fields_temporal[d] = new CustomCRSSpatialField(
								(CustomCRSBoundedSpatialDimension) dimensions[d]);
					}
					else {
						dimensions[d] = new CustomCRSUnboundedSpatialDimension(
								INTERVAL,
								(byte) d);
						fields_temporal[d] = new CustomCRSSpatialField(
								(CustomCRSUnboundedSpatialDimension) dimensions[d]);
					}
				}
				fields_temporal[dimensions.length] = new TimeField(
						Unit.YEAR);
			}
			else {
				fields = new NumericDimensionField[dimensions.length];
				for (int d = 0; d < dimensions.length; d++) {
					CoordinateSystemAxis csa = cs.getAxis(d);
					if (!isUnbounded(csa)) {
						dimensions[d] = new CustomCRSBoundedSpatialDimension(
								(byte) d,
								csa.getMinimumValue(),
								csa.getMaximumValue());
						fields[d] = new CustomCRSSpatialField(
								(CustomCRSBoundedSpatialDimension) dimensions[d]);
					}
					else {
						if (d == 0) {
							dimensions[d] = new CustomCRSUnboundedSpatialDimensionX(
									INTERVAL,
									(byte) d);
							fields[d] = new CustomCRSSpatialField(
									(CustomCRSUnboundedSpatialDimensionX) dimensions[d]);
						}
						if (d == 1) {
							dimensions[d] = new CustomCRSUnboundedSpatialDimensionY(
									INTERVAL,
									(byte) d);
							fields[d] = new CustomCRSSpatialField(
									(CustomCRSUnboundedSpatialDimensionY) dimensions[d]);
						}
					}
				}
			}

		}

		BasicIndexModel indexModel = null;
		if (isDefaultCRS) {
			indexModel = new BasicIndexModel(
					options.storeTime ? SPATIAL_TEMPORAL_FIELDS : SPATIAL_FIELDS);
		}
		else {

			indexModel = new CustomCrsIndexModel(
					options.storeTime ? fields_temporal : fields,
					crsCode);
		}

		return new CustomIdIndex(
				XZHierarchicalIndexFactory.createFullIncrementalTieredStrategy(
						dimensions,
						new int[] {
							// TODO this is only valid for 2D coordinate
							// systems, again consider the possibility of being
							// flexible enough to handle n-dimensions
							LONGITUDE_BITS,
							LATITUDE_BITS
						},
						SFCType.HILBERT),
				indexModel,
				new ByteArrayId(
						// TODO append CRS code to ID if its overridden
						isDefaultCRS ? (options.storeTime ? DEFAULT_SPATIAL_ID + "_TIME" : DEFAULT_SPATIAL_ID)
								: (options.storeTime ? DEFAULT_SPATIAL_ID + "_TIME" : DEFAULT_SPATIAL_ID) + "_"
										+ crsCode.substring(crsCode.indexOf(":") + 1)));
	}

	private static boolean isUnbounded(
			CoordinateSystemAxis csa ) {
		double min = csa.getMinimumValue();
		double max = csa.getMaximumValue();

		if (!Double.isFinite(max) || !Double.isFinite(min)) {
			return true;
		}
		return false;
	}

	public static CoordinateReferenceSystem decodeCRS(
			String crsCode ) {

		CoordinateReferenceSystem crs = null;
		try {
			crs = CRS.decode(
					crsCode,
					true);
		}
		catch (final FactoryException e) {
			LOGGER.error(
					"Unable to decode '" + crsCode + "' CRS",
					e);
			throw new RuntimeException(
					"Unable to initialize '" + crsCode + "' object",
					e);
		}

		return crs;

	}

	@Override
	public Class<? extends CommonIndexValue>[] getRequiredIndexTypes() {
		return new Class[] {
			GeometryWrapper.class
		};
	}

	public static class SpatialIndexBuilder extends
			BaseIndexBuilder<SpatialIndexBuilder>
	{
		private final SpatialOptions options;

		public SpatialIndexBuilder() {
			super();
			options = new SpatialOptions();
		}

		public SpatialIndexBuilder setIncludeTimeInCommonIndexModel(
				final boolean storeTime ) {
			options.storeTime = storeTime;
			return this;
		}

		public SpatialIndexBuilder setCrs(
				final String crs ) {
			options.crs = crs;
			return this;
		}

		@Override
		public PrimaryIndex createIndex() {
			return createIndex(internalCreatePrimaryIndex(options));
		}
	}

	public static boolean isSpatial(
			final PrimaryIndex index ) {
		if (index == null) {
			return false;
		}

		return isSpatial(index.getIndexStrategy());
	}

	public static boolean isSpatial(
			final NumericIndexStrategy indexStrategy ) {
		if ((indexStrategy == null) || (indexStrategy.getOrderedDimensionDefinitions() == null)) {
			return false;
		}
		final NumericDimensionDefinition[] dimensions = indexStrategy.getOrderedDimensionDefinitions();
		if (dimensions.length != 2) {
			return false;
		}
		boolean hasLat = false, hasLon = false;
		for (final NumericDimensionDefinition definition : dimensions) {
			if (definition instanceof LatitudeDefinition || definition instanceof CustomCRSUnboundedSpatialDimensionY) {
				hasLat = true;
			}
			else if (definition instanceof LongitudeDefinition
					|| definition instanceof CustomCRSUnboundedSpatialDimensionX) {
				hasLon = true;
			}
		}
		return hasLat && hasLon;
	}

}

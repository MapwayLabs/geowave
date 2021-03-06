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
package mil.nga.giat.geowave.core.geotime;

import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.NoSuchAuthorityCodeException;
import org.geotools.factory.GeoTools;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.io.ParseException;
import com.vividsolutions.jts.io.WKBReader;
import com.vividsolutions.jts.io.WKBWriter;

import mil.nga.giat.geowave.core.geotime.index.dimension.LatitudeDefinition;
import mil.nga.giat.geowave.core.geotime.index.dimension.LongitudeDefinition;
import mil.nga.giat.geowave.core.geotime.store.dimension.CustomCRSBoundedSpatialDimension;
import mil.nga.giat.geowave.core.geotime.store.dimension.CustomCRSUnboundedSpatialDimensionX;
import mil.nga.giat.geowave.core.geotime.store.dimension.CustomCRSUnboundedSpatialDimensionY;
import mil.nga.giat.geowave.core.geotime.store.dimension.CustomCrsIndexModel;
import mil.nga.giat.geowave.core.index.dimension.NumericDimensionDefinition;
import mil.nga.giat.geowave.core.index.sfc.data.BasicNumericDataset;
import mil.nga.giat.geowave.core.index.sfc.data.MultiDimensionalNumericData;
import mil.nga.giat.geowave.core.index.sfc.data.NumericData;
import mil.nga.giat.geowave.core.index.sfc.data.NumericRange;
import mil.nga.giat.geowave.core.index.sfc.data.NumericValue;
import mil.nga.giat.geowave.core.store.index.PrimaryIndex;
import mil.nga.giat.geowave.core.store.query.BasicQuery.ConstraintData;
import mil.nga.giat.geowave.core.store.query.BasicQuery.ConstraintSet;
import mil.nga.giat.geowave.core.store.query.BasicQuery.Constraints;
import mil.nga.giat.geowave.core.store.util.ClasspathUtils;

/**
 * This class contains a set of Geometry utility methods that are generally
 * useful throughout the GeoWave core codebase
 */
public class GeometryUtils
{
	public static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
	private final static Logger LOGGER = LoggerFactory.getLogger(GeometryUtils.class);
	private static final Object MUTEX = new Object();
	private static final Object MUTEX_DEFAULT_CRS = new Object();
	private static final int DEFAULT_DIMENSIONALITY = 2;
	public static final String DEFAULT_CRS_STR = "EPSG:4326";
	private static CoordinateReferenceSystem defaultCrsSingleton;
	private static Set<ClassLoader> initializedClassLoaders = new HashSet<>();

	@edu.umd.cs.findbugs.annotations.SuppressFBWarnings()
	public static CoordinateReferenceSystem getDefaultCRS() {
		if (defaultCrsSingleton == null) { // avoid sync penalty if we can
			synchronized (MUTEX_DEFAULT_CRS) {
				// have to do this inside the sync to avoid double init
				if (defaultCrsSingleton == null) {
					try {
						initClassLoader();
						defaultCrsSingleton = CRS.decode(
								DEFAULT_CRS_STR,
								true);
					}
					catch (final Exception e) {
						LOGGER.error(
								"Unable to decode " + DEFAULT_CRS_STR + " CRS",
								e);
						defaultCrsSingleton = DefaultGeographicCRS.WGS84;
					}
				}
			}
		}
		return defaultCrsSingleton;
	}

	public static void initClassLoader()
			throws MalformedURLException {
		synchronized (MUTEX) {
			final ClassLoader myCl = GeometryUtils.class.getClassLoader();
			if (initializedClassLoaders.contains(myCl)) {
				return;
			}
			final ClassLoader classLoader = ClasspathUtils.transformClassLoader(myCl);
			if (classLoader != null) {
				GeoTools.addClassLoader(classLoader);
			}
			initializedClassLoaders.add(myCl);
		}
	}

	public static Constraints basicConstraintsFromGeometry(
			final Geometry geometry ) {

		final List<ConstraintSet> set = new LinkedList<>();
		constructListOfConstraintSetsFromGeometry(
				geometry,
				set,
				false);

		return new Constraints(
				set);
	}

	/**
	 * This utility method will convert a JTS geometry to contraints that can be
	 * used in a GeoWave query.
	 *
	 * @return Constraints as a mapping of NumericData objects representing
	 *         ranges for a latitude dimension and a longitude dimension
	 */
	public static GeoConstraintsWrapper basicGeoConstraintsWrapperFromGeometry(
			final Geometry geometry ) {

		final List<ConstraintSet> set = new LinkedList<>();
		final boolean geometryConstraintsExactMatch = constructListOfConstraintSetsFromGeometry(
				geometry,
				set,
				true);

		return new GeoConstraintsWrapper(
				new Constraints(
						set),
				geometryConstraintsExactMatch,
				geometry);
	}

	/**
	 * Recursively decompose geometry into a set of envelopes to create a single
	 * set.
	 *
	 * @param geometry
	 * @param destinationListOfSets
	 * @param checkTopoEquality
	 */
	private static boolean constructListOfConstraintSetsFromGeometry(
			final Geometry geometry,
			final List<ConstraintSet> destinationListOfSets,
			final boolean checkTopoEquality ) {

		// Get the envelope of the geometry being held
		final int n = geometry.getNumGeometries();
		boolean retVal = true;
		if (n > 1) {
			retVal = false;
			for (int gi = 0; gi < n; gi++) {
				constructListOfConstraintSetsFromGeometry(
						geometry.getGeometryN(gi),
						destinationListOfSets,
						checkTopoEquality);
			}
		}
		else {
			final Envelope env = geometry.getEnvelopeInternal();
			destinationListOfSets.add(basicConstraintSetFromEnvelope(env));
			if (checkTopoEquality) {
				retVal = new GeometryFactory().toGeometry(
						env).equalsTopo(
						geometry);
			}
		}
		return retVal;
	}

	/**
	 * This utility method will convert a JTS envelope to contraints that can be
	 * used in a GeoWave query.
	 *
	 * @return Constraints as a mapping of NumericData objects representing
	 *         ranges for a latitude dimension and a longitude dimension
	 */
	public static ConstraintSet basicConstraintSetFromEnvelope(
			final Envelope env ) {
		// Create a NumericRange object using the x axis
		final NumericRange rangeLongitude = new NumericRange(
				env.getMinX(),
				env.getMaxX());

		// Create a NumericRange object using the y axis
		final NumericRange rangeLatitude = new NumericRange(
				env.getMinY(),
				env.getMaxY());

		final Map<Class<? extends NumericDimensionDefinition>, ConstraintData> constraintsPerDimension = new HashMap<>();
		// Create and return a new IndexRange array with an x and y axis
		// range

		ConstraintData xRange = new ConstraintData(
				rangeLongitude,
				false);
		ConstraintData yRange = new ConstraintData(
				rangeLatitude,
				false);
		constraintsPerDimension.put(
				CustomCRSUnboundedSpatialDimensionX.class,
				xRange);
		constraintsPerDimension.put(
				CustomCRSUnboundedSpatialDimensionY.class,
				yRange);
		constraintsPerDimension.put(
				LongitudeDefinition.class,
				xRange);
		constraintsPerDimension.put(
				LatitudeDefinition.class,
				yRange);

		return new ConstraintSet(
				constraintsPerDimension);
	}

	/**
	 * This utility method will convert a JTS envelope to contraints that can be
	 * used in a GeoWave query.
	 *
	 * @return Constraints as a mapping of NumericData objects representing
	 *         ranges for a latitude dimension and a longitude dimension
	 */
	public static Constraints basicConstraintsFromEnvelope(
			final Envelope env ) {

		return new Constraints(
				basicConstraintSetFromEnvelope(env));
	}

	/**
	 * This utility method will convert a JTS envelope to that can be used in a
	 * GeoWave query.
	 *
	 * @return Constraints as a mapping of NumericData objects representing
	 *         ranges for a latitude dimension and a longitude dimension
	 */
	public static ConstraintSet basicConstraintsFromPoint(
			final double latitudeDegrees,
			final double longitudeDegrees ) {
		// Create a NumericData object using the x axis
		final NumericData latitude = new NumericValue(
				latitudeDegrees);

		// Create a NumericData object using the y axis
		final NumericData longitude = new NumericValue(
				longitudeDegrees);

		final Map<Class<? extends NumericDimensionDefinition>, ConstraintData> constraintsPerDimension = new HashMap<>();
		// Create and return a new IndexRange array with an x and y axis
		// range
		constraintsPerDimension.put(
				LongitudeDefinition.class,
				new ConstraintData(
						longitude,
						false));
		constraintsPerDimension.put(
				LatitudeDefinition.class,
				new ConstraintData(
						latitude,
						false));
		return new ConstraintSet(
				constraintsPerDimension);
	}

	public static MultiDimensionalNumericData getBoundsFromEnvelope(
			final Envelope envelope ) {
		final NumericRange[] boundsPerDimension = new NumericRange[2];
		boundsPerDimension[0] = new NumericRange(
				envelope.getMinX(),
				envelope.getMaxX());
		boundsPerDimension[1] = new NumericRange(
				envelope.getMinY(),
				envelope.getMaxY());
		return new BasicNumericDataset(
				boundsPerDimension);
	}

	/**
	 * Generate a longitude range from a JTS geometry
	 *
	 * @param geometry
	 *            The JTS geometry
	 * @return The x range
	 */
	public static NumericData xRangeFromGeometry(
			final Geometry geometry ) {
		if ((geometry == null) || geometry.isEmpty()) {
			return new NumericRange(
					0,
					0);
		}
		// Get the envelope of the geometry being held
		final Envelope env = geometry.getEnvelopeInternal();

		// Create a NumericRange object using the x axis
		return new NumericRange(
				env.getMinX(),
				env.getMaxX());
	}

	/**
	 * Generate a latitude range from a JTS geometry
	 *
	 * @param geometry
	 *            The JTS geometry
	 * @return The y range
	 */
	public static NumericData yRangeFromGeometry(
			final Geometry geometry ) {
		if ((geometry == null) || geometry.isEmpty()) {
			return new NumericRange(
					0,
					0);
		}
		// Get the envelope of the geometry being held
		final Envelope env = geometry.getEnvelopeInternal();

		// Create a NumericRange object using the y axis
		return new NumericRange(
				env.getMinY(),
				env.getMaxY());
	}

	/**
	 * Converts a JTS geometry to binary using JTS a Well Known Binary writer
	 *
	 * @param geometry
	 *            The JTS geometry
	 * @return The binary representation of the geometry
	 */
	public static byte[] geometryToBinary(
			final Geometry geometry ) {

		int dimensions = DEFAULT_DIMENSIONALITY;

		if (!geometry.isEmpty()) {
			dimensions = Double.isNaN(geometry.getCoordinate().getOrdinate(
					Coordinate.Z)) ? 2 : 3;
		}

		return new WKBWriter(
				dimensions).write(geometry);
	}

	/**
	 * Converts a byte array as well-known binary to a JTS geometry
	 *
	 * @param binary
	 *            The well known binary
	 * @return The JTS geometry
	 */
	public static Geometry geometryFromBinary(
			final byte[] binary ) {
		try {
			return new WKBReader().read(binary);
		}
		catch (final ParseException e) {
			LOGGER.warn(
					"Unable to deserialize geometry data",
					e);
		}
		return null;
	}

	/**
	 * This mehtod returns an envelope between negative infinite and positive
	 * inifinity in both x and y
	 *
	 * @return the infinite bounding box
	 */
	public static Geometry infinity() {
		// unless we make this synchronized, we will want to instantiate a new
		// geometry factory because geometry factories are not thread safe
		return new GeometryFactory().toGeometry(new Envelope(
				Double.NEGATIVE_INFINITY,
				Double.POSITIVE_INFINITY,
				Double.NEGATIVE_INFINITY,
				Double.POSITIVE_INFINITY));
	}

	public static class GeoConstraintsWrapper
	{
		private final Constraints constraints;
		private final boolean constraintsMatchGeometry;
		private final Geometry jtsBounds;

		public GeoConstraintsWrapper(
				final Constraints constraints,
				final boolean constraintsMatchGeometry,
				final Geometry jtsBounds ) {
			this.constraints = constraints;
			this.constraintsMatchGeometry = constraintsMatchGeometry;
			this.jtsBounds = jtsBounds;
		}

		public Constraints getConstraints() {
			return constraints;
		}

		public boolean isConstraintsMatchGeometry() {
			return constraintsMatchGeometry;
		}

		public Geometry getGeometry() {
			return jtsBounds;
		}
	}

	public static CoordinateReferenceSystem getIndexCrs(
			final PrimaryIndex[] indices ) {

		CoordinateReferenceSystem indexCrs = null;

		for (final PrimaryIndex primaryindx : indices) {

			// for first iteration
			if (indexCrs == null) {
				indexCrs = getIndexCrs(primaryindx);
			}
			else {
				if (primaryindx.getIndexModel() instanceof CustomCrsIndexModel) {
					// check if indexes have different CRS
					if (!indexCrs.equals(((CustomCrsIndexModel) primaryindx.getIndexModel()).getCrs())) {
						LOGGER.error("Multiple indices with different CRS is not supported");
						throw new RuntimeException(
								"Multiple indices with different CRS is not supported");
					}
					else {
						if (!indexCrs.equals(getDefaultCRS())) {
							LOGGER.error("Multiple indices with different CRS is not supported");
							throw new RuntimeException(
									"Multiple indices with different CRS is not supported");
						}

					}
				}
			}
		}

		return indexCrs;
	}

	public static CoordinateReferenceSystem getIndexCrs(
			final PrimaryIndex index ) {

		CoordinateReferenceSystem indexCrs = null;

		if (index.getIndexModel() instanceof CustomCrsIndexModel) {
			indexCrs = ((CustomCrsIndexModel) index.getIndexModel()).getCrs();
		}
		else {
			indexCrs = getDefaultCRS();
		}
		return indexCrs;
	}

	public static String getCrsCode(
			final CoordinateReferenceSystem crs ) {

		return (CRS.toSRS(crs));
	}
}

package mil.nga.giat.geowave.datastore.dynamodb.operations;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;

import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.QueryRequest;
import com.amazonaws.services.dynamodbv2.model.QueryResult;

import mil.nga.giat.geowave.core.store.metadata.AbstractGeoWavePersistence;
import mil.nga.giat.geowave.core.store.operations.MetadataDeleter;
import mil.nga.giat.geowave.core.store.operations.MetadataQuery;
import mil.nga.giat.geowave.core.store.operations.MetadataType;

public class DynamoDBMetadataDeleter implements
		MetadataDeleter
{
	private final static Logger LOGGER = Logger.getLogger(DynamoDBMetadataDeleter.class);

	private final DynamoDBOperations operations;
	private final MetadataType metadataType;

	public DynamoDBMetadataDeleter(
			final DynamoDBOperations operations,
			final MetadataType metadataType ) {
		super();
		this.operations = operations;
		this.metadataType = metadataType;
	}

	@Override
	public void close()
			throws Exception {}

	@Override
	public boolean delete(
			final MetadataQuery metadata ) {
		// the nature of metadata deleter is that primary ID is always
		// well-defined and it is deleting a single entry at a time
		final String tableName = operations.getMetadataTableName(metadataType);
		final QueryRequest queryRequest = new QueryRequest(
				tableName);

		if (metadata.hasSecondaryId()) {
			queryRequest.withFilterExpression(
					DynamoDBOperations.METADATA_SECONDARY_ID_KEY + " = :secVal").addExpressionAttributeValuesEntry(
					":secVal",
					new AttributeValue().withB(ByteBuffer.wrap(metadata.getSecondaryId())));
		}
		queryRequest.withKeyConditionExpression(
				DynamoDBOperations.METADATA_PRIMARY_ID_KEY + " = :priVal").addExpressionAttributeValuesEntry(
				":priVal",
				new AttributeValue().withB(ByteBuffer.wrap(metadata.getPrimaryId())));

		final QueryResult queryResult = operations.getClient().query(
				queryRequest);
		for (Map<String, AttributeValue> entry : queryResult.getItems()) {
			Map<String, AttributeValue> key = new HashMap<>();
			key.put(
					DynamoDBOperations.METADATA_PRIMARY_ID_KEY,
					entry.get(DynamoDBOperations.METADATA_PRIMARY_ID_KEY));
			key.put(
					DynamoDBOperations.METADATA_TIMESTAMP_KEY,
					entry.get(DynamoDBOperations.METADATA_TIMESTAMP_KEY));
			operations.getClient().deleteItem(
					tableName,
					key);
		}

		return true;
	}

	@Override
	public void flush() {}

}

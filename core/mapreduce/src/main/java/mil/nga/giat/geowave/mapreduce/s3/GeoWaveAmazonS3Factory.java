package mil.nga.giat.geowave.mapreduce.s3;

import java.util.Properties;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.upplication.s3fs.AmazonS3ClientFactory;

public class GeoWaveAmazonS3Factory extends
		AmazonS3ClientFactory
{

	@Override
	protected AWSCredentialsProvider getCredentialsProvider(
			Properties props ) {
		AWSCredentialsProvider credentialsProvider = super.getCredentialsProvider(props);
		if (credentialsProvider instanceof DefaultAWSCredentialsProviderChain) {
			return new DefaultGeoWaveAWSCredentialsProvider();
		}
		return credentialsProvider;
	}

}

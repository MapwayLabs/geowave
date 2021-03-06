package mil.nga.giat.geowave.ingest.s3;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.Assert;
import org.junit.Test;

import com.upplication.s3fs.S3FileSystem;

import io.findify.s3mock.S3Mock;
import mil.nga.giat.geowave.core.ingest.IngestUtils;
import mil.nga.giat.geowave.core.ingest.IngestUtils.URLTYPE;
import mil.nga.giat.geowave.core.ingest.spark.SparkIngestDriver;

public class DefaultGeoWaveAWSCredentialsProviderTest
{

	@Test
	public void testAnonymousAccess()
			throws NoSuchFieldException,
			SecurityException,
			IllegalArgumentException,
			IllegalAccessException,
			URISyntaxException,
			IOException {
		File temp = File.createTempFile(
				"temp",
				Long.toString(System.nanoTime()));
		temp.mkdirs();
		S3Mock mockS3 = new S3Mock.Builder().withPort(
				8001).withFileBackend(
				temp.getAbsolutePath()).withInMemoryBackend().build();
		mockS3.start();
		IngestUtils.setURLStreamHandlerFactory(URLTYPE.S3);
		SparkIngestDriver sparkDriver = new SparkIngestDriver();
		S3FileSystem s3 = sparkDriver.initializeS3FS("s3://s3.amazonaws.com");
		s3.getClient().setEndpoint(
				"http://127.0.0.1:8001");
		s3.getClient().createBucket(
				"testbucket");
		s3.getClient().putObject(
				"testbucket",
				"test",
				"content");
		try (Stream<Path> s = Files.list(IngestUtils.setupS3FileSystem(
				"s3://testbucket/",
				"s3://s3.amazonaws.com"))) {
			Assert.assertEquals(
					1,
					s.count());
		}
		mockS3.shutdown();
	}
}
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.test.external_dataset.aws;

import static org.apache.hyracks.util.file.FileUtil.joinPath;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import org.apache.asterix.common.api.INcApplicationContext;
import org.apache.asterix.test.common.TestExecutor;
import org.apache.asterix.test.runtime.ExecutionTestUtil;
import org.apache.asterix.test.runtime.LangExecutionUtil;
import org.apache.asterix.testframework.context.TestCaseContext;
import org.apache.asterix.testframework.context.TestFileContext;
import org.apache.asterix.testframework.xml.TestCase;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.hyracks.control.nc.NodeControllerService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import io.findify.s3mock.S3Mock;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Runs an AWS S3 mock server and test it as an external dataset
 */
@RunWith(Parameterized.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class AwsS3ExternalDatasetTest {

    private static final Logger LOGGER = LogManager.getLogger();

    // subclasses of this class MUST instantiate these variables before using them to avoid unexpected behavior
    static String SUITE_TESTS;
    static String ONLY_TESTS;
    static String TEST_CONFIG_FILE_NAME;
    static Runnable PREPARE_S3_BUCKET;
    static Runnable PREPARE_FIXED_DATA_BUCKET;

    // Base directory paths for data files
    private static final String JSON_DATA_PATH = joinPath("data", "json");
    private static final String CSV_DATA_PATH = joinPath("data", "csv");
    private static final String TSV_DATA_PATH = joinPath("data", "tsv");

    // Service endpoint
    private static final int S3_MOCK_SERVER_PORT = 8001;
    private static final String S3_MOCK_SERVER_HOSTNAME = "http://localhost:" + S3_MOCK_SERVER_PORT;

    // Region, bucket and definitions
    private static final String S3_MOCK_SERVER_REGION = "us-west-2";
    private static final String S3_MOCK_SERVER_BUCKET = "playground";
    private static final String S3_MOCK_SERVER_FIXED_DATA_BUCKET = "fixed-data-bucket"; // Do not use, has fixed data
    private static final String S3_MOCK_SERVER_BUCKET_JSON_DEFINITION = "json-data/reviews/"; // data resides here
    private static final String S3_MOCK_SERVER_BUCKET_CSV_DEFINITION = "csv-data/reviews/"; // data resides here
    private static final String S3_MOCK_SERVER_BUCKET_TSV_DEFINITION = "tsv-data/reviews/"; // data resides here

    private static final Set<String> fileNames = new HashSet<>();
    private static final CreateBucketRequest.Builder CREATE_BUCKET_BUILDER = CreateBucketRequest.builder();
    private static final DeleteBucketRequest.Builder DELETE_BUCKET_BUILDER = DeleteBucketRequest.builder();
    private static final PutObjectRequest.Builder PUT_OBJECT_BUILDER = PutObjectRequest.builder();

    // IMPORTANT: The following values must be used in the AWS S3 test case
    private static S3Mock s3MockServer;
    private static S3Client client;
    private static PutObjectRequest.Builder builder = PutObjectRequest.builder().bucket(S3_MOCK_SERVER_BUCKET);

    protected TestCaseContext tcCtx;

    public AwsS3ExternalDatasetTest(TestCaseContext tcCtx) {
        this.tcCtx = tcCtx;
    }

    @BeforeClass
    public static void setUp() throws Exception {
        final TestExecutor testExecutor = new AwsTestExecutor();
        LangExecutionUtil.setUp(TEST_CONFIG_FILE_NAME, testExecutor);
        setNcEndpoints(testExecutor);
        startAwsS3MockServer();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        LangExecutionUtil.tearDown();

        // Shutting down S3 mock server
        LOGGER.info("Shutting down S3 mock server and client");
        if (client != null) {
            client.close();
        }
        if (s3MockServer != null) {
            s3MockServer.shutdown();
        }
        LOGGER.info("S3 mock down and client shut down successfully");
    }

    @Parameters(name = "AwsS3ExternalDatasetTest {index}: {0}")
    public static Collection<Object[]> tests() throws Exception {
        SUITE_TESTS = "testsuite_external_dataset.xml";
        ONLY_TESTS = "only_external_dataset.xml";
        TEST_CONFIG_FILE_NAME = "src/main/resources/cc.conf";
        PREPARE_S3_BUCKET = AwsS3ExternalDatasetTest::prepareS3Bucket;
        PREPARE_FIXED_DATA_BUCKET = AwsS3ExternalDatasetTest::prepareFixedDataBucket;
        return LangExecutionUtil.tests(ONLY_TESTS, SUITE_TESTS);
    }

    @Test
    public void test() throws Exception {
        LangExecutionUtil.test(tcCtx);
    }

    private static void setNcEndpoints(TestExecutor testExecutor) {
        final NodeControllerService[] ncs = ExecutionTestUtil.integrationUtil.ncs;
        final Map<String, InetSocketAddress> ncEndPoints = new HashMap<>();
        final String ip = InetAddress.getLoopbackAddress().getHostAddress();
        for (NodeControllerService nc : ncs) {
            final String nodeId = nc.getId();
            final INcApplicationContext appCtx = (INcApplicationContext) nc.getApplicationContext();
            int apiPort = appCtx.getExternalProperties().getNcApiPort();
            ncEndPoints.put(nodeId, InetSocketAddress.createUnresolved(ip, apiPort));
        }
        testExecutor.setNcEndPoints(ncEndPoints);
    }

    /**
     * Starts the AWS s3 mocking server and loads some files for testing
     */
    private static void startAwsS3MockServer() {
        // Starting S3 mock server to be used instead of real S3 server
        LOGGER.info("Starting S3 mock server");
        s3MockServer = new S3Mock.Builder().withPort(S3_MOCK_SERVER_PORT).withInMemoryBackend().build();
        s3MockServer.start();
        LOGGER.info("S3 mock server started successfully");

        // Create a client and add some files to the S3 mock server
        LOGGER.info("Creating S3 client to load initial files to S3 mock server");
        S3ClientBuilder builder = S3Client.builder();
        URI endpoint = URI.create(S3_MOCK_SERVER_HOSTNAME); // endpoint pointing to S3 mock server
        builder.region(Region.of(S3_MOCK_SERVER_REGION)).credentialsProvider(AnonymousCredentialsProvider.create())
                .endpointOverride(endpoint);
        client = builder.build();
        LOGGER.info("Client created successfully");

        // Create the bucket and upload some json files
        PREPARE_S3_BUCKET.run();
        PREPARE_FIXED_DATA_BUCKET.run();
    }

    /**
     * Creates a bucket and fills it with some files for testing purpose.
     */
    private static void prepareS3Bucket() {
        LOGGER.info("creating bucket " + S3_MOCK_SERVER_BUCKET);
        client.createBucket(CreateBucketRequest.builder().bucket(S3_MOCK_SERVER_BUCKET).build());
        LOGGER.info("bucket created successfully");

        LOGGER.info("Adding JSON files to the bucket");
        loadJsonFiles();
        LOGGER.info("JSON Files added successfully");

        LOGGER.info("Adding CSV files to the bucket");
        loadCsvFiles();
        LOGGER.info("CSV Files added successfully");

        LOGGER.info("Adding TSV files to the bucket");
        loadTsvFiles();
        LOGGER.info("TSV Files added successfully");
    }

    /**
     * This bucket is being filled by fixed data, a test is counting all records in this bucket. If this bucket is
     * changed, the test case will fail and its result will need to be updated each time
     */
    private static void prepareFixedDataBucket() {
        LOGGER.info("creating bucket " + S3_MOCK_SERVER_FIXED_DATA_BUCKET);
        client.createBucket(CreateBucketRequest.builder().bucket(S3_MOCK_SERVER_FIXED_DATA_BUCKET).build());
        LOGGER.info("bucket " + S3_MOCK_SERVER_FIXED_DATA_BUCKET + " created successfully");

        LOGGER.info("Loading fixed data to " + S3_MOCK_SERVER_FIXED_DATA_BUCKET);

        // Files data
        RequestBody requestBody = RequestBody.fromFile(Paths.get(JSON_DATA_PATH, "single-line", "20-records.json"));
        client.putObject(builder.bucket(S3_MOCK_SERVER_FIXED_DATA_BUCKET).key("1.json").build(), requestBody);
        client.putObject(builder.bucket(S3_MOCK_SERVER_FIXED_DATA_BUCKET).key("2.json").build(), requestBody);
        client.putObject(builder.bucket(S3_MOCK_SERVER_FIXED_DATA_BUCKET).key("lvl1/3.json").build(), requestBody);
        client.putObject(builder.bucket(S3_MOCK_SERVER_FIXED_DATA_BUCKET).key("lvl1/4.json").build(), requestBody);
        client.putObject(builder.bucket(S3_MOCK_SERVER_FIXED_DATA_BUCKET).key("lvl1/lvl2/5.json").build(), requestBody);
    }

    private static void loadJsonFiles() {
        String dataBasePath = JSON_DATA_PATH;
        String definition = S3_MOCK_SERVER_BUCKET_JSON_DEFINITION;

        // Normal format
        String definitionSegment = "json";
        loadData(dataBasePath, "single-line", "20-records.json", definition, definitionSegment, false);
        loadData(dataBasePath, "multi-lines", "20-records.json", definition, definitionSegment, false);
        loadData(dataBasePath, "multi-lines-with-arrays", "5-records.json", definition, definitionSegment, false);
        loadData(dataBasePath, "multi-lines-with-nested-objects", "5-records.json", definition, definitionSegment,
                false);

        // gz compressed format
        definitionSegment = "gz";
        loadGzData(dataBasePath, "single-line", "20-records.json", definition, definitionSegment, false);
        loadGzData(dataBasePath, "multi-lines", "20-records.json", definition, definitionSegment, false);
        loadGzData(dataBasePath, "multi-lines-with-arrays", "5-records.json", definition, definitionSegment, false);
        loadGzData(dataBasePath, "multi-lines-with-nested-objects", "5-records.json", definition, definitionSegment,
                false);

        // Mixed normal and gz compressed format
        definitionSegment = "mixed";
        loadData(dataBasePath, "single-line", "20-records.json", definition, definitionSegment, false);
        loadData(dataBasePath, "multi-lines", "20-records.json", definition, definitionSegment, false);
        loadData(dataBasePath, "multi-lines-with-arrays", "5-records.json", definition, definitionSegment, false);
        loadData(dataBasePath, "multi-lines-with-nested-objects", "5-records.json", definition, definitionSegment,
                false);
        loadGzData(dataBasePath, "single-line", "20-records.json", definition, definitionSegment, false);
        loadGzData(dataBasePath, "multi-lines", "20-records.json", definition, definitionSegment, false);
        loadGzData(dataBasePath, "multi-lines-with-arrays", "5-records.json", definition, definitionSegment, false);
        loadGzData(dataBasePath, "multi-lines-with-nested-objects", "5-records.json", definition, definitionSegment,
                false);
    }

    private static void loadCsvFiles() {
        String dataBasePath = CSV_DATA_PATH;
        String definition = S3_MOCK_SERVER_BUCKET_CSV_DEFINITION;

        // Normal format
        String definitionSegment = "csv";
        loadData(dataBasePath, "", "01.csv", definition, definitionSegment, false);
        loadData(dataBasePath, "", "02.csv", definition, definitionSegment, false);

        // gz compressed format
        definitionSegment = "gz";
        loadGzData(dataBasePath, "", "01.csv", definition, definitionSegment, false);
        loadGzData(dataBasePath, "", "02.csv", definition, definitionSegment, false);

        // Mixed normal and gz compressed format
        definitionSegment = "mixed";
        loadData(dataBasePath, "", "01.csv", definition, definitionSegment, false);
        loadData(dataBasePath, "", "02.csv", definition, definitionSegment, false);
        loadGzData(dataBasePath, "", "01.csv", definition, definitionSegment, false);
        loadGzData(dataBasePath, "", "02.csv", definition, definitionSegment, false);
    }

    private static void loadTsvFiles() {
        String dataBasePath = TSV_DATA_PATH;
        String definition = S3_MOCK_SERVER_BUCKET_TSV_DEFINITION;

        // Normal format
        String definitionSegment = "tsv";
        loadData(dataBasePath, "", "01.tsv", definition, definitionSegment, false);
        loadData(dataBasePath, "", "02.tsv", definition, definitionSegment, false);

        // gz compressed format
        definitionSegment = "gz";
        loadGzData(dataBasePath, "", "01.tsv", definition, definitionSegment, false);
        loadGzData(dataBasePath, "", "02.tsv", definition, definitionSegment, false);

        // Mixed normal and gz compressed format
        definitionSegment = "mixed";
        loadData(dataBasePath, "", "01.tsv", definition, definitionSegment, false);
        loadData(dataBasePath, "", "02.tsv", definition, definitionSegment, false);
        loadGzData(dataBasePath, "", "01.tsv", definition, definitionSegment, false);
        loadGzData(dataBasePath, "", "02.tsv", definition, definitionSegment, false);
    }

    private static void loadData(String fileBasePath, String filePathSegment, String filename, String definition,
            String definitionSegment, boolean removeExtension) {
        // Files data
        Path filePath = Paths.get(fileBasePath, filePathSegment, filename);
        RequestBody requestBody = RequestBody.fromFile(filePath);

        // Keep or remove the file extension
        Assert.assertFalse("Files with no extension are not supported yet for external datasets", removeExtension);
        String finalFileName;
        if (removeExtension) {
            finalFileName = FilenameUtils.removeExtension(filename);
        } else {
            finalFileName = filename;
        }

        // Files base definition
        filePathSegment = filePathSegment.isEmpty() ? "" : filePathSegment + "/";
        definitionSegment = definitionSegment.isEmpty() ? "" : definitionSegment + "/";
        String basePath = definition + filePathSegment + definitionSegment;

        // Load the data
        client.putObject(builder.key(basePath + finalFileName).build(), requestBody);
        client.putObject(builder.key(basePath + "level1a/" + finalFileName).build(), requestBody);
        client.putObject(builder.key(basePath + "level1b/" + finalFileName).build(), requestBody);
        client.putObject(builder.key(basePath + "level1a/level2a/" + finalFileName).build(), requestBody);
        client.putObject(builder.key(basePath + "level1a/level2b/" + finalFileName).build(), requestBody);
    }

    private static void loadGzData(String fileBasePath, String filePathSegment, String filename, String definition,
            String definitionSegment, boolean removeExtension) {
        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                GZIPOutputStream gzipOutputStream = new GZIPOutputStream(byteArrayOutputStream)) {

            // Files data
            Path filePath = Paths.get(fileBasePath, filePathSegment, filename);

            // Get the compressed data
            gzipOutputStream.write(Files.readAllBytes(filePath));
            gzipOutputStream.close(); // Need to close or data will be invalid
            byte[] gzipBytes = byteArrayOutputStream.toByteArray();
            RequestBody requestBody = RequestBody.fromBytes(gzipBytes);

            // Keep or remove the file extension
            Assert.assertFalse("Files with no extension are not supported yet for external datasets", removeExtension);
            String finalFileName;
            if (removeExtension) {
                finalFileName = FilenameUtils.removeExtension(filename);
            } else {
                finalFileName = filename;
            }
            finalFileName += ".gz";

            // Files base definition
            filePathSegment = filePathSegment.isEmpty() ? "" : filePathSegment + "/";
            definitionSegment = definitionSegment.isEmpty() ? "" : definitionSegment + "/";
            String basePath = definition + filePathSegment + definitionSegment;

            // Load the data
            client.putObject(builder.key(basePath + finalFileName).build(), requestBody);
            client.putObject(builder.key(basePath + "level1a/" + finalFileName).build(), requestBody);
            client.putObject(builder.key(basePath + "level1b/" + finalFileName).build(), requestBody);
            client.putObject(builder.key(basePath + "level1a/level2a/" + finalFileName).build(), requestBody);
            client.putObject(builder.key(basePath + "level1a/level2b/" + finalFileName).build(), requestBody);
        } catch (Exception ex) {
            LOGGER.error(ex.getMessage());
        }
    }

    static class AwsTestExecutor extends TestExecutor {

        public void executeTestFile(TestCaseContext testCaseCtx, TestFileContext ctx, Map<String, Object> variableCtx,
                String statement, boolean isDmlRecoveryTest, ProcessBuilder pb, TestCase.CompilationUnit cUnit,
                MutableInt queryCount, List<TestFileContext> expectedResultFileCtxs, File testFile, String actualPath,
                MutableInt actualWarnCount) throws Exception {
            String[] lines;
            switch (ctx.getType()) {
                case "s3bucket":
                    // <bucket_name> <def_name> <sub-path:src_file1,sub-path:src_file2,sub-path:src_file3>
                    lines = TestExecutor.stripAllComments(statement).trim().split("\n");
                    String lastLine = lines[lines.length - 1];
                    String[] command = lastLine.trim().split(" ");
                    int length = command.length;
                    if (length != 3) {
                        throw new Exception("invalid create bucket format");
                    }
                    dropRecreateBucket(command[0], command[1], command[2]);
                    break;
                default:
                    super.executeTestFile(testCaseCtx, ctx, variableCtx, statement, isDmlRecoveryTest, pb, cUnit,
                            queryCount, expectedResultFileCtxs, testFile, actualPath, actualWarnCount);
            }
        }
    }

    private static void dropRecreateBucket(String bucketName, String definition, String files) {
        String definitionPath = definition + (definition.endsWith("/") ? "" : "/");
        String[] fileSplits = files.split(",");

        LOGGER.info("Dropping bucket " + bucketName);
        try {
            client.deleteBucket(DELETE_BUCKET_BUILDER.bucket(bucketName).build());
        } catch (NoSuchBucketException e) {
            // ignore
        }
        LOGGER.info("Creating bucket " + bucketName);
        client.createBucket(CREATE_BUCKET_BUILDER.bucket(bucketName).build());
        LOGGER.info("Uploading to bucket " + bucketName + " definition " + definitionPath);
        fileNames.clear();
        for (int i = 0; i < fileSplits.length; i++) {
            String[] s3pathAndSourceFile = fileSplits[i].split(":");
            int size = s3pathAndSourceFile.length;
            String path;
            String sourceFilePath;
            String sourceFileName;
            if (size == 1) {
                // case: playground json-data/reviews SOURCE_FILE1,SOURCE_FILE2
                path = definitionPath;
                sourceFilePath = s3pathAndSourceFile[0];
                sourceFileName = FilenameUtils.getName(s3pathAndSourceFile[0]);
            } else {
                // case: playground json-data/reviews level1/sub-level:SOURCE_FILE1,level2/sub-level:SOURCE_FILE2
                path = definitionPath + s3pathAndSourceFile[0] + (s3pathAndSourceFile[0].endsWith("/") ? "" : "/");
                sourceFilePath = s3pathAndSourceFile[1];
                sourceFileName = FilenameUtils.getName(s3pathAndSourceFile[1]);
            }

            String keyPath = path + sourceFileName;
            int k = 1;
            while (fileNames.contains(keyPath)) {
                keyPath = path + (k++) + sourceFileName;
            }
            fileNames.add(keyPath);
            client.putObject(PUT_OBJECT_BUILDER.bucket(bucketName).key(keyPath).build(),
                    RequestBody.fromFile(Paths.get(sourceFilePath)));
        }
        LOGGER.info("Done creating bucket with data");
    }
}

package org.plukh.kpltest;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.kinesis.AmazonKinesis;
import com.amazonaws.services.kinesis.AmazonKinesisClient;
import com.amazonaws.services.kinesis.model.*;
import com.amazonaws.services.kinesis.producer.KinesisProducer;
import com.amazonaws.services.kinesis.producer.KinesisProducerConfiguration;
import com.google.common.collect.ImmutableList;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

public class KPLHandler {
    private static final Logger log = LogManager.getLogger(KPLHandler.class);

    private static final String STREAM = "kpl-test-stream";
    private static final int STREAM_SHARDS = 1;
    private static final long STREAM_CREATION_TIMEOUT = TimeUnit.MINUTES.toMillis(3);
    private static final long SLEEP_BETWEEN_ATTEMPTS_TIME = TimeUnit.SECONDS.toMillis(10);

    private static final String AWS_REGION = "us-east-1";

    private static final List<Pair<String, String>> BINARIES = ImmutableList.of(
            Pair.of("classpath:/bin/mkfifo", "/usr/bin/mkfifo"),
            Pair.of("/lib/x86_64-linux-gnu/libdl.so.2", "/tmp/amazon-kinesis-producer-native-binaries/libdl.so.2"),
            Pair.of("/lib/x86_64-linux-gnu/libpthread.so.0",
                    "/tmp/amazon-kinesis-producer-native-binaries/libpthread.so.0"),
            Pair.of("/lib/x86_64-linux-gnu/librt.so.1", "/tmp/amazon-kinesis-producer-native-binaries/librt.so.1"),
            Pair.of("/lib/x86_64-linux-gnu/libm.so.6", "/tmp/amazon-kinesis-producer-native-binaries/libm.so.6"),
            Pair.of("/lib/x86_64-linux-gnu/libgcc_s.so.1", "/tmp/amazon-kinesis-producer-native-binaries/libgcc_s" +
                    ".so.1"),
            Pair.of("/lib/x86_64-linux-gnu/libc.so.6", "/tmp/amazon-kinesis-producer-native-binaries/libc.so.6")
    );
    private static final long DELAY_BETWEEN_MESSAGES = TimeUnit.SECONDS.toMillis(10);

    private KinesisProducer producer;
    private Timer pusherTimer;

    public void initialize() throws IOException {
        initStream();
        initBinaries();
        initKinesisProducerLibrary();
        initMessagePusher();
    }

    protected void initStream() {
        log.info("Creating Kinesis stream");

        AmazonKinesis kinesis = new AmazonKinesisClient();

        // Describe the stream and check if it exists.
        DescribeStreamRequest describeStreamRequest = new DescribeStreamRequest().withStreamName(STREAM);
        try {
            StreamDescription streamDescription = kinesis.describeStream(describeStreamRequest).getStreamDescription();
            log.trace("Found exising stream {}, has a status of {}", STREAM, streamDescription.getStreamStatus());

            if ("DELETING".equals(streamDescription.getStreamStatus())) {
                throw new RuntimeException("Stream " + STREAM +
                        " is in DELETING state, can't use existing stream and can't create new stream");
            }

            // Wait for the stream to become active if it is not yet ACTIVE.
            if (!"ACTIVE".equals(streamDescription.getStreamStatus())) {
                waitForStreamToBecomeAvailable(kinesis, STREAM);
            }
        } catch (ResourceNotFoundException ex) {
            log.info("Stream {} does not exist. Creating it now", STREAM);

            // Create a stream. The number of shards determines the provisioned throughput.
            CreateStreamRequest createStreamRequest = new CreateStreamRequest();
            createStreamRequest.setStreamName(STREAM);
            createStreamRequest.setShardCount(STREAM_SHARDS);
            kinesis.createStream(createStreamRequest);

            // The stream is now being created. Wait for it to become active.
            waitForStreamToBecomeAvailable(kinesis, STREAM);
        }
    }

    private void waitForStreamToBecomeAvailable(AmazonKinesis kinesis, String streamName) {
        log.debug("Waiting for stream {} to become ACTIVE...", streamName);

        long startTime = System.currentTimeMillis();
        long endTime = startTime + STREAM_CREATION_TIMEOUT;

        while (System.currentTimeMillis() < endTime) {
            try {
                Thread.sleep(SLEEP_BETWEEN_ATTEMPTS_TIME);
            } catch (InterruptedException e) {
                //Do nothing, ok
            }

            try {
                DescribeStreamRequest describeStreamRequest = new DescribeStreamRequest();
                describeStreamRequest.setStreamName(streamName);

                DescribeStreamResult describeStreamResponse = kinesis.describeStream(describeStreamRequest);

                String streamStatus = describeStreamResponse.getStreamDescription().getStreamStatus();

                log.debug("Current stream status: {}", streamStatus);

                if ("ACTIVE".equals(streamStatus)) {
                    return;
                }
            } catch (ResourceNotFoundException ex) {
                // ResourceNotFound means the stream doesn't exist yet,
                // so ignore this error and just keep polling.
            } catch (AmazonServiceException ase) {
                throw new RuntimeException("Error creating stream " + streamName, ase);
            }
        }

        throw new RuntimeException("Stream " + streamName + " never became active");
    }

    protected void initBinaries() throws IOException {
        log.debug("Extracting binaries, if needed");
        if (!SystemUtils.IS_OS_WINDOWS) {
            log.debug("Running on Unix, extracting binaries...");

            for (Pair<String, String> binary : BINARIES) {
                File binfile = new File(binary.getRight());
                //noinspection ResultOfMethodCallIgnored
                binfile.getParentFile().mkdirs();

                if (binfile.exists()) {
                    log.warn("Binary already exists: {}", binfile.getAbsolutePath());
                } else {
                    try (InputStream in = binary.getLeft().startsWith("classpath:") ?
                            this.getClass().getResourceAsStream(binary.getLeft().replace("classpath:", "")) :
                            new FileInputStream(binary.getLeft());
                         OutputStream out = new FileOutputStream(binfile)) {
                        IOUtils.copy(in, out);
                    }

                    //noinspection ResultOfMethodCallIgnored
                    binfile.setExecutable(true, false);

                    log.info("Extracted executable: {}", binfile.getAbsolutePath());
                    log.debug("Executable exists: {}, size: {}", binfile.exists(), binfile.length());
                }
            }
        }
    }

    protected void initKinesisProducerLibrary() {
        log.info("Initializing Kinesis Producer Library...");
        KinesisProducerConfiguration producerConfig = new KinesisProducerConfiguration();

        producerConfig.setRegion(AWS_REGION);
        log.info("Set AWS region to {}", AWS_REGION);

        producer = new KinesisProducer(producerConfig);

        log.info("Kinesis Producer Library initialized successfully");
    }

    private void initMessagePusher() {
        pusherTimer = new Timer("Pusher Timer", true);
        pusherTimer.schedule(new TimerTask() {
            private int message = 0;

            @Override
            public void run() {
                pushToStream("Message " + (message++));
            }
        }, DELAY_BETWEEN_MESSAGES, DELAY_BETWEEN_MESSAGES);
        log.info("Kinesis message pusher started");
    }

    private void pushToStream(String text) {
        log.debug("Pushing data to Kinesis stream: {}", text);
        try {
            ByteBuffer activityBytes = ByteBuffer.wrap(text.getBytes("UTF8"));
            producer.addUserRecord(STREAM, text, activityBytes);
        } catch (UnsupportedEncodingException e) {
            log.error("This is totally unexpected - specified UTF8 encoding, but it is unsupported", e);
        }
    }

    public void shutdown() {
        terminateMessagePusher();
        shutdownKinesisProducerLibrary();
        destroyStream();
    }

    private void terminateMessagePusher() {
        pusherTimer.cancel();
    }

    private void shutdownKinesisProducerLibrary() {
        log.debug("Shutting down Kinesis Producer Library...");
        if (producer !=  null) {
            log.trace("Flushing outstanding records...");
            producer.flushSync();
            log.trace("Flush complete, destroying process");
            producer.destroy();
            log.info("Kinesis Producer Library shut down");
        }
    }

    private void destroyStream() {
        log.info("Destroying Kinesis stream...");

        // Delete the stream
        AmazonKinesis kinesis = new AmazonKinesisClient();
        try {
            kinesis.deleteStream(STREAM);
            log.info("Deleted Kinesis stream {}", STREAM);
        } catch (ResourceNotFoundException ex) {
            log.warn("Kinesis stream {} not found", STREAM);
        }
    }
}

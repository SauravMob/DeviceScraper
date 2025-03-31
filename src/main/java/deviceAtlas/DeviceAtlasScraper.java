package deviceAtlas;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.*;

public class DeviceAtlasScraper {

    final static String BASE_URL = "https://deviceatlas.com";
    // Connection timeout and retry configuration
    final static int CONNECTION_TIMEOUT = 100000; // 10 seconds
    final static int MAX_RETRIES = 3;
    final static int RETRY_DELAY = 1000; // 1 second

    // Thread pool configuration
    final static int BRAND_THREAD_POOL_SIZE = 2;
    final static int DEVICE_THREAD_POOL_SIZE = 4;

    // Batch processing configuration
    final static int BRANDS_PER_BATCH = 5;

    // Reusable ObjectMapper (thread-safe)
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) {
        int totalCountDone = 1;

        // Fetching all the brands
        List<String> alreadyProcessed = listOfProcessedDevices(totalCountDone);
        System.out.println("Number of devices done: " + alreadyProcessed.size());

        Map<String, String> brandsList = fetchUnprocessedBrands(alreadyProcessed);
        System.out.println("Total Remaining Elements: " + brandsList.size());

        // Process brands in batches using a thread pool
        ExecutorService brandExecutor = Executors.newFixedThreadPool(BRAND_THREAD_POOL_SIZE);

        List<List<Map.Entry<String, String>>> batches = createBatches(brandsList.entrySet(), BRANDS_PER_BATCH);
        CountDownLatch latch = new CountDownLatch(batches.size());

        for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
            final int currentBatchIndex = batchIndex;
            final List<Map.Entry<String, String>> currentBatch = batches.get(batchIndex);

            brandExecutor.submit(() -> {
                try {
                    processBrandBatch(currentBatchIndex + totalCountDone, currentBatch, alreadyProcessed);
                } catch (Exception e) {
                    System.err.println("Error processing batch " + (currentBatchIndex + totalCountDone) + ": " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(); // Wait for all batches to complete
            System.out.println("All batches processed successfully!");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Processing interrupted: " + e.getMessage());
        } finally {
            brandExecutor.shutdown();
        }
    }

    private static void processBrandBatch(int batchCounter, List<Map.Entry<String, String>> batch, List<String> alreadyProcessed) {
        Map<String, List<Map<String, String>>> brandMap = new ConcurrentHashMap<>();
        List<String> processedBrands = Collections.synchronizedList(new ArrayList<>());

        // Create a thread pool for processing devices within a brand
        ExecutorService deviceExecutor = Executors.newFixedThreadPool(DEVICE_THREAD_POOL_SIZE);
        List<Future<?>> futures = new ArrayList<>();

        for (Map.Entry<String, String> entry : batch) {
            String brandName = entry.getKey();
            String brandUrl = entry.getValue();

            futures.add(deviceExecutor.submit(() -> {
                System.out.println("Processing Brand: " + brandName + " in batch " + batchCounter);
                try {
                    List<Map<String, String>> devices = fetchAllDevices(brandUrl);
                    brandMap.put(brandName, devices);
                    processedBrands.add(brandName);
                    System.out.println("Completed Brand: " + brandName + " - Found " + devices.size() + " devices");
                } catch (Exception e) {
                    System.err.println("Error processing brand " + brandName + ": " + e.getMessage());
                }
            }));
        }

        // Wait for all brand processing to complete
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                System.err.println("Error waiting for brand processing: " + e.getMessage());
            }
        }

        deviceExecutor.shutdown();

        // Generate file with all processed brands in this batch
        generateFile(batchCounter, brandMap);
        synchronized (alreadyProcessed) {
            alreadyProcessed.addAll(processedBrands);
        }
        System.out.println("Processed brands in batch " + batchCounter + ": " + processedBrands);
    }

    private static <T> List<List<T>> createBatches(Collection<T> collection, int batchSize) {
        List<List<T>> batches = new ArrayList<>();
        List<T> currentBatch = new ArrayList<>();

        for (T item : collection) {
            currentBatch.add(item);
            if (currentBatch.size() == batchSize) {
                batches.add(new ArrayList<>(currentBatch));
                currentBatch.clear();
            }
        }

        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }

        return batches;
    }

    static void generateFile(int batchCounter, Map<String, List<Map<String, String>>> brandMap) {
        try (BufferedWriter wr = new BufferedWriter(new FileWriter("src/main/resources/BrandsList_" + batchCounter + ".txt"))) {
            String jsonOutput = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(brandMap);
            wr.write(jsonOutput);
            System.out.println("Successfully wrote batch " + batchCounter + " to file");
        } catch (IOException e) {
            System.err.println("Error: An error occurred while generating JSON for batch " + batchCounter + " - " + e.getMessage());
        }
    }

    static Map<String, String> fetchUnprocessedBrands(List<String> alreadyProcessed) {
        Map<String, String> brandMap = new HashMap<>();

        // Use connection pooling with retries
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                Connection connection = Jsoup.connect(BASE_URL + "/device-data/devices/")
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                        .header("Accept-Language", "*")
                        .timeout(CONNECTION_TIMEOUT)
                        .method(Connection.Method.GET);

                Connection.Response initialResponse = connection.execute();
                Document doc = initialResponse.parse();

                Elements links = doc.select(".manufacturer-group ul li a");

                for (Element element : links) {
                    String brandName = element.text();
                    if (!alreadyProcessed.contains(brandName)) {
                        brandMap.put(brandName, element.attr("href"));
                    }
                }

                // If successful, break out of retry loop
                break;

            } catch (IOException e) {
                System.err.println("Attempt " + (attempt + 1) + " failed: " + e.getMessage());
                if (attempt < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(RETRY_DELAY);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        return brandMap;
    }

    static List<String> listOfProcessedDevices(int totalCount) {
        List<String> listOfDevices = Collections.synchronizedList(new ArrayList<>());

        for (int count = 1; count <= totalCount; count++) {
            try {
                // Use try-with-resources for proper resource management
                InputStream inputStream = DeviceAtlasScraper.class.getClassLoader().getResourceAsStream("BrandsList_" + count + ".txt");

                if (inputStream == null) {
                    System.out.println("Could not find resource: BrandsList_" + count + ".txt");
                    continue;
                }

                Map<String, List<Map<String, String>>> data = objectMapper.readValue(
                        inputStream,
                        new TypeReference<Map<String, List<Map<String, String>>>>() {
                        }
                );

                inputStream.close();

                // Use streams for more efficient processing
                data.keySet().stream()
                        .filter(brandName -> !listOfDevices.contains(brandName))
                        .forEach(listOfDevices::add);

            } catch (Exception e) {
                System.err.println("Error in listOfProcessedDevices for file " + count + ": " + e.getMessage());
            }
        }
        return listOfDevices;
    }

    static List<Map<String, String>> fetchAllDevices(String url) throws IOException {
        List<Map<String, String>> resultList = Collections.synchronizedList(new ArrayList<>());

        // Apply retry logic
        Document doc = getDocumentWithRetry(BASE_URL + url);
        if (doc == null) {
            return resultList;  // Return empty list if document can't be fetched
        }

        // Use a more specific selector
        Elements deviceLinks = doc.select("#vendor-browser-container div p a");

        // Use a CompletionService for better performance
        ExecutorService deviceExecutor = Executors.newFixedThreadPool(DEVICE_THREAD_POOL_SIZE);
        CompletionService<Map<String, String>> completionService = new ExecutorCompletionService<>(deviceExecutor);

        int submittedTasks = 0;

        // Submit all tasks
        for (Element deviceLink : deviceLinks) {
            final String deviceName = deviceLink.text();
            final String deviceUrl = deviceLink.attr("href");

            completionService.submit(() -> fetchDeviceInfo(deviceName, deviceUrl));
            submittedTasks++;
        }

        // Collect results as they complete
        for (int i = 0; i < submittedTasks; i++) {
            try {
                Future<Map<String, String>> future = completionService.take();
                Map<String, String> deviceInfo = future.get();
                if (deviceInfo != null) {
                    resultList.add(deviceInfo);
                }
            } catch (InterruptedException | ExecutionException e) {
                System.err.println("Error fetching device: " + e.getMessage());
            }
        }

        deviceExecutor.shutdown();
        return resultList;
    }

    private static Map<String, String> fetchDeviceInfo(String deviceName, String deviceUrl) {
        Map<String, String> deviceInfo = new HashMap<>();
        deviceInfo.put("model", deviceName);

        try {
            Document deviceDoc = getDocumentWithRetry(BASE_URL + deviceUrl);
            if (deviceDoc != null) {
                deviceInfo.put("name", fetchSeparateDevice(deviceDoc));
            } else {
                deviceInfo.put("name", "Error: Unable to fetch device page");
            }
        } catch (Exception e) {
            System.err.println("Error fetching device " + deviceName + ": " + e.getMessage());
            deviceInfo.put("name", "Error: " + e.getMessage());
        }

        return deviceInfo;
    }

    private static Document getDocumentWithRetry(String url) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                // Use different user agents to avoid detection
                String[] userAgents = {
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.0 Safari/605.1.15",
                        "Mozilla/5.0 (Linux; Android 13; SM-A536B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"
                };

                String userAgent = userAgents[attempt % userAgents.length];

                return Jsoup.connect(url)
                        .userAgent(userAgent)
                        .header("Accept-Language", "*")
                        .timeout(CONNECTION_TIMEOUT)
                        .get();

            } catch (IOException e) {
                System.err.println("Attempt " + (attempt + 1) + " failed for URL " + url + ": " + e.getMessage());
                if (attempt < MAX_RETRIES - 1) {
                    try {
                        // Exponential backoff
                        Thread.sleep(RETRY_DELAY * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        return null;  // Return null if all attempts fail
    }

    static String fetchSeparateDevice(Document doc) {
        Element productData = doc.getElementById("product-data");
        if (productData == null) {
            return "Unknown";
        }

        Element titleElement = productData.selectFirst(".device-title");
        return titleElement != null ? titleElement.text() : "Unknown";
    }
}
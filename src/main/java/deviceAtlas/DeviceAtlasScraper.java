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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class DeviceAtlasScraper {

    final static String BASE_URL = "https://deviceatlas.com/";

    public static void main(String[] args) {

        int totalCountDone = 1;

        // Fetching all the brands
        List<String> alreadyProcessed = listOfProcessedDevices(totalCountDone);
        System.out.println("Number of devices done: " + alreadyProcessed.size());

        Map<String, String> brandsList = fetchUnprocessedBrands(alreadyProcessed);
        System.out.println("Total Remaining Elements: " + brandsList.size());

        Map<String, List<Map<String, String>>> brandMap = new HashMap<>();
        List<String> addedBrands = new ArrayList<>();

        for (Map.Entry<String, String> entry : brandsList.entrySet()) {
            String brandName = entry.getKey();
            System.out.println("***********************************");
            System.out.println("Processing Brand: " + brandName);
            try {
                brandMap.put(brandName, fetchAllDevices(entry.getValue()));
                addedBrands.add(brandName);

                if (addedBrands.size() == 3) {
                    totalCountDone++;
                    generateFile(totalCountDone, brandMap);
                    alreadyProcessed.addAll(addedBrands);
                    System.out.println("Final Already Processed: " + alreadyProcessed);
                    System.out.println("Processed brands in batch " + totalCountDone + ": " + addedBrands);

                    // Reset for next batch
                    addedBrands = new ArrayList<>();
                    brandMap = new HashMap<>();
                    System.out.println("***********************************");
                }
            } catch (Exception e) {
                System.out.println("Error in fetching all devices: " + e.getMessage());
            }
        }
    }

    static void generateFile(int batchCounter, Map<String, List<Map<String, String>>> brandMap) {
        ObjectMapper objectMapper = new ObjectMapper();
        try (BufferedWriter wr = new BufferedWriter(new FileWriter("src/main/resources/BrandsList_" + batchCounter + ".txt"))) {
            String jsonOutput = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(brandMap);
            wr.write(jsonOutput);
            System.out.println("Successfully wrote batch " + batchCounter + " to file");
        } catch (IOException e) {
            System.err.println("Error: An error occurred while generating JSON for batch " + batchCounter + " - " + e.getMessage());
        }
    }

    static Map<String, String> fetchUnprocessedBrands(List<String> alreadyProcessed) {
        System.out.println(alreadyProcessed);
        // Set up a connection pool for reuse
        Connection.Response initialResponse;

        Map<String, String> brandMap = new HashMap<>();
        try {
            // Set up a connection with shared cookies
            initialResponse = Jsoup.connect(BASE_URL + "device-data/devices/")
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept-Language", "*")
                    .method(Connection.Method.GET)
                    .execute();

            Document doc = initialResponse.parse();

            // More efficient selector - get directly what you need
            Elements links = doc.select(".manufacturer-group ul li a");

            for (Element element : links) {
                String brandName = element.text();
                if (!alreadyProcessed.contains(brandName)) {
                    brandMap.put(brandName, element.attr("href"));
                }
            }
        } catch (IOException e) {
            System.out.println("Error in main: " + e.getMessage());
        }

        return brandMap;
    }

    static List<String> listOfProcessedDevices(int totalCount) {
        List<String> listOfDevices = new ArrayList<>();
        for (int count = 1; count <= totalCount; count++) {
            try {
                // Fix the path - use the full path from the classpath root
                InputStream inputStream = DeviceAtlasScraper.class.getClassLoader().getResourceAsStream("BrandsList_" + count + ".txt");

                // Add null check to help diagnose the issue
                if (inputStream == null) {
                    System.out.println("Could not find resource: BrandsList_" + count + ".txt");
                }

                ObjectMapper objectMapper = new ObjectMapper();
                Map<String, List<Map<String, String>>> data = objectMapper.readValue(inputStream, new TypeReference<Map<String, List<Map<String, String>>>>() {
                });

                for (Map.Entry<String, List<Map<String, String>>> entry : data.entrySet()) {
                    String brandName = entry.getKey();
                    if (!listOfDevices.contains(brandName)) {
                        listOfDevices.add(brandName);
                    }
                }

            } catch (Exception e) {
                System.out.println("Error in listOfProcessedDevices: " + e.getMessage()); // Print full stack trace for better debugging
            }
        }
        return listOfDevices;
    }

    static List<Map<String, String>> fetchAllDevices(String url) throws IOException {
        Document doc = Jsoup.connect(BASE_URL + url)
                .userAgent("Mozilla/5.0 (Linux; Android 13; SM-A536B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36")
                .header("Accept-Language", "*")
                .get();

        List<Map<String, String>> resultList = new ArrayList<>();

        // More efficient selector - direct path to what you need
        Elements deviceLinks = doc.select("#vendor-browser-container div p a");

        // Process devices in parallel (within limits)
        ExecutorService deviceExecutor = Executors.newFixedThreadPool(5); // Smaller pool for device processing
        List<Future<Map<String, String>>> deviceFutures = new ArrayList<>();

        for (Element deviceLink : deviceLinks) {
            String deviceName = deviceLink.text();
            String deviceUrl = deviceLink.attr("href");

            Future<Map<String, String>> future = deviceExecutor.submit(() -> {
                try {
                    Document deviceDoc = Jsoup.connect(BASE_URL + deviceUrl)
                            .userAgent("Mozilla/5.0 (Linux; Android 12; Redmi Note 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36")
                            .header("Accept-Language", "*")
                            .get();

                    Map<String, String> deviceInfo = new HashMap<>();
                    deviceInfo.put("name", fetchSeparateDevice(deviceDoc));
                    deviceInfo.put("model", deviceName);
                    return deviceInfo;
                } catch (IOException e) {
                    System.out.println("Error fetching device " + deviceName + ": " + e.getMessage());
                    Map<String, String> errorInfo = new HashMap<>();
                    errorInfo.put("model", deviceName);
                    errorInfo.put("name", "Error: " + e.getMessage());
                    return errorInfo;
                }
            });

            deviceFutures.add(future);
        }

        // Collect results
        for (Future<Map<String, String>> future : deviceFutures) {
            try {
                Map<String, String> deviceInfo = future.get();
                if (deviceInfo != null) {
                    resultList.add(deviceInfo);
                }
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("Error waiting for device task: " + e.getMessage());
            }
        }

        deviceExecutor.shutdown();
        System.out.println("ResultList: " + resultList);
        return resultList;
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
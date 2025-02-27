import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {

    final static String BASE_URL = "https://www.gsmarena.com/";

    public static void main(String[] args) {
        Document doc;

        // Fetching all the brands
        Map<String, String> brandsList = new HashMap<>();

        try {
            doc = Jsoup.connect(BASE_URL + "makers.php3")
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept-Language", "*")
                    .get();
            Elements elements = doc.select("table").select("tbody").select("tr").select("td");

            for (Element ele : elements) {
                Elements url = ele.select("a");
                brandsList.put(url.text(), url.attr("href"));
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        // Fetching all the brands along with pagination for a brand
        Map<String, List<String>> brandMap = new HashMap<>();

        for (Map.Entry<String, String> entry : brandsList.entrySet()) {
            System.out.println(entry.getKey() + " : " + entry.getValue());
            try {
                doc = Jsoup.connect(BASE_URL + entry.getValue())
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                        .header("Accept-Language", "*")
                        .get();
                brandMap.put(entry.getKey(), fetchAllDevices(doc, new ArrayList<>()));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        System.out.println("BrandsList: " + brandMap);
    }

    static List<String> fetchAllDevices(Document doc, List<String> mobileList) throws IOException {
        Document doc1;
        Elements elements = doc.getElementsByClass("makers").select("ul").select("li");
        for (Element ele : elements) {
            String modelName = ele.select("a").select("strong").select("span").text();
            mobileList.add(modelName);
        }
        Element ele = doc.getElementsByClass("nav-pages").select("a").last();
        if (ele != null) {
            if (ele.attr("href").equals("#") && !ele.hasAttr("title") && ele.text().equals("►")) {
                return mobileList;
            }
            doc1 = Jsoup.connect(BASE_URL + ele.attr("href"))
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept-Language", "*")
                    .get();
            mobileList = fetchAllDevices(doc1, mobileList);
        }
        return mobileList;
    }
}
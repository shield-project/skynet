package org.shieldproject.skynet.crawler.provider.service;

import jdk.nashorn.api.scripting.ScriptObjectMirror;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.shoper.commons.http.HttpClient;
import org.shoper.commons.http.HttpClientBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * @author kezhijie
 * @date 2019/1/11 9:27
 */
@Component
public class SuningTask {
    // 苏宁数码分类
    private static final String SUNING_DIGITAL_URL = "https://list.suning.com/#20089";
    private static final String SUNING_PRODUCT_URL = "//product.suning.com/#{suffix}/#{prefix}.html";

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @PostConstruct
    public void init() {
        // 抓商品ID
        new Thread(() -> catchItems()).start();
        // 抓商品详情
        new Thread(() -> fetchItemSku()).start();
    }

    private void fetchItemSku() {
        int count = 0;
        for (;;count++) {
            String id = redisTemplate.opsForList().rightPop("SN_ITEM_ID", 5, TimeUnit.SECONDS);
            if (id == null) continue;
            if (id.equals("over")) break;
            fetchItemSku(id);
        }

        redisTemplate.opsForList().leftPush("SN_ITEM_SKU", "over");
        System.out.println("苏宁列表页商品数量--" + count);
    }

    private void fetchItemSku(String id) {
        String[] var1 = id.split("\\|\\|\\|\\|\\|");

        String url = SUNING_PRODUCT_URL.replace("#{suffix}", var1[1]).replace("#{prefix}", var1[0]);

        try {
            HttpClient build = HttpClientBuilder.custom().retry(3).url(url).timeoutUnit(TimeUnit.MICROSECONDS).timeout(1).charset("utf-8").build();
            String data = build.doGet();
            Document parse = Jsoup.parse(data);
            Element scriptElement = parse.getElementsByTag("script").first();
            if (Objects.nonNull(scriptElement)) {
                String replace = scriptElement.html();
                replace += "\nsn.clusterMap;";
                ScriptEngineManager manager = new ScriptEngineManager();
                ScriptEngine engine = manager.getEngineByExtension("js");

                // 获取当前商品所有SKU
                ScriptObjectMirror eval = (ScriptObjectMirror) engine.eval(replace);
                Collection<Object> values = eval.values();

                for (Object p : values) {
                    ScriptObjectMirror v = (ScriptObjectMirror) p;
                    String skuId = null;
                    List<Map<String, Object>> skuList = (List<Map<String, Object>>) v.get("itemCuPartNumber");

                    for (Map<String, Object> map : skuList) {
                        Object skuId1 = map.get("partNumber");
                        if (skuId1 instanceof Double)
                            skuId = new BigDecimal((Double) skuId1).toPlainString();
                        else if (skuId1 instanceof Integer)
                            skuId = new BigDecimal((Integer) skuId1).toPlainString();
                        else if (skuId1 instanceof Long)
                            skuId = new BigDecimal((Long) skuId1).toPlainString();

                        System.out.println(skuId);
//                        redisTemplate.opsForList().leftPush("SN_ITEM_SKU", skuId);
                    }
                }
            } else
                System.out.println("页面有变动");
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    // 抓取商品ID
    public void catchItems() {
        try {
            String categoryCode = SUNING_DIGITAL_URL.substring(SUNING_DIGITAL_URL.indexOf("#"));

            HttpClient build = HttpClientBuilder.custom().retry(3).url(SUNING_DIGITAL_URL).charset("utf-8").timeout(1).timeoutUnit(TimeUnit.MINUTES).build();
            String html = build.doGet();
            Document parse = Jsoup.parse(html);
            Element element = parse.getElementById(categoryCode);
            Elements items = element.getElementsByClass("price");
            System.out.println(SUNING_DIGITAL_URL);
            for (Element item : items) {
                // e.g. 600384083|||||0070156382
                String skuid = item.attr("datasku");
                redisTemplate.opsForList().leftPush("SN_ITEM_ID", skuid);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        redisTemplate.opsForList().leftPush("SN_ITEM_ID", "over");
    }
}

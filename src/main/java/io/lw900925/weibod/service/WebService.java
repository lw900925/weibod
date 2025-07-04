package io.lw900925.weibod.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.RateLimiter;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.lw900925.weibod.config.WeibodProperties;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import reactor.core.publisher.FluxSink;

/**
 * @author LIUWEI
 */
@Service
public class WebService {

    private static final Logger logger = LoggerFactory.getLogger(WebService.class);

    @Autowired
    private OkHttpClient okHttpClient;
    @Autowired
    private WeibodProperties properties;
    @Autowired
    private Gson gson;

    private RateLimiter rateLimiter = RateLimiter.create(0.5);

    public void run(String uid, String filter, FluxSink<String> sink) {
        // 1.获取缓存
        Map<String, Map<String, String>> cacheMap = loadCache();

        // 2.获取用户基本信息和timelines
        Map<String, Object> map = getTimelines(uid, cacheMap, sink);

        // 3.提取图片链接
        map = extract(map, filter, sink);

        // 4.下载文件
        download(map, cacheMap, sink);

        // 5.更新缓存
        saveCache(cacheMap);
    }

    private Map<String, Map<String, String>> loadCache() {
        Path cachePath = Paths.get(properties.getWeibo().getCacheDir());
        if (Files.exists(cachePath)) {
            try (InputStream inputStream = Files.newInputStream(cachePath)) {
                String jsonStr = StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
                JsonArray caches = JsonParser.parseString(jsonStr).getAsJsonArray();
                return StreamSupport.stream(Spliterators.spliteratorUnknownSize(caches.iterator(), 0), false)
                        .map(c -> c.getAsJsonObject())
                        .map(c -> {
                            Map<String, String> cache = Maps.newHashMap();
                            cache.put("uid", c.get("uid").getAsString());
                            cache.put("screen_name", c.get("screen_name").getAsString());
                            cache.put("created_at", c.get("created_at").getAsString());
                            return cache;
                        })
                        .collect(Collectors.toMap(map -> map.get("uid"), map -> map));
            } catch (IOException e) {
                logger.error(String.format("读取缓存文件失败：%s", e.getMessage()), e);
            }
        }
        return Maps.newHashMap();
    }

    public Map<String, Object> getTimelines(String uid, Map<String, Map<String, String>> cacheMap,
            FluxSink<String> sink) {

        // 获取用户信息
        JsonObject userInfo = getUserInfo(uid, sink);
        String screenName = userInfo.get("screen_name").getAsString();

        // 获取缓存信息
        Map<String, String> cache = cacheMap.getOrDefault(uid, Maps.newHashMap());
        cache.put("uid", uid);
        cache.put("screen_name", screenName);

        // statuses_count
        int count = userInfo.get("statuses_count").getAsInt();
        if (count == 0) {
            return null;
        }

        sink.next(String.format("%s - 总共有%s条微博", screenName, count));
        logger.debug("{} - 总共有{}条微博", screenName, count);

        int size = properties.getWeibo().getSize();

        String url = properties.getWeibo().getApi().getBaseUrl() + "/container/getIndex";
        Map<String, String> parameters = new HashMap<>();
        parameters.put("type", "uid");
        parameters.put("value", uid);
        parameters.put("containerid", "107603" + uid);
        parameters.put("count", String.valueOf(size));

        JsonArray mblogs = new JsonArray();
        boolean hasMore = true;
        int index = 0;
        while (true) {
            // 这个接口频繁访问会返回403，需要控制访问频率
            // 获取令牌，控制请求频率
            rateLimiter.acquire();

            String jsonStr = httpGet(url, parameters);
            JsonObject jsonObject = JsonParser.parseString(jsonStr).getAsJsonObject();
            if (jsonObject.get("ok").getAsInt() == 0) {
                break;
            }

            JsonObject cardlistInfo = jsonObject.get("data").getAsJsonObject().get("cardlistInfo").getAsJsonObject();
            if (cardlistInfo.has("since_id")) {
                parameters.put("since_id", cardlistInfo.get("since_id").getAsString());
            } else if (cardlistInfo.has("total") || cardlistInfo.has("page")) {
                hasMore = false;
            }

            // 增量抓取逻辑
            JsonArray cards = jsonObject.get("data").getAsJsonObject().get("cards").getAsJsonArray();
            for (int i = 0; i < cards.size(); i++) {
                JsonObject card = cards.get(i).getAsJsonObject();
                if (card.get("card_type").getAsInt() == 9) {
                    JsonObject mblog = card.get("mblog").getAsJsonObject();
                    if (mblog.get("mblogtype").getAsInt() != 2) { // 过滤掉置顶微博
                        if (cache.containsKey("created_at")) {
                            // 当前微博创建时间
                            LocalDateTime createAt = LocalDateTime.parse(mblog.get("created_at").getAsString(),
                                    DateTimeFormatter.ofPattern("E MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH));

                            // 缓存中最新的微博创建时间
                            LocalDateTime latest = LocalDateTime.parse(cache.get("created_at"),
                                    DateTimeFormatter.ofPattern("E MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH));

                            // 只保留缓存中创建时间之后的微博
                            if (createAt.isBefore(latest) || createAt.isEqual(latest)) {
                                hasMore = false;
                                break;
                            }
                        }

                        mblogs.add(mblog);
                    }
                }
            }

            sink.next(String.format("%s - 第%s次抓取，本次返回%s条timeline", screenName, index + 1, cards.size()));
            logger.debug("{} - 第{}次抓取，本次返回{}条timeline", screenName, index + 1, cards.size());

            index++;

            if (!hasMore) {
                break;
            }
        }

        sink.next(String.format("%s - 所有timeline已经获取完毕，结果集中共包含%s条", screenName, mblogs.size()));
        logger.debug("{} - 所有timeline已经获取完毕，结果集中共包含{}条", screenName, mblogs.size());

        // 更新缓存
        if (mblogs.size() > 0) {
            JsonObject mblog = mblogs.get(0).getAsJsonObject();
            cache.put("created_at", mblog.get("created_at").getAsString());
        }
        cacheMap.put(uid, cache);

        Map<String, Object> map = Maps.newHashMap();
        map.put("user", userInfo);
        map.put("mblogs", mblogs);
        return map;
    }

    private JsonObject getUserInfo(String uid, FluxSink<String> sink) {
        String url = properties.getWeibo().getApi().getBaseUrl() + "/container/getIndex";
        String jsonStr = httpGet(url, ImmutableMap.<String, String>builder()
                .put("type", "uid")
                .put("value", uid)
                .build());

        JsonObject msg = JsonParser.parseString(jsonStr).getAsJsonObject();
        if (msg.get("ok").getAsInt() != 1) {
            String str = String.format("获取用户失败: %s", decode(jsonStr));
            sink.next(str);
            throw new RuntimeException(str);
        }

        return msg.get("data").getAsJsonObject().get("userInfo").getAsJsonObject();
    }

    private String httpGet(String url, Map<String, String> parameters) {
        String jsonStr = null;

        String[] keyValuePairs = parameters.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
        String strQueryParam = String.join("&", keyValuePairs);
        url = url + "?" + strQueryParam;
        Request request = new Request.Builder().url(url).get().build();
        try {
            Response response = okHttpClient.newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                jsonStr = response.body().string();
            } else {
                logger.debug("GET响应失败\nURL: {}\nstatus_code:{}\nmessage:{}", url, response.code(), response.message());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return jsonStr;
    }

    private static String decode(String str) {
        Pattern pattern = Pattern.compile("(\\\\u(\\p{XDigit}{4}))");
        Matcher matcher = pattern.matcher(str);
        char ch;
        while (matcher.find()) {
            ch = (char) Integer.parseInt(matcher.group(2), 16);
            str = str.replace(matcher.group(1), String.valueOf(ch));
        }
        return str;
    }

    private Map<String, Object> extract(Map<String, Object> map, String filter, FluxSink<String> sink) {
        List<Map<String, String>> list = new ArrayList<>();

        if (map == null) {
            return null;
        }

        JsonObject user = (JsonObject) map.get("user");
        JsonArray mblogs = (JsonArray) map.get("mblogs");

        if (mblogs.size() == 0) {
            map.put("live_photos", list);
            return map;
        }

        mblogs.forEach(element -> {
            JsonObject mblog = element.getAsJsonObject();
            String createdAt = mblog.get("created_at").getAsString();

            // 获取所有pics标签
            if (mblog.has("pics") && mblog.get("pics").isJsonArray()) {
                JsonArray pics = mblog.get("pics").getAsJsonArray();
                for (int i = 0; i < pics.size(); i++) {
                    JsonObject pic = pics.get(i).getAsJsonObject();

                    Map<String, String> picMap = Maps.newHashMap();
                    picMap.put("created_at", createdAt);
                    picMap.put("index", String.valueOf(i + 1));

                    // 如果仅下载LivePhoto且该图片是LivePhoto
                    String picUrl = pic.get("large").getAsJsonObject().get("url").getAsString();
                    if ("all".equals(filter)) {
                        picMap.put("img", picUrl);
                        if (pic.has("videoSrc")) {
                            String movUrl = pic.get("videoSrc").getAsString();
                            picMap.put("mov", movUrl);
                        }
                    } else if ("livephoto".equals(filter) && pic.has("videoSrc")) {
                        String movUrl = pic.get("videoSrc").getAsString();
                        picMap.put("img", picUrl);
                        picMap.put("mov", movUrl);
                    } else {
                        continue;
                    }

                    list.add(picMap);
                }

            }
        });

        map.put("live_photos", list);
        String str = String.format("%s - 链接已提取完毕，总共%s个", user.get("screen_name").getAsString(), list.size());
        sink.next(str);
        logger.debug(str);
        return map;
    }

    @SuppressWarnings("unchecked")
    private void download(Map<String, Object> map, Map<String, Map<String, String>> cacheMap, FluxSink<String> sink) {
        if (map == null || ((JsonArray) map.get("mblogs")).size() == 0) {
            return;
        }

        JsonObject user = (JsonObject) map.get("user");
        List<Map<String, String>> livePhotos = (List<Map<String, String>>) map.get("live_photos");

        String screenName = user.get("screen_name").getAsString();

        sink.next(String.format("%s - 开始下载...", screenName));
        logger.debug("{} - 开始下载...", screenName);

        AtomicInteger indexAI = new AtomicInteger(0);

        livePhotos.forEach(livePhoto -> {
            String imgUrl = livePhoto.get("img");
            String movUrl = livePhoto.get("mov");
            String createdAt = livePhoto.get("created_at");
            String i = livePhoto.get("index");

            LocalDateTime createDate = LocalDateTime.parse(createdAt,
                    DateTimeFormatter.ofPattern("E MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH));
            String second = DateTimeFormatter.ofPattern("ss").format(createDate);

            int index = indexAI.incrementAndGet();

            List<String> filenames = Lists.newArrayList(imgUrl, movUrl).stream().filter(Objects::nonNull).map(url -> {
                // 文件命格式：yyyy_MM_dd_HH_mm_ss_IMG_0001.JPG
                String filename = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm").format(createDate)
                        + "_IMG_"
                        + Strings.padStart(i + second, 4, '0')
                        + ".";

                String extension = getFileExtension(url);
                if (Strings.isNullOrEmpty(extension)) {
                    filename = "错误的文件：url = " + url;
                    return filename;
                } else {
                    filename += extension.toUpperCase();
                }
                
                // 下载文件
                return post(url, filename, screenName, 5);
            }).collect(Collectors.toList());

            String str = String.format("%s - 第[%s/%s]个文件下载完成: %s",
                    screenName, index, livePhotos.size(), Joiner.on(" - ").join(filenames));
            sink.next(str);
            logger.debug(str);
        });

        sink.next(String.format("%s - 下载完成", screenName));
    }

    private String getFileExtension(String url) {
        try {
            URI uri = new URI(url);
            String path = uri.getPath();

            // 优先从路径提取扩展名
            String ext = extractExtensionFromPath(path);
            if (ext != null)
                return ext;

            // 如果路径无扩展名，尝试从查询参数提取
            String query = uri.getQuery();
            if (query != null && !query.isEmpty()) {
                Map<String, String> params = parseQueryParams(uri);
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    String paramValue = entry.getValue();
                    if (paramValue != null && paramValue.toLowerCase().startsWith("http")) {
                        // 递归处理嵌套URL
                        ext = getFileExtension(paramValue);
                        if (ext != null)
                            return ext;
                    }
                }
            }

            return null;
        } catch (Exception e) {
            logger.error("获取文件扩展名异常: {}", url, e);
            return null;
        }
    }

    // 从路径提取扩展名的私有方法
    private String extractExtensionFromPath(String path) {
        if (path == null || path.isEmpty())
            return null;

        int lastSlashIndex = path.lastIndexOf('/');
        String filename = (lastSlashIndex != -1)
                ? path.substring(lastSlashIndex + 1)
                : path;

        int lastDotIndex = filename.lastIndexOf('.');
        return (lastDotIndex != -1)
                ? filename.substring(lastDotIndex + 1).toLowerCase()
                : null;
    }

    // 复用之前的查询参数解析方法
    private Map<String, String> parseQueryParams(URI uri) {
        Map<String, String> queryPairs = new HashMap<>();
        if (uri == null || uri.getQuery() == null) {
            return queryPairs;
        }

        String[] pairs = uri.getQuery().split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            String key = idx > 0 ? pair.substring(0, idx) : pair;
            String value = idx > 0 ? pair.substring(idx + 1) : null;

            try {
                // 使用UTF-8解码
                key = URLDecoder.decode(key, "utf-8");
                if (value != null) {
                    value = URLDecoder.decode(value, "utf-8");
                }
            } catch (Exception e) {
                logger.warn("URL参数解码失败: {}", pair);
            }

            queryPairs.put(key, value);
        }

        return queryPairs;
    }

    private String post(String url, String filename, String screenName, int retry) {
        if (retry < 0) {
            logger.warn("重试次数用完，文件下载失败: url = {}", url);
            return filename;
        }

        String destDir = properties.getWeibo().getDestDir();
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                try (InputStream inputStream = Objects.requireNonNull(response.body()).byteStream()) {
                    Path path = Paths.get(destDir, screenName, filename);
                    Files.createDirectories(path.getParent());
                    Files.copy(inputStream, path);
                }
            } else {
                filename = String.format("文件下载出错 - code = %s, message = %s, url = %s",
                        response.code(), response.message(), url);
                logger.error(filename);
            }
        } catch (ProtocolException e) {
            if ("unexpected end of stream".equals(e.getMessage())) {
                logger.warn("POST请求失败，正在重试: retry = {}，filename = {}, url = {}", retry, filename, url);
                deleteIfExists(Paths.get(destDir, screenName, filename));
                post(url, filename, screenName, --retry);
            }
        } catch (FileAlreadyExistsException e) {
            logger.warn("文件已存在，跳过下载：filename = {}, url = {}", filename, url);
            filename = String.format("[SKIP]:%s", filename);
        } catch (IOException e) {
            filename = String.format("写入本地文件失败: url = %s, filename = %s", url, filename);
            logger.error(filename);
            logger.error(e.getMessage(), e);
        }

        return filename;
    }

    private void saveCache(Map<String, Map<String, String>> cacheMap) {
        try {
            // 保存缓存文件
            List<Map<String, String>> caches = Lists.newArrayList(cacheMap.values());
            String jsonStr = gson.toJson(caches);
            Path path = Paths.get(properties.getWeibo().getCacheDir());
            Files.createDirectories(path.getParent());
            Files.deleteIfExists(path);
            Files.write(path, jsonStr.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
    }

    private void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
    }
}

package io.lw900925.weibod.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;

@SpringBootConfiguration
public class WeibodConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(WeibodConfiguration.class);

    @Autowired
    private WeibodProperties weibodProperties;

    @Bean
    public X509TrustManager x509TrustManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    @Bean
    public SSLSocketFactory sslSocketFactory() {
        try {
            // 信任任何链接
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] { x509TrustManager() }, new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Create a new connection pool with tuning parameters appropriate for a
     * single-user application.
     * The tuning parameters in this pool are subject to change in future OkHttp
     * releases. Currently
     */
    @Bean
    public ConnectionPool pool() {
        return new ConnectionPool(200, 5, TimeUnit.MINUTES);
    }

    @Bean
    public OkHttpClient okHttpClient() {
        // 读取cookie配置文件
        String cookie = Optional.ofNullable(weibodProperties.getWeibo().getApi().getCookie())
            .map(Paths::get)
            .filter(Files::exists)
            .map(path -> {
                List<String> lines = Lists.newArrayList();
                try {
                    lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    logger.error("读取cookie文件错误: {}", e.getMessage(), e);
                }
                return lines;
            })
            .map(lines -> String.join("", lines)).orElse("");

        return new OkHttpClient.Builder()
                .protocols(Collections.singletonList(Protocol.HTTP_1_1)) // 禁用HTTP/2
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .connectionPool(pool())
                .retryOnConnectionFailure(true)
                .addNetworkInterceptor(chain -> {
                    // 添加必要请求头
                    Request request = chain.request().newBuilder()
                            .header("User-Agent",
                                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .header("Accept",
                                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                            .header("Referer", "https://weibo.com/")
                            .header("Accept-Encoding", "identity")
                            .header("Connection", "close")
                            .header("Cookie", cookie)
                            .build();
                    return chain.proceed(request);
                }).build();
    }

    @Bean
    public Gson gson() {
        return new GsonBuilder()
                .setPrettyPrinting()
                .create();
    }
}

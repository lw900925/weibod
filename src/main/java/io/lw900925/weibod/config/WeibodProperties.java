package io.lw900925.weibod.config;

import com.google.common.collect.Lists;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

@ConfigurationProperties(prefix = "weibod")
@Component
public class WeibodProperties {

    private Weibo weibo;

    public Weibo getWeibo() {
        return weibo;
    }

    public void setWeibo(Weibo weibo) {
        this.weibo = weibo;
    }

    public static class Weibo {

        private Api api;
        private Integer size = 50;
        private String destDir;
        private String cacheDir;
        private String userDir;
        private List<String> retryForExceptions = Lists.newArrayList();

        public Api getApi() {
            return api;
        }

        public void setApi(Api api) {
            this.api = api;
        }

        public Integer getSize() {
            return size;
        }

        public void setSize(Integer size) {
            this.size = size;
        }

        public String getDestDir() {
            return destDir;
        }

        public void setDestDir(String destDir) {
            this.destDir = destDir;
        }

        public String getCacheDir() {
            return cacheDir;
        }

        public void setCacheDir(String cacheDir) {
            this.cacheDir = cacheDir;
        }

        public String getUserDir() {
            return userDir;
        }

        public void setUserDir(String userDir) {
            this.userDir = userDir;
        }

        public List<String> getRetryForExceptions() {
            return retryForExceptions;
        }

        public void setRetryForExceptions(List<String> retryForExceptions) {
            this.retryForExceptions = retryForExceptions;
        }

        public static class Api {
            private String baseUrl;
            private String cookie;

            public String getBaseUrl() {
                return baseUrl;
            }

            public void setBaseUrl(String baseUrl) {
                this.baseUrl = baseUrl;
            }

            public String getCookie() {
                return cookie;
            }

            public void setCookie(String cookie) {
                this.cookie = cookie;
            }
        }
    }


}

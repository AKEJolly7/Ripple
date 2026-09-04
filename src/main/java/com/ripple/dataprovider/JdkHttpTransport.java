package com.ripple.dataprovider;

import com.ripple.dataprovider.api.HttpTransport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 基于 JDK HttpClient 的真实传输实现：带 User-Agent 与超时，IO 异常归类为 NETWORK。
 */
public final class JdkHttpTransport implements HttpTransport {

    /**
     * 完整浏览器形态的 UA：Yahoo/Sina 等源的 WAF 按浏览器特征（AppleWebKit/Chrome 等 token）
     * 做模式匹配，残缺 UA 实测被拒（curl 默认 UA → Yahoo 429）；末尾附产品 token 保持可追溯。
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Ripple/0.1";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public Response get(URI uri) throws HttpFetchException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(resp.statusCode(), resp.body());
        } catch (IOException e) {
            throw new HttpFetchException(HttpFetchException.Type.NETWORK, "I/O error for " + uri, e);
        }
    }
}

package com.ripple.dataprovider.api;

import com.ripple.dataprovider.HttpFetchException;

import java.net.URI;

/**
 * HTTP 传输抽象：Provider 只面向该接口，测试注入 mock 即可离线验证解析逻辑。
 */
public interface HttpTransport {

    /**
     * 一次 GET 的结果（状态码 + 响应体）。
     */
    record Response(int status, String body) {
    }

    Response get(URI uri) throws HttpFetchException, InterruptedException;
}

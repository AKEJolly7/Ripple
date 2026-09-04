package com.ripple.dataprovider;

/**
 * 数据抓取失败，按类型分类（限速 / 服务端 / 客户端 / 网络 / 解析），便于上层差异化处理。
 */
public final class HttpFetchException extends Exception {

    public enum Type {RATE_LIMITED, SERVER, CLIENT, NETWORK, PARSE}

    private final Type type;

    public HttpFetchException(Type type, String message) {
        super(message);
        this.type = type;
    }

    public HttpFetchException(Type type, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
    }

    public Type type() {
        return type;
    }

    /**
     * 由 HTTP 状态码归类（200 之外的 4xx/5xx）。
     */
    public static HttpFetchException ofStatus(int status) {
        if (status == 429) {
            return new HttpFetchException(Type.RATE_LIMITED, "HTTP 429 rate limited");
        }
        if (status >= 500) {
            return new HttpFetchException(Type.SERVER, "HTTP " + status + " server error");
        }
        return new HttpFetchException(Type.CLIENT, "HTTP " + status + " client error");
    }
}

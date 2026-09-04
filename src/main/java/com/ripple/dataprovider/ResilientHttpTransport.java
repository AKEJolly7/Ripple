package com.ripple.dataprovider;

import com.ripple.dataprovider.api.HttpTransport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Random;

/**
 * 弹性传输：对 429/5xx/网络错误做指数退避重试，参数为
 * 基础间隔 1s、最多 3 次重试（1s→2s→4s，含 ±25% 随机抖动、单次上限 8s）。
 * 4xx（非 429）不重试直接抛出（如 Yahoo 地域封锁 403，重试无意义）。
 * 重试参数可注入以便单测。
 */
public final class ResilientHttpTransport implements HttpTransport {

    private static final Logger log = LoggerFactory.getLogger(ResilientHttpTransport.class);
    private static final long MAX_BACKOFF_MS = 8_000;

    private final HttpTransport delegate;
    private final int maxRetries;
    private final long baseBackoffMs;
    private final Random random = new Random();

    public ResilientHttpTransport() {
        this(new JdkHttpTransport(), 3, 1_000);
    }

    public ResilientHttpTransport(HttpTransport delegate, int maxRetries, long baseBackoffMs) {
        this.delegate = delegate;
        this.maxRetries = maxRetries;
        this.baseBackoffMs = baseBackoffMs;
    }

    @Override
    public Response get(URI uri) throws HttpFetchException, InterruptedException {
        HttpFetchException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                long backoff = backoffMs(attempt);
                log.info("退避 {}ms 后进行第 {}/{} 次重试: {}", backoff, attempt, maxRetries, uri);
                Thread.sleep(backoff);
            }
            Response resp;
            try {
                resp = delegate.get(uri);
            } catch (HttpFetchException e) {
                if (e.type() != HttpFetchException.Type.NETWORK || attempt == maxRetries) {
                    throw e;
                }
                last = e;
                continue;
            }
            if (resp.status() == 200) {
                return resp;
            }
            boolean retryable = resp.status() == 429 || resp.status() >= 500;
            if (!retryable || attempt == maxRetries) {
                throw HttpFetchException.ofStatus(resp.status());
            }
            last = HttpFetchException.ofStatus(resp.status());
        }
        throw last != null ? last
                : new HttpFetchException(HttpFetchException.Type.NETWORK, "unreachable");
    }

    /**
     * 第 attempt 次重试的等待时长：min(base·2^(attempt-1), cap)，加 ±25% 抖动防惊群。
     */
    long backoffMs(int attempt) {
        long capped = Math.min(baseBackoffMs << (attempt - 1), MAX_BACKOFF_MS);
        long jitter = Math.max(1, capped / 4);
        return Math.max(1, capped - jitter + random.nextLong(2 * jitter + 1));
    }
}

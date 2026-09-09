package com.ripple.dataprovider;

import com.ripple.dataprovider.api.HttpTransport;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试用 mock 传输：按序返回预置状态码与响应体，耗尽后重复最后一项；记录全部请求 URI。
 */
final class FakeTransport implements HttpTransport {

    private final List<Integer> statuses;
    private final List<String> bodies;
    private final List<URI> uris = new ArrayList<>();
    private int calls;

    FakeTransport(List<Integer> statuses, String body) {
        this(statuses, List.of(body));
    }

    FakeTransport(List<Integer> statuses, List<String> bodies) {
        this.statuses = List.copyOf(statuses);
        this.bodies = List.copyOf(bodies);
    }

    int calls() {
        return calls;
    }

    URI lastUri() {
        return uris.get(uris.size() - 1);
    }

    List<URI> uris() {
        return List.copyOf(uris);
    }

    @Override
    public Response get(URI uri) {
        uris.add(uri);
        int status = statuses.get(Math.min(calls, statuses.size() - 1));
        String body = bodies.get(Math.min(calls, bodies.size() - 1));
        calls++;
        return new Response(status, body);
    }
}

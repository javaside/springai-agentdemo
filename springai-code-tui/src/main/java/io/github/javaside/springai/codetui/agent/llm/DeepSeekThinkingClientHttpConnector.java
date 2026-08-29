package io.github.javaside.springai.codetui.agent.llm;

import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.http.client.reactive.ClientHttpResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.function.Function;

/** Rewrites only the finite JSON request body; response streaming remains delegated unchanged. */
final class DeepSeekThinkingClientHttpConnector implements ClientHttpConnector {

    private final ClientHttpConnector delegate;
    private final ThinkingConfig config;
    private final io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry visionRegistry;

    DeepSeekThinkingClientHttpConnector(ClientHttpConnector delegate, ThinkingConfig config,
                                        io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry visionRegistry) {
        this.delegate = delegate;
        this.config = config;
        this.visionRegistry = visionRegistry;
    }

    @Override
    public Mono<ClientHttpResponse> connect(HttpMethod method, URI uri,
            Function<? super ClientHttpRequest, Mono<Void>> requestCallback) {
        return delegate.connect(method, uri, request -> requestCallback.apply(new ClientHttpRequestDecorator(request) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                return DataBufferUtils.join(body).flatMap(joined -> {
                    byte[] bytes = new byte[joined.readableByteCount()];
                    joined.read(bytes);
                    DataBufferUtils.release(joined);
                    byte[] decorated = DeepSeekThinkingBodyCodec.decorateStreaming(bytes, config, visionRegistry);
                    getHeaders().setContentLength(decorated.length);
                    return getDelegate().writeWith(Mono.just(bufferFactory().wrap(decorated)));
                });
            }

            @Override
            public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                return writeWith(Flux.from(body).flatMapSequential(Function.identity()));
            }
        }));
    }
}

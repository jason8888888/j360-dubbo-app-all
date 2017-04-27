package me.j360.dubbo.trace.brave;


import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.rpc.*;
import com.github.kristofa.brave.*;
import com.github.kristofa.brave.http.BraveHttpHeaders;
import com.github.kristofa.brave.internal.Nullable;
import com.github.kristofa.brave.internal.Util;
import com.twitter.zipkin.gen.Endpoint;

import java.util.Collection;
import java.util.Collections;

import static com.google.common.base.Preconditions.checkNotNull;

@Activate(group = {Constants.CONSUMER})
public class BraveDubboClientFilter implements Filter {

    /** Creates a tracing interceptor with defaults. Use {@link #builder(Brave)} to customize. */
    public static BraveDubboClientFilter create(Brave brave) {
        return new Builder(brave).build();
    }

    public static Builder builder(Brave brave) {
        return new Builder(brave);
    }

    public static final class Builder {
        final Brave brave;
        Builder(Brave brave) { // intentionally hidden
            this.brave = Util.checkNotNull(brave, "brave");
        }
        public BraveDubboClientFilter build() {
            return new BraveDubboClientFilter(this);
        }
    }

    private final ClientRequestInterceptor clientRequestInterceptor;
    private final ClientResponseInterceptor clientResponseInterceptor;

    BraveDubboClientFilter(Builder b) { // intentionally hidden
        this.clientRequestInterceptor = b.brave.clientRequestInterceptor();
        this.clientResponseInterceptor = b.brave.clientResponseInterceptor();
    }


    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        if ("com.alibaba.dubbo.monitor.MonitorService".equals(invoker.getInterface().getName())) {
            return invoker.invoke(invocation);
        }
        RpcContext context = RpcContext.getContext();
        clientRequestInterceptor.handle(new DubboClientRequestAdapter(context, invocation));
        Result result = invoker.invoke(invocation);
        clientResponseInterceptor.handle(new DubboClientResponseAdapter(result));
        return result;
    }


    static final class DubboClientRequestAdapter implements ClientRequestAdapter {

        private final RpcContext context;
        private final Invocation invocation;

        public DubboClientRequestAdapter(RpcContext context,Invocation invocation) {
            this.context = checkNotNull(context);
            this.invocation = checkNotNull(invocation);
        }

        @Override
        public String getSpanName() {
            return context.getMethodName();
        }

        @Override
        public void addSpanIdToRequest(@Nullable SpanId spanId) {

            // 添加下游信息
            if (spanId == null) {
                ((RpcInvocation) invocation).setAttachment(BraveHttpHeaders.Sampled.getName(), "0");
            } else {
                ((RpcInvocation) invocation).setAttachment(BraveHttpHeaders.Sampled.getName(), "1");
                ((RpcInvocation) invocation).setAttachment(BraveHttpHeaders.TraceId.getName(), IdConversion.convertToString(spanId.traceId));
                ((RpcInvocation) invocation).setAttachment(BraveHttpHeaders.SpanId.getName(), IdConversion.convertToString(spanId.spanId));

                if (spanId.nullableParentId() != null) {
                    ((RpcInvocation) invocation).setAttachment(BraveHttpHeaders.ParentSpanId.getName(), IdConversion.convertToString(spanId.parentId));

                }
            }
        }

        @Override
        public Collection<KeyValueAnnotation> requestAnnotations() {
            return Collections.emptyList();
        }

        @Override
        public Endpoint serverAddress() {
            return null;
        }
    }

    static final class DubboClientResponseAdapter implements ClientResponseAdapter {

        private final Result result;

        public DubboClientResponseAdapter(Result result) {
            this.result = checkNotNull(result);
        }

        @Override
        public Collection<KeyValueAnnotation> responseAnnotations() {
            return result.getException() == null
                    ? Collections.<KeyValueAnnotation>emptyList()
                    : Collections.singletonList(KeyValueAnnotation.create(DubboKeys.DUBBO_EXCEPTION_NAME, result.getException().getMessage()));
        }
    }

}

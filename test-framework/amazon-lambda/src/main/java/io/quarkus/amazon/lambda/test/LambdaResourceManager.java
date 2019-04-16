package io.quarkus.amazon.lambda.test;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.amazon.lambda.runtime.FunctionError;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.undertow.Undertow;
import io.undertow.io.Receiver;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.util.HttpString;

public class LambdaResourceManager implements QuarkusTestResourceLifecycleManager {

    protected static final String QUARKUS_INTERNAL_AWS_LAMBDA_TEST_API = "quarkus-internal.aws-lambda.test-api";

    private volatile Undertow undertow;

    public static final int PORT = Integer.getInteger("quarkus-internal.aws-lambda.test-port", 5387);

    @Override
    public Map<String, String> start() {

        RoutingHandler routingHandler = new RoutingHandler(true);
        routingHandler.add("GET", "/2018-06-01/runtime/invocation/next", new HttpHandler() {
            @Override
            public void handleRequest(HttpServerExchange exchange) throws Exception {
                LambdaStartedNotifier.started = true;
                LambdaClient.Request req = null;
                while (req == null) {
                    req = LambdaClient.REQUEST_QUEUE.poll(100, TimeUnit.MILLISECONDS);
                    if (undertow == null || undertow.getWorker().isShutdown()) {
                        return;
                    }
                }
                exchange.getResponseHeaders().put(new HttpString("Lambda-Runtime-Aws-Request-Id"), req.id);
                exchange.getResponseSender().send(req.json);
            }
        });
        routingHandler.add("POST", "/2018-06-01/runtime/invocation/{req}/response", new HttpHandler() {
            @Override
            public void handleRequest(HttpServerExchange exchange) throws Exception {
                String id = exchange.getQueryParameters().get("req").getFirst();
                exchange.getRequestReceiver().receiveFullString(new Receiver.FullStringCallback() {
                    @Override
                    public void handle(HttpServerExchange exchange, String message) {
                        LambdaClient.REQUESTS.get(id).complete(message);
                    }
                });
            }
        });

        routingHandler.add("POST", "/2018-06-01/runtime/invocation/{req}/error", new HttpHandler() {
            @Override
            public void handleRequest(HttpServerExchange exchange) throws Exception {
                String id = exchange.getQueryParameters().get("req").getFirst();
                exchange.getRequestReceiver().receiveFullString(new Receiver.FullStringCallback() {
                    @Override
                    public void handle(HttpServerExchange exchange, String message) {
                        ObjectMapper mapper = new ObjectMapper();
                        try {
                            FunctionError result = mapper.readerFor(FunctionError.class).readValue(message);

                            LambdaClient.REQUESTS.get(id).completeExceptionally(
                                    new LambdaException(result.getErrorType(), result.getErrorMessage()));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
            }
        });
        routingHandler.add("POST", "/2018-06-01/runtime/init/error", new HttpHandler() {
            @Override
            public void handleRequest(HttpServerExchange exchange) throws Exception {
                exchange.getRequestReceiver().receiveFullString(new Receiver.FullStringCallback() {
                    @Override
                    public void handle(HttpServerExchange exchange, String message) {
                        ObjectMapper mapper = new ObjectMapper();
                        try {
                            FunctionError result = mapper.readerFor(FunctionError.class).readValue(message);
                            LambdaClient.problem = new LambdaException(result.getErrorType(), result.getErrorMessage());
                            LambdaStartedNotifier.started = true;
                            for (Map.Entry<String, CompletableFuture<String>> e : LambdaClient.REQUESTS.entrySet()) {
                                e.getValue().completeExceptionally(LambdaClient.problem);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
            }
        });
        undertow = Undertow.builder().addHttpListener(PORT, "localhost")
                .setHandler(new BlockingHandler(routingHandler))
                .build();
        undertow.start();
        return Collections.singletonMap(QUARKUS_INTERNAL_AWS_LAMBDA_TEST_API, "localhost:" + PORT);
    }

    @Override
    public void stop() {
        undertow.stop();
        undertow = null;

    }
}

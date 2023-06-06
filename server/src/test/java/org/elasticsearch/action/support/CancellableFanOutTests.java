/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.support;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.tasks.CancellableTask;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.tasks.TaskCancelHelper;
import org.elasticsearch.tasks.TaskCancelledException;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.ReachabilityChecker;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.oneOf;
import static org.hamcrest.Matchers.startsWith;

public class CancellableFanOutTests extends ESTestCase {

    public void testFanOutWithoutCancellation() {
        final var task = randomFrom(
            new Task(1, "test", "test", "", TaskId.EMPTY_TASK_ID, Map.of()),
            new CancellableTask(1, "test", "test", "", TaskId.EMPTY_TASK_ID, Map.of()),
            null
        );
        final var future = new PlainActionFuture<String>();

        final var itemListeners = new HashMap<String, ActionListener<String>>();
        final var finalFailure = randomBoolean();

        new CancellableFanOut<String, String, String>() {
            int counter;

            @Override
            protected void sendItemRequest(String item, ActionListener<String> listener) {
                itemListeners.put(item, listener);
            }

            @Override
            protected void onItemResponse(String item, String itemResponse) {
                assertThat(item, oneOf("a", "c"));
                assertEquals(item + "-response", itemResponse);
                counter += 1;
            }

            @Override
            protected void onItemFailure(String item, Exception e) {
                assertEquals("b", item);
                counter += 1;
            }

            @Override
            protected String onCompletion() {
                assertEquals(3, counter);
                if (finalFailure) {
                    throw new ElasticsearchException("failed");
                } else {
                    return "completed";
                }
            }
        }.run(task, List.of("a", "b", "c").iterator(), future);

        itemListeners.remove("a").onResponse("a-response");
        assertFalse(future.isDone());
        itemListeners.remove("b").onFailure(new ElasticsearchException("b-response"));
        assertFalse(future.isDone());
        itemListeners.remove("c").onResponse("c-response");
        assertTrue(future.isDone());
        if (finalFailure) {
            assertEquals("failed", expectThrows(ElasticsearchException.class, future::actionGet).getMessage());
        } else {
            assertEquals("completed", future.actionGet());
        }
    }

    public void testReleaseOnCancellation() {
        final var task = new CancellableTask(1, "test", "test", "", TaskId.EMPTY_TASK_ID, Map.of());
        final var future = new PlainActionFuture<String>();

        final var itemListeners = new HashMap<String, ActionListener<String>>();
        final var handledItemResponse = new AtomicBoolean();

        final var reachabilityChecker = new ReachabilityChecker();
        reachabilityChecker.register(new CancellableFanOut<String, String, String>() {
            @Override
            protected void sendItemRequest(String item, ActionListener<String> listener) {
                itemListeners.put(item, listener);
            }

            @Override
            protected void onItemResponse(String item, String itemResponse) {
                assertEquals("a", item);
                assertEquals("a-response", itemResponse);
                assertTrue(handledItemResponse.compareAndSet(false, true));
            }

            @Override
            protected void onItemFailure(String item, Exception e) {
                fail(item);
            }

            @Override
            protected String onCompletion() {
                throw new AssertionError("onCompletion");
            }
        }).run(task, List.of("a", "b", "c").iterator(), future);

        itemListeners.remove("a").onResponse("a-response");
        assertTrue(handledItemResponse.get());
        reachabilityChecker.checkReachable();

        TaskCancelHelper.cancel(task, "test");
        reachabilityChecker.ensureUnreachable(); // even though we're still holding on to some item listeners.
        assertFalse(future.isDone());

        itemListeners.remove("b").onResponse("b-response");
        assertFalse(future.isDone());

        itemListeners.remove("c").onFailure(new ElasticsearchException("c-response"));
        assertTrue(itemListeners.isEmpty());
        assertTrue(future.isDone());
        expectThrows(TaskCancelledException.class, future::actionGet);
    }

    public void testConcurrency() throws InterruptedException {

        final var isProcessorThread = startsWith("processor-thread-");
        final var isCancelThread = equalTo("cancel-thread");

        final var items = randomList(1, 3, () -> randomAlphaOfLength(5));
        final var processorThreads = new Thread[items.size()];
        final var queue = new LinkedBlockingQueue<Runnable>();
        final var barrier = new CyclicBarrier(processorThreads.length + 1);
        for (int i = 0; i < processorThreads.length; i++) {
            processorThreads[i] = new Thread(() -> {
                try {
                    assertThat(Thread.currentThread().getName(), isProcessorThread);
                    final var item = Objects.requireNonNull(queue.poll(10, TimeUnit.SECONDS));
                    safeAwait(barrier);
                    item.run();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }, "processor-thread-" + i);
            processorThreads[i].start();
        }

        final var task = new CancellableTask(1, "test", "test", "", TaskId.EMPTY_TASK_ID, Map.of());

        final var cancelThread = new Thread(() -> {
            assertThat(Thread.currentThread().getName(), isCancelThread);
            safeAwait(barrier);
            TaskCancelHelper.cancel(task, "test");
        }, "cancel-thread");
        cancelThread.start();

        final var itemsProcessed = new AtomicInteger();
        final var completionLatch = new CountDownLatch(1);
        new CancellableFanOut<String, String, String>() {
            @Override
            protected void sendItemRequest(String s, ActionListener<String> listener) {
                queue.add(
                    randomBoolean() ? () -> listener.onResponse(s) : () -> listener.onFailure(new ElasticsearchException("sendItemRequest"))
                );
            }

            @Override
            protected void onItemResponse(String s, String response) {
                assertThat(Thread.currentThread().getName(), isProcessorThread);
                assertEquals(s, response);
                assertThat(itemsProcessed.incrementAndGet(), lessThanOrEqualTo(items.size()));
            }

            @Override
            protected void onItemFailure(String s, Exception e) {
                assertThat(Thread.currentThread().getName(), isProcessorThread);
                assertThat(e, instanceOf(ElasticsearchException.class));
                assertEquals("sendItemRequest", e.getMessage());
                assertThat(itemsProcessed.incrementAndGet(), lessThanOrEqualTo(items.size()));
            }

            @Override
            protected String onCompletion() {
                assertEquals(items.size(), itemsProcessed.get());
                assertThat(Thread.currentThread().getName(), isProcessorThread);
                if (randomBoolean()) {
                    return "finished";
                } else {
                    throw new ElasticsearchException("onCompletion");
                }
            }
        }.run(task, items.iterator(), new ActionListener<>() {
            @Override
            public void onResponse(String s) {
                assertEquals(items.size(), itemsProcessed.get());
                assertThat(Thread.currentThread().getName(), isProcessorThread);
                assertEquals("finished", s);
                completionLatch.countDown();
            }

            @Override
            public void onFailure(Exception e) {
                if (e instanceof TaskCancelledException) {
                    assertThat(Thread.currentThread().getName(), anyOf(isProcessorThread, isCancelThread));
                } else {
                    assertEquals(items.size(), itemsProcessed.get());
                    assertThat(Thread.currentThread().getName(), isProcessorThread);
                    assertThat(e, instanceOf(ElasticsearchException.class));
                    assertEquals("onCompletion", e.getMessage());
                }
                completionLatch.countDown();
            }
        });

        safeAwait(completionLatch);

        cancelThread.join();
        for (Thread processorThread : processorThreads) {
            processorThread.join();
        }
    }
}

package org.apache.kafka.retryableTest.mockCallbacks;

import org.apache.kafka.retryableTest.Pair;
import org.apache.kafka.streams.kstream.internals.RetryableForeachAction;

import java.util.LinkedList;
import java.util.List;

public class MockForeach<K, V> implements MockCallback<K, V> {
    private final List<Pair> receivedRecords = new LinkedList<>();
    private final RetryableForeachAction<K, V> mockForeach = (key, value) -> receivedRecords.add(new Pair<>(key, value));

    @Override
    public RetryableForeachAction<K, V> getCallback() { return mockForeach; }

    @Override
    public List<Pair> getReceivedRecords() { return receivedRecords; }
}

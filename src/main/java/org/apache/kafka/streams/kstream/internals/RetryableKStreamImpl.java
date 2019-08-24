package org.apache.kafka.streams.kstream.internals;

import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.kstream.RetryableKStream;
import org.apache.kafka.streams.kstream.internals.graph.ProcessorParameters;
import org.apache.kafka.streams.kstream.internals.graph.StatefulProcessorNode;
import org.apache.kafka.streams.kstream.internals.graph.StreamsGraphNode;
import org.apache.kafka.streams.kstream.internals.models.TaskAttempt;
import org.apache.kafka.streams.kstream.internals.serdes.TaskAttemptSerde;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;

import java.lang.reflect.Field;
import java.util.Objects;
import java.util.Set;

public class RetryableKStreamImpl<K, V> extends KStreamImpl<K, V> implements RetryableKStream<K, V> {
    // TODO Composition of provided stream instead of extension would avoid need for reflection and requirement for a
    //  KStreamImpl instead of a KStream. Well, not completely, since this Impl needs a builder... or could it add
    // to the processing topology via `process`?

    private static final String RETRIES_STORE_SUFFIX = "-RETRIES_STORE";
    private static final String RETRYABLE_FOREACH_PREFIX = "KSTREAM-RETRYABLE_FOREACH-";

    /*
     * Reflection is necessary since repartitionRequired is private with no getter, and KStreamImpl does not provide
     * a copy constructor.
     */
    private static boolean getRepartitionRequired(KStreamImpl stream){
        // Safer to assume that reparation is required.
        boolean repartitionRequired = true;
        try {
            Field repartitionRequiredField = KStreamImpl.class.getDeclaredField("repartitionRequired");
            repartitionRequiredField.setAccessible(true);
            repartitionRequired = (Boolean) repartitionRequiredField.get(stream);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
        }

        return repartitionRequired;
    }

    /*
     * Copy of constructor provided by KStreamImpl
     */
    RetryableKStreamImpl(String name, Serde<K> keySerde, Serde<V> valueSerde, Set<String> sourceNodes, boolean repartitionRequired, StreamsGraphNode streamsGraphNode, InternalStreamsBuilder builder) {
        super(name, keySerde, valueSerde, sourceNodes, repartitionRequired, streamsGraphNode, builder);
    }

    /*
     * Constructor that decorates a {@code KStreamImpl} with Retryable methods
     */
    public RetryableKStreamImpl(KStreamImpl<K, V> stream) {
        this(stream.name, stream.keySerde, stream.valSerde, stream.sourceNodes, getRepartitionRequired(stream), stream.streamsGraphNode, stream.builder);
    }

    @Override
    public void retryableForeach(final RetryableForeachAction<? super K, ? super V> action) {
        retryableForeach(action, builder.newProcessorName(NamedInternal.empty().name()));
    }

    @Override
    public void retryableForeach(final RetryableForeachAction<? super K, ? super V> action, String name) {
        Objects.requireNonNull(action, "action can't be null");
        Objects.requireNonNull(name, "name can't be null");
        final String nodeName = RETRYABLE_FOREACH_PREFIX.concat(name);
        final String retries_store_name = nodeName.concat(RETRIES_STORE_SUFFIX);


        StoreBuilder<KeyValueStore<Long, TaskAttempt>> retries_store_builder = getRetriesStoreBuilder(retries_store_name);

        String deadLetterPublisherNodeName = builder.newProcessorName(DeadLetterPublisherNode.DEAD_LETTER_PUBLISHER_NODE_PREFIX);

        final ProcessorParameters<? super K, ? super V> processorParameters = new ProcessorParameters<>(
                new KStreamRetryableForeach<>(retries_store_name, deadLetterPublisherNodeName, action),
                nodeName
        );

        final StatefulProcessorNode<? super K, ? super V> retryableForeachNode = new StatefulProcessorNode<>(nodeName, processorParameters, retries_store_builder);
        builder.addGraphNode(this.streamsGraphNode, retryableForeachNode);
        builder.addGraphNode(retryableForeachNode, DeadLetterPublisherNode.get(deadLetterPublisherNodeName));
    }

    /*
     * Common setup for retries state stores
     */
    private StoreBuilder<KeyValueStore<Long, TaskAttempt>> getRetriesStoreBuilder(String retries_store_name){
        // TODO Fix types of this key value store
        return Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(retries_store_name),
                Serdes.Long(), new TaskAttemptSerde());
    }
}

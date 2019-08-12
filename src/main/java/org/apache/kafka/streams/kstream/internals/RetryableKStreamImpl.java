package org.apache.kafka.streams.kstream.internals;

import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.kstream.RetryableKStream;
import org.apache.kafka.streams.kstream.internals.graph.ProcessorGraphNode;
import org.apache.kafka.streams.kstream.internals.graph.ProcessorParameters;
import org.apache.kafka.streams.kstream.internals.graph.StatefulProcessorNode;
import org.apache.kafka.streams.kstream.internals.graph.StreamsGraphNode;
import org.apache.kafka.streams.state.KeyValueBytesStoreSupplier;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.internals.KeyValueStoreBuilder;

import java.lang.reflect.Field;
import java.util.Objects;
import java.util.Set;

public class RetryableKStreamImpl<K, V> extends KStreamImpl<K, V> implements RetryableKStream<K, V> {
    // TODO Composition of provided stream instead of extention would avoid need for reflection and requirement for a
    //  KStreamImpl instead of a KStream

    private static final String RETRIES_STORE_SUFFIX = "-RETRIES_STORE";

    private static final String RETRYABLE_FOREACH_NAME = "RETRYABLEKSTREAM-RETRYABLE_FOREACH-";
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
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (NoSuchFieldException e) {
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
        retryableForeach(action, builder.newProcessorName(RETRYABLE_FOREACH_NAME));
    }

    @Override
    public void retryableForeach(final RetryableForeachAction<? super K, ? super V> action, String name) {
        Objects.requireNonNull(action, "action can't be null");
        Objects.requireNonNull(name, "name can't be null");
        final String retries_store_name = name.concat(RETRIES_STORE_SUFFIX);


        // TODO Fix types of this key value store
        StoreBuilder<KeyValueStore<String, String>> retries_store = Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(retries_store_name),
                Serdes.String(), Serdes.String());
        builder.internalTopologyBuilder.addStateStore(retries_store, name);


        StatefulProcessorNode
        final ProcessorParameters<? super K, ? super V> processorParameters = new ProcessorParameters<>(
                new RetryableKStreamRetryableForeach<>(retries_store_name, action),
                name
        );

        final ProcessorGraphNode<? super K, ? super V> retriableForeachNode = new ProcessorGraphNode<>(name, processorParameters);
        builder.addGraphNode(this.streamsGraphNode, retriableForeachNode);

    }
}
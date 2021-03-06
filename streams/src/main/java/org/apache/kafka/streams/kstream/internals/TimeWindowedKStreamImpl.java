/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.kstream.internals;

import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.kstream.Aggregator;
import org.apache.kafka.streams.kstream.Initializer;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Reducer;
import org.apache.kafka.streams.kstream.TimeWindowedKStream;
import org.apache.kafka.streams.kstream.Window;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.kstream.Windows;
import org.apache.kafka.streams.kstream.internals.graph.StreamsGraphNode;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.WindowBytesStoreSupplier;
import org.apache.kafka.streams.state.WindowStore;

import java.util.Objects;
import java.util.Set;

import static org.apache.kafka.streams.kstream.internals.KGroupedStreamImpl.AGGREGATE_NAME;
import static org.apache.kafka.streams.kstream.internals.KGroupedStreamImpl.REDUCE_NAME;

public class TimeWindowedKStreamImpl<K, V, W extends Window> extends AbstractStream<K> implements TimeWindowedKStream<K, V> {

    private final Windows<W> windows;
    private final Serde<K> keySerde;
    private final Serde<V> valSerde;
    private final GroupedStreamAggregateBuilder<K, V> aggregateBuilder;

    TimeWindowedKStreamImpl(final Windows<W> windows,
                            final InternalStreamsBuilder builder,
                            final Set<String> sourceNodes,
                            final String name,
                            final Serde<K> keySerde,
                            final Serde<V> valSerde,
                            final boolean repartitionRequired,
                            final StreamsGraphNode streamsGraphNode) {
        super(builder, name, sourceNodes, streamsGraphNode);
        this.valSerde = valSerde;
        this.keySerde = keySerde;
        this.windows = Objects.requireNonNull(windows, "windows can't be null");
        this.aggregateBuilder = new GroupedStreamAggregateBuilder<>(builder, keySerde, valSerde, repartitionRequired, sourceNodes, name, streamsGraphNode);
    }

    @Override
    public KTable<Windowed<K>, Long> count() {
        return doCount(Materialized.with(keySerde, Serdes.Long()));
    }

    @Override
    public KTable<Windowed<K>, Long> count(final Materialized<K, Long, WindowStore<Bytes, byte[]>> materialized) {
        Objects.requireNonNull(materialized, "materialized can't be null");

        // TODO: remove this when we do a topology-incompatible release
        // we used to burn a topology name here, so we have to keep doing it for compatibility
        if (new MaterializedInternal<>(materialized).storeName() == null) {
            builder.newStoreName(AGGREGATE_NAME);
        }

        return doCount(materialized);
    }

    private KTable<Windowed<K>, Long> doCount(final Materialized<K, Long, WindowStore<Bytes, byte[]>> materialized) {
        final MaterializedInternal<K, Long, WindowStore<Bytes, byte[]>> materializedInternal = new MaterializedInternal<>(materialized);
        materializedInternal.generateStoreNameIfNeeded(builder, AGGREGATE_NAME);

        if (materializedInternal.keySerde() == null) {
            materializedInternal.withKeySerde(keySerde);
        }
        if (materializedInternal.valueSerde() == null) {
            materializedInternal.withValueSerde(Serdes.Long());
        }

        return aggregateBuilder.build(
            new KStreamWindowAggregate<>(
                windows,
                materializedInternal.storeName(),
                aggregateBuilder.countInitializer,
                aggregateBuilder.countAggregator
            ),
            AGGREGATE_NAME,
            materialize(materializedInternal),
            materializedInternal.isQueryable()
        );
    }


    @Override
    public <VR> KTable<Windowed<K>, VR> aggregate(final Initializer<VR> initializer,
                                                  final Aggregator<? super K, ? super V, VR> aggregator) {
        return aggregate(initializer, aggregator, Materialized.with(keySerde, null));
    }

    @Override
    public <VR> KTable<Windowed<K>, VR> aggregate(final Initializer<VR> initializer,
                                                  final Aggregator<? super K, ? super V, VR> aggregator,
                                                  final Materialized<K, VR, WindowStore<Bytes, byte[]>> materialized) {
        Objects.requireNonNull(initializer, "initializer can't be null");
        Objects.requireNonNull(aggregator, "aggregator can't be null");
        Objects.requireNonNull(materialized, "materialized can't be null");
        final MaterializedInternal<K, VR, WindowStore<Bytes, byte[]>> materializedInternal = new MaterializedInternal<>(materialized);
        materializedInternal.generateStoreNameIfNeeded(builder, AGGREGATE_NAME);
        if (materializedInternal.keySerde() == null) {
            materializedInternal.withKeySerde(keySerde);
        }
        return aggregateBuilder.build(
            new KStreamWindowAggregate<>(
                windows,
                materializedInternal.storeName(),
                initializer,
                aggregator
            ),
            AGGREGATE_NAME,
            materialize(materializedInternal),
            materializedInternal.isQueryable());
    }

    @Override
    public KTable<Windowed<K>, V> reduce(final Reducer<V> reducer) {
        return reduce(reducer, Materialized.with(keySerde, valSerde));
    }

    @Override
    public KTable<Windowed<K>, V> reduce(final Reducer<V> reducer, final Materialized<K, V, WindowStore<Bytes, byte[]>> materialized) {
        Objects.requireNonNull(reducer, "reducer can't be null");
        Objects.requireNonNull(materialized, "materialized can't be null");

        final MaterializedInternal<K, V, WindowStore<Bytes, byte[]>> materializedInternal = new MaterializedInternal<>(materialized);
        materializedInternal.generateStoreNameIfNeeded(builder, REDUCE_NAME);

        if (materializedInternal.keySerde() == null) {
            materializedInternal.withKeySerde(keySerde);
        }
        if (materializedInternal.valueSerde() == null) {
            materializedInternal.withValueSerde(valSerde);
        }

        return aggregateBuilder.build(
            new KStreamWindowReduce<>(windows, materializedInternal.storeName(), reducer),
            REDUCE_NAME,
            materialize(materializedInternal),
            materializedInternal.isQueryable()
        );
    }

    @SuppressWarnings("deprecation") // continuing to support Windows#maintainMs/segmentInterval in fallback mode
    private <VR> StoreBuilder<WindowStore<K, VR>> materialize(final MaterializedInternal<K, VR, WindowStore<Bytes, byte[]>> materialized) {
        WindowBytesStoreSupplier supplier = (WindowBytesStoreSupplier) materialized.storeSupplier();
        if (supplier == null) {
            if (materialized.retention() != null) {
                // new style retention: use Materialized retention and default segmentInterval
                final long retentionPeriod = materialized.retention().toMillis();

                if ((windows.size() + windows.gracePeriodMs()) > retentionPeriod) {
                    throw new IllegalArgumentException("The retention period of the window store "
                                                           + name + " must be no smaller than its window size plus the grace period."
                                                           + " Got size=[" + windows.size() + "],"
                                                           + " grace=[" + windows.gracePeriodMs() + "],"
                                                           + " retention=[" + retentionPeriod + "]");
                }

                supplier = Stores.persistentWindowStore(
                    materialized.storeName(),
                    retentionPeriod,
                    windows.size(),
                    false
                );

            } else {
                // old style retention: use deprecated Windows retention/segmentInterval.

                // NOTE: in the future, when we remove Windows#maintainMs(), we should set the default retention
                // to be (windows.size() + windows.grace()). This will yield the same default behavior.

                if ((windows.size() + windows.gracePeriodMs()) > windows.maintainMs()) {
                    throw new IllegalArgumentException("The retention period of the window store "
                                                           + name + " must be no smaller than its window size plus the grace period."
                                                           + " Got size=[" + windows.size() + "],"
                                                           + " grace=[" + windows.gracePeriodMs() + "],"
                                                           + " retention=[" + windows.maintainMs() + "]");
                }

                supplier = Stores.persistentWindowStore(
                    materialized.storeName(),
                    windows.maintainMs(),
                    windows.size(),
                    false,
                    windows.segmentInterval()
                );
            }
        }
        final StoreBuilder<WindowStore<K, VR>> builder = Stores.windowStoreBuilder(
            supplier,
            materialized.keySerde(),
            materialized.valueSerde()
        );

        if (materialized.loggingEnabled()) {
            builder.withLoggingEnabled(materialized.logConfig());
        } else {
            builder.withLoggingDisabled();
        }

        if (materialized.cachingEnabled()) {
            builder.withCachingEnabled();
        }
        return builder;
    }
}
